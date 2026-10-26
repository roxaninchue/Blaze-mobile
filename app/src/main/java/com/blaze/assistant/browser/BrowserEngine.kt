package com.blaze.assistant.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/**
 * Blaze's own browser. Tabs are WebViews that live here, not in the screen, so Blaze can open,
 * read, click and type on pages in the background, and the Browser screen just shows the current tab.
 */
object BrowserEngine {
    /**
     * v0.45.25 RAM diet: a tab is a small record; its WebView ([live]) exists only while the tab is on screen or Blaze
     * is using it. [web] gives the WebView, bringing a sleeping tab back (its back/forward state restored) on first use,
     * so every caller works as before. Code that only looks (lists, grids, settings changes) uses [live].
     */
    class Tab(val id: Int, val ctxWrap: MutableContextWrapper, val isPrivate: Boolean = false) {
        private var liveState by mutableStateOf<WebView?>(null)
        /** The WebView if the tab is awake, else null (never wakes it). Observable, so the screen re-hosts a new one. */
        var live: WebView?
            get() = liveState
            internal set(v) { liveState = v }
        /** The tab's WebView, waking the tab if it sleeps. Main thread. */
        val web: WebView get() = liveState ?: BrowserEngine.wakeUp(this)
        var title by mutableStateOf(if (isPrivate) "Private tab" else "New tab")
        var icon by mutableStateOf<ImageBitmap?>(null)
        /** A small (160 px) picture of the page for the tab grid, taken when you leave the tab. */
        var thumb by mutableStateOf<ImageBitmap?>(null)
        /** v0.45.25: the picture was moved to disk (normal tabs only); the grid decodes it while it shows. */
        var thumbOnDisk by mutableStateOf(false)
        internal var mask: ScriptHandler? = null
        internal var maskJs: String? = null
        var url by mutableStateOf("")
        var progress by mutableIntStateOf(100)
        /** v0.45.25 Sky: trackers refused on this tab, when it was last on screen, and whether Blaze has driven it. */
        var blocked by mutableIntStateOf(0)
        var lastSeen by mutableStateOf(System.currentTimeMillis())
        var agentUsed by mutableStateOf(false)
        /** v0.29.1 load feed: what the page is pulling right now (see [LoadFeed]). Plain fields: nothing on screen
         *  reads them live, so they no longer invalidate Compose on every resource (v0.45.25). */
        @Volatile var pulls = 0
        @Volatile var pulling = ""
        @Volatile var loadStart = 0L
        @Volatile var lastPull = 0L
        var canBack by mutableStateOf(false)
        var canForward by mutableStateOf(false)
        @Volatile var loaded: CompletableDeferred<Unit> = CompletableDeferred(Unit)
        /** A JS alert/confirm the page showed while nobody was looking (answered for it, told to Blaze on the next read). */
        @Volatile var lastDialog: String? = null
        /** v0.29.7: a tab restored after a restart that hasn't loaded yet (loads when it's shown). */
        internal var pending: TabStore.Snap? = null
        /** v0.29.7: saved accounts for this page's sign-in form (the "Use a saved password" bar). */
        var fillAccounts by mutableStateOf<List<BrowserLogins.Login>>(emptyList())
    }

    /** v0.29.7: a sign-in the user just sent, waiting for "Save password?" (memory only, never written unless they tap Save). */
    data class LoginOffer(val tabId: Int, val host: String, val site: String, val user: String, val pass: String, val update: Boolean)
    var loginOffer by mutableStateOf<LoginOffer?>(null)
    private var offerWaiting: LoginOffer? = null

    /** Set when a page's renderer died (Android closed it for memory, or it crashed) and the tab was reopened. */
    @Volatile var lastCrash: String? = null

    val tabs = mutableStateListOf<Tab>()
    private var currentIndex by mutableIntStateOf(0)
    /**
     * The tab on screen. A sleeping tab wakes when the screen (or Blaze) first uses its WebView; the tab you left
     * gets a grid picture now and goes to sleep a moment later (v0.45.25).
     */
    var current: Int
        get() = currentIndex
        set(v) {
            val old = tabs.getOrNull(currentIndex)
            if (old != null && v != currentIndex) { snapshot(old); old.lastSeen = System.currentTimeMillis() }
            tabs.getOrNull(v)?.lastSeen = System.currentTimeMillis()
            currentIndex = v; scheduleSave(); scheduleDiet()
        }
    var desktop by mutableStateOf(false)
    var fullView by mutableStateOf<View?>(null)
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    /** The Browser screen sets this so file pickers on pages work. */
    var filePicker: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    private var nextId = 1
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun tab(): Tab? = tabs.getOrNull(current)
    fun appContext(): Context = app

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        BrowserLogins.dir = app.filesDir; TabStore.dir = app.filesDir; SavedPages.root = app.filesDir
        BrowserVpn.onMaskChanged = { remask() }
        BrowserVpn.init(app)
        TrackerBlock.init(app)
        main.post { clearPrivateData() }   // leftovers if Blaze was killed with private tabs open
        io.execute { TabStore.clearStateFiles(); clearThumbFiles() }   // last run's sleeping-tab files (ordered before any new one)
        restoreTabs()
        // v0.45.25: Android's memory warnings put every tab nobody is looking at to sleep.
        try { app.registerComponentCallbacks(trimmer) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: registerComponentCallbacks", e) }
    }

    // ---------- v0.45.25 RAM diet: sleeping tabs ----------
    /** True while the Browser screen is started (set by BrowserActivity). */
    @Volatile var uiVisible = false
        private set
    private var agentTabId: Int? = null
    @Volatile private var agentAt = 0L
    private val dietTask = Runnable { diet(pressed = false) }

    /** The Browser screen came up or went away. Hidden: the visible tab is paused, and sleeps once Blaze is idle. */
    fun setUiVisible(on: Boolean) {
        uiVisible = on
        if (!::app.isInitialized) return
        if (on) { tab()?.live?.onResume(); scheduleDiet() }
        else {
            tab()?.live?.takeIf { !agentRecent(TabDiet.AGENT_KEEP_PRESSED_MS) }?.onPause()
            scheduleDiet()
            // When Blaze drove a tab and stops, it sleeps after the keep window (checked again then).
            main.postDelayed(dietTask, TabDiet.AGENT_KEEP_MS + 1_000)
        }
    }

