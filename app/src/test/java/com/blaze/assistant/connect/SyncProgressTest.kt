package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.24: the cloud sync notification's numbers, and the steps a round reports. */
class SyncProgressTest {
    private val pull = SyncProgress.Phase.PULL
    private val push = SyncProgress.Phase.PUSH

    @Test fun percentKnownAndUnknown() {
        assertEquals(40, SyncProgress.percent(SyncProgress.Step(push, 4, 10)))
        assertEquals(50, SyncProgress.percent(SyncProgress.Step(pull, 9, 0, 512, 1024, full = true)))
        assertNull(SyncProgress.percent(SyncProgress.Step(pull, 9)))
        // Never 100 before the round ends, never past it when the copy grew.
        assertEquals(99, SyncProgress.percent(SyncProgress.Step(pull, 9, 0, 5000, 1024, full = true)))
        assertEquals(99, SyncProgress.percent(SyncProgress.Step(push, 10, 10)))
    }

    @Test fun text() {
        assertEquals("Uploading 3 of 10 \u00b7 30%", SyncProgress.text(SyncProgress.Step(push, 3, 10)))
        assertEquals("Downloading \u00b7 84 items", SyncProgress.text(SyncProgress.Step(pull, 84)))
        assertEquals("Downloading \u00b7 1 item", SyncProgress.text(SyncProgress.Step(pull, 1)))
        assertEquals("Downloading \u00b7 512 KB of 2.0 MB \u00b7 25%", SyncProgress.text(SyncProgress.Step(pull, 5, 0, 512 * 1024, 2L * 1024 * 1024, full = true)))
    }

    @Test fun smallBackgroundRoundsStaySilent() {
        assertFalse(SyncProgress.shouldShow(SyncProgress.Step(pull, 2), manual = false))
        assertFalse(SyncProgress.shouldShow(SyncProgress.Step(push, 0, 3), manual = false))
        assertTrue(SyncProgress.shouldShow(SyncProgress.Step(push, 0, 3), manual = true))
        assertTrue(SyncProgress.shouldShow(SyncProgress.Step(pull, 2, full = true), manual = false))
        assertTrue(SyncProgress.shouldShow(SyncProgress.Step(pull, 200, more = true), manual = false))
        assertTrue(SyncProgress.shouldShow(SyncProgress.Step(push, 0, 40), manual = false))
    }

    @Test fun endLines() {
        assertEquals("Synced", SyncProgress.endLine("Synced"))
        assertTrue(SyncProgress.ok("Synced, but 2 item(s) couldn't be opened with this passphrase."))
        assertFalse(SyncProgress.ok("Waiting for internet."))
        assertEquals("Waiting for internet", SyncProgress.endLine("Waiting for internet."))
        assertEquals("Blaze's service is busy", SyncProgress.endLine("Blaze's service is busy. Trying again in a minute."))
    }

    @Test fun aRoundReportsPullAndPushSteps() {
        val server = SyncEngineTest.FakeServer()
        val key = SyncCrypto.deriveKey("passphrase one", SyncCrypto.newSalt(), 1_000)
        server.check = SyncCrypto.makeCheck(key)
        val mem = SyncEngineTest.MapAdapter()
        for (i in 1..30) mem.set("m$i", i.toLong(), "fact $i")
        var stateA: String? = null
        val stepsA = ArrayList<SyncProgress.Step>()
        SyncEngine(server, listOf(mem), key, JsonSyncState({ stateA }, { stateA = it }), { 1_000_000L }, stepsA::add).run()
        val pushes = stepsA.filter { it.phase == push }
        assertEquals(30, pushes.first().total)
        assertEquals(30, pushes.last().done)

        // A second phone restores everything: pull steps only, marked full, items counted.
        val other = SyncEngineTest.MapAdapter()
        var stateB: String? = null
        val stepsB = ArrayList<SyncProgress.Step>()
        SyncEngine(server, listOf(other), key, JsonSyncState({ stateB }, { stateB = it }), { 1_000_000L }, stepsB::add).run()
        assertTrue(stepsB.all { it.phase == pull && it.full })
        assertEquals(30, stepsB.last().done)
        assertEquals(30, other.items.size)
    }
}
