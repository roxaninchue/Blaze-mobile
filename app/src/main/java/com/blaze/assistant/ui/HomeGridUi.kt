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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
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
import com.blaze.assistant.ui.splash.HomeSequence
import androidx.compose.ui.graphics.drawscope.clipRect

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
    /** RC2: every tracked interest, for the focused Interests card (the grid shows [topics], at most 3). */
    val allTopics: List<InterestsFeed.Topic> = topics,
) {
    val hasTasks: Boolean get() = tasks.isNotEmpty()
}

/**
 * The look, sampled from the approved preview's final frame (540 x 1200 px = 360 x 800 dp at 1.5x). RC2: sizes measured
 * against that frame (cards 154.5 dp wide with 17 dp gutters; type about 15% smaller than RC1), so a 360 dp phone at
 * the default font scale shows the reference's proportions.
 */
object HomeGridLook {
    val cardFill = Color(0xFF18233A).copy(alpha = 0.84f)
    val cardEdge = Color(0xFFB4C8E6).copy(alpha = 0.30f)
    /** The reference's card outline: a 1.3 dp light band just inside the edge (2 px at 1.5x). */
    val edgeWidth = 1.35.dp
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
    val radius = 24.dp
    val gap = 17.dp
    val side = 17.dp
    val row1 = 179.dp
    val row2 = 171.dp
    /** Without tasks Music is one full-width row (no empty cell beside it). */
    val musicWide = 92.dp
    /** Card titles (Interests): start size and the smallest step [FitTitle] may take to fit one line. */
    const val titleSp = 12.5f
    const val titleMinSp = 10f
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
 * A card: rounded navy glass. During the intro the [index]-th card after the board plays its part of the approved
 * sequence ([HomeSequence]): Interests' chips first then its frame, Music / To-do emerging at the centre and gliding to
 * their cell, Next up / Quick actions drawing their outline in place and lifting into their row. index < 0 = no intro.
 */
@Composable
fun HomeCard(card: HomeGrid.Card, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
             label: String = "", content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(HomeGridLook.radius)
    val key = HomeIntroKeys.card(card)
    // RC2 fix (frames gone after the intro): the modifier chain has the SAME shape with and without the intro. RC1 added
    // the two graphicsLayers only while the intro ran, so the settled recomposition removed layer nodes from the chain;
    // on the phone the card's drawBehind (fill + outline) was then not re-recorded until the next cold start. Now the
    // layers are always there (identity when settled) and every intro value is read inside their lambdas.
    val live = intro?.takeIf { index >= 0 }
    val focus = LocalHomeFocus.current
    Column(modifier
        .onGloballyPositioned { if (intro != null && !intro.done) intro.bounds[key] = it.boundsInRoot(); focus?.cells?.set(card, it.boundsInRoot()) }
        .graphicsLayer {
            val t = HomeCardFrame.of(live, index)
            alpha = t.alpha * (focus?.gridAlpha(card) ?: 1f); scaleX = t.scale; scaleY = t.scale
            val b = live?.bounds?.get(key); val root = live?.bounds?.get(HomeIntroKeys.ROOT)
            if (b != null && root != null && t.move < 1f) {
                val drop = if (card == HomeGrid.Card.TODO) HomeSequence.TODO_CENTER_DROP * root.height else 0f
                translationX = (root.center.x - b.center.x) * (1f - t.move)
                translationY = (root.center.y + drop - b.center.y) * (1f - t.move)
            } else { translationX = 0f; translationY = t.lift * HomeSequence.LIFT_DP * density }
        }
        // the card's rounded clip, as a layer that is always there (stable chain); off only while Interests' chips are
        // still larger than the card at the centre (they overflow it in the video too)
        .graphicsLayer { this.shape = shape; clip = HomeCardFrame.of(live, index).contentScale <= 1.01f }
        .drawWithCache {
            val settled = HomeGridLook.edgeWidth.toPx()
            val r = HomeGridLook.radius.toPx()
            val trimPath = Path(); val pm = PathMeasure(); val seg = Path()
            var measuredW = settled
            onDrawBehind {
                val t = HomeCardFrame.of(live, index)
                val f = t.fill
                val fa = t.frameAlpha
                if (fa > 0f) drawRoundRect(lerp(HomeGridLook.glassFill, HomeGridLook.cardFill, f), cornerRadius = CornerRadius(r), alpha = fa)
                val w = HomeCardFrame.edgePx(f, settled, density)
                val edge = lerp(HomeGridLook.glassEdge, HomeGridLook.cardEdge, f)
                if (t.outline >= 1f) {
                    drawRoundRect(edge, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(r - w / 2), style = Stroke(w))
                } else if (t.outline > 0f) {
                    // the outline draws itself (path trim) from the top-left corner, clockwise
                    if (trimPath.isEmpty || measuredW != w) {
                        trimPath.reset(); clockwiseRoundRect(trimPath, w / 2, w / 2, size.width - w / 2, size.height - w / 2, r - w / 2)
                        pm.setPath(trimPath, false); measuredW = w
                    }
                    seg.reset(); pm.getSegment(0f, pm.length * t.outline, seg, true)
                    drawPath(seg, edge, style = Stroke(w))
                }
            }
        }
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label.ifBlank { null }, onClick = onClick) else Modifier)
        .graphicsLayer { alpha = HomeCardFrame.of(live, index).content }
        .padding(horizontal = 13.dp, vertical = 11.dp), content = content)
}

