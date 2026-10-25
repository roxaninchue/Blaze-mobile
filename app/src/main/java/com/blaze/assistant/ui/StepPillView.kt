package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 0.45.20 step pill (user spec C): one subtle rounded pill under the pending reply with the newest step; a new step
 * replaces the old one (a quick cross-fade). Plain text only (agent/StepPill.clean already checked it). Theme-aware: the
 * same AA capsule as the day chips. TalkBack reads each new step in a polite live region.
 */
object StepPillView { const val TAG = "step_pill" }

@Composable
fun StepPillRow(text: String?, specialist: Boolean = false, modifier: Modifier = Modifier, inline: Boolean = false) {
    val c = LocalBlaze.current
    // 0.45.24: beside the drop ([inline]); it leaves at once (no fade over the reply's first words). Remove animations: no fades at all.
    AnimatedVisibility(text != null, modifier = modifier, enter = MotionTokens.fade(), exit = if (inline) ExitTransition.None else MotionTokens.fadeAway()) {
        val shown = text.orEmpty()
        Row(if (inline) Modifier.padding(start = 6.dp) else Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 2.dp)) {
            Row(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(BubblePalette.capsule(c), RoundedCornerShape(14.dp))
                .border(1.dp, BubblePalette.edge(c), RoundedCornerShape(14.dp))
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = shown }
                .padding(horizontal = 11.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (specialist) 6.dp else 5.dp).clip(CircleShape).background(if (specialist) c.accent else BubblePalette.capsuleInk(c)))
                Spacer(Modifier.width(7.dp))
                AnimatedContent(shown, transitionSpec = { if (MotionTokens.enabled) fadeIn() togetherWith fadeOut() else EnterTransition.None togetherWith ExitTransition.None }, label = "stepPill") { t ->
                    Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 15.sp, color = BubblePalette.capsuleInk(c)))
                }
            }
        }
    }
}
