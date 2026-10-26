package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.RepeatOneOn
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.mediaapps.Repeat
import com.blaze.assistant.mediaapps.StageMusicLogic
import com.blaze.assistant.stage.StageItem
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageTimer
import com.blaze.assistant.stage.StageTools
import com.blaze.assistant.stage.TimerState
import org.json.JSONArray

/**
 * Blaze Stage cards: the stateless faces of every hero and widget. They get plain data ([StageData]) and report taps
 * ([StageEvent]); StageHost feeds them live values (the timer once a second, the music state once a second) so the
 * screenshot tests can draw every card in any theme without services. Only theme colours ([LocalBlaze]) are used.
 * 0.46.1: icons, not words (Material Symbols Rounded, each with a content description), denser layouts, and the basics
 * every card was missing (music queue / shuffle / repeat / seek / art, timer presets and steps, quiz hint / back / review,
 * flashcards know / don't know / shuffle / progress, plan and note editing, the browser card).
 */
@Immutable
data class StageBrowserNow(val hasTab: Boolean = false, val title: String = "", val url: String = "", val progress: Int = 100,
                           val canBack: Boolean = false, val canForward: Boolean = false)

@Immutable
data class StageData(val now: Long = 0L, val timer: TimerState? = null, val music: StageMusicLogic.Snapshot = StageMusicLogic.Snapshot(),
                     val cardFront: String = "", val cardBack: String = "", val cardsLeft: Int = 0, val browser: StageBrowserNow = StageBrowserNow(),
                     /** 0.46.2 Movies: continue watching, shown on an empty video card. */
                     val movies: List<com.blaze.assistant.movies.Progress> = emptyList())

sealed class StageEvent {
    data class Timer(val op: String, val minutes: Double = 1.0) : StageEvent()
    /** play | pause | next | previous | shuffle | repeat | seek:<ms> | at:<i> | up:<i> | down:<i> | remove:<i> | next_up:<i> */
    data class Music(val op: String) : StageEvent()
    data class QuizAnswer(val index: Int) : StageEvent()
    object QuizNext : StageEvent()
    object QuizBack : StageEvent()
    object QuizHint : StageEvent()
    object QuizRetryWrong : StageEvent()
    data class Card(val grade: Int) : StageEvent()
    object Flip : StageEvent()
    object Shuffle : StageEvent()
    data class Toggle(val index: Int) : StageEvent()
    /** New props for the card (plan items, note text, timer sound, video speed…). */
    data class Props(val props: Map<String, String>) : StageEvent()
    /** back | forward | reload | stop | go:<text> */
    data class Browser(val op: String) : StageEvent()
    /** speed | fullscreen | pick:<i> (a search result) | resume:<i> (continue watching) | results (back to the results) */
    data class Video(val op: String) : StageEvent()
    object OpenFull : StageEvent()
    object Expand : StageEvent()
    object Shrink : StageEvent()
    object Close : StageEvent()
    object Menu : StageEvent()
}

@Composable
internal fun stageSurface(): Color { val c = LocalBlaze.current; return if (c.dark) Color(0xFF16161A).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.94f) }

@Composable
internal fun StageCardFrame(modifier: Modifier = Modifier, desc: String, radius: Dp = 22.dp, content: @Composable BoxScope.() -> Unit) {
    val c = LocalBlaze.current
    Box(modifier.clip(RoundedCornerShape(radius)).background(stageSurface()).border(1.dp, c.hair, RoundedCornerShape(radius))
        .semantics { contentDescription = desc }, content = content)
}

/** A round icon button: the one control shape on every card (icon + content description, 48 dp target by default). */
@Composable
internal fun StageIconButton(icon: ImageVector, desc: String, modifier: Modifier = Modifier, primary: Boolean = false, on: Boolean = false,
                             size: Dp = 44.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val bg = when { primary -> c.accent; on -> c.accent.copy(alpha = 0.16f); else -> Color.Transparent }
    val tint = when { primary -> if (c.dark) Color.Black else Color.White; on -> c.accent; !enabled -> c.sub.copy(alpha = 0.4f); else -> c.ink }
    Box(modifier.size(size).clip(CircleShape).background(bg).clickable(enabled = enabled, role = Role.Button, onClickLabel = desc) { onClick() }
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** A small text chip for values (presets "25m", speed "1.5x"); actions are icons. */
@Composable
private fun ValueChip(text: String, desc: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent.copy(alpha = 0.18f) else c.faint)
        .border(1.dp, if (selected) c.accent else c.hair, RoundedCornerShape(50))
        .clickable(role = Role.Button, onClickLabel = desc) { onClick() }.semantics { contentDescription = desc }
        .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) c.accent else c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The thin header every hero has: title, shrink, and the ⋯ menu (move, shrink, open in full view, close, reset layout). */
@Composable
private fun HeroHeader(item: StageItem, on: (StageEvent) -> Unit, extra: @Composable () -> Unit = {}) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.props["title"]?.ifBlank { null } ?: item.kind.label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        extra()
        StageIconButton(Icons.Rounded.CloseFullscreen, "Shrink ${item.kind.label} to a widget", size = 40.dp) { on(StageEvent.Shrink) }
        StageIconButton(Icons.Rounded.MoreHoriz, "${item.kind.label} options", size = 40.dp) { on(StageEvent.Menu) }
    }
}

