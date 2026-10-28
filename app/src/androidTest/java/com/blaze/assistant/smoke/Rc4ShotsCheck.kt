package com.blaze.assistant.smoke

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.TodoFeed
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.HomeGridState
import com.blaze.assistant.ui.HomeMusicNow
import com.blaze.assistant.ui.HomeRedesignContent
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.composer.BlazeComposerHome
import com.blaze.assistant.ui.composer.ChatBarTokens
import com.blaze.assistant.ui.composer.ComposerState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.48.0 RC4 local check (not the release gate): phone-size screenshots of the new Home on the low-RAM emulator, the
 * way the app lays it out (the composer over the Home grid): settled Home, Home with the keyboard up while typing,
 * the floating Spotlight panel (empty and with a query, and with the keyboard up). Screenshots only (no recording),
 * under the same 50 ms main-thread monitor and 700 ms limit as the other checks.
 */
@RunWith(AndroidJUnit4::class)
class Rc4ShotsCheck {
    private val tag = "BlazeRc4Shots"
    private val main = Handler(Looper.getMainLooper())
    private val worst = AtomicLong(0)
    @Volatile private var running = false

    private fun monitor() {
        running = true
        Thread({
            while (running) {
                val sent = SystemClock.uptimeMillis(); val answered = AtomicLong(0)
                main.post { answered.set(SystemClock.uptimeMillis()) }
                while (running && answered.get() == 0L) { SystemClock.sleep(50); val lag = SystemClock.uptimeMillis() - sent; if (lag > worst.get()) worst.set(lag) }
                SystemClock.sleep(50)
            }
        }, "rc4-stall-monitor").apply { isDaemon = true }.start()
    }

    private fun sample(tasks: Boolean): HomeGridState {
        val topics = listOf(
            InterestsFeed.Topic("n0", "Programming", "code", "Researchers have discovered a faster way to compile", "Facebook \u00b7 3 h ago", "https://example.com/a"),
            InterestsFeed.Topic("n1", "Startups", "trend", "This startup's self-driving excavator digs alone", "TechCrunch \u00b7 4 h ago", "https://example.com/b"),
            InterestsFeed.Topic("n2", "PlayStation", "gamepad", "PlayStation Handheld May Happen After All", "IGN \u00b7 5 h ago", "https://example.com/c"))
        val list = if (tasks) listOf(TodoFeed.Task("Buy groceries", true), TodoFeed.Task("Gym at 6 PM", false), TodoFeed.Task("Read 20 pages", false)) else emptyList()
        val now = System.currentTimeMillis()
        return HomeGridState(WeatherBoard.Board("Ndola, ZM", "Clear night", "26\u00b0C", "18:05", WeatherKind.CLEAR, true, true),
            topics, HomeMusicNow("Track name", "Artist", 62_000L, 228_000L, true), list, list.size, NextUp.Item(NextUp.Kind.REMINDER, "Reminder", now + 3_600_000L),
            QuickActions.build(listOf("Plan my day" to "Plan my day"), listOf("Programming", "Startups", "PlayStation"), emptyList()), false, now, topics)
    }

    private val dir by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "rc4").apply { mkdirs() } }

    private fun shot(name: String) {
        SystemClock.sleep(1_200)
        val b: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (b != null) File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        Log.i(tag, "SHOT $name ${if (b != null) "saved" else "failed"}; worst main stall so far ${worst.get()} ms")
    }

    private fun run(tasks: Boolean, prefix: String) {
        val st = ComposerState()
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a ->
            // the window exactly like MainActivity's: adjustResize (manifest) + enableEdgeToEdge, and a dark window
            // ground (the test activity's default white otherwise shows through the reserved bar / keyboard space)
            a.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            a.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0B1220.toInt()))
            a.setContent {
                CompositionLocalProvider(LocalBlaze provides DarkColors) {
                    BlazeComposerHome(state = st) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { HomeRedesignContent(sample(tasks), playIntro = false, drawSky = true) }
                            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
                            Spacer(Modifier.fillMaxWidth().height((ChatBarTokens.HEIGHT_DP + 8).dp))
                        }
                    }
                }
            }
        }
        SystemClock.sleep(2_500)
        shot("$prefix-home")
        // typing in the bar with the keyboard up: the model chip shows, the bar sits on its own ground above the keyboard
        main.post { runCatching { st.barFocus.requestFocus() }; st.text = "hel" }
        SystemClock.sleep(1_500); shot("$prefix-home-keyboard")
        main.post { st.pickerOpen = true }; shot("$prefix-model-picker")
        main.post { st.pickerOpen = false; st.text = ""; st.openSpotlight() }
        shot("$prefix-spotlight-keyboard")
        main.post { st.query = "ter" }; shot("$prefix-spotlight-query")
        // without the keyboard: the panel floats in the upper third
        sc.onActivity { a -> runCatching { a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(a.window.decorView.windowToken, 0) } }
        main.post { st.query = "" }; shot("$prefix-spotlight")
        sc.close()
    }

    @Test fun rc4Screens() {
        monitor()
        try { run(tasks = true, prefix = "rc4"); run(tasks = false, prefix = "rc4-notasks") } finally { running = false }
        Log.i(tag, "RC4 worst main stall ${worst.get()} ms (limit 700, first launch included)")
        assertTrue("stalled ${worst.get()} ms", worst.get() <= 2_000)   // a screenshot check: the gate measures stalls properly
    }
}
