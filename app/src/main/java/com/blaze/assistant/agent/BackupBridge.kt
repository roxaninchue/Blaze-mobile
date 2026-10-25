package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.26, pure (BackupBridgeTest): turns one Blaze model call (OpenAI chat messages + Blaze's tool schemas) into one
 * OpenCode server message, and OpenCode's reply back into an OpenAI-shaped assistant message.
 *
 * Why tools go through Blaze and not through OpenCode: OpenCode runs its own agent loop and would execute tools
 * itself, outside Blaze's confirm gates (ConfirmGate, screen-action OKs, risky-action prompts), step pills, tool cards
 * and verification. So every OpenCode tool is switched off ([config]: tools "*": false, permissions deny) and Blaze's
 * own tools are exposed to the model as a manifest in the system prompt with one strict call form,
 * `<tool_call>{"name": …, "arguments": {…}}</tool_call>` (the Hermes / Qwen form free models are trained on). The
 * reply is parsed with Blaze's existing parser ([ToolCalls.fromText]) into real `tool_calls`, so the agent loop runs
 * them exactly like a Blaze Cloud tool call: same gates, same cards, same results. Results go back in the next
 * call's transcript as `<tool_response>` blocks.
 *
 * Persona: Blaze's own system prompt (persona, memory, rules) is passed through as OpenCode's `system`, under a
 * custom "blaze" agent whose prompt is only the identity line. Nothing here names OpenCode to the model as itself.
 */
object BackupBridge {
    const val AGENT = "blaze"
    /** Newest-first transcript budget (characters) for one call; older turns are cut first. */
    const val MAX_TRANSCRIPT = 60_000
    private const val MAX_TOOL_DESC = 280
    private const val MAX_TOOL_PARAMS = 1_400
    private const val MAX_TOOL_RESULT = 6_000

    const val IDENTITY = "You are Blaze, the user's personal assistant on their phone. Answer as Blaze, in Blaze's voice. " +
        "Never call yourself OpenCode, a CLI, a coding agent or a model, and never mention which model or service answers."

    /** OpenCode's built-in tools, all off (the wildcard covers ones added in later versions). */
    val OFF_TOOLS = listOf("*", "bash", "edit", "write", "read", "grep", "glob", "list", "patch", "multiedit", "webfetch", "websearch",
        "codesearch", "todowrite", "todoread", "task", "skill", "lsp", "batch", "question")

    /** The backup can't read pictures (text turns only); a picture turn goes straight to the next step. */
    fun canServe(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val c = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) { val t = c.optJSONObject(j)?.optString("type").orEmpty(); if (t == "image_url" || t == "input_image" || t == "image") return false }
        }
        return true
    }

    private fun textOf(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { s -> s.isNotEmpty() } }.joinToString("\n")
        null, JSONObject.NULL -> ""
        else -> content.toString()
    }

    /** The tool manifest + the one call form. Empty when there are no tools. */
    fun toolProtocol(tools: JSONArray?): String {
        if (tools == null || tools.length() == 0) return ""
        val sb = StringBuilder("\n\n## Tools\nYou can use these tools. To use one, reply with one or more lines, each exactly:\n" +
            "<tool_call>{\"name\": \"tool_name\", \"arguments\": {…}}</tool_call>\n" +
            "and nothing else in that reply. You'll get each result back as <tool_response name=\"tool_name\">…</tool_response>; then carry on " +
            "(another tool call or your answer). Only use tools listed here, with valid JSON arguments. Never show tool syntax to the user in a normal answer.\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val f = t.optJSONObject("function") ?: t
            val name = f.optString("name")
            if (name.isBlank()) continue
            val desc = f.optString("description").replace('\n', ' ').take(MAX_TOOL_DESC)
            val params = (f.optJSONObject("parameters") ?: JSONObject()).toString().let { if (it.length > MAX_TOOL_PARAMS) it.take(MAX_TOOL_PARAMS) + "…" else it }
            sb.append("- ").append(name).append(": ").append(desc).append("\n  parameters: ").append(params).append('\n')
        }
        return sb.toString()
    }

    /** OpenCode's `system` for one call: the identity line, Blaze's own system messages, the tool protocol. */
    fun system(messages: JSONArray, tools: JSONArray?): String {
        val own = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .filter { it.optString("role") == "system" || it.optString("role") == "developer" }
            .map { textOf(it.opt("content")) }.filter { it.isNotBlank() }
        return (listOf(IDENTITY) + own).joinToString("\n\n") + toolProtocol(tools)
    }

    /** The conversation (everything but system) as one text, newest kept when over [max]. */
    fun transcript(messages: JSONArray, max: Int = MAX_TRANSCRIPT): String {
        val names = HashMap<String, String>()   // tool_call_id → tool name
        val turns = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system", "developer" -> {}
                "user" -> turns += "User: " + textOf(m.opt("content"))
                "assistant" -> {
                    val sb = StringBuilder("Blaze: ").append(textOf(m.opt("content")))
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null) for (j in 0 until calls.length()) {
                        val c = calls.optJSONObject(j) ?: continue
                        val f = c.optJSONObject("function") ?: continue
                        names[c.optString("id")] = f.optString("name")
                        val args = f.opt("arguments").let { a -> if (a is String) runCatching { JSONObject(a) }.getOrElse { JSONObject() } else a as? JSONObject ?: JSONObject() }
                        sb.append("\n<tool_call>").append(JSONObject().put("name", f.optString("name")).put("arguments", args)).append("</tool_call>")
                    }
                    turns += sb.toString().trimEnd()
                }
                "tool" -> {
                    val name = names[m.optString("tool_call_id")] ?: m.optString("name").ifBlank { "tool" }
                    val body = textOf(m.opt("content")).let { if (it.length > MAX_TOOL_RESULT) it.take(MAX_TOOL_RESULT) + "\n…[cut]" else it }
                    turns += "<tool_response name=\"$name\">\n$body\n</tool_response>"
                }
            }
        }
        // Newest first within the budget, then back in order. The latest turn always stays (cut to the budget if huge).
        val kept = ArrayDeque<String>()
        var n = 0
        for (t in turns.asReversed()) {
            if (kept.isNotEmpty() && n + t.length > max) break
            kept.addFirst(if (t.length > max) t.takeLast(max) else t); n += t.length
        }
        val head = if (kept.size < turns.size) "[earlier conversation left out]\n\n" else ""
        return head + kept.joinToString("\n\n") + "\n\nReply as Blaze to the latest message" + (if (turns.lastOrNull()?.startsWith("<tool_response") == true) " (use the tool results above)." else ".")
    }

    /** POST /session/{id}/message (or prompt_async) body. */
    fun messageBody(messages: JSONArray, tools: JSONArray?, model: String): JSONObject = JSONObject()
        .put("agent", AGENT)
        .put("model", JSONObject().put("providerID", BackupModels.PROVIDER).put("modelID", model))
        .put("system", system(messages, tools))
        .put("tools", JSONObject().apply { OFF_TOOLS.forEach { put(it, false) } })
        .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", transcript(messages))))

    /** OpenCode's free tier is used up for this phone (every free model shares it): stop trying them until it resets. */
    fun freeLimit(reason: String, message: String): Boolean = reason == "free_tier_limit" || "free usage" in message.lowercase()

    /** A failed reply: OpenCode puts the provider error on the assistant message (info.error). */
    data class Failure(val status: Int, val message: String)

    fun failure(info: JSONObject?): Failure? {
        val e = info?.optJSONObject("error") ?: return null
        val d = e.optJSONObject("data")
        val msg = (d?.optString("message")?.takeIf { it.isNotBlank() } ?: e.optString("name")).ifBlank { "backup error" }
        val st = d?.optInt("statusCode", 0)?.takeIf { it > 0 } ?: if (e.optString("name").contains("Auth", true)) 401 else 0
        return Failure(st, msg)
    }

    /** Text and reasoning of an assistant reply ({info, parts}). */
    fun replyText(resp: JSONObject): Pair<String, String> {
        val parts = resp.optJSONArray("parts") ?: return "" to ""
        val text = StringBuilder(); val think = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("synthetic") || p.optBoolean("ignored")) continue
            when (p.optString("type")) { "text" -> text.append(p.optString("text")); "reasoning" -> think.append(p.optString("text")) }
        }
        return text.toString() to think.toString()
    }

    private val selfName = Regex("""\b(I am|I'm|I’m|this is|my name is)\s+(?:an?\s+)?(?:OpenCode|opencode)\b""")

    /** The rare reply where a model still introduces itself by the server's name: it's Blaze. */
    fun cleanText(text: String): String = selfName.replace(text) { "${it.groupValues[1]} Blaze" }

    /** OpenAI assistant message: tool calls written in the reply become real `tool_calls` (Blaze runs them). */
    fun toOpenAI(text: String, tools: JSONArray?): JSONObject {
        val msg = JSONObject().put("role", "assistant")
        val clean = cleanText(text)
        val calls = if (tools != null && tools.length() > 0) ToolCalls.fromText(clean, tools) else null
        if (calls != null && calls.first.length() > 0) {
            val c = calls.first
            for (i in 0 until c.length()) c.optJSONObject(i)?.put("id", "bk_${System.nanoTime().toString(36)}_$i")
            msg.put("tool_calls", c)
            msg.put("content", calls.second.trim().ifBlank { null } ?: JSONObject.NULL)
        } else msg.put("content", clean.trim())
        return msg
    }

    // ---------------- the event stream (GET /event) ----------------

    sealed class Ev {
        data class Text(val text: String, val part: String = "") : Ev()
        data class Think(val text: String) : Ev()
        object Idle : Ev()
        data class Error(val failure: Failure) : Ev()
        /** OpenCode is retrying this call by itself (rate limit, free-tier limit): Blaze moves on instead of waiting. */
        data class Retry(val message: String, val reason: String, val next: Long) : Ev()
        object Other : Ev()
    }

    /**
     * One SSE event (its JSON `data`) for session [session]. [assistant] collects the ids of assistant messages (a
     * `message.updated` names the role), so the user's own text part (echoed as an update too) is never shown.
     */
    fun event(ev: JSONObject, session: String, assistant: MutableSet<String>): Ev {
        val props = ev.optJSONObject("properties") ?: return Ev.Other
        when (ev.optString("type")) {
            "message.updated" -> {
                val info = props.optJSONObject("info") ?: return Ev.Other
                if (info.optString("sessionID") != session || info.optString("role") != "assistant") return Ev.Other
                assistant += info.optString("id")
                return failure(info)?.let { Ev.Error(it) } ?: Ev.Other
            }
            "message.part.updated" -> {
                val p = props.optJSONObject("part") ?: return Ev.Other
                if (p.optString("sessionID") != session || p.optString("messageID") !in assistant) return Ev.Other
                if (p.optBoolean("synthetic")) return Ev.Other
                return when (p.optString("type")) { "text" -> Ev.Text(p.optString("text"), p.optString("id")); "reasoning" -> Ev.Think(p.optString("text")); else -> Ev.Other }
            }
            "session.idle" -> return if (props.optString("sessionID") == session) Ev.Idle else Ev.Other
            "session.status" -> {
                if (props.optString("sessionID") != session) return Ev.Other
                val st = props.optJSONObject("status") ?: return Ev.Other
                return when (st.optString("type")) {
                    "idle" -> Ev.Idle
                    "retry" -> Ev.Retry(st.optString("message"), st.optJSONObject("action")?.optString("reason").orEmpty(), st.optLong("next", 0))
                    else -> Ev.Other
                }
            }
            "session.error" -> {
                if (props.has("sessionID") && props.optString("sessionID") != session) return Ev.Other
                val e = props.optJSONObject("error") ?: return Ev.Other
                return Ev.Error(failure(JSONObject().put("error", e)) ?: Failure(0, "backup error"))
            }
        }
        return Ev.Other
    }

    // ---------------- the server's config ----------------

    /** opencode.json for the headless server: no updates, no sharing, every built-in tool off, the "blaze" agent. */
    fun config(defaultModel: String?): JSONObject = JSONObject()
        .put("\$schema", "https://opencode.ai/config.json")
        .put("autoupdate", false)
        .put("share", "disabled")
        .put("snapshot", false)
        .put("lsp", false)
        .put("formatter", false)
        .apply { if (!defaultModel.isNullOrBlank()) put("model", "${BackupModels.PROVIDER}/$defaultModel") }
        .put("tools", JSONObject().apply { OFF_TOOLS.forEach { put(it, false) } })
        .put("permission", JSONObject().put("edit", "deny").put("bash", "deny").put("webfetch", "deny"))
        .put("agent", JSONObject().put(AGENT, JSONObject()
            .put("description", "Blaze, the user's assistant")
            .put("mode", "primary")
            .put("prompt", IDENTITY)
            .put("tools", JSONObject().apply { OFF_TOOLS.forEach { put(it, false) } })))
}
