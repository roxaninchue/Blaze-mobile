package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.29 theme-tuned chat bubbles: every theme defines its bubble tones, each theme's user bubble is its own colour, and
 * every text in or on a bubble reads at WCAG AA (4.5:1) in light and dark over black, white, the page and the theme's own
 * ground, particle and accent colours.
 */
class BubbleTonesTest {
    private val modes = listOf(false, true)

    @Test fun everyThemeDefinesBubbleColours() {
        for (t in LivingThemes.PICKER) assertTrue("theme '${t.id}' (${t.name}) has no bubble tones", BubbleTones.BY_THEME.containsKey(t.id))
        assertEquals(LivingThemes.PICKER.map { it.id }.toSet(), BubbleTones.BY_THEME.keys)
        for (dark in modes) for (t in LivingThemes.PICKER) {
            val c = blazePalette(t.id, dark, null)
            assertTrue("${t.id} dark=$dark: no user bubble colour", c.bubbleMe != Color.Unspecified)
            assertTrue("${t.id} dark=$dark: no Blaze bubble colour", c.bubbleBlaze != Color.Unspecified)
        }
    }

    @Test fun eachThemeHasItsOwnUserBubble() {
        val tones = BubbleTones.BY_THEME.values.map { it.mine }
        assertEquals("two themes share a user bubble accent", tones.size, tones.toSet().size)
        for (dark in modes) {
            val fills = LivingThemes.PICKER.map { BubblePalette.fill(blazePalette(it.id, dark, null), true) }
            assertEquals("dark=$dark", fills.size, fills.toSet().size)
        }
        // Blaze's (and Halo's) bubble is tinted to go with the theme, softer than the user's.
        val ember = blazePalette("golden_ember", true, null); val deep = blazePalette("deep_sea", true, null)
        assertTrue(ember.bubbleBlaze != deep.bubbleBlaze)
    }

    private fun backdrops(id: String, c: BlazeColors): List<Color> {
        val t = LivingThemes.of(id)
        return listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f)) + t.ground.map { Color(it) } + t.particles.map { Color(it) } + Color(t.accent)
    }

    @Test fun everyThemeReadsAtAA_lightAndDark() {
        var checked = 0
        for (t in LivingThemes.PICKER) for (dark in modes) {
            val c = blazePalette(t.id, dark, null)
            for (mine in listOf(true, false)) {
                val a = BubblePalette.fill(c, mine).alpha
                assertTrue("${t.id} dark=$dark mine=$mine alpha $a", a >= BubblePalette.FROST && a < 1f)
                for (g in backdrops(t.id, c)) {
                    val fill = Contrast.over(BubblePalette.fill(c, mine), g)
                    val text = Contrast.ratio(Contrast.over(BubblePalette.text(c, mine), fill), fill)
                    val meta = Contrast.ratio(Contrast.over(BubblePalette.meta(c, mine), fill), fill)
                    assertTrue("${t.id} dark=$dark mine=$mine on $g text $text", text >= Contrast.AA_TEXT)
                    assertTrue("${t.id} dark=$dark mine=$mine on $g time $meta", meta >= Contrast.AA_TEXT)
                    checked += 2
                }
            }
        }
        assertTrue(checked > 500)
    }

    @Test fun aPaleCustomAccentStillReads() {
        // Classic with a near-white Look & feel accent, dark mode: the tone is mixed in less until the text reads.
        val c = blazePalette("", true, 0xFFF8F8F8)
        val solid = Contrast.over(c.bubbleMe, Color.Black)
        assertTrue(Contrast.ratio(c.ink, solid) >= BubbleTones.SOLID_MIN)
        for (g in listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f))) {
            val fill = Contrast.over(BubblePalette.fill(c, true), g)
            assertTrue(Contrast.ratio(Contrast.over(BubblePalette.text(c, true), fill), fill) >= Contrast.AA_TEXT)
        }
    }
}
