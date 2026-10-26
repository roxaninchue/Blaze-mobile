/*
 * The 0-1000 normalized coordinate target follows google/artemis (Apache-2.0, Copyright 2026 Google LLC) and the
 * percentage tap of Minitap mobile-use as included in Artemis (third_party/mobile_use/controllers/unified_controller.py
 * tap_percentage, Apache-2.0, Copyright 2025-2026 Minitap, Inc., modifications Copyright 2026 Google LLC). Rewritten in
 * Kotlin for Blaze. See THIRD_PARTY_NOTICES.md.
 */
package com.blaze.assistant.phone.flash

import org.json.JSONArray
import org.json.JSONObject

/** One step the flash engine can take on the phone. [expect] = what the model says the screen should show afterwards. */
sealed class PhoneAct {
    open val expect: String = ""
    /** Same action again (loop guard key). */
    abstract fun key(): String
    abstract fun describe(): String

    data class Tap(val index: Int, val long: Boolean = false, override val expect: String = "", val label: String = "") : PhoneAct() {
        override fun key() = (if (long) "long:" else "tap:") + index
        override fun describe() = (if (long) "long-press [" else "tap [") + index + "]" + (if (label.isNotBlank()) " \"${label.take(40)}\"" else "")
    }
    /** A tap on the 0-1000 grid (visual fallback). */
    data class TapAt(val x: Int, val y: Int, val desc: String = "", val long: Boolean = false, override val expect: String = "") : PhoneAct() {
        override fun key() = "at:" + (x / 20) + "," + (y / 20)
        override fun describe() = "tap at [$x,$y]" + (if (desc.isNotBlank()) " (${desc.take(40)})" else "")
    }
    data class Type(val index: Int?, val text: String, val submit: Boolean = false, override val expect: String = "") : PhoneAct() {
        override fun key() = "type:" + (index ?: -1) + ":" + text.trim().lowercase()
        override fun describe() = "type \"${text.take(40)}\"" + (index?.let { " into [$it]" } ?: "") + (if (submit) " + enter" else "")
    }
    data class Scroll(val direction: String, val index: Int? = null, override val expect: String = "") : PhoneAct() {
        override fun key() = "scroll:" + direction + ":" + (index ?: -1)
        override fun describe() = "scroll $direction" + (index?.let { " [$it]" } ?: "")
    }
    data class Key(val name: String, override val expect: String = "") : PhoneAct() {
        override fun key() = "key:$name"
        override fun describe() = "press $name"
    }
    data class OpenApp(val app: String, override val expect: String = "") : PhoneAct() {
        override fun key() = "open:" + app.lowercase()
        override fun describe() = "open $app"
    }
    data class Wait(val ms: Long) : PhoneAct() {
        override fun key() = "wait"
        override fun describe() = "wait ${ms} ms"
    }
    data class Done(val answer: String) : PhoneAct() {
        override fun key() = "done"
        override fun describe() = "done"
    }
    data class Fail(val reason: String) : PhoneAct() {
        override fun key() = "fail"
        override fun describe() = "give up"
    }
}

/**
 * Reads the model's reply into a [PhoneAct]. Lenient: a JSON object anywhere in the reply (code fences, prose around it,
 * single quotes and unquoted keys as org.json allows), Artemis/Blaze action names and their synonyms, `target` as an
 * index or an [x, y] pair on the 0-1000 grid. Null = no usable action.
 */
object FlashParse {
    private val KEYS = mapOf("back" to "back", "go_back" to "back", "home" to "home", "go_home" to "home", "recents" to "recents",
        "enter" to "enter", "press_enter" to "enter", "search" to "enter")

