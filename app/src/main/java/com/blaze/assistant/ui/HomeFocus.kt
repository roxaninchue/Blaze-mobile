package com.blaze.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.home.HomeGrid
import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.ui.splash.CloudGlyphPainter

/**
 * RC2 card focus ("spotlight a card"): tapping a card's top / header lifts that card forward, large and centred, from
 * its exact grid cell (its frame grows from the cell's rect to the focus rect, the compact card fading into the full
 * content), while the rest of Home blurs behind it ([HomeBackdrop]). A tap on the blurred sides or Back shrinks it back
 * into its cell with the same easing. Taps inside the expanded card work. Reduce motion: a crossfade in place.
 * Which card is open survives rotation / process recreation (it reopens settled).
 */
@Stable
class HomeFocusState(initial: HomeGrid.Card? = null) {
    /** The card in the overlay (kept while it shrinks back). */
    var card by mutableStateOf(initial)
        internal set
    /** The open target; null while closing or closed. */
    var target by mutableStateOf(initial)
        private set
    val progress = Animatable(if (initial != null) 1f else 0f)
    /** Each card's cell in root coordinates (written by the grid on layout). */
    val cells = mutableStateMapOf<HomeGrid.Card, Rect>()
    /** Set by the overlay: false with reduce motion (crossfade in place, the grid card stays). */
    internal var moving by mutableStateOf(true)

    val isOpen: Boolean get() = target != null
    val shown: Boolean get() = card != null

    fun open(c: HomeGrid.Card) { card = c; target = c }
    fun close() { target = null }

    /** The grid card under a lifted card is hidden (the overlay draws it). */
    fun gridAlpha(c: HomeGrid.Card): Float = if (moving && card == c && (target != null || progress.value > 0f)) 0f else 1f

    companion object {
        val Saver: Saver<HomeFocusState, String> = Saver(save = { it.target?.name ?: "" },
            restore = { s -> HomeFocusState(HomeGrid.Card.values().firstOrNull { it.name == s }) })
    }
}

@Composable
fun rememberHomeFocusState(): HomeFocusState = rememberSaveable(saver = HomeFocusState.Saver) { HomeFocusState() }

/** The focus state of the Home being drawn (null outside Home, in the overlay itself, and while the intro plays). */
val LocalHomeFocus = staticCompositionLocalOf<HomeFocusState?> { null }

/**
 * Lets the chat bar (drawn by the host outside Home, e.g. HomeSurface) hide while a card is focused, and lets the host
 * close the card (a tap on the bar's area dismisses it like a tap outside).
 */
object HomeFocusBus {
    var active by mutableStateOf(false)
        internal set
    /** 0..1 while a card is lifted (the bar fades by 1 - this). */
    var amount by mutableStateOf(0f)
        internal set
    @Volatile var close: (() -> Unit)? = null
}

/** A card's top / header: tapping it focuses the card. Nothing outside a focusable Home. */
fun header(card: HomeGrid.Card): Modifier = Modifier.composed {
    val f = LocalHomeFocus.current
    if (f == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Expand") { f.open(card) }
}

/** Pure: the rect the frame is drawn at, p = 0 (the grid cell) .. 1 (the focus rect). Reduce motion: always the focus rect. */
object HomeFocusGeometry {
    fun frame(cell: Rect?, focus: Rect, p: Float, moving: Boolean): Rect {
        if (!moving || cell == null) return focus
        val t = p.coerceIn(0f, 1f)
        fun l(a: Float, b: Float) = a + (b - a) * t
        return Rect(l(cell.left, focus.left), l(cell.top, focus.top), l(cell.right, focus.right), l(cell.bottom, focus.bottom))
    }
    /** The compact card fades out over the first half, the full content fades in over the second. */
    fun compactAlpha(p: Float): Float = (1f - p / 0.5f).coerceIn(0f, 1f)
    fun fullAlpha(p: Float): Float = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
}

private fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = HomeGridLook.ink) =
    TextStyle(fontFamily = Type.Sans, fontWeight = weight, fontSize = size, color = color)

/**
 * The overlay: the blurred Home ([backdrop]), the dim that closes on a tap, and the lifted card. Compose it only while
 * [HomeFocusState.shown].
 */
