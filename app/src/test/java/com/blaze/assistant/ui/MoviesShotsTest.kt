package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ReplySources
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.mediaapps.MovieDetails
import com.blaze.assistant.movies.MovieGroup
import com.blaze.assistant.movies.MovieInfo
import com.blaze.assistant.movies.MovieLink
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 0.46.2 screenshots (shots job, BLAZE_SHOTS=1): a movie's details page and a reply's source pills, each checked to be drawn. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class MoviesShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() { try { Settings.init(ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "MoviesShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    @Test fun movieDetailsAndSourcePills() {
        rule.mainClock.autoAdvance = false
        val info = MovieInfo("The Stranger", "A war crimes investigator tracks a fugitive to a quiet Connecticut town.", "", "movie",
            listOf(MovieGroup("Watch", direct = listOf(MovieLink("Play", "archive:TheStranger_0")))), listOf("1946", "Film noir"), "", listOf("Orson Welles"), "Public domain")
        val found = ReplySources.Found(listOf(ReplySources.Source("https://archive.org/details/TheStranger_0", "The Stranger (1946)"),
            ReplySources.Source("https://en.wikipedia.org/wiki/The_Stranger_(1946_film)", ""), ReplySources.Source("https://www.blender.org/about/projects/", "Blender open projects")))
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Column(Modifier.fillMaxSize().background(DarkColors.paper).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourcePills(found, Modifier.fillMaxWidth())
                    Column(Modifier.fillMaxWidth().height(620.dp)) {
                        MovieDetails(info, "Internet Archive", saved = true, msg = "", onBack = {}, onWatchlist = {}, onPick = {}, loadEpisodes = { emptyList() })
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)
        val bmp = capture("movies-details-pills-dark")
        assertTrue("movies frame is blank", distinct(bmp) > 12)
    }
}