// ---------------------------------------------------------------- heroes

@Composable
fun StageHero(item: StageItem, d: StageData, modifier: Modifier = Modifier, low: Boolean = false, video: (@Composable (Modifier) -> Unit)? = null,
              web: (@Composable (Modifier) -> Unit)? = null, on: (StageEvent) -> Unit) {
    StageCardFrame(modifier, desc = heroDesc(item, d)) {
        Column(Modifier.fillMaxSize()) {
            HeroHeader(item, on) {
                if (item.kind == StageKind.PLAN || item.kind == StageKind.NOTE) {
                    val editing = item.props["editing"] == "true"
                    StageIconButton(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, if (editing) "Done editing" else "Edit ${item.kind.label.lowercase()}",
                        size = 40.dp, on = editing) { on(StageEvent.Props(mapOf("editing" to if (editing) "" else "true"))) }
                }
            }
            val pad = if (item.kind == StageKind.BROWSER) 0.dp else 14.dp
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad, vertical = if (item.kind == StageKind.BROWSER) 0.dp else 6.dp), contentAlignment = Alignment.Center) {
                when (item.kind) {
                    StageKind.TIMER -> TimerFace(item, d.timer, d.now, low, on)
                    StageKind.MUSIC -> MusicFace(item, d.music, on)
                    StageKind.VIDEO -> VideoFace(item, d, video, on)
                    StageKind.QUIZ -> QuizFace(item, on)
                    StageKind.FLASHCARDS -> CardsFace(item, d, on)
                    StageKind.PLAN -> PlanFace(item, on)
                    StageKind.NOTE -> NoteFace(item, on)
                    StageKind.BROWSER -> BrowserFace(d.browser, web, on)
                }
            }
        }
    }
}

internal fun heroDesc(item: StageItem, d: StageData): String = when (item.kind) {
    StageKind.TIMER -> d.timer?.let { "${it.label} timer, ${StageTimer.clock(StageTimer.remaining(it, d.now))} left" + if (it.running) "" else ", not running" } ?: "Timer"
    StageKind.MUSIC -> StageMusicLogic.card(d.music).let { m -> if (m.title.isNotBlank()) "Music, ${m.title}" + (if (m.playing) ", playing" else ", paused") else "Music" }
    StageKind.BROWSER -> "Browser" + d.browser.title.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
    else -> item.props["title"]?.ifBlank { null } ?: item.kind.label
}

// ---------------------------------------------------------------- timer

@Composable
private fun TimerFace(item: StageItem, t: TimerState?, now: Long, low: Boolean, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    if (t == null) { Text("Timer", color = c.sub); return }
    val left = StageTimer.remaining(t, now)
    val p = StageTimer.progress(t, now)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight.takeIf { it < 2000.dp } ?: maxWidth, 210.dp)
            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                // The ring redraws only when the second changes (p changes once a second); no per-frame work.
                val track = c.hair; val fill = c.accent
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    val sw = 9.dp.toPx(); val r = Size(size.width - sw, size.height - sw); val o = Offset(sw / 2, sw / 2)
                    drawArc(track, 0f, 360f, false, o, r, style = Stroke(sw))
                    if (p > 0f) drawArc(fill, -90f, 360f * p, false, o, r, style = Stroke(sw, cap = if (low) StrokeCap.Butt else StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(StageTimer.clock(left), color = c.ink, fontSize = 42.sp, fontWeight = FontWeight.Light)
                    val cyc = StageTimer.cycle(t)
                    Text(when {
                        t.done -> "Done"
                        cyc != null -> (if (t.phase % 2 == 1) "Break" else "Focus") + " \u00b7 cycle ${cyc.first} of ${cyc.second}"
                        t.running -> "Running"
                        t.started -> "Paused"
                        else -> StageTimer.words(t.phases[0])
                    }, color = c.sub, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!t.started && !t.done) {
            // Presets and a custom length, before it starts.
            val cur = item.props["minutes"]?.toDoubleOrNull() ?: (t.phases[0] / 60_000.0)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StageTimer.PRESETS.forEach { m -> ValueChip(StageTimer.minutesText(m) + "m", "Set ${StageTimer.minutesText(m)} minutes", selected = kotlin.math.abs(m - cur) < 0.01) { on(StageEvent.Timer("set", m)) } }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val sound = item.props["sound"].orEmpty().ifBlank { "ring" }
            StageIconButton(when (sound) { "vibrate" -> Icons.Rounded.Vibration; "silent" -> Icons.Rounded.NotificationsOff; else -> Icons.Rounded.NotificationsActive },
                "Alert sound: $sound. Tap to change", size = 40.dp) {
                val all = com.blaze.assistant.stage.StageStore.SOUNDS
                on(StageEvent.Props(mapOf("sound" to all[(all.indexOf(sound) + 1).mod(all.size)])))
            }
            if (!t.started && !t.done) {
                val cur = item.props["minutes"]?.toDoubleOrNull() ?: (t.phases[0] / 60_000.0)
                StageIconButton(Icons.Rounded.Remove, "Shorter", size = 40.dp) { on(StageEvent.Timer("set", StageTimer.step(cur, false))) }
                StageIconButton(Icons.Rounded.PlayArrow, "Start the ${t.label} timer", primary = true, size = 56.dp) { on(StageEvent.Timer("start")) }
                StageIconButton(Icons.Rounded.Add, "Longer", size = 40.dp) { on(StageEvent.Timer("set", StageTimer.step(cur, true))) }
            } else if (t.done) {
                StageIconButton(Icons.Rounded.Replay, "Start the timer again", primary = true, size = 56.dp) { on(StageEvent.Timer("start")) }
            } else {
                StageIconButton(Icons.Rounded.Remove, "Take off one minute", size = 40.dp) { on(StageEvent.Timer("sub", 1.0)) }
                StageIconButton(if (t.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (t.running) "Pause the timer" else "Resume the timer",
                    primary = true, size = 56.dp) { on(StageEvent.Timer(if (t.running) "pause" else "start")) }
                StageIconButton(Icons.Rounded.Add, "Add one minute", size = 40.dp) { on(StageEvent.Timer("add", 1.0)) }
            }
            StageIconButton(Icons.Rounded.RestartAlt, "Reset the timer", size = 40.dp, enabled = t.started) { on(StageEvent.Timer("reset")) }
        }
    }
}

// ---------------------------------------------------------------- music

/** The cover: the song's own art (saved cover, embedded picture) or a themed placeholder. */
@Composable
private fun MusicArt(e: StageMusicLogic.Entry?, modifier: Modifier) {
    val c = LocalBlaze.current
    val track = remember(e?.key, e?.art) { e?.let { com.blaze.assistant.mediaapps.MediaLib.find(it.key)
        ?: com.blaze.assistant.mediaapps.Track(it.key, it.uri, it.title, it.artist, art = it.art) } }
    val img = com.blaze.assistant.mediaapps.rememberArt(track)
    Box(modifier.clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(c.accent.copy(alpha = if (c.dark) 0.55f else 0.85f), c.accent.copy(alpha = 0.25f), c.faint))),
        contentAlignment = Alignment.Center) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.fillMaxSize(0.4f))
    }
}

