package com.blaze.assistant.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.BrandProgress
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.Label
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.hairline
import com.blaze.assistant.ui.hairlineBottom
import com.blaze.assistant.ui.hairlineTop

/** The Browser screen: shows BrowserEngine's current tab with an address bar, tabs, bookmarks, history and downloads. */
class BrowserActivity : androidx.fragment.app.FragmentActivity(), com.blaze.assistant.ui.BackRule.Shelled {
    /**
     * v0.45.25 Back (edge swipe on gesture navigation, or the back button): BackRule sees it first, as for every Blaze
     * screen. While the browser has something of its own to undo (fullscreen video, an open sheet, the pointer, the Ask
     * sheet, or a page to go back to) Back stays here and the screen's BackHandler handles it; otherwise it goes to the chat.
     */
    override fun backTakesToChat(): Boolean = !BrowserUi.innerBack()
    override fun backToChat() { com.blaze.assistant.mediaapps.BackToChat.go(this) }

    private var pending: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending?.onReceiveValue(if (r.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null)
        pending = null
    }
    /** VPN configs: .conf files or a provider's .zip, several at once. */
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val added = mutableListOf<String>(); val bad = mutableListOf<String>()
        uris.forEach { u ->
            try {
                val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "config.conf"
                val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.size > 5_000_000) { bad += "$name is too big to be a WireGuard config."; return@forEach }
                val (a, b) = BrowserVpn.import(name, bytes)
                added += a.map { "${it.flag} ${it.label}" }; bad += b
            } catch (e: Exception) { bad += e.message ?: "Couldn't read a file." }
        }
        val msg = (if (added.isNotEmpty()) "Added ${added.size}: ${added.take(6).joinToString()}${if (added.size > 6) "…" else ""}." else "") +
            (if (bad.isNotEmpty()) " Skipped ${bad.size}: ${bad.first()}" else "")
        Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show()
    }

    /** v0.45.25 Sky: the search pill's mic. What was said loads in the current tab (a web search unless it's an address). */
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
        (BrowserEngine.tab() ?: BrowserEngine.newTab(this)).let { BrowserEngine.load(it, said) }
    }

    private fun listen() {
        try {
            voice.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search or say a web address"))
        } catch (_: Exception) { Toast.makeText(this, "No voice input on this phone.", Toast.LENGTH_SHORT).show() }
    }

    /**
     * v0.45.25 Sky: the page runs edge to edge with the system navigation bar hidden (a swipe from the bottom shows it for a
     * moment). Gesture users keep their back edges and home swipe; 3-button users swipe the buttons in. Re-applied on resume
     * and focus because dialogs, the keyboard and picture-in-picture can bring the bar back.
     */
    private fun hideNav() {
        if (isInPictureInPictureMode) return
        try {
            val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        } catch (_: Exception) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideNav() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT))
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        hideNav()
        BrowserEngine.init(this)
        BrowserEngine.filePicker = { cb, p ->
            pending?.onReceiveValue(null); pending = cb
            try { picker.launch(p.createIntent()); true } catch (_: Exception) { pending = null; cb.onReceiveValue(null); false }
        }
        BrowserUi.authenticate = { reason, ok -> authenticate(reason, ok) }
        handle(intent)
        if (BrowserEngine.tabs.isEmpty()) BrowserEngine.newTab(this)
        setContent { BlazeTheme { BrowserScreen(onClose = { finish() }, onImport = { try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { } },
            onPopOut = { w, h -> popOut(w, h) }, onVoice = { listen() }) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    /** v0.24.6: the page pointer's Pop out: the video under the pointer plays in a small window over other apps. */
    fun popOut(w: Float, h: Float): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return try {
            val r = if (w > 0f && h > 0f) (w / h).coerceIn(0.42f, 2.38f) else 16f / 9f
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build())
        } catch (_: Exception) { false }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        BrowserUi.pip.value = isInPictureInPictureMode
    }

    private fun handle(i: Intent?) {
        val u = i?.dataString ?: i?.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value } ?: return
        BrowserEngine.newTab(this, u)
    }

    override fun onResume() { super.onResume(); BrowserEngine.attach(this); BrowserEngine.tab()?.live?.onResume(); hideNav() }
    override fun onPause() { BrowserEngine.attach(null); BrowserEngine.saveTabsNow(); super.onPause() }
    // v0.45.25: the engine knows when the screen is up, so hidden tabs (and, once Blaze is idle, the visible one) can sleep.
    override fun onStart() { super.onStart(); BrowserEngine.setUiVisible(true) }
    override fun onStop() { BrowserEngine.saveTabsNow(); TrackerBlock.flush(); BrowserEngine.setUiVisible(false); super.onStop() }

    /** v0.29.7: fingerprint / face / PIN before a saved password is shown, copied or edited. No screen lock = no reveal. */
    private fun authenticate(reason: String, ok: () -> Unit) {
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Set a screen lock (PIN, pattern or fingerprint) in Android settings to see saved passwords.", Toast.LENGTH_LONG).show(); return }
        try {
            androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) { ok() }
            }).authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Saved passwords").setSubtitle(reason).setAllowedAuthenticators(auth).build())
        } catch (e: Exception) { Toast.makeText(this, "Couldn't ask for your screen lock: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        BrowserUi.authenticate = null
        BrowserEngine.attach(null); BrowserEngine.filePicker = null
        // Back to the background browser: the tabs keep running offscreen at screen size.
        // Only awake tabs have a WebView; sleeping ones stay records (v0.45.25).
        BrowserEngine.tabs.forEach { t -> t.live?.let { w -> (w.parent as? ViewGroup)?.removeView(w); BrowserEngine.layoutOffscreen(w) } }
        super.onDestroy()
    }
}

