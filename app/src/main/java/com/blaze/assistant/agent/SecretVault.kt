package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.46.6 Secure form: Blaze's vault for keys, tokens, logins, cards and one-time codes the user gives through the
 * Secure Form card (request_secret) or the paste guard. Each value is sealed with the Android Keystore AES-GCM key
 * ([Secrets], `enc1:`), lives in `filesDir/secret_vault.json` (excluded from cloud backup and device transfer) and is
 * never put into chat history, memory, logs, crash reports or a model/gateway request. The model only ever handles an
 * opaque handle like `{{secret:cloudflare_token}}` plus a safe preview ("…a1b2"); tools swap the value in on the phone
 * at execution time ([SecretUse]) and every output is scrubbed back to the handle ([scrub]).
 *
 * One-time codes are kept in memory only (10 minutes) and never written. Card details are only ever typed into a page
 * in Blaze's browser on the sites they were saved for, and are never shown back (no reveal). Pure Kotlin + org.json:
 * tests swap [seal] / [open] and [file] (SecretVaultTest, SecretUseTest, SecretLeakTest).
 */
object SecretVault {

    enum class Kind(val id: String, val title: String) {
        API_KEY("api_key", "API key"), LOGIN("login", "Login"), CARD("card", "Card"), CODE("code", "One-time code"), GENERIC("generic", "Secret");

        companion object {
            fun parse(raw: String?): Kind {
                val s = raw.orEmpty().lowercase().trim()
                return when {
                    s.isEmpty() -> GENERIC
                    Regex("card|credit|debit|payment").containsMatchIn(s) -> CARD
                    Regex("otp|one.?time|2fa|totp|verification|\\bcode\\b|^code$").containsMatchIn(s) -> CODE
                    Regex("login|password|passcode|sign.?in|credential|username").containsMatchIn(s) -> LOGIN
                    Regex("api|token|key|pat\\b|bearer").containsMatchIn(s) -> API_KEY
                    else -> entries.firstOrNull { it.id == s } ?: GENERIC
                }
            }
        }
    }

    data class FieldSpec(val name: String, val label: String, val masked: Boolean, val numeric: Boolean = false)

    data class Entry(val id: String, val label: String, val service: String, val kind: Kind, val hosts: List<String>,
                     val preview: String, val created: Long, val lastUsed: Long, val fields: List<String>)

    @Volatile var file: File? = null
    /** Encrypt / decrypt; tests swap these for plain functions (no Keystore on the JVM). */
    @Volatile var seal: (String) -> String = { Secrets.encrypt(it) }
    @Volatile var open: (String) -> String = { Secrets.decrypt(it) }
    @Volatile var now: () -> Long = { System.currentTimeMillis() }
    const val CODE_TTL_MS = 10 * 60_000L

    private val lock = Any()
    /** One-time codes: memory only. id -> (meta, values, expires). */
    private val codes = LinkedHashMap<String, Pair<JSONObject, Map<String, String>>>()
    @Volatile private var known: List<Pair<String, String>>? = null   // value -> handle, longest first (for scrub)

    // ---------- fields ----------
    fun fieldsFor(kind: Kind, label: String = ""): List<FieldSpec> = when (kind) {
        Kind.API_KEY -> listOf(FieldSpec("value", if (Regex("(?i)token").containsMatchIn(label)) "Token" else "API key", true))
        Kind.LOGIN -> listOf(FieldSpec("username", "Username or email", false), FieldSpec("password", "Password", true))
        Kind.CARD -> listOf(FieldSpec("number", "Card number", true, true), FieldSpec("exp", "Expiry (MM/YY)", false, true), FieldSpec("cvc", "CVC", true, true))
        Kind.CODE -> listOf(FieldSpec("code", "Code", false, true))
        Kind.GENERIC -> listOf(FieldSpec("value", label.ifBlank { "Secret" }.take(40), true))
    }

    /** The field the bare handle `{{secret:id}}` means. */
    fun primary(kind: Kind): String = when (kind) { Kind.LOGIN -> "password"; Kind.CARD -> "number"; Kind.CODE -> "code"; else -> "value" }

    // ---------- handles ----------
    val HANDLE = Regex("""\{\{secret:([a-z0-9_]{1,48})(?:\.([a-z0-9_]{1,24}))?\}\}""")
    fun handle(id: String, field: String? = null): String = if (field.isNullOrBlank()) "{{secret:$id}}" else "{{secret:$id.$field}}"
    fun hasHandle(s: String) = HANDLE.containsMatchIn(s)

    /** Every handle the model may use for [e] (the bare one first). */
    fun handlesOf(e: Entry): List<String> = listOf(handle(e.id)) + if (e.fields.size > 1) e.fields.map { handle(e.id, it) } else emptyList()

    fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "secret" }

    // ---------- previews (never the value) ----------
    fun preview(kind: Kind, values: Map<String, String>): String {
        fun tail(v: String) = if (v.length >= 12) "…" + v.takeLast(4) else "••••"
        return when (kind) {
            Kind.API_KEY, Kind.GENERIC -> tail(values["value"].orEmpty().trim())
            Kind.LOGIN -> maskUser(values["username"].orEmpty()) + " · ••••••"
            Kind.CARD -> values["number"].orEmpty().filter { it.isDigit() }.let { d -> if (d.length >= 12) "•••• " + d.takeLast(4) else "••••" }
            Kind.CODE -> "One-time code"
        }
    }

    fun maskUser(u: String): String {
        val v = u.trim(); if (v.isEmpty()) return "••••"
        val at = v.indexOf('@')
        return when {
            at > 0 -> v.take(minOf(2, at)) + "•••" + v.substring(at)
            v.length <= 4 -> v.take(1) + "•••"
            else -> v.take(2) + "•••" + v.takeLast(1)
        }
    }

    // ---------- storage ----------
    private fun data(): JSONObject = synchronized(lock) {
        try { file?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } } catch (_: Exception) { null } ?: JSONObject().put("v", 1).put("items", JSONObject())
    }
    private fun write(o: JSONObject) = synchronized(lock) {
        known = null
        try { file?.let { f -> f.parentFile?.mkdirs(); val t = File(f.path + ".tmp"); t.writeText(o.toString()); if (!t.renameTo(f)) { f.writeText(o.toString()); t.delete() } } }
        catch (e: Exception) { try { com.blaze.assistant.device.CrashLog.quiet("SecretVault.write", e) } catch (_: Throwable) { } }
    }
    private fun items(d: JSONObject): JSONObject = d.optJSONObject("items") ?: JSONObject().also { d.put("items", it) }

    private fun entryOf(id: String, o: JSONObject): Entry {
        val f = o.optJSONObject("f")
        val names = o.optJSONArray("fields")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: f?.keys()?.asSequence()?.toList() ?: emptyList()
        val hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Entry(id, o.optString("label"), o.optString("service"), Kind.parse(o.optString("kind")), hosts, o.optString("preview"),
            o.optLong("created"), o.optLong("used"), names)
    }

    private fun purgeCodes() = synchronized(lock) {
        val t = now(); val it = codes.entries.iterator()
        while (it.hasNext()) if (it.next().value.first.optLong("expires") < t) { it.remove(); known = null }
    }

    fun list(): List<Entry> {
        purgeCodes()
        val d = items(data())
        val stored = d.keys().asSequence().map { entryOf(it, d.getJSONObject(it)) }.toList()
        val mem = synchronized(lock) { codes.map { (id, p) -> entryOf(id, p.first) } }
        return (stored + mem).sortedByDescending { maxOf(it.lastUsed, it.created) }
    }

    fun get(id: String): Entry? = list().firstOrNull { it.id == id }

    /** The newest saved secret for [service] of [kind] (null = none). */
    fun findFor(service: String, kind: Kind): Entry? {
        val s = slug(service); if (s.isBlank()) return null
        return list().firstOrNull { slug(it.service) == s && it.kind == kind }
    }

    /**
     * Saves (or replaces, when [id] is given) a secret and returns its entry. One-time codes stay in memory only.
     * [values] go straight into the sealed store; nothing here logs or returns them.
     */
    fun save(kind: Kind, label: String, service: String, hosts: List<String>, values: Map<String, String>, id: String? = null): Entry {
        val clean = values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        require(clean.isNotEmpty()) { "nothing to save" }
        val t = now()
        val idBase = id ?: slug((service.ifBlank { label }) + "_" + when (kind) { Kind.API_KEY -> if (label.contains("key", true)) "key" else "token"; Kind.LOGIN -> "login"; Kind.CARD -> "card"; Kind.CODE -> "code"; Kind.GENERIC -> "secret" })
        val meta = JSONObject().put("label", label.ifBlank { (service.ifBlank { "Saved" }) + " " + kind.title.lowercase() }.take(60))
            .put("service", service.take(40)).put("kind", kind.id).put("hosts", JSONArray(hosts.map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct()))
            .put("preview", preview(kind, clean)).put("created", t).put("used", 0L)
        if (kind == Kind.CODE) {
            synchronized(lock) {
                val cid = id ?: uniqueId(idBase, codes.keys + items(data()).keys().asSequence().toSet())
                meta.put("expires", t + CODE_TTL_MS).put("fields", JSONArray(clean.keys.toList()))
                codes[cid] = meta to clean; known = null
                return entryOf(cid, meta)
            }
        }
        synchronized(lock) {
            val d = data(); val it = items(d)
            val sid = id ?: uniqueId(idBase, it.keys().asSequence().toSet() + codes.keys)
            it.optJSONObject(sid)?.let { old -> meta.put("created", old.optLong("created", t)).put("used", old.optLong("used")) }
            val f = JSONObject(); clean.forEach { (k, v) -> f.put(slug(k), seal(v)) }
            meta.put("f", f).put("fields", JSONArray(clean.keys.map { slug(it) }))
            it.put(sid, meta); write(d)
            return entryOf(sid, meta)
        }
    }

    private fun uniqueId(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2; while ("${base}_$n" in used) n++
        return "${base}_$n"
    }

    /** Replaces the values of [id] (Saved secrets › Replace). */
    fun replace(id: String, values: Map<String, String>): Entry? {
        val e = get(id) ?: return null
        val merged = e.fields.associateWith { value(id, it, touch = false).orEmpty() }.filterValues { it.isNotEmpty() } + values.filterValues { it.isNotBlank() }
        return save(e.kind, e.label, e.service, e.hosts, merged, id)
    }

    fun rename(id: String, label: String): Boolean = synchronized(lock) {
        val l = label.trim().take(60); if (l.isEmpty()) return false
        codes[id]?.let { it.first.put("label", l); return true }
        val d = data(); val o = items(d).optJSONObject(id) ?: return false
        o.put("label", l); write(d); true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (codes.remove(id) != null) { known = null; return true }
        val d = data(); val it = items(d)
        if (!it.has(id)) return false
        it.remove(id); write(d); true
    }

    /** The real value of one field ([field] null = the primary field). On the phone only; marks the secret used. */
    fun value(id: String, field: String? = null, touch: Boolean = true): String? {
        purgeCodes()
        synchronized(lock) {
            codes[id]?.let { (meta, vals) -> return vals[field ?: primary(Kind.parse(meta.optString("kind")))] }
        }
        val d = data(); val o = items(d).optJSONObject(id) ?: return null
        val f = o.optJSONObject("f") ?: return null
        val name = field ?: primary(Kind.parse(o.optString("kind")))
        val sealed = f.optString(name).ifEmpty { return null }
        val v = try { open(sealed) } catch (_: Exception) { "" }
        if (v.isEmpty()) return null
        if (touch) synchronized(lock) { val d2 = data(); items(d2).optJSONObject(id)?.let { it.put("used", now()); write(d2) } }
        return v
    }

    // ---------- scrubbing ----------
    /** value -> handle pairs (with the URL-encoded, Base64 and Basic-auth forms), longest first. Cached until a change. */
    private fun knownPairs(): List<Pair<String, String>> {
        known?.let { return it }
        val out = ArrayList<Pair<String, String>>()
        fun add(v: String, h: String) {
            if (v.length < 5) return
            out += v to h
            try { java.net.URLEncoder.encode(v, "UTF-8").takeIf { it != v && it.length >= 4 }?.let { out += it to h } } catch (_: Throwable) { }
            b64(v)?.let { if (it.length >= 8) out += it to h }
        }
        for (e in list()) {
            val vals = e.fields.associateWith { value(e.id, it, touch = false).orEmpty() }
            vals.forEach { (f, v) ->
                // A username or a card's expiry is not secret on its own (scrubbing "rocky" everywhere would garble the chat).
                if (v.isEmpty() || f == "username" || f == "exp") return@forEach
                val h = if (e.fields.size > 1) handle(e.id, f) else handle(e.id)
                add(v, h)
                if (e.kind == Kind.CARD && f == "number") { val dg = v.filter { it.isDigit() }; if (dg != v) add(dg, h); add(dg.chunked(4).joinToString(" "), h) }
            }
            if (e.kind == Kind.LOGIN) { val u = vals["username"].orEmpty(); val p = vals["password"].orEmpty()
                if (u.isNotEmpty() && p.isNotEmpty()) b64("$u:$p")?.let { out += it to handle(e.id) } }
        }
        return out.distinctBy { it.first }.sortedByDescending { it.first.length }.also { known = it }
    }

    private fun b64(s: String): String? = try { java.util.Base64.getEncoder().encodeToString(s.toByteArray()).trimEnd('=') } catch (_: Throwable) { null }

    /** Replaces every saved secret value in [text] (also URL-encoded / Base64 / Basic-auth forms) with its handle. Never throws. */
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        return try {
            val pairs = if (file == null && synchronized(lock) { codes.isEmpty() }) return text else knownPairs()
            var t = text
            for ((v, h) in pairs) if (t.contains(v)) t = t.replace(v, h)
            t
        } catch (_: Throwable) { text }
    }

    /** True when any saved value is in [text] (tests and the wire guard). */
    fun leaks(text: String): Boolean = try { knownPairs().any { text.contains(it.first) } } catch (_: Throwable) { false }

    /** For the chat bubble: `{{secret:gitlab_token}}` drawn as "🔒 GitLab token" (the message itself keeps the handle). */
    fun displayHandles(text: String): String {
        if (!hasHandle(text)) return text
        return try { HANDLE.replace(text) { m -> "\uD83D\uDD12 " + (get(m.groupValues[1])?.label ?: "saved secret") } } catch (_: Throwable) { text }
    }

    /** Tests: forget everything in memory (the file stays as it is). */
    fun resetForTests() = synchronized(lock) { codes.clear(); known = null }
}