/** A draggable seek slider. The bar follows the finger while dragging and seeks on release (one seek, not one per frame). */
@Composable
private fun SeekBar(posMs: Long, durMs: Long, modifier: Modifier = Modifier, onSeek: (Long) -> Unit) {
    val c = LocalBlaze.current
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: StageMusicLogic.frac(posMs, durMs)
    val shownMs = drag?.let { (it * durMs).toLong() } ?: posMs
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(26.dp)
            .pointerInput(durMs) { detectTapGestures { o -> if (durMs > 0) onSeek(StageMusicLogic.seekTo(o.x, size.width.toFloat(), durMs)) } }
            .pointerInput(durMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (durMs > 0) drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { f -> onSeek((f * durMs).toLong()) }; drag = null },
                    onDragCancel = { drag = null }) { ch, dx -> if (durMs > 0) { ch.consume(); drag = ((drag ?: shown) + dx / size.width).coerceIn(0f, 1f) } }
            }
            .semantics {
                contentDescription = "Seek: ${StageMusicLogic.elapsed(shownMs)} of ${StageMusicLogic.elapsed(durMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { v -> if (durMs > 0) onSeek((v.coerceIn(0f, 1f) * durMs).toLong()); durMs > 0 }
            }) {
            val track = c.hair; val fill = c.accent; val active = drag != null
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2; val h = 4.dp.toPx()
                drawLine(track, Offset(0f, y), Offset(size.width, y), h, StrokeCap.Round)
                if (shown > 0f) drawLine(fill, Offset(0f, y), Offset(size.width * shown, y), h, StrokeCap.Round)
                drawCircle(fill, (if (active) 8.dp else 6.dp).toPx(), Offset((size.width * shown).coerceIn(0f, size.width), y))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(StageMusicLogic.elapsed(shownMs), color = c.sub, fontSize = 11.sp)
            Text(if (durMs > 0) StageMusicLogic.remaining(shownMs, durMs) else "", color = c.sub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MusicFace(item: StageItem, s: StageMusicLogic.Snapshot, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val m = StageMusicLogic.card(s)
    val showQueue = item.props["queue"] == "true"
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        if (showQueue) MusicQueue(s, m, Modifier.weight(1f).fillMaxWidth(), on)
        else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight, 200.dp)
            MusicArt(m.current, Modifier.size(side))
        }
        Spacer(Modifier.height(8.dp))
        if (!m.hasService && m.title.isBlank()) {
            Text("Nothing playing", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StageIconButton(Icons.Rounded.PlayArrow, "Play music", primary = true, size = 56.dp) { on(StageEvent.Music("play")) }
                StageIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open Blaze Music") { on(StageEvent.OpenFull) }
            }
            return@Column
        }
        Text(m.title.ifBlank { "Blaze Music" }, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (m.artist.isNotBlank()) Text(m.artist, color = c.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        SeekBar(m.posMs, m.durMs, Modifier.fillMaxWidth(0.92f)) { ms -> on(StageEvent.Music("seek:$ms")) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            StageIconButton(if (m.shuffle) Icons.Rounded.ShuffleOn else Icons.Rounded.Shuffle, if (m.shuffle) "Shuffle on" else "Shuffle off", on = m.shuffle, size = 40.dp) { on(StageEvent.Music("shuffle")) }
            StageIconButton(Icons.Rounded.SkipPrevious, "Previous song", enabled = m.canPrev) { on(StageEvent.Music("previous")) }
            StageIconButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pause" else "Play", primary = true, size = 56.dp) {
                on(StageEvent.Music(if (m.playing) "pause" else "play")) }
            StageIconButton(Icons.Rounded.SkipNext, "Next song", enabled = m.canNext) { on(StageEvent.Music("next")) }
            StageIconButton(when (m.repeat) { Repeat.ONE -> Icons.Rounded.RepeatOneOn; Repeat.ALL -> Icons.Rounded.RepeatOn; else -> Icons.Rounded.Repeat },
                when (m.repeat) { Repeat.ONE -> "Repeat one"; Repeat.ALL -> "Repeat all"; else -> "Repeat off" }, on = m.repeat != Repeat.OFF, size = 40.dp) { on(StageEvent.Music("repeat")) }
            StageIconButton(Icons.AutoMirrored.Rounded.QueueMusic, if (showQueue) "Hide the queue" else "Show the queue", on = showQueue, size = 40.dp) {
                on(StageEvent.Props(mapOf("queue" to if (showQueue) "" else "true"))) }
        }
    }
}