/**
 * What a card's frame shows: the intro's transform while it runs, otherwise [HomeIntroSpec.SETTLED] (full fill and
 * outline). Pure, so the settled frame is unit tested for every way Home is reached: no intro (later visits, reduce
 * motion), an intro that finished, and an intro that was skipped.
 */
object HomeCardFrame {
    fun of(intro: HomeIntroClock?, index: Int = 0): HomeSequence.Frame = when {
        intro == null || intro.done -> HomeIntroSpec.SETTLED
        else -> intro.cardT(index)
    }
    /** The outline's width: a bright 1.5 dp edge while the card is light glass, the reference's band when settled. */
    fun edgePx(fill: Float, settledPx: Float, density: Float): Float = 1.5f * density + (settledPx - 1.5f * density) * fill.coerceIn(0f, 1f)
}

/** A rounded rectangle traced clockwise from the top-left corner's end (so a path trim draws the top edge first). */
fun clockwiseRoundRect(p: Path, l: Float, t: Float, r: Float, b: Float, rad: Float) {
    val k = rad.coerceAtMost(minOf(r - l, b - t) / 2f).coerceAtLeast(0f); val d = 2 * k
    p.moveTo(l + k, t)
    p.lineTo(r - k, t); p.arcTo(androidx.compose.ui.geometry.Rect(r - d, t, r, t + d), -90f, 90f, false)
    p.lineTo(r, b - k); p.arcTo(androidx.compose.ui.geometry.Rect(r - d, b - d, r, b), 0f, 90f, false)
    p.lineTo(l + k, b); p.arcTo(androidx.compose.ui.geometry.Rect(l, b - d, l + d, b), 90f, 90f, false)
    p.lineTo(l, t + k); p.arcTo(androidx.compose.ui.geometry.Rect(l, t, l + d, t + d), 180f, 90f, false)
    p.close()
}

// ---------------------------------------------------------------- card content in the intro

/** ms on card [index]'s own intro clock; huge (= settled) without a running intro. */
fun HomeIntroClock?.local(index: Int): Long = if (this == null || done || index < 0) Long.MAX_VALUE / 4 else cardLocal(index)

