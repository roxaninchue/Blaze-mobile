package com.blaze.assistant.ui

import com.blaze.assistant.stage.StageTimer
import com.blaze.assistant.stage.StageTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.1 the basics every card was missing: quiz rounds / hint / review, timer presets, steps and cycles. Pure parts. */
class StageCardBasicsTest {
    private val qs = StageTools.questions("""[{"q":"2+2","options":["3","4","5"],"answer":1,"hint":"Even"},{"q":"Sky","options":["Blue","Green"],"answer":0},{"q":"H2O","options":["Water","Salt","Air","Gold"],"answer":"Water"}]""")

    @Test fun quizScoreReviewAndRetryWrong() {
        assertEquals("Even", qs[0].hint); assertEquals("", qs[1].hint)
        val all = StageQuiz.round(emptyMap(), qs.size)
        assertEquals(listOf(0, 1, 2), all)
        val ans = mapOf(0 to 1, 1 to 1, 2 to 2)
        assertEquals(1, StageQuiz.score(qs, ans, all))
        assertEquals(listOf(1, 2), StageQuiz.wrong(qs, ans, all))
        // Retry the wrong ones: the round is only those, the right answer kept.
        val p = mapOf("only" to "1,2", "answers" to StageQuiz.encode(ans - setOf(1, 2)))
        assertEquals(listOf(1, 2), StageQuiz.round(p, qs.size))
        assertEquals(mapOf(0 to 1), StageQuiz.answers(p))
        assertEquals(0, StageQuiz.score(qs, StageQuiz.answers(p), StageQuiz.round(p, qs.size)))
        assertEquals("0:1,2:3", StageQuiz.encode(mapOf(2 to 3, 0 to 1)))
        assertEquals(emptyMap<Int, Int>(), StageQuiz.answers(mapOf("answers" to "x:y,,3")))
        assertEquals(listOf(0, 1, 2), StageQuiz.round(mapOf("only" to "9,x"), qs.size))   // nothing valid = the whole quiz
    }

    @Test fun hintTakesAwayAWrongOptionNeverTheAnswer() {
        assertEquals(0, StageQuiz.hidden(qs[0]))     // first wrong option
        assertEquals(-1, StageQuiz.hidden(qs[1]))    // two options: nothing to take away
        assertTrue(StageQuiz.hidden(qs[2]) != qs[2].answer)
    }

    @Test fun timerPresetsStepsAndCycles() {
        assertEquals(listOf(5.0, 15.0, 25.0, 45.0), StageTimer.PRESETS)
        assertEquals(30.0, StageTimer.step(25.0, true), 0.0)
        assertEquals(20.0, StageTimer.step(25.0, false), 0.0)
        assertEquals(9.0, StageTimer.step(10.0, false), 0.0)
        assertEquals(6.0, StageTimer.step(5.0, true), 0.0)
        assertEquals(1.0, StageTimer.step(1.0, false), 0.0)
        assertEquals(720.0, StageTimer.step(720.0, true), 0.0)
        assertEquals("25", StageTimer.minutesText(25.0)); assertEquals("0.5", StageTimer.minutesText(0.5))
        val p = StageTimer.create("t", "Study", 25.0, pomodoro = true, breakMin = 5.0, cycles = 4)
        assertEquals(1 to 4, StageTimer.cycle(p))
        assertEquals(2 to 4, StageTimer.cycle(p.copy(phase = 2)))
        assertEquals(2 to 4, StageTimer.cycle(p.copy(phase = 3)))   // the break after focus 2
        assertEquals(null, StageTimer.cycle(StageTimer.create("t", "Tea", 3.0)))
        // Take a minute off a running timer: never below one second.
        val r = StageTimer.start(StageTimer.create("t", "x", 2.0), 0L)
        assertEquals(60_000L, StageTimer.remaining(StageTimer.add(r, -60_000L, 0L), 0L))
        assertEquals(1_000L, StageTimer.remaining(StageTimer.add(r, -10 * 60_000L, 0L), 0L))
    }
}
