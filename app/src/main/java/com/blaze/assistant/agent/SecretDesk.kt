package com.blaze.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.6 Secure form: the request_secret tool (Secure Form card in the chat) and the paste hold ("This looks like a
 * secret. Save it securely instead?"). The values typed or pasted go from the card straight into [SecretVault]; the
 * model gets only the handle and a safe preview. Pure Kotlin apart from the StateFlows the chat draws (SecretDeskTest).
 */
object SecretDesk {
    const val TOOL = SecretRules.TOOL
    const val COPY = "Only on this phone. Blaze uses it without seeing it."

    data class Request(val id: Long, val kind: SecretVault.Kind, val label: String, val service: String, val hint: String,
                       val hosts: List<String>, val fields: List<SecretVault.FieldSpec>, val answer: CompletableDeferred<Map<String, String>?>)

    data class Saved(val label: String, val preview: String, val at: Long)

    data class PasteHold(val text: String, val hits: List<PasteGuard.Hit>, val byVoice: Boolean, val send: (String) -> Unit) {
        /** What the user sees on the hold card: the message with each secret dotted out. */
        val masked: String get() = PasteGuard.redact(text, hits, hits.map { "•".repeat(8) + " (" + it.label + ")" })
        val labels: List<String> get() = hits.map { it.label }.distinct()
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending
    private val _saved = MutableStateFlow<Saved?>(null)
    /** The "Saved securely" state shown briefly after a card is filled. */
    val saved: StateFlow<Saved?> = _saved
    private val _paste = MutableStateFlow<PasteHold?>(null)
    val paste: StateFlow<PasteHold?> = _paste
    private val lock = Mutex()
    private val ids = java.util.concurrent.atomic.AtomicLong(1)
    @Volatile var waitMs = 15 * 60_000L

    fun toolDef(): JSONObject {
        fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
        val props = JSONObject()
            .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("api_key", "token", "password", "login", "card", "code", "generic")))
                .put("description", "api_key/token, password/login (username + password), card (number, expiry, CVC), code (one-time code), generic"))
            .put("label", p("string", "What the user sees, e.g. 'Cloudflare API token'"))
            .put("service", p("string", "The service it is for, e.g. cloudflare, gitlab, github, openai, or a domain"))
            .put("hint", p("string", "One short line: why it's needed or where to find it"))
            .put("hosts", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("description", "Hosts it may be sent to (default: the service's API hosts)"))
            .put("fields", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Field names, only for generic"))
            .put("fresh", p("boolean", "Ask again even if one is saved (e.g. the saved one was rejected)"))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
            .put("description", "Ask the user for a password, API key/token, login, card or one-time code with a Secure Form card. NEVER ask for these in chat. " +
                "The value is encrypted on the phone; you get a handle like {{secret:cloudflare_token}} and a safe preview. Put the handle where the value goes " +
                "in http_request / run_shell / run_code / git / browser_type: Blaze swaps the value in on the phone, only for the hosts it was saved for. " +
                "A saved one is returned without asking.")
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(listOf("kind", "label")))))
    }

    /** The request a tool call describes. */
    fun requestOf(args: JSONObject): Request {
        val kind = SecretVault.Kind.parse(args.optString("kind").ifBlank { args.optString("type") }.ifBlank { args.optString("label") })
        val rawService = args.optString("service").ifBlank { args.optString("label") }.let { s ->
            if (args.optString("service").isBlank()) SecretUse.serviceNames.keys.firstOrNull { s.lowercase().contains(it) }.orEmpty() else s }
        val service = SecretUse.serviceId(rawService)
        val label = args.optString("label").trim().ifBlank { (if (service.isNotBlank()) SecretUse.displayName(service) + " " else "") + kind.title.lowercase() }.take(60)
        val hostsArg = args.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val custom = args.optJSONArray("fields")?.let { a -> (0 until minOf(a.length(), 4)).mapNotNull { i ->
            (a.optJSONObject(i)?.optString("name") ?: a.optString(i)).takeIf { it.isNotBlank() } } } ?: emptyList()
        val fields = if (kind == SecretVault.Kind.GENERIC && custom.isNotEmpty())
            custom.map { n -> SecretVault.FieldSpec(SecretVault.slug(n), n.replace('_', ' ').replaceFirstChar { it.uppercase() }, !Regex("(?i)user|email|name|account|id$").containsMatchIn(n)) }
            else SecretVault.fieldsFor(kind, label)
        return Request(ids.getAndIncrement(), kind, label, service, args.optString("hint").take(140), SecretUse.hostsFor(rawService, hostsArg), fields, CompletableDeferred())
    }

    /** The text the model gets back: handles, preview and where it may go. Never a value. */
    fun summary(e: SecretVault.Entry, reused: Boolean): String {
        val hs = SecretVault.handlesOf(e)
        val where = if (e.hosts.isEmpty()) "only typed into pages in Blaze's browser" else "only for ${e.hosts.joinToString(", ")}" +
            (SecretUse.serviceClis[SecretUse.serviceId(e.service)]?.let { " (and the ${it.joinToString("/")} CLI)" } ?: "")
        return (if (reused) "Already saved securely: " else "Saved securely on the phone: ") + "${e.label} as ${hs.first()} (${e.preview})" +
            (if (hs.size > 1) "; fields: ${hs.drop(1).joinToString(", ")}" else "") + ". Usable $where. " +
            (if (e.kind == SecretVault.Kind.CARD) "Card details are only typed into the payment page with browser_type; never send them anywhere else. "
             else "Put the handle exactly where the value goes; Blaze swaps it in on the phone. ") +
            "You never see the value. Don't repeat the handle to the user and never ask them to type it in chat."
    }

    /**
     * The request_secret tool. [step] adds a chat step line; [away] tells the user when Blaze isn't on screen.
     * Returns the model's result (handles only).
     */
    suspend fun run(args: JSONObject, step: (String) -> Unit = {}, away: (String) -> Unit = {}): String {
        val req = requestOf(args)
        if (!args.optBoolean("fresh") && req.kind != SecretVault.Kind.CODE && req.service.isNotBlank()) {
            SecretVault.findFor(req.service, req.kind)?.let { step("Using your saved ${it.label}"); return summary(it, reused = true) }
        }
        step("Waiting for you: ${req.label}")
        val values = ask(req, away) ?: return "The user cancelled the Secure Form (or didn't answer). Don't ask for ${req.label} again in this task and never ask for it in chat: " +
            "say in one line what you needed it for."
        val e = try { SecretVault.save(req.kind, req.label, req.service, req.hosts, values) }
            catch (_: Exception) { return "The Secure Form came back empty. Ask again with request_secret only if the user wants to." }
        _saved.value = Saved(e.label, e.preview, System.currentTimeMillis())
        step("Saved securely: ${e.label}")
        return summary(e, reused = false)
    }

    suspend fun ask(req: Request, away: (String) -> Unit = {}): Map<String, String>? = lock.withLock {
        _pending.value = req
        try { away(req.label); kotlinx.coroutines.withTimeoutOrNull(waitMs) { req.answer.await() } }
        finally { if (_pending.value?.id == req.id) _pending.value = null }
    }

    /** Save / Cancel on the card. The card goes at once. */
    fun answer(values: Map<String, String>?) {
        val r = _pending.value ?: return
        r.answer.complete(values?.filterValues { it.isNotBlank() }?.takeIf { it.isNotEmpty() })
        _pending.value = null
    }

    fun clearSaved() { _saved.value = null }

    // ---------- paste safety ----------
    @Volatile private var bypass: String? = null

    /**
     * Before a message is sent: true = held for the "looks like a secret" card (don't send now). [send] sends a text
     * as the user's message (Agent.send). "Send anyway" passes the same text once.
     */
    fun hold(text: String, byVoice: Boolean, send: (String) -> Unit): Boolean {
        if (bypass != null && bypass == text) { bypass = null; return false }
        val hits = try { PasteGuard.detect(text) } catch (_: Throwable) { emptyList() }
        if (hits.isEmpty()) return false
        _paste.value = PasteHold(text, hits, byVoice, send)
        return true
    }

    /** "Save securely": each secret to the vault, then the message goes with handles in their place. */
    fun saveHeld(): List<SecretVault.Entry> {
        val h = _paste.value ?: return emptyList()
        _paste.value = null
        val entries = h.hits.map { hit ->
            val service = PasteGuard.serviceFor(hit, h.text)
            val kind = hit.kind
            val values = when (kind) {
                SecretVault.Kind.LOGIN -> mapOf("password" to hit.value(h.text))
                SecretVault.Kind.CARD -> mapOf("number" to hit.value(h.text).filter { it.isDigit() })
                SecretVault.Kind.CODE -> mapOf("code" to hit.value(h.text))
                else -> mapOf("value" to hit.value(h.text))
            }
            val label = if (service.isNotBlank() && !hit.label.startsWith(SecretUse.displayName(service))) SecretUse.displayName(service) + " " + hit.label.lowercase() else hit.label
            SecretVault.save(kind, label, service, SecretUse.hostsFor(service), values)
        }
        val handles = entries.map { SecretVault.handle(it.id) }
        val rest = PasteGuard.redact(h.text, h.hits, handles)
        val out = if (rest.trim() in handles.toSet() || rest.trim() == handles.joinToString(" ")) "I saved ${entries.joinToString(" and ") { it.label }} securely: ${handles.joinToString(", ")}." else rest
        entries.lastOrNull()?.let { _saved.value = Saved(entries.joinToString(", ") { it.label }, it.preview, System.currentTimeMillis()) }
        val text = out
        bypass = text
        h.send(text)
        return entries
    }

    /** "Send anyway": the message goes as typed. */
    fun sendHeldAnyway() {
        val h = _paste.value ?: return
        _paste.value = null
        bypass = h.text
        h.send(h.text)
    }

    /** Dismiss: nothing is sent; the text is dropped (it is not put back in the composer). */
    fun dropHeld() { _paste.value = null }
}
