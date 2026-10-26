package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * 0.46.6 Secure form: using a saved secret without the model ever seeing it. A tool call may carry a handle like
 * `{{secret:cloudflare_token}}`; [prepare] swaps in the real value on the phone, right before the tool runs, and only
 * when the call goes to a host (or CLI) the secret was saved for. Card details never leave through http or a terminal
 * (only the browser types them, on their own site). Whatever the tool returns goes through [SecretVault.scrub] before
 * it reaches the chat or the model. Pure Kotlin (SecretUseTest).
 */
object SecretUse {

    /** Known services: where their APIs live. A secret saved for one is allowed there (and its subdomains) only. */
    val serviceHosts: Map<String, List<String>> = mapOf(
        "cloudflare" to listOf("api.cloudflare.com", "cloudflare.com"),
        "gitlab" to listOf("gitlab.com"),
        "github" to listOf("api.github.com", "github.com", "uploads.github.com"),
        "openai" to listOf("api.openai.com"),
        "anthropic" to listOf("api.anthropic.com"),
        "google" to listOf("googleapis.com", "google.com"),
        "gemini" to listOf("generativelanguage.googleapis.com"),
        "stripe" to listOf("api.stripe.com"),
        "aws" to listOf("amazonaws.com"),
        "slack" to listOf("slack.com"),
        "huggingface" to listOf("huggingface.co"),
        "openrouter" to listOf("openrouter.ai"),
        "groq" to listOf("api.groq.com"),
        "notion" to listOf("api.notion.com"),
        "telegram" to listOf("api.telegram.org"),
        "vercel" to listOf("api.vercel.com"),
        "netlify" to listOf("api.netlify.com"),
        "supabase" to listOf("supabase.co", "supabase.com"),
        "firebase" to listOf("firebaseio.com", "googleapis.com"),
        "twilio" to listOf("api.twilio.com"),
        "mistral" to listOf("api.mistral.ai"),
        "deepseek" to listOf("api.deepseek.com"),
        "nous" to listOf("nousresearch.com"),
    )

    /** CLIs that take a service's token from the command line or environment. */
    val serviceClis: Map<String, List<String>> = mapOf(
        "cloudflare" to listOf("wrangler", "flarectl"), "gitlab" to listOf("glab"), "github" to listOf("gh"), "aws" to listOf("aws"),
        "huggingface" to listOf("huggingface-cli"), "vercel" to listOf("vercel"), "netlify" to listOf("netlify"), "stripe" to listOf("stripe"),
        "supabase" to listOf("supabase"), "firebase" to listOf("firebase"),
    )

    /** Display names for services found in text ("GitLab", not "gitlab"). */
    val serviceNames: Map<String, String> = mapOf(
        "cloudflare" to "Cloudflare", "gitlab" to "GitLab", "github" to "GitHub", "openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google",
        "gemini" to "Gemini", "stripe" to "Stripe", "aws" to "AWS", "slack" to "Slack", "huggingface" to "Hugging Face", "openrouter" to "OpenRouter",
        "groq" to "Groq", "notion" to "Notion", "telegram" to "Telegram", "vercel" to "Vercel", "netlify" to "Netlify", "supabase" to "Supabase",
        "firebase" to "Firebase", "twilio" to "Twilio", "mistral" to "Mistral", "deepseek" to "DeepSeek", "nous" to "Nous",
    )

    /** Service id for a name the model or user wrote ("Cloudflare API", "gitlab.com" -> cloudflare / gitlab), else its slug. */
    fun serviceId(raw: String): String {
        val s = raw.lowercase().trim()
        if (s.isEmpty()) return ""
        serviceHosts.keys.firstOrNull { s == it || s.contains(it) }?.let { return it }
        if (s.contains("hugging")) return "huggingface"
        if (s.contains("chatgpt")) return "openai"
        if (s.contains("claude")) return "anthropic"
        return SecretVault.slug(s.substringBefore('/')).take(32)
    }

    fun displayName(service: String): String = serviceNames[serviceId(service)] ?: service.trim().replaceFirstChar { it.uppercase() }

    /** The hosts a secret for [service] may go to: given [hosts] first, else the service's known hosts, else the service if it looks like a domain. */
    fun hostsFor(service: String, hosts: List<String> = emptyList()): List<String> {
        val given = hosts.mapNotNull { hostOf(it).ifBlank { null } }
        if (given.isNotEmpty()) return given.distinct()
        serviceHosts[serviceId(service)]?.let { return it }
        val d = service.lowercase().trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        return if (Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(d)) listOf(d.removePrefix("www.")) else emptyList()
    }

    fun hostOf(url: String): String {
        val u = url.trim().lowercase()
        val m = Regex("^(?:[a-z][a-z0-9+.-]*://)?(?:[^@/\\s]*@)?([a-z0-9.-]+)").find(u) ?: return ""
        return m.groupValues[1].trim('.').removePrefix("www.")
    }

    /** [host] is one of [allowed] or a subdomain of one ("api.cloudflare.com.evil.io" is not). */
    fun hostAllowed(host: String, allowed: List<String>): Boolean {
        val h = host.lowercase().trim('.').removePrefix("www.")
        if (h.isEmpty()) return false
        return allowed.any { a0 -> val a = a0.lowercase().trim('.').removePrefix("www."); a.isNotEmpty() && (h == a || h.endsWith(".$a")) }
    }

