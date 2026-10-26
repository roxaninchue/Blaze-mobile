package com.blaze.assistant.ui

import android.app.ActivityManager
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AlignHorizontalRight
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.blaze.assistant.stage.ChatMode
import com.blaze.assistant.stage.MotionSpec
import com.blaze.assistant.stage.PerfTier
import com.blaze.assistant.stage.StageAction
import com.blaze.assistant.stage.StageItem
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageLayers
import com.blaze.assistant.stage.StageMode
import com.blaze.assistant.stage.StagePerf
import com.blaze.assistant.stage.StageReducer
import com.blaze.assistant.stage.StageSlot
import com.blaze.assistant.stage.StageStore
import com.blaze.assistant.stage.StageTimer
import com.blaze.assistant.stage.StageTools
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** The phone's motion tier, read once (RAM, low-RAM flag, refresh rate, cores, the animator scale). */
object StageDevice {
    @Volatile private var cached: PerfTier? = null
    fun tier(ctx: Context): PerfTier = cached ?: try {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val hz = try { (ctx.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate) ?: 60f } catch (_: Exception) { 60f }
        val scale = try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
        StagePerf.tier(am?.isLowRamDevice == true, mi.totalMem / (1024 * 1024), hz, scale, Runtime.getRuntime().availableProcessors())
    } catch (_: Throwable) { PerfTier.MID }.also { cached = it }
}

/** One shared video player for the Stage (one video at a time; a second video replaces the first). */
object StageVideo {
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private var url: String = ""
    /** Play as soon as it's ready: only for a video just shown, never for a card restored after the app was closed. */
    @Volatile var autoplay = false
    val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    fun player(ctx: Context, u: String): androidx.media3.exoplayer.ExoPlayer {
        val p = player ?: androidx.media3.exoplayer.ExoPlayer.Builder(ctx.applicationContext).build().also { player = it }
        if (u != url) { url = u; p.setMediaItem(androidx.media3.common.MediaItem.fromUri(u)); p.prepare(); p.playWhenReady = autoplay; autoplay = false
            // Music pauses while a video plays (two sounds at once is never wanted).
            try { com.blaze.assistant.mediaapps.MusicService.current?.transport("pause") } catch (_: Exception) { } }
        return p
    }
    /** The next playback speed after [now] ("1" -> "1.25" … -> "0.75" -> "1"); applied to the player. */
    fun nextSpeed(now: String): String {
        val cur = now.toFloatOrNull() ?: 1f
        val n = SPEEDS[(SPEEDS.indexOfFirst { abs(it - cur) < 0.01f } + 1).mod(SPEEDS.size)]
        try { player?.setPlaybackSpeed(n) } catch (_: Exception) { }
        return if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
    }
    fun pause() { try { player?.pause() } catch (_: Exception) { } }
    /** A Stage video is playing now (MainActivity takes it into picture-in-picture when the user leaves Blaze). */
    fun playing(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }
    /** Bumped when the picture-in-picture window gives the player back, so the card's view takes the video surface again. */
    val gen = androidx.compose.runtime.mutableIntStateOf(0)
    fun current(): androidx.media3.exoplayer.ExoPlayer? = player
    fun release() { try { player?.release() } catch (_: Exception) { }; player = null; url = "" }
}

/**
 * 0.46.1 the browser card's WebView: the built-in browser's current tab (the same WebView browser_* tools drive), hosted
 * while the card is big and given back (offscreen, paused once Blaze is idle) when it shrinks or the app goes to the background.
 */
object StageWeb {
    fun now(): StageBrowserNow = try {
        val e = com.blaze.assistant.browser.BrowserEngine
        e.tab()?.let { t -> StageBrowserNow(true, t.title, t.url, t.progress, t.canBack, t.canForward) } ?: StageBrowserNow()
    } catch (_: Throwable) { StageBrowserNow() }

