package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 0.46.4 hotfix: the hedged brain race in virtual time. Brains are fakes that show their first word after a delay, think
 * without ever answering, hang with no output, or refuse with a rate limit. Every case asserts a first word (or the next
 * brain) within the deadlines.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HedgeRaceTest {
    /** Missing-Main launch failures from earlier test classes surface at the first runTest here (CI run 40374778854); drop only those ([LeakedMainLaunches]). */
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()
    enum class B { PHONE, NOUS, CLOUD }

    val chat = HedgeRace.Limits(firstMs = 8_000, hedgeMs = 3_000, stallMs = 30_000, thinkMs = 8_000, wholeMs = 30_000)
    val task = HedgeRace.Limits(firstMs = 20_000, hedgeMs = 6_000, stallMs = 30_000, thinkMs = 45_000, wholeMs = 60_000)

    class RateLimited : Exception("429 Free usage limit reached")

    /** A fake brain. [first] = ms to its first word (null = never), [think] = streams thinking every second meanwhile. */
    data class Fake(val first: Long?, val think: Boolean = false, val error: Long? = null, val words: Int = 3, val gap: Long = 200, val stallAfter: Int = -1)

    val shown = mutableListOf<String>()

    suspend fun TestScope.play(f: Fake, a: HedgeRace.Attempt<B>): String {
        val t0 = testScheduler.currentTime
        if (f.error != null) { delay(f.error); throw RateLimited() }
        if (f.first == null) {
            while (true) { delay(1_000); if (f.think) a.think() }
        }
        while (testScheduler.currentTime - t0 < f.first) { delay(minOf(1_000L, f.first - (testScheduler.currentTime - t0))); if (f.think) a.think() }
        val sb = StringBuilder()
        for (i in 0 until f.words) {
            if (i == f.stallAfter) delay(10 * 60_000)
            sb.append("${a.step}$i ")
            if (a.visible()) shown += sb.toString()
            delay(f.gap)
        }
        return sb.toString().trim()
    }

    private suspend fun TestScope.race(lim: HedgeRace.Limits, brains: Map<B, Fake>, order: List<B> = brains.keys.toList(), streaming: Boolean = true,
                                       status: MutableList<String> = mutableListOf(), fails: MutableList<String> = mutableListOf()) =
        HedgeRace.run(order, lim, streaming, call = { a -> play(brains.getValue(a.step), a) },
            onStatus = { status += it }, onFail = { s, e -> fails += "$s:${e.message}" }, now = { testScheduler.currentTime })

    @Test fun fastFirstBrainWinsAlone() = runTest {
        val st = mutableListOf<String>()
        val o = race(chat, mapOf(B.NOUS to Fake(1_200), B.CLOUD to Fake(900)), status = st)
        assertEquals(B.NOUS, o.step); assertEquals(1_200, o.firstVisibleMs); assertFalse(o.hedged); assertTrue(st.isEmpty())
    }

    @Test fun onPhoneBrainHangingWithNoOutputFailsOverWithinTheChatDeadline() = runTest {
        // the 0.46.3 failure: the first brain never shows a word (no stream, no error)
        val st = mutableListOf<String>()
        val o = race(chat, mapOf(B.PHONE to Fake(null), B.NOUS to Fake(2_000), B.CLOUD to Fake(1_500)), status = st)
        // hedged at 3 s: Nous is asked in parallel and shows its first word 2 s later
        assertEquals(B.NOUS, o.step); assertTrue(o.hedged); assertEquals(5_000, o.firstVisibleMs)
        assertEquals(listOf("Switching to a faster brain"), st)
        assertTrue("first word within ~5 s", o.firstVisibleMs <= 5_500)
    }

    @Test fun hangingEverywhereButLastStillAnswersAndNeverWaitsForever() = runTest {
        val fails = mutableListOf<String>()
        val o = race(chat, mapOf(B.PHONE to Fake(null), B.NOUS to Fake(null), B.CLOUD to Fake(1_000)), fails = fails)
        assertEquals(B.CLOUD, o.step)
        // phone hedged at 3 s, Nous at 6 s, Cloud's word at 7 s; nobody waited past the 8 s cap
        assertEquals(7_000, o.firstVisibleMs)
    }

    @Test fun setupInProgressMeansTheOnPhoneBrainIsNotAskedAndNousStartsAtOnce() = runTest {
        // the router leaves a cold / setting-up brain out (BrainRace.order(warm = false)): the race starts at Nous at t=0
        val o = race(chat, mapOf(B.NOUS to Fake(1_800), B.CLOUD to Fake(1_500)), order = listOf(B.NOUS, B.CLOUD))
        assertEquals(B.NOUS, o.step); assertEquals(1_800, o.firstVisibleMs)
    }

    @Test fun slowFirstTokenIsHedgedAndTheFasterBrainWins() = runTest {
        shown.clear()
        val o = race(task, mapOf(B.NOUS to Fake(15_000), B.CLOUD to Fake(2_000)))
        // task hedge at 6 s; Cloud shows at 8 s; Nous (15 s) is cancelled and none of its text reaches the screen
        assertEquals(B.CLOUD, o.step); assertTrue(o.hedged); assertEquals(8_000, o.firstVisibleMs)
        assertTrue(shown.all { it.startsWith("CLOUD") })
        assertEquals("CLOUD0 CLOUD1 CLOUD2", o.value)
    }

    @Test fun hedgeWinnerIsWhoeverShowsFirstEvenIfStartedLater() = runTest {
        shown.clear()
        val o = race(chat, mapOf(B.NOUS to Fake(4_000), B.CLOUD to Fake(500)))
        assertEquals(B.CLOUD, o.step); assertEquals(3_500, o.firstVisibleMs)
        assertTrue("loser's words never shown", shown.none { it.startsWith("NOUS") })
    }

    @Test fun rateLimitReplyMovesOnAtOnce() = runTest {
        val fails = mutableListOf<String>()
        val o = race(chat, mapOf(B.PHONE to Fake(null, error = 400), B.NOUS to Fake(null, error = 300), B.CLOUD to Fake(1_000)), fails = fails)
        assertEquals(B.CLOUD, o.step); assertEquals(1_700, o.firstVisibleMs)
        assertEquals(2, fails.size); assertTrue(fails.all { "429" in it })
    }

    @Test fun reasoningModelThinkingForeverIsCutOnChat() = runTest {
        // hidden thinking is not a first word: chat drops it by 8 s (and hedges at 3 s)
        val o = race(chat, mapOf(B.NOUS to Fake(null, think = true), B.CLOUD to Fake(1_000)))
        assertEquals(B.CLOUD, o.step); assertEquals(4_000, o.firstVisibleMs)
    }

    @Test fun thinkingAloneTimesOutWhenItIsTheOnlyBrain() = runTest {
        val t0 = testScheduler.currentTime
        try { race(chat, mapOf(B.NOUS to Fake(null, think = true))); fail("should give up") } catch (e: Exception) { assertTrue(e is HedgeRace.TooSlow) }
        val took = testScheduler.currentTime - t0
        assertTrue("chat gives up at 8 s, took $took", took in 8_000..8_300)
    }

    @Test fun taskThinkingIsAllowedUpToTheThinkingCapThenCut() = runTest {
        val t0 = testScheduler.currentTime
        try { race(task, mapOf(B.NOUS to Fake(null, think = true))); fail("should give up") } catch (e: Exception) { assertTrue(e is HedgeRace.TooSlow) }
        assertTrue(testScheduler.currentTime - t0 in 45_000..45_300)
        // a reasoning model that answers at 30 s on a task (alone) is kept
        val o = race(task, mapOf(B.NOUS to Fake(30_000, think = true)))
        assertEquals(30_000, o.firstVisibleMs)
    }

    @Test fun stallAfterFirstWordFailsOverWithin30s() = runTest {
        val fails = mutableListOf<String>()
        val t0 = testScheduler.currentTime
        val o = race(chat, mapOf(B.NOUS to Fake(1_000, stallAfter = 1), B.CLOUD to Fake(1_000)), fails = fails)
        assertEquals(B.CLOUD, o.step)
        assertTrue(fails.single().contains("stalled"))
        assertTrue(testScheduler.currentTime - t0 < 34_000)
    }

    @Test fun nonStreamingCallHasAWholeAnswerCapAndNoHedge() = runTest {
        val o = race(chat, mapOf(B.NOUS to Fake(null), B.CLOUD to Fake(2_000)), streaming = false)
        assertEquals(B.CLOUD, o.step); assertFalse(o.hedged)
    }

    @Test fun fatalErrorEndsOnceNobodyElseRuns() = runTest {
        class SignIn : Exception("sign in")
        try {
            HedgeRace.run(listOf(B.CLOUD, B.NOUS), chat, true, call = { a -> if (a.step == B.CLOUD) throw SignIn() else { delay(100_000); "x" } },
                fatal = { s, e -> e is SignIn }, now = { testScheduler.currentTime })
            fail()
        } catch (e: Exception) { assertTrue(e.message, e is SignIn) }
    }

    @Test fun cancellingTheCallerCancelsEveryBrain() = runTest {
        var cancelled = 0
        val job = backgroundScope.launch {
            HedgeRace.run(listOf(B.NOUS, B.CLOUD), chat, true, call = { _ -> try { delay(1_000_000); "x" } catch (e: CancellationException) { cancelled++; throw e } },
                now = { testScheduler.currentTime })
        }
        delay(4_000); job.cancel(); delay(10)
        assertEquals(2, cancelled)
    }

    @Test fun watchGivesUpOnAQueuedCloudRun() = runTest {
        // a Cloud run the gateway keeps "queued": no progress ever. armed once the message is sent (t = 1 s)
        val t0 = testScheduler.currentTime
        val got = HedgeRace.watch(20_000, 30_000, { if (testScheduler.currentTime - t0 >= 1_000) t0 + 1_000 else 0L }, { 0L }, now = { testScheduler.currentTime }) {
            delay(10 * 60_000); "never"
        }
        assertEquals(null, got)
        assertTrue(testScheduler.currentTime - t0 in 21_000..21_500)
        // a run that answers in time comes back
        val t1 = testScheduler.currentTime
        assertEquals("ok", HedgeRace.watch(20_000, 30_000, { t1 }, { 0L }, now = { testScheduler.currentTime }) { delay(500); "ok" })
    }
}
