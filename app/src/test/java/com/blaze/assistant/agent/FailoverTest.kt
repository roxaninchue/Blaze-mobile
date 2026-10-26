package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 0.46.3: Blaze Cloud busy at ANY turn of a task (turn 1, turn 8) never shows an error while a brain is left: the call is
 * answered by the backup (or the reserve) with the same context and the task finishes. Simulates the agent's tool loop:
 * each turn is one model call through [Failover.run]; an exception escaping a turn would be the error bubble.
 */
class FailoverTest {
    private val busy = LlmClient.LlmException(Cloud.BUSY, 503)

    /** A 10-turn task: turns 1..9 ask for a tool, turn 10 answers. Returns (bubbles, who answered each turn, status lines). */
    private fun task(busyAt: Set<Int>, backupReady: Boolean, backupFails: Boolean = false, reserveFails: Boolean = false):
            Triple<List<String>, List<String>, List<String>> {
        val bubbles = ArrayList<String>(); val via = ArrayList<String>(); val status = ArrayList<String>()
        val context = ArrayList<String>()
        runBlocking {
            for (turn in 1..10) {
                try {
                    val out = Failover.run(
                        cloud = { if (turn in busyAt) throw busy; "cloud t$turn after ${context.size}" },
                        backupReady = backupReady,
                        backup = { if (backupFails) throw IllegalStateException("backup down"); "backup t$turn after ${context.size}" },
                        reserve = { if (reserveFails) throw busy; "reserve t$turn after ${context.size}" },
                        onSwitch = { status.add(it) })
                    via.add(out.via.name)
                    context.add(out.value)   // the tool result goes back into the same conversation
                    if (turn == 10) bubbles.add("BLAZE: done")
                } catch (e: LlmClient.LlmException) {
                    bubbles.add("ERROR: ${e.message}"); break
                }
            }
        }
        return Triple(bubbles, via, status)
    }

    @Test fun busyAtTurn1FinishesViaBackupWithNoErrorBubble() {
        val (bubbles, via, status) = task(setOf(1), backupReady = true)
        assertEquals(listOf("BLAZE: done"), bubbles)
        assertEquals("BACKUP", via[0])
        assertTrue(via.drop(1).all { it == "CLOUD" })
        assertEquals(listOf(Failover.SWITCHED), status)
    }

    @Test fun busyAtTurn8MidTaskContinuesWithItsContext() {
        val (bubbles, via, _) = task(setOf(8), backupReady = true)
        assertEquals(listOf("BLAZE: done"), bubbles)
        assertEquals(10, via.size)
        assertEquals("BACKUP", via[7])
    }

    @Test fun withoutTheBackupTheReserveAnswers() {
        val (bubbles, via, _) = task(setOf(1, 8), backupReady = false)
        assertEquals(listOf("BLAZE: done"), bubbles)
        assertEquals("RESERVE", via[0]); assertEquals("RESERVE", via[7])
    }

    @Test fun backupDownThenReserve() {
        val (bubbles, via, _) = task(setOf(8), backupReady = true, backupFails = true)
        assertEquals(listOf("BLAZE: done"), bubbles)
        assertEquals("RESERVE", via[7])
    }

    @Test fun errorOnlyWhenEveryBrainFailed() {
        val (bubbles, _, _) = task(setOf(8), backupReady = true, backupFails = true, reserveFails = true)
        assertEquals(1, bubbles.size)
        assertTrue(bubbles[0], bubbles[0].startsWith("ERROR") && bubbles[0].contains("Retry"))
    }

    @Test fun retryOnceComesBeforeTheBackup() = runBlocking {
        var n = 0
        val out = Failover.run(cloud = { if (n++ == 0) throw busy; "second try" }, backupReady = true, backup = { "backup" }, reserve = { "reserve" }, retryOnce = true)
        assertEquals("second try", out.value); assertTrue(out.retried)
    }

    @Test fun signInAndAllowanceAreNotFailedOver() = runBlocking {
        for (e in listOf(LlmClient.LlmException(Cloud.SIGNED_OUT, 401), LlmClient.LlmException("You've used today's free messages. They come back tomorrow.", 429))) {
            try { Failover.run(cloud = { throw e }, backupReady = true, backup = { "b" }, reserve = { "r" }); fail("should throw") }
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
        assertTrue(CloudRuns.handOff("models_busy"))
        assertTrue(CloudRuns.handOff("no_reply"))
        assertTrue(CloudRuns.handOff("503"))
        assertFalse(CloudRuns.handOff("quota_exceeded"))
        assertFalse(CloudRuns.handOff("too_large"))
        assertTrue(CloudRuns.handOff(LlmClient.LlmException(Cloud.BUSY, 503)))
        assertFalse(CloudRuns.handOff(LlmClient.LlmException(CloudRuns.STOPPED, 0)))
        assertFalse(CloudRuns.handOff(CloudRuns.RunRefused("You've used today's free messages.", 429)))
        assertFalse(CloudRuns.handOff(LlmClient.LlmException("too large", 413)))
    }

    @Test fun healthLine() {
        assertEquals("", BackupStatus.healthLine(null, ""))
        assertTrue(BackupStatus.healthLine(true, "17:40").contains("answering (17:40)"))
        assertTrue(BackupStatus.healthLine(false, "").contains("not answering"))
    }
}