/** Browser screen state shared with the activity. */
object BrowserUi {
    val pip = androidx.compose.runtime.mutableStateOf(false)
    /** The summoned page pointer (v0.24.6). */
    val pointer = androidx.compose.runtime.mutableStateOf(false)
    /** v0.29.7: device unlock for Saved passwords (set while the browser is open). */
    @Volatile var authenticate: ((String, () -> Unit) -> Unit)? = null
    @Volatile var unlockedUntil = 0L
    /** v0.45.25: true while Back has something to do inside the browser (set by the screen; read by BackRule). */
    @Volatile var innerBack: () -> Boolean = { false }
}

private enum class Panel { NONE, TABS, GRID, LAUNCHER, SITE, FIND, BOOKMARKS, HISTORY, PRIVACY, PASSWORDS, SAVED_PAGES, INSTALL_SITE, SITE_APPS }

/**
 * The Browser (v0.24.6 look): rounded dark Blaze theme with a pill address bar, rounded tab chips, round icon
 * buttons and a brand-coloured progress shimmer. The mascot button summons the page pointer (long-press too);
 * the sparkle button opens "Ask Blaze about this page".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BrowserScreen(onClose: () -> Unit, onImport: () -> Unit, onPopOut: (Float, Float) -> Boolean = { _, _ -> false },
                           onVoice: () -> Unit = {},
                           previewPointer: PagePointer.Hit? = null, previewMenu: Boolean = false, previewAsk: List<Pair<Boolean, String>>? = null,
                           previewPage: (@Composable () -> Unit)? = null, previewTip: androidx.compose.ui.geometry.Offset? = null) {
    val ctx = LocalContext.current
    val tab = BrowserEngine.tab()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var tabsSeg by remember { mutableStateOf(TabSeg.OPEN) }
    var tick by remember { mutableIntStateOf(0) }   // re-read bookmarks after a change
    var pointer by BrowserUi.pointer
    if (previewPointer != null) pointer = true
    var askOpen by remember { mutableStateOf(previewAsk != null) }
    var askFirst by remember { mutableStateOf<String?>(null) }
    val pip by BrowserUi.pip
    var reading by remember { mutableStateOf<SavedPages.Page?>(null) }
    var installUrl by remember { mutableStateOf("") }
    val priv = tab?.isPrivate == true

    // Back (edge swipe, predictive back or the back button): what's on top of the page closes first, then the page goes
    // back, then the browser closes.
    SideEffect {
        BrowserUi.innerBack = { BrowserEngine.fullView != null || reading != null || askOpen || pointer || panel != Panel.NONE ||
            (BrowserEngine.tab()?.let { it.live?.canGoBack() ?: it.canBack } ?: false) }
    }
    BackHandler {
        when {
            BrowserEngine.fullView != null -> BrowserEngine.exitFull()
            reading != null -> reading = null
            askOpen -> askOpen = false
            pointer -> pointer = false
            panel != Panel.NONE -> panel = Panel.NONE
            tab != null && (tab.live?.canGoBack() ?: false) -> BrowserEngine.back(tab)
            else -> onClose()
        }
    }

    BrowserEngine.fullView?.let { v ->
        AndroidView(factory = { FrameLayout(it) }, update = { f -> if (v.parent !== f) { (v.parent as? ViewGroup)?.removeView(v); f.removeAllViews(); f.addView(v) } },
            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        return
    }

    reading?.let { p -> SavedTextReader(p, onClose = { reading = null }); return }

    if (panel == Panel.GRID) {
        Box(Modifier.fillMaxSize().background(if (priv) androidx.compose.ui.graphics.Color(0xFF202124) else LocalBlaze.current.paper).systemBarsPadding()) {
            TabGrid(startPrivate = priv, onDone = { panel = Panel.NONE })
        }
        return
    }

    // Picture-in-picture: only the page (the video was scrolled to the middle and started).
    if (pip && tab != null) {
        key(tab.id) { WebHost(tab) }
        return
    }

    val host = remember(tab?.id) {
        PointerHost(
            openUrl = { u, mode ->
                when (mode) {
                    OpenMode.SAME -> BrowserEngine.tab()?.let { BrowserEngine.load(it, u) }
                    OpenMode.NEW -> BrowserEngine.newTab(ctx, u, isPrivate = priv)
                    // v0.45.25: a background tab is a record until you open it (no second WebView loading behind you).
                    OpenMode.BACKGROUND -> BrowserEngine.newBackgroundTab(ctx, u, isPrivate = priv)
                }
            },
            ask = { q -> askFirst = q; askOpen = true },
            pip = { h, x, y ->
                BrowserEngine.tab()?.live?.evaluateJavascript(PagePointer.focusVideoJs(x, y), null)
                pointer = false
                if (!onPopOut(h.w, h.h)) android.widget.Toast.makeText(ctx, "This phone doesn't allow picture-in-picture for Blaze.", android.widget.Toast.LENGTH_SHORT).show()
            },
            isPrivate = priv)
    }

    fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()
    fun share(url: String) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun runTool(t: Tool) {
        val url = tab?.url.orEmpty()
        panel = Panel.NONE
        when (t) {
            Tool.ASK -> { askFirst = null; askOpen = true }
            Tool.POINTER -> pointer = true
            Tool.TRANSLATE -> tab?.let { if (url.startsWith("http")) BrowserEngine.load(it, translateUrl(url)) }
            Tool.FIND -> panel = Panel.FIND
            Tool.BOOKMARKS -> panel = Panel.BOOKMARKS
            Tool.HISTORY -> panel = Panel.HISTORY
            Tool.DOWNLOADS -> try { ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            Tool.SAVED -> panel = Panel.SAVED_PAGES
            Tool.PASSWORDS -> panel = Panel.PASSWORDS
            Tool.INSTALL -> { installUrl = url; panel = Panel.INSTALL_SITE }
            Tool.MY_APPS -> panel = Panel.SITE_APPS
            Tool.DESKTOP -> BrowserEngine.useDesktop(!BrowserEngine.desktop)
            Tool.SHARE -> if (url.startsWith("http")) share(url)
            Tool.TASKS -> { tabsSeg = TabSeg.BLAZE; panel = Panel.TABS }
        }
    }
    fun siteAction(a: SiteAction) {
        val url = tab?.url.orEmpty()
        panel = Panel.NONE
        when (a) {
            SiteAction.PASSWORDS -> panel = Panel.PASSWORDS
            SiteAction.PRIVACY -> panel = Panel.PRIVACY
            SiteAction.INSTALL -> { installUrl = url; panel = Panel.INSTALL_SITE }
            SiteAction.ASK -> { askFirst = null; askOpen = true }
            SiteAction.BOOKMARK -> { BrowserData.toggleBookmark(ctx, tab?.title.orEmpty(), url); tick++ }
            SiteAction.SAVE -> tab?.let { t -> BrowserEngine.savePage(t) { msg -> toast(msg) } }
            SiteAction.SHARE -> share(url)
            SiteAction.OPEN_WITH -> try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }
    fun setPrivate(on: Boolean) {
        if (on) { if (!priv) BrowserEngine.privateTabs().lastOrNull()?.let { BrowserEngine.switchTo(it) } ?: BrowserEngine.newTab(ctx, isPrivate = true) }
        else if (priv) { val i = BrowserEngine.tabs.indexOfLast { !it.isPrivate }; if (i >= 0) BrowserEngine.current = i else BrowserEngine.newTab(ctx) }
        panel = Panel.NONE
    }
    /** Swipe the pill: the next / previous tab on the same side (normal or private). */
    fun swipeTab(next: Boolean) {
        val same = BrowserEngine.tabs.filter { it.isPrivate == priv }
        val i = same.indexOf(tab); if (i < 0 || same.size < 2) return
        val j = if (next) i + 1 else i - 1
        same.getOrNull(j)?.let { BrowserEngine.switchTo(it) }
    }

    // v0.33.3: the floating toolbar hides on scroll down and comes back on scroll up (or a tap on the page top).
    // perf-browser: a plain state object that composition never reads (only the slide animation below collects it), so
    // each scroll-direction flip no longer recomposes this whole screen.
    val barShown = remember { mutableStateOf(true) }
    LaunchedEffect(tab?.id, tab?.url) { barShown.value = true }
    // v0.34.1: where the floating toolbar is docked (bottom, top, left or right of the page), kept across launches.
    var dock by remember { mutableStateOf(ToolbarDock.load()) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val blank = tab == null || tab.url.isBlank() || tab.url == "about:blank"
    // v0.45.25 Sky: the system navigation bar is hidden; the pill keeps clear of the home-gesture zone (and the side docks
    // of the back-gesture edges), with the 3-button bar's height too when it is swiped in.
    val bottomInsets = skyBottomInsets()
    val sideInsets = WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)
    val navBottom = bottomInsets.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(if (blank) Sky.navy else Color.White).imePadding().onSizeChanged { areaSize = it }) {
        // The page, full-bleed under the floating pill (only the status bar is kept clear).
        if (previewPage != null) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                previewPage()
                if (pointer) PagePointerLayer(null, host, onClose = { pointer = false }, preview = previewPointer, previewMenu = previewMenu, startTip = previewTip)
            }
        } else if (tab != null) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                key(tab.id) { WebHost(tab, onScroll = { down -> if (panel == Panel.NONE) barShown.value = !down }) }
            }
            if (blank) SkyStartPage(tick, bottomSpace = 64.dp + navBottom, open = { u -> BrowserEngine.load(tab, u); panel = Panel.NONE },
                onSearch = { panel = Panel.LAUNCHER }, onVoice = onVoice)
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                if (pointer) PagePointerLayer(tab.web, host, onClose = { pointer = false })
                // v0.29.7 saved passwords: offer to save after a sign-in, or offer saved accounts on a sign-in form.
                val offer = BrowserEngine.loginOffer
                if (!pointer && panel == Panel.NONE && !priv) {
                    if (offer != null && offer.tabId == tab.id) SaveLoginCard(offer, onSave = { BrowserEngine.acceptLoginOffer() },
                        onNever = { BrowserEngine.neverForSite() }, onDismiss = { BrowserEngine.dismissLoginOffer() })
                    else if (tab.fillAccounts.isNotEmpty()) LoginFillBar(tab.fillAccounts, onPick = { BrowserEngine.fillLogin(tab, it) }, onDismiss = { tab.fillAccounts = emptyList() })
                }
            }
        }
        // A 2dp progress line along the top of the page (its own scope: progress ticks never recompose the screen).
        if (tab != null && !blank) TopProgress(tab, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        if (panel == Panel.FIND && tab != null) Box(Modifier.align(Alignment.TopCenter)) { SkyFindBar(tab.live, onClose = { panel = Panel.NONE }) }

        androidx.compose.runtime.CompositionLocalProvider(LocalSheetInset provides (if (dock == ToolbarDock.BOTTOM) 72.dp + navBottom else navBottom)) {
            when (panel) {
                Panel.PRIVACY -> Sheet { PrivacySheet(onImport = onImport, onPrivateTab = { BrowserEngine.newTab(ctx, isPrivate = true); panel = Panel.NONE }) }
                Panel.PASSWORDS -> Sheet { SavedPasswordsPanel() }
                Panel.INSTALL_SITE -> Sheet { InstallSiteBody(installUrl) { panel = Panel.NONE } }
                Panel.SITE_APPS -> Sheet { InstalledSitesBody { panel = Panel.NONE } }
                Panel.SAVED_PAGES -> Sheet {
                    SavedPagesPanel(onOpen = { p ->
                        if (!BrowserEngine.openSaved(ctx, p)) toast("That saved page is missing.")
                        panel = Panel.NONE
                    }, onRead = { p -> reading = p; panel = Panel.NONE })
                }
                Panel.BOOKMARKS -> Sheet { EntryList("Bookmarks", BrowserData.bookmarks(ctx), "No bookmarks yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE } }
                Panel.HISTORY -> Sheet {
                    EntryList("History", BrowserData.history(ctx), "No history yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE }
                    MenuRow(Icons.Rounded.DeleteSweep, "Clear history") { BrowserData.clearHistory(ctx); panel = Panel.NONE }
                }
                else -> {}
            }
        }

        // v0.33.3 floating bar, v0.34.1 docking (drag the grip to any edge; remembered), v0.45.25 Sky look: one frosted pill
        // (lock · address · VPN · tabs · Blaze) with small chips beside it (back / forward / reload / home, Blaze's status).
        val sheetOpen = panel == Panel.TABS || panel == Panel.LAUNCHER || panel == Panel.SITE
        val pinned = panel != Panel.NONE || pointer || blank
        // perf-browser: same 220 ms slide and same rule as before (hidden under a sheet, else scrolled-up or pinned), but
        // driven from the scroll state off-composition; the value is read only in graphicsLayer (draw phase).
        val hideAnim = remember { Animatable(if (sheetOpen) 1f else 0f) }
        LaunchedEffect(sheetOpen, pinned) {
            snapshotFlow { !sheetOpen && BrowserPerf.barShown(barShown.value, pinned) }
                .collectLatest { show -> hideAnim.animateTo(if (show) 0f else 1f, tween(220)) }
        }
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val dragOff = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val snapAlpha = remember { Animatable(1f) }
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        var dragging by remember { mutableStateOf(false) }
        suspend fun snapBar() {
            val area = areaSize; val bar = barSize
            if (area.width == 0 || bar.width == 0) { dragOff.animateTo(Offset.Zero); return }
            val margin = with(density) { 10.dp.toPx() }
            val bottom = with(density) { navBottom.toPx() }
            val thick = (if (dock.vertical) bar.width else bar.height).toFloat()
            val (hx, hy) = ToolbarDock.center(dock, area.width.toFloat(), area.height.toFloat(), thick, margin, bottom)
            val target = ToolbarDock.nearest(hx + dragOff.value.x, hy + dragOff.value.y, area.width.toFloat(), area.height.toFloat())
            if (target == dock) { dragOff.animateTo(Offset.Zero, spring(dampingRatio = 0.72f, stiffness = 500f)); return }
            val (tx, ty) = ToolbarDock.center(target, area.width.toFloat(), area.height.toFloat(), thick, margin, bottom)
            coroutineScope {
                launch { snapAlpha.animateTo(0.2f, tween(170)) }
                dragOff.animateTo(Offset(tx - hx, ty - hy), tween(190))
            }
            dock = target
            ToolbarDock.save(target)
            dragOff.snapTo(Offset.Zero)
            snapAlpha.animateTo(1f, tween(220))
        }
        val vertical = dock.vertical
        val barMod = Modifier.align(dock.alignment)
            .offset { IntOffset(dragOff.value.x.roundToInt(), dragOff.value.y.roundToInt()) }
            .graphicsLayer {
                val hide = hideAnim.value
                val away = hide * ((if (vertical) size.width else size.height) + 48.dp.toPx())
                when (dock) {
                    ToolbarDock.BOTTOM -> translationY = away
                    ToolbarDock.TOP -> translationY = -away
                    ToolbarDock.LEFT -> translationX = -away
                    ToolbarDock.RIGHT -> translationX = away
                }
                alpha = (1f - hide) * snapAlpha.value
                if (dragging) { scaleX = 1.04f; scaleY = 1.04f }
            }
            .then(when (dock) {
                ToolbarDock.BOTTOM -> Modifier.windowInsetsPadding(bottomInsets).padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
                ToolbarDock.TOP -> Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp).fillMaxWidth()
                ToolbarDock.LEFT -> Modifier.windowInsetsPadding(sideInsets).windowInsetsPadding(bottomInsets).padding(start = 6.dp, top = 10.dp, bottom = 10.dp)
                ToolbarDock.RIGHT -> Modifier.windowInsetsPadding(sideInsets).windowInsetsPadding(bottomInsets).padding(end = 6.dp, top = 10.dp, bottom = 10.dp)
            })
            .onSizeChanged { barSize = it }
        val grip: @Composable () -> Unit = {
            Box(Modifier.size(if (vertical) 40.dp else 18.dp, if (vertical) 18.dp else 40.dp)
                .pointerInput(dock, navBottom, areaSize) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; scope.launch { snapBar() } },
                        onDragCancel = { dragging = false; scope.launch { snapBar() } },
                        onDrag = { ch, d -> ch.consume(); scope.launch { dragOff.snapTo(dragOff.value + d) } })
                }
                .semantics { contentDescription = "Move the toolbar: drag it to the top, bottom, left or right" }, contentAlignment = Alignment.Center) {
                Icon(if (vertical) Icons.Rounded.DragHandle else Icons.Rounded.DragIndicator, contentDescription = null,
                    tint = if (dragging) Sky.ink else Sky.weak, modifier = Modifier.size(16.dp))
            }
        }
        val url = tab?.url.orEmpty()
        val insecure = com.blaze.assistant.connect.UrlSafety.notSecure(url)
        val lockIcon = when { priv -> Icons.Rounded.VisibilityOff; insecure -> Icons.Rounded.ErrorOutline; url.startsWith("https") -> Icons.Rounded.Lock; else -> Icons.Rounded.Info }
        val tabCount: @Composable () -> Unit = {
            val n = BrowserEngine.tabs.count { it.isPrivate == priv }
            Box(Modifier.size(40.dp).clip(CircleShape).combinedClickable(
                onClick = {
                    // perf-browser: the grid picture is copied from the screen (PixelCopy, off the main thread) when nothing
                    // covers the page, then the grid opens; otherwise the software snapshot as before.
                    val covered = blank || pointer || askOpen || panel != Panel.NONE || BrowserEngine.loginOffer != null || tab?.fillAccounts?.isNotEmpty() == true
                    BrowserEngine.snapshotThen((ctx as? Activity)?.window, dockBottom = dock == ToolbarDock.BOTTOM, overlay = covered) {
                        tabsSeg = if (priv) TabSeg.PRIVATE else TabSeg.OPEN; panel = Panel.TABS }
                },
                onLongClick = { BrowserEngine.newTab(ctx, isPrivate = priv); panel = Panel.NONE },
                onClickLabel = "Tabs", onLongClickLabel = "New tab")
                .semantics { contentDescription = "Tabs ($n). Long-press for a new tab" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).border(1.6.dp, Sky.ink, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Text(if (n > 99) ":D" else "$n", Sky.ink, 11, weight = FontWeight.SemiBold) }
            }
        }
        val blazeButton: @Composable () -> Unit = {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (pointer) Sky.blue.copy(alpha = 0.75f) else Sky.blue)
                .combinedClickable(onClick = { if (url.startsWith("http") && !pointer) { askFirst = null; askOpen = true } else pointer = !pointer },
                    onLongClick = { pointer = true; panel = Panel.NONE },
                    onClickLabel = "Ask Blaze about this page", onLongClickLabel = "Summon the Blaze pointer")
                .semantics { contentDescription = "Blaze: tap to ask about this page, long-press for the pointer" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        val navChips: @Composable () -> Unit = {
            SkyChipButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = tab?.canBack == true) { tab?.let { BrowserEngine.back(it) } }
            SkyChipButton(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", enabled = tab?.canForward == true) { tab?.let { BrowserEngine.forward(it) } }
            if (tab != null && !blank) ReloadChip(tab)
            SkyChipButton(Icons.Rounded.Home, "Start page") { tab?.let { BrowserEngine.load(it, "about:blank") }; panel = Panel.NONE; pointer = false }
        }
        if (vertical) Column(barMod.frost(Sky.pill, Sky.frost).verticalScroll(rememberScrollState()).padding(horizontal = 3.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            grip()
            SkyChipButton(lockIcon, "Site info") { panel = Panel.SITE }
            SkyChipButton(Icons.Rounded.Search, "Search or type a web address") { panel = Panel.LAUNCHER }
            navChips(); tabCount(); blazeButton()
        } else Column(barMod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val chips: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BlazeStatusChip(tab, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.weight(0.01f))
                    if (!blank) Row(Modifier.frost(Sky.pill, Sky.frost).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) { navChips() }
                }
            }
            if (dock == ToolbarDock.TOP) Unit else chips()
            var swipe by remember { mutableFloatStateOf(0f) }
            Row(Modifier.fillMaxWidth().height(56.dp).frost(Sky.pill, Sky.frost)
                .pointerInput(tab?.id, priv) {
                    detectHorizontalDragGestures(onDragStart = { swipe = 0f },
                        onDragEnd = { val th = 56.dp.toPx(); if (swipe < -th) swipeTab(true) else if (swipe > th) swipeTab(false); swipe = 0f },
                        onDragCancel = { swipe = 0f }, onHorizontalDrag = { ch, d -> ch.consume(); swipe += d })
                }
                .padding(start = 2.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                grip()
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Site info") { panel = Panel.SITE }, contentAlignment = Alignment.Center) {
                    Icon(lockIcon, if (insecure) "Not secure" else "Site info", tint = if (insecure) Color(0xFFFFB86B) else if (url.startsWith("https")) Sky.green else Sky.sub, modifier = Modifier.size(16.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Search or type a web address") { panel = Panel.LAUNCHER }, contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (insecure) { Text("Not secure \u00b7 ", Color(0xFFFFB86B), 13, maxLines = 1) }
                        Text(if (blank) (if (priv) "Private: search or type" else "Search or type a web address") else display(url), if (blank) Sky.sub else Sky.ink, 15, maxLines = 1)
                    }
                }
                when (BrowserVpn.status) {
                    BrowserVpn.Status.ON -> VpnBadge("VPN", Sky.green) { panel = Panel.PRIVACY }
                    BrowserVpn.Status.CONNECTING -> VpnBadge("VPN\u2026", Color(0xFFFFB86B)) { panel = Panel.PRIVACY }
                    BrowserVpn.Status.FAILED -> VpnBadge("VPN!", Color(0xFFFF8A80)) { panel = Panel.PRIVACY }
                    BrowserVpn.Status.OFF -> {}
                }
                tabCount()
                Spacer(Modifier.width(4.dp))
                blazeButton()
            }
            if (dock == ToolbarDock.TOP) chips()
        }

        // Sky sheets: over the pill.
        when (panel) {
            Panel.TABS -> SkyTabsSheet(tabsSeg, onDone = { panel = Panel.NONE }, onGrid = { panel = Panel.GRID }, onSearch = { panel = Panel.LAUNCHER })
            Panel.LAUNCHER -> SkyLauncher(onDismiss = { panel = Panel.NONE },
                onGo = { q -> (BrowserEngine.tab() ?: BrowserEngine.newTab(ctx)).let { BrowserEngine.load(it, q) }; panel = Panel.NONE },
                onAsk = { q -> askFirst = q; askOpen = true; panel = Panel.NONE },
                onTool = { runTool(it) }, onVpnMore = { panel = Panel.PRIVACY }, onPrivate = { setPrivate(it) })
            Panel.SITE -> SkySiteSheet(onDismiss = { panel = Panel.NONE }, onAction = { siteAction(it) })
            else -> {}
        }
    }

    if (askOpen) PageAskSheet(title = tab?.title.orEmpty(), initial = askFirst,
        readPage = { BrowserEngine.capture(ctx) }, web = tab?.web?.takeIf { previewAsk == null },
        onDismiss = { askOpen = false; askFirst = null }, preview = previewAsk)
}