/** A line that "types in" during the intro: revealed left to right from [at] (card-local ms), read in draw only (no recomposition). */
fun Modifier.introType(intro: HomeIntroClock?, index: Int, at: Long, len: Int): Modifier = drawWithContent {
    val p = if (intro == null || intro.done || index < 0) 1f else HomeSequence.p(intro.cardLocal(index), at, HomeSequence.lineMs(len))
    if (p >= 1f) drawContent() else if (p > 0f) clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
}

/** An element that fades in during the intro from [at] (card-local ms). */
fun Modifier.introAppear(intro: HomeIntroClock?, index: Int, at: Long, ms: Long = 180L): Modifier = graphicsLayer {
    alpha = if (intro == null || intro.done || index < 0) 1f else HomeSequence.appear(intro.cardLocal(index), at, ms)
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
    val focus = LocalHomeFocus.current
    Column(modifier
        // RC2: same chain shape with and without the intro (see HomeCard)
        .onGloballyPositioned { if (intro != null && !intro.done) intro.bounds[key] = it.boundsInRoot(); focus?.cells?.set(HomeGrid.Card.WEATHER, it.boundsInRoot()) }
        .graphicsLayer { alpha = focus?.gridAlpha(HomeGrid.Card.WEATHER) ?: 1f }
        .clip(shape)
        .drawWithCache {
            val stroke = 1.5.dp.toPx()
            val settled = HomeGridLook.edgeWidth.toPx()
            val rr = RoundRect(stroke / 2, stroke / 2, size.width - stroke / 2, size.height - stroke / 2, CornerRadius(HomeGridLook.radius.toPx() - stroke / 2))
            // start the trim at the top-left corner, next to the icon, and go clockwise
            val full = Path().apply { addRoundRect(rr) }
            // the trim follows the top edge first, like the video (addRoundRect alone runs counter-clockwise)
            val trimmed = Path().also { clockwiseRoundRect(it, stroke / 2, stroke / 2, size.width - stroke / 2, size.height - stroke / 2, HomeGridLook.radius.toPx() - stroke / 2) }
            val pm = PathMeasure().apply { setPath(trimmed, false) }
            val seg = Path()
            onDrawBehind {
                val live = intro?.takeIf { !it.done }
                val fill = live?.boardFill() ?: 1f
                val trim = live?.boardOutline() ?: 1f
                if (trim > 0f) drawRect(lerp(HomeGridLook.glassFill.copy(alpha = 0.10f * trim), HomeGridLook.cardFill, fill))
                if (trim >= 1f) {
                    drawPath(full, lerp(HomeGridLook.glassEdge, HomeGridLook.cardEdge, fill), style = Stroke(stroke + (settled - stroke) * fill))
                } else if (trim > 0f) {
                    seg.reset(); pm.getSegment(0f, pm.length * trim, seg, true)
                    drawPath(seg, HomeGridLook.glassEdge, style = Stroke(stroke))
                }
            }
        }
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { contentDescription = desc }
        .padding(start = 10.dp, end = 10.dp, top = 11.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the condition icon (sun / cloud variants / rain / snow / storm / fog, day or night) is the splash cloud's glyph
            Canvas(Modifier.padding(start = 4.dp).size(width = 24.dp, height = 18.dp)
                .onGloballyPositioned { if (intro != null && !intro.done) intro.bounds[HomeIntroKeys.ICON] = it.boundsInRoot() }
                .graphicsLayer { alpha = intro?.takeIf { !it.done }?.iconAlpha() ?: 1f }) {
                val f = CloudGlyphPainter.fitIcon(0f, 0f, size.width, size.height)
                painter.draw(this, f[0], f[1], f[2], HomeGridLook.ink, 1.4.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
            TypedText(b.place, intro, HomeIntroSpec.TYPE_PLACE_AT, style(12.5.sp), Modifier.weight(1f).then(header(HomeGrid.Card.WEATHER)))
        }
        Spacer(Modifier.height(8.dp))
        BoardTile(intro, Modifier.weight(1f)) {
            TypedText(b.condition.ifBlank { if (b.loaded) "" else "Weather" }.replaceFirstChar { it.titlecase() }, intro, HomeIntroSpec.TYPE_COND_AT, style(10.5.sp, color = HomeGridLook.sub))
            TypedText(b.temp.ifBlank { "--°" }, intro, HomeIntroSpec.TYPE_COND_AT + 80, style(20.5.sp, FontWeight.Bold))
        }
        Spacer(Modifier.height(7.dp))
        BoardTile(intro, Modifier.weight(1f)) {
            TypedText("Sunset", intro, HomeIntroSpec.TYPE_SUN_AT, style(10.5.sp, color = HomeGridLook.sub))
            TypedText(sunset.ifBlank { "--:--" }, intro, HomeIntroSpec.TYPE_SUN_AT + 80, style(20.5.sp, FontWeight.Bold))
        }
    }
}

@Composable
private fun BoardTile(intro: HomeIntroClock?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Column(modifier.fillMaxWidth()
        .graphicsLayer { alpha = intro?.takeIf { !it.done }?.boardTiles() ?: 1f }
        .clip(shape)
        .drawBehind {
            val f = intro?.takeIf { !it.done }?.boardFill() ?: 1f
            drawRect(lerp(Color.White.copy(alpha = 0.08f), HomeGridLook.tile, f))
            if (f < 1f) drawRoundRect(HomeGridLook.glassEdge.copy(alpha = 0.85f * (1f - f)), cornerRadius = CornerRadius(15.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        }
        .padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center, content = content)
}

/** A line that types itself in during the intro (recomposes only when the visible length changes). */
@Composable
fun TypedText(text: String, intro: HomeIntroClock?, at: Long, style: TextStyle, modifier: Modifier = Modifier) {
    val n by remember(text, intro) { derivedStateOf { if (intro == null || intro.done) text.length else intro.typed(text.length, at) } }
    // The full line keeps its height from the start (no layout shift while typing).
    Text(if (n >= text.length) text else text.take(n), modifier = modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        minLines = 1)
}

// ---------------------------------------------------------------- Interests

@Composable
fun InterestsCardV2(topics: List<InterestsFeed.Topic>, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier,
                    onTopic: (InterestsFeed.Topic) -> Unit, onEmpty: () -> Unit) {
    HomeCard(HomeGrid.Card.INTERESTS, intro, index, modifier, onClick = if (topics.isEmpty()) onEmpty else null, label = "Pick interests") {
        FitTitle(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = HomeGridLook.ink)) { append("Interests") }
            withStyle(SpanStyle(color = HomeGridLook.sub)) { append(" \u00b7 Tracking") }
        }, Modifier.introType(intro, index, HomeSequence.INT_TITLE_AT, 20).then(header(HomeGrid.Card.INTERESTS)).semantics { heading() })
        Spacer(Modifier.height(5.dp))
        if (topics.isEmpty()) {
            Text("Tell me what you're into and the latest headlines show up here.", style = style(11.sp, color = HomeGridLook.sub), maxLines = 4)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            topics.forEachIndexed { i, t ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = t.url.isNotBlank(), role = Role.Button) { onTopic(t) }
                    .semantics(mergeDescendants = true) { }) {
                    // intro: the chips pop in one by one at the centre before the card's frame exists
                    TopicChip(t.label, t.icon, Modifier.graphicsLayer {
                        val a = if (intro == null || intro.done || index < 0) 1f else HomeSequence.chipPop(i, intro.cardLocal(index))
                        // large at the centre (the video's chips), shrinking into place as the card glides to its cell
                        val cs = HomeCardFrame.of(intro, index).contentScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        alpha = a; scaleX = (0.6f + 0.4f * a) * cs; scaleY = (0.6f + 0.4f * a) * cs
                    })
                    val headline = t.headline.ifBlank { "Fetching the latest\u2026" }
                    Text(headline, style = style(9.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp).introType(intro, index, HomeSequence.headlineAt(i), headline.length.coerceAtMost(28)))
                    if (t.meta.isNotBlank()) Text(t.meta, style = style(8.sp, color = HomeGridLook.faint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.introAppear(intro, index, HomeSequence.headlineAt(i) + HomeSequence.lineMs(headline.length.coerceAtMost(28))))
                }
            }
        }
    }
}

