package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.orch.StatusLine

/**
 * 0.47.0 the work pill (spec §3.2, reference shots 1000169526/1000169527): ONE rounded glass pill under the latest
 * message with a single live line of what Blaze is doing. A long line ticker-scrolls (marquee); with animations off it is
 * cut with "…". Tap opens the list of parts (each part's line and state); tap again closes it. It goes away the moment the
 * answer lands (the caller passes null). No "thinking" words, no internal names. TAG for tests.
 */
object WorkPillView { const val TAG = "work_pill" }

@Composable
fun WorkPill(line: String?, steps: List<StatusLine.Step>, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    var open by remember { mutableStateOf(false) }
    AnimatedVisibility(line != null, modifier = modifier, enter = MotionTokens.fade(), exit = ExitTransition.None) {
        val shown = line.orEmpty()
        Column(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 2.dp)) {
            Row(Modifier.widthIn(max = 320.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(24.dp))
                .background(BubblePalette.capsule(c).copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                .border(1.dp, BubblePalette.edge(c), RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide steps" else "Show steps") { open = !open }
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = shown }
                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shown, maxLines = 1, softWrap = false,
                    overflow = if (MotionTokens.enabled) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (MotionTokens.enabled) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900, velocity = 38.dp) else Modifier,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 19.sp, color = BubblePalette.capsuleInk(c)))
            }
            if (open && steps.isNotEmpty()) Column(Modifier.padding(start = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot = when (s.state) { StatusLine.State.RUNNING -> c.accent; StatusLine.State.DONE -> BubblePalette.capsuleInk(c)
                            StatusLine.State.FAILED -> c.ink; StatusLine.State.STOPPED -> BubblePalette.edge(c) }
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(8.dp))
                        Text(s.line + when (s.state) { StatusLine.State.DONE -> " \u00b7 done"; StatusLine.State.FAILED -> " \u00b7 didn't work"; StatusLine.State.STOPPED -> " \u00b7 stopped"; else -> "" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 16.sp, color = BubblePalette.capsuleInk(c)))
                    }
                }
            }
        }
    }
}

/** The bubble's send state: a small spinning ring while sending, a single tick once delivered. No words. */
@Composable
fun SendTick(sending: Boolean, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val ink = BubblePalette.capsuleInk(c)
    Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (sending) {
            val tr = rememberInfiniteTransition(label = "sendRing")
            val turn by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "sendRingTurn")
            androidx.compose.foundation.Canvas(Modifier.size(11.dp)) {
                val sw = 1.6.dp.toPx()
                drawArc(ink.copy(alpha = 0.25f), 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawArc(ink, if (MotionTokens.enabled) turn else 0f, 100f, false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        } else {
            androidx.compose.foundation.Canvas(Modifier.size(11.dp).alpha(0.9f)) {
                val sw = 1.7.dp.toPx(); val w = size.width; val h = size.height
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.12f, h * 0.55f); lineTo(w * 0.40f, h * 0.82f); lineTo(w * 0.90f, h * 0.20f) }
                drawPath(p, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

/**
 * 0.47.0 the one-time Butler card (orch/ButlerIntro): shown only to people whose personality was already set, so it was
 * kept. "Try it" switches to Butler; "No thanks" keeps theirs. Either answer and it never comes back.
 */
@Composable
fun ButlerIntroCard(onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
        .background(BubblePalette.capsule(c).copy(alpha = 0.55f), RoundedCornerShape(20.dp))
        .border(1.dp, BubblePalette.edge(c), RoundedCornerShape(20.dp))
        .semantics(mergeDescendants = false) { contentDescription = com.blaze.assistant.agent.orch.ButlerIntro.TEXT }
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(com.blaze.assistant.agent.orch.ButlerIntro.TEXT,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 19.sp, color = c.ink))
        Spacer(Modifier.heightIn(min = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntroButton(com.blaze.assistant.agent.orch.ButlerIntro.TRY, primary = true) { onAnswer(true) }
            IntroButton(com.blaze.assistant.agent.orch.ButlerIntro.NO, primary = false) { onAnswer(false) }
        }
    }
}

/** Theme-aware pill button (ink on paper, both themes): the dialog PillButton is dark-only and vanished on light. */
@Composable
private fun IntroButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val shape = RoundedCornerShape(50)
    Box(Modifier.heightIn(min = 40.dp).clip(shape)
        .background(if (primary) c.ink else c.paper.copy(alpha = 0f), shape)
        .border(1.dp, if (primary) c.ink else BubblePalette.edge(c), shape)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 18.sp, color = if (primary) c.paper else c.ink))
    }
}
