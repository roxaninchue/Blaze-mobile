package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.ui.splash.CloudGlyphPainter
import com.blaze.assistant.ui.splash.CloudSplashSpec
import com.blaze.assistant.ui.splash.HomeIntroSpec
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.47 Splash -> Home screenshots (shots job, BLAZE_SHOTS=1) at 360 x 800 dp, 1.5x: the same 540 x 1200 px frame as the
 * approved preview video, so the settled Home can be compared with its final frame. Also the splash, three hand-over
 * moments and every weather variant (day black / night white).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class HomeRedesignShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        rule.mainClock.autoAdvance = false
        rule.setContent(content)
        repeat(3) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(250) }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "HomeRedesignShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun home(name: String, withTasks: Boolean = true, night: Boolean = false, frozenU: Long? = null) = capture(name) {
        HomeRedesignContent(HomeRedesignSample.state(withTasks, night), playIntro = false, drawSky = true, frozenU = frozenU)
    }

    private fun save(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "HomeRedesignShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /** Advance the (manual) clock [ms] in 100 ms frames, letting native rendering catch up now and then. */
    private fun run(ms: Long) {
        var t = 0L
        while (t < ms) { rule.mainClock.advanceTimeBy(100); t += 100; if (t % 1000 == 0L) { rule.waitForIdle(); Thread.sleep(60) } }
        rule.waitForIdle(); Thread.sleep(250); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
    }

    /**
     * RC2 (frames gone after the intro): every card has its full frame, i.e. a dark navy fill inside each cell (sampled in
     * the card's padding, away from text) and a light outline brighter than that fill at the cell's edge. Positions in dp
     * from HomeGridLook (17 dp sides and gaps, rows 179 / 171, top padding 12).
     */
    private fun assertFrames(b: Bitmap, withTasks: Boolean, label: String) {
        val d = b.width / 360f
        fun px(x: Float, y: Float) = b.getPixel((x * d).toInt(), (y * d).toInt())
        fun blue(p: Int) = p and 0xFF
        fun lum(p: Int) = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        val sky = px(180f, 101f)
        assertTrue("$label: the gutter between the top cards is sky (${Integer.toHexString(sky)})", blue(sky) > 150)
        val inside = mutableListOf(23f to 101f, 337f to 101f, 23f to 293f)
        if (withTasks) inside += 337f to 293f else inside += 337f to 254f
        for ((x, y) in inside) {
            val p = px(x, y)
            assertTrue("$label: card fill at ($x, $y) dp is navy, got ${Integer.toHexString(p)}", blue(p) < 140)
        }
        // the outline: the left edge of the weather card (17 dp) is lighter than its fill 6 dp in
        val edge = px(17.6f, 101f); val fill = px(23f, 101f)
        assertTrue("$label: outline ${Integer.toHexString(edge)} vs fill ${Integer.toHexString(fill)}", lum(edge) > lum(fill) + 15)
    }

    @Test fun settled() {
        val b = home("home-settled")
        // the cards are dark navy over a blue sky: the top-left card's middle is darker than the sky at the edge
        val card = b.getPixel(b.width / 4, b.height / 6); val sky = b.getPixel(2, b.height / 3)
        assertTrue((card and 0xFF) < (sky and 0xFF))
        assertFrames(b, true, "settled")
    }
    @Test fun settledNoTasks() { assertFrames(home("home-settled-no-todo", withTasks = false), false, "settled, no to-do") }

    /** RC2: the intro played live to the end (first run): the settled grid keeps every card frame. */
    private fun afterIntro(name: String, withTasks: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent { HomeRedesignContent(HomeRedesignSample.state(withTasks), playIntro = true, drawSky = true) }
        run(9_500)
        assertFrames(save(name), withTasks, name)
    }
    @Test fun settledAfterIntro() = afterIntro("home-after-intro", true)
    @Test fun settledAfterIntroNoTodo() = afterIntro("home-after-intro-no-todo", false)

    /** RC2: the intro skipped by a tap during the card emergence. */
    @Test fun settledAfterSkip() {
        rule.mainClock.autoAdvance = false
        rule.setContent { HomeRedesignContent(HomeRedesignSample.state(), playIntro = true, drawSky = true) }
        run(3_800)
        rule.onRoot().performTouchInput { click(center) }
        run(600)
        assertFrames(save("home-after-skip"), true, "after a skip")
    }

    /** RC2: reduce motion ("Remove animations"): no intro, settled from the first frame. */
    @Test fun settledReduceMotion() {
        MotionTokens.setEnabledForTests(false)
        try { assertFrames(capture("home-reduce-motion") {
            HomeRedesignContent(HomeRedesignSample.state(), playIntro = !HomeIntroClock.played && MotionTokens.enabled, drawSky = true) }, true, "reduce motion") }
        finally { MotionTokens.setEnabledForTests(true) }
    }

    /**
     * RC2: back from a view. The app shows a view (Settings, Music...) in place of Home and Home leaves the composition;
     * coming back re-creates Home with the intro already played. Here a stand-in view is shown first, then Home again.
     */
    @Test fun settledAfterReturningFromAView() {
        rule.mainClock.autoAdvance = false
        val inView = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            if (inView.value) Box(Modifier.fillMaxSize().background(Color(0xFF101010)))
            else HomeRedesignContent(HomeRedesignSample.state(), playIntro = false, drawSky = true)
        }
        run(300)
        rule.runOnIdle { inView.value = false }
        run(500)
        assertFrames(save("home-after-view"), true, "after a view")
    }

    /** RC2 card focus: a card lifted over the blurred Home. */
    private fun focused(name: String, card: com.blaze.assistant.home.HomeGrid.Card, software: Boolean) = capture(name) {
        HomeRedesignContent(HomeRedesignSample.state().let { it.copy(allTopics = it.topics + HomeRedesignSample.extraTopics()) },
            playIntro = false, drawSky = true, focus = HomeFocusState(card), forceSoftwareBlur = software)
    }
    @Test fun focusedInterests() {
        focused("focus-interests", com.blaze.assistant.home.HomeGrid.Card.INTERESTS, software = false)
        rule.onNodeWithText("Formula One").assertIsDisplayed()
    }
    @Test fun focusedWeather() {
        focused("focus-weather", com.blaze.assistant.home.HomeGrid.Card.WEATHER, software = false)
        rule.onNodeWithText("Open the forecast").assertIsDisplayed()
    }
    @Test fun focusedLowRamBlur() {
        val b = focused("focus-interests-lowram", com.blaze.assistant.home.HomeGrid.Card.INTERESTS, software = true)
        // the sides are dimmed (the blurred, dimmed Home): darker than the open sky
        val side = b.getPixel(4, b.height / 2)
        assertTrue("dim on the sides: ${Integer.toHexString(side)}", (side and 0xFF) < 170)
    }
    @Test fun settledNight() { home("home-settled-night", night = true) }
    @Test fun splash() { home("intro-0-splash", frozenU = -900L) }
    @Test fun splashNight() { home("intro-0-splash-night", night = true, frozenU = -900L) }
    @Test fun glide() { home("intro-1-glide", frozenU = HomeIntroSpec.GLIDE_MS / 2) }
    @Test fun board() { home("intro-2-board-typing", frozenU = HomeIntroSpec.TYPE_COND_AT + 120) }
    @Test fun cards() { home("intro-3-cards", frozenU = HomeIntroSpec.CARDS_AT + HomeIntroSpec.CARD_STAGGER + 260) }

    /** Every weather variant at splash size, day (black outline) over the day sky and night (white) over the night sky. */
    @Test fun variants() = run {
        capture("splash-variants") {
            Canvas(Modifier.fillMaxSize()) {
                val kinds = WeatherKind.values()
                val cellW = size.width / 2f; val cellH = size.height / kinds.size
                kinds.forEachIndexed { r, k ->
                    for (n in 0..1) {
                        val night = n == 1
                        drawRect(if (night) Color(0xFF14214A) else Color(0xFF78B6E8), androidx.compose.ui.geometry.Offset(n * cellW, r * cellH), androidx.compose.ui.geometry.Size(cellW, cellH))
                        val unit = cellH * 0.85f
                        CloudGlyphPainter(k, night).draw(this, n * cellW + (cellW - unit) / 2f, r * cellH + cellH * 0.30f, unit,
                            Color(CloudSplashSpec.ink(night)), 2f * density)
                    }
                }
            }
        }
        Unit
    }

    /**
     * 0.48.0: the new Home with the composer as the app wires it (HomeSurface): Home sits above a bottom slot as tall as the
     * chat bar (ChatBarTokens.HEIGHT_DP + 8), and the bar floats over that slot, so "All widgets" scrolls above the bar.
     */
    @Test fun homeWithChatBar() {
        val b = capture("home-with-chat-bar") {
            androidx.compose.runtime.CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Box(Modifier.fillMaxSize()) {
                    HomeSkyBackground(night = false, modifier = Modifier.fillMaxSize())
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { HomeRedesignContent(HomeRedesignSample.state(), playIntro = false, drawSky = false) }
                        androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth()
                            .height((com.blaze.assistant.ui.composer.ChatBarTokens.HEIGHT_DP + 8).dp))
                    }
                    Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
                        com.blaze.assistant.ui.composer.BlazeChatBar(com.blaze.assistant.ui.composer.ComposerState())
                    }
                }
            }
        }
        // "All widgets" must end above the bar's top edge (the bar is the bottom HEIGHT_DP of the frame).
        val all = rule.onNode(androidx.compose.ui.test.hasText("All widgets"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val barTop = b.height - com.blaze.assistant.ui.composer.ChatBarTokens.HEIGHT_DP * rule.density.density
        assertTrue("All widgets (bottom ${all.bottom}) overlaps the chat bar (top $barTop)", all.bottom <= barTop)
    }

    /** The Interests title must show whole ("Interests · Tracking") in the 360 dp grid: no overflow, no clipped word. */
    @Test fun interestsTitleFits() {
        home("home-settled-title-check")
        val node = rule.onNode(androidx.compose.ui.test.hasText("Interests \u00b7 Tracking"), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val r = layouts.single()
        assertTrue("Interests title overflows: width ${r.size.width}, text ${r.multiParagraph.intrinsics.maxIntrinsicWidth}", !r.hasVisualOverflow)
        assertTrue(r.lineCount == 1 && r.getLineEnd(0) == "Interests \u00b7 Tracking".length)
    }

    /** Chat and Spotlight over Home with NO blur (low-RAM phones): the solid scrim + opaque bubbles. */
    private fun overNewHome(name: String, body: @androidx.compose.runtime.Composable () -> Unit) = capture(name) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBlaze provides DarkColors,
            com.blaze.assistant.ui.composer.LocalComposerSolid provides true) {
            Box(Modifier.fillMaxSize()) {
                // the app's layout: Home above a bottom slot as tall as the bar (HomeSurface), the scrim over all of it
                HomeSkyBackground(night = false, modifier = Modifier.fillMaxSize())
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { HomeRedesignContent(HomeRedesignSample.state(), playIntro = false, drawSky = false) }
                    androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height((com.blaze.assistant.ui.composer.ChatBarTokens.HEIGHT_DP + 8).dp))
                }
                Box(Modifier.fillMaxSize().background(com.blaze.assistant.composer.ComposerLook.scrim(DarkColors, blur = false)))
                body()
            }
        }
    }

    @Test fun chatOverNewHome() {
        val now = System.currentTimeMillis()
        val msgs = listOf(
            com.blaze.assistant.agent.ChatMessage(1, com.blaze.assistant.agent.Role.USER, "What's on today?", at = now - 120_000),
            com.blaze.assistant.agent.ChatMessage(2, com.blaze.assistant.agent.Role.BLAZE, "Standup at 10:00, then the dentist at 15:30.", at = now - 110_000))
        val st = com.blaze.assistant.ui.composer.ComposerState().apply { chatOpen = true }
        com.blaze.assistant.ui.MotionTokens.setEnabledForTests(false)
        val b = overNewHome("chat-over-new-home") {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { com.blaze.assistant.ui.composer.ChatOverHomeContent(st, msgs, busy = true, now = now) }
                com.blaze.assistant.ui.composer.BlazeChatBar(st)
            }
        }
        com.blaze.assistant.ui.MotionTokens.setEnabledForTests(true)
        // the scrim is strong: the frame's top-middle (Home's cards behind) is close to the theme background
        val p = b.getPixel(b.width / 2, b.height / 2)
        assertTrue("scrim too weak: ${Integer.toHexString(p)}", ((p shr 16) and 0xFF) < 0x40 && (p and 0xFF) < 0x48)
    }

    @Test fun spotlightOverNewHome() {
        com.blaze.assistant.mediaapps.MediaLib.seedForTests(listOf(com.blaze.assistant.mediaapps.Track("s1", "content://s1", "Bohemian Rhapsody", "Queen")), emptyList())
        val st = com.blaze.assistant.ui.composer.ComposerState().apply { spotlightOpen = true }
        com.blaze.assistant.ui.MotionTokens.setEnabledForTests(false)
        overNewHome("spotlight-over-new-home") {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    com.blaze.assistant.ui.composer.SpotlightPanel(st, onAct = {})
                }
                com.blaze.assistant.ui.composer.BlazeChatBar(st)
            }
        }
        com.blaze.assistant.ui.MotionTokens.setEnabledForTests(true)
    }
}