/**
 * 0.48.0: a one-line card title that never clips: it starts at [HomeGridLook.titleSp] and steps down (to
 * [HomeGridLook.titleMinSp]) until the whole line fits the cell, e.g. "Interests \u00b7 Tracking" in a 360 dp-wide grid.
 * It isn't drawn until it fits, so no clipped frame shows.
 */
@Composable
fun FitTitle(text: androidx.compose.ui.text.AnnotatedString, modifier: Modifier = Modifier) {
    var fontSp by remember(text) { mutableFloatStateOf(HomeGridLook.titleSp) }
    var fits by remember(text) { mutableStateOf(false) }
    Text(text, modifier = modifier.fillMaxWidth().drawWithContent { if (fits) drawContent() }, style = style(fontSp.sp), maxLines = 1, softWrap = false,
        overflow = TextOverflow.Clip, onTextLayout = { r ->
            if (r.hasVisualOverflow && fontSp > HomeGridLook.titleMinSp) {
                // one step straight to the size that fits (rounded down to 0.5 sp), else 0.5 sp smaller
                val need = r.multiParagraph.intrinsics.maxIntrinsicWidth.coerceAtLeast(1f)
                val guess = kotlin.math.floor(fontSp * (r.size.width / need) * 2f) / 2f
                fontSp = (if (guess < fontSp) guess else fontSp - 0.5f).coerceAtLeast(HomeGridLook.titleMinSp)
            } else fits = true
        })
}

