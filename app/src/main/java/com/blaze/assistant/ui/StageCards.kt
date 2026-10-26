package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.stage.StageItem
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageTimer
import com.blaze.assistant.stage.StageTools
import com.blaze.assistant.stage.TimerState

/**
 * 0.46.0 Blaze Stage cards: the stateless faces of every hero and widget. They get plain data ([StageData]) and report taps
 * ([StageEvent]); StageHost feeds them live values (the timer once a second, the music position once a second) so the
 * screenshot tests can draw every card in any theme without services. Only theme colours ([LocalBlaze]) are used.
 */
@Immutable
data class StageMusicNow(val playing: Boolean = false, val title: String = "", val artist: String = "", val posMs: Long = 0, val durMs: Long = 0, val hasService: Boolean = false)

@Immutable
data class StageData(val now: Long = 0L, val timer: TimerState? = null, val music: StageMusicNow = StageMusicNow(),
                     val cardFront: String = "", val cardBack: String = "", val cardsLeft: Int = 0)

sealed class StageEvent {
    data class Timer(val op: String, val minutes: Double = 5.0) : StageEvent()
    data class Music(val op: String) : StageEvent()
    data class QuizAnswer(val index: Int) : StageEvent()
    object QuizNext : StageEvent()
    data class Card(val grade: Int) : StageEvent()
    object Flip : StageEvent()
    data class Toggle(val index: Int) : StageEvent()
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

@Composable
private fun Chip(text: String, primary: Boolean = false, desc: String = text, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(modifier.clip(RoundedCornerShape(50)).background(if (primary) c.accent else c.faint).border(1.dp, if (primary) c.accent else c.hair, RoundedCornerShape(50))
        .clickable(role = Role.Button, onClickLabel = desc) { onClick() }.padding(horizontal = 16.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) (if (c.dark) Color.Black else Color.White) else c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The thin header every hero has: title, kind, and the ⋯ menu (move, shrink, open in full view, close, reset layout). */
@Composable
private fun HeroHeader(item: StageItem, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.props["title"]?.ifBlank { null } ?: item.kind.label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        Text("\u2013", color = c.sub, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Shrink to a widget") { on(StageEvent.Shrink) }
            .padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "Shrink ${item.kind.label} to a widget" })
        Text("\u22EF", color = c.sub, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "More") { on(StageEvent.Menu) }
            .padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "${item.kind.label} options" })
    }
}

// ---------------------------------------------------------------- heroes

@Composable
fun StageHero(item: StageItem, d: StageData, modifier: Modifier = Modifier, low: Boolean = false, video: (@Composable (Modifier) -> Unit)? = null, on: (StageEvent) -> Unit) {
    StageCardFrame(modifier, desc = heroDesc(item, d)) {
        Column(Modifier.fillMaxSize()) {
            HeroHeader(item, on)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                when (item.kind) {
                    StageKind.TIMER -> TimerFace(d.timer, d.now, low, on)
                    StageKind.MUSIC -> MusicFace(d.music, on)
                    StageKind.VIDEO -> VideoFace(item, video, on)
                    StageKind.QUIZ -> QuizFace(item, on)
                    StageKind.FLASHCARDS -> CardsFace(item, d, on)
                    StageKind.PLAN -> PlanFace(item, on)
                    StageKind.NOTE -> NoteFace(item)
                }
            }
        }
    }
}

internal fun heroDesc(item: StageItem, d: StageData): String = when (item.kind) {
    StageKind.TIMER -> d.timer?.let { "${it.label} timer, ${StageTimer.clock(StageTimer.remaining(it, d.now))} left" + if (it.running) "" else ", not running" } ?: "Timer"
    StageKind.MUSIC -> if (d.music.title.isNotBlank()) "Music, ${d.music.title}" + (if (d.music.playing) ", playing" else ", paused") else "Music"
    else -> item.props["title"]?.ifBlank { null } ?: item.kind.label
}

