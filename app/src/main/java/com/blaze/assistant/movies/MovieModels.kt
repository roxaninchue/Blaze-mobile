package com.blaze.assistant.movies

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.2 Movies: the shapes every movie source speaks (built-in official sources and user-added provider repos alike).
 * The provider half follows the public provider interface of the Vega app (Apache-2.0, at or before commit 9ccc0c8; see
 * THIRD_PARTY_NOTICES.md): posts (title, link, image), meta/info (title, synopsis, image, linkList of seasons / qualities with
 * episodesLink or directLinks), episodes, and streams (server, link, type, quality, subtitles, headers). Reimplemented in Kotlin.
 * Pure (org.json only); MovieModelsTest.
 */
data class MoviePost(val title: String, val link: String, val image: String = "", val source: String = "", val tag: String = "")

data class MovieLink(val title: String, val link: String, val type: String = "movie", val image: String = "")

/** One season or quality group of a title: either a link to fetch episodes from, or the links themselves. */
data class MovieGroup(val title: String, val quality: String = "", val episodesLink: String = "", val direct: List<MovieLink> = emptyList())

data class MovieInfo(val title: String, val synopsis: String = "", val image: String = "", val type: String = "movie",
                     val groups: List<MovieGroup> = emptyList(), val tags: List<String> = emptyList(), val rating: String = "",
                     val cast: List<String> = emptyList(), val licence: String = "")

data class MovieSubtitle(val url: String, val lang: String = "", val label: String = "", val mime: String = "text/vtt")

data class MovieStream(val server: String, val link: String, val type: String = "mp4", val quality: String = "",
                       val subtitles: List<MovieSubtitle> = emptyList(), val headers: Map<String, String> = emptyMap(), val bytes: Long = 0)

data class MovieCatalog(val title: String, val filter: String)

/** What the Stage card / player gets: everything needed to play (and resume) one title. */
data class MoviePlay(val key: String, val title: String, val url: String, val image: String = "", val source: String = "",
                     val subtitles: List<MovieSubtitle> = emptyList(), val headers: Map<String, String> = emptyMap(), val postLink: String = "",
                     /** The source's own link for this video (re-resolved when [url] isn't kept, e.g. Jellyfin's signed URLs). */
                     val ref: String = "") {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("title", title).put("url", url).put("image", image).put("source", source)
        .put("post", postLink).put("ref", ref).put("subs", JSONArray(subtitles.map { MovieJson.sub(it) })).put("headers", JSONObject(headers as Map<*, *>))
    companion object {
        fun from(o: JSONObject?): MoviePlay? {
            o ?: return null
            val url = o.optString("url"); val ref = o.optString("ref")
            if (url.isBlank() && ref.isBlank()) return null
            return MoviePlay(o.optString("key").ifBlank { url.ifBlank { ref } }, o.optString("title").ifBlank { "Video" }, url, o.optString("image"), o.optString("source"),
                MovieJson.subs(o.optJSONArray("subs")), MovieJson.headers(o.optJSONObject("headers")), o.optString("post"), ref)
        }
        fun parse(s: String?): MoviePlay? = try { from(JSONObject(s ?: "")) } catch (_: Exception) { null }
    }
}

/** Lenient readers for what provider modules return (they are written by many hands). */
object MovieJson {
    private fun s(o: JSONObject, vararg k: String): String { for (x in k) { val v = o.opt(x); if (v != null && v != JSONObject.NULL) { val t = v.toString().trim(); if (t.isNotEmpty()) return t } }; return "" }

