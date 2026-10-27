package com.blaze.assistant.composer

import com.blaze.assistant.composer.SpotlightSearch.Act
import com.blaze.assistant.composer.SpotlightSearch.Distro
import com.blaze.assistant.composer.SpotlightSearch.Media
import com.blaze.assistant.composer.SpotlightSearch.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.47 Spotlight v2: the self-map registry search (prefixes, fallbacks never empty, fuzzy library matches, models). */
class SpotlightSearchTest {
    private val views = listOf(
        SelfMap.ViewInfo("home", "Home", "The Home cards"),
        SelfMap.ViewInfo("browser", "Browser", "Blaze's own browser and tabs"),
        SelfMap.ViewInfo("music", "Music", "Blaze Music: your songs", listOf("songs", "player")),
        SelfMap.ViewInfo("video", "Video", "Blaze Video: your videos", listOf("videos", "movies")),
        SelfMap.ViewInfo("terminal", "Terminal", "A real terminal: your Alpine Linux or the phone shell", listOf("shell", "console")),
        SelfMap.ViewInfo("memory", "Memory", "What Blaze remembers"),
        SelfMap.ViewInfo("look", "Look & feel", "Living backgrounds, theme", listOf("wallpaper", "theme")),
        SelfMap.ViewInfo("settings", "Settings", "Behaviour, voice, privacy, backup"),
    )
    private val map = SelfMap.of(views)
    private val songs = listOf(Media("s1", "Bohemian Rhapsody", "Queen"), Media("s2", "Blinding Lights", "The Weeknd"), Media("s3", "Lose Yourself", "Eminem"))
    private val videos = listOf(Media("v1", "Big Buck Bunny", "Blender"), Media("v2", "Sintel", "Blender"))
    private val distros = listOf(Distro("alpine", "Alpine Linux", true), Distro("ubuntu", "Ubuntu", false))

    private fun s(q: String) = SpotlightSearch.search(q, map, songs, videos, distros, "alpine")

    // ---------------------------------------------------------------- registry

    @Test fun registryHasEveryKind() {
        for (k in SelfMap.Kind.values()) assertTrue("no $k in the map", map.any { it.kind == k })
        assertEquals(views.size, map.count { it.kind == SelfMap.Kind.VIEW })
        assertEquals(4, map.count { it.kind == SelfMap.Kind.MODEL })
        assertEquals(map.size, map.map { it.id }.toSet().size)
    }

    @Test fun describeIsForTheAgentAndHasNoForbiddenNames() {
        val d = SelfMap.describe(map)
        assertTrue(d.contains("[view:terminal]") && d.contains("[model:inferno]") && d.contains("[action:chat]") && d.contains("[setting:wallpaper]"))
        assertFalse(Regex("(?i)\\b(hark|jarvis|open-?code|nvidia|nemotron)\\b").containsMatchIn(d))
    }

    @Test fun liveMapNeverThrowsAndHasViews() {
        val live = SelfMap.default
        assertTrue(live.count { it.kind == SelfMap.Kind.VIEW } >= 5)
        assertNotNull(live.firstOrNull { it.target == "terminal" })
    }

    // ---------------------------------------------------------------- empty / curated

    @Test fun emptyShowsShortCuratedList() {
        val r = s("   ")
        assertEquals(Mode.CURATED, r.mode)
        assertTrue(r.rows.size in 3..8)
        assertEquals("Chat with Blaze", r.rows.first().title)
        assertTrue(r.rows.any { it.title == "Views > Music" })
    }

    // ---------------------------------------------------------------- fallbacks never empty

    @Test fun unmatchedQueryOffersBrowseMusicVideoAndAskBlaze() {
        val r = s("zqxjv kkwp")
        assertEquals(Mode.PLAIN, r.mode)
        assertEquals(listOf("fb:browse", "fb:music", "fb:video", "fb:ask"), r.rows.map { it.id })
        assertEquals("Ask Blaze about \u2018zqxjv kkwp\u2019", r.rows.last().title)
        assertEquals(Act.AskBlaze("zqxjv kkwp"), r.rows.last().act)
        assertFalse(r.rows.any { it.title.contains("No results", true) })
    }