@Composable
private fun MusicQueue(s: StageMusicLogic.Snapshot, m: StageMusicLogic.Card, modifier: Modifier, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    if (s.queue.isEmpty()) { Box(modifier, contentAlignment = Alignment.Center) { Text("The queue is empty", color = c.sub, fontSize = 13.sp) }; return }
    // Now playing first, then up next in play order (shuffle-aware); every row is the player's own index.
    val rows = listOfNotNull(s.index.takeIf { it in s.queue.indices }) + m.upNext
    LazyColumn(modifier) {
        itemsIndexed(rows, key = { _, i -> i }) { pos, i ->
            val e = s.queue[i]; val now = i == s.index
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (now) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = "Play ${e.title}") { on(StageEvent.Music("at:$i")) }.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { "Song ${i + 1}" }, color = if (now) c.accent else c.ink, fontSize = 13.sp, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.artist.isNotBlank()) Text(e.artist, color = c.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!now) {
                    if (pos > 1) StageIconButton(Icons.Rounded.QueuePlayNext, "Play ${e.title} next", size = 36.dp) { on(StageEvent.Music("next_up:$i")) }
                    StageIconButton(Icons.Rounded.ArrowUpward, "Move ${e.title} up", size = 36.dp, enabled = i > 0) { on(StageEvent.Music("up:$i")) }
                    StageIconButton(Icons.Rounded.ArrowDownward, "Move ${e.title} down", size = 36.dp, enabled = i < s.queue.size - 1) { on(StageEvent.Music("down:$i")) }
                    StageIconButton(Icons.Rounded.Close, "Remove ${e.title} from the queue", size = 36.dp) { on(StageEvent.Music("remove:$i")) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- video

@Composable
private fun VideoFace(item: StageItem, d: StageData, video: (@Composable (Modifier) -> Unit)?, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val url = item.props["url"].orEmpty()
    val results = remember(item.props["results"]) { com.blaze.assistant.movies.StageMoviesLogic.results(item.props["results"]) }
    val err = item.props["error"].orEmpty()
    if (video != null && url.isNotBlank() && !StageVideoUrl.isYouTube(url)) {
        Column(Modifier.fillMaxSize()) {
            video(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (results.isNotEmpty()) StageIconButton(Icons.Rounded.VideoLibrary, "Back to the movie results", size = 40.dp) { on(StageEvent.Video("results")) }
                Spacer(Modifier.weight(1f))
                val sp = item.props["speed"].orEmpty().ifBlank { "1" }
                ValueChip(sp + "x", "Playback speed ${sp}x. Tap to change") { on(StageEvent.Video("speed")) }
                StageIconButton(Icons.Rounded.Speed, "Playback speed", size = 40.dp) { on(StageEvent.Video("speed")) }
                StageIconButton(if (item.immersive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (item.immersive) "Exit full screen" else "Full screen", size = 40.dp) {
                    on(StageEvent.Video("fullscreen")) }
                StageIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open in Blaze Video", size = 40.dp) { on(StageEvent.OpenFull) }
            }
        }
        return
    }
    // 0.46.2 Movies: search results (tap = play here), else Continue watching, else the Blaze Video button.
    val busy = item.props["busy"] == "true"
    if (results.isNotEmpty() || item.props["query"] != null) {
        Column(Modifier.fillMaxSize()) {
            val failed = item.props["failed"].orEmpty()
            if (results.isEmpty()) Text("Nothing found for \u201c${item.props["query"].orEmpty()}\u201d", color = c.sub, fontSize = 13.sp)
            if (failed.isNotBlank()) Text("Didn't answer: $failed", color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (err.isNotBlank()) Text(err, color = c.sub, fontSize = 12.sp, maxLines = 2)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(results) { i, r -> MovieRow(r.title, listOf(r.tag, sourceName(r.source)).filter { it.isNotBlank() }.joinToString(" \u00b7 "), busy) { on(StageEvent.Video("pick:$i")) } }
            }
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (d.movies.isNotEmpty()) {
            Text("Continue watching", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().semantics { heading() })
            d.movies.forEachIndexed { i, pr -> MovieRow(pr.play.title, (pr.fraction * 100).toInt().toString() + "% watched", busy) { on(StageEvent.Video("resume:$i")) } }
            if (err.isNotBlank()) Text(err, color = c.sub, fontSize = 12.sp, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            StageIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open Movies in Blaze Video", size = 40.dp) { on(StageEvent.OpenFull) }
            return@Column
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = if (c.dark) 0.6f else 0.85f)), contentAlignment = Alignment.Center) {
            StageIconButton(Icons.Rounded.PlayArrow, "Open in Blaze Video", primary = true, size = 60.dp) { on(StageEvent.OpenFull) }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (url.isBlank()) "No video picked" else if (StageVideoUrl.isYouTube(url)) "YouTube plays in Blaze Video" else url.substringAfterLast('/').take(60), color = c.sub, fontSize = 13.sp, maxLines = 1)
    }
}

private fun sourceName(id: String): String = when { id == "archive" -> "Internet Archive"; id == "blender" -> "Blender"; id == "local" -> "Your videos"; id == "jellyfin" -> "Jellyfin"; id.startsWith("p:") -> id.removePrefix("p:"); else -> id }

/** One movie line on the video card: title, a small detail line, and a play icon (the whole row plays it). */
@Composable
private fun MovieRow(title: String, sub: String, busy: Boolean, onPlay: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, role = Role.Button, onClickLabel = "Play $title") { onPlay() }
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = c.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StageIconButton(Icons.Rounded.PlayArrow, "Play $title", size = 40.dp, enabled = !busy) { onPlay() }
    }
}

object StageVideoUrl { fun isYouTube(u: String) = Regex("""(?i)(youtube\.com|youtu\.be)/""").containsMatchIn(u) }

// ---------------------------------------------------------------- quiz

/** Quiz progress kept in the card's props: which questions are in this round, the answers, the hints used. Pure. */
object StageQuiz {
    fun answers(p: Map<String, String>): Map<Int, Int> = p["answers"].orEmpty().split(',').mapNotNull { kv ->
        val a = kv.split(':'); if (a.size == 2) a[0].trim().toIntOrNull()?.let { k -> a[1].trim().toIntOrNull()?.let { k to it } } else null }.toMap()
    fun encode(m: Map<Int, Int>) = m.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    /** The question numbers in this round (all, or just the wrong ones when retrying). */
    fun round(p: Map<String, String>, n: Int): List<Int> = p["only"]?.takeIf { it.isNotBlank() }?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 0 until n }
        ?.ifEmpty { null } ?: (0 until n).toList()
    fun score(qs: List<StageTools.Question>, ans: Map<Int, Int>, round: List<Int>) = round.count { i -> ans[i] != null && ans[i] == qs.getOrNull(i)?.answer }
    fun wrong(qs: List<StageTools.Question>, ans: Map<Int, Int>, round: List<Int>) = round.filter { i -> ans[i] != null && ans[i] != qs.getOrNull(i)?.answer }
    /** The option a hint takes away: the first wrong option (stable), or -1 when only the answer and one other are left. */
    fun hidden(q: StageTools.Question): Int = if (q.options.size <= 2) -1 else q.options.indices.first { it != q.answer }
}

@Composable
private fun QuizFace(item: StageItem, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val qs = StageTools.questions(item.props["questions"])
    if (qs.isEmpty()) { Text("No questions", color = c.sub); return }
    val round = StageQuiz.round(item.props, qs.size)
    val at = item.props["at"]?.toIntOrNull() ?: 0
    // 0.46.0 cards kept one "picked" for the question on screen.
    val ans = StageQuiz.answers(item.props).ifEmpty { item.props["picked"]?.toIntOrNull()?.let { mapOf(round.getOrElse(at) { 0 } to it) } ?: emptyMap() }
    val score = StageQuiz.score(qs, ans, round)
    if (at >= round.size) {
        val wrong = StageQuiz.wrong(qs, ans, round)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score / ${round.size}", color = c.ink, fontSize = 40.sp, fontWeight = FontWeight.Light)
            Text(if (wrong.isEmpty()) "All right" else "Review the ones you missed", color = c.sub, fontSize = 13.sp)
            wrong.forEach { i -> val q = qs[i]
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.faint).padding(10.dp)) {
                    Text(q.q, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Answer: " + q.options[q.answer], color = c.accent, fontSize = 13.sp)
                    ans[i]?.let { a -> Text("You picked: " + q.options.getOrElse(a) { "" }, color = c.sub, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageIconButton(Icons.Rounded.ChevronLeft, "Back to the last question") { on(StageEvent.QuizBack) }
                if (wrong.isNotEmpty()) StageIconButton(Icons.Rounded.Replay, "Retry the ones you missed", primary = true, size = 52.dp) { on(StageEvent.QuizRetryWrong) }
            }
        }
        return
    }
    val qi = round[at]; val q = qs[qi]; val picked = ans[qi]
    val hinted = item.props["hint$qi"] == "true"
    val gone = if (hinted && q.hint.isBlank()) StageQuiz.hidden(q) else -1
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${at + 1} / ${round.size}", color = c.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("$score right", color = c.sub, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.hair)) {
            Box(Modifier.fillMaxWidth((at.toFloat() / round.size).coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(q.q, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (hinted && q.hint.isNotBlank()) Text(q.hint, color = c.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            q.options.forEachIndexed { i, o ->
                if (i == gone && picked == null) return@forEachIndexed
                val right = picked != null && i == q.answer; val wrong = picked == i && i != q.answer
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(when { right -> c.accent.copy(alpha = 0.22f); wrong -> Color(0x33E5484D); else -> c.faint })
                    .border(1.dp, if (right) c.accent else c.hair, RoundedCornerShape(14.dp))
                    .clickable(enabled = picked == null, role = Role.Button, onClickLabel = "Answer ${'A' + i}") { on(StageEvent.QuizAnswer(i)) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${'A' + i}.  $o", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (right) Icon(Icons.Rounded.Check, contentDescription = "Right answer", tint = c.accent, modifier = Modifier.size(18.dp))
                    if (wrong) Icon(Icons.Rounded.Close, contentDescription = "Wrong answer", tint = Color(0xFFE5484D), modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StageIconButton(Icons.Rounded.ChevronLeft, "Previous question", enabled = at > 0) { on(StageEvent.QuizBack) }
            StageIconButton(Icons.Rounded.Lightbulb, "Hint", on = hinted, enabled = picked == null && !hinted && (q.hint.isNotBlank() || q.options.size > 2)) { on(StageEvent.QuizHint) }
            StageIconButton(Icons.Rounded.ChevronRight, if (at + 1 < round.size) "Next question" else "See the score", primary = picked != null) { on(StageEvent.QuizNext) }
        }
    }
}

// ---------------------------------------------------------------- flashcards

@Composable
private fun CardsFace(item: StageItem, d: StageData, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val seen = item.props["seen"]?.toIntOrNull() ?: 0
    val known = item.props["known"]?.toIntOrNull() ?: 0
    if (d.cardFront.isBlank()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("All done for now", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(item.props["deck"].orEmpty() + if (seen > 0) " \u00b7 $known of $seen known" else "", color = c.sub, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp)); StageIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open Learn") { on(StageEvent.OpenFull) }
        }
        return
    }
    val flipped = item.props["flipped"] == "true"
    val total = seen + d.cardsLeft
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.props["deck"].orEmpty()} \u00b7 ${seen + 1} of $total", color = c.sub, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$known known", color = c.sub, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.hair)) {
            Box(Modifier.fillMaxWidth(if (total > 0) seen.toFloat() / total else 0f).fillMaxHeight().background(c.accent))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (flipped) c.accent.copy(alpha = 0.10f) else c.faint).border(1.dp, c.hair, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = "Flip the card") { on(StageEvent.Flip) }.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(if (flipped) d.cardBack else d.cardFront, color = c.ink, fontSize = if (flipped) 16.sp else 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            StageIconButton(Icons.Rounded.Shuffle, "Shuffle the cards", size = 40.dp) { on(StageEvent.Shuffle) }
            StageIconButton(Icons.Rounded.ThumbDown, "Don't know it yet", enabled = flipped) { on(StageEvent.Card(0)) }
            StageIconButton(Icons.Rounded.Flip, if (flipped) "Show the question" else "Show the answer", primary = !flipped, size = 52.dp) { on(StageEvent.Flip) }
            StageIconButton(Icons.Rounded.ThumbUp, "Know it", enabled = flipped) { on(StageEvent.Card(2)) }
            StageIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open Learn", size = 40.dp) { on(StageEvent.OpenFull) }
        }
    }
}

// ---------------------------------------------------------------- plan / note

@Composable
private fun PlanFace(item: StageItem, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val items = StageTools.items(item.props["items"])
    val done = item.props["done"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    if (item.props["editing"] == "true") { PlanEditor(items, done, on); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        item.props["text"]?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.body, fontSize = 14.sp); Spacer(Modifier.height(6.dp)) }
        if (items.isNotEmpty()) Text("${done.count { it in items.indices }} of ${items.size} done", color = c.sub, fontSize = 12.sp)
        items.forEachIndexed { i, line ->
            val on1 = i in done
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox, onClickLabel = if (on1) "Mark not done" else "Mark done") { on(StageEvent.Toggle(i)) }
                .padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (on1) c.accent else Color.Transparent).border(1.5.dp, if (on1) c.accent else c.sub, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) { if (on1) Icon(Icons.Rounded.Check, contentDescription = null, tint = if (c.dark) Color.Black else Color.White, modifier = Modifier.size(14.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(line, color = if (on1) c.sub else c.ink, fontSize = 15.sp)
            }
        }
    }
}

/** Plan editing: change, delete and add lines; saved with the check (done ticks follow their lines). */
@Composable
private fun PlanEditor(items: List<String>, done: Set<Int>, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val lines = remember(items) { mutableStateListOf<Pair<Int, String>>().apply { items.forEachIndexed { i, s -> add(i to s) } } }
    var fresh by remember { mutableStateOf("") }
    fun commit() {
        val kept = lines.filter { it.second.isNotBlank() } + (if (fresh.isNotBlank()) listOf(-1 to fresh.trim()) else emptyList())
        val newDone = kept.mapIndexedNotNull { j, (old, _) -> if (old in done) j else null }
        on(StageEvent.Props(mapOf("items" to JSONArray(kept.map { it.second.trim() }).toString(), "done" to newDone.joinToString(","), "editing" to "")))
    }
    val field = TextStyle(color = c.ink, fontSize = 15.sp)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            lines.forEachIndexed { k, (old, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(text, { v -> lines[k] = old to v }, textStyle = field, cursorBrush = SolidColor(c.accent), singleLine = true,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { contentDescription = "Line ${k + 1}" })
                    StageIconButton(Icons.Rounded.Close, "Delete line ${k + 1}", size = 36.dp) { lines.removeAt(k) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (fresh.isEmpty()) Text("New item", color = c.sub, fontSize = 15.sp)
                    BasicTextField(fresh, { fresh = it }, textStyle = field, cursorBrush = SolidColor(c.accent), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (fresh.isNotBlank()) { lines.add(-1 to fresh.trim()); fresh = "" } }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New item" })
                }
                StageIconButton(Icons.Rounded.Add, "Add the item", size = 36.dp, enabled = fresh.isNotBlank()) { lines.add(-1 to fresh.trim()); fresh = "" }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { StageIconButton(Icons.Rounded.Check, "Save the plan", primary = true) { commit() } }
    }
}

@Composable
private fun NoteFace(item: StageItem, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    if (item.props["editing"] != "true") {
        Text(item.props["text"].orEmpty(), color = c.ink, fontSize = 16.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        return
    }
    var text by remember(item.id) { mutableStateOf(item.props["text"].orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        BasicTextField(text, { text = it }, textStyle = TextStyle(color = c.ink, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.faint).padding(10.dp).semantics { contentDescription = "Note text" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StageIconButton(Icons.Rounded.Check, "Save the note", primary = true) { on(StageEvent.Props(mapOf("text" to text, "editing" to ""))) }
        }
    }
}

// ---------------------------------------------------------------- browser

@Composable
private fun BrowserFace(b: StageBrowserNow, web: (@Composable (Modifier) -> Unit)?, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    var input by remember(b.url) { mutableStateOf(b.url.removePrefix("https://").removePrefix("www.")) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StageIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", size = 38.dp, enabled = b.canBack) { on(StageEvent.Browser("back")) }
            StageIconButton(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", size = 38.dp, enabled = b.canForward) { on(StageEvent.Browser("forward")) }
            Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(50)).background(c.faint).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = c.sub, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(input, { input = it }, singleLine = true, textStyle = TextStyle(color = c.ink, fontSize = 13.sp), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                    keyboardActions = KeyboardActions(onGo = { if (input.isNotBlank()) on(StageEvent.Browser("go:" + input.trim())) }),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Address or search" })
            }
            StageIconButton(if (b.progress < 100) Icons.Rounded.Close else Icons.Rounded.Refresh, if (b.progress < 100) "Stop loading" else "Reload", size = 38.dp, enabled = b.hasTab) {
                on(StageEvent.Browser(if (b.progress < 100) "stop" else "reload")) }
            StageIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open in full view", size = 38.dp) { on(StageEvent.OpenFull) }
        }
        // The progress line (only while loading).
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (b.progress in 1..99) Box(Modifier.fillMaxWidth(b.progress / 100f).fillMaxHeight().background(c.accent))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)).background(c.faint), contentAlignment = Alignment.Center) {
            if (web != null && b.hasTab) web(Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = c.sub, modifier = Modifier.size(40.dp))
                Text(if (b.hasTab) b.title.ifBlank { b.url } else "Type an address or a search", color = c.sub, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------- widgets (small tiles)

internal fun kindIcon(k: StageKind): ImageVector = when (k) {
    StageKind.TIMER -> Icons.Rounded.Timer; StageKind.MUSIC -> Icons.Rounded.MusicNote; StageKind.VIDEO -> Icons.Rounded.Movie
    StageKind.QUIZ -> Icons.Rounded.Quiz; StageKind.FLASHCARDS -> Icons.Rounded.Style; StageKind.PLAN -> Icons.Rounded.Checklist
    StageKind.NOTE -> Icons.Rounded.StickyNote2; StageKind.BROWSER -> Icons.Rounded.Language
}

@Composable
fun StageWidget(item: StageItem, d: StageData, modifier: Modifier = Modifier, narrow: Boolean = false, video: (@Composable (Modifier) -> Unit)? = null, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val (title, sub) = widgetText(item, d)
    StageCardFrame(modifier.widthIn(min = if (narrow) 60.dp else 120.dp, max = if (narrow) 68.dp else 230.dp).height(if (narrow) 64.dp else 52.dp), desc = "$title, $sub. Tap to make it big", radius = 16.dp) {
        if (item.kind == StageKind.VIDEO && video != null && !item.props["url"].isNullOrBlank() && !StageVideoUrl.isYouTube(item.props["url"].orEmpty())) {
            video(Modifier.fillMaxSize()); return@StageCardFrame
        }
        if (narrow) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(kindIcon(item.kind), contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                Text(sub, color = c.ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            return@StageCardFrame
        }
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.kind == StageKind.MUSIC) MusicArt(StageMusicLogic.card(d.music).current, Modifier.size(34.dp))
            else Icon(kindIcon(item.kind), contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(title, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = c.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // One or two quick controls where they matter, so the tile works without expanding.
            when (item.kind) {
                StageKind.MUSIC -> { val m = StageMusicLogic.card(d.music)
                    StageIconButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pause music" else "Play music", size = 38.dp) {
                        on(StageEvent.Music(if (m.playing) "pause" else "play")) }
                    StageIconButton(Icons.Rounded.SkipNext, "Next song", size = 38.dp, enabled = m.canNext) { on(StageEvent.Music("next")) } }
                StageKind.TIMER -> d.timer?.takeIf { !it.done }?.let { t ->
                    StageIconButton(if (t.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (t.running) "Pause timer" else "Start timer", size = 38.dp) {
                        on(StageEvent.Timer(if (t.running) "pause" else "start")) } }
                else -> {}
            }
        }
        // Music: a thin position line along the bottom edge.
        if (item.kind == StageKind.MUSIC) { val m = StageMusicLogic.card(d.music)
            if (m.durMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(m.frac).height(2.dp).background(c.accent)) }
    }
}

internal fun widgetText(item: StageItem, d: StageData): Pair<String, String> {
    val title = item.props["title"]?.ifBlank { null } ?: item.kind.label
    return when (item.kind) {
        StageKind.TIMER -> title to (d.timer?.let { t -> if (t.done) "Done" else StageTimer.clock(StageTimer.remaining(t, d.now)) + if (!t.running) " \u00b7 paused" else "" } ?: "")
        StageKind.MUSIC -> StageMusicLogic.card(d.music).let { m -> m.title.ifBlank { "Music" } to m.artist.ifBlank { if (m.playing) "Playing" else "Paused" } }
        StageKind.VIDEO -> title to "Video"
        StageKind.QUIZ -> StageTools.questions(item.props["questions"]).let { qs -> val r = StageQuiz.round(item.props, qs.size)
            title to "${minOf(item.props["at"]?.toIntOrNull() ?: 0, r.size)}/${r.size} \u00b7 ${StageQuiz.score(qs, StageQuiz.answers(item.props), r)} right" }
        StageKind.FLASHCARDS -> (item.props["deck"] ?: title) to "${d.cardsLeft} left"
        StageKind.PLAN -> StageTools.items(item.props["items"]).let { l -> title to "${item.props["done"].orEmpty().split(',').count { it.isNotBlank() }}/${l.size} done" }
        StageKind.NOTE -> title to item.props["text"].orEmpty().take(40)
        StageKind.BROWSER -> d.browser.title.ifBlank { "Browser" } to d.browser.url.removePrefix("https://").removePrefix("www.").substringBefore('/').ifBlank { "No page" }
    }
}

/** The chat as a small tile (the newest message); tap = the chat back. */
@Composable
fun StageChatTile(last: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val c = LocalBlaze.current
    StageCardFrame(modifier.widthIn(min = 140.dp, max = 260.dp).height(52.dp).clickable(role = Role.Button, onClickLabel = "Show the chat") { onOpen() }, desc = "Chat: $last. Tap to show the chat", radius = 16.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(last.ifBlank { "Chat" }, color = c.ink, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The reply bar folded into a small floating pill (never gone); tap = the full reply bar back. */
@Composable
fun StageReplyPill(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val c = LocalBlaze.current
    Box(modifier.clip(RoundedCornerShape(50)).background(stageSurface()).border(1.dp, c.hair, RoundedCornerShape(50))
        .clickable(role = Role.Button, onClickLabel = "Show the reply bar") { onOpen() }.semantics { contentDescription = "Message Blaze" }
        .padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("Message Blaze", color = c.sub, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------- the ⋯ menu: a compact icon grid

/** One tile of the card menu: an icon, its short label on long-press (a tooltip line), the action on tap. */
data class StageMenuAction(val icon: ImageVector, val label: String, val run: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StageMenuGrid(actions: List<StageMenuAction>, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    var tip by remember { mutableStateOf("") }
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp)).background(stageSurface()).border(1.dp, c.hair, RoundedCornerShape(22.dp)).padding(10.dp)) {
        actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { a ->
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.faint)
                        .combinedClickable(role = Role.Button, onClickLabel = a.label, onLongClick = { tip = a.label }) { a.run(); onDismiss() }
                        .semantics { contentDescription = a.label }, contentAlignment = Alignment.Center) {
                        Icon(a.icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        // The tooltip line: what a long-pressed tile does.
        Text(tip.ifBlank { "Long-press an icon to see what it does" }, color = if (tip.isBlank()) c.sub else c.ink, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 16.dp))
    }
}
