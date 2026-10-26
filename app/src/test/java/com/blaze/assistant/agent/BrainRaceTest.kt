package com.blaze.assistant.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.46.4 hotfix: the router (kinds, order, limits), the Halo side reply, and the wiring that removed the 4-minute wait. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrainRaceTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()
    private val P = BackupRoute.Step.BACKUP; private val R = BackupRoute.Step.RESERVE; private val C = BackupRoute.Step.CLOUD
    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/agent/$p"), File("app/src/main/java/com/blaze/assistant/agent/$p")).first { it.exists() }.readText()

    @Test fun kinds() {
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(lite = true, heavy = false, toolCount = 40, coding = false, userChars = 10))
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(false, false, 0, false, 900))
        // Rocky's 19:07 message: short, tools offered -> chat-fast
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(false, false, 60, false, "I have a cloudflare token I want to give you and a gitlab token as well".length))
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(false, true, 60, false, 50))
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(false, false, 60, false, 600))
        assertEquals(BrainRace.Kind.CODING, BrainRace.kind(false, false, 60, true, 30))
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(false, true, 60, true, 30, forced = BrainRace.Kind.CHAT))   // Halo's side lane
        assertEquals(12, BrainRace.userChars(JSONArray().put(JSONObject().put("role", "user").put("content", " what's up?? "))))
    }

    @Test fun orderNeverWaitsOnACold() {
        // setup in progress / server stopped (not warm): the on-phone brain is left out, the call starts at Nous at once
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.TASK, warm = false, signedIn = true))
        assertEquals(listOf(P, R, C), BrainRace.order(BrainRace.Kind.TASK, warm = true, signedIn = true))   // 0.46.3 order when warm
        assertEquals(listOf(P, R, C), BrainRace.order(BrainRace.Kind.CODING, true, true))
        // chat: fastest first; defaults Nous 3.0, Cloud 3.2, phone 6.0 s (the benchmark uses these lists)
        assertEquals(listOf(R, C, P), BrainRace.order(BrainRace.Kind.CHAT, true, true))
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.CHAT, false, true))
        // measured: Cloud faster than Nous -> Cloud first
        assertEquals(listOf(C, R), BrainRace.order(BrainRace.Kind.CHAT, false, true) { if (it == C) 900L else if (it == R) 4_000L else null })
        assertEquals(listOf(P), BrainRace.order(BrainRace.Kind.CHAT, true, signedIn = false))
    }

    @Test fun limits() {
        val c = BrainRace.limits(BrainRace.Kind.CHAT, hot = false); val t = BrainRace.limits(BrainRace.Kind.TASK, hot = false)
        assertEquals(8_000L, c.firstMs); assertEquals(3_000L, c.hedgeMs); assertEquals(30_000L, c.stallMs); assertEquals(8_000L, c.thinkMs)
        assertEquals(20_000L, t.firstMs); assertEquals(6_000L, t.hedgeMs); assertEquals(30_000L, t.stallMs)
        assertEquals(0L, BrainRace.limits(BrainRace.Kind.CHAT, hot = true).hedgeMs)
        assertTrue(BrainRace.cloudRunFor(lite = false, longWork = true)); assertFalse(BrainRace.cloudRunFor(false, false)); assertFalse(BrainRace.cloudRunFor(true, true))
    }

    @Test fun ledgerLearnsFirstTokenTimes() {
        val l = BrainLedger { 0L }
        assertEquals(null, l.ttft(R)); l.ttft(R, 5_000); assertEquals(5_000L, l.ttft(R)); l.ttft(R, 1_000); assertEquals(3_400L, l.ttft(R))
        val l2 = BrainLedger(); l2.load(l.toJson()); assertEquals(3_400L, l2.ttft(R))
    }

    @Test fun haloAnswersWithinTwoSecondsWhileBlazeIsBusy() = runTest {
        // Blaze's turn is busy (hanging); Halo's side reply has its own call. The brain is slow: the holding line shows at 2 s.
        val shown = mutableListOf<Pair<Long, String>>()
        val blaze = backgroundScope.launch { delay(10 * 60_000) }   // the stuck main turn, untouched by Halo
        val r = BrainRace.haloReply(BrainRace.HALO_HOLD_MS, { BrainRace.haloHolding("check my GitLab tokens", "Reading 2 pages") }, { shown += testScheduler.currentTime to it }) { say ->
            delay(6_000); say("Blaze is still on it - about a minute more."); "Blaze is still on it - about a minute more." }
        assertEquals(2_000L, shown.first().first)
        assertEquals("Blaze is still on \u201ccheck my GitLab tokens\u201d. Right now: Reading 2 pages.", shown.first().second)
        assertEquals("Blaze is still on it - about a minute more.", r)
        assertTrue(blaze.isActive)
        // fast model: no holding line at all
        val shown2 = mutableListOf<String>()
        BrainRace.haloReply(2_000, { "hold" }, { shown2 += it }) { say -> delay(800); say("Hi!"); "Hi!" }
        assertEquals(listOf("Hi!"), shown2)
        // every brain fails after the holding line: the holding line is Halo's reply, not an error
        val r3 = BrainRace.haloReply(2_000, { "Blaze is still on your request." }, { }) { _ -> delay(9_000); throw LlmClient.LlmException(Cloud.BUSY, 503) }
        assertEquals("Blaze is still on your request.", r3)
    }

    @Test fun wiring() {
        val llm = src("LlmClient.kt"); val agent = src("Agent.kt"); val bb = src("BackupBrain.kt")
        assertTrue(llm.contains("HedgeRace.run(steps, lim, onText != null,"))
        assertTrue(llm.contains("BrainRace.order(kind, warm, token.isNotBlank())"))
        assertFalse("dead first-chunk watcher removed", llm.contains("private suspend fun watchFirstChunk"))
        assertTrue(agent.contains("BrainRace.cloudRunFor(lite, longWork) && CloudRuns.eligible("))
        assertTrue(agent.contains("HedgeRace.watch(BrainRace.TASK_LIMITS.firstMs, BrainRace.TASK_LIMITS.stallMs,"))
        assertTrue(agent.contains("CallState().also { it.raceKind = BrainRace.Kind.CHAT }.element()"))
        assertTrue(agent.contains("BrainRace.haloReply(BrainRace.HALO_HOLD_MS,"))
        assertTrue(bb.contains("fun warm(now: Long = System.currentTimeMillis()): Boolean"))
        assertTrue(src("CloudRuns.kt").contains("!(try { BackupBrain.warm() } catch (_: Throwable) { false }) &&"))
    }
}
