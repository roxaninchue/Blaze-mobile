package com.blaze.assistant.mediaapps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * 0.46.4 Music + Video revamp: the pure parts of the new Music layout (bottom nav, Home tiles and mixes, playlist cover
 * mosaic, cover tint with fallback, now-playing open / close and swipe rules, Library sort, local lyrics lookup).
 * Unit tested in MusicRevampTest; the Compose side lives in MusicShell.kt.
 */
object MusicNav {
    /** The bottom nav, always visible in Music (the full player covers it). Labels are allowed on the nav (IconOnlyTest). */
    enum class Tab(val id: String, val label: String) { HOME("home", "Home"), SEARCH("search", "Search"), LIBRARY("library", "Library"), CREATE("create", "Create") }
    val tabs: List<Tab> = Tab.values().toList()

    /** Old tab ids still arrive from intents and agent tools: "store" opens Search (the online catalogue lives there). */
    fun normalize(id: String): String = when (id) {
        "", "foryou", "home" -> "home"
        "search", "store" -> id
        "library", "create", "player" -> id
        else -> "home"
    }

    /** Which nav item is lit for a page ("store" is part of Search; the player keeps the page under it lit). */
    fun lit(tab: String, under: String): Tab = when (if (tab == "player") under else tab) {
        "search", "store" -> Tab.SEARCH
        "library" -> Tab.LIBRARY
        "create" -> Tab.CREATE
        else -> Tab.HOME
    }

    fun showNav(tab: String): Boolean = tab != "player"
    fun showMini(tab: String, hasItem: Boolean): Boolean = hasItem && tab != "player"

    /** The close (X) in the top bar: always the Blaze chat, from every page (standing rule), never the previous page. */
    const val CLOSE_TARGET = "chat"
}

/** Where the current queue came from ("Playing from …" in the full player). Set by every play-a-list action. */
object MusicContext {
    val from = MutableStateFlow("")
    fun set(name: String) { from.value = name.trim().take(60) }
    fun header(name: String): String = if (name.isBlank()) "Now playing" else "Playing from $name"
}

object MusicHome {
    val chips: List<Pair<String, String>> = listOf("all" to "All", "songs" to "Songs", "albums" to "Albums", "artists" to "Artists", "playlists" to "Playlists")

    /** One Home / Library tile: a playlist, a smart list, a mix, an album, an artist or a single song. */
    data class Tile(val id: String, val name: String, val keys: List<String>, val kind: String, val sub: String = "")

    private fun albums(songs: List<Track>): List<Tile> = songs.filter { it.album.isNotBlank() }.groupBy { it.album.lowercase() }
        .map { (_, l) -> Tile("album:" + l.first().album.lowercase(), l.first().album, l.map { it.key }, "album", l.first().artist) }
        .sortedByDescending { t -> songs.filter { it.key in t.keys }.maxOfOrNull { it.addedAt } ?: 0L }

    private fun artists(songs: List<Track>): List<Tile> = songs.filter { it.artist.isNotBlank() }.groupBy { it.artist.lowercase() }
        .map { (_, l) -> Tile("artist:" + l.first().artist.lowercase(), l.first().artist, l.map { it.key }, "artist") }
        .sortedByDescending { it.keys.size }

    /** The 2-column grid on Home: 8 tiles for the chip, most recently used first. */
    fun tiles(chip: String, songs: List<Track>, playlists: List<Playlist>, smart: List<MusicSmart.SmartList>, recent: List<String>, n: Int = 8): List<Tile> {
        val have = songs.associateBy { it.key }
        val lists = playlists.sortedByDescending { it.updated }.map { Tile(it.id, it.name, it.keys.filter { k -> k in have }, "playlist") }
        val smartTiles = smart.map { Tile(it.id, it.name, it.keys, "smart") }
        val out = when (chip) {
            "songs" -> (recent.mapNotNull { have[it] } + songs.sortedByDescending { it.addedAt }).distinctBy { it.key }
                .map { Tile("song:" + it.key, it.title, listOf(it.key), "song", it.artist) }
            "albums" -> albums(songs)
            "artists" -> artists(songs)
            "playlists" -> lists
            else -> (lists + smartTiles + albums(songs).take(4)).distinctBy { it.id }
        }
        return out.filter { it.keys.isNotEmpty() || it.kind == "playlist" }.take(n)
    }

