package com.blaze.assistant.mediaapps

import com.blaze.assistant.movies.MovieInfo
import com.blaze.assistant.movies.MoviePlay
import com.blaze.assistant.movies.MoviePost
import com.blaze.assistant.movies.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.4 Video revamp: nav, Home row order + hero, details chips, player gestures. */
class VideoRevampTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()
    private fun post(i: Int, src: String = "archive", img: String = "https://archive.org/i/$i.jpg") = MoviePost("Film $i", "$src:$i", img, src)

    @Test fun navAndOldIds() {
        assertEquals(listOf("home", "search", "library", "sources"), VideoNav.tabs.map { it.id })
        assertEquals("home", VideoNav.normalize("movies"))
        assertEquals("store", VideoNav.normalize("store"))
        assertEquals(VideoNav.Tab.SEARCH, VideoNav.lit("store", "home"))
        assertEquals(VideoNav.Tab.SOURCES, VideoNav.lit("player", "sources"))
    }

    @Test fun homeRowOrderAndTitles() {
        assertEquals(listOf("archive", "blender", "local", "jellyfin", "p:one"), VideoHome.order(listOf("p:one", "local", "jellyfin", "archive", "blender")))
        // Rows only for sources the user has: nothing invented when a provider isn't installed.
        assertEquals(listOf("archive", "blender", "local"), VideoHome.order(listOf("archive", "blender", "local")))
        assertEquals("Free films", VideoHome.rowTitle("archive", "Internet Archive", "Feature films"))
        assertEquals("Blender open movies", VideoHome.rowTitle("blender", "Blender", ""))
        assertEquals("My videos", VideoHome.rowTitle("local", "Your videos", "Your videos"))
        assertEquals("Movies \u00b7 Home server", VideoHome.rowTitle("jellyfin", "Home server", "Movies"))
    }

    @Test fun heroPicksResumeThenOfficialPosters() {
        val pr = Progress(MoviePlay("m1", "Nosferatu", "https://archive.org/x.mp4", "https://archive.org/n.jpg", "archive"), 600_000, 5_000_000, 1)
        val rows = mapOf("local" to listOf(post(9, "local")), "blender" to listOf(post(1, "blender"), post(2, "blender", img = "")), "archive" to (3..8).map { post(it) })
        val h = VideoHome.hero(listOf(pr), rows)
        assertEquals(5, h.size)
        assertEquals("Nosferatu", h[0].title); assertTrue(h[0].resume != null)
        assertTrue(h.drop(1).all { it.post != null && it.image.isNotBlank() && it.post!!.source != "local" })
        assertEquals("archive:3", h[1].post!!.link)   // archive (Free films) before Blender
        assertTrue(VideoHome.hero(emptyList(), emptyMap()).isEmpty())
    }

    @Test fun detailsChipsAndMore() {
        val info = MovieInfo("The Stranger", "x", "", "movie", emptyList(), listOf("Film noir", "1946", "1 h 35 min"), "PG", licence = "Public domain")
        assertEquals(listOf("1946", "1 h 35 min", "PG", "Public domain"), VideoDetailsLogic.chips(info))
        val p = post(1)
        assertEquals(listOf("archive:2", "archive:3"), VideoDetailsLogic.moreLikeThis(p, listOf(p, post(2), post(3), post(2))).map { it.link })
        assertEquals("Resume", VideoDetailsLogic.playLabel(60_000)); assertEquals("Play", VideoDetailsLogic.playLabel(0))
    }

    @Test fun playerGestures() {
        assertTrue(PlayerGestures.fill(1.3f, false)); assertFalse(PlayerGestures.fill(0.7f, true)); assertTrue(PlayerGestures.fill(1.02f, true))
        assertEquals(-10_000L, PlayerGestures.doubleTapSeekMs(0.1f)); assertEquals(10_000L, PlayerGestures.doubleTapSeekMs(0.9f)); assertEquals(0L, PlayerGestures.doubleTapSeekMs(0.5f))
        assertFalse(PlayerGestures.allowed(true, false)); assertTrue(PlayerGestures.allowed(true, true)); assertTrue(PlayerGestures.allowed(false, false))
        val v = src("mediaapps/VideoActivity.kt")
        assertTrue("lock overlay", "if (controls && locked)" in v && "\"Unlock the screen\"" in v)
        assertTrue("pinch", "calculateZoom()" in v && "RESIZE_MODE_ZOOM" in v)
        assertTrue("PiP on Home whenever a video plays", "setAutoEnterEnabled(player.isPlaying && current.value != null)" in v)
        // The existing player stays: double-tap seek, vertical brightness / volume, speed, subtitles, up next.
        for (k in listOf("onDoubleTap", "detectVerticalDragGestures(", "Speeds.all", "TracksDialog(a)", "Up next in")) assertTrue(k, k in v)
    }
}

/** 0.46.5 progressive browse: rows stay, failures hidden, one note only for a single user-added source, empty state with Retry. */
class BrowseStateTest {
    private fun p(t: String, l: String) = MoviePost(t, l, "", "p:a")
    @Test fun keepsHidesAndNotes() {
        val ok = Result.success(listOf("Latest" to listOf(p("Film A", "1"), p("", "2"), p("Film A", "1"))))
        val merged = BrowseState.merge(null, ok)
        assertEquals(listOf("1"), merged.getOrThrow()[0].second.map { it.link })   // untitled + duplicate dropped
        val fail: Result<BrowseRows> = Result.failure(IllegalStateException("timeout"))
        assertEquals(merged, BrowseState.merge(merged, fail))                       // a failed refresh keeps the rows
        assertEquals(merged, BrowseState.merge(merged, Result.success(emptyList())))
        assertFalse(BrowseState.loaded(BrowseState.merge(null, fail)))
        val names = mapOf("p:a" to "Provider A", "p:b" to "Provider B", "archive" to "Archive")
        assertEquals("Couldn't reach Provider A. Check your connection.", BrowseState.note(names, listOf("p:a"), mapOf("p:a" to fail)))
        assertEquals("", BrowseState.note(names, listOf("p:a", "p:b"), mapOf("p:a" to fail, "p:b" to fail)))
        assertEquals("", BrowseState.note(names, listOf("p:a"), mapOf("p:a" to merged)))
        assertEquals("", BrowseState.note(names, listOf("p:a"), mapOf("p:a" to null)))   // still loading
        assertTrue(BrowseState.nothing(listOf("p:a", "archive"), mapOf("p:a" to fail, "archive" to Result.success(emptyList())), 0))
        assertFalse(BrowseState.nothing(listOf("p:a", "archive"), mapOf("p:a" to fail, "archive" to null), 0))
        assertFalse(BrowseState.nothing(listOf("p:a"), mapOf("p:a" to fail), 2))
        val v = java.io.File(listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }, "com/blaze/assistant/mediaapps").listFiles()!!
            .filter { it.name in setOf("VideoShell.kt", "MoviesUi.kt") }.joinToString("\n") { it.readText() }
        assertFalse("never list failed sources", Regex("""didn't answer|Didn't answer""").containsMatchIn(v))
    }
}
