package com.blaze.assistant.movies

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 0.46.2 the user's added source repos and installed providers. Starts EMPTY: Blaze bundles, pre-fills and suggests no repo.
 * State (one JSON blob via [kv]) + module files under [dir]/<repo-hash>/<provider>/<file>.js. Network via [fetch] (guarded by
 * [UrlGuard]). Pure JVM (no Android): ProviderStoreTest runs it against the local mock repo.
 */
class ProviderStore(private val dir: File, private val kv: Kv, private val fetch: (String) -> String = { url -> defaultFetch(url) }) {
    interface Kv { fun get(key: String): String; fun put(key: String, value: String) }

    enum class Status(val label: String) { NOT_TESTED("Not tested"), WORKING("Working"), FAILING("Failing") }

    data class Repo(val base: String, val label: String, val added: Long)
    data class Installed(val entry: ProviderRepo.Entry, val repo: String, val status: Status = Status.NOT_TESTED, val detail: String = "",
                         val enabled: Boolean = true, val hangs: Int = 0, val latest: String = "", val settings: Map<String, String> = emptyMap()) {
        val value get() = entry.value
        val updateAvailable get() = latest.isNotBlank() && ProviderRepo.newer(entry.version, latest)
    }
    data class State(val repos: List<Repo> = emptyList(), val installed: List<Installed> = emptyList(), val active: String = "", val noticeSeen: Boolean = false,
                     /** Blaze Official was preinstalled once (removing it later keeps it removed). */
                     val seeded: Boolean = false)

    companion object {
        const val KEY = "movies_providers_v1"
        const val MAX_MODULE = 3 * 1024 * 1024
        fun defaultFetch(url: String): String {
            UrlGuard.check(url)?.let { throw IllegalArgumentException(it) }
            val r = DefaultSandboxHttp.request(SandboxHttp.Req(url, headers = mapOf("Cache-Control" to "no-cache")))
            if (r.status !in 200..299) throw IllegalStateException("The source answered ${r.status}")
            return r.body
        }
        fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        fun encode(s: State): String = JSONObject().put("v", 1).put("active", s.active).put("notice", s.noticeSeen).put("seeded", s.seeded)
            .put("repos", JSONArray(s.repos.map { JSONObject().put("base", it.base).put("label", it.label).put("added", it.added) }))
            .put("installed", JSONArray(s.installed.map { i -> JSONObject().put("value", i.entry.value).put("name", i.entry.name).put("version", i.entry.version)
                .put("icon", i.entry.icon).put("type", i.entry.type).put("repo", i.repo).put("status", i.status.name).put("detail", i.detail).put("enabled", i.enabled)
                .put("hangs", i.hangs).put("latest", i.latest).put("settings", JSONObject(i.settings as Map<*, *>)) })).toString()

        fun decode(json: String?): State = try {
            val o = JSONObject(json ?: "")
            val repos = o.optJSONArray("repos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                r.optString("base").takeIf { it.isNotBlank() }?.let { Repo(it, r.optString("label", ProviderRepo.label(it)), r.optLong("added")) } } }.orEmpty()
            val inst = o.optJSONArray("installed")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { i ->
                val v = i.optString("value"); if (!ProviderRepo.SAFE_ID.matches(v)) return@mapNotNull null
                val st = runCatching { Status.valueOf(i.optString("status")) }.getOrDefault(Status.NOT_TESTED)
                val set = LinkedHashMap<String, String>(); i.optJSONObject("settings")?.let { so -> so.keys().forEach { k -> set[k] = so.optString(k) } }
                Installed(ProviderRepo.Entry(v, i.optString("name", v), i.optString("version", "0"), i.optString("icon"), i.optString("type", "global")),
                    i.optString("repo"), st, i.optString("detail"), i.optBoolean("enabled", true), i.optInt("hangs"), i.optString("latest"), set)
            } }.orEmpty()
            State(repos, inst, o.optString("active"), o.optBoolean("notice"), o.optBoolean("seeded"))
        } catch (_: Exception) { State() }
    }

    @Volatile var state: State = decode(kv.get(KEY)); private set
    private fun save(s: State) { state = s; kv.put(KEY, encode(s)) }
    /** Per-provider kvStore contents live in memory for the app session. */
    private val kvs = HashMap<String, MutableMap<String, String>>()

    fun markNoticeSeen() = save(state.copy(noticeSeen = true))

