package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 0.46.2 immersive replies: the pages a turn actually searched or read become source pills under the reply, and pictures those
 * pages carried (og:image / twitter:image / the article's main image, written by Web.fetch as "Image: <url> (WxH)") become a small
 * image strip. Everything comes from the turn's real tool results (never from URLs the model wrote); links that would repeat a
 * pill are taken out of the reply's text. Saved with the message (ChatMessage.sources). Pure; ReplySourcesTest.
 */
object ReplySources {
    const val MAX_PILLS = 8
    const val MAX_IMAGES = 3
    /** Images smaller than this (when the page states a size) are icons / pixels, not pictures. */
    const val MIN_W = 200; const val MIN_H = 120

    data class Source(val url: String, val title: String = "") {
        val domain: String get() = domain(url)
        val label: String get() = title.trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: domain
    }
    data class Image(val url: String, val from: String, val w: Int = 0, val h: Int = 0) { val domain: String get() = domain(from.ifBlank { url }) }
    data class Found(val sources: List<Source> = emptyList(), val images: List<Image> = emptyList()) { val isEmpty get() = sources.isEmpty() && images.isEmpty() }

    fun domain(u: String): String = try { URI(u.trim()).host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.") } catch (_: Exception) { "" }

    private val tracking = Regex("""(?i)^(utm_\w+|fbclid|gclid|mc_cid|mc_eid|ref|ref_src|igshid|si|spm|cmpid)$""")
    /** One canonical form per page: https/http kept, fragment and tracking parameters dropped, no trailing slash. */
    fun norm(u: String): String = try {
        val x = URI(u.trim())
        val q = x.rawQuery?.split('&')?.filter { it.isNotBlank() && !tracking.matches(it.substringBefore('=')) }?.joinToString("&").orEmpty()
        (x.scheme.lowercase() + "://" + x.rawAuthority.lowercase().removePrefix("www.") + x.rawPath.orEmpty().trimEnd('/') + (if (q.isNotEmpty()) "?$q" else ""))
    } catch (_: Exception) { u.trim().trimEnd('/') }

    private fun okPage(u: String): Boolean = try { val x = URI(u.trim()); (x.scheme == "https" || x.scheme == "http") && !x.host.isNullOrBlank() && x.rawUserInfo == null } catch (_: Exception) { false }

    /** web_search output ("1. Title\n   https://…\n   snippet") -> sources, in order. */
    fun fromSearch(result: String): List<Source> {
        if (result.startsWith("Error") || result.startsWith("No results")) return emptyList()
        val lines = result.lines()
        val out = ArrayList<Source>()
        for (i in lines.indices) {
            val m = Regex("""^\s*\d+\.\s+(.+)$""").find(lines[i]) ?: continue
            val url = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (okPage(url)) out += Source(url, m.groupValues[1].trim().take(120))
        }
        return out
    }

    private val imageLine = Regex("""(?m)^Image:\s*(\S+)(?:\s*\((\d+)\s*[x×]\s*(\d+)\))?\s*$""")

    /** fetch_page output -> the page as a source (its "# title") and the picture lines it carried. Errors give nothing. */
    fun fromFetch(url: String, result: String): Pair<Source?, List<Image>> {
        if (result.startsWith("Error") || !okPage(url)) return null to emptyList()
        val title = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(result.take(400))?.groupValues?.get(1)?.trim().orEmpty().take(120)
        val imgs = imageLine.findAll(result).mapNotNull { m ->
            val i = Image(m.groupValues[1], url, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0)
            i.takeIf { okImage(it.url, it.w, it.h) }
        }.toList()
        return Source(url, title) to imgs
    }

    private val pixel = Regex("""(?i)(pixel|spacer|blank|1x1|transparent|tracking|tracker|beacon|/tr\?|/b\?|analytics|doubleclick|googlesyndication|facebook\.com/tr|scorecardresearch|gravatar\.com/avatar|/favicon|/icons?/|sprite|logo[-_.]?(?:sm|small|icon)?\.(?:png|svg|gif))""")
    /** A picture worth showing: https only, an image type (no svg / data:), not a tracking pixel or icon, and not tiny when its size is known. */
    fun okImage(url: String, w: Int = 0, h: Int = 0): Boolean {
        val u = url.trim()
        val x = try { URI(u) } catch (_: Exception) { return false }
        if (x.scheme != "https" || x.host.isNullOrBlank() || x.rawUserInfo != null) return false
        val path = x.rawPath.orEmpty().lowercase()
        if (path.endsWith(".svg") || path.endsWith(".ico") || pixel.containsMatchIn(u)) return false
        if ((w in 1 until MIN_W) || (h in 1 until MIN_H)) return false
        // Sizes written in the URL (…?w=50, …/50x50/…).
        Regex("""(?i)[?&](?:w|width)=(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it < MIN_W) return false }
        Regex("""(?i)/(\d{1,4})x(\d{1,4})/""").find(u)?.let { if ((it.groupValues[1].toIntOrNull() ?: 999) < MIN_W || (it.groupValues[2].toIntOrNull() ?: 999) < MIN_H) return false }
        return true
    }
    /** At draw time: a decoded picture this small is hidden (no broken or icon-sized images in a reply). */
    fun bigEnough(w: Int, h: Int): Boolean = w >= MIN_W && h >= MIN_H

    /** Collects one turn's sources as its tools run (thread-safe); [take] hands them to the reply and starts over. */
    class Collector {
        private val src = LinkedHashMap<String, Source>()
        private val img = LinkedHashMap<String, Image>()
        @Synchronized fun addSearch(result: String) { fromSearch(result).forEach { s -> src.putIfAbsent(norm(s.url), s) } }
        @Synchronized fun addFetch(url: String, result: String) {
            val (s, ims) = fromFetch(url, result)
            // A page read after a search keeps the search's place, with the page's own title when the search had none.
            if (s != null) { val k = norm(s.url); val old = src[k]; src[k] = if (old == null) s else old.copy(title = old.title.ifBlank { s.title }) }
            ims.forEach { i -> img.putIfAbsent(norm(i.url), i) }
        }
        @Synchronized fun take(): Found { val r = Found(src.values.take(MAX_PILLS), img.values.take(MAX_IMAGES)); src.clear(); img.clear(); return r }
    }

    // ---------------- dedupe with the reply text ----------------

    private val mdLink = Regex("""\[([^\]\n]{1,200})]\((https?://[^)\s]+)\)""")
    private val bare = Regex("""<?(https?://[^\s<>()\]]+[^\s<>()\].,;:!?'"])>?""")
    private val sourcesHead = Regex("""(?im)^\s*(?:\*\*|#+\s*)?(?:sources?|references?|links?|read more)\s*:?\s*(?:\*\*)?\s*$""")

    /**
     * The reply with links that a pill already shows taken out: "[Title](url)" keeps "Title"; a bare url (or "<url>") disappears; a
     * trailing "Sources:" list made only of those links goes. Links to pages that aren't pills stay as they were.
     */
    fun stripInline(text: String, set: Found): String {
        if (set.sources.isEmpty()) return text
        val have = set.sources.map { norm(it.url) }.toHashSet()
        fun dup(u: String) = norm(u) in have
        var t = mdLink.replace(text) { m -> if (dup(m.groupValues[2])) m.groupValues[1] else m.value }
        // Lines that are only a list bullet + a pill's link go entirely.
        t = t.lines().filterNot { l -> val s = l.trim().removePrefix("-").removePrefix("*").removePrefix("\u2022").trim()
            bare.matchEntire(s)?.let { dup(it.groupValues[1]) } == true || Regex("""^\d+[.)]\s*(\S+)$""").find(s)?.let { bare.matchEntire(it.groupValues[1])?.let { b -> dup(b.groupValues[1]) } } == true
        }.joinToString("\n")
        t = bare.replace(t) { m -> if (dup(m.groupValues[1])) "" else m.value }
        // A "Sources:" heading left with nothing under it.
        val lines = t.lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isNotEmpty() && sourcesHead.matches(lines.last())) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n").replace(Regex("""[ \t]+([.,;:])"""), "$1").replace(Regex("""\(\s*\)"""), "").replace(Regex("[ \\t]{2,}"), " ").trimEnd()
    }

    // ---------------- saved with the message ----------------

    fun encode(s: Found): String = if (s.isEmpty) "" else JSONObject()
        .put("s", JSONArray(s.sources.map { JSONObject().put("u", it.url).put("t", it.title) }))
        .put("i", JSONArray(s.images.map { JSONObject().put("u", it.url).put("f", it.from).put("w", it.w).put("h", it.h) })).toString()

    fun decode(json: String?): Found = try {
        if (json.isNullOrBlank()) Found() else JSONObject(json).let { o ->
            Found(o.optJSONArray("s")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { j -> j.optString("u").takeIf { okPage(it) }?.let { Source(it, j.optString("t")) } } }.orEmpty().take(MAX_PILLS),
                o.optJSONArray("i")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { j -> j.optString("u").takeIf { okImage(it, j.optInt("w"), j.optInt("h")) }?.let { Image(it, j.optString("f"), j.optInt("w"), j.optInt("h")) } } }.orEmpty().take(MAX_IMAGES))
        }
    } catch (_: Exception) { Found() }
}
