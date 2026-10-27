package com.blaze.assistant.ui.splash

import com.blaze.assistant.home.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.47 splash -> Home: the two-lobe cloud, the weather variants, the right-to-left dots and the hand-over timeline. */
class CloudSplashSpecTest {
    @Test fun cloudIsClosedAndTwoLobed() {
        val c = CloudSplashSpec.cloudCubics()
        assertEquals(5 * 8, c.size)
        // closed: the last cubic ends where the first starts
        assertEquals(c[0], c[c.size - 2], 1e-5f); assertEquals(c[1], c[c.size - 1], 1e-5f)
        val b = CloudSplashSpec.cloudBounds(c)
        assertTrue("fills the unit width (${b[0]}..${b[2]})", b[0] in -0.04f..0.06f && b[2] in 0.94f..1.04f)
        assertTrue("top near 0 (${b[1]})", b[1] in -0.05f..0.05f)
        assertEquals(CloudSplashSpec.BODY_BOTTOM, b[3], 0.01f)
        // two lobes: the highest point left of the notch is higher than the right lobe's
        fun top(k: Int) = (0..20).minOf { CloudSplashSpec.cubicAt(c, k, it / 20f).second }
        assertTrue(top(1) < top(2))
        // the notch between them dips below both tops
        assertTrue(CloudSplashSpec.CLOUD[2 * 3 + 1] > top(2))
    }

    @Test fun everyVariantHasAGlyph() {
        for (k in WeatherKind.values()) for (night in listOf(false, true)) {
            val g = CloudSplashSpec.glyph(k, night)
            when (k) {
                WeatherKind.CLEAR -> { assertFalse(g.cloud); assertTrue(g.discR > 0f); assertEquals(night, g.moon) }
                WeatherKind.PARTLY_CLOUDY -> { assertTrue(g.cloud); assertTrue(g.discR > 0f); assertEquals(night, g.moon); assertEquals(!night, g.rays.isNotEmpty()) }
                WeatherKind.OVERCAST -> assertTrue(g.backCloud)
                WeatherKind.RAIN, WeatherKind.FOG -> assertTrue(g.lines.isNotEmpty() && !g.polyline)
                WeatherKind.THUNDER -> assertTrue(g.polyline && g.lines.size >= 6)
                WeatherKind.SNOW -> assertTrue(g.dots.size % 3 == 0 && g.dots.isNotEmpty())
            }
        }
        assertTrue(CloudSplashSpec.dotsDrop(WeatherKind.RAIN) > 0f)
        assertEquals(0f, CloudSplashSpec.dotsDrop(WeatherKind.PARTLY_CLOUDY))
    }

    @Test fun outlineWhiteAtNightBlackByDay() {
        assertEquals(0xFFFFFFFF.toInt(), CloudSplashSpec.ink(night = true))
        val d = CloudSplashSpec.ink(night = false)
        assertTrue(((d shr 16) and 255) + ((d shr 8) and 255) + (d and 255) < 90)
    }

    @Test fun dotsBounceRightToLeft() {
        // first wave: the right dot lifts before the middle, the middle before the left
        fun firstLift(i: Int) = (CloudSplashSpec.WAVE_T0_MS until CloudSplashSpec.WAVE_T0_MS + CloudSplashSpec.PERIOD_MS step 5).first { CloudSplashSpec.dotLift(i, it) > 0.05f }
        assertTrue(firstLift(2) < firstLift(1)); assertTrue(firstLift(1) < firstLift(0))
        assertEquals(0f, CloudSplashSpec.dotLift(0, 0L))
    }

    @Test fun splashLeavesOnAWaveBoundary() {
        // 0.48.0 RC3: three full waves like the approved video (it hands over at ~2.4 s)
        assertEquals(-1L, CloudSplashSpec.splashEnd(500, ready = true))
        assertEquals(-1L, CloudSplashSpec.splashEnd(2_000, ready = true))
        assertEquals(-1L, CloudSplashSpec.splashEnd(2_400, ready = false))
        val e = CloudSplashSpec.splashEnd(2_400, ready = true)
        assertTrue(e in CloudSplashSpec.SPLASH_MIN_MS..2_400L)
        assertTrue(CloudSplashSpec.SPLASH_MIN_MS in 2_000L..2_500L)
        assertEquals(0L, (e - CloudSplashSpec.WAVE_T0_MS) % CloudSplashSpec.PERIOD_MS)
        assertEquals(CloudSplashSpec.SPLASH_MAX_MS, CloudSplashSpec.splashEnd(CloudSplashSpec.SPLASH_MAX_MS, ready = false))
    }

    @Test fun handOverTimeline() {
        assertEquals(0f, HomeIntroSpec.glide(0)); assertEquals(1f, HomeIntroSpec.glide(HomeIntroSpec.GLIDE_MS))
        // the board outline starts drawing while the cloud is still landing, the text types after it
        assertTrue(HomeIntroSpec.OUTLINE_AT < HomeIntroSpec.GLIDE_MS)
        assertEquals(0, HomeIntroSpec.typed(9, HomeIntroSpec.TYPE_PLACE_AT, HomeIntroSpec.TYPE_PLACE_AT))
        assertEquals(9, HomeIntroSpec.typed(9, HomeIntroSpec.TYPE_PLACE_AT, HomeIntroSpec.TYPE_PLACE_AT + HomeIntroSpec.TYPE_MS))
        // cards: nothing before its turn, settled at the end; one after the other
        val c0 = HomeIntroSpec.card(0, HomeIntroSpec.CARDS_AT - 1)
        assertEquals(0f, c0.alpha); assertEquals(0f, c0.move)
        val end = HomeIntroSpec.end(5)
        for (i in 0 until 5) assertEquals(HomeIntroSpec.SETTLED, HomeIntroSpec.card(i, end))
        assertTrue(HomeIntroSpec.card(1, HomeIntroSpec.CARDS_AT + 100).alpha < HomeIntroSpec.card(0, HomeIntroSpec.CARDS_AT + 100).alpha)
        // 0.48.0 RC3: the approved video's pace, 1:1 (about 15 s after the splash with To-do)
        assertTrue("end $end", end in 13_500L..16_500L)
    }
}
