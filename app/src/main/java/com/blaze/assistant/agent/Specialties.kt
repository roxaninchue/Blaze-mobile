package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 0.45.23 Specialties (gateway 0.8.45, src/specialties.ts): the account's playbooks of proven steps plus the user's own
 * rules for one kind of task. The gateway creates them (2nd similar task), loads them (only above its match threshold)
 * and refines them (lasting rules, corrections); the phone only lets the person list, view and delete them
 * (Settings › Advanced › Specialties).
 *
 *  - `GET /v1/specialties` → `{object:"list", data:[{id, name, kind, version, uses, steps, rules, updated_at}], general_rules:[…]}`
 *  - `GET /v1/specialties/:id` → `{object:"specialty", id, name, kind, trigger, steps[], rules[], version, uses, created_at, updated_at}`
 *    (`:id` = `sp_` + 20 hex, or `general` → `{id:"general", name:"General rules", rules[]}`)
 *  - `DELETE /v1/specialties/:id` → `{id, deleted:true}`; `DELETE /v1/specialties/:id/rules/:n` → `{id, deleted_rule:n, version?}`
 *  - Errors: `{error:{message, type, code}}`; 401 signed out / bad token, 404 no such specialty or rule (or, on the list,
 *    a gateway older than 0.8.45).
 *
 * Chat never names a specialty: run events only carry "Using a specialty" / "Updating a specialty" (StepPill). This
 * client never sends X-Blaze-Debug, so the gateway's private `_name` fields never reach the phone.
 *
 * Pure parts ([parseList], [parseDetail], [Client] over [CloudRuns.Http]) are tested on the JVM (SpecialtiesTest).
 */
object Specialties {
    const val GENERAL = "general"
    private val ID_RE = Regex("^(sp_[0-9a-f]{20}|general)$")

    data class Summary(val id: String, val name: String, val kind: String, val version: Int, val uses: Int,
                       val steps: Int, val rules: Int, val updatedAt: String)

    data class Listing(val items: List<Summary>, val generalRules: List<String>) {
        val isEmpty: Boolean get() = items.isEmpty() && generalRules.isEmpty()
    }

    data class Detail(val id: String, val name: String, val kind: String, val trigger: String, val steps: List<String>,
                      val rules: List<String>, val version: Int, val uses: Int, val createdAt: String, val updatedAt: String) {
        val isGeneral: Boolean get() = id == GENERAL
    }

