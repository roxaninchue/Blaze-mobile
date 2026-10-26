package com.blaze.assistant.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString

/**
 * v0.45.28: links in Blaze's chat replies. [Md] marks markdown links, <a href> links and bare web addresses with a
 * "URL" annotation (underlined in the accent colour); a tap on one in a reply calls [LocalOpenLink], which the chat
 * screen points at the same glass window Home opens its links in ([GlassWindow]). Long-press still selects and copies.
 * Pure parts tested in ChatLinksTest.
 */
object ChatLinks {
    const val TAG = "URL"

    /** Bare web addresses in plain text (markdown links are handled by Md itself). */
    private val bare = Regex("""(?i)\bhttps?://[^\s<>"'`\[\]{}|\\^]+""")
    private const val TRAILING = ".,;:!?)'\"»”’"

    /** Bare http(s) links in [text] as (start, end-exclusive, url), trailing punctuation left out ("see https://x.com." → x.com). */
    fun find(text: String): List<Triple<Int, Int, String>> = bare.findAll(text).mapNotNull { m ->
        var url = m.value
        // A closing bracket belongs to the link only when the link opened one ("https://en.wikipedia.org/wiki/X_(y)").
        while (url.isNotEmpty() && url.last() in TRAILING &&
            !(url.last() == ')' && url.count { it == '(' } >= url.count { it == ')' })) url = url.dropLast(1)
        if (url.length <= "https://".length || !url.substringAfter("://").contains('.')) null
        else Triple(m.range.first, m.range.first + url.length, url)
    }.toList()

    /** The link under character [offset] of [text], or null. */
    fun at(text: AnnotatedString, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        return text.getStringAnnotations(TAG, offset, offset).firstOrNull()?.item
            ?: if (offset > 0) text.getStringAnnotations(TAG, offset - 1, offset - 1).firstOrNull()?.item else null
    }

    fun hasLinks(text: AnnotatedString): Boolean = text.getStringAnnotations(TAG, 0, text.length).isNotEmpty()

    /** Web links open in the glass window; mailto: and the rest go to the phone. */
    fun inGlass(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true)
}

/** Where a tapped link in a chat reply goes (null = links aren't tappable here). */
val LocalOpenLink = staticCompositionLocalOf<((String) -> Unit)?> { null }