@Composable
private fun TimerFace(t: TimerState?, now: Long, low: Boolean, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    if (t == null) { Text("Timer", color = c.sub); return }
    val left = StageTimer.remaining(t, now)
    val p = StageTimer.progress(t, now)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(220.dp).heightIn(max = 220.dp), contentAlignment = Alignment.Center) {
            // The ring redraws only when the second changes (p changes once a second); no per-frame work.
            val track = c.hair; val fill = c.accent
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val sw = 10.dp.toPx(); val r = Size(size.width - sw, size.height - sw); val o = Offset(sw / 2, sw / 2)
                drawArc(track, 0f, 360f, false, o, r, style = Stroke(sw))
                if (p > 0f) drawArc(fill, -90f, 360f * p, false, o, r, style = Stroke(sw, cap = if (low) StrokeCap.Butt else StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(StageTimer.clock(left), color = c.ink, fontSize = 46.sp, fontWeight = FontWeight.Light)
                Text(if (t.done) "Done" else if (t.phases.size > 1) t.phaseName else if (t.running) "Running" else if (t.started) "Paused" else StageTimer.words(t.phases[0]),
                    color = c.sub, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                t.done -> Chip("Again", primary = true, desc = "Start the timer again") { on(StageEvent.Timer("start")) }
                t.running -> { Chip("Pause") { on(StageEvent.Timer("pause")) }; Chip("+5 min", desc = "Add five minutes") { on(StageEvent.Timer("add", 5.0)) } }
                t.started -> { Chip("Resume", primary = true) { on(StageEvent.Timer("start")) }; Chip("Reset") { on(StageEvent.Timer("reset")) } }
                else -> Chip("Start", primary = true, desc = "Start the ${t.label} timer") { on(StageEvent.Timer("start")) }
            }
        }
    }
}

@Composable
private fun MusicFace(m: StageMusicNow, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(150.dp).clip(RoundedCornerShape(28.dp)).background(c.faint).border(1.dp, c.hair, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
            Text("\u266B", color = c.accent, fontSize = 56.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (!m.hasService && m.title.isBlank()) {
            Text("No music playing", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Chip("Play", primary = true) { on(StageEvent.Music("play")) }; Chip("Open Music") { on(StageEvent.OpenFull) } }
            return@Column
        }
        Text(m.title.ifBlank { "Blaze Music" }, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (m.artist.isNotBlank()) Text(m.artist, color = c.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        val frac = if (m.durMs > 0) (m.posMs.toFloat() / m.durMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.hair)) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).background(c.accent))
        }
        Row(Modifier.fillMaxWidth(0.8f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(StageTimer.clock(m.posMs).let { if (m.posMs < 1000) "0:00" else it }, color = c.sub, fontSize = 11.sp)
            Text(if (m.durMs > 0) StageTimer.clock(m.durMs) else "", color = c.sub, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("\u23EE", desc = "Previous song") { on(StageEvent.Music("previous")) }
            Chip(if (m.playing) "Pause" else "Play", primary = true) { on(StageEvent.Music(if (m.playing) "pause" else "play")) }
            Chip("\u23ED", desc = "Next song") { on(StageEvent.Music("next")) }
        }
    }
}

@Composable
private fun VideoFace(item: StageItem, video: (@Composable (Modifier) -> Unit)?, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val url = item.props["url"].orEmpty()
    if (video != null && url.isNotBlank() && !StageVideoUrl.isYouTube(url)) { video(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))); return }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = if (c.dark) 0.6f else 0.85f)), contentAlignment = Alignment.Center) {
            Text("\u25B6", color = Color.White, fontSize = 40.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(if (url.isBlank()) "No video picked" else if (StageVideoUrl.isYouTube(url)) "YouTube plays in Blaze Video" else url.substringAfterLast('/').take(60), color = c.sub, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Chip("Open in Blaze Video", primary = true) { on(StageEvent.OpenFull) }
    }
}

object StageVideoUrl { fun isYouTube(u: String) = Regex("""(?i)(youtube\.com|youtu\.be)/""").containsMatchIn(u) }

@Composable
private fun QuizFace(item: StageItem, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val qs = StageTools.questions(item.props["questions"])
    val at = item.props["at"]?.toIntOrNull() ?: 0
    val picked = item.props["picked"]?.toIntOrNull()
    val score = item.props["score"]?.toIntOrNull() ?: 0
    if (qs.isEmpty()) { Text("No questions", color = c.sub); return }
    if (at >= qs.size) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score / ${qs.size}", color = c.ink, fontSize = 44.sp, fontWeight = FontWeight.Light)
            Text("Quiz done", color = c.sub, fontSize = 14.sp)
        }
        return
    }
    val q = qs[at]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Question ${at + 1} of ${qs.size}", color = c.sub, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(q.q, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        q.options.forEachIndexed { i, o ->
            val right = picked != null && i == q.answer; val wrong = picked == i && i != q.answer
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(when { right -> c.accent.copy(alpha = 0.22f); wrong -> Color(0x33E5484D); else -> c.faint })
                .border(1.dp, if (right) c.accent else c.hair, RoundedCornerShape(14.dp))
                .clickable(enabled = picked == null, role = Role.Button, onClickLabel = "Answer") { on(StageEvent.QuizAnswer(i)) }.padding(12.dp)) {
                Text(('A' + i) + ".  " + o + (if (right) "  \u2713" else if (wrong) "  \u2717" else ""), color = c.ink, fontSize = 15.sp)
            }
        }
        if (picked != null) { Spacer(Modifier.height(8.dp)); Chip(if (at + 1 < qs.size) "Next" else "See score", primary = true) { on(StageEvent.QuizNext) } }
    }
}

