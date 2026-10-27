package com.blaze.assistant.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.46.6 bug 2 (handoff 27 Oct): "remove HedgeRace first-answer-wins and FastLane for tool turns". A call that offers tools
 * runs one brain at a time in the standing order (on-phone → Nous → gateway): no hedge, no parallel start at a deadline,
 * no first answer wins, so a tool call is never returned twice or by a brain that lost. Plain chat keeps the hedge.
 * Virtual time; fake brains count how many run at once and which tool calls they return.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolTurnRaceTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()
    enum class B { PHONE, NOUS, CLOUD }

    private val task = BrainRace.limits(BrainRace.Kind.TASK, hot = false)
    private val chat = BrainRace.limits(BrainRace.Kind.CHAT, hot = false)

    /** [first] ms to its tool call (null = silent forever), [think] = thinking every second meanwhile, [error] = fails then. */
    data class Fake(val first: Long?, val think: Boolean = false, val error: Long? = null, val stallAfterCall: Boolean = false)

    private val running = AtomicInteger(0)
    private var most = 0
    private val started = mutableListOf<Pair<B, Long>>()
    private val calls = mutableListOf<String>()   // tool calls returned by the race (what the agent loop would run)

    private suspend fun TestScope.play(f: Fake, a: HedgeRace.Attempt<B>): String {
        started += a.step to testScheduler.currentTime
        most = maxOf(most, running.incrementAndGet())
        try {
            val t0 = testScheduler.currentTime
            if (f.error != null) { delay(f.error); throw LlmClient.LlmException("429 Free usage exceeded", 429) }
            if (f.first == null) while (true) { delay(1_000); if (f.think) a.think() }
            while (testScheduler.currentTime - t0 < f.first) { delay(minOf(1_000L, f.first - (testScheduler.currentTime - t0))); if (f.think) a.think() }
            a.visible()   // the tool call chunk streams in
            if (f.stallAfterCall) delay(10 * 60_000)
            delay(200)
            return "tool_call:send_sms by ${a.step}"
        } finally { running.decrementAndGet() }
    }

    private suspend fun TestScope.race(lim: HedgeRace.Limits, brains: Map<B, Fake>, parallel: Boolean, streaming: Boolean = true,
                                       fails: MutableList<String> = mutableListOf()): HedgeRace.Outcome<B, String> {
        val o = HedgeRace.run(brains.keys.toList(), lim, streaming, call = { a -> play(brains.getValue(a.step), a) },
            onFail = { s, e -> fails += "$s:${e.message}" }, now = { testScheduler.currentTime }, parallel = parallel)
        calls += o.value
        return o
    }

    @Test fun toolTurnsAreNeverChatFastAndNeverParallel() {
        // the short tool message that used to be chat-fast (hedged after 3 s, fastest brain first)
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(false, false, 60, false, 70))
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(true, false, 12, false, 5))
        assertFalse(BrainRace.parallel(60)); assertFalse(BrainRace.parallel(1))
        assertTrue(BrainRace.parallel(0))
        // Halo's side lane stays CHAT for its deadlines, but with tools it is still one brain at a time
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(false, false, 5, false, 10, forced = BrainRace.Kind.CHAT))
        assertFalse(BrainRace.parallel(5))
        assertEquals(45_000L, task.quietMs); assertEquals(20_000L, chat.quietMs)
    }

    @Test fun slowFirstBrainIsNotHedgedOnAToolTurn() = runTest {
        // 0.46.5 hedged Nous at 6 s and let whoever showed first win; now Nous is never started while the phone is working
        val o = race(task, mapOf(B.PHONE to Fake(15_000), B.NOUS to Fake(2_000), B.CLOUD to Fake(1_000)), parallel = false)
        assertEquals(B.PHONE, o.step); assertFalse(o.hedged)
        assertEquals(1, most)
        assertEquals(listOf(B.PHONE), started.map { it.first })
        assertEquals(listOf("tool_call:send_sms by PHONE"), calls)
    }

    @Test fun theSameRaceInParallelModeWouldHaveHedged() = runTest {
        // the regression guard: plain chat (parallel) still hedges, which is what tool turns must never do
        val o = race(task, mapOf(B.PHONE to Fake(15_000), B.NOUS to Fake(2_000)), parallel = true)
        assertEquals(B.NOUS, o.step); assertTrue(o.hedged); assertEquals(2, most)
    }

    @Test fun errorsFailOverInOrderOneAtATime() = runTest {
        val fails = mutableListOf<String>()
        val o = race(task, mapOf(B.PHONE to Fake(null, error = 400), B.NOUS to Fake(null, error = 300), B.CLOUD to Fake(1_000)), parallel = false, fails = fails)
        assertEquals(B.CLOUD, o.step)
        assertEquals(listOf(B.PHONE to 0L, B.NOUS to 400L, B.CLOUD to 700L), started)
        assertEquals(1, most); assertEquals(2, fails.size)
        assertEquals(listOf("tool_call:send_sms by CLOUD"), calls)
    }

    @Test fun silentBrainGivesWayAtTheQuietCapThenTheNextStarts() = runTest {
        val fails = mutableListOf<String>()
        val o = race(task, mapOf(B.PHONE to Fake(null), B.NOUS to Fake(2_000)), parallel = false, fails = fails)
        assertEquals(B.NOUS, o.step)
        assertEquals(1, most)
        val nousAt = started.first { it.first == B.NOUS }.second
        assertTrue("Nous starts only after the phone was dropped (at ${nousAt})", nousAt in 45_000L..45_100L)
        assertTrue(fails.single().contains("nothing in 45s"))
    }

    @Test fun aThinkingBrainIsNotCutByTheQuietCap() = runTest {
        // slow but working (thinking streams): kept past 45 s, nobody else is started
        val o = race(task, mapOf(B.PHONE to Fake(60_000, think = true), B.NOUS to Fake(1_000)), parallel = false)
        assertEquals(B.PHONE, o.step); assertEquals(60_000L, o.firstVisibleMs)
        assertEquals(listOf(B.PHONE), started.map { it.first })
    }

    @Test fun aBrainThatStallsAfterItsToolCallIsDroppedAndOnlyTheNextAnswerIsUsed() = runTest {
        val fails = mutableListOf<String>()
        val o = race(task, mapOf(B.PHONE to Fake(1_000, stallAfterCall = true), B.NOUS to Fake(1_000)), parallel = false, fails = fails)
        assertEquals(B.NOUS, o.step)
        assertEquals(1, most)
        // the dropped brain's tool call is never returned: exactly one tool call, from the brain that finished
        assertEquals(listOf("tool_call:send_sms by NOUS"), calls)
        assertTrue(fails.single().contains("stalled"))
    }

    @Test fun nonStreamingToolCallsWaitForTheOuterCapNotTheQuietCap() = runTest {
        // a non-streaming call has no chunks to watch: the quiet cap doesn't apply, the next brain waits for the outer cap
        val o = race(task, mapOf(B.PHONE to Fake(60_000), B.NOUS to Fake(1_000)), parallel = false, streaming = false)
        assertEquals(B.PHONE, o.step); assertEquals(1, most)
    }

    @Test fun plainChatMayStillHedgeAndOnlyTheWinnersTextShows() = runTest {
        val shown = mutableListOf<B>()
        val o = HedgeRace.run(listOf(B.PHONE, B.NOUS), chat, true, call = { a ->
            delay(if (a.step == B.PHONE) 9_000 else 500); if (a.visible()) shown += a.step; "hi from ${a.step}" },
            now = { testScheduler.currentTime }, parallel = BrainRace.parallel(0))
        assertEquals(B.NOUS, o.step); assertTrue(o.hedged)
        assertEquals(listOf(B.NOUS), shown)
    }

    @Test fun wiring() {
        val f = { p: String -> listOf(File("src/main/java/com/blaze/assistant/agent/$p"), File("app/src/main/java/com/blaze/assistant/agent/$p")).first { it.exists() }.readText() }
        val llm = f("LlmClient.kt"); val hr = f("HedgeRace.kt")
        assertTrue(llm.contains("parallel = BrainRace.parallel(tools?.length() ?: 0))"))
        assertTrue(hr.contains("val nobody = parallel && winner.get() == null && next < steps.size"))
        assertFalse(f("BrainRace.kt").contains("userChars in 1..SHORT_CHARS -> Kind.CHAT"))
    }
}
