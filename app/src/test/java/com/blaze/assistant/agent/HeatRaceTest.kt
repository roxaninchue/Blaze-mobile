package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.4 heat audit: the race sleeps instead of polling, cancels losers at once, and never hedges when told not to (warm phone). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeatRaceTest {
    enum class B { NOUS, CLOUD }
    private val task = HedgeRace.Limits(20_000, 6_000, 30_000, 45_000, 60_000)

    @Test fun noPollingWhileWaiting() = runTest {
        // one brain answering after 19 s: a 100 ms ticker would wake ~190 times; the race wakes only on deadlines / events
        var wakes = 0
        val o = HedgeRace.run(listOf(B.NOUS), task, true, call = { a -> delay(19_000); a.visible(); "ok" }, now = { wakes++; testScheduler.currentTime })
        assertEquals("ok", o.value)
        assertTrue("clock read $wakes times", wakes < 30)
    }

    @Test fun losersAreCancelledTheMomentAWinnerShows() = runTest {
        var loserCancelledAt = -1L
        var winnerAt = -1L
        HedgeRace.run(listOf(B.NOUS, B.CLOUD), task, true, call = { a ->
            if (a.step == B.NOUS) try { delay(600_000); "slow" } catch (e: CancellationException) { loserCancelledAt = testScheduler.currentTime; throw e }
            else { delay(1_000); winnerAt = testScheduler.currentTime; a.visible(); delay(5_000); "fast" }
        }, now = { testScheduler.currentTime })
        assertTrue(winnerAt > 0)
        assertTrue("loser cancelled at $loserCancelledAt, winner showed at $winnerAt", loserCancelledAt in winnerAt..winnerAt + 50)
    }

    @Test fun noHedgeWhenHot() = runTest {
        // hedgeMs = 0 (BrainRace.limits(hot = true)): one brain at a time; the next only after the first-token deadline
        val o = HedgeRace.run(listOf(B.NOUS, B.CLOUD), task.copy(hedgeMs = 0), true, call = { a ->
            if (a.step == B.NOUS) { delay(600_000); "slow" } else { delay(1_000); a.visible(); "fast" } }, now = { testScheduler.currentTime })
        assertFalse(o.hedged); assertEquals(B.CLOUD, o.step); assertTrue(o.firstVisibleMs in 21_000L..21_001L)
    }

    @Test fun setupBackoffNeverLoops() {
        assertTrue(BackupHeat.mayTry(0, 0, 1_000))
        assertFalse(BackupHeat.mayTry(1, 0 + 1, 5 * 60_000L))
        assertTrue(BackupHeat.mayTry(1, 1, 10 * 60_000L + 1))
        assertEquals(20 * 60_000L, BackupHeat.waitMs(2))
        assertEquals(6 * 3600_000L, BackupHeat.waitMs(9))
        assertFalse("stops after 3 failures", BackupHeat.mayTry(3, 1, Long.MAX_VALUE / 2))
    }

    @Test fun screenshotsAreRateCapped() {
        assertEquals(0, com.blaze.assistant.phone.ScreenHeat.shotWait(0, 5_000))
        assertEquals(500, com.blaze.assistant.phone.ScreenHeat.shotWait(1_000, 1_200))
        assertEquals(0, com.blaze.assistant.phone.ScreenHeat.shotWait(1_000, 2_000))
    }
}