@Composable
fun HomeFocusOverlay(f: HomeFocusState, s: HomeGridState, backdrop: HomeBackdrop,
                     onWeather: () -> Unit, onTopic: (InterestsFeed.Topic) -> Unit, onPickInterests: () -> Unit,
                     onMusic: () -> Unit, onMusicToggle: () -> Unit, onTodo: () -> Unit, onNextUp: () -> Unit,
                     onAction: (QuickActions.Action) -> Unit) {
    val card = f.card ?: return
    val motion = MotionTokens.enabled
    androidx.compose.runtime.SideEffect { f.moving = motion }
    LaunchedEffect(f.target, motion) {
        val dur = if (motion) HomeMotion.FOCUS_MS else HomeMotion.FOCUS_REDUCED_FADE_MS
        if (f.target != null) f.progress.animateTo(1f, tween(dur, easing = HomeMotion.Ease))
        else { f.progress.animateTo(0f, tween(dur, easing = HomeMotion.Ease)); f.card = null }
    }
    BackHandler(enabled = f.isOpen) { f.close() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var focusRect by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        HomeBackdropBlur(backdrop, alpha = { f.progress.value }, modifier = Modifier.fillMaxSize())
        // the dim: a tap on it (the blurred sides) closes the card
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = f.progress.value }
            .background(Color(0xFF060A14).copy(alpha = if (backdrop.mode == HomeBackdrop.Mode.SOFTWARE) 0.42f else 0.30f))
            .pointerInput(f) { detectTapGestures { f.close() } }
            .semantics { contentDescription = "Close card"; onClick(label = "Close card") { f.close(); true } })
        // the frame, grown from the cell's rect to the focus rect (corners never stretch)
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = if (motion) 1f else f.progress.value }) {
            val fr = focusRect ?: return@Canvas
            val cell = f.cells[card]?.translate(-origin)
            val r = HomeFocusGeometry.frame(cell, fr, f.progress.value, motion)
            val rad = HomeGridLook.radius.toPx() * (1f + 0.15f * f.progress.value)
            drawRoundRect(HomeGridLook.cardFill.copy(alpha = 0.97f), topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(rad))
            val w = HomeGridLook.edgeWidth.toPx()
            drawRoundRect(HomeGridLook.cardEdge, topLeft = Offset(r.left + w / 2, r.top + w / 2), size = Size(r.width - w, r.height - w),
                cornerRadius = CornerRadius(rad - w / 2), style = Stroke(w))
        }
        // the compact card riding along at the start (shared-element feel)
        val cell = f.cells[card]?.translate(-origin)
        // composed only for the first half of the lift (recomposes on the threshold, not per frame)
        val early by remember(f) { androidx.compose.runtime.derivedStateOf { f.progress.value < 0.5f } }
        if (motion && cell != null && early) CompositionLocalProvider(LocalHomeFocus provides null) {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(cell.left.toInt(), cell.top.toInt()) }
                .size(with(density) { cell.width.toDp() }, with(density) { cell.height.toDp() })
                .graphicsLayer {
                    val fr = focusRect
                    val p = f.progress.value
                    alpha = HomeFocusGeometry.compactAlpha(p)
                    if (fr != null) {
                        val r = HomeFocusGeometry.frame(cell, fr, p, true)
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = r.width / cell.width; scaleY = scaleX
                        translationX = r.left - cell.left; translationY = r.top - cell.top
                    }
                }) {
                CompactCard(card, s, Modifier.fillMaxSize())
            }
        }
        // the full content at the focus rect, scaled with the frame while it grows
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 56.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalHomeFocus provides null) {
                Column(Modifier.fillMaxWidth()
                    .onGloballyPositioned { focusRect = it.boundsInRoot().translate(-origin) }
                    .graphicsLayer {
                        val fr = focusRect
                        val p = f.progress.value
                        alpha = if (motion) HomeFocusGeometry.fullAlpha(p) else p
                        if (motion && fr != null && cell != null) {
                            val r = HomeFocusGeometry.frame(cell, fr, p, true)
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = r.width / fr.width; scaleY = scaleX
                            translationX = r.left - fr.left; translationY = r.top - fr.top
                        }
                    }
                    // taps on the card's own blank areas stay on the card (children's clicks are handled first)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { stateDescription = "Expanded" }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)) {
                    FocusedContent(card, s, close = { f.close() }, onWeather, onTopic, onPickInterests, onMusic, onMusicToggle, onTodo, onNextUp, onAction)
                }
            }
        }
    }
}

