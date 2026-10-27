package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.SportsCricket
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SportsFootball
import androidx.compose.material.icons.rounded.SportsMotorsports
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.SportsTennis
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.home.HomeGrid
import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.TodoFeed
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.ui.splash.CloudGlyphPainter
import com.blaze.assistant.ui.splash.HomeIntroSpec

/**
 * 0.47 Home redesign: the settled 2-column grid (Weather | Interests, Music | To-do, Next up, Quick actions) in the
 * approved look: dark navy glass cards with a soft light edge over the sky, white ink. Stateless: everything it shows
 * is in [HomeGridState] (built from real app data by HomeIntro.kt), and while the intro plays each card reads its
 * transform from [HomeIntroClock] in draw / layer lambdas only, so a running intro never recomposes the grid per frame.
 */

/** What Music shows (Blaze Music's player, read from Awareness). */
@Immutable
data class HomeMusicNow(val title: String, val artist: String, val posMs: Long, val durMs: Long, val playing: Boolean)

@Immutable
data class HomeGridState(
    val board: WeatherBoard.Board,
    val topics: List<InterestsFeed.Topic>,
    val music: HomeMusicNow?,
    val tasks: List<TodoFeed.Task>,
    val taskCount: Int,
    val next: NextUp.Item?,
    val actions: List<QuickActions.Action>,
    val h24: Boolean,
    val now: Long,
) {
    val hasTasks: Boolean get() = tasks.isNotEmpty()
}

/** The look, sampled from the approved preview's final frame. */
object HomeGridLook {
    val cardFill = Color(0xFF15203A).copy(alpha = 0.84f)
    val cardEdge = Color(0xFFB4C8E6).copy(alpha = 0.30f)
    val tile = Color(0xFF0E1626).copy(alpha = 0.72f)
    val chipFill = Color(0xFF111A2B).copy(alpha = 0.78f)
    val chipEdge = Color(0xFFB8C8E0).copy(alpha = 0.36f)
    val glassFill = Color.White.copy(alpha = 0.16f)
    val glassEdge = Color.White.copy(alpha = 0.90f)
    val ink = Color.White
    val sub = Color(0xFFC3CEDD)
    val faint = Color(0xFF93A3B8)
    val accent = Color(0xFF4A8CF0)
    val bell = Color(0xFF3A537E)
    val radius = 26.dp
    val gap = 14.dp
    val side = 16.dp
    val row1 = 182.dp
    val row2 = 172.dp
}

/** Topic icon key ([com.blaze.assistant.home.TopicIcons]) -> Material rounded icon. */
fun topicIcon(key: String): ImageVector = when (key) {
    "code" -> Icons.Rounded.Code
    "trend" -> Icons.AutoMirrored.Rounded.TrendingUp
    "gamepad" -> Icons.Rounded.SportsEsports
    "ai" -> Icons.Rounded.AutoAwesome
    "bitcoin" -> Icons.Rounded.CurrencyBitcoin
    "chart" -> Icons.AutoMirrored.Rounded.ShowChart
    "business" -> Icons.Rounded.BusinessCenter
    "wallet" -> Icons.Rounded.AccountBalanceWallet
    "currency" -> Icons.Rounded.CurrencyExchange
    "phone" -> Icons.Rounded.PhoneAndroid
    "devices" -> Icons.Rounded.Devices
    "memory" -> Icons.Rounded.Memory
    "android" -> Icons.Rounded.Android
    "basketball" -> Icons.Rounded.SportsBasketball
    "football" -> Icons.Rounded.SportsFootball
    "f1" -> Icons.Rounded.SportsMotorsports
    "cricket" -> Icons.Rounded.SportsCricket
    "tennis" -> Icons.Rounded.SportsTennis
    "soccer" -> Icons.Rounded.SportsSoccer
    "music" -> Icons.Rounded.MusicNote
    "world" -> Icons.Rounded.Public
    "place" -> Icons.Rounded.Place
    "science" -> Icons.Rounded.Science
    "health" -> Icons.Rounded.Favorite
    "school" -> Icons.Rounded.School
    "video" -> Icons.Rounded.SmartDisplay
    "fitness" -> Icons.Rounded.FitnessCenter
    "run" -> Icons.AutoMirrored.Rounded.DirectionsRun
    "movie" -> Icons.Rounded.Movie
    "weather" -> Icons.Rounded.WbSunny
    "travel" -> Icons.Rounded.Flight
    "food" -> Icons.Rounded.Restaurant
    else -> Icons.Rounded.Newspaper
}

private fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = HomeGridLook.ink) =
    TextStyle(fontFamily = Type.Sans, fontWeight = weight, fontSize = size, color = color)

// ---------------------------------------------------------------- card surface (+ intro transform)

/** Keys of the intro's measured rects. */
object HomeIntroKeys {
    const val ROOT = "root"
    const val ICON = "icon"
    fun card(c: HomeGrid.Card) = "card:" + c.name
}

/**
 * A card: rounded navy glass. During the intro the [index]-th centre card starts small at the centre as light glass with
 * a bright edge, expands, glides to its cell and darkens ([HomeIntroSpec.card]). index < 0 = no centre animation.
 */
@Composable
fun HomeCard(card: HomeGrid.Card, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
             label: String = "", content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(HomeGridLook.radius)
    val key = HomeIntroKeys.card(card)
    Column(modifier
        .then(if (intro != null) Modifier.onGloballyPositioned { intro.bounds[key] = it.boundsInRoot() } else Modifier)
        .then(if (intro != null && index >= 0) Modifier.graphicsLayer {
            val t = intro.cardT(index)
            alpha = t.alpha; scaleX = t.scale; scaleY = t.scale
            val b = intro.bounds[key]; val root = intro.bounds[HomeIntroKeys.ROOT]
            if (b != null && root != null) {
                translationX = (root.center.x - b.center.x) * (1f - t.move)
                translationY = (root.center.y - b.center.y) * (1f - t.move)
            }
        } else Modifier)
        .clip(shape)
        .drawBehind {
            val f = if (intro == null || index < 0) 1f else intro.cardT(index).fill
            drawRect(lerp(HomeGridLook.glassFill, HomeGridLook.cardFill, f))
            val w = (1.5f - 0.5f * f).dp.toPx()
            drawRoundRect(lerp(HomeGridLook.glassEdge, HomeGridLook.cardEdge, f), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(HomeGridLook.radius.toPx() - w / 2), style = Stroke(w))
        }
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label.ifBlank { null }, onClick = onClick) else Modifier)
        .then(if (intro != null && index >= 0) Modifier.graphicsLayer { alpha = intro.cardT(index).content } else Modifier)
        .padding(horizontal = 14.dp, vertical = 12.dp), content = content)
}

// ---------------------------------------------------------------- Weather board

/**
 * Weather board: icon + place on top, then two tiles: condition + temperature, and sunset. In the intro the outline
 * draws itself (path trim), the tiles appear and the text types in; the icon appears when the splash cloud lands on it.
 */
