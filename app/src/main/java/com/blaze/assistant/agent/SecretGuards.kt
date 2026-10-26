package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/** 0.46.6 Secure form: the one rule every Blaze and Halo prompt carries. */
object SecretRules {
    const val TOOL = "request_secret"
    const val PROMPT = "SECRETS: never ask the user to type, paste or send a password, API key, token, card number, CVC, PIN or one-time code in the chat, " +
        "not even \"one message each\". Always call request_secret (kind, label, service): the user gets a Secure Form card, the value stays encrypted on the phone " +
        "and you get a handle like {{secret:cloudflare_token}}. Put the handle exactly where the value goes in http_request / run_shell / run_code / git / " +
        "browser_type; Blaze swaps the value in on the phone, only for the hosts it was saved for, and you never see it. Never write a handle into messages, " +
        "notes, files or memory. If the user pastes a secret anyway, it is saved for you and arrives as a handle."
}

/**
 * 0.46.6: a final reply that asks the user to send a key, token, password, card or code in the chat ("Yes, send them over,
 * one message each", seen on 0.46.3 for Cloudflare and GitLab tokens) is replaced by the Secure Form card(s): one
 * request_secret call per service it asked about. CredAsk still handles sign-ins on a page open in Blaze's browser.
 * Pure Kotlin (SecretGuardsTest).
 */
object SecretAsk {
    private const val NOUN = """(?:api[\s_-]?keys?|access[\s_-]?keys?|secret[\s_-]?keys?|private[\s_-]?keys?|keys?|tokens?|pats?\b|personal access tokens?|passwords?|pass\s?codes?|pins?\b|""" +
        """credentials|logins?|login details|sign[- ]in details|card(?: number| details)?|credit card|debit card|cvv|cvc|security code|expiry|""" +
        """one[- ]time (?:code|password)|otp|2fa code|verification code|auth(?:entication)? code|seed phrase|recovery phrase|secret)"""
    private const val VERB = """(?:send|share|give|type|enter|drop|paste|post|reply with|tell me|let me know|text|dm|message)"""
    // "send me your Cloudflare token", "paste the API key here", "what's your password?"
    private val ask = Regex("""(?i)\b$VERB\b[^.!?\n]{0,40}?\b(?:your|the|those|these|ur|both|each|a|an|me)\b[^.!?\n]{0,30}?\b$NOUN""")
    private val whats = Regex("""(?i)\b(?:what(?:'s| is| are)|need|could i (?:get|have)|can i (?:get|have))\b[^.!?\n]{0,20}?\b(?:your|ur)\b[^.!?\n]{0,30}?\b$NOUN""")
    // "send them over", "paste it here", "drop them in the chat", "one message each" when the turn is about secrets.
    private val pronoun = Regex("""(?i)\b(?:send|paste|drop|share|type|post|give)\b(?:\s+(?:me|us))?\s+(?:them|it|those|these|both|each)\b(?:\s+(?:over|here|in|across|through|one)|[^.!?\n]{0,30}\b(?:chat|message|here)\b)""")
    private val nounOnly = Regex("""(?i)\b$NOUN""")
    private val notAsk = Regex("""(?i)(secure form|request_secret|never (?:share|send|type|paste|post)|don'?t (?:share|send|type|paste|post)|do not (?:share|send|type|paste|post)|won'?t ask|""" +
        """saved (?:securely|login|password|key|token)|reset (?:your|the) password|forgot|revoke|in the (?:dashboard|settings|app)|on the page|in the browser)""")

    fun asks(reply: String, userText: String = ""): Boolean {
        val t = reply.trim()
        if (t.isEmpty() || t.length > 2500) return false
        if (notAsk.containsMatchIn(t)) return false
        if (ask.containsMatchIn(t) || whats.containsMatchIn(t)) return true
        return pronoun.containsMatchIn(t) && (nounOnly.containsMatchIn(t) || nounOnly.containsMatchIn(userText))
    }

    data class Want(val kind: SecretVault.Kind, val service: String, val label: String)

    /** What the reply (and the user's message) asked for: one per service named, else one generic. */
    fun wants(reply: String, userText: String = ""): List<Want> {
        val both = "$reply\n$userText"
        val lower = both.lowercase()
        val kind = when {
            Regex("""(?i)card number|credit card|debit card|\bcvv\b|\bcvc\b""").containsMatchIn(both) -> SecretVault.Kind.CARD
            Regex("""(?i)one[- ]time|\botp\b|2fa|verification code|auth(?:entication)? code""").containsMatchIn(both) -> SecretVault.Kind.CODE
            Regex("""(?i)api[\s_-]?key|token|\bpat\b|access key|secret key""").containsMatchIn(both) -> SecretVault.Kind.API_KEY
            Regex("""(?i)password|passcode|login|sign[- ]in|credentials|username""").containsMatchIn(both) -> SecretVault.Kind.LOGIN
            else -> SecretVault.Kind.API_KEY
        }
        val services = SecretUse.serviceNames.keys.filter { Regex("""\b$it\b""").containsMatchIn(lower) }
            .sortedBy { lower.indexOf(it) }.take(3)
        if (services.isEmpty()) return listOf(Want(kind, "", kind.title))
        return services.map { s -> val n = SecretUse.displayName(s)
            Want(kind, s, n + " " + when (kind) { SecretVault.Kind.API_KEY -> if (Regex("(?i)$s[^.\\n]{0,20}key").containsMatchIn(both)) "API key" else "token"; else -> kind.title.lowercase() }) }
    }

