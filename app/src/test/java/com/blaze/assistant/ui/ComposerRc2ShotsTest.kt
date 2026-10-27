package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import com.blaze.assistant.composer.SpotlightLook
import com.blaze.assistant.composer.SpotlightSearch
import com.blaze.assistant.mediaapps.MediaLib
import com.blaze.assistant.mediaapps.Track
import com.blaze.assistant.ui.composer.BlazeChatBar
import com.blaze.assistant.ui.composer.BlazeComposerHome
import com.blaze.assistant.ui.composer.CachedBlur
import com.blaze.assistant.ui.composer.ChatBarTokens
import com.blaze.assistant.ui.composer.ChatOverHomeContent
import com.blaze.assistant.ui.composer.ComposerOverlay
import com.blaze.assistant.ui.composer.ComposerState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.48.0 RC2 screenshots (shots job, BLAZE_SHOTS=1), 360 x 800 dp at 1.5x like the approved video:
 * Spotlight floating at the TOP (empty, typed with inline type-ahead, the @browser preview) and the chat, all over the
 * real new Home blurred by the app's own cached SOFTWARE blur (the low-RAM path; Robolectric can't draw RenderEffect,
 * but it draws this), plus the new chat bar with the Blaze orb.
 *
 * Each frame: Home alone is drawn and grabbed first (what the app's GraphicsLayer capture records), handed to
 * CachedBlur.presetForTests (the same downscale + SoftBlur code the phone runs off the main thread), then the overlay
 * is shown. Asserts: the backdrop really is blurred (edge energy far below the sharp Home), the panel sits at the top
 * (never a bottom sheet) with the bar visible below, and the panel's text contrast is readable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class ComposerRc2ShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val dir get() = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "ComposerRc2ShotsTest").apply { mkdirs() }

    private fun grab(): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        return bmp
    }
    private fun save(name: String, b: Bitmap) { File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun settle() { repeat(3) { rule.mainClock.advanceTimeBy(120); rule.waitForIdle(); Thread.sleep(200) }; rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }

    /** Sum of |Δ| between horizontal neighbours in a band (how sharp it is). */
    private fun edges(b: Bitmap, y0: Int, y1: Int): Long {
        var s = 0L
        for (y in y0 until y1 step 2) for (x in 1 until b.width) {
            val p = b.getPixel(x, y); val q = b.getPixel(x - 1, y)
            s += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) + Math.abs((p and 0xFF) - (q and 0xFF))
        }
        return s
    }

    @Composable private fun Home() {
        Box(Modifier.fillMaxSize()) {
            HomeSkyBackground(night = false, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { HomeRedesignContent(HomeRedesignSample.state(), playIntro = false, drawSky = false) }
                Spacer(Modifier.fillMaxWidth().height((ChatBarTokens.HEIGHT_DP + 8).dp))
            }
        }
    }

    /** Home, then the overlay over its cached software blur. Returns (sharp Home, frame). */
    private fun over(name: String, state: ComposerState, expect: String, chat: (@Composable () -> Unit)? = null): Pair<Bitmap, Bitmap> {
        MotionTokens.setEnabledForTests(false)
        rule.mainClock.autoAdvance = false
        val phase = mutableIntStateOf(0)
        val blur = CachedBlur(null)
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Box(Modifier.fillMaxSize()) {
                    Home()
                    if (phase.intValue == 1) {
                        ComposerOverlay(state, modifier = Modifier.fillMaxSize(), blur = blur, chatContent = chat)
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { BlazeChatBar(state) }
                    }
                }
            }
        }
        settle()
        val home = grab()
        blur.presetForTests(home)
        rule.runOnIdle { phase.intValue = 1 }
        settle()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(expect, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasContentDescription(expect, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        settle()
        val b = grab(); save(name, b)
        MotionTokens.setEnabledForTests(true)
        File(dir, "$name.txt").writeText("blur ms (downscale + SoftBlur, JVM): ${"%.1f".format(blur.lastBlurMs)}\n")
        return home to b
    }

    /** The backdrop band between the panel and the bar is blurred: far fewer edges than the sharp Home there. */
    private fun assertBlurred(home: Bitmap, frame: Bitmap, y0f: Float, y1f: Float) {
        val y0 = (frame.height * y0f).toInt(); val y1 = (frame.height * y1f).toInt()
        val sharp = edges(home, y0, y1); val soft = edges(frame, y0, y1)
        assertTrue("backdrop not blurred: edges $soft vs sharp $sharp", soft * 10 < sharp * 4)
    }

    private fun panelBottom(): Float = rule.onNode(hasContentDescription("Spotlight search"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    /** Text contrast on the translucent panel, sampled at the empty right end of a row. */
    private fun assertReadable(frame: Bitmap, rowDesc: String) {
        val r = rule.onNode(hasContentDescription(rowDesc, substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val p = frame.getPixel((r.right - 14).toInt().coerceIn(0, frame.width - 1), r.center.y.toInt().coerceIn(0, frame.height - 1))
        val bg = Color(p)
        val ink = DarkColors.ink.compositeOver(bg); val sub = DarkColors.sub.compositeOver(bg)
        val ci = SpotlightLook.contrast(ink, bg); val cs = SpotlightLook.contrast(sub, bg)
        File(dir, "contrast.txt").appendText("$rowDesc: bg #${Integer.toHexString(p)} ink ${"%.2f".format(ci)} sub ${"%.2f".format(cs)}\n")
        assertTrue("ink contrast $ci on #${Integer.toHexString(p)}", ci >= 4.5)
        assertTrue("sub contrast $cs on #${Integer.toHexString(p)}", cs >= 3.0)
    }

    /** Spotlight's panel is at the top (just below the status bar), and the bar is still drawn at the bottom. */
    private fun assertAtTop(frame: Bitmap) {
        val field = rule.onNode(hasContentDescription("Spotlight search"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Spotlight field at ${field.top} px is not near the top", field.top < frame.height * 0.12f)
        val bar = rule.onNode(hasContentDescription("Open Spotlight", substring = false).or(hasContentDescription("Close Spotlight")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the + button is not at the bottom (${bar.top})", bar.top > frame.height * 0.85f)
    }

    private fun seed() = MediaLib.seedForTests(listOf(Track("s1", "content://s1", "Bohemian Rhapsody", "Queen")), listOf(Track("v1", "content://v1", "Big Buck Bunny", "Blender")))

    @Test fun spotlightEmpty() {
        seed()
        val st = ComposerState().apply { spotlightOpen = true }
        val (home, b) = over("rc2-spotlight-empty", st, "Chat with Blaze")
        assertAtTop(b)
        assertBlurred(home, b, 0.74f, 0.86f)
        assertReadable(b, "Views > Video")
    }

    @Test fun spotlightTyped() {
        seed()
        val st = ComposerState().apply { spotlightOpen = true; query = "term" }
        val (home, b) = over("rc2-spotlight-typed", st, "Views > Terminal")
        assertAtTop(b)
        // inline type-ahead after the typed text: "term|inal — Open"
        assertTrue("no inline completion", rule.onAllNodes(hasText("\u2014", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertBlurred(home, b, 0.74f, 0.86f)
    }

    @Test fun spotlightBrowserPreview() {
        seed()
        val st = ComposerState().apply { spotlightOpen = true; query = "@browser google.com/food"; preview = SpotlightSearch.Act.Preview("browser", "google.com/food") }
        val (_, b) = over("rc2-spotlight-browser-preview", st, "Close preview")
        assertAtTop(b)
    }

    @Test fun chatOverBlurredHome() {
        val now = System.currentTimeMillis()
        val msgs = listOf(
            ChatMessage(1, Role.USER, "What's on today?", at = now - 120_000),
            ChatMessage(2, Role.BLAZE, "Standup at 10:00, then the dentist at 15:30. Sunny, 26\u00b0 in Ndola.", at = now - 110_000),
            ChatMessage(3, Role.USER, "Remind me to leave at 15:00", at = now - 5_000))
        val st = ComposerState().apply { chatOpen = true }
        val (home, b) = over("rc2-chat-over-blurred-home", st, "Remind me") { ChatOverHomeContent(st, msgs, busy = true, now = now) }
        assertBlurred(home, b, 0.55f, 0.85f)
    }

    @Test fun chatBarWithOrb() {
        MotionTokens.setEnabledForTests(false)
        rule.mainClock.autoAdvance = false
        val typed = ComposerState().apply { text = "Plan my Saturday" }
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Box(Modifier.fillMaxSize()) {
                    Home()
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        BlazeChatBar(typed)
                        BlazeChatBar(ComposerState())
                    }
                }
            }
        }
        settle()
        val b = grab(); save("rc2-chat-bar-orb", b)
        MotionTokens.setEnabledForTests(true)
        assertTrue(rule.onAllNodes(hasContentDescription("Blaze. Tap to chat, hold to talk"), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        assertTrue(rule.onAllNodes(hasContentDescription("Send"), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        // the orb sits inside the pill, between the avatar (left) and + (right)
        val orb = rule.onNode(hasContentDescription("Blaze. Tap to chat, hold to talk"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val avatars = rule.onAllNodes(hasContentDescription("Your profile"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val plus = rule.onAllNodes(hasContentDescription("Open Spotlight"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue(avatars.all { it.right < orb.left } && plus.all { it.left > orb.right })
    }

    /**
     * The app's own wiring (BlazeComposerHome: cachedBlurSource + CachedBlurEffect) opens Spotlight without breaking.
     * The JVM renderer has no hardware bitmaps, so the capture reports a graceful failure here (solid scrim fallback);
     * the phone logs "BlazeBlur capture main=… ms" for each capture. The line is written to capture-path.txt.
     */
    @Test fun capturePathOpensCleanly() {
        MotionTokens.setEnabledForTests(false)
        rule.mainClock.autoAdvance = false
        val st = ComposerState()
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) { BlazeComposerHome(state = st) { Home() } }
        }
        settle()
        rule.runOnIdle { st.spotlightOpen = true }
        settle(); settle()
        val b = grab(); save("rc2-capture-path-jvm", b)
        File(dir, "capture-path.txt").writeText(CachedBlur.lastReport + "\n")
        MotionTokens.setEnabledForTests(true)
        assertTrue(rule.onAllNodes(hasContentDescription("Spotlight search"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
