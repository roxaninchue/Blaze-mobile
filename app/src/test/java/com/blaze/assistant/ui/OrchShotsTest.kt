package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.Delivery
import com.blaze.assistant.agent.DeliveryState
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.orch.StatusLine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.47.0 the work pill and the send ticks, rendered (CI shots job, BLAZE_SHOTS=1): a thread with the spinner (sending),
 * the tick (delivered), the glass pill with a long line (ticker / ellipsis), and the pill opened to its step list, in
 * light and dark. Frames in build/shots/OrchShotsTest/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class OrchShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        assumeTrue(System.getProperty("blaze.shots") == "1")
        try { Settings.init(ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { }
    }

    private fun capture(name: String): Bitmap {
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "OrchShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private val steps = listOf(
        StatusLine.Step("p0", "Looking it up", "Reading 3 pages", StatusLine.State.DONE),
        StatusLine.Step("p1", "Browsing", "Searching flights Lusaka to Cape Town on Friday", StatusLine.State.RUNNING),
        StatusLine.Step("p2", "Scheduling", "Setting a reminder", StatusLine.State.STOPPED),
    )

    // one setContent per test (Compose rule): the frames switch theme / state through these
    private val darkS = androidx.compose.runtime.mutableStateOf(false)
    private val sendingS = androidx.compose.runtime.mutableStateOf(true)

    @Test fun pillAndTicks() {
        rule.setContent {
            val c = if (darkS.value) DarkColors else LightColors
            CompositionLocalProvider(LocalBlaze provides c) {
                Column(Modifier.fillMaxSize().background(c.paper).padding(12.dp)) {
                    UserBubble("Find me flights to Cape Town on Friday and remind me to pack", pos = GroupPos.SINGLE)
                    DeliveryLabel(if (sendingS.value) DeliveryState.SENDING else DeliveryState.SENT)
                    WorkPill("Searching flights Lusaka to Cape Town on Friday, cheapest first, with bags", steps)
                    ButlerIntroCard({ })
                }
            }
        }
        val pill = "Searching flights Lusaka to Cape Town on Friday, cheapest first, with bags"
        for (dark in listOf(false, true)) {
            val tag = if (dark) "dark" else "light"
            rule.runOnIdle { darkS.value = dark; sendingS.value = true }; capture("sending_$tag")
            rule.onNodeWithContentDescription(Delivery.spoken(DeliveryState.SENDING), useUnmergedTree = true).assertExists()
            rule.runOnIdle { sendingS.value = false }; capture("delivered_$tag")
            rule.onNodeWithContentDescription(Delivery.spoken(DeliveryState.SENT), useUnmergedTree = true).assertExists()
            rule.onNodeWithContentDescription(pill, useUnmergedTree = true).performClick()
            capture("pill_open_$tag")
            rule.onNodeWithContentDescription(pill, useUnmergedTree = true).performClick()
        }
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "OrchShotsTest")
        assertTrue((dir.listFiles()?.size ?: 0) >= 6)
    }
}
