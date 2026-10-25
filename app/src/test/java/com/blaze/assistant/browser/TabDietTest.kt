package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabDietTest {
    private val ids = listOf(1, 2, 3, 4, 5, 6, 7)

    @Test fun visibleBrowserKeepsOnlyTheShownTab() {
        assertEquals(setOf(3), TabDiet.keepLive(ids, 3, uiVisible = true, attached = setOf(3), agentTabId = null, agentIdleMs = Long.MAX_VALUE))
    }

    @Test fun hiddenBrowserWithIdleBlazeKeepsNothing() {
        assertEquals(emptySet<Int>(), TabDiet.keepLive(ids, 3, uiVisible = false, attached = emptySet(), agentTabId = 5, agentIdleMs = TabDiet.AGENT_KEEP_MS + 1))
    }

    @Test fun blazesTabStaysAwakeWhileItWorks() {
        assertEquals(setOf(3, 5), TabDiet.keepLive(ids, 3, uiVisible = true, attached = setOf(3), agentTabId = 5, agentIdleMs = 5_000))
        assertEquals(setOf(5), TabDiet.keepLive(ids, 5, uiVisible = false, attached = emptySet(), agentTabId = 5, agentIdleMs = 60_000))
    }

    @Test fun memoryPressureShortensBlazesWindow() {
        assertEquals(emptySet<Int>(), TabDiet.keepLive(ids, 5, uiVisible = false, attached = emptySet(), agentTabId = 5, agentIdleMs = 60_000, pressed = true))
        assertEquals(setOf(5), TabDiet.keepLive(ids, 5, uiVisible = false, attached = emptySet(), agentTabId = 5, agentIdleMs = 10_000, pressed = true))
    }

    @Test fun attachedAndFullScreenTabsAreNeverPutToSleep() {
        assertEquals(setOf(2, 4), TabDiet.keepLive(ids, 1, uiVisible = false, attached = setOf(2), agentTabId = null, agentIdleMs = 0, fullScreenId = 4))
        assertEquals(emptySet<Int>(), TabDiet.keepLive(ids, 9, uiVisible = true, attached = setOf(42), agentTabId = 77, agentIdleMs = 0))
    }

    @Test fun thumbnailsAreTinyAndCardShaped() {
        val (w, h) = TabDiet.thumbSize(1080, 2200)
        assertEquals(160, w); assertEquals(205, h)
        assertTrue(w * h * 2 < 70_000)   // RGB_565: ~65 KB, was ~580 KB at 35 % of the screen
        assertEquals(100 to 50, TabDiet.thumbSize(100, 50))
        assertEquals(0 to 0, TabDiet.thumbSize(0, 10))
    }

    @Test fun iconsAreCapped() {
        assertEquals(64 to 64, TabDiet.iconSize(192, 192))
        assertEquals(64 to 32, TabDiet.iconSize(256, 128))
        assertEquals(16 to 16, TabDiet.iconSize(16, 16))
    }

    @Test fun progressMovesInSteps() {
        assertEquals(5, TabDiet.progressStep(0)); assertEquals(10, TabDiet.progressStep(13)); assertEquals(95, TabDiet.progressStep(99)); assertEquals(100, TabDiet.progressStep(100))
        assertTrue((0..100).map { TabDiet.progressStep(it) }.distinct().size <= 21)
    }

    @Test fun loadFeedIsThrottled() {
        assertFalse(TabDiet.feedDue(1_100, 1_000)); assertTrue(TabDiet.feedDue(1_250, 1_000)); assertTrue(TabDiet.feedDue(500, 1_000))
    }
}