    @Test fun noQueryEverReturnsNothing() {
        val qs = listOf("a", "?", "@", "@zzz", "!", "! ls", "inf", "terminal", "ẞ", "  x  ", "@browser", "@browser google.com/food", "123456", "!!!", "@@")
        for (q in qs) {
            val r = s(q)
            assertTrue("empty for '$q'", r.rows.isNotEmpty())
            assertFalse(r.rows.any { it.title.contains("No results", true) })
        }
        // with an empty map and library too
        for (q in qs) assertTrue(SpotlightSearch.search(q, emptyList()).rows.isNotEmpty())
    }

    @Test fun matchedQueriesStillEndWithAskBlaze() {
        val r = s("music")
        assertEquals("fb:ask", r.rows.last().id)
        assertTrue(r.rows.size <= SpotlightSearch.MAX_ROWS)
    }

    // ---------------------------------------------------------------- plain text: both forms

    @Test fun terminalWithoutPrefixSuggestsFullViewAndInlinePreview() {
        val rows = s("terminal").rows
        val full = rows.first { it.title == "Views > Terminal" }
        val inline = rows.first { it.title == "Terminal" }
        assertEquals(Act.OpenView("terminal"), full.act)
        assertEquals(Act.Preview("terminal"), inline.act)
        assertEquals("Views > Terminal", rows[0].title)
        assertEquals("Terminal", rows[1].title)
    }

    @Test fun settingsWallpaperAndMusicAlsoShowBothForms() {
        for ((q, name) in listOf("settings" to "Settings", "music" to "Music", "wallpaper" to "Look & feel")) {
            val rows = s(q).rows
            assertTrue("$q full", rows.any { it.title == "Views > $name" && it.act is Act.OpenView })
            assertTrue("$q inline", rows.any { it.title == name && it.act is Act.Preview })
        }
        assertTrue(s("wallpaper").rows.any { it.title == "Settings > Wallpaper" })
    }

    // ---------------------------------------------------------------- models

    @Test fun modelsAreSearchable() {
        val top = s("inf").rows.first()
        assertEquals("Blaze Inferno \u00b7 Smartest", top.title)
        assertEquals(Act.PickModel("inferno"), top.act)
        assertEquals(Act.PickModel("spark"), s("spark").rows.first().act)
        assertTrue(s("fast").rows.any { it.act == Act.PickModel("spark") })
        assertTrue(s("balanced").rows.any { it.act == Act.PickModel("flare") })
        assertTrue(s("model").rows.count { it.act is Act.PickModel } == 4)
    }

    // ---------------------------------------------------------------- fuzzy library

    @Test fun fuzzyLibraryMatchesShowAsMusicLibrarySong() {
        val rows = s("bohemian").rows
        val hit = rows.first { it.act is Act.PlaySong }
        assertEquals("Music > Library > Bohemian Rhapsody", hit.title)
        assertEquals(Act.PlaySong("s1"), hit.act)
        // one typo still finds it
        assertTrue(s("bohemain").rows.any { it.title == "Music > Library > Bohemian Rhapsody" })
        // artist
        assertTrue(s("queen").rows.any { it.title == "Music > Library > Bohemian Rhapsody" })
        // word prefix
        assertTrue(s("blind").rows.any { it.title == "Music > Library > Blinding Lights" })
    }

    @Test fun fuzzyVideoMatches() {
        val hit = s("buck bunny").rows.first { it.act is Act.PlayVideo }
        assertEquals("Video > Library > Big Buck Bunny", hit.title)
        assertTrue(s("sintle").rows.any { it.title == "Video > Library > Sintel" })
    }

    @Test fun libraryIsNotNoisy() {
        assertFalse(s("xyz").rows.any { it.act is Act.PlaySong || it.act is Act.PlayVideo })
        assertTrue(s("l").rows.count { it.act is Act.PlaySong } <= SpotlightSearch.LIBRARY_MAX)
    }

    // ---------------------------------------------------------------- "!" terminal

