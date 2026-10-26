package com.blaze.assistant.movies

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.2 watchlist + continue watching + resume positions for Movies (any source). Pure state + codec; the phone keeps it in
 * Settings via [MovieLibrary.Store]. Signed / tokened URLs (Jellyfin) are never saved: the source re-resolves them from [MoviePlay.ref].
 */
data class Progress(val play: MoviePlay, val posMs: Long, val durMs: Long, val updated: Long) {
    val fraction: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
}

data class MovieLib(val watchlist: List<MoviePost> = emptyList(), val progress: List<Progress> = emptyList()) {
    /** Unfinished, newest first (started > 30 s and not within the last 3 % / 2 min). */
    val continueWatching: List<Progress> get() = progress.filter { MovieLibrary.unfinished(it.posMs, it.durMs) }.sortedByDescending { it.updated }
    fun resumeAt(key: String): Long = progress.firstOrNull { it.play.key == key }?.takeIf { MovieLibrary.unfinished(it.posMs, it.durMs) }?.posMs ?: 0L
    fun inWatchlist(link: String, source: String) = watchlist.any { it.link == link && it.source == source }
}

object MovieLibrary {
    const val KEY = "movies_library_v1"
    const val MAX_PROGRESS = 60
    const val MAX_WATCHLIST = 200

    fun unfinished(pos: Long, dur: Long): Boolean = pos >= 30_000 && (dur <= 0 || (pos < dur - maxOf(dur * 3 / 100, 120_000L).coerceAtMost(dur / 2)))

    /** The play as saved: no URL / headers for sources whose URLs carry a token. */
    fun storable(p: MoviePlay): MoviePlay = if (p.source == "jellyfin" || p.url.contains("api_key=") || p.url.contains("token=", true))
        p.copy(url = "", headers = emptyMap(), subtitles = emptyList()) else p

    fun saveProgress(l: MovieLib, p: MoviePlay, pos: Long, dur: Long, now: Long = System.currentTimeMillis()): MovieLib {
        val rest = l.progress.filter { it.play.key != p.key }
        return l.copy(progress = (listOf(Progress(storable(p), pos.coerceAtLeast(0), dur.coerceAtLeast(0), now)) + rest).take(MAX_PROGRESS))
    }
    fun forget(l: MovieLib, key: String) = l.copy(progress = l.progress.filter { it.play.key != key })
    fun toggleWatchlist(l: MovieLib, p: MoviePost): MovieLib =
        if (l.inWatchlist(p.link, p.source)) l.copy(watchlist = l.watchlist.filterNot { it.link == p.link && it.source == p.source })
        else l.copy(watchlist = (listOf(p) + l.watchlist).take(MAX_WATCHLIST))

    private fun post(p: MoviePost) = JSONObject().put("t", p.title).put("l", p.link).put("i", p.image).put("s", p.source).put("g", p.tag)
    fun encode(l: MovieLib): String = JSONObject().put("v", 1)
        .put("watch", JSONArray(l.watchlist.map { post(it) }))
        .put("progress", JSONArray(l.progress.map { JSONObject().put("play", it.play.toJson()).put("pos", it.posMs).put("dur", it.durMs).put("at", it.updated) })).toString()
    fun decode(s: String?): MovieLib = try {
        val o = JSONObject(s ?: "")
        val w = o.optJSONArray("watch")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { j ->
            val l = j.optString("l"); if (l.isBlank()) null else MoviePost(j.optString("t", "Video"), l, j.optString("i"), j.optString("s"), j.optString("g")) } }.orEmpty()
        val p = o.optJSONArray("progress")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { j ->
            MoviePlay.from(j.optJSONObject("play"))?.let { Progress(it, j.optLong("pos"), j.optLong("dur"), j.optLong("at")) } } }.orEmpty()
        MovieLib(w.take(MAX_WATCHLIST), p.take(MAX_PROGRESS))
    } catch (_: Exception) { MovieLib() }
}
