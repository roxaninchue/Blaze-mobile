package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.0 deterministic cards: data that comes from a tool (unused apps with sizes, most-used apps, any list of rows the tool
 * wrote as "a · b · c" lines) is turned into a ```table block straight from the tool's own result, so the model doesn't retype
 * 40 rows of JSON (and break them, or drop the rows it couldn't size). The tool result tells the model the card is attached
 * ([NOTE]); [attach] puts the card under the final reply (replacing a table the model wrote anyway). Pure Kotlin; ToolCardsTest.
 */
object ToolCards {
    data class Card(val tool: String, val block: String, val rows: Int)

    const val NOTE = "[A table card with every row above is attached under your reply automatically. Write only a short comment " +
        "(what stands out, what to do); don't repeat the rows and don't write your own table.]"
    const val MIN_ROWS = 5
    /** Tools whose list results become cards (their rows are "a · b · c" lines). */
    val TOOLS = setOf("phone_health")
    const val MAX_ROWS = 60

    private val sep = Regex("""\s+·\s+""")

    /** Rows written as "a · b · c" lines (2+ columns), and the header line before them (if it ends with ':'). */
    fun rows(result: String): Pair<String, List<List<String>>> {
        val lines = result.lines()
        val rows = ArrayList<List<String>>(); var title = ""
        for (l in lines) {
            val t = l.trim().removePrefix("- ").trim()
            val cells = t.split(sep)
            if (cells.size >= 2 && t.length < 300) rows += cells.map { it.trim() }
            else if (rows.isEmpty() && t.endsWith(":")) title = t.removeSuffix(":").trim()
            else if (rows.isNotEmpty() && t.isNotEmpty() && !t.startsWith("[")) break
        }
        return title to rows
    }

    /** A card for this tool result, or null when it isn't a list worth a table. */
    fun card(name: String, args: JSONObject, result: String): Card? {
        val cols: List<String>? = when (name) {
            "phone_health" -> when (args.optString("detail")) { "unused_apps" -> listOf("App", "Size", "Last opened"); else -> null }
            else -> null
        }
        if (name !in TOOLS) return null
        val (title, rows) = rows(result)
        if (rows.size < MIN_ROWS) return null
        val w = rows.maxOf { it.size }.coerceAtMost(8)
        val heads = cols?.take(w) ?: emptyList()
        val o = JSONObject().put("title", title.ifBlank { "" }.take(80))
        if (heads.isNotEmpty()) o.put("columns", JSONArray(heads))
        // Every row, missing cells as "—" (never dropped); " 0 MB" (unknown size) shows as "—" too.
        o.put("rows", JSONArray(rows.take(MAX_ROWS).map { r -> JSONArray((0 until w).map { k -> r.getOrNull(k)?.takeIf { c -> c.isNotBlank() && !c.matches(Regex("""0(?:\.0)? ?MB""")) } ?: "\u2014" }) }))
        val more = rows.size - MAX_ROWS
        val block = "```table\n" + o.toString() + "\n```" + if (more > 0) "\n_${more} more not shown._" else ""
        return Card(name, block, rows.size)
    }

    private val tableFence = Regex("""```table[ \t]*\n[\s\S]*?(?:\n```|\z)""")

    /** The reply with the cards under it; a table the model wrote anyway is replaced by the first card (exact rows). */
    fun attach(reply: String, cards: List<Card>): String {
        if (cards.isEmpty()) return reply
        var t = reply
        val left = cards.toMutableList()
        if (tableFence.containsMatchIn(t)) { val c = left.removeAt(0); t = tableFence.replaceFirst(t, Regex.escapeReplacement(c.block)) }
        if (left.isEmpty()) return t
        return (t.trimEnd() + "\n\n" + left.joinToString("\n\n") { it.block }).trim()
    }
}
