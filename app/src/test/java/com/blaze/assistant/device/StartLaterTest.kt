package com.blaze.assistant.device

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lazy start: work queued in BlazeApp.onCreate runs after the first frame, once, in order, each step on its own. */
class StartLaterTest {
    private val ran = mutableListOf<String>()
    private val posted = mutableListOf<Runnable>()

    @Before fun setUp() {
        StartLater.resetForTests()
        StartLater.post = { r -> posted += r }
        StartLater.runStep = { name, block -> try { block() } catch (e: Exception) { ran += "failed:$name" } }
    }

    @After fun tearDown() { StartLater.resetForTests() }

    private fun drain() { while (posted.isNotEmpty()) posted.removeAt(0).run() }

    @Test fun nothingRunsBeforeTheFirstFrame() {
        StartLater.add("a") { ran += "a" }
        StartLater.add("b") { ran += "b" }
        drain()
        assertTrue(ran.isEmpty())
        assertEquals(listOf("a", "b"), StartLater.pending())
        assertFalse(StartLater.firstFrame.value)
    }

    @Test fun firstFrameRunsEachJobAsItsOwnMessageInOrder() {
        StartLater.add("a") { ran += "a" }
        StartLater.add("b") { ran += "b" }
        StartLater.onFirstFrame { ran += "hook" }
        assertTrue(StartLater.release("first frame"))
        assertTrue(StartLater.firstFrame.value)
        assertEquals("one main-thread message per job (frames can draw in between)", 3, posted.size)
        drain()
        assertEquals(listOf("hook", "a", "b"), ran)
        assertTrue(StartLater.pending().isEmpty())
    }

    @Test fun releasedOnceAndLateJobsRunAtOnce() {
        StartLater.add("a") { ran += "a" }
        assertTrue(StartLater.release("first frame"))
        assertFalse("the fallback after a drawn frame does nothing", StartLater.release("fallback"))
        drain()
        StartLater.add("late") { ran += "late" }
        StartLater.onFirstFrame { ran += "late hook" }
        drain()
        assertEquals(listOf("a", "late", "late hook"), ran)
    }

    @Test fun aFailingJobDoesNotStopTheOthers() {
        StartLater.add("bad") { throw IllegalStateException("boom") }
        StartLater.add("good") { ran += "good" }
        StartLater.release("fallback")
        drain()
        assertEquals(listOf("failed:bad", "good"), ran)
    }

    @Test fun fallbackWaitsLongerThanAColdStart() {
        assertTrue(StartLater.FALLBACK_MS in 1_000L..10_000L)
    }
}
