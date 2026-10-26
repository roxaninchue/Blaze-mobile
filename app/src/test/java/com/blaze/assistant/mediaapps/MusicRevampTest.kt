package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.4 Music revamp: nav, close-to-chat, cover mosaic, colour fallback, now-playing gestures, Blaze DJ plan + plain speech. */
class MusicRevampTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()
    private fun t(i: Int, artist: String = "A$i", art: String = "", title: String = "Song $i", album: String = "", ms: Long = 200_000, added: Long = i.toLong()) =
        Track("k$i", "/m/$i.mp3", title, artist, album, ms, art, "download", added)

    @Test fun navHasFourTabsAndMapsOldIds() {
        assertEquals(listOf("home", "search", "library", "create"), MusicNav.tabs.map { it.id })
        assertEquals(listOf("Home", "Search", "Library", "Create"), MusicNav.tabs.map { it.label })
        assertEquals("home", MusicNav.normalize(""))
        assertEquals("home", MusicNav.normalize("foryou"))
        assertEquals("store", MusicNav.normalize("store"))
        assertEquals("player", MusicNav.normalize("player"))
        assertEquals(MusicNav.Tab.SEARCH, MusicNav.lit("store", "home"))
        assertEquals(MusicNav.Tab.LIBRARY, MusicNav.lit("player", "library"))
        assertTrue(MusicNav.showNav("home")); assertFalse(MusicNav.showNav("player"))
        assertTrue(MusicNav.showMini("search", true)); assertFalse(MusicNav.showMini("player", true)); assertFalse(MusicNav.showMini("home", false))
    }

    @Test fun closeAlwaysReturnsToBlazeChat() {
        assertEquals("chat", MusicNav.CLOSE_TARGET)
        val m = src("mediaapps/MusicActivity.kt")
        assertTrue("onClose = { BackToChat.go(this) }" in m)
        assertTrue(Regex("""MediaTopBar\([^\n]*\n\s*onAccount = \{ account = true \}, onClose = onClose\)""").containsMatchIn(m))
        assertTrue("onClose = { a.leaveToChat() }" in src("mediaapps/VideoActivity.kt"))
        val shell = src("mediaapps/MusicShell.kt")
        assertTrue("Close and go back to Blaze chat" in shell)
        // Account badge left, close right (badge is the first child, close the last).
        val bar = shell.substringAfter("fun MediaTopBar(").substringBefore("\n}\n")
        assertTrue(bar.indexOf("AccountBadge(onAccount)") < bar.indexOf("actions()") && bar.indexOf("actions()") < bar.indexOf("Icons.Rounded.Close"))
    }

    @Test fun bottomNavInMusicAndVideo() {
        assertTrue("MediaBottomNav(MusicNav.tabs" in src("mediaapps/MusicActivity.kt"))
        assertTrue("MediaBottomNav(VideoNav.tabs" in src("mediaapps/VideoActivity.kt"))
    }

    @Test fun playlistCoverMosaic() {
        val four = listOf(t(1, art = "/a1"), t(2, art = "/a1"), t(3, art = "/a2"), t(4, art = "/a3"), t(5, art = "/a4"), t(6, art = "/a5"))
        assertEquals(listOf("k1", "k3", "k4", "k5"), CoverMosaic.pick(four).map { it.key })
        assertTrue(CoverMosaic.isMosaic(four))
        val three = listOf(t(1, art = "/a1"), t(2, art = "/a2"), t(3, art = "/a2"), t(4, art = "/a3"))
        assertEquals(listOf("k1"), CoverMosaic.pick(three).map { it.key })
        assertTrue(CoverMosaic.pick(emptyList()).isEmpty())
        // No art: albums tell covers apart.
        val albums = (1..5).map { t(it, album = "Al$it") }
        assertEquals(4, CoverMosaic.pick(albums).size)
    }

    @Test fun coverTintFallsBackToTheTheme() {
        val accent = 0xFF336699.toInt()
        assertEquals(0x336699, CoverTint.of(null, accent))
        assertEquals(0x336699, CoverTint.of(IntArray(0), accent))
        assertEquals(0x336699, CoverTint.of(IntArray(256) { 0x00FF0000 }, accent))   // fully transparent
        val red = CoverTint.of(IntArray(256) { 0xFFE02020.toInt() }, accent)
        assertTrue(((red shr 16) and 0xff) > 200 && (red and 0xff) < 60)
    }

    @Test fun nowPlayingOpenCloseAndSwipes() {
        assertTrue(NowPlayingGestures.dismiss(200f, 0f, 140f))
        assertTrue(NowPlayingGestures.dismiss(20f, 2500f, 140f))
        assertFalse(NowPlayingGestures.dismiss(60f, 300f, 140f))
        assertEquals(1, NowPlayingGestures.skip(-300f, 1000f))
        assertEquals(-1, NowPlayingGestures.skip(300f, 1000f))
        assertEquals(0, NowPlayingGestures.skip(100f, 1000f))
        assertEquals(0, NowPlayingGestures.skip(100f, 0f))
        // Tapping the mini-player opens the player; Back / the chevron / a swipe down go back to the page under it.
        val m = src("mediaapps/MusicActivity.kt")
        assertTrue("MiniPlayer(ctl, np) { onTab(\"player\") }" in m)
        assertTrue("PlayerSheet(tab == \"player\", onCollapse = { onTab(under) })" in m)
        assertTrue("BackHandler(tab == \"player\") { onTab(under) }" in m)
        assertTrue("MusicContext.header(\"Road trip\") == \"Playing from Road trip\"".isNotEmpty())
        assertEquals("Playing from Road trip", MusicContext.header("Road trip"))
        assertEquals("Now playing", MusicContext.header(""))
    }

    @Test fun homeTilesMixesAndRecent() {
        val songs = (1..30).map { t(it, artist = "Artist ${it % 3}", album = "Album ${it % 5}") }
        val lists = (1..10).map { Playlist("p$it", "List $it", listOf("k$it"), it.toLong()) }
        val tiles = MusicHome.tiles("all", songs, lists, emptyList(), emptyList())
        assertEquals(8, tiles.size)
        assertEquals("p10", tiles.first().id)   // most recently updated first
        assertTrue(MusicHome.tiles("playlists", songs, lists, emptyList(), emptyList()).all { it.kind == "playlist" })
        assertTrue(MusicHome.tiles("artists", songs, lists, emptyList(), emptyList()).all { it.kind == "artist" })
        assertEquals(listOf("k3", "k1"), MusicHome.recentlyPlayed(songs, listOf("k3", "k1", "k3", "gone")).map { it.key })
        val mixes = MusicHome.mixes(songs, emptyList(), 20_000, 20)
        assertTrue(mixes.isNotEmpty() && mixes.size <= 8 && mixes.all { it.keys.size >= 3 })
        assertEquals(listOf("All", "Songs", "Albums", "Artists", "Playlists"), MusicHome.chips.map { it.second })
    }

    @Test fun playlistMetaSortAndCrop() {
        assertEquals("", PlaylistMeta.total(listOf(t(1, ms = 0))))
        assertEquals("34 min", PlaylistMeta.total(listOf(t(1, ms = 34 * 60_000L))))
        assertEquals("1 h 12 min", PlaylistMeta.total(listOf(t(1, ms = 60 * 60_000L), t(2, ms = 12 * 60_000L))))
        assertEquals(Triple(50, 0, 100), PlaylistMeta.cropSquare(200, 100))
        assertEquals(Triple(0, 25, 50), PlaylistMeta.cropSquare(50, 100))
        val l = listOf(t(1, title = "b", artist = "z"), t(2, title = "a", artist = "y"))
        assertEquals(listOf("k2", "k1"), PlaylistMeta.sort(l, PlaylistMeta.Sort.TITLE).map { it.key })
        assertEquals(listOf("k2", "k1"), PlaylistMeta.sort(l, PlaylistMeta.Sort.ADDED).map { it.key })
        assertEquals(listOf("k1", "k2"), PlaylistMeta.sort(l, PlaylistMeta.Sort.CUSTOM).map { it.key })
        assertEquals(listOf("Playlists", "Artists", "Albums", "Songs", "Downloaded"), LibraryView.chips.map { it.second })
        assertEquals("/sdcard/Music/a.lrc", LocalLyrics.lrcPathFor("file:///sdcard/Music/a.mp3"))
        assertEquals(null, LocalLyrics.lrcPathFor("content://media/1"))
    }

    @Test fun djPlansFromTheLibrary() {
        val songs = (1..40).map { t(it, artist = "Artist ${it % 4}", title = if (it % 10 == 0) "Chill $it" else "Song $it") }
        val recent = listOf("k1", "k2", "k3")
        val plan = DjPlan.sequence(songs, emptyList(), setOf("k5"), recent, DjPlan.Vibe.CHILL, 20, 15, 42)
        assertEquals(15, plan.size)
        assertEquals(plan.size, plan.map { it.key }.toSet().size)
        assertTrue(plan.none { it.key in recent })
        assertTrue("artists spaced out", plan.zipWithNext().none { (a, b) -> a.artist == b.artist })
        assertTrue("chill titles rank up", plan.take(6).count { it.title.startsWith("Chill") } >= 2)
        assertEquals(DjPlan.Vibe.LATE, DjPlan.vibeFor(23)); assertEquals(DjPlan.Vibe.UPBEAT, DjPlan.vibeFor(8))
        assertTrue(DjPlan.sequence(emptyList(), emptyList(), emptySet(), emptyList(), DjPlan.Vibe.AUTO, 9, 5, 1).isEmpty())
        val seed = songs[4]
        val more = DjPlan.moreLikeThis(seed, songs, emptyList(), emptyList(), 5)
        assertTrue(more.none { it.key == seed.key } && more.first().artist == seed.artist)
    }

    @Test fun djTalksPlainly() {
        val s = t(1, artist = "Sampa the Great", title = "Final Form")
        val lines = listOf(DjSpeech.start(s, DjPlan.Vibe.AUTO, 21), DjSpeech.next(s), DjSpeech.changed(DjPlan.Vibe.FOCUS), DjSpeech.moreLike(s)) +
            DjPlan.Vibe.values().map { DjSpeech.start(s, it, 9) }
        for (l in lines) {
            assertTrue(l, DjSpeech.plain(l))
            assertTrue(l, l.length < 140)
        }
        assertEquals("Next: Final Form by Sampa the Great.", DjSpeech.next(s))
        assertFalse(DjSpeech.plain("Let's embark on a sonic journey"))
        assertFalse(DjSpeech.plain("Sit back and enjoy the vibes"))
        // Off on battery saver, and the user can turn it off.
        val dj = src("mediaapps/BlazeDj.kt")
        assertTrue("fun speaks(ctx: Context): Boolean = voiceOn(ctx) && !lowPower(ctx)" in dj)
        assertTrue("isPowerSaveMode" in dj)
    }

    @Test fun ownLookNoOtherBrandAndNoCliNames() {
        val files = listOf("mediaapps/MusicShell.kt", "mediaapps/MusicHub.kt", "mediaapps/BlazeDj.kt", "mediaapps/VideoShell.kt", "mediaapps/VideoHub.kt")
        for (f in files) {
            val s = src(f)
            assertFalse("$f names Spotify", Regex("(?i)spotify").containsMatchIn(s))
            assertFalse("$f uses Spotify green", Regex("(?i)1DB954|1ED760").containsMatchIn(s))
            assertFalse("$f names OpenCode", Regex("(?i)open ?code").containsMatchIn(s))
        }
        // Themes and glass: the pages sit on the themed screen and use glass surfaces.
        assertTrue("ThemedScreen(" in src("mediaapps/MusicActivity.kt") && ".glass(" in src("mediaapps/MusicShell.kt"))
    }
}
