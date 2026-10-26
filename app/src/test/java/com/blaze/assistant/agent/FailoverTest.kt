package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.46.3: brain order on-device OpenCode → Nous reserve → Blaze Cloud. A failure at ANY turn of a task (turn 1, turn 8)
 * never shows an error while a brain is left: the next brain answers with the same context and the task finishes.
 * Simulates the agent's tool loop: each turn is one model call through [Failover.run]; an escaping exception would be
 * the error bubble.
 */
class FailoverTest {
    private val busy = LlmClient.LlmException(Cloud.BUSY, 503)

    private class Sim(val bubbles: List<String>, val via: List<String>, val status: List<String>)

    private fun task(failAt: Map<Int, Set<BackupRoute.Step>>, backupReady: Boolean, signedIn: Boolean = true): Sim {
        val bubbles = ArrayList<String>(); val via = ArrayList<String>(); val status = ArrayList<String>()
        val context = ArrayList<String>()
        runBlocking {
            for (turn in 1..10) {
                try {
                    val out = Failover.run(BrainOrder.plan(backupReady, signedIn), call = { step ->
                        if (step in (failAt[turn] ?: emptySet())) throw if (step == BackupRoute.Step.BACKUP) LlmClient.LlmException("backup: no first chunk", 0) else busy
                        "${step.name} t$turn after ${context.size}"
                    }, onSwitch = { status.add(it) })
                    via.add(out.via.name)
                    context.add(out.value)
                    if (turn == 10) bubbles.add("BLAZE: done")
                } catch (e: LlmClient.LlmException) { bubbles.add("ERROR: ${e.message}"); break }
            }
        }
        return Sim(bubbles, via, status)
    }
    private val B = BackupRoute.Step.BACKUP; private val R = BackupRoute.Step.RESERVE; private val C = BackupRoute.Step.CLOUD

    @Test fun onDeviceFirstThenNousThenCloud() {
        assertEquals(listOf(B, R, C), BrainOrder.plan(backupReady = true))
        assertEquals(listOf(R, C), BrainOrder.plan(backupReady = false))      // not ready yet / 32-bit phone: start at Nous
        assertEquals(listOf(B), BrainOrder.plan(backupReady = true, signedIn = false))
        val s = task(emptyMap(), backupReady = true)
        assertTrue(s.via.all { it == "BACKUP" }); assertTrue(s.status.isEmpty())
    }

    @Test fun failureAtTurn1FinishesViaNousWithNoErrorBubble() {
        val s = task(mapOf(1 to setOf(B)), backupReady = true)
        assertEquals(listOf("BLAZE: done"), s.bubbles)
        assertEquals("RESERVE", s.via[0]); assertTrue(s.via.drop(1).all { it == "BACKUP" })
        assertEquals(listOf(Failover.SWITCHED), s.status)
    }

    @Test fun failureAtTurn8MidTaskContinuesWithItsContext() {
        val s = task(mapOf(8 to setOf(B, R)), backupReady = true)
        assertEquals(listOf("BLAZE: done"), s.bubbles)
        assertEquals(10, s.via.size)
        assertEquals("CLOUD", s.via[7])
    }

    @Test fun notReadyStartsAtNousSilently() {
        val s = task(emptyMap(), backupReady = false)
        assertTrue(s.via.all { it == "RESERVE" }); assertTrue(s.status.isEmpty())
        val s8 = task(mapOf(8 to setOf(R)), backupReady = false)
        assertEquals(listOf("BLAZE: done"), s8.bubbles); assertEquals("CLOUD", s8.via[7])
    }

    @Test fun errorOnlyWhenEveryBrainFailed() {
        val s = task(mapOf(8 to setOf(B, R, C)), backupReady = true)
        assertEquals(1, s.bubbles.size)
        assertTrue(s.bubbles[0], s.bubbles[0].startsWith("ERROR") && s.bubbles[0].contains("Retry"))
    }

    @Test fun signInAndAllowanceFromCloudAreFinal() = runBlocking {
        for (e in listOf(LlmClient.LlmException(Cloud.SIGNED_OUT, 401), LlmClient.LlmException("You've used today's free messages. They come back tomorrow.", 429))) {
            try { Failover.run(listOf(C), call = { throw e }); fail("should throw") }
            catch (x: LlmClient.LlmException) { assertEquals(e.message, x.message) }
        }
    }

    @Test fun networkAndTooSlowAreFailedOver() {
        assertTrue(Failover.fixable(java.net.SocketTimeoutException("read timed out")))
        assertTrue(Failover.fixable(LlmClient.LlmException("HTTP 502", 502)))
        assertTrue(Failover.fixable(LlmClient.LlmException("rate limited", 429)))
        assertFalse(Failover.fixable(kotlinx.coroutines.CancellationException("stop")))
    }

