package com.blaze.assistant.movies

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 0.46.2 user-added provider repos ("sources"). Blaze ships none and suggests none: the user types or pastes a repo URL.
 * A repo is a folder with manifest.json (an array of {value, display_name, version, icon, type, disabled, hasSettings}) and
 * dist/<value>/{posts,meta,stream,catalog}.js (+ optional episodes.js), the module layout of Vega-format provider repos.
 * Pure; ProviderRepoTest.
 */
object ProviderRepo {
    val REQUIRED = listOf("posts", "meta", "stream", "catalog")
    val OPTIONAL = listOf("episodes")

    data class Entry(val value: String, val name: String, val version: String, val icon: String = "", val type: String = "global",
                     val disabled: Boolean = false, val hasSettings: Boolean = false)

    /** manifest.json -> entries (bad ones dropped; ids limited to safe characters so they can name files). */
    fun parseManifest(text: String): List<Entry> {
        val t = text.trim()
        val arr = when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> JSONObject(t).let { o -> o.optJSONArray("providers") ?: o.optJSONArray("items") ?: throw IllegalArgumentException("No provider list in manifest.json") }
            else -> throw IllegalArgumentException("manifest.json isn't JSON")
        }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
            val v = o.optString("value").trim()
            if (!SAFE_ID.matches(v)) null
            else Entry(v, o.optString("display_name").trim().ifBlank { v }.take(60), o.optString("version").trim().ifBlank { "0" }.take(20),
                o.optString("icon").trim().takeIf { UrlGuard.check(it) == null }.orEmpty(), o.optString("type").trim().ifBlank { "global" }.take(20),
                o.optBoolean("disabled"), o.optBoolean("hasSettings"))
        } }.distinctBy { it.value }.take(100)
    }

    val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    /**
     * Blaze's own example repo (Vega format: Internet Archive + Blender open movies), preinstalled as "Blaze Official". Its source
     * lives in Blaze's GitLab (blazeinchue-group/blaze-providers-example); the public copy is served from Blaze's site.
     */
    const val OFFICIAL_BASE = "https://blazeinchue.pages.dev/providers/blaze-official"
    const val OFFICIAL_LABEL = "Blaze Official"
    /** What the Add source field shows as its example (Blaze's own repo, never a third-party one). */
    const val EXAMPLE = OFFICIAL_BASE

    private val SHORTHAND = Regex("^([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})$")
    private val GH = Regex("(?i)^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/(?:tree|blob)/([^/]+)(/.*)?)?$")
    private val GH_RAW = Regex("(?i)^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)(/.*)?$")
    private val GL = Regex("(?i)^https?://(?:www\\.)?gitlab\\.com/((?:[^/-][^/]*/)+?[^/]+?)(?:\\.git)?(?:/-/(?:tree|blob|raw)/([^/]+)(/.*)?)?$")

    /**
     * What the user typed / a link carried -> the base URLs to try, in order (each is a folder that may hold manifest.json).
     * "owner/repo" means GitHub. A GitHub or GitLab repo page becomes its raw files on the named branch, else main, then master;
     * each also with dist/. Any other http(s) URL is tried as given, then with dist/. Returns (candidates, why-not).
     */
    fun candidates(input: String): Pair<List<String>, String> {
        var s = input.trim().trim('"', '\'', '<', '>').substringBefore('#')
        if (s.isEmpty()) return emptyList<String>() to "Paste the source's URL."
        SHORTHAND.matchEntire(s)?.let { m -> if (!m.groupValues[1].contains('.')) s = "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}" }
        if (!Regex("(?i)^[a-z][a-z0-9+.-]*://").containsMatchIn(s)) s = "https://$s"
        UrlGuard.check(s)?.let { return emptyList<String>() to it }
        s = s.substringBefore('?').trimEnd('/')
        if (s.endsWith("/manifest.json", true)) s = s.dropLast("/manifest.json".length)
        fun withDist(bases: List<String>) = bases.flatMap { listOf(it, "$it/dist") }.distinct()
        GH.matchEntire(s)?.let { m ->
            val (o, r, br, rest) = m.destructured
            val path = rest.trimEnd('/')
            return withDist((listOf(br).filter { it.isNotBlank() } + listOf("main", "master")).distinct().map { "https://raw.githubusercontent.com/$o/$r/$it$path" }) to ""
        }
        GH_RAW.matchEntire(s)?.let { return withDist(listOf(s)) to "" }
        GL.matchEntire(s)?.let { m ->
            val (proj, br, rest) = m.destructured
            val path = rest.trimEnd('/')
            return withDist((listOf(br).filter { it.isNotBlank() } + listOf("main", "master")).distinct().map { "https://gitlab.com/$proj/-/raw/$it$path" }) to ""
        }
        return withDist(listOf(s)) to ""
    }

    /** The first candidate (kept for callers that want one URL without network). */
    fun normalize(input: String): Pair<String?, String> = candidates(input).let { (c, why) -> c.firstOrNull() to why }

    fun manifestUrl(base: String) = "$base/manifest.json"
    fun moduleUrl(base: String, value: String, file: String) = "$base/dist/$value/$file.js"
    /** A manifest kept inside dist/ has its modules next to it (dist/<value>/<file>.js = base/<value>/<file>.js). */
    fun moduleUrls(base: String, value: String, file: String): List<String> =
        if (base.endsWith("/dist")) listOf("$base/$value/$file.js", moduleUrl(base, value, file)) else listOf(moduleUrl(base, value, file), "$base/$value/$file.js")

    /** A short label for a repo (host + first path parts), shown in the Providers list. */
    fun label(base: String): String = try {
        val u = URI(base); val parts = u.path.trim('/').split('/').filter { it.isNotBlank() }.take(2)
        (listOf(u.host.removePrefix("www.")) + parts).joinToString("/").take(60)
    } catch (_: Exception) { base.take(60) }

    /** Newer version? Numeric dotted compare ("1.10" > "1.9"), else plain string inequality. */
    fun newer(have: String, latest: String): Boolean {
        val a = have.split('.').map { it.toIntOrNull() }; val b = latest.split('.').map { it.toIntOrNull() }
        if (a.all { it != null } && b.all { it != null }) {
            for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrNull(i) ?: 0; val y = b.getOrNull(i) ?: 0; if (x != y) return y > x }
            return false
        }
        return have != latest
    }
}

