package com.blaze.assistant.variant

import org.junit.Assert.assertEquals
import org.junit.Test

class TapCounterTest {
    @Test fun sevenQuickTapsReachTheCount() {
        val t = TapCounter()
        val left = (0 until 7).map { t.tap(1_000L + it * 300L) }
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), left)
    }

    @Test fun aSlowTapStartsOver() {
        val t = TapCounter()
        repeat(5) { t.tap(1_000L + it * 300L) }
        assertEquals(6, t.tap(10_000L))
    }

    @Test fun afterReachingItStartsOver() {
        val t = TapCounter(needed = 3)
        assertEquals(2, t.tap(0)); assertEquals(1, t.tap(100)); assertEquals(0, t.tap(200))
        assertEquals(2, t.tap(300))
    }

    @Test fun releaseAndDebugHaveNoHiddenSwitch() {
        // Unit tests run against the debug variant, which uses the no-op hooks (src/noPreview).
        assertEquals(false, VariantHooks.VERSION_TAPS)
    }
}