    fun op(ctx: Context, op: String) {
        val e = com.blaze.assistant.browser.BrowserEngine
        try {
            e.init(ctx)
            val t = e.tab()
            when {
                op.startsWith("go:") -> { val q = op.removePrefix("go:"); if (t == null) e.newTab(ctx, com.blaze.assistant.browser.BrowserEngine.toUrl(q)) else e.load(t, q) }
                t == null -> {}
                op == "back" -> e.back(t)
                op == "forward" -> e.forward(t)
                op == "reload" -> t.web.reload()
                op == "stop" -> t.live?.stopLoading()
            }
        } catch (x: Throwable) { com.blaze.assistant.device.CrashLog.quiet("StageWeb.op", x) }
    }

    /** Takes the WebView back from the card: offscreen size again, dialogs to the app, the tab may sleep. */
    fun release(v: android.webkit.WebView?) {
        val e = com.blaze.assistant.browser.BrowserEngine
        try { v?.let { w -> (w.parent as? ViewGroup)?.removeView(w); e.layoutOffscreen(w) }; e.attach(null); e.setUiVisible(false) } catch (_: Throwable) { }
    }
}

/** The drag ghost: the widget under the finger, drawn above everything (chat, other widgets, the top and reply bars). */
private data class Ghost(val item: StageItem, val origin: Offset, val size: IntSize)

/**
 * The Stage: the chat pane as one playground. [chat] draws the chat stream (the normal MessageList) in whatever space the
 * layout gives it. Heroes, widgets, the docked chat and the chat tile all live here; the top bar / reply bar flags are read by
 * ChatScreen. Back steps toward the normal chat (StageStore.back) and every element's ⋯ menu has Reset layout.
 * 0.46.1 paint order ([StageLayers]): chat at the bottom, docked and floating widgets above it, the last one touched in front,
 * and a widget being dragged in its own window above the whole screen, lifted slightly.
 */
