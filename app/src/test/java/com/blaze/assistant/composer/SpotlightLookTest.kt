package com.blaze.assistant.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.blaze.assistant.composer.SpotlightSearch.Act
import com.blaze.assistant.composer.SpotlightSearch.Icon
import com.blaze.assistant.composer.SpotlightSearch.Row
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.48.0 RC2: macOS-style Spotlight look: inline type-ahead, translucent tint, calm motion. */
class SpotlightLookTest {
    private fun row(title: String, act: Act = Act.OpenView("terminal")) = Row("r", Icon.VIEW, title, "sub", act)

    @Test fun completionShowsTheRestAndTheHint() {
        val c = SpotlightLook.completion("Send", row("Send Message", Act.RunAction("send")))!!
        assertEquals(" Message", c.rest); assertEquals("Run", c.hint); assertEquals("Send Message", c.full)
        // matches the last part of "Views > Terminal"
        val t = SpotlightLook.completion("term", row("Views > Terminal"))!!
        assertEquals("inal", t.rest); assertEquals("Open", t.hint)
        // case-insensitive, keeps the title's own letters
        assertEquals("ic", SpotlightLook.completion("MUS", row("Music", Act.Preview("music")))!!.rest)
    }

    @Test fun noCompletionWhenItWouldMislead() {
        assertNull(SpotlightLook.completion("", row("Terminal")))
        assertNull(SpotlightLook.completion("!ls", row("! ls")))
        assertNull(SpotlightLook.completion("@b", row("@browser")))
        assertNull(SpotlightLook.completion("xyz", row("Terminal")))
        assertNull(SpotlightLook.completion("Terminal", row("Terminal")))
        assertNull(SpotlightLook.completion("term", null))
    }

    @Test fun panelIsTranslucentAndReadable() {
        for (dark in listOf(true, false)) {
            val tint = SpotlightLook.tint(dark)
            assertTrue("tint alpha ${tint.alpha}", tint.alpha in 0.5f..0.8f)   // the blur shows through
            val theme = if (dark) DarkColors else LightColors
            // worst case behind the panel: a mid-grey blurred Home under the backdrop dim
            val behind = ComposerLook.backdropDim(theme).compositeOver(Color(0xFF808080))
            val panel = tint.compositeOver(behind)
            val ink = theme.ink.compositeOver(panel); val sub = theme.sub.compositeOver(panel)
            assertTrue("ink contrast ${SpotlightLook.contrast(ink, panel)}", SpotlightLook.contrast(ink, panel) >= 4.5)
            assertTrue("sub contrast ${SpotlightLook.contrast(sub, panel)}", SpotlightLook.contrast(sub, panel) >= 3.0)
        }
    }

    @Test fun calmPacingIsAboutTwiceRc1() {
        // RC1: 220 ms in, 150 ms out, 320 ms lift. The owner asked for ~1.5-2x.
        assertTrue(ComposerMotion.ENTER in 330..440)
        assertTrue(ComposerMotion.EXIT in 225..300)
        assertTrue(ComposerMotion.LIFT in 480..640)
        assertTrue(ComposerMotion.BAR in 225..440)
    }

    @Test fun everyIconHasColours() {
        for (i in Icon.values()) { val (a, b) = SpotlightLook.iconColors(i); assertEquals(1f, a.alpha, 0f); assertEquals(1f, b.alpha, 0f) }
    }
}
