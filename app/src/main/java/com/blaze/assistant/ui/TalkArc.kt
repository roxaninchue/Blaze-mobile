package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Mood
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * v0.43.0: who the next message goes to, picked from the header orb instead of the "Talk to @blaze @halo @all" pills.
 * v0.45.28: Blaze or Halo only (Halo x Blaze joins in by itself when a message needs both), on a flat full-width arc.
 * Tap the orb: a lit semicircle opens above the composer (or above the big mic) with the glass orb (Blaze), the halo
 * (Halo) and the orb in its halo (All). Turn it by dragging around the arc or swiping sideways; the focused icon is
 * larger and lit. Tap an icon to pick it; tap outside to close. The header orb then shows the current pick.
 * Maths in TalkArcMath (unit tested).
 */

/** The icon for a target: "blaze" = the glass orb, "halo" = the gold halo, "all" = the orb inside its halo. */
fun DrawScope.drawTalkTarget(who: String, center: Offset, w: Float, t: Float = 0f, alpha: Float = 1f) {
    when (who) {
        "blaze" -> drawWaterOrb(center, w * 0.3f, t, alpha = alpha)
        "halo" -> drawHaloRing(center, w * 0.19f, t, alpha)
        else -> drawDuoEmblem(center, w, t, alpha)
    }
}

/** The header orb showing who you're talking to (on the icon's dark disc, like [BrandMark]). */
@Composable
fun TalkTargetMark(who: String, mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    if (who != "blaze" && who != "halo") { BrandMark(mood, size, modifier); return }
    Box(modifier.size(size).clip(CircleShape).background(Color(com.blaze.assistant.phone.Mascot.ICON_BG)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 1.05f)) { drawTalkTarget(who, Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension) }
    }
}

/**
 * The arc itself. v0.45.28: a flat half-ellipse as wide as the chat bar (its ends reach the bar's left and right edges),
 * much lower than the old semicircle, with two icons: Blaze and Halo. [alignEnd] is kept for callers; the arc is full
 * width now. [onPick] gets "blaze" / "halo"; the caller closes the arc.
 */
@Composable
fun TalkArc(current: String, alignEnd: Boolean = false, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(TalkArcMath.indexOf(current).toFloat()) }
    LaunchedEffect(current) { pos.snapTo(TalkArcMath.indexOf(current).toFloat()) }
    var focused by remember { mutableIntStateOf(TalkArcMath.indexOf(current)) }
    val density = LocalDensity.current
    val icon = 42.dp; val band = 26.dp; val base = 20.dp
    // Same side margins as the composer (12 dp), so the band's outer ends line up with the chat bar's edges.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        val arcW = maxWidth
        val rxDp = arcW / 2 - band / 2
        val ryDp = with(density) { TalkArcMath.flatRy(rxDp.toPx(), 40.dp.toPx()).toDp() }
        val arcH = ryDp + base + icon * 0.8f
        Box(Modifier.width(arcW).height(arcH)
            .semantics { contentDescription = "Choose who to talk to. Now: ${TalkArcMath.label(TalkArcMath.targetAt(focused))}" }
            .pointerInput(arcW) {
                val cx = arcW.toPx() / 2f; val cy = arcH.toPx() - base.toPx(); val rx = rxDp.toPx(); val ry = ryDp.toPx()
                var last = 0f
                detectDragGestures(
                    onDragStart = { o -> last = TalkArcMath.touchAngle(o.x - cx, o.y - cy, rx, ry) },
                    onDragEnd = { scope.launch { pos.animateTo(TalkArcMath.snap(pos.value).toFloat(), tween(180)) } },
                    onDragCancel = { scope.launch { pos.animateTo(TalkArcMath.snap(pos.value).toFloat(), tween(180)) } },
                ) { change, drag ->
                    val p = change.position
                    val now = TalkArcMath.touchAngle(p.x - cx, p.y - cy, rx, ry)
                    // Near the centre an angle means little: there a sideways swipe turns the arc instead.
                    val nx = (p.x - cx) / rx; val ny = (p.y - cy) / ry
                    val nearCentre = nx * nx + ny * ny < 0.45f * 0.45f
                    val next = if (nearCentre) TalkArcMath.swipe(pos.value, drag.x, rx) else TalkArcMath.rotate(pos.value, last, now)
                    last = now
                    change.consume()
                    scope.launch { pos.snapTo(next) }
                    val f = TalkArcMath.snap(next)
                    if (f != focused) { focused = f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            }) {
            val p = pos.value
            // The lit band: a soft half-ellipse from Blaze blue to Halo gold, brightest under the focused icon.
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f; val cy = size.height - base.toPx(); val rx = rxDp.toPx(); val ry = ryDp.toPx()
                val tl = Offset(cx - rx, cy - ry); val sz = Size(rx * 2, ry * 2)
                val brush = Brush.horizontalGradient(listOf(DuoColors.blazeBlue, DuoColors.blazeBlue, DuoColors.haloGold), startX = cx - rx, endX = cx + rx)
                drawArc(brush, 180f, 180f, false, tl, sz, alpha = 0.16f, style = Stroke(width = band.toPx(), cap = StrokeCap.Round))
                drawArc(brush, 180f, 180f, false, tl, sz, alpha = 0.55f, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                val (fx, fy) = TalkArcMath.offset(TalkArcMath.snap(p), p, rx, ry)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset(cx + fx, cy + fy), radius = 40.dp.toPx()),
                    radius = 40.dp.toPx(), center = Offset(cx + fx, cy + fy))
            }
            // The icons: each is its own button for TalkBack and taps; the drag above turns them all.
            TalkArcMath.TARGETS.forEachIndexed { i, who ->
                val (ox, oy) = with(density) { TalkArcMath.offset(i, p, rxDp.toPx(), ryDp.toPx()) }
                val s = TalkArcMath.scale(i, p); val lit = TalkArcMath.focus(i, p)
                val half = with(density) { (icon / 2).toPx() }
                val cx = with(density) { (arcW / 2).toPx() }; val cy = with(density) { (arcH - base).toPx() }
                Box(Modifier.offset { IntOffset((cx + ox - half).roundToInt(), (cy + oy - half).roundToInt()) }.size(icon)
                    .clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); onPick(who)
                    }
                    .semantics { contentDescription = "Talk to ${TalkArcMath.label(who)}"; selected = who == TalkArcMath.normalize(current) },
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val ctr = Offset(size.width / 2f, size.height / 2f)
                        if (lit > 0.05f) drawCircle(Color.White.copy(alpha = 0.10f * lit), radius = size.minDimension / 2f, center = ctr)
                        drawTalkTarget(who, ctr, size.minDimension * 0.62f * s, alpha = 0.55f + 0.45f * lit)
                    }
                }
            }
            // A small label under the top of the arc says who is focused.
            Text(TalkArcMath.label(TalkArcMath.targetAt(TalkArcMath.snap(p))),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
        }
    }
}
