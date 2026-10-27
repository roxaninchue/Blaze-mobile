package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.6 (Rocky's 0.46.5 screenshot: raw "<｜DSML｜function_calls>…" in a chat bubble): DeepSeek's native tool-call markup written
 * as text is run as real calls and never shown, whole or cut off, with ASCII or full-width bars; 30 mocked turns in a row.
 */
class DsmlCallsTest {
    private val B = "\uFF5C"   // full-width vertical bar, as DeepSeek writes it
    private fun tools(vararg names: String) = JSONArray().apply { names.forEach { n ->
        put(JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")).put("count", JSONObject().put("type", "integer"))))))
    } }
    private fun block(name: String, vararg params: Pair<String, String>, bar: String = B, close: Boolean = true) = buildString {
        append("<${bar}DSML${bar}function_calls>\n<${bar}DSML${bar}invoke name=\"$name\">\n")
        for ((k, v) in params) append("<${bar}DSML${bar}parameter name=\"$k\"${if (v.toIntOrNull() == null) " string=\"true\"" else ""}>$v</${bar}DSML${bar}parameter>\n")
        if (close) append("</${bar}DSML${bar}invoke>\n</${bar}DSML${bar}function_calls>")
    }

    @Test fun aDsmlBlockBecomesARealCallAndLeavesOnlyTheWords() {
        val text = "Let me check that.\n" + block("web_search", "query" to "weather in Lusaka", "count" to "3")
        val (calls, left) = CallScan.toCalls(text, tools("web_search", "memory_search"))!!
        assertEquals(1, calls.length())
        val f = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("web_search", f.getString("name"))
        val a = JSONObject(f.getString("arguments"))
        assertEquals("weather in Lusaka", a.getString("query")); assertEquals(3, a.getInt("count"))
        assertEquals("Let me check that.", left)
        assertFalse(CallScan.clean(text).contains("DSML"))
    }

    @Test fun asciiBarsTwoInvokesAndStrayTagsAreHandled() {
        val two = "<|DSML|function_calls>\n<|DSML|invoke name=\"web_search\"><|DSML|parameter name=\"query\" string=\"true\">a</|DSML|parameter></|DSML|invoke>\n" +
            "<|DSML|invoke name=\"memory_search\"><|DSML|parameter name=\"query\" string=\"true\">b</|DSML|parameter></|DSML|invoke>\n</|DSML|function_calls>"
        val (calls, _) = CallScan.toCalls(two, tools("web_search", "memory_search"))!!
        assertEquals(listOf("web_search", "memory_search"), (0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") })
        // a leftover closing tag alone is hidden, never a call
        val stray = "Done.\n</${B}DSML${B}function_calls>"
        assertEquals("Done.", CallScan.clean(stray))
    }

    @Test fun aCutOffBlockIsHeldBackWhileStreamingAndNeverShown() {
        val cut = "Searching now.\n" + block("web_search", "query" to "trains", close = false)
        assertEquals("Searching now.", CallScan.clean(cut, final = false))
        assertFalse(CallScan.clean(cut, final = true).contains("DSML"))
        for (partial in listOf("Hi <$B", "Hi <${B}DS", "Hi <${B}DSML${B}func", "Hi </${B}DSML${B}inv"))
            assertEquals("partial tag: $partial", "Hi", CallScan.clean(partial, final = false))
        // ordinary text with a < stays exactly as it was
        assertEquals("1 < 2 and a | b", CallScan.clean("1 < 2 and a | b", final = false))
    }

    @Test fun thirtyMockedTurnsInARowNeverLeakMarkup() {
        val all = tools("web_search", "memory_search", "set_alarm")
        var ran = 0
        repeat(30) { i ->
            val reply = when (i % 5) {
                0 -> "Turn $i.\n" + block("web_search", "query" to "q$i")
                1 -> "Turn $i.\n" + block("memory_search", "query" to "m$i", bar = "|")
                2 -> "Turn $i: just words, no call."
                3 -> "Turn $i.\n" + block("set_alarm", "query" to "07:00", "count" to "$i")
                else -> "Turn $i.\n<${B}DSML${B}invoke name=\"web_search\"><${B}DSML${B}parameter name=\"query\" string=\"true\">t$i</${B}DSML${B}parameter></${B}DSML${B}invoke>"   // no wrapper
            }
            // while streaming, every prefix the bubble shows is clean too
            var k = 0
            while (k < reply.length) { k = minOf(reply.length, k + 23); val shown = CallScan.clean(reply.substring(0, k), final = false)
                assertTrue("turn $i leaked while streaming: $shown", !shown.contains("DSML") && !shown.contains("<\uFF5C")) }
            val r = CallScan.toCalls(reply, all)
            val words = r?.second ?: CallScan.clean(reply)
            if (r != null) ran += r.first.length()
            assertTrue("turn $i leaked: $words", !words.contains("DSML") && !words.contains("function_calls") && !words.contains("invoke"))
            assertTrue(words.startsWith("Turn $i"))
        }
        assertEquals(24, ran)
    }
}
