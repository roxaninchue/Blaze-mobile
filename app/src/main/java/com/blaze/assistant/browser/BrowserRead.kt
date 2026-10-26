package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.29 browser tasks: what the model reads of a page ([BrowserScripts.READ]), formatted to fit a budget so nothing
 * downstream has to cut it (LongChat used to keep only its first 40 and last 20 lines, which dropped most pages' content).
 * Viewport first: the scroll position, an open dialog, headings, the text on screen, the elements on screen, then the
 * elements and text below. Pure (BrowserReadTest).
 */
object BrowserRead {
    /** The most a read (browser_open / browser_read) returns. */
    const val BUDGET = 3_500
    /** The page summary returned after a click / submit / choice / key. */
    const val AFTER = 1_200
    /** Keys browser_key may press. */
    val KEYS = listOf("Enter", "Escape", "Tab", "ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Backspace", "Space", "PageDown", "PageUp", "Home", "End")

    /** One element line: `[12] button "Add to basket"`, with its value, state, options or link; ↓ / ↑ when off screen. */
    fun item(o: JSONObject): String {
        val sb = StringBuilder()
        when (o.optInt("w", 0)) { 1 -> sb.append("\u2193 "); -1 -> sb.append("\u2191 ") }
        sb.append('[').append(o.optInt("id")).append("] ").append(o.optString("kind", "button")).append(" \"").append(o.optString("label")).append('"')
        val v = o.optString("value")
        if (v.isNotBlank() && v != o.optString("label")) sb.append(" = \"").append(v).append('"')
        if (o.has("checked")) sb.append(if (o.optBoolean("checked")) " (checked)" else " (not checked)")
        if (o.optBoolean("disabled")) sb.append(" (disabled)")
        o.optJSONArray("opts")?.let { a -> if (a.length() > 0) sb.append(" options: ").append((0 until a.length()).joinToString(" | ") { a.optString(it) }) }
        o.optString("href").takeIf { it.isNotBlank() }?.let { sb.append(" \u2192 ").append(it.take(90)) }
        return sb.toString()
    }

    /** "top", "34%" or "bottom" of the page, and whether there is more below. */
    fun scrollLine(y: Int, h: Int, vh: Int): String {
        val max = (h - vh).coerceAtLeast(0)
        if (max <= 4) return "Scroll: the whole page fits on screen."
        val where = when { y <= 4 -> "top"; y >= max - 4 -> "bottom"; else -> "${(y * 100 / max)}% down" }
        return "Scroll: $where (y $y of $max)." + if (y < max - 4) " More below: browser_scroll or browser_find." else ""
    }

    /**
     * The read as text, at most [budget] characters. [compact] (after an action) leaves out the text below and keeps
     * fewer elements.
     */
    fun format(r: JSONObject, budget: Int = BUDGET, compact: Boolean = false): String {
        val head = StringBuilder()
        head.append("Page: ").append(r.optString("title").take(100)).append(" \u2014 ").append(r.optString("url").take(160)).append('\n')
        head.append(scrollLine(r.optInt("y"), r.optInt("h"), r.optInt("vh"))).append('\n')
        r.optString("dialog").takeIf { it.isNotBlank() }?.let { head.append("Dialog open (deal with it first): \"").append(it.take(160)).append("\"\n") }
        r.optJSONArray("heads")?.let { a -> if (a.length() > 0) head.append("Headings: ").append((0 until minOf(a.length(), 8)).joinToString(" \u00b7 ") { a.optString(it) }).append('\n') }
        val items = r.optJSONArray("items") ?: JSONArray()
        val lines = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        val onEls = lines.filter { it.optInt("w", 0) == 0 }.map { item(it) }
        val offEls = lines.filter { it.optInt("w", 0) != 0 }.map { item(it) }
        val on = r.optString("on").trim()
        val below = if (compact) "" else r.optString("below").trim()

        val room = (budget - head.length - 60).coerceAtLeast(200)
        // Shares: on-screen text and elements first; what's left goes to the elements and text below.
        val textMax = if (compact) room * 2 / 5 else room * 3 / 10
        val sb = StringBuilder(head)
        sb.append("On screen:\n").append(clip(on.ifBlank { "(no text on screen)" }, textMax)).append('\n')
        sb.append("\nElements (on screen first; \u2193 below, \u2191 above):\n")
        val elementsEnd = budget - (if (compact || below.isBlank()) 20 else minOf(500, below.length + 40))
        var shown = 0
        for (l in onEls + offEls) {
            if (sb.length + l.length + 1 > elementsEnd || (compact && shown >= 25)) break
            sb.append(l).append('\n'); shown++
        }
        val more = lines.size - shown
        if (more > 0) sb.append("(+").append(more).append(" more elements: browser_find or browser_scroll)\n")
        if (below.isNotBlank()) {
            val left = budget - sb.length - 30
            if (left > 80) sb.append("\nBelow:\n").append(clip(below, left)).append('\n')
        }
        return clip(sb.toString().trimEnd(), budget)
    }

    /** [s] cut to [max] characters at a line or word end, with "…" when cut. */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf('\n', max - 1).takeIf { it > max * 3 / 4 } ?: s.lastIndexOf(' ', max - 1).takeIf { it > max * 3 / 4 } ?: (max - 1)
        return s.substring(0, cut).trimEnd() + "\u2026"
    }

    /** browser_find's answer: each hit's snippet and the nearest element to act on. */
    fun found(query: String, r: JSONObject): String {
        val hits = r.optJSONArray("hits") ?: JSONArray()
        if (hits.length() == 0) return "\u201c${query.take(60)}\u201d isn't on this page (text or element labels). Try other words, browser_scroll, or another page."
        val sb = StringBuilder("Found \u201c${query.take(60)}\u201d ${hits.length()}\u00d7 (the first is scrolled into view):\n")
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            sb.append(i + 1).append(". [").append(h.optString("where")).append("] ").append(h.optString("text").take(160))
            if (h.has("id")) sb.append(" \u2192 [").append(h.optInt("id")).append("] ").append(h.optString("kind")).append(" \"").append(h.optString("label").take(60)).append('"')
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** The plain line for a click / type / select result the page refused (null = it worked). */
    fun problem(r: JSONObject?, what: String): String? {
        if (r == null) return "Couldn't $what: the page didn't answer (it may still be loading). Call browser_read and try again."
        val l = r.optString("l").take(60)
        return when (r.optString("r")) {
            "ok" -> null
            "missing" -> "Couldn't $what: no such element on this page now. Call browser_read for the current numbers."
            "hidden" -> "Couldn't $what: \"$l\" is hidden right now (open its menu or section first, or browser_find it)."
            "disabled" -> "Couldn't $what: \"$l\" is disabled (fill the required fields first)."
            "covered" -> "Couldn't $what: \"$l\" is covered by \"${r.optString("by")}\"" + (r.optInt("byId", -1).takeIf { it >= 0 }?.let { " [$it]" } ?: "") +
                ". Close or accept that first (often a cookie banner or pop-up), then try again."
            "select" -> "\"$l\" is a dropdown: use browser_select with one of: " + opts(r) + "."
            "noteditable" -> "Couldn't $what: \"$l\" is a ${r.optString("kind")}, not a text field. Click it, or pick the right field from browser_read."
            "readonly" -> "Couldn't $what: \"$l\" is read-only."
            "mismatch" -> "Typed into \"$l\" but the page didn't keep it (it shows \"${r.optString("got")}\"" +
                (r.optString("type").takeIf { it.isNotBlank() }?.let { ", a $it field" } ?: "") + "). Check the format it expects."
            "notselect" -> "\"$l\" isn't a standard dropdown."
            "nooption" -> "No option like that in \"$l\". Options: " + opts(r) + "."
            "badkey" -> "That key isn't supported. Keys: ${KEYS.joinToString()}."
            else -> "Couldn't $what (${r.optString("r").take(40)})."
        }
    }

    private fun opts(r: JSONObject): String = r.optJSONArray("opts")?.let { a -> (0 until a.length()).joinToString(" | ") { a.optString(it) } }.orEmpty().ifBlank { "(none listed)" }
}