@Composable
fun TopicChip(label: String, icon: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(HomeGridLook.chipFill).border(1.dp, HomeGridLook.chipEdge, CircleShape)
        .padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(topicIcon(icon), contentDescription = null, tint = HomeGridLook.ink, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = style(8.5.sp, FontWeight.SemiBold), maxLines = 1)
    }
}

// ---------------------------------------------------------------- Music

private fun clock(ms: Long): String { if (ms < 0) return "0:00"; val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun MusicCardV2(m: HomeMusicNow?, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onOpen: () -> Unit, onToggle: () -> Unit) {
    HomeCard(HomeGrid.Card.MUSIC, intro, index, modifier, onClick = onOpen, label = "Open Music") {
        Text(musicLabel(m), style = style(11.sp, color = HomeGridLook.sub), modifier = header(HomeGrid.Card.MUSIC).fillMaxWidth()
            .introAppear(intro, index, HomeSequence.MUSIC_ART_AT))
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MusicArt(Modifier.size(56.dp).introAppear(intro, index, HomeSequence.MUSIC_ART_AT))
            Spacer(Modifier.weight(1f))
            Box(Modifier.introAppear(intro, index, HomeSequence.MUSIC_PLAY_AT)) { PlayButton(m, 36.dp, onOpen, onToggle) }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.height(9.dp))
        val title = m?.title?.ifBlank { null } ?: "Nothing playing"
        Text(title, style = style(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.introType(intro, index, HomeSequence.MUSIC_TITLE_AT, title.length.coerceAtMost(18)))
        Spacer(Modifier.height(2.dp))
        Text(m?.artist?.ifBlank { null } ?: "Open Music", style = style(11.sp, color = HomeGridLook.sub), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.introAppear(intro, index, HomeSequence.MUSIC_ARTIST_AT))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().introAppear(intro, index, HomeSequence.MUSIC_BAR_AT)) { Column { MusicProgress(m) } }
    }
}

/**
 * RC2: Music when there are no tasks (To-do hidden): one full-width row, so the grid has no empty cell beside it.
 * Art, title / artist and play on one line, the progress under it.
 */
