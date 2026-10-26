package com.blaze.assistant.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.46.2 Internet Archive parsers against recorded archive.org responses (test/resources/movies). */
class ArchiveMoviesTest {
    private fun res(n: String) = listOf(File("src/test/resources/movies/$n"), File("app/src/test/resources/movies/$n")).first { it.isFile }.readText()

    @Test fun searchDocsBecomePosts() {
        val p = ArchiveMovieParse.search(res("archive-search.json"), "archive")
        assertEquals(5, p.size)
        assertEquals("Escape From Sobibor", p[0].title)
        assertEquals("archive:Escape_From_Sobibor.avi", p[0].link)
        assertEquals("1987", p[0].tag)
        assertTrue(p.all { it.image.startsWith("https://archive.org/services/img/") && it.source == "archive" })
        assertTrue(ArchiveMovieParse.search("{}", "archive").isEmpty())
    }

    @Test fun metadataInfoAndStreams() {
        val j = res("archive-metadata-TheStranger_0.json")
        val i = ArchiveMovieParse.info(j, "Free")
        assertEquals("The Stranger", i.title)
        assertEquals("Public domain", i.licence)
        assertTrue(i.tags.contains("1946"))
        assertEquals("archive:TheStranger_0", i.groups.single().direct.single().link)
        val s = ArchiveMovieParse.streams(j)
        assertEquals(3, s.size)
        assertEquals("mp4", s[0].type); assertEquals("480p", s[0].quality)
        assertEquals("https://archive.org/download/TheStranger_0/The_Stranger.mp4", s[0].link)
        assertEquals("ogv", s.last().type)
        assertEquals("Auto captions", s[0].subtitles.single().label)
        assertEquals("Sintel", ArchiveMovieParse.words("Sintel (2010)*").replace(" 2010", ""))
    }
}
