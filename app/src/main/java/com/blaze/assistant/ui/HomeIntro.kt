package com.blaze.assistant.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.home.HomeBoard
import com.blaze.assistant.home.HomeGrid
import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.TodoFeed
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetData
import com.blaze.assistant.home.WidgetTypes
import com.blaze.assistant.ui.splash.CloudGlyphPainter
import com.blaze.assistant.ui.splash.CloudSplashSpec
import com.blaze.assistant.ui.splash.HomeIntroSpec
import com.blaze.assistant.ui.splash.HomeSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 0.47 Splash -> Home (approved design). One clock drives everything: the outlined cloud splash with its right-to-left
 * dots, then the cloud shrinking and gliding top-left into the weather board's icon, the board's outline drawing itself
 * and its text typing in, then the other cards emerging at the centre and settling into the grid. Compose-native only
 * (translate / scale / alpha / path trim), the sky is the one cached blur, no Lottie or video. Reduce motion (the
 * phone's "Remove animations") skips straight to the settled grid. A tap skips the rest.
 */
@Stable
class HomeIntroClock(val kind: WeatherKind, val night: Boolean, private val cards: Int,
                     order0: List<HomeGrid.Card> = HomeSequence.orderFor(cards)) {
    /** The cards after the board, in the order they come ([HomeSequence.order]); settled when the splash hands over (data has loaded by then). */
    var order by mutableStateOf(order0)
        private set
    private var starts = HomeSequence.starts(order0, HomeIntroSpec.CARDS_AT)
    fun setOrder(o: List<HomeGrid.Card>) { if (o != order) { order = o; starts = HomeSequence.starts(o, HomeIntroSpec.CARDS_AT) } }
    /** ms since the intro's first frame. */
    val t = mutableLongStateOf(0L)
    /** When the splash handed over (-1 = still on the splash). */
    val splashEnd = mutableLongStateOf(-1L)
    var done by mutableStateOf(false)
    var skipped by mutableStateOf(false)
    /** Rects in root coordinates: the intro's own box, the weather icon, each card's cell (measured before its layer). */
    val bounds = mutableStateMapOf<String, Rect>()
    var rootHeight by mutableStateOf(0f)

    /** ms since the splash ended (negative while it's still showing). */
    fun u(): Long = if (done) Long.MAX_VALUE / 4 else splashEnd.longValue.let { if (it < 0) -1L else t.longValue - it }
    val endU: Long get() = HomeSequence.end(order, HomeIntroSpec.CARDS_AT)

    /** The [i]-th card after the board. */
    fun card(i: Int): HomeGrid.Card? = order.getOrNull(i)
    /** ms on card [i]'s own clock (negative before it starts; huge once the intro is done). */
    fun cardLocal(i: Int): Long { val u = u(); if (done) return Long.MAX_VALUE / 4; return if (u < 0 || i !in starts.indices) -1L else u - starts[i] }
    fun cardT(i: Int): HomeSequence.Frame { val c = card(i) ?: return HomeSequence.SETTLED; return HomeSequence.frame(c, cardLocal(i)) }
    fun boardOutline(): Float { val u = u(); return if (u < 0) 0f else HomeIntroSpec.outline(u) }
    fun boardTiles(): Float { val u = u(); return if (u < 0) 0f else HomeIntroSpec.tiles(u) }
    fun boardFill(): Float { val u = u(); return if (u < 0) 0f else HomeIntroSpec.boardFill(u) }
    fun iconAlpha(): Float { val u = u(); return if (u >= HomeIntroSpec.GLIDE_MS) 1f else 0f }
    fun typed(len: Int, at: Long): Int { val u = u(); return if (u < 0) 0 else HomeIntroSpec.typed(len, at, u) }
    /** The chat bar's alpha (it fades in after the last card). */
    fun barAlpha(): Float = if (done) 1f else HomeSequence.barAlpha(order, HomeIntroSpec.CARDS_AT, u())

    companion object {
        /** The intro plays once per process (the cold open); later visits to Home show the settled grid. */
        @Volatile var played = false
    }
}

/**
 * 0.48.0 RC3: the chat bar is drawn by the composer host, outside Home's own tree; while the intro runs it reads its alpha
 * here (in a graphicsLayer lambda, so no recomposition). 1 whenever no intro is running.
 */
object HomeIntroBus {
    val barAlpha = androidx.compose.runtime.mutableFloatStateOf(1f)
}

/** Is the redesigned Home on? (A switch for the combined release; the old board stays one tap away.) */
object HomeRedesign {
    @Volatile var enabled = true
}

private fun isNight(b: WeatherBoard.Board): Boolean = b.night

/**
 * The redesigned Home. [chatBar] is the slot for the chat composer (built separately): it sits at the bottom over the
 * grid, which keeps room for it. [drawSky] draws the sky behind the grid (HomePane passes false when it draws it
 * full-bleed itself). Card data loads after the first frame and never on the main thread.
 */
@Composable
fun HomeRedesignScreen(
    onAsk: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onAllWidgets: () -> Unit,
    onPickInterests: () -> Unit = {},
    drawSky: Boolean = true,
    reserveBottom: Boolean = false,
    playIntro: Boolean = !HomeIntroClock.played && MotionTokens.enabled,
    chatBar: (@Composable BoxScope.() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val widgets by HomeBoard.widgets.collectAsState()
    val data by HomeBoard.data.collectAsState()
    val s = rememberHomeGridState(ctx, widgets, data)
    HomeRedesignContent(s, playIntro = playIntro, drawSky = drawSky, reserveBottom = reserveBottom || chatBar != null,
        onIntroDone = { HomeIntroClock.played = true },
        onWeather = { data.values.firstOrNull { it.link.isNotBlank() && it.sunset.isNotBlank() }?.link?.let(onOpenLink) },
        onTopic = { t -> if (t.url.isNotBlank()) onOpenLink(t.url) },
        onPickInterests = onPickInterests,
        onMusic = { try { com.blaze.assistant.mediaapps.MusicActivity.open(ctx) } catch (_: Throwable) { } },
        onMusicToggle = { mediaPlayPause(ctx) },
        onTodo = onAllWidgets,
        onNextUp = { s.next?.let { n -> if (n.kind == NextUp.Kind.CALENDAR) onOpenLink("calendar:" + n.at) else onAsk("What's next on my schedule today?") } ?: onAsk("Set a reminder for me") },
        onAction = { a -> onAsk(a.prompt) },
        onAllWidgets = onAllWidgets,
        chatBar = chatBar)
}

/** Sends play/pause to the active media session (Blaze Music when it is the one playing). */
private fun mediaPlayPause(ctx: Context) {
    try {
        val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    } catch (_: Throwable) { }
}

/**
 * Builds the grid's state from real data. Widgets / cached content come from [HomeBoard]'s in-memory flows; the rest
 * (routines, the next calendar event, the music player) is read only after the first frame has been drawn, off the main
 * thread. That deferral is a plain LaunchedEffect + withFrameNanos so it merges easily with a shared FirstFrame helper.
 */
@Composable
fun rememberHomeGridState(ctx: Context, widgets: List<Widget>, data: Map<String, WidgetData>): HomeGridState {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; started = true }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var music by remember { mutableStateOf<HomeMusicNow?>(null) }
    var event by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var reminders by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var routineHours by remember { mutableStateOf<List<Int>>(emptyList()) }
    val routines by com.blaze.assistant.routine.Routines.all.collectAsState()
    val h24 = remember { try { android.text.format.DateFormat.is24HourFormat(ctx) } catch (_: Throwable) { false } }

    // after the first frame: ask the board to refresh what the grid shows when it is stale (network, off main)
    LaunchedEffect(started, widgets.size) {
        if (!started) return@LaunchedEffect
        widgets.filter { it.type == WidgetTypes.WEATHER || InterestsFeed.isTopic(it) || TodoFeed.isTodo(it) }.forEach { HomeBoard.visible(it.id) }
    }
    LaunchedEffect(started, routines) {
        if (!started) return@LaunchedEffect
        val (r, h) = withContext(Dispatchers.Default) {
            val t = System.currentTimeMillis()
            val on = routines.filter { it.enabled }
            on.map { it.name to com.blaze.assistant.routine.Routines.next(it, t) } to on.filter { it.at <= 0L && it.everyMinutes <= 0 }.map { it.hour }
        }
        reminders = r; routineHours = h
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            event = withContext(Dispatchers.IO) { nextCalendarEvent(ctx, System.currentTimeMillis()) }
            delay(5 * 60_000L)
        }
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            music = com.blaze.assistant.agent.Awareness.get("music")?.let { n ->
                val pos = if (n.playing && n.posMs >= 0) n.posMs + (System.currentTimeMillis() - n.at) else n.posMs
                HomeMusicNow(n.title, n.detail.removePrefix("by ").trim(), pos.coerceAtMost(if (n.durMs > 0) n.durMs else Long.MAX_VALUE), n.durMs, n.playing)
            }
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val cal = remember(now / 60_000L) { Calendar.getInstance().apply { timeInMillis = now } }
    val board = remember(widgets, data, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) {
        WeatherBoard.from(widgets, data, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
    val topics = remember(widgets, data) { InterestsFeed.topics(widgets, data) }
    val tasks = remember(widgets, data) { TodoFeed.tasks(widgets, data) }
    val count = remember(widgets, data) { TodoFeed.count(widgets, data) }
    val next = remember(reminders, event, now / 60_000L) { NextUp.pick(reminders, listOfNotNull(event), now) }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val actions = remember(widgets, routineHours, hour) {
        QuickActions.build(com.blaze.assistant.agent.SimpleHome.starters(hour).map { it.label to it.prompt }, InterestsFeed.labels(widgets), routineHours)
    }
    val allTopics = remember(widgets, data) { InterestsFeed.topics(widgets, data, max = 24) }
    return HomeGridState(board, topics, music, tasks, count, next, actions, h24, now, allTopics)
}

/** The next calendar event in the coming 7 days (title, start), or null without READ_CALENDAR. Call off the main thread. */
private fun nextCalendarEvent(ctx: Context, now: Long): Pair<String, Long>? = try {
    if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) null else {
        val cc = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(cc, now); android.content.ContentUris.appendId(cc, now + 7 * 86_400_000L)
        ctx.contentResolver.query(cc.build(), arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
            android.provider.CalendarContract.Instances.ALL_DAY), null, null, "${android.provider.CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            var out: Pair<String, Long>? = null
            while (out == null && c.moveToNext()) { if (c.getInt(2) != 1 && c.getLong(1) > now) out = c.getString(0).orEmpty().ifBlank { "Event" } to c.getLong(1) }
            out
        }
    }
} catch (_: Throwable) { null }

/**
 * Stateless body (screenshots use it directly): sky, grid, the intro overlay and the chat bar slot.
 * With [playIntro] false the grid is drawn settled from the first frame.
 */
@Composable
fun HomeRedesignContent(
    s: HomeGridState,
    playIntro: Boolean,
    drawSky: Boolean = true,
    reserveBottom: Boolean = false,
    onIntroDone: () -> Unit = {},
    onWeather: () -> Unit = {}, onTopic: (InterestsFeed.Topic) -> Unit = {}, onPickInterests: () -> Unit = {},
    onMusic: () -> Unit = {}, onMusicToggle: () -> Unit = {}, onTodo: () -> Unit = {}, onNextUp: () -> Unit = {},
    onAction: (QuickActions.Action) -> Unit = {}, onAllWidgets: () -> Unit = {},
    chatBar: (@Composable BoxScope.() -> Unit)? = null,
    /** Screenshot tests: pin the intro clock at this many ms after the splash ended (null = run live). */
    frozenU: Long? = null,
    /** RC2 card focus (which card is lifted; saved across rotation / recreation). */
    focus: HomeFocusState = rememberHomeFocusState(),
    /** Tests: the low-RAM blur path (one cached software blur) even where RenderEffect exists. */
    forceSoftwareBlur: Boolean = false,
) {
    val cards = remember(s.hasTasks) { HomeGrid.order(s.hasTasks).size - 1 }
    // The intro decides its variant and its cards once (the cloud must match the icon it lands on).
    val intro = remember { if (playIntro || frozenU != null) HomeIntroClock(s.board.kind, s.board.night, cards, HomeSequence.order(s.hasTasks)) else null }
    val boardNow by rememberUpdatedState(s.board)
    val stateNow by rememberUpdatedState(s)
    val done by rememberUpdatedState(onIntroDone)
    if (intro != null && frozenU != null) {
        // negative = still on the splash, -frozenU ms into it
        if (frozenU < 0) { intro.splashEnd.longValue = -1L; intro.t.longValue = -frozenU }
        else { intro.splashEnd.longValue = 0L; intro.t.longValue = frozenU; if (frozenU >= intro.endU) intro.done = true }
    } else if (intro != null) LaunchedEffect(intro) {
        var t0 = -1L
        while (!intro.done) {
            val f = withFrameMillis { it }
            if (t0 < 0) t0 = f
            val t = f - t0
            intro.t.longValue = t
            HomeIntroBus.barAlpha.floatValue = intro.barAlpha()
            if (intro.skipped) { intro.done = true; break }
            if (intro.splashEnd.longValue < 0) {
                // ready: the weather board has content (or we stop waiting at the splash's max)
                val e = CloudSplashSpec.splashEnd(t, boardNow.loaded || t >= CloudSplashSpec.SPLASH_MIN_MS)
                if (e >= 0) { intro.setOrder(HomeSequence.order(stateNow.hasTasks)); intro.splashEnd.longValue = e }
            } else if (t - intro.splashEnd.longValue >= intro.endU) intro.done = true
        }
        HomeIntroBus.barAlpha.floatValue = 1f
        done()
    }
    // the host's chat bar stays hidden until the last card has landed; never left hidden if Home goes away mid-intro
    if (intro != null && frozenU == null) androidx.compose.runtime.DisposableEffect(intro) {
        if (!intro.done) HomeIntroBus.barAlpha.floatValue = 0f
        onDispose { HomeIntroBus.barAlpha.floatValue = 1f }
    }
    val introOn = intro != null && !intro.done
    val scroll = rememberScrollState()
    val backdrop = rememberHomeBackdrop(forceSoftware = forceSoftwareBlur)
    // the host's chat bar fades while a card is lifted; a tap on its area closes the card
    androidx.compose.runtime.DisposableEffect(focus) {
        HomeFocusBus.close = { focus.close() }
        onDispose { if (HomeFocusBus.close != null) HomeFocusBus.close = null; HomeFocusBus.active = false; HomeFocusBus.amount = 0f }
    }
    androidx.compose.runtime.SideEffect { HomeFocusBus.active = focus.isOpen }
    LaunchedEffect(focus) { androidx.compose.runtime.snapshotFlow { focus.progress.value }.collect { HomeFocusBus.amount = it } }

    Box(Modifier.fillMaxSize().then(if (intro != null) Modifier.onGloballyPositioned {
        intro.bounds[HomeIntroKeys.ROOT] = it.boundsInRoot(); intro.rootHeight = it.findRootCoordinates().size.height.toFloat()
    } else Modifier)) {
        Box(Modifier.fillMaxSize().homeBackdropSource(backdrop)) {
        if (drawSky) HomeSkyBackground(night = intro?.night ?: s.board.night, modifier = Modifier.fillMaxSize())
        androidx.compose.runtime.CompositionLocalProvider(LocalHomeFocus provides if (introOn) null else focus) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !introOn).padding(top = 12.dp, bottom = if (reserveBottom) 112.dp else 24.dp)) {
            HomeGridV2(s, intro?.takeIf { !it.done }, onWeather = onWeather, onTopic = onTopic, onPickInterests = onPickInterests,
                onMusic = onMusic, onMusicToggle = onMusicToggle, onTodo = onTodo, onNextUp = onNextUp, onAction = onAction) {
                Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                    Text("All widgets", modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAllWidgets)
                        .graphicsLayer { alpha = if (intro == null || intro.done) 1f else 0f }.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)))
                }
            }
        }
        }
        }
        if (introOn) IntroOverlay(intro!!)
        if (chatBar != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .graphicsLayer { alpha = (1f - focus.progress.value) * (if (intro != null && frozenU == null) HomeIntroBus.barAlpha.floatValue else 1f) }) { chatBar() }
        if (focus.shown && !introOn) HomeFocusOverlay(focus, s, backdrop, onWeather = onWeather, onTopic = onTopic, onPickInterests = onPickInterests,
            onMusic = onMusic, onMusicToggle = onMusicToggle, onTodo = onTodo, onNextUp = onNextUp, onAction = onAction)
    }
}