    /**
     * The request_secret calls to run instead of showing [reply], or null to show it. [guarded] = this turn already
     * swapped one reply; [known] = tools this request may call.
     */
    fun decide(reply: String, userText: String, guarded: Boolean, known: Collection<String>): JSONArray? {
        if (guarded || SecretRules.TOOL !in known) return null
        if (!asks(reply, userText)) return null
        val calls = JSONArray()
        wants(reply, userText).forEachIndexed { i, w ->
            val args = JSONObject().put("kind", w.kind.id).put("label", w.label).put("service", w.service)
                .put("hint", "So Blaze can use it without it going through the chat.")
            calls.put(JSONObject().put("id", "secretask_$i").put("type", "function")
                .put("function", JSONObject().put("name", SecretRules.TOOL).put("arguments", args.toString())))
        }
        return calls
    }
}

/**
 * 0.46.6 paste safety: a message the user is about to send that looks like a secret (token formats, a Luhn-valid card
 * number, "my password is …", or a long high-entropy string) is held before it is sent: "This looks like a secret.
 * Save it securely instead?" Pure Kotlin (SecretGuardsTest: true and false positives).
 */
object PasteGuard {
    data class Hit(val start: Int, val end: Int, val kind: SecretVault.Kind, val service: String, val label: String) {
        fun value(text: String) = text.substring(start, end)
    }

    private class Pat(val re: Regex, val kind: SecretVault.Kind, val service: String, val label: String, val group: Int = 0)