    /** One call's outcome. Every failure the page can show has its own case. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        /** No Blaze session on this phone, or the gateway rejected it (401). */
        object SignedOut : Result<Nothing>()
        /** 404 on a specialty or a rule: it's gone (deleted on another device, or never existed). */
        object NotFound : Result<Nothing>()
        /** 404 / 405 on the list: the gateway is older than 0.8.45. */
        object Unavailable : Result<Nothing>()
        /** No connection (DNS, timeout, refused). */
        object Offline : Result<Nothing>()
        data class Failed(val code: Int, val error: String) : Result<Nothing>()
    }

    fun validId(id: String) = ID_RE.matches(id)

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optString(i, "").trim().takeIf { it.isNotEmpty() } }

    /** `steps` / `rules` on the list are counts; a full array (older drafts) counts too. */
    private fun count(o: JSONObject, key: String): Int = o.optJSONArray(key)?.length() ?: o.optInt(key, 0)

    fun parseList(o: JSONObject): Listing {
        val a = o.optJSONArray("data") ?: JSONArray()
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { s ->
            val id = s.optString("id")
            if (id.isBlank()) null
            else Summary(id, s.optString("name").ifBlank { "Specialty" }, s.optString("kind"), s.optInt("version", 1),
                s.optInt("uses", 0), count(s, "steps"), count(s, "rules"), s.optString("updated_at"))
        }
        return Listing(items, strings(o.optJSONArray("general_rules")))
    }

    fun parseDetail(o: JSONObject): Detail {
        val id = o.optString("id")
        return Detail(id, o.optString("name").ifBlank { if (id == GENERAL) "General rules" else "Specialty" }, o.optString("kind"),
            o.optString("trigger"), strings(o.optJSONArray("steps")), strings(o.optJSONArray("rules")), o.optInt("version", 1),
            o.optInt("uses", 0), o.optString("created_at"), o.optString("updated_at"))
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "2026-10-23T17:02:11.000Z" → "23 Oct 2026"; anything else → "". */
    fun shortDate(iso: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso.trim()) ?: return ""
        val (y, mo, d) = m.destructured
        val mi = mo.toInt() - 1
        if (mi !in 0..11) return ""
        return "${d.toInt()} ${MONTHS[mi]} $y"
    }

    /** "Used 3 times · 2 rules" for a list row. */
    fun subLine(s: Summary): String = listOfNotNull(
        when (s.uses) { 0 -> "Not used yet"; 1 -> "Used once"; else -> "Used ${s.uses} times" },
        when (s.rules) { 0 -> null; 1 -> "1 rule"; else -> "${s.rules} rules" },
        shortDate(s.updatedAt).ifEmpty { null }?.let { "updated $it" },
    ).joinToString(" \u00b7 ")

    /** The gateway's `error.code` (or `http_<n>`). */
    fun errorCode(code: Int, body: String): String =
        (try { JSONObject(body).optJSONObject("error")?.optString("code") } catch (_: Exception) { null })?.ifBlank { null } ?: "http_$code"

    /** A plain sentence for a failure (the page's error / offline / empty-ish states). */
    fun plain(r: Result<*>): String = when (r) {
        is Result.Ok<*> -> ""
        Result.SignedOut -> "Sign in to your Blaze account to see your specialties."
        Result.NotFound -> "That specialty is gone. It may have been deleted on another device."
        Result.Unavailable -> "Specialties aren't available on Blaze's service yet."
        Result.Offline -> "You're offline. Connect to the internet to see your specialties."
        is Result.Failed -> when {
            r.code == 429 -> "Blaze's service is busy. Try again in a minute."
            r.code >= 500 -> "Blaze's service had a problem. Try again in a minute."
            else -> "Couldn't load your specialties (${r.error})."
        }
    }

    /** The gateway's /v1/specialties routes over [http] (paths relative to /v1, like CloudRuns). Blocking: call off the main thread. */
    class Client(
        private val http: CloudRuns.Http = Net(),
        private val signedIn: () -> Boolean = { Settings.gatewayToken().isNotBlank() },
    ) {
        private val headers = mapOf("Accept" to "application/json")

        private fun <T> call(method: String, path: String, list: Boolean = false, parse: (JSONObject) -> T): Result<T> {
            if (!signedIn()) return Result.SignedOut
            val res = try { http.send(method, path, null, headers) }
                catch (_: IOException) { return Result.Offline }
                catch (e: Exception) { return Result.Failed(0, e.javaClass.simpleName.ifBlank { "network" }) }
            val code = res.code
            val body = try { res.text() } catch (_: Exception) { "" }
            return when {
                code in 200..299 -> try { Result.Ok(parse(if (body.isBlank()) JSONObject() else JSONObject(body))) }
                    catch (_: Exception) { Result.Failed(code, "bad_response") }
                code == 401 || (code == 403 && errorCode(code, body) == "invalid_api_key") -> Result.SignedOut
                (code == 404 || code == 405) && list -> Result.Unavailable
                code == 404 -> Result.NotFound
                else -> Result.Failed(code, errorCode(code, body))
            }
        }

        fun list(): Result<Listing> = call("GET", "/specialties", list = true) { parseList(it) }

        fun get(id: String): Result<Detail> =
            if (!validId(id)) Result.NotFound else call("GET", "/specialties/$id") { parseDetail(it) }

        /** Deletes a whole specialty (or, for [GENERAL], every general rule). */
        fun delete(id: String): Result<Unit> =
            if (!validId(id)) Result.NotFound else call("DELETE", "/specialties/$id") { }

        /** Deletes rule [n] (0-based, as shown) of a specialty or of the general rules. */
        fun deleteRule(id: String, n: Int): Result<Unit> =
            if (!validId(id) || n < 0) Result.NotFound else call("DELETE", "/specialties/$id/rules/$n") { }
    }

    /**
     * The real gateway: HTTPS with the Blaze sign-in. Its own small connection (not CloudRuns.GatewayHttp) so a Settings
     * call never lands in a chat turn's trace or reply-language headers.
     */
    class Net(private val base: String = Providers.GATEWAY_BASE, private val token: () -> String = { Settings.gatewayToken() }) : CloudRuns.Http {
        override fun send(method: String, path: String, body: String?, headers: Map<String, String>): CloudRuns.Response {
            val c = com.blaze.assistant.connect.UrlSafety.open(base.trimEnd('/') + path)
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Blaze-Android")
                c.setRequestProperty("Authorization", "Bearer ${token()}")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val s = (if (code in 200..299) c.inputStream else c.errorStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
                return CloudRuns.Response(code, c.contentType.orEmpty(), s.bufferedReader(), onClose = { c.disconnect() })
            } catch (e: IOException) { c.disconnect(); throw e }
        }
    }
}