@Composable
private fun VpnBadge(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.padding(end = 4.dp).clip(Sky.pill).background(color.copy(alpha = 0.18f)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color, 11, weight = FontWeight.SemiBold) }
}

/** Reload, or stop while loading: reads the tab's progress in its own scope. */
@Composable
private fun ReloadChip(tab: BrowserEngine.Tab) {
    val loading by remember(tab) { derivedStateOf { tab.progress < 100 } }
    SkyChipButton(if (loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (loading) "Stop loading" else "Reload") {
        tab.live?.let { if (loading) it.stopLoading() else it.reload() } ?: tab.web
    }
}

@Composable
private fun TopProgress(tab: BrowserEngine.Tab, modifier: Modifier) {
    val p = tab.progress
    if (p in 0..99) ThinProgress(p, modifier)
}

/** "Blaze reading in background · Watch": shows while Blaze drives a tab; Watch brings that tab on screen. */
@Composable
private fun BlazeStatusChip(tab: BrowserEngine.Tab?, modifier: Modifier) {
    val step by BrowserLive.step.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(step) { now = System.currentTimeMillis() }
    val s = step ?: return
    if (now - s.at !in 0..BrowserLive.WINDOW_MS) return
    val agent = BrowserEngine.agentTab() ?: return
    val here = agent === tab
    Row(modifier.height(36.dp).frost(Sky.pill, Sky.frost).clickable(enabled = !here) { BrowserEngine.switchTo(agent) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Sky.green)); Spacer(Modifier.width(8.dp))
        Text(if (here) "Blaze is browsing this tab" else "Blaze ${s.what.ifBlank { "reading" }.replaceFirstChar { it.lowercase() }.take(30)} in background \u00b7 Watch", Sky.ink, 12, maxLines = 1)
    }
}

