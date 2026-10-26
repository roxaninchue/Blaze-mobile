package com.blaze.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.28: which back camera Blaze Camera opens, the separate ultrawide below 1x, and focus at high zoom. */
class CameraLensTest {
    private val e = 0.001f
    private val main = CameraZoom.Lens("0", logical = false, minZoom = 1f, maxZoom = 8f, eq = 0.85f)
    private val wide = CameraZoom.Lens("2", logical = false, minZoom = 1f, maxZoom = 4f, eq = 0.43f)
    private val tele = CameraZoom.Lens("3", logical = false, minZoom = 1f, maxZoom = 4f, eq = 2.6f)

    @Test fun logicalCameraFirst() {
        assertNull(CameraZoom.pickBack(listOf(main, wide, tele)))   // no logical camera: CameraX's default
        val logical = CameraZoom.Lens("5", logical = true, minZoom = 0.6f, maxZoom = 30f, eq = 0.85f)
        val small = CameraZoom.Lens("6", logical = true, minZoom = 1f, maxZoom = 10f, eq = 0.85f)
        assertEquals("5", CameraZoom.pickBack(listOf(main, small, logical, wide)))
    }

    @Test fun separateUltraWideOnlyWhenNeeded() {
        assertEquals("2" to 0.5f, CameraZoom.ultraWide(main, listOf(main, wide, tele)))
        assertNull(CameraZoom.ultraWide(main, listOf(main, tele)))                            // none wider
        assertNull(CameraZoom.ultraWide(main.copy(minZoom = 0.6f), listOf(main, wide)))      // main already goes below 1x
        assertNull(CameraZoom.ultraWide(null, listOf(wide)))
        val barelyWider = CameraZoom.Lens("4", false, 1f, 2f, 0.8f)
        assertNull(CameraZoom.ultraWide(main, listOf(main, barelyWider)))                     // 0.94x isn't an ultrawide
        // the 0.5 preset then shows (the camera's min is replaced by the ultrawide's relative zoom)
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), CameraZoom.presets(0.5f, 10f))
    }

    @Test fun ultraWideSwitchHasHysteresis() {
        assertTrue(CameraZoom.useUltraWide(0.5f, false))
        assertFalse(CameraZoom.useUltraWide(0.97f, false))
        assertTrue(CameraZoom.useUltraWide(0.97f, true))
        assertFalse(CameraZoom.useUltraWide(1f, true))
        assertEquals(1f, CameraZoom.ultraWideHw(0.5f, 0.5f), e)
        assertEquals(1.6f, CameraZoom.ultraWideHw(0.8f, 0.5f), e)
        assertEquals(2f, CameraZoom.ultraWideHw(1.5f, 0.5f), e)
    }

    @Test fun focusSpotShrinksWithZoom() {
        assertEquals(0.15f, CameraZoom.meteringSize(1f), e)
        assertEquals(0.15f, CameraZoom.meteringSize(0.5f), e)
        assertTrue(CameraZoom.meteringSize(4f) < CameraZoom.meteringSize(2f))
        assertEquals(0.05f, CameraZoom.meteringSize(20f), e)
    }

    @Test fun refocusOnceTheZoomMoved() {
        assertTrue(CameraZoom.refocusAfter(1f, 5f))
        assertTrue(CameraZoom.refocusAfter(1f, 0.5f))
        assertFalse(CameraZoom.refocusAfter(5f, 5.1f))
        assertFalse(CameraZoom.refocusAfter(Float.NaN, 2f))
    }
}
