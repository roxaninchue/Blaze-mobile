package com.blaze.assistant.ui.splash

import com.blaze.assistant.home.HomeGrid
import com.blaze.assistant.ui.HomeSkyRule
import com.blaze.assistant.ui.HomeSkySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.48.0 RC3 Home entry sequence (timed from the approved videos) and the sky rule. */
class HomeSequenceTest {
    private val all = HomeSequence.order(hasTasks = true)
    private val noTasks = HomeSequence.order(hasTasks = false)

    @Test fun orderLikeTheVideo() {
        assertEquals(listOf(HomeGrid.Card.INTERESTS, HomeGrid.Card.MUSIC, HomeGrid.Card.TODO, HomeGrid.Card.NEXT_UP, HomeGrid.Card.QUICK), all)
        assertFalse(HomeGrid.Card.TODO in noTasks)
        assertEquals(all, HomeSequence.orderFor(5)); assertEquals(noTasks, HomeSequence.orderFor(4))
    }

    @Test fun cardStartsMatchTheVideo() {
        // video (s): splash hands over ~2.4; Interests chips 5.5, Music 8.2, To-do 10.4, Next up 13.0, Quick actions ~14.7
        val s = HomeSequence.starts(all, HomeIntroSpec.CARDS_AT).map { (it + 2_400) / 1000.0 }
        val video = listOf(5.5, 8.2, 10.4, 13.0, 14.7)
        s.zip(video).forEach { (a, v) -> assertEquals("start $a vs video $v", v, a, 0.35) }
        // the chat bar appears last, ~17 s into the video
        val bar = (HomeSequence.cardsEnd(all, HomeIntroSpec.CARDS_AT) + 2_400) / 1000.0
        assertEquals(16.9, bar, 0.6)
        // without tasks the To-do step is simply left out
        assertEquals(HomeSequence.TODO_SPAN, HomeSequence.cardsEnd(all, 0) - HomeSequence.cardsEnd(noTasks, 0))
    }

    @Test fun everyCardIsHiddenBeforeAndSettledAfter() {
        for (c in all) {
            assertEquals(0f, HomeSequence.frame(c, -1).alpha)
            val f = HomeSequence.frame(c, HomeSequence.span(c))
            assertEquals("$c alpha", 1f, f.alpha); assertEquals("$c move", 1f, f.move, 1e-4f); assertEquals("$c fill", 1f, f.fill, 1e-4f)
            assertEquals("$c outline", 1f, f.outline, 1e-4f); assertEquals("$c lift", 0f, f.lift, 1e-4f); assertEquals("$c scale", 1f, f.scale * f.contentScale, 1e-4f)
        }
    }

    @Test fun interestsChipsComeBeforeTheirFrame() {
        val early = HomeSequence.frame(HomeGrid.Card.INTERESTS, HomeSequence.CHIP_STAGGER * 3 + HomeSequence.CHIP_POP_MS)
        assertEquals(0f, early.frameAlpha); assertEquals(0f, early.move)
        assertTrue(early.contentScale > 1.2f)                         // large at the centre
        assertEquals(1f, HomeSequence.chipPop(2, HomeSequence.CHIP_STAGGER * 2 + HomeSequence.CHIP_POP_MS))
        assertTrue(HomeSequence.chipPop(2, HomeSequence.CHIP_STAGGER) == 0f)  // one by one
        // the headlines type only once the card is in its cell
        assertTrue(HomeSequence.headlineAt(0) >= HomeSequence.INT_GLIDE_AT + HomeSequence.INT_GLIDE_MS)
    }

    @Test fun musicAndTodoHoldAtTheCentreThenGlide() {
        val emerged = HomeSequence.frame(HomeGrid.Card.MUSIC, HomeSequence.MUSIC_FILL_AT - 1)
        assertEquals(0f, emerged.fill)                                  // light glass while it emerges
        val hold = HomeSequence.frame(HomeGrid.Card.MUSIC, HomeSequence.MUSIC_GLIDE_AT - 1)
        assertEquals(1f, hold.alpha); assertEquals(0f, hold.move); assertEquals(1f, hold.fill)   // dark at the centre, then glides
        val mid = HomeSequence.frame(HomeGrid.Card.TODO, HomeSequence.TODO_GLIDE_AT + HomeSequence.GLIDE_MS / 2)
        assertTrue(mid.move in 0.3f..0.7f)
        // the first done task ticks in the video; a later done task is ticked from the start
        assertFalse(HomeSequence.todoTicked(0, true, 0, HomeSequence.TODO_TICK_AT - 1))
        assertTrue(HomeSequence.todoTicked(0, true, 0, HomeSequence.TODO_TICK_AT))
        assertTrue(HomeSequence.todoTicked(2, true, 0, 0))
        assertFalse(HomeSequence.todoTicked(1, false, 0, 99_999))
    }