    @Test fun cloudRunFailuresAreHandedToTheLocalLoop() {
        assertTrue(CloudRuns.handOff("models_busy")); assertTrue(CloudRuns.handOff("no_reply")); assertTrue(CloudRuns.handOff("503"))
        assertFalse(CloudRuns.handOff("quota_exceeded")); assertFalse(CloudRuns.handOff("too_large"))
        assertTrue(CloudRuns.handOff(LlmClient.LlmException(Cloud.BUSY, 503)))
        assertFalse(CloudRuns.handOff(LlmClient.LlmException(CloudRuns.STOPPED, 0)))
        assertFalse(CloudRuns.handOff(CloudRuns.RunRefused("You've used today's free messages.", 429)))
        assertFalse(CloudRuns.handOff(LlmClient.LlmException("too large", 413)))
        // A2
        listOf(503, 408, EmptyReply.CODE, 500, 0).forEach { assertTrue("$it", CloudRuns.fallLocal(LlmClient.LlmException("x", it))) }
        assertFalse(CloudRuns.fallLocal(LlmClient.LlmException("quota_exceeded", 429)))
        assertFalse(CloudRuns.fallLocal(LlmClient.LlmException("too large", 413)))
    }

    @Test fun codingJobsGetTheStrongFreeCodersFirst() {
        fun user(t: String) = JSONArray().put(JSONObject().put("role", "user").put("content", t))
        assertTrue(BrainOrder.coding(user("fix this kotlin function, it throws a NullPointerException")))
        assertTrue(BrainOrder.coding(user("clone my repo and run the tests in the terminal")))
        assertFalse(BrainOrder.coding(user("what should I cook tonight?")))
        val live = listOf("big-pickle", "glm-5.3-flash-free", "deepseek-v4.1-flash-free", "beam-free")
        assertEquals(listOf("deepseek-v4.1-flash-free", "glm-5.3-flash-free", "big-pickle", "beam-free"), BrainOrder.models(live, coding = true, googleData = false))
        assertEquals(live, BrainOrder.models(live, coding = false, googleData = false))
        // asked for even before the live list shows them
        assertEquals(listOf("deepseek-v4.1-flash-free", "glm-5.3-flash-free", "big-pickle"), BrainOrder.models(listOf("big-pickle"), true, false))
        // Google data: zero-retention models only
        assertEquals(listOf("glm-5.3-flash-free", "deepseek-v4.1-flash-free"), BrainOrder.models(live, false, true))
        val gmail = JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "gmail_search")))))
        assertTrue(BrainOrder.googleData(gmail))
        assertEquals(20_000L, BrainOrder.FIRST_TOKEN_MS)
    }

    @Test fun noNvidiaOrJevModelIdsAnywhere() {
        assertTrue(BrainOrder.models(listOf("nvidia/nemotron-3-super", "jev-1", "big-pickle", "nemotron-ultra-free"), true, false).none { BackupModels.excluded(it) })
        assertFalse(ProviderCatalog.isChatText("nvidia/nemotron-3-super-120b-a12b"))
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        // a quoted model id naming NVIDIA / Nemotron / Jev / System One; the exclusion and hide lists themselves are the only mentions
        val id = Regex(""""[^"\s]*(?:nvidia/|nemotron-|/jev|jev-|system-?one)[^"\s]*"""", RegexOption.IGNORE_CASE)
        val bad = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> f.readLines().mapIndexedNotNull { n, l -> if (id.containsMatchIn(l)) "${f.name}:${n + 1}: ${l.trim().take(100)}" else null } }.toList()
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun backupRestIsCappedAt30Minutes() {
        val now = 1_000_000L
        assertEquals(now + 30 * 60_000L, BackupModels.capRest(now + 6 * 3600_000L, now))
        assertEquals(now + 60_000L, BackupModels.capRest(now + 60_000L, now))
    }

    @Test fun healthLine() {
        assertEquals("", BackupStatus.healthLine(null, ""))
        assertTrue(BackupStatus.healthLine(true, "17:40").contains("answering (17:40)"))
        assertTrue(BackupStatus.healthLine(false, "").contains("not answering"))
    }

    @Test fun ledgerRecordsUsagePerBrain() {
        var t = 0L
        val l = BrainLedger { t }
        t = 5; l.ok(B, 1200); t = 6; l.fail(R, "busy"); t = 7; l.ok(C, 900)
        assertEquals("answering", l.health(B)); assertEquals("failing", l.health(R)); assertEquals("not used yet", BrainLedger().health(B))
        val l2 = BrainLedger(); l2.load(l.toJson()); assertEquals(1, l2.rows[B]?.calls); assertEquals(1, l2.rows[R]?.fails)
    }
}