    enum class Channel { HTTP, TERMINAL, BROWSER, NONE }

    fun channelOf(tool: String): Channel = when (tool) {
        "http_request", "fetch_page", "download_file" -> Channel.HTTP
        "run_shell", "run_code", "termux_run", com.blaze.assistant.terminal.Git.NAME -> Channel.TERMINAL
        else -> if (tool.startsWith("browser_")) Channel.BROWSER else Channel.NONE
    }

    sealed class Prep {
        /** No handle in the call: run it as it is. */
        object None : Prep()
        /** [args] with the values in; [ids] = secrets used. */
        data class Ok(val args: JSONObject, val ids: List<String>) : Prep()
        /** Refused: the text the model gets instead of a result. */
        data class Denied(val reason: String) : Prep()
    }

    private val urlRe = Regex("""(?i)\b(?:https?|wss?)://[^\s'"`<>)]+""")
    private val bareHostRe = Regex("""(?i)(?<![\w@/.-])((?:[a-z0-9-]+\.)+(?:com|io|dev|ai|co|org|net|app|cloud|so))(?![\w.-])""")

    /** Hosts a terminal command talks to (URLs first; then bare domains like `git push gitlab.com:…`). */
    fun hostsIn(text: String): List<String> {
        val urls = urlRe.findAll(text).map { hostOf(it.value) }.filter { it.isNotBlank() }.toList()
        if (urls.isNotEmpty()) return urls.distinct()
        return bareHostRe.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()
    }

    /**
     * Swaps handles in a tool call for values, on the phone, when the call goes only where each secret was saved for.
     * Browser tools resolve their own placeholders (SecureInput.resolve -> [forBrowser]) and come back as [Prep.None].
     */
    fun prepare(tool: String, args: JSONObject): Prep {
        val raw = args.toString()
        if (!SecretVault.hasHandle(raw)) return Prep.None
        val ch = channelOf(tool)
        if (ch == Channel.BROWSER) return Prep.None
        if (ch == Channel.NONE) return Prep.Denied("Saved secrets can only be used in http_request, fetch_page, download_file, run_shell, run_code or git calls " +
            "(and browser_type on their own site), never in $tool. Don't put a {{secret:…}} handle in messages, notes, files or memory.")
        val targets: List<String> = if (ch == Channel.HTTP) listOf(hostOf(args.optString("url"))).filter { it.isNotBlank() } else hostsIn(raw)
        val used = LinkedHashSet<String>()
        for (m in SecretVault.HANDLE.findAll(raw)) {
            val id = m.groupValues[1]
            val e = SecretVault.get(id) ?: return Prep.Denied("There's no saved secret ${m.value}. Ask the user with request_secret (never in chat), then use the handle it returns.")
            if (e.kind == SecretVault.Kind.CARD) return Prep.Denied("Card details are only ever typed into the payment page in Blaze's browser (browser_type on ${e.hosts.firstOrNull() ?: "its site"}), never sent through $tool.")
            val f = m.groupValues[2].ifBlank { null }
            if (f != null && f !in e.fields) return Prep.Denied("${e.label} has no field \"$f\". Use ${SecretVault.handlesOf(e).joinToString(" or ")}.")
            val allowed = e.hosts
            val ok = when {
                targets.isNotEmpty() -> allowed.isNotEmpty() && targets.all { hostAllowed(it, allowed) }
                ch == Channel.TERMINAL -> cliAllowed(e, raw)
                else -> false
            }
            if (!ok) return Prep.Denied("${m.value} (${e.label}) is only allowed for ${allowed.ifEmpty { listOf("the hosts it was saved for") }.joinToString(", ")}" +
                (serviceClis[serviceId(e.service)]?.let { " and the ${it.joinToString("/")} CLI" } ?: "") +
                if (targets.isNotEmpty()) "; this call goes to ${targets.joinToString(", ")}. Not sent." else "; this call doesn't say where it goes. Put the service's API URL in the call.")
            used += id
        }
        var miss: String? = null
        val out = SecretVault.HANDLE.replace(raw) { m ->
            val v = SecretVault.value(m.groupValues[1], m.groupValues[2].ifBlank { null })
            if (v == null) { miss = m.value; m.value } else JSONObject.quote(v).let { q -> q.substring(1, q.length - 1) }
        }
        miss?.let { return Prep.Denied("Couldn't open $it on this phone (it may have been saved on another phone or expired). Ask again with request_secret.") }
        return try { Prep.Ok(JSONObject(out), used.toList()) } catch (_: Exception) { Prep.Denied("Couldn't use the saved secret in this call.") }
    }

    private fun cliAllowed(e: SecretVault.Entry, cmd: String): Boolean {
        val clis = serviceClis[serviceId(e.service)] ?: return false
        return clis.any { Regex("(?:^|[\\s;&|(\"'])" + Regex.escape(it) + "(?:\\s|$)").containsMatchIn(cmd) }
    }

    /**
     * For Blaze's browser: the value of `{{secret:name}}` / `{{secret:name.field}}` when [host] is one the secret was saved
     * for (cards included: that is how a card reaches its payment page). Null when unknown or not allowed.
     */
    fun forBrowser(name: String, host: String): String? {
        val id = name.substringBefore('.'); val f = name.substringAfter('.', "").ifBlank { null }
        val e = SecretVault.get(id) ?: return null
        if (!hostAllowed(host, e.hosts)) return null
        return SecretVault.value(id, f)
    }
}