@Composable
fun StageHost(lastMessage: String, chat: @Composable (Modifier) -> Unit) {
    val ctx = LocalContext.current
    val st by StageStore.state.collectAsState()
    val timers by StageStore.timers.collectAsState()
    val tier = remember { StageDevice.tier(ctx) }
    val motion = remember(tier) { StagePerf.motion(tier, if (MotionTokens.enabled) 1f else 0f) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var ghost by remember { mutableStateOf<Ghost?>(null) }
    var ghostDx by remember { mutableFloatStateOf(0f) }; var ghostDy by remember { mutableFloatStateOf(0f) }

    // Live values: one clock tick per second, and only while something on the Stage shows time (no per-frame work).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val needsClock = st.items.any { it.kind == StageKind.TIMER && timers[it.id]?.running == true } || st.items.any { it.kind == StageKind.MUSIC }
    LaunchedEffect(needsClock) {
        while (needsClock) {
            now = System.currentTimeMillis()
            val running = StageStore.timers.value.values.filter { it.running }
            // Catch phases that ended (the alarm also does this when the app is closed).
            running.forEach { t -> if (now >= t.endAt) StageStore.onAlarm(ctx, t.id) }
            val next = running.minOfOrNull { StageTimer.nextTickDelay(StageTimer.remaining(it, now)) } ?: 1000L
            delay(next.coerceIn(50L, 1000L))
        }
    }
    // Music: the one MusicService (the same media session as the notification and lock screen), read once a second.
    var music by remember { mutableStateOf(com.blaze.assistant.mediaapps.StageMusicLogic.Snapshot()) }
    var musicPoke by remember { mutableIntStateOf(0) }
    val hasMusic = st.items.any { it.kind == StageKind.MUSIC }
    LaunchedEffect(hasMusic, now, musicPoke) { if (hasMusic) music = com.blaze.assistant.mediaapps.StageMusic.snapshot() }
    // A closed video card frees the shared player.
    val hasVideo = st.items.any { it.kind == StageKind.VIDEO }
    LaunchedEffect(hasVideo) { if (!hasVideo) StageVideo.release() }
    val hasBrowser = st.items.any { it.kind == StageKind.BROWSER }
    LaunchedEffect(hasBrowser) { if (hasBrowser) com.blaze.assistant.browser.BrowserEngine.init(ctx) }

    fun cards(i: StageItem) = try { com.blaze.assistant.agent.Flashcards.queue(i.props["deck"].orEmpty()).let { q ->
        i.props["seed"]?.toLongOrNull()?.let { q.shuffled(kotlin.random.Random(it)) } ?: q } } catch (_: Exception) { emptyList() }

    fun data(i: StageItem): StageData = when (i.kind) {
        StageKind.TIMER -> StageData(now = now, timer = timers[i.id])
        StageKind.MUSIC -> StageData(now = now, music = music)
        StageKind.FLASHCARDS -> { val q = cards(i); val card = q.firstOrNull()
            StageData(now = now, cardFront = card?.front.orEmpty(), cardBack = card?.back.orEmpty(), cardsLeft = q.size) }
        StageKind.BROWSER -> StageData(now = now, browser = StageWeb.now())
        else -> StageData(now = now)
    }

    fun update(i: StageItem, p: Map<String, String>) { StageStore.dispatch(StageAction.Update(i.id, p)) }

    fun event(i: StageItem, e: StageEvent) {
        when (e) {
            is StageEvent.Timer -> StageStore.timerOp(ctx, i.id, e.op, e.minutes)
            is StageEvent.Music -> { com.blaze.assistant.mediaapps.StageMusic.op(ctx, e.op); musicPoke++ }
            is StageEvent.QuizAnswer -> {
                val qs = StageTools.questions(i.props["questions"]); val round = StageQuiz.round(i.props, qs.size)
                val qi = round.getOrNull(i.props["at"]?.toIntOrNull() ?: 0) ?: return
                update(i, mapOf("answers" to StageQuiz.encode(StageQuiz.answers(i.props) + (qi to e.index)), "picked" to ""))
            }
            StageEvent.QuizNext -> update(i, mapOf("at" to ((i.props["at"]?.toIntOrNull() ?: 0) + 1).toString()))
            StageEvent.QuizBack -> update(i, mapOf("at" to ((i.props["at"]?.toIntOrNull() ?: 0) - 1).coerceAtLeast(0).toString()))
            StageEvent.QuizHint -> { val qs = StageTools.questions(i.props["questions"]); val round = StageQuiz.round(i.props, qs.size)
                round.getOrNull(i.props["at"]?.toIntOrNull() ?: 0)?.let { update(i, mapOf("hint$it" to "true")) } }
            StageEvent.QuizRetryWrong -> { val qs = StageTools.questions(i.props["questions"]); val round = StageQuiz.round(i.props, qs.size)
                val ans = StageQuiz.answers(i.props); val wrong = StageQuiz.wrong(qs, ans, round)
                if (wrong.isNotEmpty()) update(i, mapOf("only" to wrong.joinToString(","), "at" to "0", "answers" to StageQuiz.encode(ans - wrong.toSet()))) }
            StageEvent.Flip -> update(i, mapOf("flipped" to if (i.props["flipped"] == "true") "" else "true"))
            StageEvent.Shuffle -> update(i, mapOf("seed" to System.nanoTime().toString(), "flipped" to ""))
            is StageEvent.Card -> {
                cards(i).firstOrNull()?.let { com.blaze.assistant.agent.Flashcards.answer(it.id, e.grade) }
                update(i, mapOf("flipped" to "", "seen" to ((i.props["seen"]?.toIntOrNull() ?: 0) + 1).toString(),
                    "known" to ((i.props["known"]?.toIntOrNull() ?: 0) + if (e.grade >= 2) 1 else 0).toString()))
            }
            is StageEvent.Toggle -> {
                val done = i.props["done"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
                if (!done.add(e.index)) done.remove(e.index)
                update(i, mapOf("done" to done.sorted().joinToString(",")))
            }
            is StageEvent.Props -> update(i, e.props)
            is StageEvent.Browser -> StageWeb.op(ctx, e.op)
            is StageEvent.Video -> when (e.op) {
                "speed" -> update(i, mapOf("speed" to StageVideo.nextSpeed(i.props["speed"].orEmpty())))
                "fullscreen" -> if (i.immersive) {
                    StageStore.dispatch(StageAction.Show(i.kind, mapOf("immersive" to "false"), StageMode.HERO, null, i.id))
                    StageStore.dispatch(StageAction.SetChrome(topBar = true, reply = com.blaze.assistant.stage.ReplyBar.FULL))
                } else { StageStore.dispatch(StageAction.Show(i.kind, mapOf("immersive" to "true"), StageMode.HERO, null, i.id)); StageStore.dispatch(StageAction.Resize(i.id, 1f)) }
            }
            StageEvent.OpenFull -> if (i.kind == StageKind.BROWSER) com.blaze.assistant.browser.BrowserTools.show(ctx) else i.kind.fullView?.let { Views.open(it) }
            StageEvent.Expand -> StageStore.dispatch(StageAction.Expand(i.id))
            StageEvent.Shrink -> StageStore.dispatch(StageAction.Shrink(i.id))
            StageEvent.Close -> StageStore.dispatch(StageAction.Close(i.id))
            StageEvent.Menu -> menuFor = i.id
        }
    }

    val videoSlot: @Composable (StageItem) -> (@Composable (Modifier) -> Unit) = { i ->
        { m -> key(StageVideo.gen.intValue) { AndroidView(factory = { c -> androidx.media3.ui.PlayerView(c).apply { useController = true; setShowSubtitleButton(true)
                player = StageVideo.player(c, i.props["url"].orEmpty()) } },
            modifier = m, onRelease = { it.player = null }) } }
    }
    // The browser card's WebView (hero only: a collapsed card draws a title row, no WebView).
    val webSlot: @Composable (Modifier) -> Unit = { m ->
        val tab = com.blaze.assistant.browser.BrowserEngine.tab()
        val live = tab?.live
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
        var shown by remember { mutableIntStateOf(0) }
        DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                when (ev) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> { com.blaze.assistant.browser.BrowserEngine.setUiVisible(true); shown++ }
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> com.blaze.assistant.browser.BrowserEngine.setUiVisible(false)
                    else -> {}
                }
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        if (tab != null) key(tab.id, live, shown) {
            AndroidView(factory = { c -> FrameLayout(c) }, modifier = m, update = { f ->
                val w = tab.web
                com.blaze.assistant.browser.BrowserEngine.attach(f.context)
                if (w.parent !== f) { (w.parent as? ViewGroup)?.removeView(w); f.removeAllViews(); f.addView(w, FrameLayout.LayoutParams(-1, -1)) }
            }, onRelease = { f -> val w = (0 until f.childCount).map { f.getChildAt(it) }.filterIsInstance<android.webkit.WebView>().firstOrNull(); f.removeAllViews(); StageWeb.release(w) })
        }
    }

    val hero = st.hero
    val view = LocalView.current
    var stageOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onWindowPos { stageOrigin = it }) {
        val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f); val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        LaunchedEffect(stageW, stageH) { StageStore.aspect = stageW / stageH }
        val dens = LocalDensity.current
        @Composable fun WidgetTile(i: StageItem, narrow: Boolean) {
            var origin by remember(i.id) { mutableStateOf(Offset.Zero) }
            var sz by remember(i.id) { mutableStateOf(IntSize.Zero) }
            val dragging = ghost?.item?.id == i.id
            StageWidget(i, data(i), narrow = narrow, video = if (i.kind == StageKind.VIDEO) videoSlot(i) else null,
                modifier = Modifier
                    .onGloballyPositioned { origin = it.positionInWindow(); sz = it.size }
                    // The tile stays in place (invisible) while its ghost follows the finger above everything.
                    .graphicsLayer { alpha = if (dragging) 0f else 1f }
                    .pointerInput(i.id) {
                        // Long-press and drag = move it (drop on top / side / bottom / anywhere); a fast sideways drag = dismiss.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { StageStore.dispatch(StageAction.Touch(i.id)); ghostDx = 0f; ghostDy = 0f; ghost = Ghost(i, origin, sz) },
                            onDrag = { ch, d -> ch.consume(); ghostDx += d.x; ghostDy += d.y },
                            onDragEnd = {
                                val fx = (origin.x - stageOrigin.x + ghostDx) / stageW; val fy = (origin.y - stageOrigin.y + ghostDy) / stageH
                                if (abs(ghostDx) > stageW * 0.45f && abs(ghostDy) < stageH * 0.12f) StageStore.dispatch(StageAction.Close(i.id))
                                else { val slot = StageReducer.slotForDrop(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)); StageStore.dispatch(StageAction.Move(i.id, slot, fx.coerceIn(0f, 0.85f), fy.coerceIn(0f, 0.9f))) }
                                ghost = null; ghostDx = 0f; ghostDy = 0f
                            }, onDragCancel = { ghost = null; ghostDx = 0f; ghostDy = 0f })
                    }
                    .clickable(onClickLabel = "Make ${i.kind.label} big") { event(i, StageEvent.Expand) },
                on = { e -> event(i, e) })
        }

        Column(Modifier.fillMaxSize()) {
            // Top slot (above the chat stream in paint order, so a tile dragged down never slides under a bubble).
            val top = st.widgetsIn(StageSlot.TOP)
            AnimatedVisibility(top.isNotEmpty() || (st.chat.mode == ChatMode.WIDGET && st.chat.slot == StageSlot.TOP), enter = enterOf(motion), exit = exitOf(motion),
                modifier = Modifier.zIndex(StageLayers.DOCK)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    top.forEach { key(it.id) { WidgetTile(it, false) } }
                    if (st.chat.mode == ChatMode.WIDGET && st.chat.slot == StageSlot.TOP) StageChatTile(lastMessage) { StageStore.dispatch(StageAction.SetChat(ChatMode.FULL)) }
                }
            }
            // Hero.
            if (hero != null) {
                Box(Modifier.fillMaxWidth().weight(1f).zIndex(StageLayers.HERO).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(hero.id, transitionSpec = { enterOf(motion) togetherWith exitOf(motion) }, label = "hero") { id ->
                        val h = st.byId(id) ?: return@AnimatedContent
                        var drag by remember(id) { mutableFloatStateOf(0f) }
                        StageHero(h, data(h), low = tier == PerfTier.LOW, video = if (h.kind == StageKind.VIDEO) videoSlot(h) else null,
                            web = if (h.kind == StageKind.BROWSER) webSlot else null,
                            modifier = Modifier.fillMaxWidth(h.scale).fillMaxHeight(h.scale)
                                .graphicsLayer { translationY = drag.coerceAtLeast(0f) }
                                .pointerInput(id) {
                                    // Swipe the hero down = back to the chat (it becomes a widget).
                                    detectDragGestures(onDrag = { ch, d -> if (d.y > 0 || drag > 0) { ch.consume(); drag += d.y } },
                                        onDragEnd = { if (drag > with(dens) { 90.dp.toPx() }) StageStore.dispatch(StageAction.Shrink(id)); drag = 0f },
                                        onDragCancel = { drag = 0f })
                                },
                            on = { e -> event(h, e) })
                    }
                }
            }
            // Chat stream: full, the newest few (drag its handle to show more or fewer), as a tile, or hidden. Bottom of the paint order.
            when (st.chat.mode) {
                ChatMode.FULL -> Box(Modifier.fillMaxWidth().weight(1f).zIndex(StageLayers.CHAT)) { chat(Modifier.fillMaxSize()) }
                ChatMode.LAST -> {
                    if (hero == null) Box(Modifier.weight(1f))
                    var dragDy by remember { mutableFloatStateOf(0f) }
                    Box(Modifier.fillMaxWidth().height(14.dp).pointerInput(Unit) {
                        detectDragGestures(onDrag = { ch, d -> ch.consume(); dragDy += d.y },
                            onDragEnd = { val l = StageReducer.chatCountForDrag(st.chat.count, with(dens) { dragDy.toDp().value }); StageStore.dispatch(StageAction.SetChat(l.mode, l.count)); dragDy = 0f },
                            onDragCancel = { dragDy = 0f })
                    }.semantics { contentDescription = "Drag to show more or fewer messages" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(LocalBlaze.current.hair))
                    }
                    Box(Modifier.fillMaxWidth().zIndex(StageLayers.CHAT).height((st.chat.count * 64 + 24).dp.coerceAtMost(with(dens) { (stageH * 0.6f).toDp() }))) { chat(Modifier.fillMaxSize()) }
                }
                ChatMode.WIDGET, ChatMode.HIDDEN -> if (hero == null) Box(Modifier.weight(1f))
            }
            // Bottom slot (+ the chat tile by default).
            val bottom = st.widgetsIn(StageSlot.BOTTOM)
            val tileHere = st.chat.mode == ChatMode.WIDGET && st.chat.slot != StageSlot.TOP
            AnimatedVisibility(bottom.isNotEmpty() || tileHere, enter = enterOf(motion), exit = exitOf(motion), modifier = Modifier.zIndex(StageLayers.DOCK)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tileHere) StageChatTile(lastMessage) { StageStore.dispatch(StageAction.SetChat(ChatMode.FULL)) }
                    bottom.forEach { key(it.id) { WidgetTile(it, false) } }
                }
            }
        }
        // Side rail.
        val side = st.widgetsIn(StageSlot.SIDE)
        if (side.isNotEmpty()) Column(Modifier.align(Alignment.CenterEnd).zIndex(StageLayers.SIDE).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            side.forEach { key(it.id) { WidgetTile(it, true) } }
        }
        // Floating widgets (where the user dropped them), the last touched in front.
        st.widgetsIn(StageSlot.FLOAT).forEach { i ->
            key(i.id) {
                Box(Modifier.zIndex(StageLayers.z(i, st)).offset { IntOffset((i.x * stageW).roundToInt(), (i.y * stageH).roundToInt()) }
                    .width(if (i.kind == StageKind.VIDEO) 200.dp else 180.dp)) { WidgetTile(i, false) }
            }
        }
        // Top bar hidden: a small tab brings it back.
        if (!st.topBar) Box(Modifier.align(Alignment.TopCenter).zIndex(StageLayers.DOCK).padding(top = 2.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = "Show the top bar") {
            StageStore.dispatch(StageAction.SetChrome(topBar = true)) }.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(LocalBlaze.current.hair))
        }
        // The drag ghost: its own window over the whole screen (above the chat, every widget, the top and reply bars), lifted a
        // little (scale + a static shadow; only its position changes while the finger moves). The window never takes touches
        // (the drag stays with the tile's gesture) and goes away on drop.
        ghost?.let { g ->
            Popup(offset = IntOffset(-stageOrigin.x.roundToInt(), -stageOrigin.y.roundToInt()),
                properties = PopupProperties(focusable = false, clippingEnabled = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
                Box(Modifier.size(with(dens) { view.rootView.width.toDp() }, with(dens) { view.rootView.height.toDp() })) {
                    Box(Modifier
                        .offset { IntOffset(g.origin.x.roundToInt(), g.origin.y.roundToInt()) }
                        .size(with(dens) { g.size.width.toDp() }, with(dens) { g.size.height.toDp() })
                        .graphicsLayer {
                            translationX = ghostDx; translationY = ghostDy
                            scaleX = StageLayers.DRAG_SCALE; scaleY = StageLayers.DRAG_SCALE
                            shadowElevation = 10.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = false
                            alpha = (1f - abs(ghostDx) / (stageW * 0.9f)).coerceIn(0.35f, 1f)
                        }) {
                        StageWidget(g.item, data(g.item), modifier = Modifier.fillMaxSize(), narrow = g.item.slot == StageSlot.SIDE) {}
                    }
                }
            }
        }
    }

    menuFor?.let { id ->
        val i = st.byId(id)
        if (i == null) { menuFor = null; return@let }
        val acts = buildList {
            if (i.mode == StageMode.HERO) add(StageMenuAction(Icons.Rounded.CloseFullscreen, "Shrink to a widget") { StageStore.dispatch(StageAction.Shrink(id)) })
            else add(StageMenuAction(Icons.Rounded.OpenInFull, "Make it big") { StageStore.dispatch(StageAction.Expand(id)) })
            if (i.kind.fullView != null) add(StageMenuAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in full view") { event(i, StageEvent.OpenFull) })
            add(StageMenuAction(Icons.Rounded.VerticalAlignTop, "Move to the top") { StageStore.dispatch(StageAction.Move(id, StageSlot.TOP)) })
            add(StageMenuAction(Icons.Rounded.AlignHorizontalRight, "Move to the side") { StageStore.dispatch(StageAction.Move(id, StageSlot.SIDE)) })
            add(StageMenuAction(Icons.Rounded.VerticalAlignBottom, "Move to the bottom") { StageStore.dispatch(StageAction.Move(id, StageSlot.BOTTOM)) })
            if (st.chat.mode != ChatMode.FULL) add(StageMenuAction(Icons.AutoMirrored.Rounded.Chat, "Show the whole chat") { StageStore.dispatch(StageAction.SetChat(ChatMode.FULL)) })
            else add(StageMenuAction(Icons.Rounded.ViewAgenda, "Chat: last 3 messages") { StageStore.dispatch(StageAction.SetChat(ChatMode.LAST, 3)) })
            add(StageMenuAction(Icons.Rounded.ChatBubbleOutline, "Chat as a small tile") { StageStore.dispatch(StageAction.SetChat(ChatMode.WIDGET)) })
            add(StageMenuAction(Icons.Rounded.VisibilityOff, "Hide the chat") { StageStore.dispatch(StageAction.SetChat(ChatMode.HIDDEN)) })
            add(StageMenuAction(Icons.Rounded.CenterFocusStrong, "Focus: hide the top and reply bars") { StageStore.dispatch(StageAction.Layout("focus")) })
            add(StageMenuAction(Icons.Rounded.RestartAlt, "Reset layout") { StageStore.dispatch(StageAction.Reset) })
            add(StageMenuAction(Icons.Rounded.Close, "Close") { StageStore.dispatch(StageAction.Close(id)) })
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { menuFor = null }) { StageMenuGrid(acts) { menuFor = null } }
    }
}

