package com.blaze.assistant.ui.splash

import com.blaze.assistant.ui.splash.SkySplashSpec as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.47.x sky splash: time-of-day palettes, dot wave order and count, hand-off timing, gate, geometry. Pure JVM. */
class SkySplashSpecTest {
    @Test fun phasesFollowLocalHour() {
        val want = mapOf(0 to S.Phase.NIGHT, 4 to S.Phase.NIGHT, 5 to S.Phase.DAWN, 6 to S.Phase.DAWN, 7 to S.Phase.DAY, 12 to S.Phase.DAY,
            16 to S.Phase.DAY, 17 to S.Phase.GOLDEN, 18 to S.Phase.GOLDEN, 19 to S.Phase.NIGHT, 23 to S.Phase.NIGHT)
        want.forEach { (h, p) -> assertEquals("hour $h", p, S.phaseFor(h)) }
        assertTrue(S.palette(S.Phase.NIGHT).stars); assertFalse(S.palette(S.Phase.DAY).stars)
    }

    @Test fun waveStartsAtTheRightmostDotAndTravelsLeft() {
        // first moment each dot leaves the ground
        fun firstLift(i: Int): Long = (S.WAVE_T0_MS..S.WAVE_T0_MS + S.PERIOD_MS).first { S.dotLift(i, it, 99) > 0.01f }
        val r = firstLift(2); val m = firstLift(1); val l = firstLift(0)
        assertTrue("right before middle", r < m); assertTrue("middle before left", m < l)
        assertEquals(S.STAGGER_MS.toDouble(), (m - r).toDouble(), 2.0)
        assertEquals(S.STAGGER_MS.toDouble(), (l - m).toDouble(), 2.0)
    }

    @Test fun hopIsTenDpAtMostAndLandsBeforeTheNextWave() {
        var peak = 0f; var t = 0L
        while (t < S.PERIOD_MS) { for (i in 0..2) peak = maxOf(peak, S.dotLift(i, S.WAVE_T0_MS + t, 99)); t++ }
        assertTrue("peak $peak", peak in 0.97f..1.0f)
        // every dot rests at each wave boundary (cross-fade starts there)
        for (c in 1..5) for (i in 0..2) assertEquals(0f, S.dotLift(i, S.WAVE_T0_MS + c * S.PERIOD_MS, 99), 1e-4f)
        assertTrue("left dot lands inside the period", 2 * S.STAGGER_MS + S.HOP_MS < S.PERIOD_MS)
    }

    @Test fun threeWavesMinimumThenLoopsUntilReady() {
        fun hops(cycles: Int): Int {   // count take-offs of the right dot
            var n = 0; var was = false; var t = 0L
            while (t < 8_000) { val up = S.dotLift(2, t, cycles) > 0.01f; if (up && !was) n++; was = up; t += 5 }
            return n
        }
        assertEquals(3, hops(S.MIN_CYCLES)); assertEquals(5, hops(5))
        assertEquals(-1L, S.fadeStart(S.MIN_END_MS - 1, true))            // never before 3 waves
        assertEquals(S.MIN_END_MS, S.fadeStart(S.MIN_END_MS, true))
        assertEquals(S.MIN_END_MS, S.fadeStart(S.MIN_END_MS + 30, true))  // on the boundary it just passed
        assertEquals(-1L, S.fadeStart(4_000, false))                      // still loading: keep looping
        assertEquals(S.MAX_MS, S.fadeStart(S.MAX_MS, false))              // never trap the user
        assertTrue(S.MIN_END_MS + S.CROSSFADE_MS <= 2_600)                // the whole minimum show is short
    }

