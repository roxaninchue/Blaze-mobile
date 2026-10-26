package com.blaze.assistant.ui

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxHeight
import com.blaze.assistant.stage.ChatMode
import com.blaze.assistant.stage.MotionSpec
import com.blaze.assistant.stage.PerfTier
import com.blaze.assistant.stage.StageAction
import com.blaze.assistant.stage.StageItem
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageMode
import com.blaze.assistant.stage.StagePerf
import com.blaze.assistant.stage.StageReducer
import com.blaze.assistant.stage.StageSlot
import com.blaze.assistant.stage.StageState
import com.blaze.assistant.stage.StageStore
import com.blaze.assistant.stage.StageTimer
import com.blaze.assistant.stage.TimerState
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
    fun player(ctx: Context, u: String): androidx.media3.exoplayer.ExoPlayer {
        val p = player ?: androidx.media3.exoplayer.ExoPlayer.Builder(ctx.applicationContext).build().also { player = it }
        if (u != url) { url = u; p.setMediaItem(androidx.media3.common.MediaItem.fromUri(u)); p.prepare(); p.playWhenReady = true
            // Music pauses while a video plays (two sounds at once is never wanted).
            try { com.blaze.assistant.mediaapps.MusicService.current?.transport("pause") } catch (_: Exception) { } }
        return p
    }
    fun release() { try { player?.release() } catch (_: Exception) { }; player = null; url = "" }
}

/**
 * The Stage: the chat pane as one playground. [chat] draws the chat stream (the normal MessageList) in whatever space the
 * layout gives it. Heroes, widgets, the docked chat and the chat tile all live here; the top bar / reply bar flags are read by
 * ChatScreen. Back steps toward the normal chat (StageStore.back) and every element's ⋯ menu has Reset layout.
 */
