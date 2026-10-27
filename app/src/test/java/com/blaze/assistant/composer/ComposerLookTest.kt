package com.blaze.assistant.composer

import androidx.compose.ui.graphics.Color
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LightColors
import com.blaze.assistant.ui.QuickChipRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.48.0: no-blur scrim / opaque bubble (low-RAM phones) and the Quick actions chip-row words. */
class ComposerLookTest {
    @Test fun noBlurScrimIsStrongThemeBackground() {
        for (c in listOf(DarkColors, LightColors)) {
            val s = ComposerLook.scrim(c, blur = false)
            assertTrue("scrim alpha ${s.alpha}", s.alpha in 0.85f..0.90f)
            assertEquals(c.paper.red, s.red, 0.01f); assertEquals(c.paper.green, s.green, 0.01f); assertEquals(c.paper.blue, s.blue, 0.01f)
        }
    }
    @Test fun blurPathUnchanged() {
        assertEquals(Color.Black.copy(alpha = 0.45f), ComposerLook.scrim(DarkColors, blur = true))
        assertEquals(Color.White.copy(alpha = 0.35f), ComposerLook.scrim(LightColors, blur = true))
    }
    @Test fun rc2BackdropDimIsLight() {
        assertEquals(Color.Black.copy(alpha = 0.30f), ComposerLook.backdropDim(DarkColors))
        assertEquals(Color.White.copy(alpha = 0.22f), ComposerLook.backdropDim(LightColors))
    }
    @Test fun blazeBubbleIsOpaque() {
        assertEquals(1f, ComposerLook.blazeBubble(DarkColors).alpha, 0f)
        assertEquals(1f, ComposerLook.blazeBubble(LightColors).alpha, 0f)
        assertEquals(1f, ComposerLook.blazeBubble(DarkColors.copy(bubbleBlaze = Color(0x885A6070))).alpha, 0f)
        assertEquals(1f, ComposerLook.panel(DarkColors).alpha, 0f)
    }
    @Test fun quickActionWords() {
        assertEquals(listOf("on", "Programming,", "Startups", "and", "PlayStation", "news"), QuickChipRow.words(" on Programming,  Startups and PlayStation news "))
        assertEquals(emptyList<String>(), QuickChipRow.words("  "))
    }
}