    /**
     * "Your mixes": the daily mixes (by your most played artists) and, for a library without much play history, one mix per
     * artist with 3+ songs; then a time-of-day mix from [DjPlan]. Never more than [n].
     */
    fun mixes(songs: List<Track>, events: List<PlayEvent>, day: Long, hour: Int, n: Int = 8): List<Tile> {
        val out = ArrayList<Tile>()
        MusicSmart.dailyMixes(songs, events, day).forEach { out += Tile(it.id, it.name, it.keys, "mix", it.about) }
        if (out.size < 3) artists(songs).filter { it.keys.size >= 3 }.take(6 - out.size).forEach {
            out += Tile("mix:artist:" + it.name.lowercase(), it.name + " mix", it.keys, "mix", it.name) }
        val vibe = DjPlan.vibeFor(hour)
        val mood = DjPlan.sequence(songs, events, emptySet(), emptyList(), vibe, hour, 25, day)
        if (mood.size >= 3) out += Tile("mix:mood:" + vibe.id, vibe.label + " mix", mood.map { it.key }, "mix", "For " + DjPlan.partOfDay(hour))
        return out.distinctBy { it.id }.take(n)
    }

    /** "Recently played": the last songs played, newest first, once each. */
    fun recentlyPlayed(songs: List<Track>, recent: List<String>, n: Int = 12): List<Track> {
        val have = songs.associateBy { it.key }
        return recent.distinct().mapNotNull { have[it] }.take(n)
    }
}

object CoverMosaic {
    /**
     * The tracks whose art makes a playlist cover: the first 4 with different covers (a 2x2 mosaic); with fewer than 4
     * different covers, just the first one (a single cover); none = the themed placeholder.
     */
    fun pick(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (t in tracks) {
            val id = t.art.ifBlank { t.album.ifBlank { t.key } }.lowercase()
            if (seen.add(id)) out += t
            if (out.size == 4) break
        }
        return if (out.size >= 4) out else out.take(1)
    }
    fun isMosaic(tracks: List<Track>): Boolean = pick(tracks).size == 4
}

object CoverTint {
    /**
     * The header colour for a cover: its dominant colour ([ArtColors.dominant]), or [fallback] (the theme's accent) when
     * there's no picture, it failed to load, or it's fully transparent. Returned as 0xRRGGBB.
     */
    fun of(pixels: IntArray?, fallback: Int): Int {
        if (pixels == null || pixels.isEmpty() || pixels.none { (it ushr 24) >= 128 }) return fallback and 0xFFFFFF
        return ArtColors.dominant(pixels)
    }
}

object PlaylistMeta {
    /** "1 h 12 min", "34 min", "under a minute"; blank when no song knows its length. */
    fun total(tracks: List<Track>): String {
        val ms = tracks.sumOf { it.durationMs.coerceAtLeast(0) }
        if (ms <= 0) return ""
        val min = ms / 60_000
        return when { min >= 60 -> "${min / 60} h ${min % 60} min"; min >= 1 -> "$min min"; else -> "under a minute" }
    }

    enum class Sort(val id: String, val label: String) { CUSTOM("custom", "Custom order"), TITLE("title", "Title"), ARTIST("artist", "Artist"), ADDED("added", "Recently added") }

    fun sort(tracks: List<Track>, s: Sort): List<Track> = when (s) {
        Sort.CUSTOM -> tracks
        Sort.TITLE -> tracks.sortedBy { it.title.lowercase() }
        Sort.ARTIST -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
        Sort.ADDED -> tracks.sortedByDescending { it.addedAt }
    }

    /** A custom playlist picture is cropped to the centred square (left, top, side) of a [w] x [h] picture. */
    fun cropSquare(w: Int, h: Int): Triple<Int, Int, Int> { val s = minOf(w, h).coerceAtLeast(0); return Triple((w - s) / 2, (h - s) / 2, s) }
}

object NowPlayingGestures {
    /** Swipe the player down past [limitPx] (or fling faster than 1800 px/s) to close it into the mini-player. */
    fun dismiss(dragPx: Float, velocity: Float, limitPx: Float): Boolean = dragPx > limitPx || velocity > 1800f

    /** Swipe the art sideways: past a quarter of its width, left = next (+1), right = previous (-1); else stay (0). */
    fun skip(dxPx: Float, widthPx: Float): Int = if (widthPx <= 0f || abs(dxPx) < widthPx / 4f) 0 else if (dxPx < 0) 1 else -1
}

object LibraryView {
    val chips: List<Pair<String, String>> = listOf("playlists" to "Playlists", "artists" to "Artists", "albums" to "Albums", "songs" to "Songs", "downloaded" to "Downloaded")
    /** Downloaded = songs saved on the phone by Blaze (Store downloads), not ones it only streams or found in the gallery. */
    fun downloaded(songs: List<Track>): List<Track> = songs.filter { it.source == "download" }
}

object LocalLyrics {
    /** The .lrc that sits next to a song file (same name), if the song is a plain file path or file:// uri. */
    fun lrcPathFor(uri: String): String? {
        val path = when { uri.startsWith("file://") -> uri.removePrefix("file://"); uri.startsWith("/") -> uri; else -> return null }
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return (if (dot > slash) path.substring(0, dot) else path) + ".lrc"
    }
}