@Composable
fun StageHost(lastMessage: String, chat: @Composable (Modifier) -> Unit) {
    val ctx = LocalContext.current
    val st by StageStore.state.collectAsState()
    val timers by StageStore.timers.collectAsState()
    val tier = remember { StageDevice.tier(ctx) }
    val motion = remember(tier) { StagePerf.motion(tier, if (MotionTokens.enabled) 1f else 0f) }
    var menuFor by remember { mutableStateOf<String?>(null) }

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
    var music by remember { mutableStateOf(StageMusicNow()) }
    val hasMusic = st.items.any { it.kind == StageKind.MUSIC }
    LaunchedEffect(hasMusic, now) {
        if (hasMusic) music = com.blaze.assistant.mediaapps.StageMusic.now().let { StageMusicNow(it.playing, it.title, it.artist, it.posMs, it.durMs, it.service) }
    }
    // A closed video card frees the shared player.
    val hasVideo = st.items.any { it.kind == StageKind.VIDEO }
    LaunchedEffect(hasVideo) { if (!hasVideo) StageVideo.release() }

    fun data(i: StageItem): StageData = when (i.kind) {
        StageKind.TIMER -> StageData(now = now, timer = timers[i.id])
        StageKind.MUSIC -> StageData(now = now, music = music)
        StageKind.FLASHCARDS -> {
            val q = try { com.blaze.assistant.agent.Flashcards.queue(i.props["deck"].orEmpty()) } catch (_: Exception) { emptyList() }
            val card = q.firstOrNull()
            StageData(now = now, cardFront = card?.front.orEmpty(), cardBack = card?.back.orEmpty(), cardsLeft = q.size)
        }
        else -> StageData(now = now)
    }

    fun event(i: StageItem, e: StageEvent) {
        when (e) {
            is StageEvent.Timer -> StageStore.timerOp(ctx, i.id, e.op, e.minutes)
            is StageEvent.Music -> com.blaze.assistant.mediaapps.StageMusic.op(ctx, e.op)
            is StageEvent.QuizAnswer -> {
                val qs = com.blaze.assistant.stage.StageTools.questions(i.props["questions"]); val at = i.props["at"]?.toIntOrNull() ?: 0
                val right = qs.getOrNull(at)?.answer == e.index
                StageStore.dispatch(StageAction.Update(i.id, mapOf("picked" to e.index.toString(), "score" to ((i.props["score"]?.toIntOrNull() ?: 0) + if (right) 1 else 0).toString())))
            }
            StageEvent.QuizNext -> StageStore.dispatch(StageAction.Update(i.id, mapOf("at" to ((i.props["at"]?.toIntOrNull() ?: 0) + 1).toString(), "picked" to "")))
            StageEvent.Flip -> StageStore.dispatch(StageAction.Update(i.id, mapOf("flipped" to if (i.props["flipped"] == "true") "" else "true")))
            is StageEvent.Card -> {
                val q = try { com.blaze.assistant.agent.Flashcards.queue(i.props["deck"].orEmpty()) } catch (_: Exception) { emptyList() }
                q.firstOrNull()?.let { com.blaze.assistant.agent.Flashcards.answer(it.id, e.grade) }
                StageStore.dispatch(StageAction.Update(i.id, mapOf("flipped" to "", "n" to System.nanoTime().toString())))
            }
            is StageEvent.Toggle -> {
                val done = i.props["done"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
                if (!done.add(e.index)) done.remove(e.index)
                StageStore.dispatch(StageAction.Update(i.id, mapOf("done" to done.sorted().joinToString(","))))
            }
            StageEvent.OpenFull -> i.kind.fullView?.let { Views.open(it) }
            StageEvent.Expand -> StageStore.dispatch(StageAction.Expand(i.id))
            StageEvent.Shrink -> StageStore.dispatch(StageAction.Shrink(i.id))
            StageEvent.Close -> StageStore.dispatch(StageAction.Close(i.id))
            StageEvent.Menu -> menuFor = i.id
        }
    }

    val videoSlot: @Composable (StageItem) -> (@Composable (Modifier) -> Unit) = { i ->
        { m -> AndroidView(factory = { c -> androidx.media3.ui.PlayerView(c).apply { useController = true; player = StageVideo.player(c, i.props["url"].orEmpty()) } },
            modifier = m, onRelease = { it.player = null }) }
    }

    val hero = st.hero
    var stageOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onPlaced { stageOrigin = it }) {
        val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f); val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val dens = LocalDensity.current
        @Composable fun WidgetTile(i: StageItem, narrow: Boolean) {
            var dx by remember(i.id) { mutableFloatStateOf(0f) }; var dy by remember(i.id) { mutableFloatStateOf(0f) }
            var origin by remember(i.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            StageWidget(i, data(i), narrow = narrow, video = if (i.kind == StageKind.VIDEO) videoSlot(i) else null,
                modifier = Modifier
                    .onPlaced { origin = it }
                    .graphicsLayer { translationX = dx; translationY = dy; alpha = (1f - abs(dx) / (stageW * 0.9f)).coerceIn(0.2f, 1f) }
                    .pointerInput(i.id) {
                        // Long-press and drag = move it (drop on top / side / bottom / anywhere); a fast sideways drag = dismiss.
                        detectDragGesturesAfterLongPress(onDrag = { ch, d -> ch.consume(); dx += d.x; dy += d.y },
                            onDragEnd = {
                                val fx = (origin.x - stageOrigin.x + dx) / stageW; val fy = (origin.y - stageOrigin.y + dy) / stageH
                                if (abs(dx) > stageW * 0.45f && abs(dy) < stageH * 0.12f) StageStore.dispatch(StageAction.Close(i.id))
                                else { val slot = StageReducer.slotForDrop(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)); StageStore.dispatch(StageAction.Move(i.id, slot, fx.coerceIn(0f, 0.85f), fy.coerceIn(0f, 0.9f))) }
                                dx = 0f; dy = 0f
                            }, onDragCancel = { dx = 0f; dy = 0f })
                    }
                    .clickable(onClickLabel = "Make ${i.kind.label} big") { event(i, StageEvent.Expand) },
                on = { e -> event(i, e) })
        }

        Column(Modifier.fillMaxSize()) {
            // Top slot.
            val top = st.widgetsIn(StageSlot.TOP)
            AnimatedVisibility(top.isNotEmpty() || (st.chat.mode == ChatMode.WIDGET && st.chat.slot == StageSlot.TOP), enter = enterOf(motion), exit = exitOf(motion)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    top.forEach { key(it.id) { WidgetTile(it, false) } }
                    if (st.chat.mode == ChatMode.WIDGET && st.chat.slot == StageSlot.TOP) StageChatTile(lastMessage) { StageStore.dispatch(StageAction.SetChat(ChatMode.FULL)) }
                }
            }
            // Hero.
            val heroBox = Modifier.fillMaxWidth()
            if (hero != null) {
                Box(heroBox.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(hero.id, transitionSpec = { enterOf(motion) togetherWith exitOf(motion) }, label = "hero") { id ->
                        val h = st.byId(id) ?: return@AnimatedContent
                        var drag by remember(id) { mutableFloatStateOf(0f) }
                        StageHero(h, data(h), low = tier == PerfTier.LOW, video = if (h.kind == StageKind.VIDEO) videoSlot(h) else null,
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
            // Chat stream: full, the newest few (drag its handle to show more or fewer), as a tile, or hidden.
            when (st.chat.mode) {
                ChatMode.FULL -> Box(Modifier.fillMaxWidth().weight(1f)) { chat(Modifier.fillMaxSize()) }
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
                    Box(Modifier.fillMaxWidth().height((st.chat.count * 64 + 24).dp.coerceAtMost(with(dens) { (stageH * 0.6f).toDp() }))) { chat(Modifier.fillMaxSize()) }
                }
                ChatMode.WIDGET, ChatMode.HIDDEN -> if (hero == null) Box(Modifier.weight(1f))
            }
            // Bottom slot (+ the chat tile by default).
            val bottom = st.widgetsIn(StageSlot.BOTTOM)
            val tileHere = st.chat.mode == ChatMode.WIDGET && st.chat.slot != StageSlot.TOP
            AnimatedVisibility(bottom.isNotEmpty() || tileHere, enter = enterOf(motion), exit = exitOf(motion)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tileHere) StageChatTile(lastMessage) { StageStore.dispatch(StageAction.SetChat(ChatMode.FULL)) }
                    bottom.forEach { key(it.id) { WidgetTile(it, false) } }
                }
            }
        }
        // Side rail.
        val side = st.widgetsIn(StageSlot.SIDE)
        if (side.isNotEmpty()) Column(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            side.forEach { key(it.id) { WidgetTile(it, true) } }
        }
        // Floating widgets (where the user dropped them).
        st.widgetsIn(StageSlot.FLOAT).forEach { i ->
            key(i.id) {
                Box(Modifier.offset { IntOffset((i.x * stageW).roundToInt(), (i.y * stageH).roundToInt()) }.width(if (i.kind == StageKind.VIDEO) 200.dp else 180.dp)) { WidgetTile(i, false) }
            }
        }
        // Top bar hidden: a small tab brings it back.
        if (!st.topBar) Box(Modifier.align(Alignment.TopCenter).padding(top = 2.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = "Show the top bar") {
            StageStore.dispatch(StageAction.SetChrome(topBar = true)) }.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(LocalBlaze.current.hair))
        }
    }

    menuFor?.let { id ->
        val i = st.byId(id)
        if (i == null) { menuFor = null; return@let }
        val items = buildList<Pair<String, () -> Unit>> {
            if (i.mode == StageMode.HERO) add("Shrink to a widget" to { StageStore.dispatch(StageAction.Shrink(id)); Unit }) else add("Make it big" to { StageStore.dispatch(StageAction.Expand(id)); Unit })
            if (i.kind.fullView != null) add("Open in full view" to { Views.open(i.kind.fullView); Unit })
            add("Move to top" to { StageStore.dispatch(StageAction.Move(id, StageSlot.TOP)); Unit })
            add("Move to the side" to { StageStore.dispatch(StageAction.Move(id, StageSlot.SIDE)); Unit })
            add("Move to bottom" to { StageStore.dispatch(StageAction.Move(id, StageSlot.BOTTOM)); Unit })
            if (st.chat.mode != ChatMode.FULL) add("Show the whole chat" to { StageStore.dispatch(StageAction.SetChat(ChatMode.FULL)); Unit })
            else add("Chat: last 3 messages" to { StageStore.dispatch(StageAction.SetChat(ChatMode.LAST, 3)); Unit })
            add("Chat as a small tile" to { StageStore.dispatch(StageAction.SetChat(ChatMode.WIDGET)); Unit })
            add("Hide the chat" to { StageStore.dispatch(StageAction.SetChat(ChatMode.HIDDEN)); Unit })
            add("Focus (hide top and reply bars)" to { StageStore.dispatch(StageAction.Layout("focus")); Unit })
            add("Reset layout" to { StageStore.dispatch(StageAction.Reset); Unit })
            add("Close" to { StageStore.dispatch(StageAction.Close(id)); Unit })
        }
        BlazeDialogCardMenu(items) { menuFor = null }
    }
}

@Composable
private fun BlazeDialogCardMenu(items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        BlazeMenuCard(items, onDismiss)
    }
}

private fun enterOf(m: MotionSpec) = if (m.enterMs <= 0) androidx.compose.animation.EnterTransition.None
    else if (!m.morph) fadeIn(tween(m.enterMs))
    else if (m.springs) fadeIn(tween(m.enterMs)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.88f)
    else fadeIn(tween(m.enterMs)) + scaleIn(tween(m.enterMs), initialScale = 0.92f)

private fun exitOf(m: MotionSpec) = if (m.exitMs <= 0) androidx.compose.animation.ExitTransition.None
    else if (!m.morph) fadeOut(tween(m.exitMs)) else fadeOut(tween(m.exitMs)) + scaleOut(tween(m.exitMs), targetScale = 0.92f)

private fun Modifier.onPlaced(f: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.onGloballyPositioned { f(it.positionInRoot()) }