private fun enterOf(m: MotionSpec) = if (m.enterMs <= 0) androidx.compose.animation.EnterTransition.None
    else if (!m.morph) fadeIn(tween(m.enterMs))
    else if (m.springs) fadeIn(tween(m.enterMs)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.88f)
    else fadeIn(tween(m.enterMs)) + scaleIn(tween(m.enterMs), initialScale = 0.92f)

private fun exitOf(m: MotionSpec) = if (m.exitMs <= 0) androidx.compose.animation.ExitTransition.None
    else if (!m.morph) fadeOut(tween(m.exitMs)) else fadeOut(tween(m.exitMs)) + scaleOut(tween(m.exitMs), targetScale = 0.92f)

private fun Modifier.onWindowPos(f: (Offset) -> Unit): Modifier =
    this.onGloballyPositioned { f(it.positionInWindow()) }

/**
 * 0.46.1 picture-in-picture for a Stage video: when the user leaves Blaze while a Stage video plays, MainActivity enters
 * picture-in-picture and draws only this (the shared player, no controls); back in the app the card takes the video again.
 */
@Composable
fun StageVideoPip() {
    val p = StageVideo.current() ?: return
    AndroidView(factory = { c -> androidx.media3.ui.PlayerView(c).apply { useController = false; player = p } },
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
        onRelease = { it.player = null; StageVideo.gen.intValue++ })
}