private fun Rect.translate(o: Offset) = Rect(left + o.x, top + o.y, right + o.x, bottom + o.y)

/** The card as it sits in the grid (no intro), for the first half of the lift. */
@Composable
private fun CompactCard(card: HomeGrid.Card, s: HomeGridState, modifier: Modifier) {
    when (card) {
        HomeGrid.Card.WEATHER -> WeatherCardV2(s.board, s.h24, null, modifier)
        HomeGrid.Card.INTERESTS -> InterestsCardV2(s.topics, null, -1, modifier, onTopic = {}, onEmpty = {})
        HomeGrid.Card.MUSIC -> MusicCardV2(s.music, null, -1, modifier, onOpen = {}, onToggle = {})
        HomeGrid.Card.TODO -> TodoCardV2(s.tasks, s.taskCount, null, -1, modifier, onOpen = {})
        HomeGrid.Card.NEXT_UP -> NextUpCardV2(s.next, s.now, s.h24, null, -1, modifier, onOpen = {})
        HomeGrid.Card.QUICK -> QuickActionsCardV2(s.actions, null, -1, modifier, onAction = {})
    }
}

@Composable
private fun Pill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (primary) Color(0xFFF2F4F7) else Color.White.copy(alpha = 0.10f))
        .border(1.dp, Color.White.copy(alpha = if (primary) 0f else 0.32f), CircleShape)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(text, style = style(13.sp, FontWeight.SemiBold, if (primary) Color(0xFF15203A) else HomeGridLook.ink))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FocusedContent(card: HomeGrid.Card, s: HomeGridState, close: () -> Unit,
                                       onWeather: () -> Unit, onTopic: (InterestsFeed.Topic) -> Unit, onPickInterests: () -> Unit,
                                       onMusic: () -> Unit, onMusicToggle: () -> Unit, onTodo: () -> Unit, onNextUp: () -> Unit,
                                       onAction: (QuickActions.Action) -> Unit) {
    when (card) {
        HomeGrid.Card.WEATHER -> {
            val b = s.board
            val painter = remember(b.kind, b.night) { CloudGlyphPainter(b.kind, b.night) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 40.dp, height = 30.dp)) {
                    val g = CloudGlyphPainter.fitIcon(0f, 0f, size.width, size.height); painter.draw(this, g[0], g[1], g[2], HomeGridLook.ink, 2.dp.toPx())
                }
                Spacer(Modifier.width(10.dp))
                Text(b.place, style = style(18.sp, FontWeight.Medium), modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(16.dp))
            FocusTile {
                Text(b.condition.ifBlank { "Weather" }.replaceFirstChar { it.titlecase() }, style = style(14.sp, color = HomeGridLook.sub))
                Text(b.temp.ifBlank { "--°" }, style = style(40.sp, FontWeight.Bold))
            }
            Spacer(Modifier.height(10.dp))
            FocusTile {
                Text("Sunset", style = style(14.sp, color = HomeGridLook.sub))
                Text(WeatherBoard.clock(b.sunset, s.h24).ifBlank { "--:--" }, style = style(32.sp, FontWeight.Bold))
            }
            Spacer(Modifier.height(16.dp))
            Pill("Open the forecast", primary = true, onClick = onWeather)
        }
        HomeGrid.Card.INTERESTS -> {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = HomeGridLook.ink)) { append("Interests") }
                withStyle(SpanStyle(color = HomeGridLook.sub)) { append(" \u00b7 Tracking") }
            }, style = style(19.sp), modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            if (s.allTopics.isEmpty()) Text("Tell me what you're into and the latest headlines show up here.", style = style(14.sp, color = HomeGridLook.sub))
            s.allTopics.forEach { t ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = t.url.isNotBlank(), role = Role.Button) { onTopic(t) }
                    .semantics(mergeDescendants = true) { }.padding(vertical = 7.dp)) {
                    Row(Modifier.clip(CircleShape).background(HomeGridLook.chipFill).border(1.dp, HomeGridLook.chipEdge, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(topicIcon(t.icon), contentDescription = null, tint = HomeGridLook.ink, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t.label, style = style(13.sp, FontWeight.SemiBold), maxLines = 1)
                    }
                    Text(t.headline.ifBlank { "Fetching the latest\u2026" }, style = style(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                    if (t.meta.isNotBlank()) Text(t.meta, style = style(12.sp, color = HomeGridLook.faint), maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            Pill(if (s.allTopics.isEmpty()) "Pick interests" else "Edit interests", primary = true) { close(); onPickInterests() }
        }
        HomeGrid.Card.MUSIC -> {
            val m = s.music
            Text(if (m == null) "Music" else if (m.playing) "Now playing" else "Paused", style = style(14.sp, color = HomeGridLook.sub))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicArt(Modifier.size(96.dp), corner = 18.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(m?.title?.ifBlank { null } ?: "Nothing playing", style = style(19.sp, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(m?.artist?.ifBlank { null } ?: "Open Music", style = style(14.sp, color = HomeGridLook.sub), maxLines = 1)
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFF2F4F7))
                    .clickable(role = Role.Button, onClick = if (m == null) onMusic else onMusicToggle), contentAlignment = Alignment.Center) {
                    Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (m?.playing == true) "Pause" else "Play",
                        tint = Color(0xFF15203A), modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            MusicProgress(m)
            Spacer(Modifier.height(16.dp))
            Pill("Open Music", primary = true) { close(); onMusic() }
        }
        HomeGrid.Card.TODO -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("To-do", style = style(19.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f).semantics { heading() })
                Text("${s.taskCount}", style = style(14.sp, color = HomeGridLook.sub))
            }
            Spacer(Modifier.height(10.dp))
            s.tasks.forEach { t ->
                Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = t.title + if (t.done) ", done" else "" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                        .then(if (t.done) Modifier.background(HomeGridLook.accent) else Modifier.border(1.6.dp, Color(0xFFE6ECF4), RoundedCornerShape(6.dp))),
                        contentAlignment = Alignment.Center) {
                        if (t.done) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(t.title, style = style(15.sp, color = if (t.done) HomeGridLook.faint else HomeGridLook.ink)
                        .copy(textDecoration = if (t.done) TextDecoration.LineThrough else null))
                }
            }
            Spacer(Modifier.height(12.dp))
            Pill("Open the list", primary = true) { close(); onTodo() }
        }
        HomeGrid.Card.NEXT_UP -> {
            val item = s.next
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(HomeGridLook.bell), contentAlignment = Alignment.Center) {
                    Icon(if (item?.kind == NextUp.Kind.CALENDAR) Icons.Rounded.Event else Icons.Rounded.NotificationsNone, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("Next up", style = style(15.sp, color = HomeGridLook.sub), modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(12.dp))
            Text(if (item == null) "Nothing scheduled" else item.title, style = style(22.sp, FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item != null) Text(NextUp.whenLabel(item.at, s.now, s.h24), style = style(16.sp, color = HomeGridLook.sub))
            Spacer(Modifier.height(16.dp))
            Pill(if (item == null) "Set a reminder" else "Open", primary = true) { close(); onNextUp() }
        }
        HomeGrid.Card.QUICK -> {
            Text("Quick actions", style = style(19.sp, FontWeight.SemiBold), modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            s.actions.forEach { a ->
                FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClickLabel = a.verb) { close(); onAction(a) }
                    .padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(a.verb, style = style(15.sp, FontWeight.SemiBold), maxLines = 1)
                    }
                    QuickChipRow.words(a.rest).forEach { w ->
                        Text(w, style = style(15.sp, color = HomeGridLook.ink.copy(alpha = 0.92f)), maxLines = 1, softWrap = false,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusTile(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HomeGridLook.tile).padding(horizontal = 16.dp, vertical = 12.dp), content = content)
}
