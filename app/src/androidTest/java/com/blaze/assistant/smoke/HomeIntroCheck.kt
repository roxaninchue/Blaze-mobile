package com.blaze.assistant.smoke

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.TodoFeed
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.ui.HomeGridState
import com.blaze.assistant.ui.HomeIntroClock
import com.blaze.assistant.ui.HomeMusicNow
import com.blaze.assistant.ui.HomeRedesignContent
import com.blaze.assistant.ui.MotionTokens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.48.0 RC3 local check (NOT part of the official low-RAM gate, which runs only LowRamSmokeTest's six scenarios): the whole
 * Home entry sequence (splash -> board -> each card -> chat bar) played live on the low-RAM emulator with animations on,
 * watched by the same 50 ms main-thread monitor and held to the same 700 ms limit; a screenshot every 0.5 s goes to the
 * app's files (intro/) for comparison with the approved video. The workflow screen-records it too.
 * The first run is a warm-up (classes, emulator shaders) that also takes the screenshots; the second is measured.
 */
@RunWith(AndroidJUnit4::class)
class HomeIntroCheck {
    private val tag = "BlazeIntroCheck"
    private val main = Handler(Looper.getMainLooper())
    private val worst = AtomicLong(0)
    @Volatile private var running = false

    private fun monitor() {
        running = true
        Thread({
            while (running) {
                val sent = SystemClock.uptimeMillis(); val answered = AtomicLong(0)
                main.post { answered.set(SystemClock.uptimeMillis()) }
                while (running && answered.get() == 0L) {
                    SystemClock.sleep(50)
                    val lag = SystemClock.uptimeMillis() - sent
                    if (lag > worst.get()) worst.set(lag)
                }
                SystemClock.sleep(50)
            }
        }, "intro-stall-monitor").apply { isDaemon = true }.start()
    }

    private fun sample(): HomeGridState {
        val topics = listOf(
            InterestsFeed.Topic("n0", "Programming", "code", "Researchers have discovered a faster way to compile", "Facebook \u00b7 3 h ago", "https://example.com/a"),
            InterestsFeed.Topic("n1", "Startups", "trend", "This startup's self-driving excavator digs alone", "TechCrunch \u00b7 4 h ago", "https://example.com/b"),
            InterestsFeed.Topic("n2", "PlayStation", "gamepad", "PlayStation Handheld May Happen After All", "IGN \u00b7 5 h ago", "https://example.com/c"))
        val tasks = listOf(TodoFeed.Task("Buy groceries", true), TodoFeed.Task("Gym at 6 PM", false), TodoFeed.Task("Read 20 pages", false))
        val now = System.currentTimeMillis()
        return HomeGridState(WeatherBoard.Board("Ndola, ZM", "Broken Clouds", "24\u00b0C", "18:04", WeatherKind.PARTLY_CLOUDY, false, true),
            topics, HomeMusicNow("Track name", "Artist", 62_000L, 228_000L, true), tasks, 3, NextUp.Item(NextUp.Kind.REMINDER, "Reminder", now + 3_600_000L),
            QuickActions.build(listOf("Plan my day" to "Plan my day"), listOf("Programming", "Startups", "PlayStation"), emptyList()), false, now)
    }

    private fun play(label: String, shots: File?): Long {
        worst.set(0)
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a -> a.setContent { HomeRedesignContent(sample(), playIntro = true, drawSky = true) } }
        val t0 = SystemClock.uptimeMillis()
        var k = 0
        while (SystemClock.uptimeMillis() - t0 < 19_000) {
            if (shots != null) try {
                val b: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (b != null) File(shots, "intro-%04.1f.png".format(java.util.Locale.ROOT, k * 0.5f)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
            } catch (_: Throwable) { }
            k++
            val next = t0 + k * 500L
            SystemClock.sleep((next - SystemClock.uptimeMillis()).coerceAtLeast(1))
        }
        val w = worst.get()
        Log.i(tag, "INTRO $label: worst main stall $w ms over 19 s (limit 700)")
        sc.close()
        return w
    }

    @Test fun introPlaysWithinTheStallBudget() {
        MotionTokens.setEnabledForTests(true)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "intro").apply { mkdirs() }
        monitor()
        try {
            // screenshots during the warm-up (taking them costs the emulator CPU), the second run is measured clean
            HomeIntroClock.played = false
            play("warm-up + screenshots", dir)
            SystemClock.sleep(1_000)
            HomeIntroClock.played = false
            val w = play("measured", null)
            Log.i(tag, "INTRO screenshots in ${dir.absolutePath}")
            assertTrue("intro stalled $w ms", w <= 700)
        } finally { running = false }
    }
}
