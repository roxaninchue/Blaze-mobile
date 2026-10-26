package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.Home
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.mediaapps.*
import com.blaze.assistant.movies.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 0.46.5 Music + Video revamp frames (shots job, BLAZE_SHOTS=1): Music home, playlist, now playing; Video home and details. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class MediaRevampShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val songs = (1..14).map { Track("k$it", "", listOf("Final Form", "Kalimba", "Lusaka Nights", "Rain", "Copper", "Blue Hour", "Zambezi")[it % 7] + " $it",
        listOf("Sampa", "Mumba", "Chanda", "Bwalya")[it % 4], "Album ${it % 5}", 180_000L + it * 7_000, "", "download", it.toLong()) }

    @Before fun setUp() {
        try { Settings.init(ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { }
        MediaLib.seedForTests(songs)
        MediaLib.setPlaylists(listOf(Playlist("p1", "Road trip", songs.take(6).map { it.key }, 3), Playlist("p2", "Study", songs.drop(6).map { it.key }, 2),
            Playlist("p3", "Evening", songs.takeLast(4).map { it.key }, 1)))
    }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "MediaRevampShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    private fun frame(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { CompositionLocalProvider(LocalBlaze provides DarkColors) { Box(Modifier.fillMaxSize().background(DarkColors.paper)) { content() } } }
        rule.mainClock.advanceTimeBy(800)
        assertTrue("$name frame is blank", distinct(capture(name)) > 12)
    }

    private val nav = MusicNav.tabs.map { NavItem(it.id, it.label, androidx.compose.material.icons.Icons.Rounded.Home) }

    @Test fun musicHome() = frame("music-home-dark") {
        Column(Modifier.fillMaxSize()) {
            MediaTopBar("Music", {}, {})
            Box(Modifier.weight(1f).fillMaxWidth()) { MusicHomePage(remember { NowPlaying() }, {}, { _, _, _ -> }, {}) }
            MediaBottomNav(nav, "home") {}
        }
    }

    @Test fun musicPlaylist() = frame("music-playlist-dark") {
        PlaylistPage(MusicHome.Tile("p1", "Road trip", songs.take(6).map { it.key }, "playlist"), remember { NowPlaying() }, { _, _, _ -> }, {}, {}, {})
    }

    @Test fun musicNowPlaying() = frame("music-nowplaying-dark") {
        val np = remember { NowPlaying().also { it.item = MediaItem.Builder().setMediaId("k3").build(); it.dur = 200_000; it.pos = 61_000; it.count = 6 } }
        MusicContext.set("Road trip")
        PlayerPage(null, np, { _, _, _ -> }, {})
    }

    private val src = object : MovieSource {
        override val id = "archive"; override val name = "Internet Archive"; override val official = true
        override suspend fun rows() = emptyList<Pair<String, List<MoviePost>>>()
        override suspend fun search(query: String) = emptyList<MoviePost>()
        override suspend fun info(post: MoviePost) = MovieInfo(post.title)
        override suspend fun streams(link: MovieLink) = emptyList<MovieStream>()
    }
    private val posts = listOf("Nosferatu", "The General", "Metropolis", "His Girl Friday", "Sita Sings the Blues").mapIndexed { i, t -> MoviePost(t, "archive:$i", "", "archive") }

    @Test fun videoHome() = frame("video-home-dark") {
        Column(Modifier.fillMaxSize()) {
            MediaTopBar("Video", {}, {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VideoHomeContent(listOf(src), mapOf("archive" to Result.success(listOf("Feature films" to posts))),
                    listOf(Progress(MoviePlay("m1", "Nosferatu", "u", "", "archive"), 600_000, 5_000_000, 1)), posts.take(2), { false }, { _, _ -> }, {})
            }
            MediaBottomNav(VideoNav.tabs.map { NavItem(it.id, it.label, androidx.compose.material.icons.Icons.Rounded.Home) }, "home") {}
        }
    }

    @Test fun videoDetails() = frame("video-details-dark") {
        val info = MovieInfo("The Stranger", "A war crimes investigator tracks a fugitive to a quiet Connecticut town, where the man has a new name, a new wife and a job at a school.", "", "movie",
            listOf(MovieGroup("Watch", direct = listOf(MovieLink("Play", "archive:TheStranger_0")))), listOf("1946", "1 h 35 min", "Film noir"), "", listOf("Orson Welles"), "Public domain")
        MovieDetails(info, "Internet Archive", saved = true, msg = "", onBack = {}, onWatchlist = {}, onPick = {}, loadEpisodes = { emptyList() },
            more = posts, onMore = {}, resumeMs = 600_000, onResume = {}, onDownload = {}, onShare = {})
    }
}
