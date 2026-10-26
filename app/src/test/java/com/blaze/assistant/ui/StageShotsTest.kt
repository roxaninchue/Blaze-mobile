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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.stage.StageItem
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageMode
import com.blaze.assistant.stage.StageTimer
import org.junit.Assert.assertEquals
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
 * 0.46.0 Blaze Stage screenshots (CI's shots job, BLAZE_SHOTS=1): every hero and every widget in three themes x light / dark
 * (build/shots/StageShotsTest/), each frame checked to be drawn (not blank), plus the timer card's Start button and labels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class StageShotsTest {
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

    private val t0 = 1_761_480_000_000L
    private val timer = StageTimer.start(StageTimer.create("timer1", "Study", 30.0), t0)
    private val items = listOf(
        StageItem("timer1", StageKind.TIMER, mapOf("title" to "Study")),
        StageItem("music2", StageKind.MUSIC),
        StageItem("video3", StageKind.VIDEO, mapOf("url" to "https://youtu.be/abc")),
        StageItem("quiz4", StageKind.QUIZ, mapOf("title" to "Biology", "picked" to "1", "score" to "1",
            "questions" to """[{"q":"Where does photosynthesis happen?","options":["Mitochondria","Chloroplast","Nucleus"],"answer":1},{"q":"2+2","options":["3","4"],"answer":1}]""")),
        StageItem("cards5", StageKind.FLASHCARDS, mapOf("deck" to "CIT102 loops")),
        StageItem("plan6", StageKind.PLAN, mapOf("title" to "Friday", "items" to """["Lab at 10","Quiz at 14:00","Gym"]""", "done" to "0")),
        StageItem("note7", StageKind.NOTE, mapOf("title" to "Note", "text" to "Bring the calculator and the lab coat.")),
        StageItem("browser8", StageKind.BROWSER),
        StageItem("music9", StageKind.MUSIC, mapOf("queue" to "true")),
    )
    private val queue = listOf("Midnight Drive" to "Lo-fi Lusaka", "Copper Sun" to "Kalindula Trio", "Rain on Tin" to "Zed Beats", "Night Bus" to "Lo-fi Lusaka")
        .mapIndexed { i, (t, a) -> com.blaze.assistant.mediaapps.StageMusicLogic.Entry("k$i", t, a) }
    private val musicNow = com.blaze.assistant.mediaapps.StageMusicLogic.Snapshot(true, true, "Midnight Drive", "Lo-fi Lusaka", 72_000, 201_000, 3,
        shuffle = true, repeat = com.blaze.assistant.mediaapps.Repeat.ALL, queue = queue, index = 0)
    private fun data(i: StageItem) = when (i.kind) {
        StageKind.TIMER -> StageData(now = t0 + 61_000, timer = timer)
        StageKind.MUSIC -> StageData(now = t0, music = musicNow)
        StageKind.BROWSER -> StageData(now = t0, browser = StageBrowserNow(true, "Blaze – Home", "https://blaze.example/", 60, canBack = true))
        StageKind.FLASHCARDS -> StageData(now = t0, cardFront = "What does a for loop repeat?", cardBack = "Its body, once per item", cardsLeft = 12)
        else -> StageData(now = t0)
    }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "StageShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    private val themes = listOf("" to "classic", "golden_ember" to "ember", "rose_dawn" to "rose")
    private val which = mutableStateOf(Triple("", false, 0))

    @Test fun heroesAndWidgets_3themes_lightDark() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val (id, dark, k) = which.value
            val c = blazePalette(id, dark, null)
            CompositionLocalProvider(LocalBlaze provides c) {
                Column(Modifier.fillMaxSize().background(c.paper).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hero = items[k]
                    StageHero(hero.copy(mode = StageMode.HERO), data(hero), modifier = Modifier.fillMaxWidth().height(430.dp)) {}
                    // Every widget under it (top strip / side rail sizes).
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.take(3).forEach { StageWidget(it, data(it)) {} } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.drop(3).take(2).forEach { StageWidget(it, data(it)) {} }; StageChatTile("Here's your Friday plan") {} }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.drop(5).forEach { StageWidget(it, data(it), narrow = true) {} }; StageWidget(items[0], data(items[0]), narrow = true) {}
                        Box(Modifier.padding(start = 8.dp)) { StageReplyPill {} } }
                }
            }
        }
        var frames = 0
        for ((id, tag) in themes) for (dark in listOf(false, true)) for (k in items.indices) {
            if (tag != "classic" && k > 3 && dark) continue   // keep the run short: all heroes in classic, the main ones everywhere
            which.value = Triple(id, dark, k)
            rule.mainClock.advanceTimeBy(600)
            val bmp = capture("stage-${items[k].kind.id}-$tag-${if (dark) "dark" else "light"}")
            assertTrue("${items[k].kind.id} $tag frame is blank", distinct(bmp) > 12)
            frames++
        }
        assertTrue(frames >= 30)
    }

    @Test fun menuGridAndEndedMusic() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Column(Modifier.fillMaxSize().background(DarkColors.paper).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageMenuGrid(listOf("Shrink", "Full view", "Top", "Side", "Bottom", "Chat", "Tile", "Hide", "Focus", "Reset", "Close").map {
                        StageMenuAction(androidx.compose.material.icons.Icons.Rounded.Close, it) {} }) {}
                    // The 0.46.0 bug: an ended song drew a full bar at 2:24 / 2:24 before playing.
                    StageHero(items[1].copy(mode = StageMode.HERO), StageData(now = t0, music = musicNow.copy(playing = false, posMs = 144_000, durMs = 144_000, state = 4)),
                        modifier = Modifier.fillMaxWidth().height(420.dp)) {}
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)
        val bmp = capture("stage-menu-and-ended-music")
        assertTrue(distinct(bmp) > 12)
        rule.onNodeWithText("0:00").assertExists()
        rule.onNodeWithText("-2:24").assertExists()
        rule.onNodeWithContentDescription("Play").assertExists()
        rule.onNodeWithContentDescription("Shuffle on").assertExists()
        rule.onNodeWithContentDescription("Repeat all").assertExists()
    }

    @Test fun timerCardHasStartAndLabels() {
        val fresh = StageTimer.create("timer1", "Study", 30.0)
        var op = ""
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                StageHero(items[0].copy(mode = StageMode.HERO), StageData(now = t0, timer = fresh), modifier = Modifier.fillMaxWidth().height(420.dp)) { e ->
                    if (e is StageEvent.Timer) op = e.op }
            }
        }
        rule.onNodeWithText("30:00").assertExists()
        rule.onNodeWithContentDescription("Start the Study timer").performClick()
        assertEquals("start", op)
        rule.onNodeWithContentDescription("Timer options").assertExists()
        rule.onNodeWithContentDescription("Shrink Timer to a widget").assertExists()
    }
}