@Composable
fun MusicCardWide(m: HomeMusicNow?, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onOpen: () -> Unit, onToggle: () -> Unit) {
    HomeCard(HomeGrid.Card.MUSIC, intro, index, modifier, onClick = onOpen, label = "Open Music") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MusicArt(Modifier.size(44.dp).introAppear(intro, index, HomeSequence.MUSIC_ART_AT), corner = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(musicLabel(m), style = style(10.5.sp, color = HomeGridLook.sub), maxLines = 1, modifier = header(HomeGrid.Card.MUSIC)
                    .introAppear(intro, index, HomeSequence.MUSIC_ART_AT))
                val title = m?.title?.ifBlank { null } ?: "Nothing playing"
                Text(title, style = style(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.introType(intro, index, HomeSequence.MUSIC_TITLE_AT, title.length.coerceAtMost(18)))
                Text(m?.artist?.ifBlank { null } ?: "Open Music", style = style(11.sp, color = HomeGridLook.sub), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.introAppear(intro, index, HomeSequence.MUSIC_ARTIST_AT))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.introAppear(intro, index, HomeSequence.MUSIC_PLAY_AT)) { PlayButton(m, 36.dp, onOpen, onToggle) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().introAppear(intro, index, HomeSequence.MUSIC_BAR_AT)) { Column { MusicProgress(m, times = false) } }
    }
}

private fun musicLabel(m: HomeMusicNow?) = if (m == null) "Music" else if (m.playing) "Now playing" else "Paused"

/** The reference's album tile: orange with a blue diamond from the lower right and a white pair of notes. */
@Composable
internal fun MusicArt(modifier: Modifier, corner: Dp = 12.dp) {
    Canvas(modifier.clip(RoundedCornerShape(corner))) {
        drawRect(Color(0xFFFFA24E))
        val w = size.width; val h = size.height
        // the diamond: a square turned 45 degrees, its centre at the lower right
        val d = Path().apply { moveTo(w * 0.22f, h * 0.98f); lineTo(w * 1.02f, h * 0.18f); lineTo(w * 1.02f, h * 1.02f); lineTo(w * 0.22f, h * 1.02f); close() }
        drawPath(d, Brush.linearGradient(listOf(Color(0xFFEFA56B), Color(0xFF5A8FF2)), Offset(w * 0.35f, h * 0.35f), Offset(w, h)))
        // beamed pair of notes
        val ink = Color.White.copy(alpha = 0.92f)
        val st = w * 0.055f
        val x1 = w * 0.40f; val x2 = w * 0.68f; val top1 = h * 0.28f; val top2 = h * 0.22f; val by1 = h * 0.66f; val by2 = h * 0.60f
        drawLine(ink, Offset(x1, by1), Offset(x1, top1), st)
        drawLine(ink, Offset(x2, by2), Offset(x2, top2), st)
        val beam = Path().apply { moveTo(x1 - st / 2, top1); lineTo(x2 + st / 2, top2); lineTo(x2 + st / 2, top2 + h * 0.08f); lineTo(x1 - st / 2, top1 + h * 0.08f); close() }
        drawPath(beam, ink)
        drawOval(ink, Offset(x1 - w * 0.15f, by1 - h * 0.06f), Size(w * 0.16f, h * 0.12f))
        drawOval(ink, Offset(x2 - w * 0.15f, by2 - h * 0.06f), Size(w * 0.16f, h * 0.12f))
    }
}