@Composable
private fun CardsFace(item: StageItem, d: StageData, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    if (d.cardFront.isBlank()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("All done for now", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(item.props["deck"].orEmpty(), color = c.sub, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp)); Chip("Open Learn") { on(StageEvent.OpenFull) }
        }
        return
    }
    val flipped = item.props["flipped"] == "true"
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Text("${item.props["deck"].orEmpty()} \u00b7 ${d.cardsLeft} left", color = c.sub, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.faint).border(1.dp, c.hair, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = "Flip the card") { on(StageEvent.Flip) }.padding(18.dp), contentAlignment = Alignment.Center) {
            Text(if (flipped) d.cardBack else d.cardFront, color = c.ink, fontSize = if (flipped) 16.sp else 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }
        Spacer(Modifier.height(10.dp))
        if (!flipped) Chip("Show answer", primary = true) { on(StageEvent.Flip) }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Again") { on(StageEvent.Card(0)) }; Chip("Good", primary = true) { on(StageEvent.Card(2)) }; Chip("Easy") { on(StageEvent.Card(3)) }
        }
    }
}

@Composable
private fun PlanFace(item: StageItem, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val items = StageTools.items(item.props["items"])
    val done = item.props["done"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        item.props["text"]?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.body, fontSize = 14.sp); Spacer(Modifier.height(8.dp)) }
        items.forEachIndexed { i, line ->
            val on1 = i in done
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox, onClickLabel = if (on1) "Mark not done" else "Mark done") { on(StageEvent.Toggle(i)) }
                .padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (on1) c.accent else Color.Transparent).border(1.5.dp, if (on1) c.accent else c.sub, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) { if (on1) Text("\u2713", color = if (c.dark) Color.Black else Color.White, fontSize = 12.sp) }
                Spacer(Modifier.width(12.dp))
                Text(line, color = if (on1) c.sub else c.ink, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun NoteFace(item: StageItem) {
    val c = LocalBlaze.current
    Text(item.props["text"].orEmpty(), color = c.ink, fontSize = 16.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
}

// ---------------------------------------------------------------- widgets (small tiles)

@Composable
fun StageWidget(item: StageItem, d: StageData, modifier: Modifier = Modifier, narrow: Boolean = false, video: (@Composable (Modifier) -> Unit)? = null, on: (StageEvent) -> Unit) {
    val c = LocalBlaze.current
    val (title, sub) = widgetText(item, d)
    StageCardFrame(modifier.widthIn(min = if (narrow) 64.dp else 120.dp, max = if (narrow) 72.dp else 220.dp).height(if (narrow) 72.dp else 56.dp), desc = "$title, $sub. Tap to make it big", radius = 18.dp) {
        if (item.kind == StageKind.VIDEO && video != null && !item.props["url"].isNullOrBlank() && !StageVideoUrl.isYouTube(item.props["url"].orEmpty())) {
            video(Modifier.fillMaxSize()); return@StageCardFrame
        }
        Row(Modifier.fillMaxSize().padding(horizontal = if (narrow) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (item.kind) { StageKind.TIMER -> "\u23F1"; StageKind.MUSIC -> "\u266B"; StageKind.VIDEO -> "\u25B6"; StageKind.QUIZ -> "?"; StageKind.FLASHCARDS -> "\u25A4"; StageKind.PLAN -> "\u2611"; StageKind.NOTE -> "\u270E" }
            if (narrow) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(icon, color = c.accent, fontSize = 18.sp)
                    Text(sub, color = c.ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                return@Row
            }
            Text(icon, color = c.accent, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(title, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = c.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // One quick control where it matters (play / pause, start / pause), so the tile works without expanding.
            when (item.kind) {
                StageKind.MUSIC -> { Spacer(Modifier.width(6.dp)); MiniButton(if (d.music.playing) "\u23F8" else "\u25B6", if (d.music.playing) "Pause music" else "Play music") { on(StageEvent.Music(if (d.music.playing) "pause" else "play")) } }
                StageKind.TIMER -> d.timer?.takeIf { !it.done }?.let { t -> Spacer(Modifier.width(6.dp)); MiniButton(if (t.running) "\u23F8" else "\u25B6", if (t.running) "Pause timer" else "Start timer") { on(StageEvent.Timer(if (t.running) "pause" else "start")) } }
                else -> {}
            }
        }
    }
}

@Composable
private fun MiniButton(glyph: String, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(34.dp).clip(CircleShape).background(c.faint).clickable(role = Role.Button, onClickLabel = desc) { onClick() }.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { Text(glyph, color = c.ink, fontSize = 14.sp) }
}

internal fun widgetText(item: StageItem, d: StageData): Pair<String, String> {
    val title = item.props["title"]?.ifBlank { null } ?: item.kind.label
    return when (item.kind) {
        StageKind.TIMER -> title to (d.timer?.let { t -> if (t.done) "Done" else StageTimer.clock(StageTimer.remaining(t, d.now)) + if (!t.running) " \u00b7 paused" else "" } ?: "")
        StageKind.MUSIC -> (d.music.title.ifBlank { "Music" }) to (d.music.artist.ifBlank { if (d.music.playing) "Playing" else "Paused" })
        StageKind.VIDEO -> title to "Video"
        StageKind.QUIZ -> title to StageTools.questions(item.props["questions"]).size.let { n -> "${minOf(item.props["at"]?.toIntOrNull() ?: 0, n)}/$n \u00b7 ${item.props["score"] ?: "0"} right" }
        StageKind.FLASHCARDS -> (item.props["deck"] ?: title) to "${d.cardsLeft} left"
        StageKind.PLAN -> StageTools.items(item.props["items"]).let { l -> title to "${item.props["done"].orEmpty().split(',').count { it.isNotBlank() }}/${l.size} done" }
        StageKind.NOTE -> title to item.props["text"].orEmpty().take(40)
    }
}

/** The chat as a small tile (one line of the newest message); tap = the chat back. */
@Composable
fun StageChatTile(last: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val c = LocalBlaze.current
    StageCardFrame(modifier.widthIn(min = 140.dp, max = 260.dp).height(56.dp).clickable(role = Role.Button, onClickLabel = "Show the chat") { onOpen() }, desc = "Chat: $last. Tap to show the chat", radius = 18.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("\uD83D\uDCAC", fontSize = 16.sp); Spacer(Modifier.width(8.dp))
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
