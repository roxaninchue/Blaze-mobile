package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.26: Blaze Cloud first, the on-device backup second, the Nous reserve last; when to move on. */
class BackupRouteTest {
    @Test fun orderAndReserveHeaders() {
        assertEquals(listOf(BackupRoute.Step.CLOUD, BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE), BackupRoute.plan(true))
        assertEquals(listOf(BackupRoute.Step.CLOUD), BackupRoute.plan(false))
        // With the backup ready, the first call keeps the reserve for later; without it the gateway adds it itself.
        assertEquals("skip", BackupRoute.reserveHeader(BackupRoute.Step.CLOUD, true))
        assertEquals("", BackupRoute.reserveHeader(BackupRoute.Step.CLOUD, false))
        assertEquals("only", BackupRoute.reserveHeader(BackupRoute.Step.RESERVE, true))
    }

    @Test fun firstChunkDeadlineIsConfigurable() {
        assertEquals(15_000L, BackupRoute.ttftMs(true, null))
        assertEquals(45_000L, BackupRoute.ttftMs(false, null))
        assertEquals(8_000L, BackupRoute.ttftMs(true, 8_000))
        assertEquals(24_000L, BackupRoute.ttftMs(false, 8_000))
        assertEquals(3_000L, BackupRoute.ttftMs(true, 500))          // floor
        assertEquals(120_000L, BackupRoute.ttftMs(true, 999_999))    // cap
        assertEquals(0L, BackupRoute.ttftMs(true, 0))                // 0 = errors only
        assertEquals(15_000L, BackupRoute.ttftMs(true, -5))
    }

    @Test fun tooSlowOnlyBeforeAnySignOfLife() {
        assertFalse(BackupRoute.tooSlow(0, 14_999, false, 15_000))
        assertTrue(BackupRoute.tooSlow(0, 15_000, false, 15_000))
        assertFalse(BackupRoute.tooSlow(0, 60_000, true, 15_000))
        assertFalse(BackupRoute.tooSlow(0, 60_000, false, 0))
        assertTrue(BackupRoute.alive(100, 150, false, 0))            // a chunk after the start
        assertFalse(BackupRoute.alive(100, 50, false, 0))            // an older call's chunk doesn't count
        assertTrue(BackupRoute.alive(100, 0, true, 120))             // a non-streamed answer arrived
        assertFalse(BackupRoute.alive(100, 0, true, 90))             // an older call's flag
    }

    @Test fun movesOnForOutagesNotForAccountProblems() {
        assertTrue(BackupRoute.moveOn(503, Cloud.BUSY))
        assertTrue(BackupRoute.moveOn(0, Cloud.UNREACHABLE))
        assertTrue(BackupRoute.moveOn(429, "rate limited"))
        assertTrue(BackupRoute.moveOn(EmptyReply.CODE, EmptyReply.FAILED))
        assertFalse(BackupRoute.moveOn(401, Cloud.EXPIRED))
        assertFalse(BackupRoute.moveOn(403, Cloud.WAITLISTED))
        assertFalse(BackupRoute.moveOn(401, Cloud.SIGNED_OUT))
        assertFalse(BackupRoute.moveOn(429, "You've used today's free messages. They come back tomorrow."))
        assertFalse(BackupRoute.moveOn(429, "quota_exceeded"))
    }

    // v0.45.28: Rocky on 4G without the backup brain saw "Blaze Cloud is busy" and the Nous reserve was never asked.
    @Test fun noBackupBusyStillGoesToTheReserve() {
        val R = listOf(BackupRoute.Step.RESERVE)
        assertEquals(R, BackupRoute.fallbacks(false, 503, Cloud.BUSY, false))
        assertEquals(R, BackupRoute.fallbacks(false, 429, Cloud.BUSY, false))                 // busy, not the allowance
        assertEquals(R, BackupRoute.fallbacks(false, 0, Cloud.UNREACHABLE, false))
        assertEquals(R, BackupRoute.fallbacks(false, 0, "Blaze Cloud too slow (no first chunk in 15s)", false))
        assertEquals(R, BackupRoute.fallbacks(false, 502, "Blaze · blaze-auto said (502): upstream", false))
        // with the backup ready: backup first, then the reserve
        assertEquals(listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE), BackupRoute.fallbacks(true, 503, Cloud.BUSY, false))
        // never for what no other brain can fix, nor a request the services refuse as such
        assertTrue(BackupRoute.fallbacks(false, 401, Cloud.SIGNED_OUT, false).isEmpty())
        assertTrue(BackupRoute.fallbacks(true, 403, Cloud.WAITLISTED, false).isEmpty())
        assertTrue(BackupRoute.fallbacks(false, 429, "You've used today's free messages. They come back tomorrow.", false).isEmpty())
        assertTrue(BackupRoute.fallbacks(true, 400, "bad tool shape", true).isEmpty())
    }

    @Test fun partialStreamFailureFallsBack() {
        val broke = "Blaze · blaze-auto stopped sending (timed out mid-reply)."
        assertTrue(BackupRoute.brokeMidReply(broke))
        assertTrue(BackupRoute.brokeMidReply("unexpected end of stream on https://gateway"))
        assertFalse(BackupRoute.brokeMidReply(Cloud.SIGNED_OUT))
        assertEquals(listOf(BackupRoute.Step.RESERVE), BackupRoute.fallbacks(false, 0, broke, false))
        assertEquals(listOf(BackupRoute.Step.BACKUP, BackupRoute.Step.RESERVE), BackupRoute.fallbacks(true, 0, broke, false))
        // the reserve call is the gateway call with X-Blaze-Reserve: only
        assertEquals("only", BackupRoute.reserveHeader(BackupRoute.Step.RESERVE, false))
    }

    @Test fun llmClientNeverSkipsTheReserveWithoutBackup() {
        val src = HideModels19Test.srcFile("java/com/blaze/assistant/agent/LlmClient.kt").readText()
        assertFalse(src.contains("if (e is TooSlow && !backupReady) throw"))
        // 0.46.4: every call of the loop runs through the hedged race (BrainRace order: Nous / Blaze Cloud always, phone when warm)
        assertTrue(src.contains("HedgeRace.run("))
        assertTrue(src.contains("BrainRace.order(kind, warm, token.isNotBlank())"))
    }
}