@Composable
private fun WebHost(tab: BrowserEngine.Tab, onScroll: ((down: Boolean) -> Unit)? = null) {
    val cb = androidx.compose.runtime.rememberUpdatedState(onScroll)
    AndroidView(factory = { FrameLayout(it) }, update = { f ->
        val w = tab.web
        if (onScroll != null) w.setOnScrollChangeListener { _, _, y, _, oldY ->
            val d = ToolbarScroll.direction(y, oldY)
            if (d != null) cb.value?.invoke(d)
        } else w.setOnScrollChangeListener(null)
        if (w.parent !== f) { (w.parent as? ViewGroup)?.removeView(w); f.removeAllViews(); f.addView(w, FrameLayout.LayoutParams(-1, -1)) }
        // v0.30.5: Home's live preview shows this page scaled down; full size here.
        if (w.scaleX != 1f || w.scaleY != 1f) { w.scaleX = 1f; w.scaleY = 1f }
    }, modifier = Modifier.fillMaxSize())
}

private fun display(u: String) = if (u == "about:blank") "" else if (SavedPages.isSavedUrl(u)) "Offline copy" else u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

@Composable private fun Head(s: String) = Text(s, Glass.sub, 12, weight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

@Composable private fun EntryList(title: String, l: List<BrowserData.Entry>, empty: String, open: (String) -> Unit) {
    Text(title, Glass.text, 16, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp))
    if (l.isEmpty()) Text(empty, Glass.sub, 13, modifier = Modifier.padding(start = 18.dp, bottom = 16.dp))
    LazyColumn(Modifier.heightIn(max = 420.dp).padding(horizontal = 8.dp)) { items(l.take(150)) { e -> EntryRow(e) { open(e.url) } } }
}

