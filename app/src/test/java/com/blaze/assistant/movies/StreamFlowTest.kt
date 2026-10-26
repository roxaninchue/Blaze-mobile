package com.blaze.assistant.movies

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.5 generic source flow with neutral mocks ("Provider A"): release-title parsing, season grouping, stream order +
 * fallback, and the headers the player gets. No real provider names, domains or fixtures.
 */
class StreamFlowTest {
    @Test fun parsesReleaseTitles() {
        val a = ReleaseTitle.parse("Season 17 [Hindi \u2013 English \u2013 Japanese] 720p x264 [200MB/E]")
        assertEquals(17, a.season); assertEquals(720, a.height); assertEquals("720p", a.quality)
        assertEquals(listOf("English", "Hindi", "Japanese"), a.languages.sorted())
        assertEquals("200 MB per episode", a.size)
        assertFalse(a.clean.contains("x264") || a.clean.contains("720p") || a.clean.contains("Hindi"))
        val b = ReleaseTitle.parse("Show.Name.S02E05.Some.Title.2160p.WEB-DL.1.4GB")
        assertEquals(2, b.season); assertEquals(5, b.episode); assertEquals("4K", b.quality); assertEquals("1.4 GB", b.size)
        assertEquals("4K", ReleaseTitle.parse("Movie Title (2019) 4K UHD").quality)
        assertEquals("480p", ReleaseTitle.parse("480p | ENG").quality)
        assertEquals(listOf("English"), ReleaseTitle.parse("480p | ENG").languages)
        assertNull(ReleaseTitle.parse("Download Links").season)
    }

    @Test fun groupsSeasonsAndPicksTheVariant() {
        val groups = listOf(
            MovieGroup("Season 2 [English] 1080p [600MB/E]", episodesLink = "a/s2-1080"),
            MovieGroup("Season 1 [English - Japanese] 720p [200MB/E]", episodesLink = "a/s1-720"),
            MovieGroup("Season 1 [English - Japanese] 1080p [500MB/E]", episodesLink = "a/s1-1080"),
            MovieGroup("Season 1 480p", episodesLink = "a/s1-480"))
        val seasons = ReleaseTitle.seasons(groups)
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(3, seasons[0].variants.size)
        assertEquals("Season 1", seasons[0].label)
        assertEquals(1080, ReleaseTitle.pick(seasons[0].variants, 1080)!!.height)
        assertEquals(720, ReleaseTitle.pick(seasons[0].variants, 720)!!.height)            // fits the data saver cap
        assertEquals(480, ReleaseTitle.pick(seasons[0].variants, 1080, prefHeight = 480)!!.height) // remembered choice
        assertEquals(480, ReleaseTitle.pick(seasons[0].variants, 360)!!.height)            // nothing fits: the smallest
        // A film: no seasons, one "Watch" with its qualities.
        val film = ReleaseTitle.seasons(listOf(MovieGroup("720p [1GB]", direct = listOf(MovieLink("Play", "a/m720"))), MovieGroup("1080p [2GB]", direct = listOf(MovieLink("Play", "a/m1080")))))
        assertEquals(1, film.size); assertNull(film[0].number); assertEquals("Watch", film[0].label)
        val eps = ReleaseTitle.episodes(listOf(MovieLink("Episode 1", "e1"), MovieLink("S01E02 The Second One 720p x264", "e2", image = "t2"), MovieLink("Show.S01E03.mkv", "e3")))
        assertEquals(listOf(1, 2, 3), eps.map { it.number })
        assertEquals("Episode 1", eps[0].title)
        assertTrue(eps[1].title.contains("The Second One") && !eps[1].title.contains("x264"))
        assertEquals("t2", eps[1].link.image)
    }

    private val providerA = object : MovieSource {
        override val id = "p:provider-a"; override val name = "Provider A"; override val official = false
        override suspend fun rows() = emptyList<Pair<String, List<MoviePost>>>()
        override suspend fun search(query: String) = emptyList<MoviePost>()
        override suspend fun info(post: MoviePost) = MovieInfo(post.title, groups = listOf(MovieGroup("720p", direct = listOf(MovieLink("Play", "a/play")))))
        override suspend fun streams(link: MovieLink): List<MovieStream> = MovieJson.streams(JSONArray("""[
            {"server":"Server 1","link":"https://media.example.test/v/master","type":"m3u8","quality":"1080p","headers":{"referer":"https://watch.example.test/","User-Agent":"Mock/1.0","X-Empty":""}},
            {"server":"Server 2","link":"https://media.example.test/v/720.mp4","type":"mp4","quality":"720p"},
            {"server":"Server 3","link":"https://media.example.test/v/master","type":"m3u8","quality":"1080p"},
            {"server":"Bad","link":"ftp://media.example.test/v.mp4","type":"mp4","quality":"480p"},
            {"server":"Odd","link":"https://media.example.test/v/strange","type":"torrent","quality":"480p"},
            {"server":"Dash","link":"https://media.example.test/v/manifest.mpd","type":"","quality":""}
        ]"""))
    }