@Composable
private fun PlayButton(m: HomeMusicNow?, d: Dp, onOpen: () -> Unit, onToggle: () -> Unit) {
    Box(Modifier.size(d).clip(CircleShape).background(Color(0xFFF2F4F7))
        .clickable(role = Role.Button, onClickLabel = if (m?.playing == true) "Pause" else "Play", onClick = if (m == null) onOpen else onToggle),
        contentAlignment = Alignment.Center) {
        Icon(if (m?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (m?.playing == true) "Pause" else "Play", tint = Color(0xFF15203A), modifier = Modifier.size(d * 0.6f))
    }
}

@Composable
internal fun MusicProgress(m: HomeMusicNow?, times: Boolean = true) {
    val frac = if (m != null && m.durMs > 0) (m.posMs.toFloat() / m.durMs).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().height(9.dp)) {
        val y = size.height / 2; val h = 2.5.dp.toPx()
        drawLine(Color(0xFF5D6C78), Offset(0f, y), Offset(size.width, y), strokeWidth = h, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color(0xFFEDEDF2), Offset(0f, y), Offset(size.width * frac, y), strokeWidth = h, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color(0xFFEDEDF2), 4.dp.toPx(), Offset(size.width * frac, y))
    }
    if (times) Row(Modifier.fillMaxWidth().padding(top = 1.dp)) {
        Text(clock(m?.posMs ?: 0L), style = style(8.5.sp, color = HomeGridLook.faint))
        Spacer(Modifier.weight(1f))
        Text(clock(m?.durMs ?: 0L), style = style(8.5.sp, color = HomeGridLook.faint))
    }
}

// ---------------------------------------------------------------- To-do

@Composable
fun TodoCardV2(tasks: List<TodoFeed.Task>, count: Int, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    HomeCard(HomeGrid.Card.TODO, intro, index, modifier, onClick = onOpen, label = "Open to-do") {
        Row(header(HomeGrid.Card.TODO).introType(intro, index, 120L, 8), verticalAlignment = Alignment.CenterVertically) {
            Text("To-do", style = style(12.5.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f).semantics { heading() })
            Text("$count", style = style(11.sp, color = HomeGridLook.sub))
        }
        val firstDone = remember(tasks) { tasks.indexOfFirst { it.done } }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            tasks.forEachIndexed { i, t ->
                // intro: each row types in, then the first done task ticks (the video's "Buy groceries")
                val ticked by remember(t, intro, index) { derivedStateOf {
                    if (intro == null || intro.done || index < 0) t.done else HomeSequence.todoTicked(i, t.done, firstDone, intro.cardLocal(index))
                } }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.introAppear(intro, index, HomeSequence.todoRowAt(i), 120L)
                    .semantics(mergeDescendants = true) { contentDescription = t.title + if (t.done) ", done" else "" }) {
                    Box(Modifier.size(19.dp).clip(RoundedCornerShape(5.dp))
                        .then(if (ticked) Modifier.background(HomeGridLook.accent) else Modifier.border(1.5.dp, Color(0xFFE6ECF4), RoundedCornerShape(5.dp))),
                        contentAlignment = Alignment.Center) {
                        if (ticked) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(t.title, style = style(11.5.sp, color = if (ticked) HomeGridLook.faint else HomeGridLook.ink)
                        .copy(textDecoration = if (ticked) TextDecoration.LineThrough else null), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.introType(intro, index, HomeSequence.todoRowAt(i), t.title.length.coerceAtMost(16)))
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
            Box(Modifier.size(37.dp).introAppear(intro, index, 260L).clip(CircleShape).background(HomeGridLook.bell), contentAlignment = Alignment.Center) {
                Icon(if (item?.kind == NextUp.Kind.CALENDAR) Icons.Rounded.Event else Icons.Rounded.NotificationsNone, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Next up", style = style(11.sp, color = HomeGridLook.sub), modifier = header(HomeGrid.Card.NEXT_UP)
                    .introAppear(intro, index, HomeSequence.NEXT_FILL_AT - 100L, 260L))
                Text(line, style = style(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.introType(intro, index, HomeSequence.NEXT_TEXT_AT, line.length.coerceAtMost(20)))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = HomeGridLook.sub, modifier = Modifier.size(24.dp)
                .introAppear(intro, index, HomeSequence.NEXT_FILL_AT))
        }
    }
}

// ---------------------------------------------------------------- Quick actions

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickActionsCardV2(actions: List<QuickActions.Action>, intro: HomeIntroClock?, index: Int, modifier: Modifier = Modifier, onAction: (QuickActions.Action) -> Unit) {
    HomeCard(HomeGrid.Card.QUICK, intro, index, modifier) {
        Text("Quick actions", style = style(12.sp, FontWeight.SemiBold), modifier = header(HomeGrid.Card.QUICK).semantics { heading() }
            .introType(intro, index, 120L, 13))
        Spacer(Modifier.height(5.dp))
        actions.forEachIndexed { row, a ->
            val words = QuickChipRow.words(a.rest)
            // intro: the chip, then the line's words appear left to right; the second chip flashes like "Catch up" in the video
            fun shown(j: Int): Float = if (intro == null || intro.done || index < 0) 1f
                else (HomeSequence.quickRow(row, intro.cardLocal(index)) * (words.size + 1) - j).coerceIn(0f, 1f)
            // 0.48.0: one chip row per action like the approved video: the verb chip, then the rest of the line beside it,
            // word by word, so a long line wraps back to the card's left edge (not a chip with a text block under it).
            FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = a.verb) { onAction(a) }
                // one node for TalkBack and tests: the words are separate Texts only for the wrap
                .clearAndSetSemantics { contentDescription = a.verb + " " + a.rest; text = androidx.compose.ui.text.AnnotatedString(a.rest)
                    role = Role.Button; onClick(label = a.verb) { onAction(a); true } }.padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(QuickChipRow.WORD_GAP.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.padding(end = (QuickChipRow.CHIP_GAP - QuickChipRow.WORD_GAP).dp).graphicsLayer { alpha = shown(0) }
                    .clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f))
                    .drawBehind {
                        if (row == 1 && intro != null && !intro.done && index >= 0) {
                            val f = HomeSequence.quickFlash(intro.cardLocal(index))
                            if (f > 0f) drawRect(HomeGridLook.accent, alpha = f)
                        }
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(a.verb, style = style(12.sp, FontWeight.SemiBold), maxLines = 1)
                }
                words.forEachIndexed { j, w ->
                    Text(w, style = style(11.5.sp, color = HomeGridLook.ink.copy(alpha = 0.92f)), maxLines = 1, softWrap = false,
                        modifier = Modifier.align(Alignment.CenterVertically).graphicsLayer { alpha = shown(j + 1) })
                }
            }
        }
    }
}