    @Test fun nextUpAndQuickDrawInPlaceAndLift() {
        for (c in listOf(HomeGrid.Card.NEXT_UP, HomeGrid.Card.QUICK)) {
            val f = HomeSequence.frame(c, HomeSequence.OUTLINE_MS / 2)
            assertEquals(1f, f.move)                     // never at the centre
            assertTrue(f.outline in 0.3f..0.7f)          // the outline is drawing itself
            assertEquals(1f, f.lift)                      // a little below its row
        }
        assertTrue(HomeSequence.quickFlash(HomeSequence.QUICK_FLASH_AT + HomeSequence.QUICK_FLASH_MS / 4) > 0.9f)
        assertEquals(0f, HomeSequence.quickFlash(HomeSequence.QUICK_FLASH_AT + HomeSequence.QUICK_FLASH_MS))
        assertEquals(0f, HomeSequence.quickRow(1, HomeSequence.QUICK_ROW_AT))
        assertEquals(1f, HomeSequence.quickRow(0, HomeSequence.QUICK_ROW_AT + HomeSequence.QUICK_ROW_MS))
    }

    @Test fun chatBarFadesInLast() {
        val end = HomeSequence.cardsEnd(all, HomeIntroSpec.CARDS_AT)
        assertEquals(0f, HomeSequence.barAlpha(all, HomeIntroSpec.CARDS_AT, -1))
        assertEquals(0f, HomeSequence.barAlpha(all, HomeIntroSpec.CARDS_AT, end - 1))
        assertEquals(1f, HomeSequence.barAlpha(all, HomeIntroSpec.CARDS_AT, end + HomeSequence.BAR_MS))
        assertEquals(HomeSequence.end(all, HomeIntroSpec.CARDS_AT), end + HomeSequence.BAR_MS)
    }

    @Test fun typingIsPacedPerCharacter() {
        assertEquals(0, HomeSequence.typed(10, 100, 100))
        assertEquals(10, HomeSequence.typed(10, 100, 100 + HomeSequence.lineMs(10)))
        assertTrue(HomeSequence.lineMs(1) >= HomeSequence.LINE_MIN_MS); assertTrue(HomeSequence.lineMs(500) <= HomeSequence.LINE_MAX_MS)
    }

    @Test fun skyIsAlwaysOnTheNewHomeExceptUnderAPickedPhoto() {
        assertTrue(HomeSkyRule.drawsSky(redesign = true, background = "glass", hasPhoto = false))
        assertTrue(HomeSkyRule.drawsSky(redesign = true, background = "theme", hasPhoto = false))   // a living theme no longer hides it
        assertFalse(HomeSkyRule.drawsSky(redesign = true, background = "image", hasPhoto = true))
        assertTrue(HomeSkyRule.drawsSky(redesign = true, background = "image", hasPhoto = false))
        assertFalse(HomeSkyRule.drawsSky(redesign = false, background = "glass", hasPhoto = false))
    }

    @Test fun ridgesAreLayeredFarToNear() {
        // traced from the video: far ridge above the middle one above the near one, all in the lower half
        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val far = HomeSkySpec.ridgeY(0, x); val mid = HomeSkySpec.ridgeY(1, x); val near = HomeSkySpec.ridgeY(2, x)
            assertTrue("x $x: $far < $mid < $near", far < mid && mid < near)
            assertTrue(far > 0.5f && near < 0.8f)
        }
        assertEquals(HomeSkySpec.PROFILES.size + 3, HomeSkySpec.DAY.size)
        assertEquals(HomeSkySpec.DAY.size, HomeSkySpec.NIGHT.size)
    }
}
