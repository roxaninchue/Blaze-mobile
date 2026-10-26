package com.blaze.assistant.agent

import com.blaze.assistant.agent.BackupStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.28: Settings › Backup brain status line, buttons and the wait stepper. */
class BackupStatusTest {
    private val p = BackupNudge.Phone(supported = true, enabled = true, linuxInstalled = true, backupInstalled = false, online = true, unmetered = false)

    @Test fun states() {
        assertEquals(State.NOT_SUPPORTED, BackupStatus.state(p.copy(supported = false), false, 0, 0))
        assertEquals(State.INSTALLED, BackupStatus.state(p.copy(backupInstalled = true), false, 0, 100))
        assertEquals(State.RESTING, BackupStatus.state(p.copy(backupInstalled = true), false, 500, 100))
        assertEquals(State.DOWNLOADING, BackupStatus.state(p, true, 0, 0))
        assertEquals(State.OFF, BackupStatus.state(p.copy(enabled = false), false, 0, 0))
        assertEquals(State.NEEDS_LINUX, BackupStatus.state(p.copy(linuxInstalled = false), false, 0, 0))
        assertEquals(State.WAITING_WIFI, BackupStatus.state(p, false, 0, 0))
        assertEquals(State.NOT_DOWNLOADED, BackupStatus.state(p.copy(unmetered = true), false, 0, 0))
        assertEquals(State.NOT_DOWNLOADED, BackupStatus.state(p.copy(mobileDataOk = true), false, 0, 0))
    }

    @Test fun linesAndButtons() {
        assertEquals("On-phone brain ready.", BackupStatus.line(State.INSTALLED, "1.19.4", 78))
        assertTrue(BackupStatus.line(State.RESTING, "1.19.4", 78, "14:05").contains("back at 14:05"))
        assertTrue(BackupStatus.line(State.NEEDS_LINUX, "1.19.4", 78).contains("one-time"))
        assertTrue(BackupStatus.line(State.WAITING_WIFI, "1.19.4", 78).contains("Wi-Fi") && BackupStatus.line(State.WAITING_WIFI, "1.19.4", 78).contains("78 MB"))
        assertTrue(BackupStatus.line(State.NOT_SUPPORTED, "1.19.4", 78).contains("Not supported"))
        assertTrue(BackupStatus.canDownload(State.WAITING_WIFI) && BackupStatus.canDownload(State.NOT_DOWNLOADED))
        assertFalse(BackupStatus.canDownload(State.INSTALLED) || BackupStatus.canDownload(State.NEEDS_LINUX) || BackupStatus.canDownload(State.DOWNLOADING))
        assertTrue(BackupStatus.needsLinux(State.NEEDS_LINUX))
        assertTrue(BackupStatus.confirmMobileData(p)); assertFalse(BackupStatus.confirmMobileData(p.copy(unmetered = true)))
    }

    @Test fun waitStepper() {
        assertEquals(15, BackupStatus.waitSeconds(""))
        assertEquals(0, BackupStatus.waitSeconds("0"))
        assertEquals(8, BackupStatus.waitSeconds("8000"))
        assertEquals(20, BackupStatus.stepWait(15, true)); assertEquals(10, BackupStatus.stepWait(15, false))
        assertEquals(3, BackupStatus.stepWait(0, true)); assertEquals(5, BackupStatus.stepWait(3, true))
        assertEquals(0, BackupStatus.stepWait(3, false)); assertEquals(3, BackupStatus.stepWait(5, false))
        assertEquals(120, BackupStatus.stepWait(120, true))
        assertEquals("Only on errors", BackupStatus.waitLabel(0)); assertEquals("15 s", BackupStatus.waitLabel(15))
    }
}