    @Test fun gate() {
        assertTrue(SkySplashGate.onCold(SkySplashGate.MAIN, listen = false, locked = false))
        assertTrue(SkySplashGate.onCold(null, listen = false, locked = false))
        assertFalse(SkySplashGate.onCold("com.blaze.assistant.CALL", false, false))
        assertFalse(SkySplashGate.onCold("android.intent.action.VIEW", false, false))
        assertFalse(SkySplashGate.onCold(SkySplashGate.MAIN, listen = true, locked = false))
        assertFalse(SkySplashGate.onCold(SkySplashGate.MAIN, listen = false, locked = true))
        assertTrue(SkySplashGate.onReturn(false, false, false, false))
        assertFalse(SkySplashGate.onReturn(ownScreenRecently = true, stoppedByScreenOff = false, pip = false, locked = false))
        assertFalse(SkySplashGate.onReturn(false, stoppedByScreenOff = true, pip = false, locked = false))
        assertFalse(SkySplashGate.onReturn(false, false, pip = true, locked = false))
        assertTrue(SkySplashGate.cancelFor("android.intent.action.SEND", false)); assertFalse(SkySplashGate.cancelFor(SkySplashGate.MAIN, false))
    }

    @Test fun cloudIsCentredAndAbout58PercentWide() {
        val w = 720f; val h = 1600f; val seg = FloatArray(8 * S.CLOUD_SEGMENTS)
        val bottom = S.cloudCubics(w / 2, h * S.CLOUD_CENTER_Y, w * S.CLOUD_WIDTH, seg)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE
        for (k in 0 until S.CLOUD_SEGMENTS) for (s in 0..32) {
            val o = k * 8; val t = s / 32f; val u = 1 - t
            val x = u * u * u * seg[o] + 3 * u * u * t * seg[o + 2] + 3 * u * t * t * seg[o + 4] + t * t * t * seg[o + 6]
            val y = u * u * u * seg[o + 1] + 3 * u * u * t * seg[o + 3] + 3 * u * t * t * seg[o + 5] + t * t * t * seg[o + 7]
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y)
        }
        val width = (maxX - minX) / w
        assertTrue("width $width", width in 0.55f..0.62f)
        assertEquals(w / 2, (minX + maxX) / 2, w * 0.02f)
        assertTrue("dots fit above the mountains", bottom + 40 * 2 < h * 0.585f - 0.05f * h)
        assertTrue("cloud in the upper half", minY > h * 0.2f && bottom < h * 0.5f)
        // closed outline
        assertEquals(seg[0], seg[8 * S.CLOUD_SEGMENTS - 2], 1e-3f); assertEquals(seg[1], seg[8 * S.CLOUD_SEGMENTS - 1], 1e-3f)
    }

    @Test fun mountainsGetHazierFurtherBack() {
        val p = S.palette(S.Phase.DAY)
        fun lum(c: Int) = ((c shr 16) and 255) * 0.3 + ((c shr 8) and 255) * 0.59 + (c and 255) * 0.11
        assertTrue(lum(S.ridgeColor(p, 0)) > lum(S.ridgeColor(p, 1))); assertTrue(lum(S.ridgeColor(p, 1)) > lum(S.ridgeColor(p, 2)))
        for (r in 0..2) for (i in 0..100) assertTrue(S.ridgeY(r, i / 100f) in 0.45f..0.85f)
    }

    @Test fun starsStayClearOfTheCloudAndDots() {
        val s = S.stars(); assertEquals(S.STAR_COUNT * 4, s.size)
        var k = 0
        while (k < s.size) { val x = s[k]; val y = s[k + 1]; assertFalse("star at $x,$y", x > 0.15f && x < 0.85f && y > 0.28f); k += 4 }
    }

    @Test fun blurSoftensAnEdgeAndDitherStaysWithinOneStep() {
        val w = 20; val h = 4; val px = IntArray(w * h) { if (it % w < 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        S.boxBlur(px, IntArray(px.size), w, h, S.BOX_RADIUS, S.BOX_PASSES)
        val mid = px[10] and 255; assertTrue("edge softened ($mid)", mid in 60..200)
        assertEquals(0, px[0] and 255); assertEquals(255, px[w - 1] and 255)
        val flat = IntArray(1000) { 0xFF808080.toInt() }; S.dither(flat)
        assertTrue(flat.all { val v = it and 255; v in 127..129 && (it ushr 24) == 255 })
    }
}
