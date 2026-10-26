package com.blaze.assistant.movies

/**
 * 0.46.5 "I cannot actually watch": the generic stream flow for any source. A provider's stream / extract function returns
 * several streams (each with its own headers / referer); Blaze tries them in order with a short start timeout and moves to
 * the next on an error, and only says "This source didn't give a playable video…" when none plays. Pure (StreamPlanTest).
 */
object StreamPlan {
    const val START_TIMEOUT_MS = 15_000L
    const val FAIL_TEXT = "This source didn't give a playable video. Try another quality or source."

    enum class Kind { HLS, DASH, PROGRESSIVE }

    /** HLS / DASH / a plain file, from the type the provider gave, else from the URL. */
    fun kind(type: String, url: String): Kind {
        val t = type.lowercase().trim()
        val u = url.lowercase().substringBefore('?').substringBefore('#')
        // The URL's own evidence wins over a guessed type (sources often say "mp4" for everything).
        return when {
            u.endsWith(".m3u8") || u.contains(".m3u8/") -> Kind.HLS
            u.endsWith(".mpd") -> Kind.DASH
            t in setOf("m3u8", "hls", "application/x-mpegurl", "application/vnd.apple.mpegurl") -> Kind.HLS
            t in setOf("dash", "mpd", "application/dash+xml") -> Kind.DASH
            u.contains("/hls/") -> Kind.HLS
            else -> Kind.PROGRESSIVE
        }
    }

    /** The stream's headers as the player sends them: names normalised (Referer, Origin, User-Agent, Cookie kept), empty ones dropped. */
    fun headers(h: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in h) {
            val key = k.trim(); val value = v.trim()
            if (key.isEmpty() || value.isEmpty() || '\n' in value || '\r' in value) continue
            val norm = when (key.lowercase()) { "referer", "referrer" -> "Referer"; "origin" -> "Origin"; "user-agent" -> "User-Agent"; "cookie" -> "Cookie"; else -> key }
            out[norm] = value
        }
        return out
    }

    /**
     * The order to try streams in: playable ones only (http(s), a known or blank type), no duplicates; the remembered quality
     * first, then the best at or under [maxHeight], then the rest best-first, then streams with no quality.
     */
    fun order(streams: List<MovieStream>, maxHeight: Int = 1080, prefHeight: Int = 0): List<MovieStream> {
        val ok = streams.filter { s ->
            Regex("(?i)^https?://").containsMatchIn(s.link) && (s.type.isBlank() || s.type.lowercase() in MovieHub.PLAYABLE || kind(s.type, s.link) != Kind.PROGRESSIVE)
        }.distinctBy { it.link }
        fun h(s: MovieStream) = MovieJson.qualityRank(s.quality)
        val pref = if (prefHeight > 0) ok.filter { h(it) == prefHeight } else emptyList()
        val under = ok.filter { h(it) in 1..maxHeight && it !in pref }.sortedByDescending { h(it) }
        val over = ok.filter { h(it) > maxHeight && it !in pref }.sortedBy { h(it) }
        val none = ok.filter { h(it) == 0 && it !in pref }
        return pref + under + none + over
    }

    /** The next stream to try after [failed] (index into [order]); null = none left. */
    fun next(failed: Int, size: Int): Int? = (failed + 1).takeIf { it < size }

    /** A log line for a failed attempt: no URLs, no headers, no tokens. */
    fun logLine(source: String, index: Int, size: Int, kind: Kind, reason: String): String =
        "movie stream ${index + 1}/$size from ${source.take(40)} (${kind.name.lowercase()}) failed: ${reason.replace(Regex("""https?://\S+"""), "<url>").take(120)}"
}
