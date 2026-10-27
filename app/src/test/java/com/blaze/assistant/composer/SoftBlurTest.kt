package com.blaze.assistant.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.48.0 RC2: the software "one cached blur" (works on low-RAM phones, no RenderEffect). */
class SoftBlurTest {
    @Test fun uniformStaysUniform() {
        val w = 30; val h = 20
        val px = IntArray(w * h) { 0xFF336699.toInt() }
        val out = SoftBlur.blur(px, w, h)
        assertTrue(out.all { it == 0xFF336699.toInt() })
    }

    @Test fun spreadsAPointAndKeepsOpaque() {
        val w = 41; val h = 41
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[20 * w + 20] = 0xFFFFFFFF.toInt()
        val out = SoftBlur.blur(px, w, h, radius = 3)
        val centre = out[20 * w + 20] and 0xFF
        val near = out[20 * w + 23] and 0xFF
        val far = out[0] and 0xFF
        assertTrue("centre $centre > near $near", centre >= near)
        assertTrue("light spread to its neighbours", near > 0)
        assertEquals(0, far)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test fun sharpEdgeBecomesAGradient() {
        val w = 40; val h = 4
        val px = IntArray(w * h) { i -> if (i % w < 20) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val out = SoftBlur.blur(px, w, h)
        val row = (0 until w).map { out[w + it] and 0xFF }
        assertTrue(row.zipWithNext().all { (a, b) -> b >= a })   // monotonic
        assertTrue(row[19] in 40..215 && row[20] in 40..215)    // the edge is soft
        assertEquals(0, row[0]); assertEquals(255, row[w - 1])
    }

    @Test fun radiusZeroCopiesAndInputUntouched() {
        val px = IntArray(12) { it * 1000 or (0xFF shl 24) }
        val keep = px.copyOf()
        assertTrue(SoftBlur.blur(px, 4, 3, radius = 0).contentEquals(keep))
        SoftBlur.blur(px, 4, 3)
        assertTrue(px.contentEquals(keep))
    }

    @Test fun tinyAndSmallSizes() {
        assertEquals(90 to 200, SoftBlur.smallSize(720, 1600))
        assertEquals(1 to 1, SoftBlur.smallSize(3, 2))
        val out = SoftBlur.blur(intArrayOf(0xFF102030.toInt()), 1, 1)
        assertEquals(0xFF102030.toInt(), out[0])
    }
}
