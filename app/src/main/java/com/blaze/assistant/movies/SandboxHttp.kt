package com.blaze.assistant.movies

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 0.46.2 the only network a provider module gets. Every hop (the first URL and every redirect) passes [UrlGuard]; no cookie
 * jar, no Blaze headers or tokens, no app accounts; bodies are capped. Plain HttpURLConnection (no app-wide CookieHandler is set).
 */
interface SandboxHttp {
    data class Req(val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(), val body: String? = null, val follow: Boolean = true)
    data class Res(val status: Int, val statusText: String, val url: String, val headers: Map<String, String>, val body: String)
    fun request(r: Req): Res
}

object DefaultSandboxHttp : SandboxHttp {
    const val MAX_BODY = 4 * 1024 * 1024
    const val MAX_REDIRECTS = 5
    private val METHODS = setOf("GET", "POST", "HEAD", "PUT", "OPTIONS")
    /** Headers a provider may not set (they'd leak or confuse the transport). */
    private val BLOCKED = setOf("host", "content-length", "connection", "transfer-encoding", "proxy-authorization")
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    override fun request(r: SandboxHttp.Req): SandboxHttp.Res {
        val method = r.method.uppercase().takeIf { it in METHODS } ?: throw IllegalArgumentException("Method ${r.method} isn't allowed")
        var url = r.url; var hops = 0; var m = method; var body = r.body
        while (true) {
            UrlGuard.check(url)?.let { throw IllegalArgumentException(it) }
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false; c.connectTimeout = 12_000; c.readTimeout = 15_000; c.useCaches = false
                c.requestMethod = m
                c.setRequestProperty("User-Agent", UA)
                for ((k, v) in r.headers) if (k.lowercase() !in BLOCKED && k.isNotBlank() && '\n' !in v && '\r' !in v) c.setRequestProperty(k, v)
                if (body != null && m != "GET" && m != "HEAD") { c.doOutput = true; c.outputStream.use { it.write(body!!.toByteArray()) } }
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (r.follow && code in listOf(301, 302, 303, 307, 308) && loc != null && hops < MAX_REDIRECTS) {
                    url = URI(url).resolve(loc.trim()).toString(); hops++
                    if (code == 303 || ((code == 301 || code == 302) && m == "POST")) { m = "GET"; body = null }
                    continue
                }
                val hs = LinkedHashMap<String, String>()
                c.headerFields.forEach { (k, v) -> if (k != null) hs[k.lowercase()] = v.joinToString(if (k.equals("set-cookie", true)) "\n" else ", ") }
                val text = if (m == "HEAD") "" else ((if (code >= 400) c.errorStream else c.inputStream)?.use { inp ->
                    val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var n: Int
                    while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); if (out.size() > MAX_BODY) throw IllegalStateException("Response over ${MAX_BODY / 1024 / 1024} MB") }
                    out.toString(charset(c.contentType))
                } ?: "")
                return SandboxHttp.Res(code, c.responseMessage.orEmpty(), url, hs, text)
            } finally { c.disconnect() }
        }
    }

    private fun charset(ct: String?): String = ct?.let { Regex("(?i)charset=([\\w.-]+)").find(it)?.groupValues?.get(1) }?.takeIf { runCatching { java.nio.charset.Charset.forName(it) }.isSuccess } ?: "UTF-8"

    fun reqFrom(o: JSONObject): SandboxHttp.Req {
        val h = LinkedHashMap<String, String>()
        o.optJSONObject("headers")?.let { hs -> hs.keys().forEach { k -> val v = hs.opt(k); if (v != null && v != JSONObject.NULL) h[k] = v.toString() } }
        return SandboxHttp.Req(o.optString("url"), o.optString("method", "GET").ifBlank { "GET" }, h, if (o.has("body") && !o.isNull("body")) o.optString("body") else null, o.optBoolean("follow", true))
    }
    fun resJson(r: SandboxHttp.Res): String = JSONObject().put("status", r.status).put("statusText", r.statusText).put("url", r.url)
        .put("headers", JSONObject(r.headers as Map<*, *>)).put("body", r.body).toString()
}
