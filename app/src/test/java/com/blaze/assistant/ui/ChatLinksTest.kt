package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.28: links in chat replies are marked for tapping (markdown, <a href> and bare addresses). */
class ChatLinksTest {
    private fun links(md: String) = Md.render(md, Color.Blue, Color.Gray).let { r -> r.getStringAnnotations(ChatLinks.TAG, 0, r.length).map { it.item } }

    @Test fun bareAddressesTrimTrailingPunctuation() {
        assertEquals(listOf("https://blazeinchue.pages.dev"), ChatLinks.find("Get it at https://blazeinchue.pages.dev.").map { it.third })
        assertEquals(listOf("https://en.wikipedia.org/wiki/Ndola_(city)"), ChatLinks.find("(see https://en.wikipedia.org/wiki/Ndola_(city))").map { it.third })
        assertEquals(listOf("http://a.co/x?y=1"), ChatLinks.find("http://a.co/x?y=1, then").map { it.third })
        assertTrue(ChatLinks.find("no links here, just https:// and http://x").isEmpty())
        val (a, b, _) = ChatLinks.find("go https://x.io now").single()
        assertEquals(3, a); assertEquals(15, b)
    }

    @Test fun repliesMarkEveryKindOfLink() {
        assertEquals(listOf("https://example.com"), links("[Docs](https://example.com)"))
        assertEquals(listOf("https://a.b/c"), links("<a href=\"https://a.b/c\">here</a>"))
        assertEquals(listOf("https://x.io/my_file_name.pdf"), links("Download https://x.io/my_file_name.pdf today"))
        assertEquals(listOf("https://x.io"), links("**Site:** https://x.io."))
        assertEquals(listOf("https://a.io", "https://b.io"), links("- https://a.io\n- https://b.io"))
        assertTrue(links("plain words").isEmpty())
        assertTrue(links("```\nhttps://in.code.block\n```").isEmpty())
    }

    @Test fun underscoresInsideAnAddressStayPartOfIt() {
        val r = Md.render("see https://x.io/a_b_c here", Color.Blue, Color.Gray)
        assertEquals("see https://x.io/a_b_c here", r.text)
    }

    @Test fun tapLookupAndWhereItOpens() {
        val r = Md.render("Open https://x.io please", Color.Blue, Color.Gray)
        val at = r.text.indexOf("x.io")
        assertEquals("https://x.io", ChatLinks.at(r, at))
        assertNull(ChatLinks.at(r, 1))
        assertTrue(ChatLinks.hasLinks(r))
        assertFalse(ChatLinks.hasLinks(Md.render("nothing", Color.Blue, Color.Gray)))
        assertTrue(ChatLinks.inGlass("https://x.io")); assertTrue(ChatLinks.inGlass("HTTP://x.io"))
        assertFalse(ChatLinks.inGlass("mailto:a@b.c"))
    }
}