@Composable
fun WeatherCardV2(b: WeatherBoard.Board, h24: Boolean, intro: HomeIntroClock?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(HomeGridLook.radius)
    val key = HomeIntroKeys.card(HomeGrid.Card.WEATHER)
    val painter = remember(b.kind, b.night) { CloudGlyphPainter(b.kind, b.night) }
    val sunset = remember(b.sunset, h24) { WeatherBoard.clock(b.sunset, h24) }
    val desc = listOf(b.place, b.condition, b.temp, if (sunset.isNotBlank()) "sunset $sunset" else "").filter { it.isNotBlank() }.joinToString(", ")
    Column(modifier
        .then(if (intro != null) Modifier.onGloballyPositioned { intro.bounds[key] = it.boundsInRoot() } else Modifier)
        .clip(shape)
        .drawWithCache {
            val stroke = 1.5.dp.toPx()
            val rr = RoundRect(stroke / 2, stroke / 2, size.width - stroke / 2, size.height - stroke / 2, CornerRadius(HomeGridLook.radius.toPx() - stroke / 2))
            // start the trim at the top-left corner, next to the icon, and go clockwise
            val full = Path().apply { addRoundRect(rr) }
            val pm = PathMeasure().apply { setPath(full, false) }
            val seg = Path()
            onDrawBehind {
                val fill = intro?.boardFill() ?: 1f
                val trim = intro?.boardOutline() ?: 1f
                if (trim > 0f) drawRect(lerp(HomeGridLook.glassFill.copy(alpha = 0.10f * trim), HomeGridLook.cardFill, fill))
                if (trim >= 1f) {
                    drawPath(full, lerp(HomeGridLook.glassEdge, HomeGridLook.cardEdge, fill), style = Stroke(stroke - 0.5f * fill * density))
                } else if (trim > 0f) {
                    seg.reset(); pm.getSegment(0f, pm.length * trim, seg, true)
                    drawPath(seg, HomeGridLook.glassEdge, style = Stroke(stroke))
                }
            }
        }
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { contentDescription = desc }
        .padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 28.dp, height = 22.dp)
                .then(if (intro != null) Modifier.onGloballyPositioned { intro.bounds[HomeIntroKeys.ICON] = it.boundsInRoot() } else Modifier)
                .graphicsLayer { alpha = intro?.iconAlpha() ?: 1f }) {
                val f = CloudGlyphPainter.fitIcon(0f, 0f, size.width, size.height)
                painter.draw(this, f[0], f[1], f[2], HomeGridLook.ink, 1.6.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
            TypedText(b.place, intro, HomeIntroSpec.TYPE_PLACE_AT, style(14.sp, FontWeight.Medium), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        BoardTile(intro, Modifier.weight(1f)) {
            TypedText(b.condition.ifBlank { if (b.loaded) "" else "Weather" }.replaceFirstChar { it.titlecase() }, intro, HomeIntroSpec.TYPE_COND_AT, style(11.5.sp, color = HomeGridLook.sub))
            TypedText(b.temp.ifBlank { "--°" }, intro, HomeIntroSpec.TYPE_COND_AT + 80, style(24.sp, FontWeight.SemiBold))
        }
        Spacer(Modifier.height(8.dp))
        BoardTile(intro, Modifier.weight(1f)) {
            TypedText("Sunset", intro, HomeIntroSpec.TYPE_SUN_AT, style(11.5.sp, color = HomeGridLook.sub))
            TypedText(sunset.ifBlank { "--:--" }, intro, HomeIntroSpec.TYPE_SUN_AT + 80, style(24.sp, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun BoardTile(intro: HomeIntroClock?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth()
        .graphicsLayer { alpha = intro?.boardTiles() ?: 1f }
        .clip(shape)
        .drawBehind {
            val f = intro?.boardFill() ?: 1f
            drawRect(lerp(Color.White.copy(alpha = 0.08f), HomeGridLook.tile, f))
            if (f < 1f) drawRoundRect(HomeGridLook.glassEdge.copy(alpha = 0.85f * (1f - f)), cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center, content = content)
}

/** A line that types itself in during the intro (recomposes only when the visible length changes). */
@Composable
fun TypedText(text: String, intro: HomeIntroClock?, at: Long, style: TextStyle, modifier: Modifier = Modifier) {
    val n by remember(text, intro) { derivedStateOf { if (intro == null) text.length else intro.typed(text.length, at) } }
    // The full line keeps its height from the start (no layout shift while typing).
    Text(if (n >= text.length) text else text.take(n), modifier = modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        minLines = 1)
}

// ---------------------------------------------------------------- Interests

@Composable
fun InterestsCardV2(topics: List<InterestsFeed.Topic>, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier,
                    onTopic: (InterestsFeed.Topic) -> Unit, onEmpty: () -> Unit) {
    HomeCard(HomeGrid.Card.INTERESTS, intro, index, modifier, onClick = if (topics.isEmpty()) onEmpty else null, label = "Pick interests") {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = HomeGridLook.ink)) { append("Interests") }
            withStyle(SpanStyle(color = HomeGridLook.sub)) { append(" \u00b7 Tracking") }
        }, modifier = Modifier.semantics { heading() }, style = style(15.sp), maxLines = 1)
        Spacer(Modifier.height(6.dp))
        if (topics.isEmpty()) {
            Text("Tell me what you're into and the latest headlines show up here.", style = style(12.sp, color = HomeGridLook.sub), maxLines = 4)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            topics.forEach { t ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = t.url.isNotBlank(), role = Role.Button) { onTopic(t) }
                    .semantics(mergeDescendants = true) { }) {
                    TopicChip(t.label, t.icon)
                    Text(t.headline.ifBlank { "Fetching the latest\u2026" }, style = style(11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                    if (t.meta.isNotBlank()) Text(t.meta, style = style(9.5.sp, color = HomeGridLook.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun TopicChip(label: String, icon: String) {
    Row(Modifier.clip(CircleShape).background(HomeGridLook.chipFill).border(1.dp, HomeGridLook.chipEdge, CircleShape)
        .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(topicIcon(icon), contentDescription = null, tint = HomeGridLook.ink, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = style(10.5.sp, FontWeight.SemiBold), maxLines = 1)
    }
}

// ---------------------------------------------------------------- Music

private fun clock(ms: Long): String { if (ms < 0) return "0:00"; val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun MusicCardV2(m: HomeMusicNow?, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onOpen: () -> Unit, onToggle: () -> Unit) {
    HomeCard(HomeGrid.Card.MUSIC, intro, index, modifier, onClick = onOpen, label = "Open Music") {
        Text(if (m == null) "Music" else if (m.playing) "Now playing" else "Paused", style = style(12.sp, color = HomeGridLook.sub))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF6A15A), Color(0xFFE48A5E), Color(0xFF5B8DEF)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFF2F4F7))
                .clickable(role = Role.Button, onClickLabel = if (m?.playing == true) "Pause" else "Play", onClick = if (m == null) onOpen else onToggle),
                contentAlignment = Alignment.Center) {
                Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (m?.playing == true) "Pause" else "Play", tint = Color(0xFF15203A), modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(m?.title?.ifBlank { null } ?: "Nothing playing", style = style(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(m?.artist?.ifBlank { null } ?: "Open Music", style = style(11.5.sp, color = HomeGridLook.sub), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        val frac = if (m != null && m.durMs > 0) (m.posMs.toFloat() / m.durMs).coerceIn(0f, 1f) else 0f
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val y = size.height / 2; val h = 3.dp.toPx()
            drawLine(Color(0xFF5D6C78), Offset(0f, y), Offset(size.width, y), strokeWidth = h, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color(0xFFEDEDF2), Offset(0f, y), Offset(size.width * frac, y), strokeWidth = h, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(Color(0xFFEDEDF2), 4.5.dp.toPx(), Offset(size.width * frac, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clock(m?.posMs ?: 0L), style = style(9.5.sp, color = HomeGridLook.faint))
            Spacer(Modifier.weight(1f))
            Text(clock(m?.durMs ?: 0L), style = style(9.5.sp, color = HomeGridLook.faint))
        }
    }
}

// ---------------------------------------------------------------- To-do

@Composable
fun TodoCardV2(tasks: List<TodoFeed.Task>, count: Int, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    HomeCard(HomeGrid.Card.TODO, intro, index, modifier, onClick = onOpen, label = "Open to-do") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("To-do", style = style(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f).semantics { heading() })
            Text("$count", style = style(12.sp, color = HomeGridLook.sub))
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            tasks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = t.title + if (t.done) ", done" else ""
                }) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                        .then(if (t.done) Modifier.background(HomeGridLook.accent) else Modifier.border(1.6.dp, Color(0xFFE6ECF4), RoundedCornerShape(6.dp))),
                        contentAlignment = Alignment.Center) {
                        if (t.done) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(t.title, style = style(13.5.sp, color = if (t.done) HomeGridLook.faint else HomeGridLook.ink)
                        .copy(textDecoration = if (t.done) TextDecoration.LineThrough else null), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Next up

@Composable
fun NextUpCardV2(item: NextUp.Item?, now: Long, h24: Boolean, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val line = if (item == null) "Nothing scheduled" else item.title + " \u00b7 " + NextUp.whenLabel(item.at, now, h24)
    HomeCard(HomeGrid.Card.NEXT_UP, intro, index, modifier, onClick = onOpen, label = "Open next up") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(HomeGridLook.bell), contentAlignment = Alignment.Center) {
                Icon(if (item?.kind == NextUp.Kind.CALENDAR) Icons.Rounded.Event else Icons.Rounded.NotificationsNone, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Next up", style = style(12.sp, color = HomeGridLook.sub))
                Text(line, style = style(16.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HomeGridLook.sub, modifier = Modifier.size(26.dp))
        }
    }
}

// ---------------------------------------------------------------- Quick actions

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickActionsCardV2(actions: List<QuickActions.Action>, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onAction: (QuickActions.Action) -> Unit) {
    HomeCard(HomeGrid.Card.QUICK, intro, index, modifier) {
        Text("Quick actions", style = style(14.sp, FontWeight.SemiBold), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        actions.forEach { a ->
            FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = a.verb) { onAction(a) }
                .semantics(mergeDescendants = true) { contentDescription = a.verb + " " + a.rest }.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(a.verb, style = style(13.5.sp, FontWeight.SemiBold), maxLines = 1)
                }
                Text(a.rest, style = style(13.5.sp, color = HomeGridLook.ink.copy(alpha = 0.92f)),
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- the grid

/**
 * The settled grid ([HomeGrid.rows]): row 1 and row 2 have fixed heights (no layout shift while data loads, which also
 * keeps the intro's measured cells stable); Next up and Quick actions wrap their content.
 */
@Composable
fun HomeGridV2(s: HomeGridState, intro: HomeIntroClock?, modifier: Modifier = Modifier,
               onWeather: () -> Unit = {}, onTopic: (InterestsFeed.Topic) -> Unit = {}, onPickInterests: () -> Unit = {},
               onMusic: () -> Unit = {}, onMusicToggle: () -> Unit = {}, onTodo: () -> Unit = {}, onNextUp: () -> Unit = {},
               onAction: (QuickActions.Action) -> Unit = {}, footer: @Composable ColumnScope.() -> Unit = {}) {
    val order = remember(s.hasTasks) { HomeGrid.order(s.hasTasks).filter { it != HomeGrid.Card.WEATHER } }
    fun idx(c: HomeGrid.Card) = order.indexOf(c)
    Column(modifier.fillMaxWidth().padding(horizontal = HomeGridLook.side), verticalArrangement = Arrangement.spacedBy(HomeGridLook.gap)) {
        HomeGrid.rows(s.hasTasks).forEach { row ->
            if (row.full) {
                when (row.cards.first()) {
                    HomeGrid.Card.NEXT_UP -> NextUpCardV2(s.next, s.now, s.h24, intro, idx(HomeGrid.Card.NEXT_UP), Modifier.fillMaxWidth().heightIn(min = 64.dp), onNextUp)
                    HomeGrid.Card.QUICK -> QuickActionsCardV2(s.actions, intro, idx(HomeGrid.Card.QUICK), Modifier.fillMaxWidth(), onAction)
                    else -> {}
                }
            } else {
                val h = if (row.cards.contains(HomeGrid.Card.WEATHER)) HomeGridLook.row1 else HomeGridLook.row2
                Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(HomeGridLook.gap)) {
                    for (i in 0 until 2) {
                        val c = row.cards.getOrNull(i)
                        val cell = Modifier.weight(1f).fillMaxHeight()
                        when (c) {
                            HomeGrid.Card.WEATHER -> WeatherCardV2(s.board, s.h24, intro, cell, onWeather)
                            HomeGrid.Card.INTERESTS -> InterestsCardV2(s.topics, intro, idx(c), cell, onTopic, onPickInterests)
                            HomeGrid.Card.MUSIC -> MusicCardV2(s.music, intro, idx(c), cell, onMusic, onMusicToggle)
                            HomeGrid.Card.TODO -> TodoCardV2(s.tasks, s.taskCount, intro, idx(c), cell, onTodo)
                            else -> Spacer(cell)
                        }
                    }
                }
            }
        }
        footer()
    }
}
