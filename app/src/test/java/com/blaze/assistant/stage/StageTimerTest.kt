package com.blaze.assistant.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.0 Stage timer: end-time based (no drift), pause / resume, pomodoro phases, display rounding, word parsing. */
class StageTimerTest {
    private val t0 = 1_761_480_000_000L

    @Test fun notRunningUntilStart() {
        val t = StageTimer.create("timer1", "Study", 30.0)
        assertFalse(t.running); assertFalse(t.started)
        assertEquals(30 * 60_000L, StageTimer.remaining(t, t0 + 999_999))
        assertEquals("30:00", StageTimer.clock(StageTimer.remaining(t, t0)))
    }

    @Test fun endTimeNeverDrifts() {
        val s = StageTimer.start(StageTimer.create("t", "Study", 30.0), t0)
        assertEquals(t0 + 30 * 60_000L, s.endAt)
        // However late or irregular the ticks are, remaining comes from the end time.
        for (late in listOf(1L, 999L, 61_234L, 29 * 60_000L + 500L)) assertEquals(30 * 60_000L - late, StageTimer.remaining(s, t0 + late))
        assertEquals(0L, StageTimer.remaining(s, t0 + 31 * 60_000L))
        val (done, ended) = StageTimer.advance(s, t0 + 30 * 60_000L)
        assertTrue(done.done); assertEquals(1, ended); assertFalse(done.running)
    }

    @Test fun pauseResumeKeepsExactLeft() {
        var s = StageTimer.start(StageTimer.create("t", "x", 10.0), t0)
        s = StageTimer.pause(s, t0 + 4 * 60_000L + 250)
        assertEquals(6 * 60_000L - 250, s.left); assertFalse(s.running); assertTrue(s.started)
        assertEquals(s.left, StageTimer.remaining(s, t0 + 99 * 60_000L))   // paused: time doesn't pass
        s = StageTimer.start(s, t0 + 20 * 60_000L)
        assertEquals(t0 + 20 * 60_000L + 6 * 60_000L - 250, s.endAt)
    }

    @Test fun addAndReset() {
        var s = StageTimer.start(StageTimer.create("t", "x", 1.0), t0)
        s = StageTimer.add(s, 5 * 60_000L, t0)
        assertEquals(6 * 60_000L, StageTimer.remaining(s, t0))
        s = StageTimer.add(s, -60 * 60_000L, t0)
        assertEquals(1_000L, StageTimer.remaining(s, t0))
        s = StageTimer.reset(s)
        assertFalse(s.running); assertEquals(60_000L, s.left)
    }

    @Test fun pomodoroPhasesRollFromTheEndTime() {
        val p = StageTimer.create("p", "Pomodoro", 25.0, pomodoro = true, breakMin = 5.0, cycles = 2)
        assertEquals(listOf(25 * 60_000L, 5 * 60_000L, 25 * 60_000L), p.phases)
        val s = StageTimer.start(p, t0)
        // The phone notices 3 minutes late: the break still ends 5 minutes after the focus ended, not after it was noticed.
        val (b, n) = StageTimer.advance(s, t0 + 28 * 60_000L)
        assertEquals(1, n); assertEquals(1, b.phase); assertEquals("Break", b.phaseName)
        assertEquals(t0 + 30 * 60_000L, b.endAt)
        val (all, n2) = StageTimer.advance(s, t0 + 2 * 3_600_000L)
        assertEquals(3, n2); assertTrue(all.done)
    }

    @Test fun displayRoundsUpAndTicksOnSecondBoundaries() {
        assertEquals("0:01", StageTimer.clock(1)); assertEquals("0:00", StageTimer.clock(0))
        assertEquals("1:00:00", StageTimer.clock(3_600_000)); assertEquals("29:59", StageTimer.clock(29 * 60_000L + 59_000))
        assertEquals(250L, StageTimer.nextTickDelay(10_250)); assertEquals(1000L, StageTimer.nextTickDelay(10_000))
        assertEquals(0.5f, StageTimer.progress(StageTimer.start(StageTimer.create("t", "x", 2.0), t0), t0 + 60_000L), 0.0001f)
    }

    @Test fun parsesLooseMinutes() {
        assertEquals(30.0, StageTimer.parseMinutes("30")!!, 0.0)
        assertEquals(30.0, StageTimer.parseMinutes("30 min")!!, 0.0)
        assertEquals(90.0, StageTimer.parseMinutes("1h30")!!, 0.0)
        assertEquals(90.0, StageTimer.parseMinutes("1.5 hours")!!, 0.0)
        assertEquals(90.0, StageTimer.parseMinutes("1 hour 30 minutes")!!, 0.0)
        assertEquals(1.5, StageTimer.parseMinutes("90s")!!, 0.0)
        assertEquals(30.0, StageTimer.parseMinutes("half an hour")!!, 0.0)
        assertNull(StageTimer.parseMinutes("soon")); assertNull(StageTimer.parseMinutes("0"))
    }

    @Test fun jsonRoundTrip() {
        val s = StageTimer.start(StageTimer.create("p", "Pomodoro", 25.0, pomodoro = true), t0)
        assertEquals(s, TimerState.from(s.toJson()))
    }

    @Test fun words() {
        assertEquals("30 min", StageTimer.words(30 * 60_000L)); assertEquals("1 h 30 min", StageTimer.words(90 * 60_000L)); assertEquals("45 s", StageTimer.words(45_000))
    }
}
