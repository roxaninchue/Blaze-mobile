package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.0 regression (phone screenshot, a Halo bubble): a free route with no native tool calling wrote
 * "Tool call: blaze_docs_search {…}", "Tool call: memory_search {…}", "Tool call: load_tools {"groups":["learn"]}" as text and
 * the app showed it. Labelled text-form calls are real calls now, and never shown (bubble, streaming tail, Halo lane).
 */
class TextToolCallLeakTest {
    private fun tool(n: String, vararg p: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { p.forEach { put(it, JSONObject().put("type", "string")) } })))
    private val all = JSONArray(listOf(tool("blaze_docs_search", "query"), tool("memory_search", "query"), tool("load_tools", "groups")))
    private val leaked = "Tool call: blaze_docs_search {\"query\":\"modular screens study coach\"}\n" +
        "Tool call: memory_search {\"query\":\"blaze update\"}\n" +
        "Tool call: load_tools {\"groups\":[\"learn\"]}"

    @Test fun labelledCallsBecomeRealCalls() {
        val (calls, rest) = TextCalls.toCalls(leaked, all)!!
        assertEquals(listOf("blaze_docs_search", "memory_search", "load_tools"), (0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") })
        assertEquals("[\"learn\"]", JSONObject(calls.getJSONObject(2).getJSONObject("function").getString("arguments")).getJSONArray("groups").toString())
        assertEquals("", rest)
    }

    @Test fun otherLabelShapes() {
        for (t in listOf("**Tool call:** memory_search {\"query\":\"x\"}", "tool_call: memory_search {\"query\":\"x\"}", "Calling: memory_search(query=\"x\")",
                "- Function call: memory_search {\"query\":\"x\"}", "Action - memory_search {\"query\":\"x\"}"))
            assertTrue(t, TextCalls.toCalls("Let me check.\n$t", all)?.first?.length() == 1)
    }

    @Test fun neverDisplayed() {
        assertEquals("Got it.", TextCalls.strip("Got it.\n$leaked", known = ToolCalls.names(all)))
        // Streaming: a half-written "Tool call: …" line is hidden until it can be run.
        assertEquals("Checking", TextCalls.strip("Checking\nTool call: blaze_docs_se", final = false))
        assertEquals("Checking", TextCalls.strip("Checking\nTool call: memory_search {\"query\":\"bla", final = false))
        // The Halo lane: the same text is calls, not words.
        assertTrue(DuoText.onlyCalls(leaked, ToolCalls.names(all)))
        assertEquals("", DuoText.strip(leaked, true, ToolCalls.names(all)).trim())
    }

    @Test fun sentencesAboutToolsStayWords() {
        val s = "The tool call failed earlier, so I used the browser instead."
        assertFalse(TextCalls.has(s, ToolCalls.names(all)))
        assertEquals(s, TextCalls.strip(s, known = ToolCalls.names(all)))
    }
}
