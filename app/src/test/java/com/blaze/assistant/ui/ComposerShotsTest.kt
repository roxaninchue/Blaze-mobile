package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import com.blaze.assistant.mediaapps.MediaLib
import com.blaze.assistant.mediaapps.Track
import com.blaze.assistant.ui.composer.BlazeChatBar
import com.blaze.assistant.ui.composer.ChatOverHomeContent
import com.blaze.assistant.ui.composer.ComposerState
import com.blaze.assistant.ui.composer.SpotlightPanel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.47 screenshots (shots job, BLAZE_SHOTS=1): the chat bar, the model chip + picker, the chat over Home and Spotlight v2
 * (curated, both forms, "!" with the distro picker, models, fallbacks, "@" views). The live blur is a RenderEffect that
 * Robolectric doesn't draw, so these frames show the dim only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class ComposerShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    // RC4: Blaze's bubbles in the chat over Home read Settings (fold answers), as in the app
    @org.junit.Before fun initSettings() { try { com.blaze.assistant.agent.Settings.init(androidx.test.core.app.ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "ComposerShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    /** Stand-in Home cards behind the composer. */
    @androidx.compose.runtime.Composable
    private fun FakeHome(dim: Boolean) {
        val c = LocalBlaze.current
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1B2A4A), Color(0xFF0A0A0C))))) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Lusaka  26\u00b0  Sunny", "Interests \u00b7 Tracking").forEach { t ->
                        Box(Modifier.weight(1f).height(130.dp).glass(RoundedCornerShape(22.dp)).padding(14.dp)) { Text(t, style = TextStyle(fontSize = 15.sp, color = c.ink)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Music", "To-do").forEach { t ->
                        Box(Modifier.weight(1f).height(130.dp).glass(RoundedCornerShape(22.dp)).padding(14.dp)) { Text(t, style = TextStyle(fontSize = 15.sp, color = c.ink)) }
                    }
                }
                Box(Modifier.fillMaxWidth().height(90.dp).glass(RoundedCornerShape(22.dp)).padding(14.dp)) { Text("Next up \u00b7 Standup 10:00", style = TextStyle(fontSize = 15.sp, color = c.ink)) }
            }
            if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
    }

    private fun frame(name: String, state: ComposerState, expect: String, dim: Boolean = false, body: (@androidx.compose.runtime.Composable () -> Unit)? = null) {
        MotionTokens.setEnabledForTests(false)
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Box(Modifier.fillMaxSize()) {
                    FakeHome(dim)
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { body?.invoke() }
                        BlazeChatBar(state)
                    }
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(expect, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(400)
        assertTrue("$name frame is blank", distinct(capture(name)) > 12)
        MotionTokens.setEnabledForTests(true)
    }

    private fun spot(name: String, q: String, expect: String) {
        MediaLib.seedForTests(listOf(Track("s1", "content://s1", "Bohemian Rhapsody", "Queen"), Track("s2", "content://s2", "Blinding Lights", "The Weeknd")),
            listOf(Track("v1", "content://v1", "Big Buck Bunny", "Blender")))
        val st = ComposerState().apply { spotlightOpen = true; query = q }
        frame(name, st, expect, dim = true) { SpotlightPanel(st, onAct = {}) }
    }

    @Test fun barEmpty() = frame("bar-empty", ComposerState(), "Message Blaze")

    @Test fun barTypingWithModelPicker() {
        val st = ComposerState().apply { text = "Plan my Saturday"; pickerOpen = true }
        frame("bar-typing-model-picker", st, "Inferno (Smartest)")
    }

    @Test fun chatOverHome() {
        val now = System.currentTimeMillis()
        val msgs = listOf(
            ChatMessage(1, Role.USER, "What's on today?", at = now - 120_000),
            ChatMessage(2, Role.BLAZE, "Standup at 10:00, then the dentist at 15:30. Sunny, 26\u00b0 in Lusaka.", at = now - 110_000),
            ChatMessage(3, Role.USER, "Remind me to leave at 15:00", at = now - 5_000))
        val st = ComposerState().apply { chatOpen = true }
        frame("chat-over-home", st, "Remind me", dim = true) { ChatOverHomeContent(st, msgs, busy = true, now = now) }
    }

    @Test fun spotlightCurated() = spot("spotlight-curated", "", "Chat with Blaze")
    @Test fun spotlightBothForms() = spot("spotlight-terminal-both-forms", "terminal", "Views > Terminal")
    @Test fun spotlightBang() = spot("spotlight-bang-distro", "!ls -la", "! ls -la")
    @Test fun spotlightModels() = spot("spotlight-model-inf", "inf", "Blaze Inferno")
    @Test fun spotlightLibrary() = spot("spotlight-library-fuzzy", "bohemain", "Music > Library > Bohemian Rhapsody")
    @Test fun spotlightFallbacks() = spot("spotlight-fallbacks", "zqxj food", "Ask Blaze about")
    @Test fun spotlightViews() = spot("spotlight-at-views", "@", "@browser")
}