/** URLs from a provider (or a pasted repo) are untrusted: http(s) only, no user:password@, no private-network hosts. */
object UrlGuard {
    /** Tests serve a mock provider on localhost; never set in the app. */
    @Volatile var allowPrivateForTests = false

    /** null = allowed, else why not. */
    fun check(url: String, allowPrivate: Boolean = allowPrivateForTests): String? {
        val u = try { URI(url.trim()) } catch (_: Exception) { return "That isn't a valid URL." }
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Only http and https URLs work."
        if (!u.rawUserInfo.isNullOrEmpty()) return "URLs with a user name or password aren't allowed."
        val host = u.host?.lowercase()?.trim('[', ']') ?: return "That URL has no host."
        if (!allowPrivate && isPrivateHost(host)) return "Local-network addresses aren't allowed for sources."
        return null
    }

    fun isPrivateHost(h0: String): Boolean {
        val h = h0.lowercase().trim('[', ']')
        if (h.isEmpty() || h == "localhost" || h == "::1" || h == "::" || h == "0.0.0.0" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        val p = h.split('.')
        if (p.size == 4 && p.all { it.toIntOrNull() in 0..255 }) {
            val (a, b) = p[0].toInt() to p[1].toInt()
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224
        }
        if (':' in h) return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb") || h.startsWith("::ffff:")
        return false
    }
}

/**
 * 0.46.2 one-tap add-source links that communities share (Blaze hosts no list): blaze://add-source?url=<repo> and
 * https://blazeinchue.pages.dev/add-source?url=<repo> (the site page opens the app, or offers the APK). Pure; AddSourceLinkTest.
 */
object AddSourceLink {
    const val SITE = "https://blazeinchue.pages.dev/add-source"
    /** The repo text a link carries, or null when it isn't an add-source link (or carries nothing usable). */
    fun parse(link: String?): String? {
        val u = try { java.net.URI(link?.trim() ?: return null) } catch (_: Exception) { return null }
        val ok = (u.scheme.equals("blaze", true) && (u.host.equals("add-source", true) || u.schemeSpecificPart.trimStart('/').startsWith("add-source", true))) ||
            (u.scheme.equals("https", true) && u.host.equals("blazeinchue.pages.dev", true) && u.path.trimEnd('/').equals("/add-source", true))
        if (!ok) return null
        val q = u.rawQuery ?: return null
        val v = q.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
        val url = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { return null }.trim()
        if (url.isEmpty() || url.length > 500) return null
        return if (ProviderRepo.candidates(url).first.isEmpty()) null else url
    }
    fun make(repo: String, https: Boolean = true): String = (if (https) SITE else "blaze://add-source") + "?url=" + java.net.URLEncoder.encode(repo, "UTF-8")
}

/** The one-time notice shown the first time the user adds a source (and only then). */
object SourceNotice {
    const val TEXT = "Third-party sources aren't made or checked by Blaze. You're responsible for what you add and watch."
    const val KEY = "movies_source_notice_seen"
    /** Show the notice before this add? (first add only) */
    fun shouldShow(seen: Boolean): Boolean = !seen
}
