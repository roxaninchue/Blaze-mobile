package com.blaze.assistant.ui

import androidx.compose.ui.geometry.Rect
import com.blaze.assistant.home.HomeGrid
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.ui.splash.HomeIntroSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RC2 pure parts: the settled card frame, navigation, the place label, the focus geometry, the software blur steps, motion pacing. */
class HomeRc2Test {
    @Test fun settledFrameIsFullForEveryWayHomeIsReached() {
        // no intro (later visits, reduce motion)
        assertEquals(HomeIntroSpec.SETTLED, HomeCardFrame.of(null, 2))
        // an intro that finished or was skipped
        val c = HomeIntroClock(WeatherKind.CLEAR, night = false, cards = 4)
        c.splashEnd.longValue = 0L; c.t.longValue = 500L
        assertTrue(HomeCardFrame.of(c, 3).fill < 1f)
        c.done = true
        for (i in 0 until 5) assertEquals(HomeIntroSpec.SETTLED, HomeCardFrame.of(c, i))
        val skipped = HomeIntroClock(WeatherKind.RAIN, night = true, cards = 3).apply { skipped = true; done = true }
        assertEquals(1f, HomeCardFrame.of(skipped, 0).fill)
        // the outline is the reference's band when settled, the bright 1.5 dp edge while light glass
        assertEquals(2.025f, HomeCardFrame.edgePx(1f, 2.025f, 1.5f), 1e-4f)
        assertEquals(2.25f, HomeCardFrame.edgePx(0f, 2.025f, 1.5f), 1e-4f)
    }

    @Test fun noEmptyCellWithoutTasks() {
        val rows = HomeGrid.rows(hasTasks = false)
        assertTrue(rows.all { it.full || it.cards.size == 2 })
        // RC4: Music | To-do (empty state) like the target, not Music across the row
        assertEquals(listOf(HomeGrid.Card.MUSIC, HomeGrid.Card.TODO), rows[1].cards)
    }

    @Test fun newHomeIsTheRoot() {
        assertTrue(HomeRoute.swipe(setting = false, redesign = true))
        assertEquals(false, HomeRoute.swipe(setting = false, redesign = false))
        assertTrue(HomeRoute.homeRoot(swipe = true, redesign = true, privateChat = false))
        assertEquals(false, HomeRoute.homeRoot(swipe = true, redesign = true, privateChat = true))
        // Back from a view / page / the chat sheet lands on Home (0), never on the chat sheet
        assertEquals(0f, HomeRoute.sheetAfterBack(swipe = true, homeRoot = true, sheet = 1f))
        assertEquals(0f, HomeRoute.sheetAfterBack(swipe = true, homeRoot = true, sheet = 0f))
        // without the new Home: as before
        assertEquals(1f, HomeRoute.sheetAfterBack(swipe = true, homeRoot = false, sheet = 0.2f))
        assertNull(HomeRoute.sheetAfterBack(swipe = false, homeRoot = false, sheet = 1f))
        assertTrue(HomeRoute.askOverHome(true))
    }

    @Test fun placeLikeTheReference() {
        assertEquals("Ndola, ZM", WeatherBoard.placeLabel("Ndola", "zm"))
        assertEquals("Ndola, ZM", WeatherBoard.placeLabel("Ndola, ZM", "ZM"))
        assertEquals("Lusaka", WeatherBoard.placeLabel("Lusaka", null))
        assertEquals("Lusaka", WeatherBoard.placeLabel("Lusaka", "ZMB"))
        assertEquals("", WeatherBoard.placeLabel(" ", "ZM"))
    }

    @Test fun focusFrameGrowsFromTheCellAndShrinksBackIntoIt() {
        val cell = Rect(25f, 18f, 257f, 286f); val focus = Rect(30f, 200f, 510f, 900f)
        assertEquals(cell, HomeFocusGeometry.frame(cell, focus, 0f, moving = true))
        assertEquals(focus, HomeFocusGeometry.frame(cell, focus, 1f, moving = true))
        val mid = HomeFocusGeometry.frame(cell, focus, 0.5f, moving = true)
        assertEquals((cell.left + focus.left) / 2, mid.left, 1e-3f); assertEquals((cell.bottom + focus.bottom) / 2, mid.bottom, 1e-3f)
        // reduce motion: no movement, a crossfade in place
        assertEquals(focus, HomeFocusGeometry.frame(cell, focus, 0.1f, moving = false))
        assertEquals(1f, HomeFocusGeometry.compactAlpha(0f)); assertEquals(0f, HomeFocusGeometry.compactAlpha(0.6f))
        assertEquals(0f, HomeFocusGeometry.fullAlpha(0.4f)); assertEquals(1f, HomeFocusGeometry.fullAlpha(1f))
    }

    @Test fun focusStateOpensClosesAndSaves() {
        val f = HomeFocusState()
        assertEquals(false, f.isOpen)
        f.open(HomeGrid.Card.INTERESTS)
        assertTrue(f.isOpen && f.shown)
        assertEquals(0f, f.gridAlpha(HomeGrid.Card.INTERESTS)); assertEquals(1f, f.gridAlpha(HomeGrid.Card.WEATHER))
        f.close()
        assertEquals(false, f.isOpen)
        val saved = HomeFocusState(HomeGrid.Card.WEATHER)
        assertEquals(1f, saved.progress.value)
        assertEquals(HomeGrid.Card.WEATHER, saved.target)
    }

    @Test fun softBlurShrinksInHalvingStepsOnce() {
        val s = SoftBlur.steps(720, 1600)
        assertEquals(720 / 16 to 1600 / 16, s.last())
        assertTrue(s.size in 3..5)
        for (i in 1 until s.size) assertTrue(s[i].first <= s[i - 1].first)
        assertTrue(SoftBlur.steps(10, 10).last() == (1 to 1))
    }

    @Test fun homeMotionFollowsTheVideo() {
        // 0.48.0 RC3: the board's timings come from the approved video (seconds into it, the splash hands over at ~2.4 s)
        assertTrue(HomeMotion.GLIDE_MS in 450L..650L)
        assertTrue(HomeMotion.BOARD_FILL_AT in 2_300L..2_900L)
        assertTrue(HomeMotion.CARDS_AT in 2_900L..3_300L)
        assertEquals(HomeMotion.GLIDE_MS, HomeIntroSpec.GLIDE_MS)
        // whole intro after the splash: ~15 s (the video is ~17 s including the splash). RC4: To-do always lands (its empty
        // state without tasks), so the intro is the same length with or without tasks
        assertTrue(HomeIntroSpec.end(5) in 13_500L..16_500L)
        assertEquals(HomeIntroSpec.end(5), HomeIntroSpec.end(4))
        assertTrue(HomeMotion.FOCUS_MS in 400..700)
    }
}
