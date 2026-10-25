package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.27: the "get on Wi-Fi and I'll download the backup" line instead of the plain Blaze Cloud error. */
class BackupNudgeTest {
    private val bytes = 78_269_653L
    private val ready = BackupNudge.Phone(supported = true, enabled = true, linuxInstalled = true, backupInstalled = false,
        online = true, unmetered = false)

    @Test fun busyCloudOnMobileDataSaysGetOnWifi() {
        assertEquals(BackupNudge.Pick.LATER, BackupNudge.pick(503, Cloud.BUSY, ready))
        assertEquals("Oops, I ran into an error. You can try again when Blaze Cloud is available, or get on Wi-Fi and I'll download a 78 MB feature to make sure this doesn't happen again.",
            BackupNudge.line(BackupNudge.Pick.LATER, bytes))
    }

    @Test fun onWifiTheDownloadStarts() {
        val wifi = ready.copy(unmetered = true)
        assertEquals(BackupNudge.Pick.DOWNLOADING, BackupNudge.pick(503, Cloud.BUSY, wifi))
        val line = BackupNudge.line(BackupNudge.Pick.DOWNLOADING, bytes)!!
        assertTrue(line.startsWith("Oops, I ran into an error."))
        assertTrue("downloading a 78 MB feature" in line)
        // Mobile data allowed for the download counts the same.
        assertEquals(BackupNudge.Pick.DOWNLOADING, BackupNudge.pick(503, Cloud.BUSY, ready.copy(mobileDataOk = true)))
    }

    @Test fun withoutLinuxItSaysLinuxIsNeeded() {
        val noLinux = ready.copy(linuxInstalled = false)
        assertEquals(BackupNudge.Pick.NEEDS_LINUX, BackupNudge.pick(503, Cloud.BUSY, noLinux))
        assertEquals(BackupNudge.Pick.NEEDS_LINUX, BackupNudge.pick(503, Cloud.BUSY, noLinux.copy(unmetered = true)))
        val line = BackupNudge.line(BackupNudge.Pick.NEEDS_LINUX, bytes)!!
        assertTrue("Alpine Linux" in line)
        assertTrue("78 MB" in line)
    }

    @Test fun cloudFailuresThatQualify() {
        assertEquals(BackupNudge.Pick.LATER, BackupNudge.pick(0, Cloud.UNREACHABLE, ready))
        assertEquals(BackupNudge.Pick.LATER, BackupNudge.pick(429, Cloud.BUSY, ready))
        assertEquals(BackupNudge.Pick.LATER, BackupNudge.pick(500, "Blaze Cloud is busy right now. Tap Retry in a moment.", ready))
        assertEquals(BackupNudge.Pick.LATER, BackupNudge.pick(502, "Blaze · blaze-auto said (502): upstream", ready))
        assertEquals(BackupNudge.Pick.LATER, BackupNudge.pick(400, Cloud.BUSY, ready))
    }

    @Test fun neverForSignInWaitlistOrAllowance() {
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(401, Cloud.SIGNED_OUT, ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(401, Cloud.EXPIRED, ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(403, Cloud.EXPIRED, ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(403, Cloud.WAITLISTED, ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(403, Cloud.REJECTED, ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(429, "You've used today's free messages. They come back tomorrow.", ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(429, "quota_exceeded", ready))
    }

    @Test fun neverForTooBigOrUnknownErrors() {
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(413, "too large", ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(0, PlainError.CONTEXT, ready))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(415, "unsupported", ready))
    }

    @Test fun onlyOnPhonesThatCanHaveTheBackupAndDontYet() {
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(503, Cloud.BUSY, ready.copy(supported = false)))    // 32-bit
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(503, Cloud.BUSY, ready.copy(backupInstalled = true)))
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(503, Cloud.BUSY, ready.copy(enabled = false)))      // switched off
        assertEquals(BackupNudge.Pick.NONE, BackupNudge.pick(0, Cloud.UNREACHABLE, ready.copy(online = false)))   // no internet
        assertNull(BackupNudge.line(BackupNudge.Pick.NONE, bytes))
    }

    @Test fun sizeIsTheRealDownload() {
        assertEquals(78L, BackupNudge.mb(BackupBrain.DOWNLOAD_BYTES))
        assertEquals(78L, BackupNudge.mb(bytes))
        assertEquals(1L, BackupNudge.mb(10))
    }

    @Test fun automaticDownloadOnWifi() {
        val wifi = ready.copy(unmetered = true)
        assertTrue(BackupNudge.autoInstall(wifi, 1_000_000, 0))
        assertFalse(BackupNudge.autoInstall(ready, 1_000_000, 0))                                  // mobile data
        assertTrue(BackupNudge.autoInstall(ready.copy(mobileDataOk = true), 1_000_000, 0))
        assertFalse(BackupNudge.autoInstall(wifi.copy(linuxInstalled = false), 1_000_000, 0))
        assertFalse(BackupNudge.autoInstall(wifi.copy(backupInstalled = true), 1_000_000, 0))
        assertFalse(BackupNudge.autoInstall(wifi.copy(enabled = false), 1_000_000, 0))
        assertFalse(BackupNudge.autoInstall(wifi.copy(supported = false), 1_000_000, 0))
        assertFalse(BackupNudge.autoInstall(wifi.copy(online = false), 1_000_000, 0))
        // After a failed try, not again on every Wi-Fi flicker.
        assertFalse(BackupNudge.autoInstall(wifi, 1_000_000, 1_000_000 - 60_000))
        assertTrue(BackupNudge.autoInstall(wifi, 1_000_000, 1_000_000 - BackupNudge.AUTO_RETRY_MS))
    }
}
