package com.blaze.assistant.movies

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.2 agent tool `movies` (and the in-app route for "play the movie …", "search movies …", "continue watching"): everything
 * lands on the Stage video card (shown in place; its open-in-full-view button goes to Blaze Video). Official sources only by
 * default: Internet Archive public-domain films, Blender open movies, the user's own videos, their Jellyfin server. Providers
 * the user installed are used only when the user asked for one (named it, or said "my providers").
 */
object MovieTools {
    const val TOOL = "movies"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Movies in Blaze Video, shown on the Stage video card. Sources: free public-domain films (Internet Archive), Blender open movies, " +
            "the user's own videos, and their Jellyfin server. action: play (query = the title), search (query), continue (resume the last unfinished one), " +
            "watchlist (list it). Sources the user added in Providers: only when the user asked for one by name or said to use their sources (use_providers=true, source=its name).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("play", "search", "continue", "watchlist"))))
            .put("query", JSONObject().put("type", "string"))
            .put("source", JSONObject().put("type", "string").put("description", "optional: archive | blender | local | jellyfin | a provider the user named"))
            .put("use_providers", JSONObject().put("type", "boolean")))
            .put("required", JSONArray(listOf("action")))))

    fun describe(a: JSONObject): String = when (a.optString("action")) { "play" -> "Finding the movie"; "search" -> "Searching movies"; "continue" -> "Picking up where you left off"; else -> "Movies" }

    /** Did the user ask for their added sources? ("use my providers", "on <provider name>"). Pure; MovieToolsTest. */
    fun userAllows(text: String, installedNames: List<String>): Boolean {
        val t = text.lowercase()
        if (Regex("""\b(my|added|installed|third[- ]party|custom) (providers?|sources?|repos?)\b|\bproviders?\b""").containsMatchIn(t)) return true
        return installedNames.any { n -> n.length >= 3 && Regex("\\b" + Regex.escape(n.lowercase()) + "\\b").containsMatchIn(t) }
    }

    suspend fun run(ctx: Context, a: JSONObject, userText: String = ""): String = withContext(Dispatchers.IO) {
        Movies.init(ctx)
        val installed = runCatching { Movies.providers().state.installed.map { it.entry.name } }.getOrDefault(emptyList())
        // Added sources only when the user's own words asked for them (named one, or "my providers / sources").
        val asked = userText.isNotBlank() && userAllows(userText, installed)
        val allow = asked && (a.optBoolean("use_providers") || installed.any { it.equals(a.optString("source").trim(), true) })
        val pool = MovieHub.allowed(Movies.sources(), allow, a.optString("source"))
        when (a.optString("action")) {
            "play" -> play(ctx, pool, a.optString("query"))
            "search" -> search(pool, a.optString("query"))
            "continue" -> resume(ctx)
            "watchlist" -> Movies.lib.value.watchlist.let { w -> if (w.isEmpty()) "The watchlist is empty." else "Watchlist: " + w.take(15).joinToString("; ") { it.title } }
            else -> "Unknown action."
        }
    }

    suspend fun play(ctx: Context, pool: List<MovieSource>, query: String): String {
        if (query.isBlank()) return "Which movie?"
        val s = MovieHub.search(pool, query)
        s.results.forEach { Movies.note(it.source, it.error) }
        val pick = MovieHub.best(s.posts, query)
        if (pick == null) {
            if (s.posts.isNotEmpty()) { withContext(Dispatchers.Main) { StageMovies.showResults(query, s) }; return "No exact match for \u201c$query\u201d; the closest results are on the video card." + failed(s) }
            return "Couldn't find \u201c$query\u201d in the free film sources" + (if (pool.any { it.id == "jellyfin" }) ", Jellyfin" else "") + "." + failed(s)
        }
        val src = pool.first { it.id == pick.source }
        val play = try { MovieHub.resolve(src, pick, Movies.maxHeight(ctx)).also { Movies.note(src, null) } }
            catch (e: Exception) { Movies.note(src, e.message); return "Found \u201c${pick.title}\u201d but couldn't play it: ${e.message}" }
        return withContext(Dispatchers.Main) { show(ctx, play) } + " Source: ${src.name}."
    }

    suspend fun search(pool: List<MovieSource>, query: String): String {
        if (query.isBlank()) return "What should I search for?"
        val s = MovieHub.search(pool, query)
        s.results.forEach { Movies.note(it.source, it.error) }
        withContext(Dispatchers.Main) { StageMovies.showResults(query, s) }
        if (s.posts.isEmpty()) return "Nothing for \u201c$query\u201d." + failed(s)
        return "On the video card: " + s.posts.take(8).joinToString("; ") { it.title + (if (it.tag.isNotBlank()) " (${it.tag})" else "") } + "." + failed(s)
    }

    suspend fun resume(ctx: Context): String {
        val pr = Movies.lib.value.continueWatching.firstOrNull() ?: return "Nothing to continue: no unfinished movie."
        val src = Movies.source(pr.play.source)
        val p = try { if (pr.play.url.isNotBlank()) pr.play else MovieHub.refresh(src ?: return "That movie's source isn't available any more.", pr.play, Movies.maxHeight(ctx)) }
            catch (e: Exception) { return "Couldn't continue \u201c${pr.play.title}\u201d: ${e.message}" }
        return withContext(Dispatchers.Main) { show(ctx, p) } + " From " + com.blaze.assistant.mediaapps.TimeText.clock(pr.posMs) + "."
    }

    private fun show(ctx: Context, p: MoviePlay): String =
        if (com.blaze.assistant.stage.StageStore.enabled) StageMovies.show(p)
        else { com.blaze.assistant.mediaapps.VideoActivity.openMovie(ctx, p); "Playing \u201c${p.title}\u201d in Blaze Video." }

    private fun failed(s: MovieHub.Search) = if (s.failed.isEmpty()) "" else " (Didn't answer: " + s.failed.joinToString(", ") { it.source.name } + ".)"

    /** The in-app route ("play the movie Nosferatu", "search movies about trains", "continue watching"). */
    suspend fun route(ctx: Context, action: String, query: String, text: String): String =
        run(ctx, JSONObject().put("action", action).put("query", query).put("use_providers", true), text)
}
