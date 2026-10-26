package com.blaze.assistant.browser

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Tools that let Blaze browse in its own browser, in the background, without touching the screen. */
object BrowserTools {
    val names = setOf("browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_tabs", "browser_show", "browser_vpn", SECURE,
        // 0.45.29: choose in dropdowns, press keys, wait for something, find words on the page.
        "browser_select", "browser_key", "browser_wait", "browser_find")
    /** v0.20.0: in-chat secure form; handled by the Agent (it needs the chat card). */
    const val SECURE = "browser_secure_input"

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    fun definitions(): List<JSONObject> = listOf(
        fn("browser_open", "Open a web page (or search words) in Blaze's own built-in browser, in the background. No screen control needed. " +
            "Use it for sites you must interact with: forms, logins, shopping, booking, reading a full page. Returns the page text and numbered elements.",
            JSONObject().put("url", p("string", "URL or search words")).put("new_tab", p("boolean", "Open in a new tab"))
                .put("show", p("boolean", "Also show the browser to the user"))
                .put("private", p("boolean", "Open in a private tab: no history, cookies wiped when closed, location masked")), listOf("url")),
        fn("browser_read", "Read the current browser page again, what's on screen first: scroll position, text on screen, numbered links, buttons and fields " +
            "(\u2193 = below the screen), then text below. Numbers stay the same while the page is open."),
        fn("browser_click", "Click an element in the browser page by its [number] from browser_read, or by its visible text. Returns what the page shows after, " +
            "or why it couldn't (covered by a pop-up, hidden, disabled). Use browser_select for dropdowns.",
            JSONObject().put("number", p("integer", "Element number")).put("text", p("string", "Visible text, if no number"))),
        fn("browser_type", "Type into a text field in the browser page (by [number] or label), optionally pressing Enter/submitting. Checks the field kept the text.",
            JSONObject().put("number", p("integer", "Field number")).put("label", p("string", "Field label/placeholder, if no number"))
                .put("text", p("string", "Text to type")).put("submit", p("boolean", "Press Enter / submit the form")), listOf("text")),
        fn("browser_select", "Choose an option in a dropdown (a select, or a custom dropdown) by the option's visible text.",
            JSONObject().put("number", p("integer", "The dropdown's number")).put("label", p("string", "The dropdown's label, if no number"))
                .put("option", p("string", "The option's text")), listOf("option")),
        fn("browser_key", "Press a key on the focused element of the browser page: ${BrowserRead.KEYS.joinToString()}.",
            JSONObject().put("key", JSONObject().put("type", "string").put("enum", JSONArray(BrowserRead.KEYS))), listOf("key")),
        fn("browser_wait", "Wait (up to 20 s) until text appears on the page (or goes away), a CSS selector shows, or just until the page settles. Use after searches, " +
            "logins and anything that loads in the background, instead of reading again and again.",
            JSONObject().put("text", p("string", "Text to wait for")).put("selector", p("string", "CSS selector to wait for, if no text"))
                .put("gone", p("boolean", "Wait for it to go away instead")).put("seconds", p("integer", "Most seconds to wait (default 10, max 20)"))),
        fn("browser_find", "Find words on the browser page (text and element labels), even below the screen: matching snippets with the nearest element's [number]; " +
            "scrolls the first one into view. Faster than scrolling and reading.",
            JSONObject().put("query", p("string", "Words to find")), listOf("query")),
        fn("browser_scroll", "Scroll the browser page by a screen; returns what's on screen after.", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up"))))),
        fn("browser_back", "Go back one page in the browser."),
        fn("browser_tabs", "List browser tabs, or switch with switch_to.", JSONObject().put("switch_to", p("integer", "Tab number to switch to"))),
        fn(SECURE, "When the page in Blaze's background browser needs something only the user has (email, phone, username + password, API key, " +
            "one-time code), call this instead of asking in chat or showing the browser. The user gets a secure form card in the chat (an 'Action required' " +
            "notification if they're away); the browser fills the page from it and (by default) submits. You never see the values: you get placeholders like " +
            "{{secret:password}} that browser_type can reuse. Saved values for the site are used without asking. Never for card numbers (use browser_show for payment).",
            JSONObject().put("title", p("string", "Short title, e.g. 'Sign in to GitHub'"))
                .put("reason", p("string", "One line on why it's needed"))
                .put("fields", JSONObject().put("type", "array").put("description", "The fields to ask for, in page order")
                    .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("name", p("string", "Short id, e.g. email, password, code"))
                        .put("label", p("string", "What the user sees, e.g. 'GitHub password'"))
                        .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("email", "phone", "username", "password", "api_key", "otp", "number", "text"))))
                        .put("target", p("string", "The page field's [number] from browser_read, or its label; leave out to find it by kind")))
                        .put("required", JSONArray(listOf("name", "kind")))))
                .put("submit", p("boolean", "Submit the form after filling (default true)"))
                .put("fresh", p("boolean", "Ask even if values are saved for this site (e.g. the saved password was wrong)")), listOf("title", "fields")),
        fn("browser_show", "Show the built-in browser on screen with the current page, so the user can watch or take over (e.g. to pay, solve a captcha, or approve Google's own sign-in). Optional: work stays in the background by default."),
        fn("browser_vpn", "The built-in browser's VPN (only the browser goes through it). Built in and free: Blaze Free (Cloudflare WARP), one tap, no setup, exits near the user " +
            "(hides the real IP but can't pick a country). Plus any per-country WireGuard configs the user imported. " +
            "status: is it on, which country, which IP, which locations exist. connect: with no country (or \"free\") uses Blaze Free when nothing is imported, else the last/first imported one; " +
            "with a country (name or code) uses that imported config, e.g. to see a site as it looks there. disconnect: back to the real connection. " +
            "Countries need an imported WireGuard config (browser ⋯ › VPN & privacy).",
            JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "connect", "disconnect"))))
                .put("country", p("string", "Country name or code for connect, e.g. Germany or DE")), listOf("action")),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "browser_open" -> "Browsing ${com.blaze.assistant.agent.ToolLeak.fixBlazeUrl(a.optString("url")).take(50)}"
        "browser_read" -> "Reading the page"
        "browser_click" -> "Clicking ${a.optString("text").ifBlank { "#" + a.optInt("number") }}"
        "browser_type" -> "Typing in the page"
        "browser_scroll" -> "Scrolling the page"
        "browser_select" -> "Choosing ${a.optString("option").take(40)}"
        "browser_key" -> "Pressing ${a.optString("key")}"
        "browser_wait" -> "Waiting for the page"
        "browser_find" -> "Finding \u201c${a.optString("query").take(40)}\u201d"
        "browser_back" -> "Going back"
        "browser_tabs" -> "Checking tabs"
        SECURE -> "Asking you for ${a.optString("title").ifBlank { "a detail" }.take(40)}"
        "browser_vpn" -> when (a.optString("action")) { "connect" -> "Switching the browser VPN to ${a.optString("country")}"; "disconnect" -> "Turning the browser VPN off"; else -> "Checking the browser VPN" }
        else -> "Showing the browser"
    }

    fun show(ctx: Context) = ctx.startActivity(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        // v0.30.5: Home's live preview window shows the page while Blaze works in it.
        if (name != "browser_vpn" && name != "browser_tabs") BrowserLive.mark(describe(name, a))
        when (name) {
            // v0.28.4: a guessed Blaze repo URL (wrong owner / case) opens the real repo.
            // v0.33.3: a Play Store page for an installed app launches the app (never "Install" for something installed).
            "browser_open" -> com.blaze.assistant.phone.AppFinder.playId(a.optString("url"))?.takeIf { com.blaze.assistant.phone.InstalledApps.launch(ctx, it) }
                ?.let { "That app ($it) is installed on this phone, so I opened it instead of its Play Store page. Never offer to install it." } ?: BrowserEngine.open(ctx, com.blaze.assistant.agent.ToolLeak.fixBlazeUrl(a.optString("url")), a.optBoolean("new_tab", false), a.optBoolean("private", false)).also { if (a.optBoolean("show")) show(ctx) }
            "browser_read" -> BrowserEngine.read(ctx)
            "browser_click" -> {
                // v0.28.2: a button that pays, sends, posts or deletes always asks first (ConfirmGate: outbound in the user's name).
                val n = if (a.has("number")) a.optInt("number") else null
                val text = a.optString("text").ifBlank { null }
                val label = BrowserEngine.labelOf(n, text).ifBlank { text.orEmpty() }
                val site = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { BrowserEngine.host() }.ifBlank { "this page" }
                if (com.blaze.assistant.phone.Pointer.risky(label) &&
                    !com.blaze.assistant.agent.Agent.confirm("Click \u201c${label.trim().take(40)}\u201d on $site?",
                        "This looks like it pays, sends, posts or deletes something.", "Click", always = true))
                    "The user did NOT approve clicking \"${label.trim().take(40)}\". Don't retry; tell them it's ready for them to finish (browser_show)."
                else BrowserEngine.click(ctx, n, text)
            }
            "browser_type" -> BrowserEngine.type(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("label").ifBlank { null },
                a.optString("text"), a.optBoolean("submit", false))
            "browser_scroll" -> BrowserEngine.scroll(ctx, a.optString("direction", "down") != "up")
            "browser_select" -> BrowserEngine.select(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("label").ifBlank { null }, a.optString("option"))
            "browser_key" -> {
                // An Enter on a pay / send / post / delete button asks first, like a click on it (ConfirmGate).
                val k = a.optString("key")
                val focused = if (k.equals("Enter", true) || k.equals("Space", true)) BrowserEngine.focusedLabel() else ""
                val site = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { BrowserEngine.host() }.ifBlank { "this page" }
                if (focused.isNotBlank() && com.blaze.assistant.phone.Pointer.risky(focused) &&
                    !com.blaze.assistant.agent.Agent.confirm("Press \u201c${focused.trim().take(40)}\u201d on $site?",
                        "This looks like it pays, sends, posts or deletes something.", "Press", always = true))
                    "The user did NOT approve pressing \"${focused.trim().take(40)}\". Don't retry; tell them it's ready for them to finish (browser_show)."
                else BrowserEngine.key(ctx, k)
            }
            "browser_wait" -> BrowserEngine.waitFor(ctx, a.optString("selector").ifBlank { null }, a.optString("text").ifBlank { null }, a.optBoolean("gone", false), a.optInt("seconds", 10))
            "browser_find" -> BrowserEngine.find(ctx, a.optString("query"))
            "browser_back" -> BrowserEngine.back(ctx)
            "browser_tabs" -> if (a.has("switch_to")) BrowserEngine.switchTab(a.optInt("switch_to")) else BrowserEngine.tabList()
            "browser_show" -> { show(ctx); "The browser is on screen." }
            "browser_vpn" -> vpn(ctx, a)
            else -> "Unknown browser tool."
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Browser error: ${e.message}" }

    private suspend fun vpn(ctx: Context, a: JSONObject): String {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { BrowserEngine.init(ctx) }
        return when (a.optString("action")) {
            "connect" -> {
                val q = a.optString("country")
                val loc = BrowserVpn.find(q) ?: return "No VPN location for \u201c$q\u201d. Available: Blaze Free (Cloudflare WARP, exits near the user)" +
                    BrowserVpn.locations.joinToString("") { ", " + it.label } + ". Other countries need a WireGuard config imported in the browser (⋯ › VPN & privacy)."
                BrowserVpn.connectNow(loc.id)
            }
            "disconnect" -> { BrowserVpn.disconnect(); "VPN off. The browser uses the real connection again." }
            else -> BrowserVpn.statusText()
        }
    }
}

/**
 * v0.30.5: what Blaze is doing in its background browser, for the Home tab's live preview window. Set on each browser
 * tool call; the preview shows while a chat is working and a browser step happened in the last [WINDOW_MS].
 */
object BrowserLive {
    data class Step(val what: String, val at: Long)
    const val WINDOW_MS = 90_000L
    private val _step = kotlinx.coroutines.flow.MutableStateFlow<Step?>(null)
    val step: kotlinx.coroutines.flow.StateFlow<Step?> = _step
    fun mark(what: String, now: Long = System.currentTimeMillis()) { _step.value = Step(what, now) }
    /** Pure (BrowserLiveTest): show the preview? [busy] = a chat is working right now. */
    fun showing(s: Step?, busy: Boolean, now: Long): Boolean = busy && s != null && now - s.at in 0..WINDOW_MS
}
