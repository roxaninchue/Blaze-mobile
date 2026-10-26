package com.blaze.assistant.movies

import android.content.Context
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.2 Movies on the Stage video card: a card can hold search results ("results" = posts JSON, "query", "failed"), a playing
 * movie ("movie" = MoviePlay JSON, "url" = its stream), and shows Continue watching when empty. Tapping a result / a continue
 * item resolves it here. [StageMoviesLogic] is the pure part (props codec), unit-tested.
 */
object StageMovies {
    /** Resolve "pick:<i>" (a search result) or "resume:<i>" (continue watching) into new card props. */
    suspend fun choose(ctx: Context, props: Map<String, String>, op: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val i = op.substringAfter(':').toIntOrNull() ?: 0
            val play = if (op.startsWith("pick:")) {
                val post = StageMoviesLogic.results(props["results"]).getOrNull(i) ?: throw IllegalStateException("That result is gone")
                val src = Movies.source(post.source) ?: throw IllegalStateException("That source isn't available any more")
                try { MovieHub.resolve(src, post, Movies.maxHeight(ctx)).also { Movies.note(src, null) } } catch (e: Exception) { Movies.note(src, e.message); throw e }
            } else {
                val p = Movies.lib.value.continueWatching.getOrNull(i)?.play ?: throw IllegalStateException("Nothing to continue")
                if (p.url.isNotBlank()) p else MovieHub.refresh(Movies.source(p.source) ?: throw IllegalStateException("That source isn't available any more"), p, Movies.maxHeight(ctx))
            }
            com.blaze.assistant.ui.StageVideo.autoplay = true
            StageMoviesLogic.playProps(play)
        } catch (e: Exception) { mapOf("error" to (e.message ?: "Couldn't play that").take(200)) }
    }

    /** Agent / route: play [p] on the Stage video card (shown in place, as the hero). */
    fun show(p: MoviePlay): String {
        com.blaze.assistant.ui.StageVideo.autoplay = true
        StageStore.dispatch(com.blaze.assistant.stage.StageAction.Show(StageKind.VIDEO, StageMoviesLogic.playProps(p), com.blaze.assistant.stage.StageMode.HERO))
        return "Playing \u201c${p.title}\u201d on the video card."
    }

    /** Agent / route: search results on the Stage video card (tap one to play it there). */
    fun showResults(query: String, s: MovieHub.Search) {
        val props = StageMoviesLogic.resultProps(query, s.posts, s.failed.map { it.source.name }) + mapOf("url" to "", "movie" to "", "title" to "Movies: $query".take(60))
        StageStore.dispatch(com.blaze.assistant.stage.StageAction.Show(StageKind.VIDEO, props, com.blaze.assistant.stage.StageMode.HERO))
    }
}

object StageMoviesLogic {
    const val MAX_RESULTS = 12
    fun playProps(p: MoviePlay): Map<String, String> = mapOf("url" to p.url, "movie" to p.toJson().toString(), "title" to p.title.take(80), "error" to "")
    fun resultProps(query: String, posts: List<MoviePost>, failed: List<String>): Map<String, String> = mapOf(
        "query" to query.take(80), "failed" to failed.joinToString(", ").take(200), "error" to "",
        "results" to JSONArray(posts.take(MAX_RESULTS).map { JSONObject().put("t", it.title).put("l", it.link).put("i", it.image).put("s", it.source).put("g", it.tag) }).toString())
    fun results(json: String?): List<MoviePost> = try { JSONArray(json ?: "[]").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
        o.optString("l").takeIf { it.isNotBlank() }?.let { MoviePost(o.optString("t", "Video"), it, o.optString("i"), o.optString("s"), o.optString("g")) } } } } catch (_: Exception) { emptyList() }
}
