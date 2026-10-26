package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.3 patch spec A1, A3, A5, A6: fallbacks after a healed 400, a slim gateway request after a Worker kill, plain error lines. */
class ReserveBodyTest {
    private fun tool(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n))

    @Test fun slimKeepsToolsUsedInHistoryAndCapsTheRest() {
        val tools = JSONArray().apply { (1..60).forEach { put(tool("t$it")) } }
        val msgs = JSONArray().apply {
            (1..10).forEach { put(JSONObject().put("role", "user").put("content", "x$it")) }
            put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "t55")))))
            put(JSONObject().put("role", "tool").put("name", "t55").put("content", "ok"))
        }
        val slim = ReserveBody.slimForReserve(tools, msgs)!!
        val names = (0 until slim.length()).map { slim.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals("t55", names[0])
        assertEquals(1 + ReserveBody.MAX_TOOLS, names.size)
    }

    @Test fun workerKillDetection() {
        assertTrue(ReserveBody.looksLikeWorkerKill(503, "error code: 1102"))
        assertTrue(ReserveBody.looksLikeWorkerKill(0, "Worker exceeded CPU time limit"))
        assertFalse(ReserveBody.looksLikeWorkerKill(503, "{\"error\":{\"code\":\"blaze_models_busy\",\"message\":\"busy\"}}"))
        assertFalse(ReserveBody.looksLikeWorkerKill(401, "error code: 1102"))
    }

    @Test fun fallbacksStillRunAfterTheHealedRetry() {
        assertEquals(listOf(BackupRoute.Step.RESERVE), BackupRoute.fallbacks(false, 400, "The model rejected the request: x", shapeError = true, afterHeal = true))
        assertEquals(listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE), BackupRoute.fallbacks(true, 400, "The model rejected the request: x", shapeError = true, afterHeal = true))
        assertEquals(emptyList<BackupRoute.Step>(), BackupRoute.fallbacks(true, 400, "The model rejected the request: x", shapeError = true))
    }

    @Test fun crashGuardShowsPlainWordsNeverAClassName() {
        assertEquals(Cloud.BUSY, com.blaze.assistant.device.CrashLog.lineFor(LlmClient.LlmException(Cloud.BUSY, 503)))
        val other = com.blaze.assistant.device.CrashLog.lineFor(IllegalStateException("t8"))
        assertFalse(other.contains("t8")); assertFalse(other.contains("IllegalState"))
    }

    @Test fun streamBrokenFrameReadsAsBrokenMidReply() {
        assertTrue(BackupRoute.brokeMidReply(LlmClient.STREAM_BROKEN))
    }
}
