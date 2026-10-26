package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.2 source pills + reply images: extraction, dedupe, inline-link stripping, image filter, persistence. */
class ReplySourcesTest {
    private val search = "1. Lusaka weather today\n   https://www.example.com/weather?utm_source=x\n   Sunny.\n2. Zambia news\n   https://news.example.org/a\n   Snippet"

    @Test fun pillsDedupeAcrossSearchAndFetch() {
        val c = ReplySources.Collector()
        c.addSearch(search)
        c.addFetch("https://example.com/weather", "# Weather in Lusaka\nImage: https://cdn.example.com/hero.jpg (1200x630)\nImage: https://cdn.example.com/pixel.gif (1x1)\nbody")
        c.addSearch(search)
        val f = c.take()
        assertEquals(2, f.sources.size)
        assertEquals("Lusaka weather today", f.sources[0].title)
        assertEquals(listOf("https://cdn.example.com/hero.jpg"), f.images.map { it.url })
        assertTrue(c.take().isEmpty)
        assertTrue(ReplySources.fromSearch("Error: x").isEmpty())
    }

    @Test fun imageFilter() {
        assertTrue(ReplySources.okImage("https://cdn.example.com/a.jpg", 800, 400))
        assertFalse(ReplySources.okImage("http://cdn.example.com/a.jpg"))
        assertFalse(ReplySources.okImage("https://cdn.example.com/a.svg"))
        assertFalse(ReplySources.okImage("https://www.facebook.com/tr?id=1"))
        assertFalse(ReplySources.okImage("https://cdn.example.com/a.jpg", 50, 50))
        assertFalse(ReplySources.okImage("https://cdn.example.com/a.jpg?w=64"))
        assertFalse(ReplySources.okImage("data:image/png;base64,AAAA"))
        assertTrue(ReplySources.bigEnough(400, 300)); assertFalse(ReplySources.bigEnough(32, 32))
    }

    @Test fun inlineLinksThatArePillsAreStripped() {
        val f = ReplySources.Found(listOf(ReplySources.Source("https://example.com/weather", "W")))
        val t = "It's sunny ([source](https://www.example.com/weather/)). See https://other.example/x\n\nSources:\n- https://example.com/weather"
        val out = ReplySources.stripInline(t, f)
        assertTrue(out, out.contains("source") && !out.contains("example.com/weather") && out.contains("https://other.example/x") && !out.contains("Sources:"))
    }

    @Test fun persists() {
        val f = ReplySources.Found(listOf(ReplySources.Source("https://a.example/x", "A")), listOf(ReplySources.Image("https://a.example/i.jpg", "https://a.example/x", 600, 400)))
        assertEquals(f, ReplySources.decode(ReplySources.encode(f)))
        assertEquals("", ReplySources.encode(ReplySources.Found()))
        assertTrue(ReplySources.decode("{bad").isEmpty)
    }
}