    /** Blaze is using [t] (a tool call): keep it awake and its renderer important. */
    private fun markAgent(t: Tab) {
        agentTabId = t.id; agentAt = System.currentTimeMillis(); t.agentUsed = true
        t.live?.let { w ->
            w.onResume()
            try { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: setRendererPriorityPolicy", e) }
        }
        main.removeCallbacks(dietTask); main.postDelayed(dietTask, TabDiet.AGENT_KEEP_MS + 1_000)
    }

    /** The tab Blaze drove in the last [ms] (the Sky "Blaze reading in background" chip), else null. */
    fun agentTab(ms: Long = BrowserLive.WINDOW_MS): Tab? = tabs.firstOrNull { it.id == agentTabId }?.takeIf { agentRecent(ms) }
    /** How many tabs hold a WebView right now. */
    fun liveCount(): Int = tabs.count { it.live != null }
    fun switchTo(t: Tab) { val i = tabs.indexOf(t); if (i >= 0) current = i }

    private fun agentRecent(ms: Long) = agentTabId != null && System.currentTimeMillis() - agentAt in 0 until ms

    /** Puts tabs to sleep a moment after something changed (a switch, a close, the screen hiding). */
    fun scheduleDiet(ms: Long = TabDiet.SLEEP_DELAY_MS) {
        if (!::app.isInitialized) return
        main.removeCallbacks(dietTask); main.postDelayed(dietTask, ms)
    }

    /** Every awake tab that [TabDiet.keepLive] doesn't keep goes to sleep. Main thread. */
    fun diet(pressed: Boolean) {
        if (!::app.isInitialized) return
        val awake = tabs.filter { it.live != null }
        if (awake.isEmpty()) return
        val attached = awake.filter { it.live?.isAttachedToWindow == true }.map { it.id }.toSet()
        val keep = TabDiet.keepLive(tabs.map { it.id }, tab()?.id, uiVisible, attached, agentTabId,
            System.currentTimeMillis() - agentAt, fullScreenId = if (fullView != null) tab()?.id else null, pressed = pressed)
        var slept = 0
        awake.forEach { t ->
            if (t.id in keep) {
                // Not on screen and not driven by Blaze lately: the renderer may be reclaimed first (the page reloads if so).
                if (t.id !in attached && !agentRecent(TabDiet.AGENT_KEEP_PRESSED_MS)) try { t.live?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: setRendererPriorityPolicy", e) }
            } else { hibernate(t); slept++ }
        }
        if (slept > 0) scheduleSave()
    }

    private val trimmer = object : android.content.ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            main.post {
                when {
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> { diet(pressed = true); dropThumbs() }
                    level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> { diet(pressed = false); dropThumbs() }
                    else -> diet(pressed = false)
                }
            }
        }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        override fun onLowMemory() { main.post { diet(pressed = true); dropThumbs() } }
    }

    /**
     * Puts a tab to sleep: its back/forward state is saved (to disk for normal tabs, memory only for private ones),
     * its grid picture goes to disk (normal tabs), and its WebView is destroyed. [web] brings it back. Main thread.
     */
    @Suppress("DEPRECATION")
    internal fun hibernate(t: Tab) {
        val w = t.live ?: return
        val snap = try { snapOf(t) } catch (_: Throwable) { TabStore.Snap(t.url, t.title) }
        t.pending = snap.takeIf { it.url.isNotBlank() }
        if (!t.isPrivate && snap.state.isNotEmpty()) TabStore.stateFileFor(t.id)?.let { f ->
            io.execute {
                TabStore.writeState(f, snap.state)
                // Swap the in-memory state for the file, unless the tab woke or changed meanwhile.
                main.post { if (t.pending === snap && f.exists()) t.pending = snap.copy(state = emptyMap(), stateFile = f) }
            }
        }
        thumbToDisk(t)
        t.progress = 100; if (!t.loaded.isCompleted) t.loaded.complete(Unit)
        t.mask = null; t.maskJs = null
        t.live = null
        destroyWeb(w)
    }

    private fun destroyWeb(w: WebView) {
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            w.setOnScrollChangeListener(null)
            w.stopLoading()
            w.setDownloadListener(null)
            w.webChromeClient = null
            w.removeAllViews()
            w.destroy()
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: destroyWeb", e) }
    }

    /** Creates the WebView of a sleeping (or never-shown) tab and restores its page. Called through [Tab.web]. */
    internal fun wakeUp(t: Tab): WebView {
        t.live?.let { return it }
        val w = buildWeb(t)
        if (t.pending != null) wake(t)
        if (!uiVisible) scheduleDiet()
        return w
    }