    fun json(raw: String): JSONObject? {
        val s = raw.substringAfterLast("</think>")
        var start = s.indexOf('{')
        while (start >= 0) {
            var depth = 0; var inStr = false; var q = ' '; var esc = false
            for (i in start until s.length) {
                val c = s[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == q) inStr = false; continue }
                when (c) {
                    '"', '\'' -> { inStr = true; q = c }
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { try { return JSONObject(s.substring(start, i + 1)) } catch (_: Exception) { }; break } }
                }
            }
            start = s.indexOf('{', start + 1)
        }
        return null
    }

    private fun point(o: JSONObject, k: String): Pair<Int, Int>? {
        val v = o.opt(k)
        val arr = when (v) {
            is JSONArray -> v
            is String -> Regex("""^\s*\[?\s*(-?\d+)\s*,\s*(-?\d+)\s*]?\s*$""").find(v)?.let { m -> JSONArray().put(m.groupValues[1].toInt()).put(m.groupValues[2].toInt()) }
            else -> null
        } ?: return null
        if (arr.length() != 2) return null
        val x = arr.optDouble(0, Double.NaN); val y = arr.optDouble(1, Double.NaN)
        if (x.isNaN() || y.isNaN()) return null
        return x.toInt().coerceIn(0, 1000) to y.toInt().coerceIn(0, 1000)
    }

    private fun index(o: JSONObject, vararg ks: String): Int? {
        for (k in ks) {
            val v = o.opt(k) ?: continue
            when (v) {
                is Number -> return v.toInt()
                is String -> v.trim().removePrefix("[").removeSuffix("]").trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    fun parse(raw: String): PhoneAct? {
        val o = json(raw) ?: return null
        val name = o.optString("action").ifBlank { o.optString("tool") }.ifBlank { o.optString("name") }.trim().lowercase().replace(' ', '_')
        val expect = o.optString("expect").ifBlank { o.optString("expected") }.trim()
        val label = o.optString("label").ifBlank { o.optString("target_label") }.trim()
        val long = o.optBoolean("long_press") || name == "long_press" || name == "long_click"
        return when (name) {
            "tap", "click", "press", "long_press", "long_click", "tap_element" -> {
                point(o, "target")?.let { (x, y) -> return PhoneAct.TapAt(x, y, o.optString("target_description").ifBlank { label }, long, expect) }
                point(o, "point")?.let { (x, y) -> return PhoneAct.TapAt(x, y, o.optString("target_description").ifBlank { label }, long, expect) }
                index(o, "target", "index", "element")?.let { PhoneAct.Tap(it, long, expect, label) }
            }
            "tap_at", "click_at", "tap_xy" -> (point(o, "target") ?: point(o, "point") ?: point(o, "xy"))?.let { (x, y) -> PhoneAct.TapAt(x, y, o.optString("target_description").ifBlank { label }, long, expect) }
            "type", "type_text", "input_text", "input" -> {
                val text = o.optString("text").ifBlank { o.optString("value") }
                if (text.isEmpty()) null else PhoneAct.Type(index(o, "target", "index", "element"), text, o.optBoolean("submit") || o.optBoolean("enter"), expect)
            }
            "scroll", "swipe" -> {
                val d = o.optString("direction").ifBlank { o.optString("dir") }.lowercase().ifBlank { "down" }
                if (d !in setOf("up", "down", "left", "right")) null else PhoneAct.Scroll(d, index(o, "target", "index", "element"), expect)
            }
            "key", "press_key", "press_button", "button" -> KEYS[o.optString("key").ifBlank { o.optString("button") }.lowercase()]?.let { PhoneAct.Key(it, expect) }
            in KEYS.keys -> PhoneAct.Key(KEYS.getValue(name), expect)
            "open_app", "launch", "launch_app", "manage_app" -> o.optString("app").ifBlank { o.optString("app_name") }.trim().takeIf { it.isNotEmpty() }?.let { PhoneAct.OpenApp(it, expect) }
            "wait", "wait_for_delay" -> PhoneAct.Wait((o.optDouble("seconds", 1.0).coerceIn(0.2, 5.0) * 1000).toLong())
            "done", "finish", "complete", "completed", "answer" -> PhoneAct.Done(o.optString("answer").ifBlank { o.optString("summary") }.ifBlank { o.optString("text") })
            "fail", "failed", "give_up", "impossible" -> PhoneAct.Fail(o.optString("reason").ifBlank { o.optString("answer") }.ifBlank { "No reason given." })
            "report_task_status" -> if (o.optString("status").lowercase().startsWith("complete")) PhoneAct.Done(o.optString("summary").ifBlank { o.optString("answer") })
                else PhoneAct.Fail(o.optString("reason").ifBlank { o.optString("summary") }.ifBlank { "No reason given." })
            else -> null
        }
    }
}
