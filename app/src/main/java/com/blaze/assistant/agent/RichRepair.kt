package com.blaze.assistant.agent

import com.blaze.assistant.ui.RichJson
import com.blaze.assistant.ui.RichKit

/**
 * 0.46.0 rich blocks right on the first try: a reply whose rich block (```table / chart / choices / form / compare / tool) still
 * can't be read after RichJson's repairs gets ONE silent re-ask for just that block, with the error, before the reply settles;
 * the fixed block is spliced in ([splice]). Rich turns also run at a lower temperature ([TEMPERATURE]). Pure; RichRepairTest.
 */
object RichRepair {
    const val TEMPERATURE = 0.2

    data class Broken(val kind: String, val raw: String, val body: String, val error: String)

    private val fence = Regex("""(?m)^[ \t]*```[ \t]*([A-Za-z]+)[ \t]*\n([\s\S]*?)\n[ \t]*```[ \t]*$""")

    /** The user's ask (or a tool's data) is likely to come back as a table / chart / choices card. */
    private val richAsk = Regex("""\b(table|chart|graph|plot|compare|comparison|vs\.?|versus|list (?:all|every|my)|breakdown|spreadsheet|columns?|rows?|ranking|which apps|unused apps|storage|biggest|top \d+)\b""", RegexOption.IGNORE_CASE)
    fun richTurn(userText: String): Boolean = richAsk.containsMatchIn(userText)

    fun parses(kind: String, body: String): Boolean = try {
        when (kind) {
            "table" -> RichKit.table(body) != null; "chart" -> RichKit.chart(body) != null; "choices" -> RichKit.choices(body) != null
            "form" -> RichKit.form(body) != null; "compare" -> RichKit.compare(body) != null; "tool" -> RichKit.tool(body) != null
            "apps" -> RichKit.apps(body) != null; else -> true
        }
    } catch (_: Exception) { false }

    /** Closed rich blocks in [reply] that can't be drawn even after the lenient repairs. */
    fun broken(reply: String): List<Broken> = fence.findAll(RichJson.fixSource(reply)).mapNotNull { m ->
        val kind = RichKit.LANGS[m.groupValues[1].lowercase()] ?: return@mapNotNull null
        val body = m.groupValues[2]
        if (parses(kind, body)) null
        else Broken(kind, m.value, body, if (RichJson.parse(body) == null) "the JSON is invalid or cut off" else "the JSON has the wrong shape for a $kind block")
    }.toList()

    /** The one re-ask: just the block, fixed, nothing else. */
    fun prompt(b: Broken): String = "This ```${b.kind} block from your reply can't be drawn: ${b.error}. Write the corrected block only: one ```${b.kind} fence " +
        "with complete, valid JSON (double quotes, every row closed, no comments), the same data, nothing before or after it.\n\n" + b.body.take(6000)

    /** The fixed fence from the model's answer, or null when it still doesn't parse. */
    fun fixedBlock(kind: String, answer: String): String? {
        val m = fence.find(RichJson.fixSource(answer)) ?: return null
        val k = RichKit.LANGS[m.groupValues[1].lowercase()] ?: return null
        if (k != kind || !parses(kind, m.groupValues[2])) return null
        return "```$kind\n" + m.groupValues[2].trim() + "\n```"
    }

    /** [reply] with the broken block replaced by [fixed] (the rest untouched). */
    fun splice(reply: String, b: Broken, fixed: String): String {
        val src = RichJson.fixSource(reply)
        return if (src.contains(b.raw)) src.replace(b.raw, fixed) else src
    }
}
