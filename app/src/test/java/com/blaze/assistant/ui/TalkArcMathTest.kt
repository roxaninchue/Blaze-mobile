package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.43.0 / v0.45.28: the Blaze / Halo arc from the header orb (Halo x Blaze is automatic now, not a pick). */
class TalkArcMathTest {
    private val e = 0.001f

    @Test fun targetsAndLabels() {
        assertEquals(listOf("blaze", "halo"), TalkArcMath.TARGETS)
        assertEquals(1, TalkArcMath.indexOf("halo")); assertEquals(0, TalkArcMath.indexOf("nobody"))
        assertEquals("halo", TalkArcMath.targetAt(9)); assertEquals("blaze", TalkArcMath.targetAt(-1))
        assertEquals("Halo", TalkArcMath.label("halo")); assertEquals("Blaze", TalkArcMath.label("blaze"))
    }

    @Test fun oldHaloXBlazePickMigratesToBlaze() {
        assertEquals("blaze", TalkArcMath.normalize("all"))
        assertEquals(0, TalkArcMath.indexOf("all"))
        assertEquals("Blaze", TalkArcMath.label("all"))
        assertEquals("halo", TalkArcMath.normalize("halo")); assertEquals("blaze", TalkArcMath.normalize(null))
    }

    @Test fun layoutPutsTheFocusedOnTop() {
        // Halo focused: Blaze to the left, Halo on top. Blaze focused: Halo to the right.
        assertEquals(150f, TalkArcMath.angle(0, 1f), e); assertEquals(90f, TalkArcMath.angle(1, 1f), e)
        assertEquals(30f, TalkArcMath.angle(1, 0f), e)
        val (x, y) = TalkArcMath.offset(1, 1f, 100f)
        assertEquals(0f, x, e); assertEquals(-100f, y, e)
        for (p in listOf(0f, 1f)) for (i in 0..1) assertTrue(TalkArcMath.angle(i, p) in 0f..180f)
        assertEquals(1.45f, TalkArcMath.scale(1, 1f), e); assertEquals(1f, TalkArcMath.scale(0, 1f), e)
        assertEquals(0.5f, TalkArcMath.focus(1, 0.5f), e)
    }

    @Test fun flatWideArc() {
        // A 160 px half-width arc is about a third as tall: the icons sit near the ends, low.
        val rx = 160f; val ry = TalkArcMath.flatRy(rx, 40f)
        assertTrue(ry < rx * 0.5f); assertTrue(ry >= 40f)
        assertEquals(40f, TalkArcMath.flatRy(50f, 40f), e)
        val (hx, hy) = TalkArcMath.offset(1, 0f, rx, ry)      // Halo to the right while Blaze is on top
        assertTrue(hx > rx * 0.8f); assertTrue(-hy < ry * 0.6f)
        val (tx, ty) = TalkArcMath.offset(0, 0f, rx, ry)
        assertEquals(0f, tx, e); assertEquals(-ry, ty, e)
        // A touch scaled to the ellipse: the right end is 0°, the top 90°.
        assertEquals(90f, TalkArcMath.touchAngle(0f, -ry, rx, ry), e)
        assertEquals(45f, TalkArcMath.touchAngle(rx, -ry, rx, ry), e)
        assertEquals(1, TalkArcMath.hit(hx, hy, 0f, rx, ry, 24f))
    }

    @Test fun rotateSwipeAndSnap() {
        // pulling the right-hand icon up over the top (angle 30 -> 90) brings it to the top
        assertEquals(1f, TalkArcMath.rotate(0f, 30f, 90f), e)
        assertEquals(0f, TalkArcMath.rotate(1f, 150f, 90f), e)
        assertEquals(1f, TalkArcMath.rotate(1f, 10f, 170f), e)            // clamped at the end
        assertEquals(20f / 60f, TalkArcMath.rotate(0f, 170f, -170f), e)   // wraps through ±180 the short way
        val r = 100f; val oneStep = r * 60f * Math.PI.toFloat() / 180f
        assertEquals(1f, TalkArcMath.swipe(0f, -oneStep, r), e)
        assertEquals(0f, TalkArcMath.swipe(0.2f, 500f, r), e)
        assertEquals(0, TalkArcMath.snap(0.4f)); assertEquals(1, TalkArcMath.snap(0.6f)); assertEquals(1, TalkArcMath.snap(9f))
    }

    @Test fun touchAngleAndHit() {
        assertEquals(90f, TalkArcMath.touchAngle(0f, -50f), e)
        assertEquals(180f, TalkArcMath.touchAngle(-50f, 0f), e)
        assertEquals(1, TalkArcMath.hit(3f, -98f, 1f, 100f, 24f))
        assertEquals(-1, TalkArcMath.hit(0f, 0f, 1f, 100f, 24f))
    }
}