    @Test fun resolvesStreamsInOrderWithHeaders() = runBlocking {
        val play = MovieHub.resolve(providerA, MoviePost("Film A", "a/film", "", "p:provider-a"), 1080)
        assertEquals("https://media.example.test/v/master", play.url)
        assertEquals(StreamPlan.Kind.HLS, StreamPlan.kind(play.type, play.url))
        // The headers the player sends: names normalised, empty ones dropped.
        assertEquals(mapOf("Referer" to "https://watch.example.test/", "User-Agent" to "Mock/1.0"), play.headers)
        // The fallbacks: the 720p file, then the DASH manifest; duplicates, non-http and unknown types out.
        assertEquals(listOf("https://media.example.test/v/720.mp4", "https://media.example.test/v/manifest.mpd"), play.alternates.map { it.link })
        assertEquals(StreamPlan.Kind.DASH, StreamPlan.kind(play.alternates[1].type, play.alternates[1].link))
        // Saved and read back: the type survives (alternates are not kept).
        val back = MoviePlay.parse(play.toJson().toString())!!
        assertEquals(play.type, back.type); assertTrue(back.alternates.isEmpty()); assertEquals(play.headers, back.headers)
    }

    @Test fun fallsBackThroughStreamsThenOnePlainMessage() {
        val tries = listOf("s1", "s2", "s3")
        var i = 0; val log = ArrayList<String>()
        // Every stream fails: each is logged (no URL, no header values), then the plain message.
        while (true) {
            log += StreamPlan.logLine("p:provider-a", i, tries.size, StreamPlan.Kind.HLS, "ERROR_CODE_IO_BAD_HTTP_STATUS at https://media.example.test/v?token=secret")
            i = StreamPlan.next(i, tries.size) ?: break
        }
        assertEquals(3, log.size)
        assertTrue(log.none { "secret" in it || "example.test" in it })
        assertEquals("This source didn't give a playable video. Try another quality or source.", StreamPlan.FAIL_TEXT)
        // Order: remembered quality first, then the best that fits, then no-quality, then too big.
        val s = listOf(MovieStream("a", "https://x.test/1", "mp4", "2160p"), MovieStream("b", "https://x.test/2", "mp4", "480p"),
            MovieStream("c", "https://x.test/3", "m3u8", ""), MovieStream("d", "https://x.test/4", "mp4", "720p"))
        assertEquals(listOf("d", "b", "c", "a"), StreamPlan.order(s, 1080).map { it.server })
        assertEquals(listOf("b", "d", "c", "a"), StreamPlan.order(s, 1080, prefHeight = 480).map { it.server })
        assertEquals(StreamPlan.Kind.HLS, StreamPlan.kind("mp4", "https://x.test/a/index.m3u8?t=1"))
        assertEquals(StreamPlan.Kind.PROGRESSIVE, StreamPlan.kind("", "https://x.test/a/file"))
    }

    @Test fun playerGetsHeadersMimeAndFallback() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val v = java.io.File(root, "com/blaze/assistant/mediaapps/VideoActivity.kt").readText()
        assertTrue("http.setDefaultRequestProperties(hdr)" in v && "http.setUserAgent(hdr[\"User-Agent\"])" in v)
        assertTrue("MimeTypes.APPLICATION_M3U8" in v && "MimeTypes.APPLICATION_MPD" in v && ".setMimeType(mime)" in v)
        assertTrue("if (!movieFailed(error.errorCodeName)) retryStream(error)" in v)
        assertTrue("StreamPlan.START_TIMEOUT_MS" in v)
        val g = listOf(java.io.File("build.gradle.kts"), java.io.File("app/build.gradle.kts")).first { it.isFile && "media3" in it.readText() }.readText()
        assertTrue("media3-exoplayer-hls" in g && "media3-exoplayer-dash" in g)
    }
}
