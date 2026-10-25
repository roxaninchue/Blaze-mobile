package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.26: one Blaze call ↔ one OpenCode message: persona, transcript, tool protocol, tool calls back, events, config. */
class BackupBridgeTest {
    private val tools = JSONArray("""[
        {"type":"function","function":{"name":"set_alarm","description":"Set an alarm","parameters":{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"}},"required":["hour"]}}},
        {"type":"function","function":{"name":"web_search","description":"Search the web","parameters":{"type":"object","properties":{"query":{"type":"string"}}}}}]""")

    private fun msgs() = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "You are Blaze. Warm, brief. User: Rocky."))
        .put(JSONObject().put("role", "user").put("content", "Wake me at 7"))
        .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "set_alarm").put("arguments", "{\"hour\":7}")))))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "Alarm set for 07:00"))

    @Test fun personaAndToolsGoInTheSystemPrompt() {
        val s = BackupBridge.system(msgs(), tools)
        assertTrue(s.startsWith(BackupBridge.IDENTITY))
        assertTrue(s.contains("You are Blaze. Warm, brief. User: Rocky."))
        assertTrue(s.contains("- set_alarm: Set an alarm"))
        assertTrue(s.contains("<tool_call>{\"name\": \"tool_name\""))
        assertFalse(BackupBridge.system(msgs(), null).contains("## Tools"))
        assertTrue(BackupBridge.IDENTITY.contains("Never call yourself OpenCode"))
    }

    @Test fun transcriptCarriesToolCallsAndResults() {
        val t = BackupBridge.transcript(msgs())
        assertTrue(t.contains("User: Wake me at 7"))
        assertTrue(t.contains("<tool_call>{\"name\":\"set_alarm\",\"arguments\":{\"hour\":7}}</tool_call>"))
        assertTrue(t.contains("<tool_response name=\"set_alarm\">\nAlarm set for 07:00\n</tool_response>"))
        assertFalse(t.contains("You are Blaze. Warm"))   // system text is not repeated in the transcript
        assertTrue(t.endsWith("(use the tool results above)."))
    }

    @Test fun longChatsKeepTheNewestTurns() {
        val m = JSONArray()
        repeat(50) { m.put(JSONObject().put("role", "user").put("content", "old message $it " + "x".repeat(2000))) }
        m.put(JSONObject().put("role", "user").put("content", "the latest question"))
        val t = BackupBridge.transcript(m, 10_000)
        assertTrue(t.startsWith("[earlier conversation left out]"))
        assertTrue(t.contains("the latest question"))
        assertFalse(t.contains("old message 0 "))
        assertTrue(t.length < 12_000)
    }

    @Test fun messageBodyUsesTheBlazeAgentAFreeModelAndNoOpenCodeTools() {
        val b = BackupBridge.messageBody(msgs(), tools, "big-pickle")
        assertEquals("blaze", b.getString("agent"))
        assertEquals("opencode", b.getJSONObject("model").getString("providerID"))
        assertEquals("big-pickle", b.getJSONObject("model").getString("modelID"))
        val off = b.getJSONObject("tools")
        listOf("*", "bash", "edit", "write", "read", "webfetch", "task").forEach { assertEquals(false, off.getBoolean(it)) }
        assertEquals("text", b.getJSONArray("parts").getJSONObject(0).getString("type"))
    }

    @Test fun toolCallsInTheReplyBecomeRealToolCalls() {
        val m = BackupBridge.toOpenAI("Sure.\n<tool_call>{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 7, \"minute\": 30}}</tool_call>", tools)
        val calls = m.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        val f = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("set_alarm", f.getString("name"))
        assertEquals(30, JSONObject(f.getString("arguments")).getInt("minute"))
        assertTrue(calls.getJSONObject(0).getString("id").startsWith("bk_"))
        assertEquals("Sure.", m.getString("content"))
        // An unknown tool stays text (the agent loop's own guards handle it); plain answers stay plain.
        assertFalse(BackupBridge.toOpenAI("<tool_call>{\"name\":\"rm_rf\",\"arguments\":{}}</tool_call>", tools).has("tool_calls"))
        assertEquals("Hi Rocky!", BackupBridge.toOpenAI("Hi Rocky!", tools).getString("content"))
        assertEquals("I'm Blaze, here to help.", BackupBridge.toOpenAI("I'm OpenCode, here to help.", null).getString("content"))
    }

    @Test fun replyPartsAndErrors() {
        val resp = JSONObject("""{"info":{"role":"assistant"},"parts":[{"type":"step-start"},{"type":"reasoning","text":"thinking"},
            {"type":"text","text":"Hello"},{"type":"text","text":" there","synthetic":false},{"type":"text","text":"[hidden]","synthetic":true}]}""")
        assertEquals("Hello there" to "thinking", BackupBridge.replyText(resp))
        assertNull(BackupBridge.failure(resp.getJSONObject("info")))
        val f = BackupBridge.failure(JSONObject("""{"error":{"name":"APIError","data":{"message":"Rate limit exceeded","statusCode":429}}}"""))!!
        assertEquals(429, f.status); assertEquals("Rate limit exceeded", f.message)
    }

    @Test fun eventsFollowOnlyThisSessionsAssistantText() {
        val seen = HashSet<String>()
        fun ev(s: String) = BackupBridge.event(JSONObject(s), "ses_1", seen)
        // The user's own part arrives before any assistant message: ignored.
        assertEquals(BackupBridge.Ev.Other, ev("""{"type":"message.part.updated","properties":{"part":{"sessionID":"ses_1","messageID":"msg_u","type":"text","text":"Wake me"}}}"""))
        assertEquals(BackupBridge.Ev.Other, ev("""{"type":"message.updated","properties":{"info":{"id":"msg_a","sessionID":"ses_1","role":"assistant"}}}"""))
        assertEquals(BackupBridge.Ev.Text("Hel"), ev("""{"type":"message.part.updated","properties":{"part":{"sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hel"},"delta":"Hel"}}"""))
        assertEquals(BackupBridge.Ev.Think("hmm"), ev("""{"type":"message.part.updated","properties":{"part":{"sessionID":"ses_1","messageID":"msg_a","type":"reasoning","text":"hmm"}}}"""))
        assertEquals(BackupBridge.Ev.Other, ev("""{"type":"message.part.updated","properties":{"part":{"sessionID":"ses_2","messageID":"msg_a","type":"text","text":"other"}}}"""))
        assertEquals(BackupBridge.Ev.Idle, ev("""{"type":"session.idle","properties":{"sessionID":"ses_1"}}"""))
        assertEquals(BackupBridge.Ev.Other, ev("""{"type":"session.idle","properties":{"sessionID":"ses_2"}}"""))
        assertEquals(BackupBridge.Ev.Idle, ev("""{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}"""))
        val err = ev("""{"type":"session.error","properties":{"sessionID":"ses_1","error":{"name":"APIError","data":{"message":"overloaded","statusCode":503}}}}""")
        assertEquals(BackupBridge.Ev.Error(BackupBridge.Failure(503, "overloaded")), err)
        // OpenCode retrying by itself (seen live on 1.19.4, 26 Oct 2026): Blaze moves on; the free-tier limit stops every free model.
        val r = ev("""{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"retry","attempt":1,"message":"Free usage exceeded, subscribe to Go","action":{"reason":"free_tier_limit","provider":"opencode"},"next":1792972799994}}}""")
        assertEquals(BackupBridge.Ev.Retry("Free usage exceeded, subscribe to Go", "free_tier_limit", 1792972799994L), r)
        assertTrue(BackupBridge.freeLimit("free_tier_limit", ""))
        assertTrue(BackupBridge.freeLimit("", "Free usage exceeded"))
        assertFalse(BackupBridge.freeLimit("rate_limit", "Too many requests"))
        assertEquals(BackupBridge.Ev.Other, ev("""{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}"""))
    }

    @Test fun picturesSkipTheBackup() {
        assertTrue(BackupBridge.canServe(msgs()))
        val pic = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "image_url"))))
        assertFalse(BackupBridge.canServe(pic))
    }

    @Test fun serverConfigTurnsEverythingOff() {
        val c = BackupBridge.config("big-pickle")
        assertEquals(false, c.getBoolean("autoupdate"))
        assertEquals("disabled", c.getString("share"))
        assertEquals("opencode/big-pickle", c.getString("model"))
        assertEquals(false, c.getJSONObject("tools").getBoolean("*"))
        assertEquals("deny", c.getJSONObject("permission").getString("bash"))
        val a = c.getJSONObject("agent").getJSONObject("blaze")
        assertEquals("primary", a.getString("mode"))
        assertEquals(BackupBridge.IDENTITY, a.getString("prompt"))
        assertFalse(BackupBridge.config(null).has("model"))
    }
}