    // ---------- v0.45.25 tab pictures: tiny, and on disk for sleeping normal tabs ----------
    private fun thumbDir() = File(app.cacheDir, "tab_thumbs")
    fun thumbFile(t: Tab): File? = if (t.isPrivate || !::app.isInitialized) null else File(thumbDir(), "${t.id}.webp")
    private fun clearThumbFiles() { try { thumbDir().listFiles()?.forEach { it.delete() } } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: clearThumbFiles", e) } }

    /** A normal tab's picture goes to disk as a small WEBP and leaves RAM; the grid decodes it while it shows. */
    private fun thumbToDisk(t: Tab) {
        val th = t.thumb ?: return
        val f = thumbFile(t) ?: return   // private tabs keep theirs in memory only (tiny), never on disk
        val bmp = try { th.asAndroidBitmap() } catch (_: Throwable) { return }
        io.execute {
            val ok = try {
                f.parentFile?.mkdirs()
                @Suppress("DEPRECATION")
                val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                f.outputStream().use { bmp.compress(fmt, 60, it) }
            } catch (_: Throwable) { false }
            if (ok) main.post { if (t.thumb === th) { t.thumbOnDisk = true; t.thumb = null } }
        }
    }

    /** Memory warning: every picture that isn't the visible tab's goes to disk (private ones are dropped). */
    fun dropThumbs() {
        val cur = tab()
        tabs.forEach { t -> if (t !== cur && t.thumb != null) { if (t.isPrivate) t.thumb = null else thumbToDisk(t) } }
    }

    // ---------- v0.29.7 tabs that survive a restart ----------
    private var restored = false
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { saveTabsNow() }

    /** Saves the tab list a moment after something changed (page loaded, tab opened / closed / switched). */
    fun scheduleSave() { if (!restored) return; main.removeCallbacks(saveTask); main.postDelayed(saveTask, 1500) }

    /** Writes the normal tabs (URL, title, order, back/forward state) and flushes cookies so sign-ins survive a kill. */
    fun saveTabsNow() {
        if (!restored || !::app.isInitialized) return
        main.removeCallbacks(saveTask)
        try {
            val snaps = tabs.filter { !it.isPrivate }.map { snapOf(it) }   // sleeping tabs' state is read from disk on the writer thread
            val saved = TabStore.Saved(snaps, TabStore.activeIndex(tabs.map { it.isPrivate }, current))
            io.execute { TabStore.write(saved) }   // one writer thread, in order
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("Browser tabs not saved", e) }
        flushCookies()
    }

    fun flushCookies() { try { CookieManager.getInstance().flush() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: CookieManager.getInstance.flush", e) } }

    @Suppress("DEPRECATION")
    private fun snapOf(t: Tab): TabStore.Snap {
        t.pending?.let { return it.copy(title = t.title) }
        val st = HashMap<String, String>()
        val w = t.live
        if (w != null) try {
            val b = android.os.Bundle()
            w.saveState(b)
            for (k in b.keySet()) when (val v = b.get(k)) {
                is ByteArray -> st[k] = "b:" + java.util.Base64.getEncoder().encodeToString(v)
                is String -> st[k] = "s:$v"
                else -> {}
            }
        } catch (_: Throwable) { st.clear() }
        val url = t.url.takeIf { it.isNotBlank() && it != "about:blank" } ?: ""
        return TabStore.Snap(url, if (url.isEmpty()) "" else t.title, if (url.isEmpty()) emptyMap() else st)
    }

    /** Reopens last session's normal tabs. Only the active one loads now; the rest load when you switch to them. */
    private fun restoreTabs() {
        if (restored) return
        val s = try { TabStore.read() } catch (_: Throwable) { TabStore.Saved(emptyList(), 0) }
        try {
            val made = s.tabs.map { snap -> newTab(app, null, false, restore = snap) }
            restored = true
            // v0.45.25: their back/forward state waits on disk, not in RAM, until each tab is opened.
            made.forEach { t -> val p = t.pending ?: return@forEach; if (p.state.isEmpty()) return@forEach
                val f = TabStore.stateFileFor(t.id) ?: return@forEach
                io.execute { TabStore.writeState(f, p.state); main.post { if (t.pending === p && f.exists()) t.pending = p.copy(state = emptyMap(), stateFile = f) } } }
            made.getOrNull(s.active)?.let { current = tabs.indexOf(it) }
        } catch (e: Throwable) {
            restored = true
            com.blaze.assistant.device.CrashLog.error("Browser tabs not restored", e)
        }
    }

    /** Loads a restored tab: its back/forward list when the WebView accepts it, else just its page. */
    private fun wake(t: Tab) {
        val p = t.pending ?: return
        t.pending = null
        if (p.url.isBlank()) return
        allowFiles(t, p.url)
        val state = p.fullState()
        p.stateFile?.let { f -> io.execute { f.delete() } }
        if (state.isNotEmpty()) {
            try {
                val b = android.os.Bundle()
                state.forEach { (k, v) ->
                    if (v.startsWith("b:")) b.putByteArray(k, java.util.Base64.getDecoder().decode(v.substring(2)))
                    else if (v.startsWith("s:")) b.putString(k, v.substring(2))
                }
                val r = t.web.restoreState(b)
                if (r != null && r.size > 0) { t.canBack = t.web.canGoBack(); t.canForward = t.web.canGoForward(); if (t.loaded.isCompleted) t.loaded = CompletableDeferred(); return }
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: line", e) }
        }
        load(t, p.url)
    }

    /** Saved pages are file:// archives; only tabs that open one may read files (and only our own folder is ever opened). */
    // v0.45.0: file access follows the page: on for one of our saved pages, off again for anything else
    // (it used to stay on after the tab left a saved page for a website).
    private fun allowFiles(t: Tab, url: String) { val on = fileAccessFor(url); if (t.web.settings.allowFileAccess != on) t.web.settings.allowFileAccess = on }

    private fun fileAccessFor(url: String?): Boolean = SavedPages.fileAccessFor(url)

    /** Back / forward with file access set for the page being returned to (a saved page needs it before it loads). */
    fun back(t: Tab): Boolean { if (!t.web.canGoBack()) return false; historyUrl(t, -1)?.let { allowFiles(t, it) }; t.web.goBack(); return true }
    fun forward(t: Tab): Boolean { if (!t.web.canGoForward()) return false; historyUrl(t, 1)?.let { allowFiles(t, it) }; t.web.goForward(); return true }
    private fun historyUrl(t: Tab, step: Int): String? = try {
        val l = t.web.copyBackForwardList(); l.getItemAtIndex(l.currentIndex + step)?.url
    } catch (_: Throwable) { null }

    // ---------- private tabs ----------
    /** Private tabs get their own WebView profile: separate cookies, storage and cache, wiped when the last one closes. */
    private const val PRIVATE_PROFILE = "blaze-private"
    val privateIsolated: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    fun privateTabs() = tabs.filter { it.isPrivate }
    fun normalTabs() = tabs.filter { !it.isPrivate }

    private fun clearPrivateData() {
        if (!privateIsolated) return
        try {
            val p = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val p ProfileStore.getInstance.getOrCrea", e) }
    }

    // ---------- location masking ----------
    private fun shouldMask(t: Tab) = BrowserVpn.maskLocation && (t.isPrivate || BrowserVpn.on)

    private fun installMask(t: Tab) {
        val w = t.live ?: return
        try { t.mask?.remove() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: t.mask.remove", e) }
        t.mask = null; t.maskJs = null
        if (!shouldMask(t)) return
        val js = LocationMask.script(BrowserVpn.maskCountry())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { t.mask = WebViewCompat.addDocumentStartJavaScript(w, js, setOf("*")); return } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: t.mask WebViewCompat.addDocumentStartJav", e) }
        }
        t.maskJs = js   // older WebView: injected as each page starts (best effort)
    }

    /** Re-applies masking after the VPN or the setting changed; reloads the visible page so it takes effect. */
    fun remask() {
        tabs.forEach { if (it.live != null) installMask(it) }   // sleeping tabs get the mask when they wake
        tab()?.let { t -> t.live?.let { if (t.url.startsWith("http")) it.reload() } }
    }

    /** Takes a small picture of the tab for the tab grid (only works while it's on screen). */
    // v0.45.25: 160 px wide and card-shaped (about 65 KB) instead of 35 % of the whole screen (about 580 KB a tab).
    fun snapshot(t: Tab? = tab()) {
        val w = t?.live ?: return
        if (w.width <= 0 || w.height <= 0 || !w.isAttachedToWindow) return
        try {
            val (tw, th) = TabDiet.thumbSize(w.width, w.height)
            if (tw <= 0) return
            val sc = tw.toFloat() / w.width
            val b = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
            val c = Canvas(b); c.scale(sc, sc); c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = b.asImageBitmap(); t.thumbOnDisk = false
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: snapshot", e) }
    }

    /** v0.33.3: a transparent 84px spacer at the end of a scrollable page (once), so the floating toolbar never hides its end. */
    const val BOTTOM_PAD_JS = "(function(){try{var b=document.body;if(!b||document.getElementById('__blaze_pad'))return;" +
        "if(document.documentElement.scrollHeight<=window.innerHeight+4)return;var d=document.createElement('div');d.id='__blaze_pad';" +
        "d.setAttribute('aria-hidden','true');d.style.cssText='display:block;height:84px;width:1px;clear:both;pointer-events:none;background:transparent';b.appendChild(d);}catch(e){}})()"

    // ---------- tabs ----------
    /**
     * A new tab. v0.45.25: a restored tab, or one opened in the background ([background]), is only a record until it is
     * shown or used; a foreground tab gets its WebView now.
     */
    fun newTab(ctx: Context, url: String? = null, isPrivate: Boolean = false, restore: TabStore.Snap? = null, background: Boolean = false): Tab {
        init(ctx)
        if (isPrivate && !privateIsolated) toast("Private tabs share cookies with normal tabs on this phone. Update Android System WebView to keep them apart.")
        val t = Tab(nextId++, MutableContextWrapper(currentCtx ?: app), isPrivate)
        if (restore != null && restore.url.isNotBlank()) {
            t.url = restore.url; t.title = restore.title.ifBlank { display(restore.url) }; t.pending = restore
        } else if (background && !url.isNullOrBlank()) {
            val u = toUrl(url)
            t.url = u; t.title = display(u); t.pending = TabStore.Snap(u, "")
        }
        tabs.add(t)
        if (t.pending != null && (restore != null || background)) { if (restore != null) currentIndex = tabs.lastIndex; scheduleSave(); return t }
        current = tabs.lastIndex
        if (!url.isNullOrBlank()) load(t, url)
        return t
    }

    /** Opens [url] in a new tab behind the current one: it loads when you switch to it (v0.45.25). */
    fun newBackgroundTab(ctx: Context, url: String, isPrivate: Boolean = false): Tab = newTab(ctx, url, isPrivate, background = true)

    /** The context new WebViews get for their dialogs (the Activity while the browser is open). */
    private var currentCtx: Context? = null

    /** Makes a tab's WebView with every client, script and setting a tab needs. Main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb(t: Tab): WebView {
        val isPrivate = t.isPrivate
        val w = WebView(t.ctxWrap)
        // The profile must be set before the WebView is used for anything else.
        if (isPrivate && privateIsolated) { try { WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: WebViewCompat.setProfilew PRIVATE_PROFIL", e) } }
        t.live = w
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            userAgentString = ua(defaultUa(w), desktop)
            if (isPrivate) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // v0.45.25: no offscreen pre-raster (the default, made explicit) and a renderer Android may reclaim first while the
        // tab isn't on screen; Blaze's own tab is raised to IMPORTANT while it drives it (markAgent).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) try { androidx.webkit.WebSettingsCompat.setOffscreenPreRaster(w.settings, false) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: setOffscreenPreRaster", e) }
        try { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: setRendererPriorityPolicy", e) }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        layoutOffscreen(w)
        var captureLate = false   // set below: inject the sign-in watcher after each load (old WebView)
        w.webViewClient = object : WebViewClient() {
            // v0.20.0: a dead page renderer (Android reclaims it on a 3 GB phone, or it crashes) used to take the
            // whole app down with it, and Samsung then switches Blaze's phone control off. Reopen the tab instead.
            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                val crashed = try { d.didCrash() } catch (_: Throwable) { false }
                com.blaze.assistant.device.CrashLog.write("Browser renderer ${if (crashed) "crashed" else "was closed by Android"} on ${t.url.take(80)}; tab reopened")
                main.post { reopen(t, v) }
                return true
            }
            // v0.45.25: Block ads & trackers (off unless the user turns it on): known ad / analytics sub-resources get an empty answer.
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (!TrackerBlock.enabled || r.isForMainFrame || !TrackerBlock.blocks(r.url?.host)) return null
                main.post { t.blocked++; TrackerBlock.counted() }
                return TrackerBlock.empty()
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                // v0.33.3: intent://, other apps' schemes (market:, mailto:, tel:, hark:…) and cross-site https App Links
                // (link taps, server redirects, JS / meta-refresh redirects) open the app that handles them; a page with
                // no app for it just keeps loading (AppLinks / RedirectRules).
                // An agent driving a background tab stays in the page: only the tab on screen hands https links to apps.
                val handled = AppLinks.handle(app, v, r, t.url.ifBlank { v.url }, appLinks = visible(t))
                if (!handled && r.isForMainFrame) allowFiles(t, r.url?.toString().orEmpty())
                return handled
            }
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (v !== t.live) return   // a WebView that's going away
                if (!t.url.equals(url, true)) t.blocked = 0
                t.url = url.orEmpty(); t.progress = 5
                // A page that got here without shouldOverrideUrlLoading (redirect, history) never keeps file access it shouldn't.
                if (!fileAccessFor(url)) allowFiles(t, url.orEmpty())
                t.pulls = 0; t.pulling = ""; t.loadStart = System.currentTimeMillis(); t.lastPull = t.loadStart
                if (t.loaded.isCompleted) t.loaded = CompletableDeferred()
                t.maskJs?.let { v.evaluateJavascript(it, null) }
                t.fillAccounts = emptyList()
            }
            override fun onLoadResource(v: WebView, url: String?) {
                if (t.progress >= 100 || url.isNullOrBlank() || url.startsWith("data:")) return
                t.pulls++
                // v0.45.25: the label (a string built per resource) at most 4 times a second.
                val now = System.currentTimeMillis()
                if (TabDiet.feedDue(now, t.lastPull)) { t.pulling = LoadFeed.label(url); t.lastPull = now }
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (v !== t.live) return
                t.url = url.orEmpty(); t.progress = 100; t.canBack = v.canGoBack(); t.canForward = v.canGoForward()
                t.loaded.complete(Unit)
                // v0.33.3: room under the page for the floating toolbar, so the last lines can scroll above it.
                if (url.orEmpty().startsWith("http")) v.evaluateJavascript(BOTTOM_PAD_JS, null)
                if (!t.isPrivate && !url.isNullOrBlank() && url != "about:blank" && !SavedPages.isSavedUrl(url)) BrowserData.addHistory(app, v.title ?: url, url)
                if (!t.isPrivate) {
                    if (captureLate) v.evaluateJavascript(BrowserLogins.CAPTURE_JS, null)
                    offerSoon(t, 700)
                    scheduleSave(); flushCookies()
                }
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                t.url = url.orEmpty(); t.canBack = v.canGoBack(); t.canForward = v.canGoForward()
                if (!t.isPrivate) { offerSoon(t, 1200); scheduleSave() }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { if (v === t.live) TabDiet.progressStep(p).let { if (it != t.progress) t.progress = it } }
            // In the background there's no Activity to show a page's dialog: answer it here and tell Blaze.
            override fun onJsAlert(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "alert: " + message.orEmpty().take(300); r.confirm(); return true
            }
            override fun onJsConfirm(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "confirm (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
            }
            override fun onJsPrompt(v: WebView, url: String?, message: String?, def: String?, r: android.webkit.JsPromptResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "prompt (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
            }
            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                r.confirm(); return true
            }
            override fun onReceivedTitle(v: WebView, title: String?) { t.title = title?.ifBlank { null } ?: t.url }
            // v0.45.25: kept at 64 px at most (sites hand over 192 px icons: 147 KB each, for a 16 dp dot).
            override fun onReceivedIcon(v: WebView, icon: Bitmap?) { t.icon = icon?.let { smallIcon(it) }?.asImageBitmap() }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean =
                filePicker?.invoke(cb, p) ?: run { cb.onReceiveValue(null); false }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { fullView = view; fullCallback = callback }
            override fun onHideCustomView() { fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null }
        }
        w.setDownloadListener { dl, userAgent, disposition, mime, _ -> download(dl, userAgent, disposition, mime) }
        installMask(t)
        // v0.29.7 saved passwords: normal tabs only. Private tabs never get the capture script or the bridge.
        captureLate = !isPrivate && installLoginBridge(t)
        return w
    }

    private fun smallIcon(b: Bitmap): Bitmap {
        val (w, h) = TabDiet.iconSize(b.width, b.height)
        return if (w == b.width && h == b.height) b else try { Bitmap.createScaledBitmap(b, w, h, true) } catch (_: Throwable) { b }
    }

    private fun display(u: String) = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    // ---------- v0.29.7 saved passwords ----------
    /**
     * Listens for the page's sign-in forms (origin-checked WebMessageListener, main frame only). Returns true when the
     * capture script has to be injected after each load (WebView too old for document-start scripts).
     */
    private fun installLoginBridge(t: Tab): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        try {
            WebViewCompat.addWebMessageListener(t.live ?: return false, BrowserLogins.BRIDGE, setOf("*")) { _, msg, origin, mainFrame, _ ->
                if (mainFrame) onLoginMessage(t, msg.data.orEmpty(), origin)
            }
        } catch (_: Throwable) { return false }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(t.live ?: return false, BrowserLogins.CAPTURE_JS, setOf("*")); return false } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: WebViewCompat.addDocumentStartJavaScript", e) }
        }
        return true
    }

    private fun onLoginMessage(t: Tab, data: String, origin: Uri) {
        if (t.isPrivate || !tabs.contains(t)) return
        val originUrl = origin.toString()
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed("$originUrl/")) return   // https (or the LAN) only
        val host = origin.host?.lowercase()?.removePrefix("www.").orEmpty()
        if (host.isEmpty() || !SiteDomain.sameSite(host, SiteDomain.hostOf(t.url).ifEmpty { host })) return
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        when (o.optString("t")) {
            "form" -> {
                val acc = try { BrowserLogins.forHost(host) } catch (_: Throwable) { emptyList() }
                if (acc.isNotEmpty()) t.fillAccounts = acc
            }
            "submit" -> {
                val user = o.optString("u").trim().take(200); val pass = o.optString("p").take(500)
                if (pass.isEmpty()) return
                SecureInput.hide(pass)   // never echoed to the agent in page text
                if (BrowserLogins.isNever(host)) return
                when (val d = BrowserLogins.decide(host, user, pass)) {
                    is BrowserLogins.Decision.Same -> BrowserLogins.touch(d.id)
                    is BrowserLogins.Decision.Save, is BrowserLogins.Decision.Update -> {
                        offerWaiting = LoginOffer(t.id, host, SiteDomain.registrable(host), user, pass, d is BrowserLogins.Decision.Update)
                        offerSoon(t, 3500)   // shown after the next page (or in 3.5 s on single-page sign-ins)
                    }
                }
            }
        }
    }

    private fun offerSoon(t: Tab, ms: Long) {
        if (offerWaiting?.tabId != t.id) return
        main.postDelayed({ offerWaiting?.takeIf { it.tabId == t.id }?.let { loginOffer = it; offerWaiting = null } }, ms)
    }

    /** "Save" / "Update" on the prompt. */
    fun acceptLoginOffer() {
        val o = loginOffer ?: return; loginOffer = null
        val ok = try { BrowserLogins.save(o.host, o.user, o.pass) != null } catch (_: Throwable) { false }
        toast(if (!ok) "Couldn't save the password." else if (o.update) "Password updated" else "Password saved for ${o.site}")
    }
    fun neverForSite() { val o = loginOffer ?: return; loginOffer = null; BrowserLogins.addNever(o.host); toast("Blaze won't offer to save passwords on ${o.site}.") }
    fun dismissLoginOffer() { loginOffer = null }

    /** Fills a saved account into the page (never submits). https (or LAN) and the same site only. */
    fun fillLogin(t: Tab, l: BrowserLogins.Login) {
        t.fillAccounts = emptyList()
        if (t.isPrivate) return
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(t.url)) { toast("This page isn't secure (http), so Blaze won't fill a password here."); return }
        if (!SiteDomain.sameSite(l.host, SiteDomain.hostOf(t.url))) return
        val pass = l.password
        if (pass.isEmpty()) { toast("This password can't be read on this phone. Save it again."); return }
        SecureInput.hide(pass)
        t.web.evaluateJavascript(BrowserLogins.fillJs(l.user, pass), null)
        try { BrowserLogins.touch(l.id) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: BrowserLogins.touchl.id", e) }
    }

    // ---------- v0.29.7 saved pages ----------
    /** Saves the tab's page as a web archive plus a text copy. [done] gets a message for the user (main thread). */
    fun savePage(t: Tab, done: (String) -> Unit) {
        val dir = SavedPages.dir() ?: return done("Couldn't save this page.")
        val url = t.url; val title = t.title.ifBlank { url }
        if (!url.startsWith("http")) return done("Only web pages can be saved.")
        try { dir.mkdirs() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: dir.mkdirs", e) }
        val id = SavedPages.newId(System.currentTimeMillis(), SavedPages.list().map { it.id })
        val f = File(dir, SavedPages.archiveName(id))
        try {
            t.web.saveWebArchive(f.absolutePath, false) { path ->
                if (path == null || !f.exists() || f.length() == 0L) { f.delete(); done("Couldn't save this page."); return@saveWebArchive }
                t.web.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { r ->
                    val body = try { JSONTokener(r ?: "\"\"").nextValue() as? String } catch (_: Exception) { null }.orEmpty()
                    Thread {
                        var hasText = false
                        if (body.isNotBlank()) try {
                            val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())
                            File(dir, SavedPages.textName(id)).writeText(SavedPages.textCopy(title, url, whenText, SecureInput.scrub(body))); hasText = true
                        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val whenText java.text.DateFormat.getDat", e) }
                        SavedPages.add(SavedPages.Page(id, title, url, System.currentTimeMillis(), f.length(), hasText))
                        main.post { done("Saved for offline: ${title.take(60)}") }
                    }.start()
                }
            }
        } catch (e: Throwable) { done("Couldn't save this page: ${e.message}") }
    }

    /** Opens a saved page in a new normal tab, from the file on the phone (works offline). */
    fun openSaved(ctx: Context, p: SavedPages.Page): Boolean {
        val f = SavedPages.archive(p.id)?.takeIf { it.exists() } ?: return false
        val t = newTab(ctx)
        t.web.settings.allowFileAccess = true
        t.title = p.title
        load(t, Uri.fromFile(f).toString())
        return true
    }

    /** True while the Browser screen shows this tab (an Activity is attached to its dialogs). */
    private fun visible(t: Tab) = t.ctxWrap.baseContext !== app && t.live?.isAttachedToWindow == true

    /**
     * The background browser: a WebView that isn't on screen still needs a size, or pages lay out at 0×0
     * and every element looks invisible to browser_read. Give it the phone's screen size, offscreen.
     */
    fun layoutOffscreen(w: WebView) {
        if (w.isAttachedToWindow) return
        try {
            val dm = app.resources.displayMetrics
            val wp = dm.widthPixels.coerceAtLeast(360); val hp = dm.heightPixels.coerceAtLeast(640)
            w.measure(View.MeasureSpec.makeMeasureSpec(wp, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hp, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, wp, hp)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val dm app.resources.displayMetrics", e) }
    }

    /**
     * A tab whose renderer died: its dead WebView goes, and the tab sleeps on its page (v0.45.25). The visible tab gets a
     * fresh WebView straight away (the screen re-hosts it); any other tab reloads when it is next shown or used.
     */
    private fun reopen(t: Tab, dead: WebView) {
        if (!tabs.contains(t) || t.live !== dead) return
        val url = t.url.takeIf { it.startsWith("http") || SavedPages.isSavedUrl(it) }
        t.live = null
        t.mask = null; t.maskJs = null
        destroyWeb(dead)
        t.pending = url?.let { TabStore.Snap(it, t.title) }
        t.progress = 100; if (!t.loaded.isCompleted) t.loaded.complete(Unit)
        if (t === tab() && (uiVisible || agentRecent(TabDiet.AGENT_KEEP_PRESSED_MS))) wakeUp(t)
        lastCrash = "The page ${url?.take(80) ?: ""} stopped (Android closed it to save memory) and was reopened; anything typed there was lost."
    }

    fun exitFull() { fullCallback?.onCustomViewHidden(); fullView = null; fullCallback = null }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        t.live?.let { t.live = null; destroyWeb(it) }
        t.pending?.stateFile?.let { f -> io.execute { f.delete() } }
        thumbFile(t)?.let { f -> io.execute { f.delete() } }
        t.thumb = null; t.icon = null
        if (agentTabId == t.id) agentTabId = null
        tabs.removeAt(i)
        if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0)
        if (t.isPrivate && tabs.none { it.isPrivate }) clearPrivateData()
        if (loginOffer?.tabId == t.id) loginOffer = null
        if (!t.isPrivate) scheduleSave()
    }

    /** Closes every tab on one side of the tab grid. */
    fun closeAll(isPrivate: Boolean) { for (i in tabs.indices.reversed()) if (tabs[i].isPrivate == isPrivate) close(i) }

    fun closeAll() { while (tabs.isNotEmpty()) close(tabs.lastIndex) }

    /** Point a tab's dialogs (alerts, pickers) at the visible Activity, or back at the app. */
    fun attach(ctx: Context?) { currentCtx = ctx; tabs.forEach { it.ctxWrap.baseContext = ctx ?: app } }

    private fun defaultUa(w: WebView) = w.settings.userAgentString
    private fun ua(base: String, desk: Boolean) =
        if (desk) base.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "").replace("; wv", "") else base.replace("; wv", "")

    fun useDesktop(on: Boolean) {
        desktop = on
        // Sleeping tabs pick the setting up when they wake (buildWeb reads [desktop]).
        tabs.forEach { t -> t.live?.settings?.let { it.userAgentString = ua(WebSettings.getDefaultUserAgent(app), on); it.useWideViewPort = true } }
        tab()?.live?.reload()
    }

    /** Text from the address bar → a URL (or a web search). */
    fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s) || s.startsWith("about:")) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        if (!s.contains(' ') && s.startsWith("localhost")) return "http://$s"
        return "https://www.google.com/search?q=" + Uri.encode(s)
    }

    fun load(t: Tab, input: String) {
        val u = toUrl(input)
        t.pending = null
        if (u.startsWith("file:") && !SavedPages.isSavedUrl(u)) { toast("Blaze only opens its own saved pages from the phone."); return }
        allowFiles(t, u)
        t.url = u; t.loaded = CompletableDeferred()
        t.web.loadUrl(u)
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) { toast("This download type isn't supported yet."); return }
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime).setTitle(name).setDescription("Downloading with Blaze")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            app.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
    }

    private fun toast(s: String) = main.post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() }

    // ---------- what Blaze uses ----------
    private suspend fun js(t: Tab, code: String): Any? = withContext(Dispatchers.Main) {
        markAgent(t)
        suspendCancellableCoroutine { cont ->
            t.web.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(try { JSONTokener(r ?: "null").nextValue() } catch (_: Exception) { r }) }
        }
    }

    private suspend fun waitLoad(t: Tab, ms: Long = 15_000) {
        withTimeoutOrNull(ms) { t.loaded.await() }
        kotlinx.coroutines.delay(500)   // let scripts render
    }

    private suspend fun ensureTab(ctx: Context): Tab = withContext(Dispatchers.Main) { tab() ?: newTab(ctx) }

    suspend fun open(ctx: Context, url: String, newTab: Boolean, isPrivate: Boolean = false): String {
        val t = withContext(Dispatchers.Main) {
            (if (newTab || tabs.isEmpty() || tab()?.isPrivate != isPrivate) newTab(ctx, url, isPrivate) else tab()!!.also { load(it, url) }).also { markAgent(it) }
        }
        waitLoad(t)
        settle(t, 2_000)
        return "Opened ${t.title.take(80)} (${t.url}).\n\n" + read(ctx, BrowserRead.BUDGET - 200)
    }

    // ---------- 0.45.29 browser tasks: BrowserScripts (page side) + BrowserRead (what the model reads) ----------

    /** The page as [BrowserScripts.READ] sees it (null when the page didn't answer). */
    private suspend fun readRaw(t: Tab): JSONObject? = try { js(t, BrowserScripts.READ) as? JSONObject }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    /** What changes when an action did something: the address, the title, the text on screen, the elements, the scroll, a dialog. */
    private fun sig(r: JSONObject?): String = if (r == null) "" else
        listOf(r.optString("url"), r.optString("title"), r.optString("on").hashCode(), r.optString("dialog"), r.optInt("y"),
            r.optJSONArray("items")?.let { a -> (0 until a.length()).joinToString(",") { i -> a.optJSONObject(i)?.let { "${it.optInt("id")}${it.optString("value")}${it.optBoolean("checked")}" }.orEmpty() } }.orEmpty().hashCode()).joinToString("|")

    private fun isNoChange(before: String, after: JSONObject?): Boolean = before.isNotEmpty() && sig(after) == before

    /**
     * After an action: waits for a navigation it started (up to 12 s, as before), then until the page has been quiet for
     * 500 ms, at most [maxMs] (3 s).
     */
    private suspend fun settle(t: Tab, maxMs: Long = 3_000) {
        kotlinx.coroutines.delay(150)
        if (t.progress < 100) withTimeoutOrNull(12_000) { t.loaded.await() }
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val q = (try { js(t, BrowserScripts.QUIET) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }) as? String ?: break
            val quiet = q.substringBefore('|').toLongOrNull() ?: 0L
            if (quiet >= 500 && q.endsWith("complete") && t.progress >= 100) break
            kotlinx.coroutines.delay(200)
        }
    }

    /** A short read of the page after an action, plus a line when nothing visibly changed. */
    private suspend fun after(t: Tab, before: String, done: String): String {
        val r = readRaw(t)
        val same = isNoChange(before, r)
        val sb = StringBuilder(done)
        if (same) sb.append(" The page didn't visibly change (it may need a moment: browser_wait, or the action didn't take; check before retrying).")
        sb.append("\n\n").append(if (r != null) BrowserRead.format(r, BrowserRead.AFTER, compact = true) else "Now on: ${t.title.take(80)} (${t.url})")
        notes(t, r)?.let { sb.append('\n').append(it) }
        return SecureInput.scrub(sb.toString())
    }

    /** The page, viewport first, at most [max] characters ([BrowserRead.BUDGET]). */
    suspend fun read(ctx: Context, max: Int = BrowserRead.BUDGET): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open. Use browser_open first."
        val r = readRaw(t) ?: return "Couldn't read this page (it didn't answer; it may still be loading). Try browser_wait, then browser_read."
        val sb = StringBuilder(BrowserRead.format(r, max))
        notes(t, r)?.let { sb.append('\n').append(it) }
        return SecureInput.scrub(sb.toString())
    }

    /**
     * v0.27.1: the open page for "Ask Blaze about this page", as a person sees it (title, readable text, button / link /
     * field labels, pictures and their descriptions). Field values are never read; secrets are scrubbed.
     */
    suspend fun capture(ctx: Context, max: Int = 30_000): PageAsk.Capture {
        val t = withContext(Dispatchers.Main) { tab() } ?: return PageAsk.Capture.NONE
        val r = try { js(t, ASK_JS) as? JSONObject } catch (_: Exception) { null }
            ?: return PageAsk.Capture(t.title, t.url, "")
        fun list(k: String) = r.optJSONArray(k)?.let { a -> (0 until a.length()).map { SecureInput.scrub(a.optString(it)) } } ?: emptyList()
        return PageAsk.Capture(title = r.optString("title").ifBlank { t.title }, url = r.optString("url").ifBlank { t.url },
            text = SecureInput.scrub(r.optString("text").take(max)), labels = list("labels"), images = r.optInt("imgs"),
            alts = list("alts"), password = r.optBoolean("pw"))
    }

    private fun itemLines(r: JSONObject): List<String> = r.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(BrowserRead::item) } } ?: emptyList()
    private fun textOf(r: JSONObject): String = r.optString("on") + "\n" + r.optString("below")

    /** Things Blaze should know about the page: a dialog it answered, a renderer restart, a site that refuses passwords. */
    private fun notes(t: Tab, r: JSONObject?): String? {
        val out = ArrayList<String>()
        lastCrash?.let { out += it; lastCrash = null }
        t.lastDialog?.let { out += "The page showed a dialog while in the background ($it)."; t.lastDialog = null }
        if (r != null) SignInHints.check(textOf(r), itemLines(r))?.let { out += it }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Host of the page in the current tab ("" when none). */
    fun host(): String = tab()?.url?.let { SecureInput.hostOf(it) }.orEmpty()
    fun url(): String = tab()?.url.orEmpty()

    /** True when the current page says this account can't use a password. */
    suspend fun passwordRefused(): String? {
        val t = withContext(Dispatchers.Main) { tab() } ?: return null
        val r = readRaw(t) ?: return null
        return SignInHints.check(textOf(r), itemLines(r))
    }

    /**
     * Types a value the user gave in a secure form into the page. The value comes from SecureInput,
     * never from the model, and the field is marked so browser_read never shows what's in it.
     */
    suspend fun fillSecret(f: SecureInput.Field, value: String): Boolean {
        val t = withContext(Dispatchers.Main) { tab() } ?: return false
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(t.url)) return false   // v0.28.2: never on plain http (LAN excepted)
        val n = f.target.trim().toIntOrNull()
        val label = f.target.takeIf { n == null && it.isNotBlank() }
        return js(t, BrowserScripts.fillSecret(n, label, SecureInput.autoSelector(f.kind), value)) == "ok"
    }

    /** Submits the form the secure fields are in (its submit button, else requestSubmit, else Enter). */
    suspend fun submitSecret(): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val before = sig(readRaw(t))
        js(t, """(function(){var l=document.querySelectorAll('[data-bz-secret]'); var e=l[l.length-1]; if(!e) return 'missing';
            var f=e.form; var b=f&&f.querySelector('button[type=submit],input[type=submit],button:not([type])');
            if(b){b.click();return 'ok';} if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); return 'ok'; }
            var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
            e.dispatchEvent(new KeyboardEvent('keydown',o)); e.dispatchEvent(new KeyboardEvent('keyup',o)); return 'ok'; })()""")
        settle(t)
        return after(t, before, "Submitted.")
    }

    /** v0.28.2: what [click] would press (its words), without pressing it; "" when not found. */
    suspend fun labelOf(n: Int?, text: String?): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return ""
        return (js(t, BrowserScripts.label(n?.takeIf { it >= 0 }, text)) as? String).orEmpty()
    }

    /** The words on the focused element (for an Enter / Space that would press a risky button); "" when none. */
    suspend fun focusedLabel(): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return ""
        return (js(t, "(function(){var e=document.activeElement;if(!e||e===document.body||e===document.documentElement)return '';" +
            "var f=e.form&&(e.tagName=='INPUT')?e.form.querySelector('button[type=submit],input[type=submit],button:not([type])'):null;var x=f||e;" +
            "return ((x.tagName=='INPUT'&&/submit|button/.test(x.type)?x.value:(x.innerText||''))||x.getAttribute('aria-label')||'').slice(0,80);})()") as? String).orEmpty()
    }

    /** A real click (pointer + mouse events at the element's centre); says why when it can't, and what the page shows after. */
    suspend fun click(ctx: Context, n: Int?, text: String?): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val before = sig(readRaw(t))
        val r = js(t, BrowserScripts.click(n?.takeIf { it >= 0 }, text)) as? JSONObject
        BrowserRead.problem(r, "click")?.let { return SecureInput.scrub(it) }
        settle(t)
        return after(t, before, "Clicked \u201c${r!!.optString("l").take(60)}\u201d.")
    }

    /** Types into a field and checks the value landed; [submit] presses Enter / submits its form. */
    suspend fun type(ctx: Context, n: Int?, label: String?, text0: String, submit: Boolean): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        // {{secret:name}} placeholders: swapped for the real value here, at the last moment; the model only ever saw the placeholder.
        val secret = SecureInput.hasPlaceholder(text0)
        if (secret && !com.blaze.assistant.connect.UrlSafety.autofillAllowed(t.url)) return com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED
        val text = if (!secret) text0 else SecureInput.resolve(text0, SecureInput.hostOf(t.url)).let { (v, missing) ->
            v ?: return "No value for ${missing.joinToString { SecureInput.placeholder(it) }} yet. Ask the user with browser_secure_input (it shows a secure form in the chat)." }
        val before = if (submit) sig(readRaw(t)) else ""
        val r = js(t, BrowserScripts.type(n?.takeIf { it >= 0 }, label, text, submit, secret)) as? JSONObject
        BrowserRead.problem(r, "type")?.let { return SecureInput.scrub(it) }
        val into = r!!.optString("l").take(60).let { if (it.isBlank()) "" else " into \u201c$it\u201d" }
        val shown = if (secret) "the saved value" else "\u201c${text0.take(60)}\u201d"
        if (!submit) return SecureInput.scrub("Typed $shown$into (the field shows it).")
        settle(t)
        return after(t, before, "Typed $shown$into and pressed Enter" + if (r.optBoolean("submitted")) " (form submitted)." else ".")
    }

    /** Picks an option in a dropdown: a real `<select>`, else opens the custom dropdown and clicks the matching option. */
    suspend fun select(ctx: Context, n: Int?, label: String?, option: String): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val before = sig(readRaw(t))
        val r = js(t, BrowserScripts.select(n?.takeIf { it >= 0 }, label, option)) as? JSONObject
        if (r?.optString("r") == "notselect") {
            val c = js(t, BrowserScripts.click(n?.takeIf { it >= 0 }, label)) as? JSONObject
            BrowserRead.problem(c, "open that dropdown")?.let { return SecureInput.scrub(it) }
            settle(t, 1_500)
            val p = js(t, BrowserScripts.pickOption(option)) as? JSONObject
            if (p?.optString("r") != "ok") return SecureInput.scrub("Opened \u201c${c!!.optString("l").take(60)}\u201d but no visible option matches \u201c${option.take(60)}\u201d. browser_read shows what's open now.")
            settle(t)
            return after(t, before, "Chose \u201c${p!!.optString("v").take(60)}\u201d.")
        }
        BrowserRead.problem(r, "choose that")?.let { return SecureInput.scrub(it) }
        settle(t)
        return after(t, before, "Chose \u201c${r!!.optString("v").take(60)}\u201d in \u201c${r.optString("l").take(60)}\u201d.")
    }

    /** Presses one key ([BrowserRead.KEYS]) on the focused element. */
    suspend fun key(ctx: Context, name: String): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val k = BrowserRead.KEYS.firstOrNull { it.equals(name.trim(), ignoreCase = true) } ?: return BrowserRead.problem(JSONObject().put("r", "badkey"), "press that")!!
        val before = sig(readRaw(t))
        val r = js(t, BrowserScripts.key(k)) as? JSONObject
        BrowserRead.problem(r, "press $k")?.let { return it }
        settle(t)
        val did = r!!.optString("did").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return after(t, before, "Pressed $k$did.")
    }

    /** Waits (≤ 20 s) for [selector] to show, [text] to appear (or with [gone], to go), or just for the page to settle. */
    suspend fun waitFor(ctx: Context, selector: String?, text: String?, gone: Boolean, seconds: Int): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val secs = seconds.coerceIn(1, 20)
        val what = when { !text.isNullOrBlank() -> "\u201c${text.take(60)}\u201d" + if (gone) " to go away" else ""; !selector.isNullOrBlank() -> "$selector" + if (gone) " to go away" else ""; else -> "the page to settle" }
        if (selector.isNullOrBlank() && text.isNullOrBlank()) { settle(t, secs * 1000L); return after(t, "", "Waited for the page to settle.") }
        val end = System.currentTimeMillis() + secs * 1000L
        var ok = false
        while (System.currentTimeMillis() < end) {
            val r = js(t, BrowserScripts.waitCheck(selector, text, gone)) as? String
            if (r == "badselector") return "That isn't a valid CSS selector. Wait for text instead."
            if (r == "yes") { ok = true; break }
            kotlinx.coroutines.delay(400)
        }
        if (ok) settle(t, 1_500)
        return after(t, "", if (ok) "Done waiting: $what." else "Waited ${secs}s; still waiting for $what.")
    }

    /** Finds words in the page (text and element labels) and scrolls the first hit into view. */
    suspend fun find(ctx: Context, query: String): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        if (query.isBlank()) return "Say what to find."
        val r = js(t, BrowserScripts.find(query)) as? JSONObject ?: return "Couldn't search this page (it didn't answer)."
        return SecureInput.scrub(BrowserRead.found(query, r))
    }

    /** Scrolls by most of a screen and shows what is on screen now. */
    suspend fun scroll(ctx: Context, down: Boolean): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val y0 = (js(t, "Math.round(scrollY)") as? Number)?.toInt()
        val s = js(t, BrowserScripts.scroll(down)) as? String
        settle(t, 1_000)
        val y = s?.substringBefore('|')?.toIntOrNull()
        val r = readRaw(t)
        val head = if (y != null && y == y0) (if (down) "Already at the bottom; nothing more below." else "Already at the top.") else "Scrolled ${if (down) "down" else "up"}."
        return SecureInput.scrub(head + "\n\n" + (r?.let { BrowserRead.format(it, 2_000, compact = true) } ?: "Now on: ${t.title.take(80)} (${t.url})"))
    }

    suspend fun back(ctx: Context): String = withContext(Dispatchers.Main) {
        val t = tab() ?: return@withContext "No page open."
        markAgent(t)
        if (back(t)) "Went back." else "Nothing to go back to."
    }.also { tab()?.let { waitLoad(it, 8000) } }

    fun tabList(): String = if (tabs.isEmpty()) "No tabs open." else
        tabs.mapIndexed { i, t -> "${if (i == current) "*" else " "}[$i]${if (t.isPrivate) " (private)" else ""} ${t.title.take(60)} — ${t.url}" }.joinToString("\n")

    suspend fun switchTab(i: Int): String = withContext(Dispatchers.Main) {
        if (i in tabs.indices) { current = i; "Switched to ${tabs[i].title}." } else "No tab $i."
    }

    private const val ASK_JS = """(function(){
      function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      var labels=[];
      [].slice.call(document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab]')).filter(vis).slice(0,150).forEach(function(e){
        var tag=e.tagName.toLowerCase(), field=(tag=='input'||tag=='textarea'||tag=='select');
        var l=(field?'':(e.innerText||''))||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||'';
        l=l.replace(/\s+/g,' ').trim().slice(0,60); if(!l) return;
        labels.push(field?'Field: '+l:l);
      });
      var imgs=[].slice.call(document.querySelectorAll('img,[role=img]')).filter(function(e){if(!vis(e))return false;var r=e.getBoundingClientRect();return r.width>=60&&r.height>=60;});
      var alts=[]; imgs.forEach(function(e){var a=(e.getAttribute('alt')||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim(); if(a) alts.push(a.slice(0,100));});
      var pw=[].slice.call(document.querySelectorAll('input[type=password]')).some(vis);
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim();
      return {title:document.title,url:location.href,text:text,labels:labels,imgs:imgs.length,alts:alts.slice(0,40),pw:pw};
    })()"""
}