    /**
     * Find a repo's manifest from what the user typed or a link carried: "owner/repo", a GitHub / GitLab page, a raw folder or
     * the manifest itself; tries the repo root, dist/, and the main / master branches. Doesn't save anything.
     */
    fun resolve(input: String): Pair<String, List<ProviderRepo.Entry>> {
        val (cands, why) = ProviderRepo.candidates(input)
        if (cands.isEmpty()) throw IllegalArgumentException(why)
        var last: Exception? = null
        for (base in cands.take(8)) {
            try {
                val entries = ProviderRepo.parseManifest(fetch(ProviderRepo.manifestUrl(base)))
                if (entries.isNotEmpty()) return base to entries
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("No manifest.json found there" + (last?.message?.let { " ($it)" } ?: ""))
    }

    /** Add a repo (the user typed / pasted it, or opened an add-source link) and read its manifest. Returns the providers it offers. */
    fun addRepo(input: String): Pair<String, List<ProviderRepo.Entry>> {
        val (base, entries) = resolve(input)
        if (state.repos.none { it.base == base }) save(state.copy(repos = state.repos + Repo(base, ProviderRepo.label(base), System.currentTimeMillis())))
        return base to entries
    }

    /** Add and install every provider of a repo in one go (the add-source link's single Install button). */
    fun addAndInstallAll(input: String): Int {
        val (base, entries) = addRepo(input)
        val todo = entries.filter { !it.disabled }
        todo.forEach { install(base, it) }
        return todo.size
    }

    /**
     * 0.46.2 preinstall Blaze's own example repo ("Blaze Official") from the copy bundled in the app, once. Its providers start
     * switched off for browsing (the built-in Internet Archive / Blender sources show the same films); they can be tested,
     * switched on, and updated from the repo like any other.
     */
    fun seedOfficial(bundled: (String) -> String?) {
        if (state.seeded) return
        try {
            val entries = ProviderRepo.parseManifest(bundled("manifest.json") ?: return)
            val base = ProviderRepo.OFFICIAL_BASE
            val installed = ArrayList(state.installed)
            for (e in entries) {
                val d = moduleDir(base, e.value); d.mkdirs()
                for (f in ProviderRepo.REQUIRED + ProviderRepo.OPTIONAL) bundled("dist/${e.value}/$f.js")?.let { File(d, "$f.js").writeText(it) }
                if (installed.none { it.value == e.value }) installed += Installed(e, base, Status.NOT_TESTED, "", enabled = false, latest = e.version)
            }
            val repos = if (state.repos.any { it.base == base }) state.repos else listOf(Repo(base, ProviderRepo.OFFICIAL_LABEL, 0L)) + state.repos
            save(state.copy(repos = repos, installed = installed, seeded = true))
        } catch (_: Exception) { save(state.copy(seeded = true)) }
    }

    fun manifest(base: String): List<ProviderRepo.Entry> = ProviderRepo.parseManifest(fetch(ProviderRepo.manifestUrl(base)))

    private fun moduleDir(repo: String, value: String) = File(dir, hash(repo) + "/" + value)

    /** Download a provider's modules (required + optional) and record it installed. */
    fun install(repo: String, e: ProviderRepo.Entry) {
        require(ProviderRepo.SAFE_ID.matches(e.value)) { "Bad provider id" }
        val got = LinkedHashMap<String, String>()
        for (f in ProviderRepo.REQUIRED + ProviderRepo.OPTIONAL) {
            var err: Exception? = null
            val text = ProviderRepo.moduleUrls(repo, e.value, f).firstNotNullOfOrNull { u -> try { fetch(u) } catch (x: Exception) { err = x; null } }
                ?: if (f in ProviderRepo.REQUIRED) throw IllegalStateException("${e.name}: couldn't get $f.js (${err?.message})") else null
            if (text != null) { if (text.length > MAX_MODULE) throw IllegalStateException("${e.name}: $f.js is too big"); got[f] = text }
        }
        val d = moduleDir(repo, e.value); d.deleteRecursively(); d.mkdirs()
        got.forEach { (f, t) -> File(d, "$f.js").writeText(t) }
        val keep = state.installed.firstOrNull { it.value == e.value && it.repo == repo }
        val others = state.installed.filterNot { it.value == e.value }
        save(state.copy(installed = others + Installed(e, repo, Status.NOT_TESTED, "", keep?.enabled ?: true, 0, e.version, keep?.settings.orEmpty()),
            active = state.active.ifBlank { e.value }))
    }

    fun remove(value: String) {
        state.installed.firstOrNull { it.value == value }?.let { moduleDir(it.repo, value).deleteRecursively() }
        kvs.remove(value)
        val left = state.installed.filterNot { it.value == value }
        save(state.copy(installed = left, active = if (state.active == value) left.firstOrNull()?.value.orEmpty() else state.active))
    }

    fun removeRepo(base: String) {
        state.installed.filter { it.repo == base }.forEach { remove(it.value) }
        save(state.copy(repos = state.repos.filterNot { it.base == base }))
    }

    fun setActive(value: String) = save(state.copy(active = value))
    fun setEnabled(value: String, on: Boolean) = update(value) { it.copy(enabled = on, hangs = if (on) 0 else it.hangs) }
    fun setSettings(value: String, settings: Map<String, String>) = update(value) { it.copy(settings = settings.filterKeys { k -> k.length <= 40 }.mapValues { v -> v.value.take(500) }) }
    private fun update(value: String, f: (Installed) -> Installed) = save(state.copy(installed = state.installed.map { if (it.value == value) f(it) else it }))

    /** Re-read every repo's manifest; returns how many installed providers have a newer version. */
    fun refresh(): Int {
        var n = 0
        val latest = HashMap<String, String>()
        for (r in state.repos) runCatching { manifest(r.base) }.getOrNull()?.forEach { latest[r.base + "|" + it.value] = it.version }
        save(state.copy(installed = state.installed.map { i -> val l = latest[i.repo + "|" + i.value] ?: i.latest; if (ProviderRepo.newer(i.entry.version, l)) n++; i.copy(latest = l) }))
        return n
    }

    fun update(value: String) {
        val i = state.installed.firstOrNull { it.value == value } ?: return
        val e = manifest(i.repo).firstOrNull { it.value == value } ?: throw IllegalStateException("${i.entry.name} is no longer in its source")
        install(i.repo, e)
    }

    fun module(value: String, file: String): String? = state.installed.firstOrNull { it.value == value }?.let { File(moduleDir(it.repo, value), "$file.js").takeIf { f -> f.isFile }?.readText() }

    fun source(value: String, sandbox: ProviderSandbox = ProviderSandbox()): JsProviderSource? = state.installed.firstOrNull { it.value == value }?.let { i ->
        JsProviderSource(i.entry, { f -> module(value, f) }, ProviderSandbox.ProviderEnv(i.settings, kvs.getOrPut(value) { LinkedHashMap() }), sandbox,
            official = i.repo == ProviderRepo.OFFICIAL_BASE)
    }

    /** Enabled providers as movie sources (the active one first). */
    fun sources(): List<JsProviderSource> = state.installed.filter { it.enabled }.sortedBy { if (it.value == state.active) 0 else 1 }.mapNotNull { source(it.value) }

    /** Test: catalog + the first catalog row (or a search) must answer within the sandbox limits. */
    suspend fun test(value: String): Status {
        val before = ProviderSandbox.abandoned.get()
        val s = source(value) ?: return Status.FAILING
        val (st, why) = try {
            val cat = s.catalog()
            val posts = if (cat.isNotEmpty()) s.rows().firstOrNull()?.second.orEmpty() else s.search("the")
            if (posts.isEmpty()) Status.FAILING to "It answered but listed nothing" else Status.WORKING to "${posts.size} titles"
        } catch (e: Exception) { Status.FAILING to (e.message ?: "Error").take(160) }
        val hung = ProviderSandbox.abandoned.get() > before
        update(value) { it.copy(status = st, detail = why, hangs = if (hung) it.hangs + 1 else it.hangs, enabled = if (hung && it.hangs + 1 >= 2) false else it.enabled) }
        return st
    }

    /** A call failed while browsing: mark it Failing (and turn it off after repeated hangs). */
    fun noteFailure(value: String, why: String) {
        val hung = why.contains("longer than")
        update(value) { it.copy(status = Status.FAILING, detail = why.take(160), hangs = if (hung) it.hangs + 1 else it.hangs, enabled = if (hung && it.hangs + 1 >= 3) false else it.enabled) }
    }
    fun noteWorking(value: String) { if (state.installed.firstOrNull { it.value == value }?.status != Status.WORKING) update(value) { it.copy(status = Status.WORKING, detail = "") } }
}