/**
 * The splash cloud + dots, then the glide: drawn on one canvas over the grid while the intro runs. Only [HomeIntroClock.t]
 * changes per frame and it is read here in draw, so nothing recomposes.
 */
@Composable
private fun IntroOverlay(intro: HomeIntroClock) {
    val painter = remember { CloudGlyphPainter(intro.kind, intro.night) }
    val ink = Color(CloudSplashSpec.ink(intro.night))
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { intro.skipped = true } }) {
        val root = intro.bounds[HomeIntroKeys.ROOT] ?: return@Canvas
        val t = intro.t.longValue
        val u = intro.u()
        // the splash's place on the SCREEN (like v1): body centred, 59% of the width, centre at 37.6% of the window height
        val screenH = if (intro.rootHeight > 0f) intro.rootHeight else size.height
        val unit = size.width * CloudSplashSpec.CLOUD_WIDTH
        val sx = size.width / 2f - unit / 2f
        val sy = screenH * CloudSplashSpec.CLOUD_CENTER_Y - root.top - unit * CloudSplashSpec.BODY_BOTTOM / 2f
        val stroke = CloudSplashSpec.STROKE_DP * density
        if (u < 0) {
            val p = CloudSplashSpec.easeOutCubic(t.toFloat() / CloudSplashSpec.CLOUD_IN_MS)
            val sc = CloudSplashSpec.CLOUD_SCALE_FROM + (1f - CloudSplashSpec.CLOUD_SCALE_FROM) * p
            val su = unit * sc
            painter.draw(this, size.width / 2f - su / 2f, sy + (unit - su) * CloudSplashSpec.BODY_BOTTOM / 2f, su, ink, stroke, alpha = p)
            drawDots(t, sx, sy, unit, ink, if (t <= CloudSplashSpec.DOTS_IN_AT_MS) 0f else CloudSplashSpec.easeOutCubic((t - CloudSplashSpec.DOTS_IN_AT_MS).toFloat() / CloudSplashSpec.DOTS_IN_MS), intro)
            return@Canvas
        }
        if (u < HomeIntroSpec.GLIDE_MS) {
            val g = HomeIntroSpec.glide(u)
            val icon = intro.bounds[HomeIntroKeys.ICON]
            var left = sx; var top = sy; var un = unit
            if (icon != null) {
                val f = CloudGlyphPainter.fitIcon(icon.left - root.left, icon.top - root.top, icon.width, icon.height)
                left = CloudSplashSpec.lerp(sx, f[0], g); top = CloudSplashSpec.lerp(sy, f[1], g); un = CloudSplashSpec.lerp(unit, f[2], g)
            }
            val col = lerp(ink, HomeGridLook.ink, g)
            painter.draw(this, left, top, un, col, CloudSplashSpec.lerp(stroke, 1.6f * density, g))
            // the dots fade as the cloud leaves
            val a = 1f - CloudSplashSpec.progress(u, 0, HomeIntroSpec.DOTS_OUT_MS)
            if (a > 0f) drawDots(t, sx, sy, unit, ink, a, intro)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDots(t: Long, sx: Float, sy: Float, unit: Float, ink: Color, alpha: Float, intro: HomeIntroClock) {
    if (alpha <= 0f) return
    val r = CloudSplashSpec.DOT_DP * density / 2f; val gap = CloudSplashSpec.DOT_GAP_DP * density
    val cy = sy + unit * (CloudSplashSpec.BODY_BOTTOM + CloudSplashSpec.dotsDrop(intro.kind)) + CloudSplashSpec.DOTS_BELOW_CLOUD_DP * density + r
    val x0 = size.width / 2f - (3 * 2 * r + 2 * gap) / 2f + r
    val hop = CloudSplashSpec.HOP_DP * density
    for (i in 0..2) drawCircle(ink, r, Offset(x0 + i * (2 * r + gap), cy - CloudSplashSpec.dotLift(i, t) * hop), alpha = alpha)
}
