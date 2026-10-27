package com.blaze.assistant.movies

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 0.46.2 Movies across sources: search with limited parallelism (failed / slow sources reported, never hidden), pick the best
 * title for a spoken request, resolve a playable stream. Pure (sources are passed in); MovieHubTest.
 */
object MovieHub {
    const val PARALLEL = 3
    const val SOURCE_TIMEOUT_MS = 40_000L   // 0.46.6: was 20 s; provider calls run one engine at a time (ProviderSandbox.Gate)

    data class SourceResult(val source: MovieSource, val posts: List<MoviePost>, val error: String? = null)
    data class Search(val results: List<SourceResult>) {
        val posts: List<MoviePost> get() = results.flatMap { it.posts }
        val failed: List<SourceResult> get() = results.filter { it.error != null }
    }

    suspend fun search(sources: List<MovieSource>, query: String, parallel: Int = PARALLEL, timeoutMs: Long = SOURCE_TIMEOUT_MS): Search = coroutineScope {
        val gate = Semaphore(parallel.coerceAtLeast(1))
        Search(sources.map { s -> async { gate.withPermit {
            try { withTimeoutOrNull(timeoutMs) { SourceResult(s, com.blaze.assistant.mediaapps.BrowseState.clean(s.search(query))) } ?: SourceResult(s, emptyList(), "took too long") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { SourceResult(s, emptyList(), (e.message ?: "error").take(160)) }   // 0.46.5: Errors too (engine OOM / stack)
        } } }.awaitAll())
    }

    /** Sources Blaze may use for a request: official ones, plus user-installed providers only when the user asked for them. */
    fun allowed(all: List<MovieSource>, allowProviders: Boolean, only: String = ""): List<MovieSource> {
        val pool = if (allowProviders) all else all.filter { it.official }
        if (only.isBlank()) return pool
        val o = only.trim().lowercase()
        return pool.filter { it.id.lowercase() == o || it.id.lowercase() == "p:$o" || it.name.lowercase().contains(o) }.ifEmpty { pool }
    }

    private fun words(s: String) = s.lowercase().replace(Regex("\\(.*?\\)"), " ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }.toSet()
    private val STOP = setOf("the", "a", "an", "of", "movie", "film", "and")

    /** Best title for "play <query>": most query words in the title, exact title first, shorter titles win ties. Null when nothing fits. */
    fun best(posts: List<MoviePost>, query: String): MoviePost? {
        val q = words(query); if (q.isEmpty()) return posts.firstOrNull()
        val exact = posts.firstOrNull { words(it.title) == q }
        if (exact != null) return exact
        return posts.map { it to (q intersect words(it.title)).size }.filter { it.second * 2 >= q.size && it.second > 0 }
            .maxWithOrNull(compareBy<Pair<MoviePost, Int>>({ it.second }, { -it.first.title.length }))?.first
    }

    val PLAYABLE = setOf("mp4", "m3u8", "hls", "dash", "mpd", "webm", "mkv", "ogv", "mov")

    /** Default stream: playable types, the best quality up to [maxHeight] (data saver: 480), else the smallest one. */
    fun pickStream(streams: List<MovieStream>, maxHeight: Int = 1080): MovieStream? {
        val ok = streams.filter { it.type.lowercase() in PLAYABLE || it.type.isBlank() }
        val under = ok.filter { MovieJson.qualityRank(it.quality).let { q -> q == 0 || q <= maxHeight } }
        return under.maxByOrNull { MovieJson.qualityRank(it.quality) } ?: ok.minByOrNull { MovieJson.qualityRank(it.quality) }
    }

    /** The first thing to play for a title: its first direct link, else the first episode of its first group. */
    suspend fun firstLink(src: MovieSource, info: MovieInfo): MovieLink? {
        info.groups.firstNotNullOfOrNull { it.direct.firstOrNull() }?.let { return it }
        val g = info.groups.firstOrNull { it.episodesLink.isNotBlank() } ?: return null
        return src.episodes(g.episodesLink).firstOrNull()
    }

    fun play(src: MovieSource, post: MoviePost, info: MovieInfo?, link: MovieLink, s: MovieStream, alternates: List<MovieStream> = emptyList()): MoviePlay {
        val title = info?.title?.ifBlank { null } ?: post.title
        val name = if (link.title.isNotBlank() && link.title != "Play" && link.type == "series") "$title \u00b7 ${link.title}" else title
        return MoviePlay(src.id + ":" + link.link, name, s.link, info?.image?.ifBlank { null } ?: post.image, src.id, s.subtitles, StreamPlan.headers(s.headers), post.link, link.link,
            s.type, alternates.filter { it.link != s.link })
    }

    /** Resolve a post all the way to something playable (details -> first link -> best stream). */
    suspend fun resolve(src: MovieSource, post: MoviePost, maxHeight: Int = 1080): MoviePlay {
        val info = src.info(post)
        val link = firstLink(src, info) ?: throw IllegalStateException("${info.title} has nothing to play")
        val all = StreamPlan.order(src.streams(link), maxHeight)
        val s = all.firstOrNull() ?: throw IllegalStateException("No playable stream for ${info.title}")
        return play(src, post, info, link, s, all.drop(1))
    }

    /** A saved play whose URL wasn't kept (token) gets a fresh one from its source. */
    suspend fun refresh(src: MovieSource, p: MoviePlay, maxHeight: Int = 1080): MoviePlay {
        if (p.url.isNotBlank()) return p
        val all = StreamPlan.order(src.streams(MovieLink(p.title, p.ref)), maxHeight)
        val s = all.firstOrNull() ?: throw IllegalStateException("No playable stream for ${p.title}")
        return p.copy(url = s.link, subtitles = s.subtitles, headers = StreamPlan.headers(s.headers), type = s.type, alternates = all.drop(1))
    }
}
