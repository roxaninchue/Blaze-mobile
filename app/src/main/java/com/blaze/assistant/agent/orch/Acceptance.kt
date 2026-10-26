package com.blaze.assistant.agent.orch

import org.json.JSONObject

/**
 * 0.47.0 acceptance checklist (spec §2.1). Each task gets 1–5 checks from its request; a check is only "met" with a
 * proof: a SCREENSHOT taken after the action, a READBACK of the changed state (alarm list, file, calendar), or an API
 * result carrying an id. Before Blaze says "done", [verify] runs: unproven checks are reported plainly and the reply may
 * not claim them ([honest]). Pure (AcceptanceTest).
 */
object Acceptance {
    enum class Proof { NONE, SCREENSHOT, READBACK, API }

    data class Check(val id: String, val what: String, val proof: Proof = Proof.NONE, val evidence: String = "") {
        val met get() = proof != Proof.NONE && evidence.isNotBlank()
        fun toJson(): JSONObject = JSONObject().put("id", id).put("what", what).put("proof", proof.name).put("evidence", evidence)
        companion object {
            fun fromJson(o: JSONObject) = Check(o.optString("id"), o.optString("what"),
                runCatching { Proof.valueOf(o.optString("proof")) }.getOrDefault(Proof.NONE), o.optString("evidence"))
        }
    }

    private val clause = Regex("""(?i)\s*(?:,\s*(?:and\s+)?then\s+|\s+and\s+then\s+|;\s*|\.\s+|\s+then\s+|,?\s+and\s+(?=(?:send|text|set|add|create|save|book|find|open|call|email|delete|remind|play|install|write|fix|run|move|rename|turn|schedule|share|post|order|download|check|compare|reply)\b))""")
    private val actionVerb = Regex("""(?i)^(?:please\s+|can you\s+|could you\s+|now\s+)?(send|text|message|set|add|create|save|book|find|open|call|email|delete|remind|play|install|write|fix|run|move|rename|turn|schedule|share|post|order|download|check|compare|reply|search|look up|summari[sz]e|make|build|update|change|cancel|pay|translate|read)\b""")

    /** 1–5 checks from the request: one per action clause ("set an alarm for 6:30 and text Mum" → two). */
    fun checklist(request: String): List<Check> {
        val parts = request.split(clause).map { it.trim().trimEnd('.', '!', '?') }.filter { it.length >= 3 }
        val acts = parts.filter { actionVerb.containsMatchIn(it) }.ifEmpty { listOf(request.trim().trimEnd('.', '!', '?')) }
        return acts.take(5).mapIndexed { i, p -> Check("c${i + 1}", p.take(140)) }
    }

    /** The proof kind a tool result gives (null when it proves nothing). */
    fun proofOf(tool: String, result: String): Proof? {
        if (result.isBlank() || result.startsWith("Error", true) || result.contains("did NOT approve") || result.startsWith("Not ", true)) return null
        return when {
            tool in setOf("phone_screenshot", "look_at_screen", "browser_show") || result.contains("[screenshot]") -> Proof.SCREENSHOT
            tool in setOf("read_screen", "phone_read", "list_routines", "calendar_events", "gcal_events", "file_read", "file_list", "browser_read", "read_messages", "memory_search", "recall_memory") -> Proof.READBACK
            Regex("""(?i)\b(id|message id|event id|ref|reference|sha|#)\s*[:=]?\s*[A-Za-z0-9_-]{4,}""").containsMatchIn(result) -> Proof.API
            else -> null
        }
    }

    /** Marks the first unmet check whose words match the tool / result as proven. */
    fun prove(checks: List<Check>, tool: String, args: String, result: String): List<Check> {
        val p = proofOf(tool, result) ?: return checks
        val hay = (tool.replace('_', ' ') + " " + args + " " + result).lowercase()
        val idx = checks.indexOfFirst { c -> !c.met && words(c.what).let { w -> w.count { it in hay } >= if (verbFits(c.what, tool)) 1 else minOf(2, w.size.coerceAtLeast(1)) } }
        if (idx < 0) return checks
        return checks.mapIndexed { i, c -> if (i == idx) c.copy(proof = p, evidence = result.take(200)) else c }
    }

    private val verbTools = mapOf(
        Regex("(?i)^(text|message|sms|send|reply|whatsapp)\\b") to Regex("send|sms|text|reply|notif_reply|message"),
        Regex("(?i)^(email|mail)\\b") to Regex("gmail_send|email"),
        Regex("(?i)^(set|wake)\\b.*\\b(alarm|timer)\\b") to Regex("set_alarm|set_timer"),
        Regex("(?i)^(remind|schedule)\\b") to Regex("routine|schedule|calendar|gcal"),
        Regex("(?i)^(call|ring)\\b") to Regex("call|dial"),
        Regex("(?i)^(save|write|create)\\b.*\\b(file|note|document)\\b") to Regex("file_write|file_edit|export_file"),
        Regex("(?i)^(delete|remove)\\b") to Regex("delete|remove|forget"),
    )
    /** The check's verb matches what the tool does (then one shared word, like the contact, is enough). */
    private fun verbFits(what: String, tool: String) = verbTools.any { (v, t) -> v.containsMatchIn(what.trim()) && t.containsMatchIn(tool) }

    private val stop = setOf("the", "and", "for", "with", "to", "a", "an", "my", "me", "please", "then", "at", "on", "in", "of", "that", "this", "it")
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9:]+")).filter { it.length >= 3 && it !in stop }

    data class Report(val allMet: Boolean, val met: List<Check>, val unmet: List<Check>) {
        /** The honest closing lines (empty when everything is proven). */
        fun lines(): String = if (unmet.isEmpty()) "" else "Not confirmed: " + unmet.joinToString("; ") { it.what } + "."
    }

    fun verify(checks: List<Check>): Report = Report(checks.all { it.met }, checks.filter { it.met }, checks.filterNot { it.met })

    private val doneClaim = Regex("""(?i)\b(done|all set|sent|it'?s sent|booked|completed|finished|i'?ve (sent|set|booked|deleted|installed|posted|saved|created)|successfully)\b""")

    /**
     * The reply as it may go out: when checks are unproven and the reply claims completion, the claim is kept but the
     * honest "Not confirmed: …" line is added (never silently claiming what wasn't verified).
     */
    fun honest(reply: String, r: Report): String = if (r.unmet.isEmpty() || !doneClaim.containsMatchIn(reply)) reply
        else reply.trimEnd() + "\n\n" + r.lines()
}