@Composable private fun EntryRow(e: BrowserData.Entry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
            Text(display(e.url).take(1).uppercase().ifBlank { "\u2022" }, Glass.text, 13, weight = FontWeight.SemiBold) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { e.url }, Glass.text, 14, maxLines = 1)
            Text(display(e.url), Glass.sub, 12, maxLines = 1)
        }
    }
}

/** v0.33.3: room under a sheet for the floating toolbar (the browser provides it; 0 elsewhere). */
internal val LocalSheetInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

@Composable internal fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
        // v0.45.25 Sky: frosted navy, no shadow (a translucent fill; nothing is blurred behind it).
        Column(Modifier.fillMaxWidth().clip(shape).background(Sky.navy.copy(alpha = 0.94f)).border(1.dp, Sky.rim, shape)
            .padding(top = 10.dp, bottom = 8.dp + LocalSheetInset.current), content = content)
    }
}

@Composable private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.raised), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Glass.text, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(text, Glass.text, 15)
    }
}

@Composable private fun Text(s: String, color: androidx.compose.ui.graphics.Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, weight: FontWeight = FontWeight.Normal) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = if (size >= 20) Type.Display else Type.Sans, fontWeight = weight, fontSize = size.sp, color = color))

/** v0.33.3: a 2dp progress line (brand gradient, rounded), overlaid on the address row's bottom edge. */
@Composable
private fun ThinProgress(progress: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).clip(Glass.pill)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(5, 100) / 100f).fillMaxHeight().clip(Glass.pill).background(Glass.brand))
    }
}

