package com.blaze.assistant.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.46.6 bug 1 (handoff 27 Oct): "Free usage exceeded" from ONE on-phone model rested the whole on-phone brain and the call
 * ended there. Now only that model rests (until its reset, or a backoff), the next on-phone model is asked, and only when
 * every model rests does the brain give way, as a non-fatal 429, to Nous and then the gateway.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrainRestTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()

    /** What OpenCode's retry event becomes ([BackupBrain.FreeLimit] in the app): 429 + the reset (0 = not given). */
    class Free(val until: Long) : LlmClient.LlmException("Free usage exceeded, subscribe to Go", 429)

    private val ids = listOf("big-pickle", "glm-5.3-flash-free", "deepseek-v4.1-flash-free")
    private var clock = 1_000_000L
    private val now = { clock }
    private val freeReset: (Throwable) -> Long? = { e -> (e as? Free)?.until }

    @Test fun restLastsUntilTheResetCappedOrABackoff() {
        val t = 10_000_000L
        assertEquals(t + 5 * 60_000L, BrainRest.until(t + 5 * 60_000L, t, 1))          // reset given: until then
        assertEquals(t + BrainRest.MAX_MS, BrainRest.until(t + 6 * 3600_000L, t, 1))    // far-off reset: capped (30 min)
        assertEquals(t + 2 * 60_000L, BrainRest.until(0L, t, 1))                        // no reset: backoff 2, 4, 8 … min
        assertEquals(t + 4 * 60_000L, BrainRest.until(0L, t, 2))
        assertEquals(t + 8 * 60_000L, BrainRest.until(t - 1, t, 3))                    // a reset in the past counts as none
        assertEquals(t + BrainRest.MAX_MS, BrainRest.until(0L, t, 40))
    }

    @Test fun freeUsageOnOneModelRestsOnlyThatModelAndTheNextOnPhoneModelAnswers() = runTest {
        val book = BrainRest.Book()
        val asked = mutableListOf<String>()
        val rested = mutableListOf<String>()
        val v = BrainRest.run(ids, book, call = { id ->
            asked += id
            if (id == "big-pickle") throw Free(clock + 10 * 60_000L)
            "answer from $id"
        }, freeReset = freeReset, onRest = { id, _ -> rested += id }, now = now)
        assertEquals("answer from glm-5.3-flash-free", v)
        assertEquals(listOf("big-pickle", "glm-5.3-flash-free"), asked)
        assertEquals(listOf("big-pickle"), rested)
        assertTrue(book.resting("big-pickle", clock)); assertEquals(clock + 10 * 60_000L, book.restingUntil("big-pickle"))
        assertFalse(book.resting("glm-5.3-flash-free", clock)); assertFalse(book.resting("deepseek-v4.1-flash-free", clock))
        // the brain as a whole is NOT resting (it used to be: freeUntil for every model)
        assertEquals(0L, book.allRestingUntil(ids, clock))

        // the next call skips the resting model without asking it
        asked.clear()
        BrainRest.run(ids, book, call = { id -> asked += id; "ok" }, freeReset = freeReset, now = now)
        assertEquals(listOf("glm-5.3-flash-free"), asked)

        // after its reset it is asked again, first
        clock += 10 * 60_000L + 1
        asked.clear()
        BrainRest.run(ids, book, call = { id -> asked += id; "ok" }, freeReset = freeReset, now = now)
        assertEquals(listOf("big-pickle"), asked)
    }

    @Test fun noResetGivenMeansABackoffThatGrowsAndAnAnswerClearsIt() = runTest {
        val book = BrainRest.Book()
        val one = listOf("beam-free", "flint-40b-free")
        val call: suspend (String) -> String = { id -> if (id == "beam-free") throw Free(0L) else "ok" }
        BrainRest.run(one, book, call, freeReset, now = now)
        assertEquals(clock + 2 * 60_000L, book.restingUntil("beam-free"))
        clock += 2 * 60_000L + 1
        BrainRest.run(one, book, call, freeReset, now = now)
        assertEquals(clock + 4 * 60_000L, book.restingUntil("beam-free"))   // second strike: 4 min
        clock += 4 * 60_000L + 1
        BrainRest.run(one, book, call = { _ -> "fine now" }, freeReset = freeReset, now = now)
        assertFalse(book.resting("beam-free", clock))
        BrainRest.run(one, book, call, freeReset, now = now)
        assertEquals(clock + 2 * 60_000L, book.restingUntil("beam-free"))   // backoff started over
    }

    @Test fun everyModelOutOfFreeUsageGivesWayAsANonFatal429() = runTest {
        val book = BrainRest.Book()
        val asked = mutableListOf<String>()
        val e = runCatching {
            BrainRest.run<String>(ids, book, call = { id -> asked += id; throw Free(clock + (ids.indexOf(id) + 1) * 60_000L) }, freeReset = freeReset, now = now)
        }.exceptionOrNull()
        assertTrue("got $e", e is BrainRest.AllResting)
        assertEquals(ids, asked)                       // every on-phone model was asked before giving way
        assertEquals(429, (e as BrainRest.AllResting).code)
        assertEquals(clock + 60_000L, e.until)          // back when the first model is back
        assertEquals(clock + 60_000L, book.allRestingUntil(ids, clock))
        // Nous and the gateway still get their turn: not fatal for the race, fixable for the old failover
        assertTrue(Failover.fixable(e))
        assertTrue(BackupRoute.moveOn(e.code, e.message.orEmpty()))
        // while all rest, a call asks nobody on the phone and gives way at once
        asked.clear()
        assertTrue(runCatching { BrainRest.run(ids, book, call = { id -> asked += id; "x" }, freeReset = freeReset, now = now) }.exceptionOrNull() is BrainRest.AllResting)
        assertTrue(asked.isEmpty())
    }

    @Test fun otherErrorsAndEmptyRepliesMoveToTheNextModelWithoutResting() = runTest {
        val book = BrainRest.Book()
        val errs = mutableListOf<String>()
        val v = BrainRest.run(ids, book, call = { id ->
            when (id) { "big-pickle" -> throw LlmClient.LlmException("backup: stalled", 0); "glm-5.3-flash-free" -> ""; else -> "real" }
        }, freeReset = freeReset, empty = { it.isEmpty() }, onError = { id, _ -> errs += id }, now = now)
        assertEquals("real", v)
        assertEquals(listOf("big-pickle", "glm-5.3-flash-free"), errs)
        assertEquals(0L, book.allRestingUntil(ids, clock)); assertFalse(book.resting("big-pickle", clock))
        // all fail with plain errors: the last error, not AllResting
        val e = runCatching { BrainRest.run<String>(ids, book, call = { _ -> throw LlmClient.LlmException("backup: prompt 500", 500) }, freeReset = freeReset, now = now) }.exceptionOrNull()
        assertEquals(500, (e as LlmClient.LlmException).code); assertFalse(e is BrainRest.AllResting)
    }

    @Test fun cancellationPassesThroughAndNothingRests() = runTest {
        val book = BrainRest.Book()
        try {
            BrainRest.run<String>(ids, book, call = { _ -> throw kotlinx.coroutines.CancellationException("stop") }, freeReset = freeReset, now = now)
            fail("cancel must pass")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0L, book.allRestingUntil(ids, clock))
    }

    @Test fun onPhoneFailoverThenNousThenGatewayInTheRace() = runTest {
        // the whole path of one tool turn: every on-phone model out of free usage → Nous answers; the gateway is never asked
        val book = BrainRest.Book()
        val asked = mutableListOf<String>()
        val o = HedgeRace.run(listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE, BackupRoute.Step.CLOUD),
            BrainRace.limits(BrainRace.Kind.TASK, hot = false), true, parallel = false,
            call = { a ->
                asked += a.step.name
                when (a.step) {
                    BackupRoute.Step.BACKUP -> BrainRest.run<String>(ids, book, call = { _ -> throw Free(0L) }, freeReset = freeReset, now = now)
                    BackupRoute.Step.RESERVE -> { a.visible(); "nous" }
                    BackupRoute.Step.CLOUD -> "gateway"
                }
            },
            fatal = { step, e -> step == BackupRoute.Step.CLOUD && !Failover.fixable(e) },
            now = { testScheduler.currentTime })
        assertEquals(BackupRoute.Step.RESERVE, o.step); assertEquals("nous", o.value)
        assertEquals(listOf("BACKUP", "RESERVE"), asked)

        // one on-phone model out of free usage, the next one answers: neither Nous nor the gateway is asked
        asked.clear()
        val book2 = BrainRest.Book()
        val o2 = HedgeRace.run(listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE, BackupRoute.Step.CLOUD),
            BrainRace.limits(BrainRace.Kind.TASK, hot = false), true, parallel = false,
            call = { a ->
                asked += a.step.name
                if (a.step == BackupRoute.Step.BACKUP) BrainRest.run(ids, book2, call = { id -> if (id == ids[0]) throw Free(0L) else { a.visible(); "phone:$id" } }, freeReset = freeReset, now = now)
                else "other"
            }, now = { testScheduler.currentTime })
        assertEquals("phone:${ids[1]}", o2.value); assertEquals(listOf("BACKUP"), asked)
    }

    @Test fun wiring() {
        val src = listOf(File("src/main/java/com/blaze/assistant/agent/BackupBrain.kt"), File("app/src/main/java/com/blaze/assistant/agent/BackupBrain.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("BrainRest.run(list.map { it.id }, rests,"))
        assertTrue(src.contains("freeReset = { e -> (e as? FreeLimit)?.until }"))
        // the old "rest them all" is gone, and the brain rests only while every model rests
        assertFalse(src.contains("list.forEach { cooling[it.id] = until }"))
        assertTrue(src.contains("freeUntil = rests.allRestingUntil("))
        assertFalse(src.contains("System.currentTimeMillis() + 3600_000L)"))
    }
}