/** 0.48.0 Quick actions chip row: the line beside the chip is laid out word by word (pure, tested). */
object QuickChipRow {
    const val WORD_GAP = 3
    const val CHIP_GAP = 10
    fun words(rest: String): List<String> = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
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
    // the intro's own card list wins (it is fixed when the intro starts; a card that shows up later just appears settled)
    fun idx(c: HomeGrid.Card) = intro?.order?.indexOf(c) ?: order.indexOf(c)
    Column(modifier.fillMaxWidth().padding(horizontal = HomeGridLook.side), verticalArrangement = Arrangement.spacedBy(HomeGridLook.gap)) {
        HomeGrid.rows(s.hasTasks).forEach { row ->
            if (row.full) {
                when (row.cards.first()) {
                    HomeGrid.Card.NEXT_UP -> NextUpCardV2(s.next, s.now, s.h24, intro, idx(HomeGrid.Card.NEXT_UP), Modifier.fillMaxWidth().heightIn(min = 63.dp), onNextUp)
                    HomeGrid.Card.QUICK -> QuickActionsCardV2(s.actions, intro, idx(HomeGrid.Card.QUICK), Modifier.fillMaxWidth(), onAction)
                    HomeGrid.Card.MUSIC -> MusicCardWide(s.music, intro, idx(HomeGrid.Card.MUSIC), Modifier.fillMaxWidth().height(HomeGridLook.musicWide), onMusic, onMusicToggle)
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
