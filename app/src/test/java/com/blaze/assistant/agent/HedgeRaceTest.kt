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

    val chat = BrainRace.limits(BrainRace.Kind.CHAT, hot = false)
    val task = BrainRace.limits(BrainRace.Kind.TASK, hot = false)

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
        // phone hedged at 3 s, Nous at 6 s, Cloud's word at 7 s (the silent ones keep racing until then, then lose)
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

    @Test fun reasoningModelThinkingForeverLosesToARealAnswerOnChat() = runTest {
        // hidden thinking is not a first word: chat hedges at 3 s and the first real word wins
        val o = race(chat, mapOf(B.NOUS to Fake(null, think = true), B.CLOUD to Fake(1_000)))
        assertEquals(B.CLOUD, o.step); assertEquals(4_000, o.firstVisibleMs)
    }

    @Test fun thinkingAloneTimesOutWhenItIsTheOnlyBrain() = runTest {
        val t0 = testScheduler.currentTime
        try { race(chat, mapOf(B.NOUS to Fake(null, think = true))); fail("should give up") } catch (e: Exception) { assertTrue(e is HedgeRace.TooSlow) }
        val took = testScheduler.currentTime - t0
        // 0.46.5: thinking keeps a brain alive; the outer cap (60 s on chat) still ends a turn that never answers
        assertTrue("chat gives up at 60 s, took $took", took in 60_000..60_300)
    }

    @Test fun taskThinkingIsAllowedUpToTheThinkingCapThenCut() = runTest {
        val t0 = testScheduler.currentTime
        try { race(task, mapOf(B.NOUS to Fake(null, think = true))); fail("should give up") } catch (e: Exception) { assertTrue(e is HedgeRace.TooSlow) }
        assertTrue(testScheduler.currentTime - t0 in 120_000..120_300)
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
        // no early hedge; at the 8 s first-token deadline Cloud is started in parallel (Nous keeps running) and answers at 10 s
        assertEquals(B.CLOUD, o.step); assertFalse(o.hedged); assertEquals(10_000, o.firstVisibleMs)
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

    // ---- 0.46.5 (Rocky 21:00): slow but working brains must not be dropped by the first-token deadline ----

    class Busy : Exception("Blaze Cloud is busy right now. Tap Retry in a moment.")

    /** Runs a race recording when each brain was cancelled as a loser and when the first real word was shown. */
    private suspend fun TestScope.slowRace(lim: HedgeRace.Limits, brains: Map<B, Fake>, order: List<B>, cancels: MutableList<Pair<B, Long>>, fails: MutableList<String> = mutableListOf()) =
        HedgeRace.run(order, lim, true, call = { a -> play(brains.getValue(a.step), a) },
            onFail = { s, e -> fails += "$s:${e.message}" }, onCancelLoser = { s -> cancels += s to testScheduler.currentTime },
            now = { testScheduler.currentTime }, retryPick = { it.first() }, retryMs = BrainRace.RETRY_MS)

    @Test fun slowOnPhoneAndNousWithCloudFailingStillAnswerOnATask() = runTest {
        // phone 25 s to its first word, Nous 15 s, Cloud fails at once (busy). 0.46.4 dropped phone at 20 s and Nous at
        // 20 s after it started, then showed "Blaze Cloud is busy". Now: phone at 0, Nous hedged at 6 s (word at 21 s),
        // Cloud at 12 s fails at once, and the answer comes from Nous at 21 s; the phone is cancelled only then.
        val cancels = mutableListOf<Pair<B, Long>>(); val fails = mutableListOf<String>()
        val o = slowRace(task, mapOf(B.PHONE to Fake(25_000), B.NOUS to Fake(15_000), B.CLOUD to Fake(null, error = 200)), listOf(B.PHONE, B.NOUS, B.CLOUD), cancels, fails)
        assertEquals(B.NOUS, o.step); assertEquals(21_000, o.firstVisibleMs)
        assertEquals(listOf(B.PHONE to 21_000L), cancels)
        assertTrue("no loser cancelled before the first output", cancels.all { it.second >= o.firstVisibleMs })
        assertEquals(1, fails.size); assertTrue(fails.single().startsWith("CLOUD"))
    }

    @Test fun slowOnPhoneWinsWhenItIsFirstToSpeakEvenAfterItsDeadline() = runTest {
        // hot phone (no early hedge): phone 25 s, Nous started at the 20 s first-token deadline (word at 35 s), Cloud failing.
        // The deadline did not cancel the phone: its word at 25 s wins.
        val cancels = mutableListOf<Pair<B, Long>>()
        val hot = BrainRace.limits(BrainRace.Kind.TASK, hot = true)
        val o = slowRace(hot, mapOf(B.PHONE to Fake(25_000), B.NOUS to Fake(15_000), B.CLOUD to Fake(null, error = 200)), listOf(B.PHONE, B.NOUS, B.CLOUD), cancels)
        assertEquals(B.PHONE, o.step); assertEquals(25_000, o.firstVisibleMs)
        assertTrue(cancels.all { it.second >= 25_000 })
    }

    @Test fun shortMessageChatTurnKeepsSlowBrainsToo() = runTest {
        // Rocky's turn was short (chat limits: 8 s first token). phone 25 s, Nous 15 s (hedged at 3 s: word at 18 s), Cloud busy.
        val cancels = mutableListOf<Pair<B, Long>>()
        val o = slowRace(chat, mapOf(B.PHONE to Fake(25_000), B.NOUS to Fake(15_000), B.CLOUD to Fake(null, error = 200)), listOf(B.PHONE, B.NOUS, B.CLOUD), cancels)
        assertEquals(B.NOUS, o.step); assertEquals(18_000, o.firstVisibleMs)
        assertTrue(cancels.all { it.second >= 18_000 })
    }

    @Test fun cloudFailureReturnsToTheRemainingBrainsAndNoErrorWhileOneIsAlive() = runTest {
        // Cloud first (chat ranking), busy at 100 ms: Nous is asked at once and answers after 50 s; nothing is thrown meanwhile
        val cancels = mutableListOf<Pair<B, Long>>()
        val o = slowRace(chat, mapOf(B.CLOUD to Fake(null, error = 100), B.NOUS to Fake(50_000)), listOf(B.CLOUD, B.NOUS), cancels)
        assertEquals(B.NOUS, o.step); assertEquals(50_100, o.firstVisibleMs); assertFalse(o.retried)
    }

    @Test fun everyBrainHardFailsThenTheMostReliableIsAskedOnceMoreWithTheLongCap() = runTest {
        var nousCalls = 0
        val o = HedgeRace.run(listOf(B.PHONE, B.NOUS, B.CLOUD), chat, true, call = { a ->
            when (a.step) {
                B.NOUS -> if (++nousCalls == 1) { delay(300); throw Busy() } else { delay(70_000); a.visible(); "late but fine" }   // 70 s > the 60 s chat cap
                else -> { delay(200); throw Busy() }
            } }, retryPick = { BrainRace.retryPick(listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE, BackupRoute.Step.CLOUD)) { s -> if (s == BackupRoute.Step.RESERVE) 0.95 else 0.2 }
                ?.let { s -> B.values()[listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE, BackupRoute.Step.CLOUD).indexOf(s)] } },
            retryMs = BrainRace.RETRY_MS, now = { testScheduler.currentTime })
        assertEquals(B.NOUS, o.step); assertTrue(o.retried); assertEquals("late but fine", o.value); assertEquals(2, nousCalls)
    }

    @Test fun everythingFailsEvenTheRetryGivesTheCalmLine() = runTest {
        val steps = listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE, BackupRoute.Step.CLOUD)
        try {
            HedgeRace.run(steps, chat, true, call = { _ -> delay(100); throw LlmClient.LlmException(Cloud.BUSY, 503) },
                allFailed = { last, fatal -> BrainRace.finalError(steps, fatal, last, last) },
                retryPick = { BrainRace.retryPick(it) { null } }, retryMs = BrainRace.RETRY_MS, now = { testScheduler.currentTime })
            fail("should fail")
        } catch (e: LlmClient.LlmException) {
            assertEquals(BrainRace.TOO_LONG, e.message); assertFalse(e.message!!.contains("Blaze Cloud is busy"))
        }
    }
}
