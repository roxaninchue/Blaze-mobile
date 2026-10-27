package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    @Test fun settled() {
        val b = home("home-settled")
        // the cards are dark navy over a blue sky: the top-left card's middle is darker than the sky at the edge
        val card = b.getPixel(b.width / 4, b.height / 6); val sky = b.getPixel(2, b.height / 3)
        assertTrue((card and 0xFF) < (sky and 0xFF))
    }
    @Test fun settledNoTasks() { home("home-settled-no-todo", withTasks = false) }
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
}