    fun posts(v: Any?, source: String): List<MoviePost> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            val t = s(o, "title"); val l = s(o, "link")
            if (t.isEmpty() || l.isEmpty()) null else MoviePost(t.take(200), l, s(o, "image"), source, s(o, "tag", "cornerTag").take(40))
        } }.distinctBy { it.link }.take(200)
    }

    fun info(o: JSONObject?): MovieInfo? {
        o ?: return null
        val t = s(o, "title"); if (t.isEmpty()) return null
        val ll = o.optJSONArray("linkList") ?: JSONArray()
        val groups = (0 until ll.length()).mapNotNull { i -> ll.optJSONObject(i)?.let { g ->
            val d = g.optJSONArray("directLinks")
            val direct = if (d == null) emptyList() else (0 until d.length()).mapNotNull { j -> d.optJSONObject(j)?.let { x ->
                val l = s(x, "link"); if (l.isEmpty()) null else MovieLink(s(x, "title").ifBlank { "Play" }, l, s(x, "type").ifBlank { "movie" }, s(x, "image")) } }
            val ep = s(g, "episodesLink")
            if (direct.isEmpty() && ep.isEmpty()) null else MovieGroup(s(g, "title").ifBlank { "Watch" }, s(g, "quality"), ep, direct)
        } }
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return MovieInfo(t.take(200), s(o, "synopsis").take(4000), s(o, "image", "poster"), s(o, "type").ifBlank { "movie" }, groups, list("tags").take(12),
            s(o, "rating"), list("cast").take(20))
    }

    fun episodes(v: Any?): List<MovieLink> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> val l = s(o, "link"); if (l.isEmpty()) null else MovieLink(s(o, "title").ifBlank { "Episode ${i + 1}" }, l, "series", s(o, "image")) } }
    }

    fun streams(v: Any?): List<MovieStream> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            val l = s(o, "link"); if (l.isEmpty() || !Regex("(?i)^https?://").containsMatchIn(l)) null
            else MovieStream(s(o, "server").ifBlank { "Server ${i + 1}" }, l, s(o, "type").ifBlank { guessType(l) }, s(o, "quality"), subs(o.optJSONArray("subtitles")), headers(o.optJSONObject("headers")))
        } }
    }

    fun catalog(v: Any?): List<MovieCatalog> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> val t = s(o, "title"); if (t.isEmpty()) null else MovieCatalog(t, s(o, "filter")) } }.take(30)
    }

    fun subs(a: JSONArray?): List<MovieSubtitle> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
        val u = s(o, "uri", "url"); if (u.isEmpty() || !Regex("(?i)^(https?|file|content)://").containsMatchIn(u)) null
        else MovieSubtitle(u, s(o, "language", "lang"), s(o, "title", "label"), s(o, "type", "mime").ifBlank { subMime(u) }.let { m -> if (m == "application/x-subrip" || m == "text/vtt" || m == "application/ttml+xml") m else subMime(u) })
    } }

    fun sub(t: MovieSubtitle): JSONObject = JSONObject().put("uri", t.url).put("language", t.lang).put("title", t.label).put("type", t.mime)

    fun headers(o: JSONObject?): Map<String, String> {
        o ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> val v = o.opt(k); if (v is String && k.isNotBlank() && k.length < 64 && v.length < 2000 && '\n' !in v && '\r' !in v) out[k] = v }
        return out
    }

    fun subMime(u: String): String = when (u.substringBefore('?').substringAfterLast('.').lowercase()) { "srt" -> "application/x-subrip"; "ttml", "dfxp", "xml" -> "application/ttml+xml"; else -> "text/vtt" }
    fun guessType(u: String): String = when {
        Regex("(?i)\\.m3u8(\\?|$)").containsMatchIn(u) -> "m3u8"
        Regex("(?i)\\.mpd(\\?|$)").containsMatchIn(u) -> "dash"
        Regex("(?i)\\.(mkv)(\\?|$)").containsMatchIn(u) -> "mkv"
        Regex("(?i)\\.(webm)(\\?|$)").containsMatchIn(u) -> "webm"
        Regex("(?i)\\.(ogv|ogg)(\\?|$)").containsMatchIn(u) -> "ogv"
        else -> "mp4"
    }

    /** Quality number for sorting ("1080p", "720", "HD" -> 720, "" -> 0). */
    fun qualityRank(q: String): Int = Regex("(\\d{3,4})").find(q)?.groupValues?.get(1)?.toIntOrNull()
        ?: when { q.contains("4k", true) || q.contains("uhd", true) -> 2160; q.contains("fhd", true) -> 1080; q.contains("hd", true) -> 720; q.contains("sd", true) -> 480; else -> 0 }
}
