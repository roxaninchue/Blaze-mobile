package com.blaze.assistant.mediaapps

import com.blaze.assistant.movies.MovieInfo
import com.blaze.assistant.movies.MoviePost
import com.blaze.assistant.movies.Progress

/**
 * 0.46.4 Video revamp: the pure parts (bottom nav, Home row order and hero picks, details chips, player gesture rules).
 * Same design language as Music. Unit tested in VideoRevampTest. All Movies logic, sources, the provider manager and the
 * 0.46.2 legal rules are unchanged: rows only come from sources the user has (official ones, their videos, their Jellyfin,
 * providers they installed and switched on).
 */
object VideoNav {
    enum class Tab(val id: String, val label: String) { HOME("home", "Home"), SEARCH("search", "Search"), LIBRARY("library", "Library"), SOURCES("sources", "Sources") }
    val tabs: List<Tab> = Tab.values().toList()

    /** Old ids still arrive from intents and agent tools: "movies" is Home now; "store" (online videos) is part of Search. */
    fun normalize(id: String): String = when (id) {
        "", "movies", "home" -> "home"
        "search", "store", "library", "sources", "player" -> id
        else -> "home"
    }

    fun lit(tab: String, under: String): Tab = when (if (tab == "player") under else tab) {
        "search", "store" -> Tab.SEARCH
        "library" -> Tab.LIBRARY
        "sources" -> Tab.SOURCES
        else -> Tab.HOME
    }
}

object VideoHome {
    /** Built-in source ids in Home order; the user's Jellyfin and installed providers follow, in the order they were added. */
    private val builtIn = listOf("archive", "blender", "local")

    fun order(ids: List<String>): List<String> {
        val known = builtIn.filter { it in ids }
        val jelly = ids.filter { it == "jellyfin" || it.startsWith("jellyfin") }
        return known + jelly + ids.filter { it !in known && it !in jelly }
    }

    /** Short row titles: Free films (Internet Archive), Blender open movies, My videos; other sources keep their own titles. */
    fun rowTitle(sourceId: String, sourceName: String, row: String): String = when (sourceId) {
        "archive" -> "Free films"
        "blender" -> "Blender open movies"
        "local" -> "My videos"
        else -> if (row.isBlank() || row == sourceName) sourceName else "$row \u00b7 $sourceName"
    }

    data class Hero(val title: String, val image: String, val post: MoviePost?, val resume: Progress?)

    /**
     * The hero carousel: what you were watching first (Resume), then the first films with a picture from the official
     * rows (Free films, Blender), then anything else with a picture; no duplicates, at most [n].
     */
    fun hero(cont: List<Progress>, rows: Map<String, List<MoviePost>>, n: Int = 5): List<Hero> {
        val out = ArrayList<Hero>()
        val seen = HashSet<String>()
        cont.filter { it.play.image.isNotBlank() }.take(2).forEach { if (seen.add(it.play.key)) out += Hero(it.play.title, it.play.image, null, it) }
        val ordered = order(rows.keys.toList()).flatMap { rows[it].orEmpty() }
        for (p in ordered) {
            if (out.size >= n) break
            if (p.image.isBlank() || p.title.isBlank() || p.source == "local") continue
            if (seen.add(p.link)) out += Hero(p.title, p.image, p, null)
        }
        return out.take(n)
    }
}

object VideoDetailsLogic {
    private val year = Regex("""^(?:19|20)\d{2}$""")
    private val runtime = Regex("""(?i)^\d+\s*(?:h|hr|hours?|m|min|mins|minutes)(?:\s*\d+\s*(?:m|min|mins|minutes))?$""")

    /** The year / runtime / rating chips under the title (from the source's tags and rating), at most 4. */
    fun chips(info: MovieInfo): List<String> {
        val y = info.tags.firstOrNull { year.matches(it.trim()) }
        val r = info.tags.firstOrNull { runtime.matches(it.trim()) }
        return listOfNotNull(y, r, info.rating.takeIf { it.isNotBlank() }, info.licence.takeIf { it.isNotBlank() }).map { it.trim() }.distinct().take(4)
    }

    /** "More like this": other films from the same source's rows (never this one), at most [n]. */
    fun moreLikeThis(post: MoviePost, rows: List<MoviePost>, n: Int = 12): List<MoviePost> =
        rows.filter { it.link != post.link }.distinctBy { it.link }.take(n)

    /** Play or Resume on the big button. */
    fun playLabel(resumeMs: Long): String = if (resumeMs > 0) "Resume" else "Play"
}

object PlayerGestures {
    /** Pinch: past 8 % bigger fills the screen (crop), past 8 % smaller fits it again; in between keeps what it was. */
    fun fill(scale: Float, filled: Boolean): Boolean = when { scale > 1.08f -> true; scale < 0.92f -> false; else -> filled }

    /** Double-tap: left third back 10 s, right third forward 10 s, the middle toggles the controls (0). */
    fun doubleTapSeekMs(xFrac: Float): Long = when { xFrac < 0.4f -> -10_000L; xFrac > 0.6f -> 10_000L; else -> 0L }

    /** With the screen locked, only the unlock button works: no taps, drags, pinches or controls. */
    fun allowed(locked: Boolean, isUnlock: Boolean): Boolean = !locked || isUnlock
}

/**
 * 0.46.5 progressive browse / search: rows land as each source answers and stay (a refresh never clears or re-flashes what
 * loaded); sources that failed or timed out are hidden; untitled items are dropped. The only notes: "Couldn't reach <source>…"
 * when the user has exactly one source they added and it failed, or one empty state with Retry when nothing loaded at all.
 */
typealias BrowseRows = List<Pair<String, List<MoviePost>>>

object BrowseState {
    const val SOURCE_TIMEOUT_MS = 20_000L

    /** Untitled items out, duplicates out. */
    fun clean(posts: List<MoviePost>): List<MoviePost> = posts.filter { it.title.isNotBlank() }.distinctBy { it.source + "|" + it.link }
    fun cleanRows(rows: BrowseRows): BrowseRows = rows.map { (t, p) -> t to clean(p) }.filter { it.second.isNotEmpty() }

    /** A new answer replaces what's shown only when it has something; a failure / empty answer keeps the rows already there. */
    fun merge(prev: Result<BrowseRows>?, next: Result<BrowseRows>): Result<BrowseRows> {
        val n = next.getOrNull()?.let { cleanRows(it) }
        if (n != null && n.isNotEmpty()) return Result.success(n)
        val p = prev?.getOrNull()
        if (p != null && p.isNotEmpty()) return Result.success(p)
        return n?.let { Result.success(it) } ?: next
    }

    fun loaded(r: Result<BrowseRows>?): Boolean = r?.getOrNull()?.isNotEmpty() == true

    /** The one small line, or "" (see the object doc). [userAdded] = ids of sources the user added (providers). */
    fun note(names: Map<String, String>, userAdded: List<String>, results: Map<String, Result<BrowseRows>?>): String {
        if (userAdded.size != 1) return ""
        val id = userAdded[0]
        val r = results[id] ?: return ""
        return if (!loaded(r) && r.isFailure) "Couldn't reach ${names[id] ?: "your source"}. Check your connection." else ""
    }

    /** Every source has answered and nothing loaded (and there's nothing saved to show): the one empty state with Retry. */
    fun nothing(ids: List<String>, results: Map<String, Result<BrowseRows>?>, saved: Int): Boolean =
        saved == 0 && ids.isNotEmpty() && ids.all { results[it] != null } && ids.none { loaded(results[it]) }

    fun isUserAdded(id: String): Boolean = id.startsWith("p:")
}
