package com.blaze.assistant.ui.splash

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 0.47.x sky splash frames (shots job, BLAZE_SHOTS=1): the four time-of-day variants mid-wave (t = 1140 ms), 360 dp. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class SkySplashShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun frame(name: String, phase: SkySplashSpec.Phase) {
        rule.mainClock.autoAdvance = false
        SkyArt.art.value = SkyArt.render(720, 1600, phase).asImageBitmap()
        rule.setContent { SkySplash(phase, ready = { false }, motion = true, onFinished = {}, frozenAtMs = 1140L) }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "SkySplashShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // the black cloud outline is drawn over the sky (some near-black pixels in the upper half)
        var dark = 0
        for (y in bmp.height / 4 until bmp.height / 2 step 2) for (x in 0 until bmp.width step 2) {
            val c = bmp.getPixel(x, y); if (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255) < 40) dark++
        }
        assertTrue("$name: outline missing ($dark)", dark > 50)
        SkyArt.release()
    }

    @Test fun dawn() = frame("sky-dawn", SkySplashSpec.Phase.DAWN)
    @Test fun day() = frame("sky-day", SkySplashSpec.Phase.DAY)
    @Test fun golden() = frame("sky-golden", SkySplashSpec.Phase.GOLDEN)
    @Test fun night() = frame("sky-night", SkySplashSpec.Phase.NIGHT)
}
