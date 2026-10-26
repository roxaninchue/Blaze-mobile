package com.blaze.assistant.movies

import android.content.Context
import com.blaze.assistant.agent.Secrets
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.mediaapps.MediaLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 0.46.2 Movies on the phone: the sources (official built-ins, the user's own videos, their Jellyfin server, providers they
 * installed), the provider store, and the watchlist / continue-watching library. Everything heavy runs on Dispatchers.IO.
 */
object Movies {
    @Volatile private var app: Context? = null
    @Volatile private var store: ProviderStore? = null
    private val _lib = MutableStateFlow(MovieLib())
    val lib: StateFlow<MovieLib> = _lib
    /** Bumped when sources / providers change (the UI re-reads). */
    val gen = MutableStateFlow(0)

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        _lib.value = MovieLibrary.decode(runCatching { Settings.str(MovieLibrary.KEY, "") }.getOrDefault(""))
    }

    fun providers(ctx: Context? = app): ProviderStore = store ?: synchronized(this) {
        store ?: ProviderStore(File((ctx ?: app)!!.filesDir, "movie_providers"), object : ProviderStore.Kv {
            override fun get(key: String) = runCatching { Settings.str(key, "") }.getOrDefault("")
            override fun put(key: String, value: String) { runCatching { Settings.setStr(key, value) } }
        }).also { st ->
            // 0.46.2 Blaze Official (Blaze's own example repo) comes preinstalled from the copy bundled in the app.
            val c = (ctx ?: app)!!
            runCatching { st.seedOfficial { path -> runCatching { c.assets.open("movies/blaze-official/$path").bufferedReader().use { it.readText() } }.getOrNull() } }
            store = st
        }
    }

    // ---------------- library ----------------
    private fun saveLib(l: MovieLib) { _lib.value = l; runCatching { Settings.setStr(MovieLibrary.KEY, MovieLibrary.encode(l)) } }
    fun progress(p: MoviePlay, pos: Long, dur: Long) { if (pos > 0) saveLib(MovieLibrary.saveProgress(_lib.value, p, pos, dur)) }
    fun forget(key: String) = saveLib(MovieLibrary.forget(_lib.value, key))
    fun toggleWatchlist(p: MoviePost) = saveLib(MovieLibrary.toggleWatchlist(_lib.value, p))

    // ---------------- Jellyfin account ----------------
    private const val JF = "movies_jellyfin_v1"; private const val JF_TOKEN = "conn_tok_jellyfin"
    data class JellyfinAccount(val server: String, val user: String, val userId: String, val name: String)
    fun jellyfinAccount(): JellyfinAccount? = try {
        val o = JSONObject(Settings.str(JF, "")); JellyfinAccount(o.getString("server"), o.optString("user"), o.getString("uid"), o.optString("name", "Jellyfin"))
    } catch (_: Exception) { null }

    /** Sign in to a Jellyfin server (password used once, never stored; the access token is kept encrypted). */
    suspend fun addJellyfin(serverIn: String, user: String, password: String): String = withContext(Dispatchers.IO) {
        val server = Jellyfin.normalize(serverIn) ?: throw IllegalArgumentException("That server address doesn't look right")
        val info = runCatching { JSONObject(MovieHttp.get("$server/System/Info/Public")) }.getOrNull()
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        val (token, uid) = Jellyfin.parseAuth(MovieHttp.post("$server/Users/AuthenticateByName", mapOf("X-Emby-Authorization" to Jellyfin.authHeader()), body))
        val name = info?.optString("ServerName").orEmpty().ifBlank { "Jellyfin" }
        Settings.setStr(JF_TOKEN, Secrets.encrypt(token))
        Settings.setStr(JF, JSONObject().put("server", server).put("user", user).put("uid", uid).put("name", name).toString())
        gen.value++
        name
    }
    fun removeJellyfin() { runCatching { Settings.setStr(JF, ""); Settings.setStr(JF_TOKEN, "") }; gen.value++ }
    private fun jellyfin(): JellyfinSource? = jellyfinAccount()?.let { a ->
        val tok = Secrets.decrypt(Settings.str(JF_TOKEN, "")); if (tok.isBlank()) null else JellyfinSource(a.server, a.userId, tok, a.name) }

    // ---------------- sources ----------------
    /** Built-in official sources + the user's Jellyfin + (when [withProviders]) the user's enabled providers. */
    fun sources(withProviders: Boolean = true): List<MovieSource> {
        val out = ArrayList<MovieSource>()
        out += ArchiveFeatureFilms; out += BlenderOpenMovies; out += LocalVideos
        jellyfin()?.let { out += it }
        if (withProviders) runCatching { out += providers().sources() }
        return out
    }
    fun source(id: String): MovieSource? = sources().firstOrNull { it.id == id }

    /** Max picture height: 480 on data saver (mobile data), else 1080. */
    fun maxHeight(ctx: Context): Int = try { if (com.blaze.assistant.mediaapps.MediaPrefs.saving(ctx)) 480 else 1080 } catch (_: Exception) { 1080 }

    /** Report a provider call's outcome to the Providers list (Working / Failing). */
    fun note(src: MovieSource, error: String?) {
        if (src !is JsProviderSource) return
        runCatching { if (error == null) providers().noteWorking(src.entry.value) else providers().noteFailure(src.entry.value, error) }
    }
}

/** The user's own videos (Blaze's library: downloads + gallery videos, and files opened from other apps). */
object LocalVideos : MovieSource {
    override val id = "local"
    override val name = "Your videos"
    override val official = true
    private fun all(): List<MoviePost> = MediaLib.videos.value.map { MoviePost(it.title, "local:" + it.key, it.art, id, "") }
    override suspend fun rows() = listOf("Your videos" to withContext(Dispatchers.IO) { app()?.let { MediaLib.ensure(it) }; all().take(30) })
    override suspend fun search(query: String): List<MoviePost> {
        val w = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        return all().filter { p -> w.isEmpty() || w.any { p.title.lowercase().contains(it) } }.take(40)
    }
    override suspend fun info(post: MoviePost) = MovieInfo(post.title, "", post.image, "movie", listOf(MovieGroup("Watch", direct = listOf(MovieLink("Play", post.link)))))
    override suspend fun streams(link: MovieLink): List<MovieStream> {
        val t = MediaLib.find(link.link.removePrefix("local:")) ?: return emptyList()
        return listOf(MovieStream("This phone", t.uri, MovieJson.guessType(t.uri), ""))
    }
    private fun app(): Context? = com.blaze.assistant.stage.StageStore.app
}