/** Bookmarks and history, kept in filesDir/browser.json. */
object BrowserData {
    data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())
    private fun file(ctx: Context) = File(ctx.filesDir, "browser.json")
    private var cache: JSONObject? = null

    private fun data(ctx: Context): JSONObject = cache ?: try { JSONObject(file(ctx).readText()) } catch (_: Exception) {
        JSONObject().put("bookmarks", JSONArray()).put("history", JSONArray()) }.also { cache = it }

    private fun save(ctx: Context) { try { file(ctx).writeText(data(ctx).toString()) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: filectx.writeTextdatactx.toString", e) } }

    // 0.45.19-speed: Blaze Phone's site resolver reads history off the main thread while the browser writes it; one lock.
    @Synchronized private fun list(ctx: Context, k: String): List<Entry> {
        val a = data(ctx).optJSONArray(k) ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.optString("t"), it.optString("u"), it.optLong("at")) }
    }
    @Synchronized private fun put(ctx: Context, k: String, l: List<Entry>) {
        data(ctx).put(k, JSONArray().apply { l.forEach { put(JSONObject().put("t", it.title).put("u", it.url).put("at", it.time)) } }); save(ctx)
    }

    fun bookmarks(ctx: Context) = list(ctx, "bookmarks")
    fun history(ctx: Context) = list(ctx, "history")
    fun isBookmarked(ctx: Context, url: String) = bookmarks(ctx).any { it.url == url }
    @Synchronized fun toggleBookmark(ctx: Context, title: String, url: String) {
        val b = bookmarks(ctx).toMutableList()
        if (b.removeAll { it.url == url }.not()) b.add(0, Entry(title.ifBlank { url }, url))
        put(ctx, "bookmarks", b)
    }
    @Synchronized fun addHistory(ctx: Context, title: String, url: String) {
        val h = history(ctx).filter { it.url != url }.toMutableList()
        h.add(0, Entry(title, url)); put(ctx, "history", h.take(300))
    }
    fun clearHistory(ctx: Context) = put(ctx, "history", emptyList())
}