/**
 * v0.34.1 (pure, tested): the edges the floating toolbar can dock to, the nearest one to where it was dropped, and the
 * centre it sits at for a page area of [w] x [h] px ([thick] = the bar's depth across its edge, [margin] its inset, [bottom]
 * the navigation bar under the page). Kept in Settings as "browser_bar_dock".
 */
enum class ToolbarDock(val id: String, val vertical: Boolean, val alignment: Alignment) {
    BOTTOM("bottom", false, Alignment.BottomCenter), TOP("top", false, Alignment.TopCenter),
    LEFT("left", true, Alignment.CenterStart), RIGHT("right", true, Alignment.CenterEnd);

    companion object {
        const val KEY = "browser_bar_dock"
        fun of(id: String?): ToolbarDock = entries.firstOrNull { it.id == id } ?: BOTTOM
        fun load(): ToolbarDock = of(runCatching { com.blaze.assistant.agent.Settings.str(KEY, BOTTOM.id) }.getOrNull())
        fun save(d: ToolbarDock) { runCatching { com.blaze.assistant.agent.Settings.setStr(KEY, d.id) } }

        /** The edge closest to the point (x, y) in a w x h area; a tie keeps the order bottom, top, left, right. */
        fun nearest(x: Float, y: Float, w: Float, h: Float): ToolbarDock {
            val d = mapOf(BOTTOM to h - y, TOP to y, LEFT to x, RIGHT to w - x)
            return entries.minBy { d.getValue(it) }
        }

        fun center(d: ToolbarDock, w: Float, h: Float, thick: Float, margin: Float, bottom: Float): Pair<Float, Float> = when (d) {
            BOTTOM -> w / 2 to h - bottom - margin - thick / 2
            TOP -> w / 2 to margin + thick / 2
            LEFT -> margin + thick / 2 to (h - bottom) / 2
            RIGHT -> w - margin - thick / 2 to (h - bottom) / 2
        }
    }
}

/** Pure (tested): which way a scroll moves the floating toolbar. true = down (hide), false = up or at the top (show), null = jitter. */
object ToolbarScroll {
    const val SLOP = 12
    fun direction(y: Int, oldY: Int): Boolean? = when {
        y <= 0 -> false
        y - oldY > SLOP -> true
        oldY - y > SLOP -> false
        else -> null
    }
}