    @Test fun bangIsATerminalCommandWithDistroPicker() {
        val r = s("!ls -la")
        assertEquals(Mode.TERMINAL, r.mode)
        assertEquals(Act.RunCommand("ls -la", "alpine"), r.rows.first().act)
        assertEquals("! ls -la", r.rows.first().title)
        assertEquals(distros, r.distros)
        assertTrue(r.distros.first { it.id == "alpine" }.installed)
        assertFalse(r.distros.first { it.id == "ubuntu" }.installed)
        assertTrue(r.rows.any { it.title == "Views > Terminal" })
    }

    @Test fun bangOnANotInstalledDistroOpensTheTerminalToSetItUp() {
        val r = SpotlightSearch.search("!apt update", map, distros = distros, distro = "ubuntu")
        assertEquals(Act.OpenView("terminal"), r.rows.first().act)
        assertTrue(r.rows.first().subtitle.contains("isn't installed"))
    }

    @Test fun bareBangHints() {
        val r = s("!")
        assertEquals(Mode.TERMINAL, r.mode)
        assertEquals("Type a command", r.rows.first().title)
    }

    // ---------------------------------------------------------------- "@" views

    @Test fun atListsViews() {
        val r = s("@")
        assertEquals(Mode.VIEWS, r.mode)
        assertTrue(r.rows.map { it.title }.containsAll(listOf("@browser", "@music", "@video", "@terminal", "@settings")))
        assertEquals(Act.Complete("@browser "), s("@bro").rows.first().act)
    }

    @Test fun atBrowserWithUrlOpensAnInlinePreviewOnEnter() {
        val top = s("@browser google.com/food").rows.first()
        assertEquals(Act.Preview("browser", "google.com/food"), top.act)
        assertTrue(top.subtitle.contains("google.com/food"))
        assertTrue(s("@browser google.com/food").rows.any { it.act == Act.OpenView("browser", "google.com/food") })
    }

    @Test fun atKnownViewAloneGivesPreviewThenFull() {
        val rows = s("@terminal").rows
        assertEquals(Act.Preview("terminal", ""), rows[0].act)
        assertEquals("Views > Terminal", rows[1].title)
        // an alias works too
        assertEquals(Act.Preview("music", ""), s("@songs").rows[0].act)
    }

    @Test fun atUnknownFallsBack() {
        val r = s("@qqqq")
        assertTrue(r.rows.isNotEmpty())
        assertEquals("fb:ask", r.rows.last().id)
    }

    // ---------------------------------------------------------------- fuzzy core + urls

    @Test fun fuzzyScores() {
        assertEquals(100, Fuzzy.score("music", "Music"))
        assertEquals(90, Fuzzy.score("ter", "Terminal"))
        assertEquals(80, Fuzzy.score("inf", "Blaze Inferno"))
        assertTrue(Fuzzy.score("termnal", "Terminal") >= 45)
        assertEquals(0, Fuzzy.score("zz", "Terminal"))
        assertTrue(Fuzzy.typo1("abc", "abd") && Fuzzy.typo1("abc", "abcd") && !Fuzzy.typo1("abc", "xyz"))
    }

    @Test fun urls() {
        assertEquals("https://google.com/food", SpotUrl.of("google.com/food"))
        assertEquals("http://x.io", SpotUrl.of("http://x.io"))
        assertTrue(SpotUrl.of("best food near me").startsWith("https://www.google.com/search?q=best+food"))
    }

    @Test fun modelChoiceWire() {
        assertEquals(null, ModelChoice.wire(ModelChoice.Family.AUTO))
        assertEquals("inferno", ModelChoice.wire(ModelChoice.Family.INFERNO))
        assertEquals("Spark (Fast)", ModelChoice.Family.SPARK.pickerLabel)
        assertEquals("Flare (Balanced)", ModelChoice.Family.FLARE.pickerLabel)
        assertEquals("Inferno (Smartest)", ModelChoice.Family.INFERNO.pickerLabel)
        assertEquals("Auto", ModelChoice.Family.AUTO.pickerLabel)
        assertEquals(ModelChoice.Family.AUTO, ModelChoice.of("nope"))
    }
}