    private val pats = listOf(
        Pat(Regex("""\bgl(?:pat|dt|rt|ptt|ft|soat|cbt)-[A-Za-z0-9_\-]{20,}"""), SecretVault.Kind.API_KEY, "gitlab", "GitLab token"),
        Pat(Regex("""\bgithub_pat_[A-Za-z0-9_]{22,}"""), SecretVault.Kind.API_KEY, "github", "GitHub token"),
        Pat(Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}"""), SecretVault.Kind.API_KEY, "github", "GitHub token"),
        Pat(Regex("""\bsk-ant-[A-Za-z0-9_\-]{20,}"""), SecretVault.Kind.API_KEY, "anthropic", "Anthropic API key"),
        Pat(Regex("""\bsk-or-v1-[A-Za-z0-9]{20,}"""), SecretVault.Kind.API_KEY, "openrouter", "OpenRouter API key"),
        Pat(Regex("""(?<![A-Za-z0-9-])sk-(?:proj-|svcacct-)?[A-Za-z0-9_\-]{20,}"""), SecretVault.Kind.API_KEY, "openai", "OpenAI API key"),
        Pat(Regex("""\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"""), SecretVault.Kind.API_KEY, "stripe", "Stripe key"),
        Pat(Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""), SecretVault.Kind.API_KEY, "aws", "AWS access key"),
        Pat(Regex("""\bAIza[0-9A-Za-z_\-]{35}"""), SecretVault.Kind.API_KEY, "google", "Google API key"),
        Pat(Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}"""), SecretVault.Kind.API_KEY, "slack", "Slack token"),
        Pat(Regex("""\bhf_[A-Za-z0-9]{30,}"""), SecretVault.Kind.API_KEY, "huggingface", "Hugging Face token"),
        Pat(Regex("""\bgsk_[A-Za-z0-9]{40,}"""), SecretVault.Kind.API_KEY, "groq", "Groq API key"),
        Pat(Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""), SecretVault.Kind.API_KEY, "", "Access token"),
        Pat(Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""), SecretVault.Kind.GENERIC, "", "Private key"),
    )

    // "my password is hunter2", "password: X", "pwd=X", "my pin is 1234", "the code is 482913"
    private val said = Regex("""(?i)\b(?:my\s+|the\s+)?(password|passwd|pwd|passcode|pin|otp|one[- ]time code|verification code|api key|token|secret)\s*(?:is|=|:)\s*["'`]?([^\s"'`]{4,128})""")
    private val cfContext = Regex("""(?i)cloudflare|\bcf\b|wrangler|workers?|pages|zone""")
    private val long40 = Regex("""(?<![A-Za-z0-9_\-])[A-Za-z0-9_\-]{40}(?![A-Za-z0-9_\-])""")
    private val longTok = Regex("""(?<![A-Za-z0-9_\-+/=.])[A-Za-z0-9_\-+/=.]{32,200}(?![A-Za-z0-9_\-+/=.])""")
    private val cardRe = Regex("""(?<![\d+])(?:\d[ -]?){12,18}\d(?!\d)""")
    private val hex = Regex("""^[0-9a-f]+$""")
    private val uuid = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.any { !it.isDigit() }) return false
        var sum = 0; var dbl = false
        for (i in digits.indices.reversed()) { var d = digits[i] - '0'; if (dbl) { d *= 2; if (d > 9) d -= 9 }; sum += d; dbl = !dbl }
        return sum % 10 == 0
    }

    fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val f = s.groupingBy { it }.eachCount()
        return -f.values.sumOf { c -> val p = c.toDouble() / s.length; p * (Math.log(p) / Math.log(2.0)) }
    }

    private fun mixed(s: String) = s.any { it.isDigit() } && s.any { it.isLowerCase() } && s.any { it.isUpperCase() }

    /** A token-looking string that is not a hash, a UUID, a URL piece, a path or a word. */
    fun tokenish(s: String): Boolean {
        if (s.length < 32) return false
        if (hex.matches(s) || hex.matches(s.lowercase()) && s.length in setOf(32, 40, 64, 128)) return false
        if (uuid.matches(s)) return false
        if (s.contains("/") || s.contains("..") || s.startsWith("http")) return false
        if (!mixed(s)) return false
        return entropy(s) >= 4.0
    }

    /** Hits in [text], in order, not overlapping. Empty = nothing that looks like a secret. Handles are never hits. */
    fun detect(text: String): List<Hit> {
        if (text.length < 8 || text.length > 20_000) return emptyList()
        val hits = ArrayList<Hit>()
        fun free(s: Int, e: Int) = hits.none { s < it.end && e > it.start } && !inHandle(text, s)
        for (p in pats) for (m in p.re.findAll(text)) if (free(m.range.first, m.range.last + 1)) hits += Hit(m.range.first, m.range.last + 1, p.kind, p.service, p.label)
        for (m in said.findAll(text)) {
            val g = m.groups[2] ?: continue
            val word = m.groupValues[1].lowercase()
            val v = g.value.trimEnd('.', ',', '!', '?', ')')
            if (v.length < 4 || Regex("(?i)^(reset|wrong|correct|incorrect|expired|changed|same|not|too|still|missing|required|empty|invalid|the|my|a|an)$").matches(v)) continue
            val kind = when { word.contains("pin") || word.contains("code") || word == "otp" -> if (word == "pin") SecretVault.Kind.LOGIN else SecretVault.Kind.CODE
                word.contains("key") || word == "token" || word == "secret" -> SecretVault.Kind.API_KEY; else -> SecretVault.Kind.LOGIN }
            val s = g.range.first; val e = s + v.length
            if (free(s, e)) hits += Hit(s, e, kind, "", when (kind) { SecretVault.Kind.CODE -> "One-time code"; SecretVault.Kind.API_KEY -> "API key"; else -> if (word == "pin") "PIN" else "Password" })
        }
        for (m in long40.findAll(text)) {
            val v = m.value
            if (!free(m.range.first, m.range.last + 1)) continue
            if (hex.matches(v.lowercase()) || !mixed(v) || entropy(v) < 3.8) continue
            val cf = cfContext.containsMatchIn(text)
            hits += Hit(m.range.first, m.range.last + 1, SecretVault.Kind.API_KEY, if (cf) "cloudflare" else "", if (cf) "Cloudflare token" else "Token")
        }
        for (m in cardRe.findAll(text)) {
            val d = m.value.filter { it.isDigit() }
            if (!luhn(d) || d.first() !in "23456") continue
            if (free(m.range.first, m.range.last + 1)) hits += Hit(m.range.first, m.range.last + 1, SecretVault.Kind.CARD, "", "Card")
        }
        for (m in longTok.findAll(text)) {
            val v = m.value.trimEnd('.', '=')
            if (!tokenish(v)) continue
            if (free(m.range.first, m.range.first + v.length)) hits += Hit(m.range.first, m.range.first + v.length, SecretVault.Kind.API_KEY, "", "Token")
        }
        return hits.sortedBy { it.start }
    }

    private fun inHandle(text: String, at: Int): Boolean = SecretVault.HANDLE.findAll(text).any { at in it.range }

    /** [text] with each hit replaced by the handle it was saved as. */
    fun redact(text: String, hits: List<Hit>, handles: List<String>): String {
        val sb = StringBuilder(); var i = 0
        hits.zip(handles).sortedBy { it.first.start }.forEach { (h, hd) -> if (h.start >= i) { sb.append(text, i, h.start).append(hd); i = h.end } }
        sb.append(text.substring(i))
        return sb.toString()
    }

    /** The service a hit belongs to: its own, else the first service named in the message. */
    fun serviceFor(hit: Hit, text: String): String {
        if (hit.service.isNotBlank()) return hit.service
        val l = text.lowercase()
        return SecretUse.serviceNames.keys.filter { Regex("""\b$it\b""").containsMatchIn(l) }.minByOrNull { l.indexOf(it) }.orEmpty()
    }
}
