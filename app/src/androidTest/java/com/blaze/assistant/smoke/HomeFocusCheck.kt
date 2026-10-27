package com.blaze.assistant.smoke

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.home.HomeGrid
import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.TodoFeed
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.ui.HomeFocusState
import com.blaze.assistant.ui.HomeGridState
import com.blaze.assistant.ui.HomeMusicNow
import com.blaze.assistant.ui.HomeRedesignContent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * RC2 local check (NOT part of the official low-RAM gate, which runs only LowRamSmokeTest's six scenarios): card focus
 * open / close on the low-RAM emulator, every card 3 times on the RenderEffect path and on the software-blur path, with the
 * same 50 ms main-thread monitor and the same 700 ms limit. Run by .github/workflows/home-focus-check.yml.
 */
@RunWith(AndroidJUnit4::class)
class HomeFocusCheck {
    private val tag = "BlazeFocusCheck"
    private val main = Handler(Looper.getMainLooper())
    private val worst = AtomicLong(0)
    @Volatile private var phase = "start"; @Volatile private var worstAt = ""
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
                    if (lag > worst.get()) { worst.set(lag); worstAt = phase }
                }
                SystemClock.sleep(50)
            }
        }, "focus-stall-monitor").apply { isDaemon = true }.start()
    }

    private fun sample(): HomeGridState {
        val topics = listOf(
            InterestsFeed.Topic("n0", "Programming", "code", "Researchers have discovered a faster way to compile", "Facebook \u00b7 3 h ago", "https://example.com/a"),
            InterestsFeed.Topic("n1", "Startups", "trend", "This startup's self-driving excavator digs alone", "TechCrunch \u00b7 4 h ago", "https://example.com/b"),
            InterestsFeed.Topic("n2", "PlayStation", "gamepad", "PlayStation Handheld May Happen After All", "IGN \u00b7 5 h ago", "https://example.com/c"))
        val all = topics + InterestsFeed.Topic("n3", "Formula One", "f1", "Ferrari brings a new floor to Austin", "Autosport \u00b7 1 h ago", "https://example.com/d")
        val tasks = listOf(TodoFeed.Task("Buy groceries", true), TodoFeed.Task("Gym at 6 PM", false), TodoFeed.Task("Read 20 pages", false))
        val now = System.currentTimeMillis()
        return HomeGridState(WeatherBoard.Board("Ndola, ZM", "Broken Clouds", "24\u00b0C", "18:04", WeatherKind.PARTLY_CLOUDY, false, true),
            topics, HomeMusicNow("Track name", "Artist", 62_000L, 228_000L, true), tasks, 3, NextUp.Item(NextUp.Kind.REMINDER, "Reminder", now + 3_600_000L),
            QuickActions.build(listOf("Plan my day" to "Plan my day"), listOf("Programming", "Startups"), emptyList()), false, now, all)
    }

    private fun cycle(software: Boolean) {
        val focus = HomeFocusState()
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a -> a.setContent { HomeRedesignContent(sample(), playIntro = false, drawSky = true, focus = focus, forceSoftwareBlur = software) } }
        SystemClock.sleep(2_000)
        repeat(3) { round ->
            for (c in HomeGrid.Card.values()) {
                phase = "${if (software) "software" else "renderEffect"} open ${c.name} #$round"
                main.post { focus.open(c) }; SystemClock.sleep(1_100)
                phase = "${if (software) "software" else "renderEffect"} close ${c.name} #$round"
                main.post { focus.close() }; SystemClock.sleep(1_100)
            }
        }
        sc.close()
    }

    @Test fun focusOpenCloseStaysUnder700ms() {
        monitor()
        try { cycle(software = false); cycle(software = true) } finally { running = false }
        Log.i(tag, "FOCUS worst main stall ${worst.get()} ms during $worstAt (limit 700)")
        assertTrue("focus stalled ${worst.get()} ms during $worstAt", worst.get() <= 700)
    }
}
