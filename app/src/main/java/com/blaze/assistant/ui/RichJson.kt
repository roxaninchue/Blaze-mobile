package com.blaze.assistant.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.0 rich blocks that always draw. Models (free routes especially) write rich JSON that is nearly right: the kind word
 * inside the fence ("```\ntable {…}" or an inline `table {…}`), single quotes, unquoted keys, or a 42-row table cut off mid-row.
 * Before, any of those showed the raw JSON in a grey code box. [parse] repairs what it can (dropping only an incomplete last
 * element); [kindPrefix] finds a kind word written in front of the JSON; [salvage] turns anything still broken into readable
 * text (rows recovered when possible), never raw JSON. Pure Kotlin; RichJsonTest.
 */
object RichJson {
    /** Lenient parse: smart quotes, trailing commas, single quotes, unquoted keys, truncated tails. Null when nothing usable. */
    fun parse(body: String): Any? {
        var t = body.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) return null
        t = t.replace('\u201C', '"').replace('\u201D', '"').replace('\u2018', '\'').replace('\u2019', '\'')
        val start = t.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        t = t.substring(start)
        strict(t)?.let { return it }
        val norm = normalize(t)
        strict(norm)?.let { return it }
        return repairTail(norm)
    }

    /** True when the body is a whole JSON value already (no tail repair needed): a streaming block can draw now. */
    fun complete(body: String): Boolean {
        val t0 = body.trim().let { b -> kindPrefix(b)?.second ?: b }.replace('\u201C', '"').replace('\u201D', '"')
        val i = t0.indexOfFirst { it == '{' || it == '[' }
        if (i < 0) return false
        val t = t0.substring(i)
        return strict(t) != null || strict(normalize(t)) != null
    }

    private fun strict(t: String): Any? = try {
        val s = t.replace(Regex(""",\s*([}\]])"""), "$1")
        when (s.firstOrNull()) { '{' -> JSONObject(s); '[' -> JSONArray(s); else -> null }
    } catch (_: Exception) { null }

    /** Single-quoted strings -> double-quoted; unquoted keys quoted; text after the top-level value dropped. */
    fun normalize(t: String): String {
        val sb = StringBuilder(t.length + 16)
        var i = 0; var depth = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                ch == '"' || ch == '\'' -> {
                    val q = ch; sb.append('"'); i++
                    while (i < t.length && t[i] != q) {
                        val c = t[i]
                        if (c == '\\' && i + 1 < t.length) { val n = t[i + 1]; if (q == '\'' && n == '\'') sb.append('\'') else { sb.append(c); sb.append(n) }; i += 2; continue }
                        if (q == '\'' && c == '"') sb.append("\\\"") else if (c == '\n') sb.append("\\n") else sb.append(c)
                        i++
                    }
                    if (i < t.length) { sb.append('"'); i++ }
                    continue
                }
                ch == '{' || ch == '[' -> { depth++; sb.append(ch) }
                ch == '}' || ch == ']' -> { depth--; sb.append(ch); if (depth == 0) return sb.toString() }
                (ch.isLetter() || ch == '_') && (sb.trimEnd().lastOrNull() == '{' || sb.trimEnd().lastOrNull() == ',') -> {
                    // An unquoted key (or a bare word value after a comma in an array, left as is unless a colon follows).
                    var j = i; while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '-')) j++
                    var k = j; while (k < t.length && t[k] == ' ') k++
                    val word = t.substring(i, j)
                    if (k < t.length && t[k] == ':' && word !in setOf("true", "false", "null")) sb.append('"').append(word).append('"') else sb.append(word)
                    i = j; continue
                }
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * A cut-off value: close what is open. First try closing at the very end (when it didn't stop inside a string); then cut back to each
     * earlier comma outside a string (dropping the incomplete element after it) and close the containers open there.
     */
    fun repairTail(t: String): Any? {
        data class Cut(val at: Int, val stack: String)
        val cuts = ArrayList<Cut>()
        val stack = StringBuilder(); var inStr = false; var i = 0
        while (i < t.length) {
            val c = t[i]
            if (inStr) { if (c == '\\') i++ else if (c == '"') inStr = false }
            else when (c) {
                '"' -> inStr = true
                '{', '[' -> stack.append(c)
                '}', ']' -> { if (stack.isNotEmpty()) stack.setLength(stack.length - 1); if (stack.isNotEmpty()) cuts += Cut(i + 1, stack.toString()) }
                ',' -> cuts += Cut(i, stack.toString())
            }
            i++
        }
        fun close(s: String) = s.reversed().map { if (it == '{') '}' else ']' }.joinToString("")
        // 1) Only closers missing (the text didn't stop inside a string): close at the end.
        if (!inStr) strict(t.trimEnd().removeSuffix(",") + close(stack.toString()))?.let { return it }
        // 2) Cut back to each earlier comma / closed element, newest first.
        for (cut in cuts.asReversed().take(60)) {
            val head = t.substring(0, cut.at).trimEnd().removeSuffix(",")
            strict(head + close(cut.stack))?.let { return it }
        }
        return null
    }

    /** "table {…}" / "chart\n{…}" / "`table {…}`": the rich kind written before the JSON, and the JSON after it. */
    private val prefixRe = Regex("""^\s*`?\s*([A-Za-z]+)\s*[:\-]?\s*(?=[{\[])""")
    fun kindPrefix(body: String): Pair<String, String>? {
        val m = prefixRe.find(body) ?: return null
        val kind = RichKit.LANGS[m.groupValues[1].lowercase()] ?: return null
        return kind to body.substring(m.range.last + 1).trim().removeSuffix("`").trim()
    }

    /** A line of prose that is really a rich block: `table {…}` or table {…} on its own (possibly to the end of the text). */
    val inlineRe = Regex("""(?m)^[ \t]*`?[ \t]*(table|chart|choices|buttons|form|compare|tool|calculator|apps)[ \t]*[:\-]?[ \t]*(\{[\s\S]*)""", RegexOption.IGNORE_CASE)

    /**
     * Readable text for a block that still can't be parsed: a table's rows recovered one by one (each complete [...] row),
     * or the plain strings in it. Never the raw JSON.
     */
    fun salvage(kind: String, body: String): String {
        val title = Regex(""""(?:title|caption|prompt|question)"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1).orEmpty()
        if (kind == "table" || kind == "compare") {
            val cols = Regex(""""(?:columns|headers|header)"\s*:\s*\[([^\]]*)]""").find(body)?.groupValues?.get(1)?.let { strings(it) }.orEmpty()
            val rowsPart = body.substringAfter("\"rows\"", "")
            val rows = Regex("""\[((?:\s*"(?:[^"\\]|\\.)*"\s*,?|\s*-?\d[\d.]*\s*,?|\s*(?:true|false|null)\s*,?)+)\s*]""").findAll(rowsPart).map { strings(it.groupValues[1]) }
                .filter { it.isNotEmpty() }.toList()
            if (rows.isNotEmpty()) {
                val w = maxOf(cols.size, rows.maxOf { it.size })
                val head = cols.ifEmpty { List(w) { "" } }.let { it + List(w - it.size) { "" } }
                return (if (title.isNotBlank()) "**$title**\n\n" else "") +
                    "| " + head.joinToString(" | ") + " |\n|" + "---|".repeat(w) + "\n" +
                    rows.joinToString("\n") { r -> "| " + (r + List(w - r.size) { "" }).joinToString(" | ") { it.ifBlank { "\u2014" }.replace("|", "/") } + " |" }
            }
        }
        val words = strings(body).filter { it.length > 1 && !it.equals(title, true) }.take(40)
        return listOfNotNull(title.takeIf { it.isNotBlank() }?.let { "**$it**" }, words.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" })
            .joinToString("\n\n").ifBlank { "(This card couldn't be drawn.)" }
    }

    private fun strings(s: String): List<String> = Regex(""""((?:[^"\\]|\\.)*)"(?!\s*:)|(-?\d+(?:\.\d+)?)""").findAll(s).map { m ->
        if (m.groupValues[1].isNotEmpty() || m.value.startsWith("\"")) m.groupValues[1].replace("\\\"", "\"").replace("\\n", " ") else m.groupValues[2] }.toList()

    /** Index just past the balanced JSON value starting at [from] (a '{' or '['), or -1 when it never closes. */
    fun valueEnd(t: String, from: Int): Int {
        var depth = 0; var inStr: Char? = null; var i = from
        while (i < t.length) {
            val c = t[i]
            if (inStr != null) { if (c == '\\') i++ else if (c == inStr) inStr = null }
            else when (c) { '"', '\'' -> inStr = c; '{', '[' -> depth++; '}', ']' -> { depth--; if (depth == 0) return i + 1 } }
            i++
        }
        return -1
    }

    private val fence = Regex("""(?m)^([ \t]*)```[ \t]*([A-Za-z]*)[ \t]*\n([\s\S]*?)(?:\n[ \t]*```[ \t]*$|\z)""")

    /**
     * The reply's source with rich blocks written the wrong way put right, before it is split into cards:
     *  - "```\ntable {…}\n```" / "```json\ntable {…}" -> "```table\n{…}\n```";
     *  - "```table\ntable {…}" -> the repeated kind word dropped;
     *  - a prose line "`table {…}`" or "table {…}" -> a ```table fence (the JSON runs to its closing brace, or to the end).
     */
    fun fixSource(src: String): String {
        if (!src.contains('{')) return src
        var out = fence.replace(src) { m ->
            val lang = m.groupValues[2].lowercase(); val body = m.groupValues[3]
            val pre = kindPrefix(body)
            val closed = m.value.trimEnd().endsWith("```") && m.value.trimEnd().length > 3 + m.groupValues[1].length + m.groupValues[2].length
            val end = if (closed) "\n" + m.groupValues[1] + "```" else ""
            when {
                pre != null && (lang.isEmpty() || lang == "json" || lang == "text" || RichKit.LANGS[lang] == pre.first) ->
                    m.groupValues[1] + "```" + pre.first + "\n" + pre.second + end
                else -> m.value
            }
        }
        // Prose lines that are a rich block (only outside fences).
        if (inlineRe.containsMatchIn(out) && !out.contains("```" )) {
            val m = inlineRe.find(out)!!
            val kind = RichKit.LANGS[m.groupValues[1].lowercase()] ?: return out
            val jsonStart = m.range.first + m.value.indexOf('{')
            val e = valueEnd(out, jsonStart)
            val json = if (e < 0) out.substring(jsonStart).trimEnd().removeSuffix("`") else out.substring(jsonStart, e)
            val rest = if (e < 0) "" else out.substring(e).trimStart('`', ' ', '\t')
            out = out.substring(0, m.range.first) + "```" + kind + "\n" + json.trim() + "\n```\n" + rest
        }
        return out
    }
}
