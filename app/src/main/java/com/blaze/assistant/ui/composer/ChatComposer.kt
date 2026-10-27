package com.blaze.assistant.ui.composer

import com.blaze.assistant.composer.ComposerLook
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.combinedClickable
import com.blaze.assistant.composer.ComposerMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import com.blaze.assistant.composer.ChatOverlayLogic
import com.blaze.assistant.composer.ModelChoice
import com.blaze.assistant.ui.DuoColors
import com.blaze.assistant.ui.GlassIntensity
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.glass
import com.blaze.assistant.ui.drawWaterOrb
import kotlinx.coroutines.delay

/** 0.47 chat composer: sizes the Home layout reserves under its cards for the bar. */
object ChatBarTokens {
    const val HEIGHT_DP = 64
    const val AVATAR_DP = 40
    /** RC2: the Blaze orb inside the pill. */
    const val ORB_DP = 42
}

// ---------------------------------------------------------------- avatars

/** The user's avatar: their initial on glass. */
@Composable
fun MeAvatar(size: Int = ChatBarTokens.AVATAR_DP, onClick: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    val letter = remember { ChatOverlayLogic.initial(runCatching { com.blaze.assistant.agent.Settings.userName }.getOrNull()) }
    Box(Modifier.size(size.dp).clip(CircleShape).glass(CircleShape, GlassIntensity.High, shadow = false)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = "Your profile" }, contentAlignment = Alignment.Center) {
        Text(letter, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = (size * 0.4f).sp, color = c.ink))
    }
}

/** Blaze's avatar: the Blaze logo (the flame mascot) on Blaze blue. */
@Composable
fun BlazeAvatar(size: Int = 30) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(DuoColors.blazeBlue, DuoColors.blazeBlue.copy(alpha = 0.75f))))
        .semantics { contentDescription = "Blaze" }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_stat_blaze), null, tint = Color.White, modifier = Modifier.size((size * 0.62f).dp))
    }
}

// ---------------------------------------------------------------- the chat bar

/**
 * The chat bar (RC2, the owner's sketch): profile avatar LEFT, the "Message Blaze" pill with the glowing Blaze orb
 * sitting inside it, "+" RIGHT. Tapping the pill types. The orb keeps the old Blaze button's job (tap: chat over Home;
 * hold: talk). While there's text the orb gives way to the send arrow inside the pill; "+" always opens Spotlight.
 * The model chip (and attachment chips) slide up above the pill on the first typed character or attachment.
 * Calm pacing ([ComposerMotion.BAR]); with Remove animations nothing moves.
 */
@Composable
fun BlazeChatBar(state: ComposerState, modifier: Modifier = Modifier, onProfile: () -> Unit = {}) {
    val c = LocalBlaze.current
    val family by ModelChoice.current.collectAsState()
    val calm = MotionTokens.enabled
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(state.chipShown,
            enter = if (calm) slideInVertically(tween(ComposerMotion.BAR, easing = MotionTokens.Decelerate)) { it } + fadeIn(tween(ComposerMotion.BAR)) else EnterTransition.None,
            exit = if (calm) slideOutVertically(tween(ComposerMotion.EXIT, easing = MotionTokens.Accelerate)) { it } + fadeOut(tween(ComposerMotion.EXIT)) else ExitTransition.None) {
            Column(Modifier.padding(start = (ChatBarTokens.AVATAR_DP + 8).dp, bottom = 6.dp)) {
                if (state.pickerOpen) ModelPicker(family) { ModelChoice.set(it); state.pickerOpen = false }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallChip(ModelChoice.chip(family), trailing = true, desc = "Model: ${family.label}. Tap to change") { state.pickerOpen = !state.pickerOpen }
                    state.attachments.forEach { a ->
                        SmallChip(a.name.take(22), desc = "Attachment ${a.name}. Tap to remove", close = true) { state.attachments.remove(a) }
                    }
                    if (state.attaching) SmallChip("Adding\u2026", desc = "Adding a file") { }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            MeAvatar(onClick = onProfile)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(50)).glass(RoundedCornerShape(50), GlassIntensity.High, shadow = false)
                .padding(start = 18.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (state.text.isEmpty()) Text("Message Blaze", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.sub))
                    BasicTextField(state.text, { state.text = it },
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.ink), cursorBrush = SolidColor(DuoColors.blazeBlue),
                        maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (state.canSend) state.send() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message Blaze" })
                }
                Spacer(Modifier.width(6.dp))
                // The Blaze orb sits inside the pill; while there's text the send arrow takes its place.
                Box(Modifier.size(ChatBarTokens.ORB_DP.dp), contentAlignment = Alignment.Center) {
                    AnimatedVisibility(!state.canSend,
                        enter = if (calm) fadeIn(tween(ComposerMotion.BAR)) + scaleIn(tween(ComposerMotion.BAR, easing = MotionTokens.Decelerate), initialScale = 0.7f) else EnterTransition.None,
                        exit = if (calm) fadeOut(tween(ComposerMotion.EXIT)) + scaleOut(tween(ComposerMotion.EXIT, easing = MotionTokens.Accelerate), targetScale = 0.7f) else ExitTransition.None) {
                        BlazeOrbButton(state)
                    }
                    // The send arrow lives INSIDE the pill and only while there's text.
                    AnimatedVisibility(state.canSend,
                        enter = if (calm) fadeIn(tween(ComposerMotion.BAR)) + scaleIn(tween(ComposerMotion.BAR, easing = MotionTokens.Decelerate), initialScale = 0.7f) else EnterTransition.None,
                        exit = if (calm) fadeOut(tween(ComposerMotion.EXIT)) else ExitTransition.None) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(DuoColors.blazeBlue).clickable { state.send() }
                            .semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // "+" always opens Spotlight.
            Box(Modifier.size(44.dp).clip(CircleShape).glass(CircleShape, GlassIntensity.High, shadow = false)
                .clickable { if (state.spotlightOpen) state.closeSpotlight() else state.openSpotlight() }
                .semantics { contentDescription = if (state.spotlightOpen) "Close Spotlight" else "Open Spotlight" }, contentAlignment = Alignment.Center) {
                val turn = remember { Animatable(0f) }
                LaunchedEffect(state.spotlightOpen) { if (MotionTokens.enabled) turn.animateTo(if (state.spotlightOpen) 45f else 0f, tween(ComposerMotion.BAR, easing = MotionTokens.Standard)) else turn.snapTo(if (state.spotlightOpen) 45f else 0f) }
                Icon(Icons.Rounded.Add, null, tint = c.ink, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn.value })
            }
        }
    }
}

/**
 * The glowing Blaze orb (the old Home's Blaze button): a dark disc, an amber ring with a soft glow, and the glossy
 * Blaze drop in the middle. Tap: the chat over Home. Hold: talk to Blaze (voice), like the old orb.
 * A still picture while Blaze is idle (no frames); while Blaze thinks or listens the glow breathes slowly
 * ([ComposerMotion.ORB_BREATHE]); with Remove animations it stays still.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BlazeOrbButton(state: ComposerState, size: Int = ChatBarTokens.ORB_DP,
                   onTap: () -> Unit = { state.openChat() },
                   onHold: () -> Unit = { com.blaze.assistant.voice.Voice.listenRequests.value = com.blaze.assistant.voice.Voice.listenRequests.value + 1 }) {
    val mood by Agent.mood.collectAsState()
    val voice by com.blaze.assistant.voice.Voice.state.collectAsState()
    val active = mood != com.blaze.assistant.agent.Mood.IDLE || voice != com.blaze.assistant.voice.Voice.State.IDLE
    val glow = if (active && MotionTokens.enabled) {
        val t = rememberInfiniteTransition(label = "orb")
        val g by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(ComposerMotion.ORB_BREATHE, easing = MotionTokens.Standard), RepeatMode.Reverse), label = "glow")
        g
    } else if (active) 1f else 0.8f
    Box(Modifier.size(size.dp).clip(CircleShape)
        .combinedClickable(onClick = onTap, onLongClick = onHold, onClickLabel = "Chat with Blaze", onLongClickLabel = "Talk to Blaze")
        .semantics { contentDescription = "Blaze. Tap to chat, hold to talk" }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawBlazeOrb(glow) }
    }
}

/** Draws the orb in this DrawScope's bounds ([glow] 0..1). Pure drawing, shared with the shots. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlazeOrb(glow: Float) {
    val r = size.minDimension / 2f
    val cen = center
    val amber = Color(0xFFFFB23F)
    // soft amber glow inside the disc's edge
    drawCircle(Brush.radialGradient(listOf(amber.copy(alpha = 0f), amber.copy(alpha = 0.45f * glow), amber.copy(alpha = 0f)), center = cen, radius = r),
        radius = r, center = cen)
    // the dark disc
    drawCircle(Color(0xFF0C0D10), radius = r * 0.86f, center = cen)
    // the amber ring with a halo
    drawCircle(amber.copy(alpha = 0.30f * glow), radius = r * 0.70f, center = cen, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.24f))
    drawCircle(Color(0xFFFFC961).copy(alpha = 0.55f + 0.45f * glow), radius = r * 0.70f, center = cen, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.085f))
    // the glossy dark marble with the Blaze drop's light on it
    drawCircle(Brush.radialGradient(listOf(Color(0xFF6A6E78), Color(0xFF26282E), Color(0xFF111215)),
        center = androidx.compose.ui.geometry.Offset(cen.x - r * 0.14f, cen.y - r * 0.18f), radius = r * 0.62f), radius = r * 0.46f, center = cen)
    drawWaterOrb(cen, r * 0.40f, t = 0f, alpha = 0.45f)
}

@Composable
private fun SmallChip(label: String, desc: String, trailing: Boolean = false, close: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.clip(RoundedCornerShape(50)).glass(RoundedCornerShape(50), GlassIntensity.High, shadow = false).clickable(onClick = onClick)
        .semantics { contentDescription = desc }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
        if (trailing) Icon(Icons.Rounded.ExpandMore, null, tint = c.sub, modifier = Modifier.padding(start = 2.dp).size(16.dp))
        if (close) Icon(Icons.Rounded.Close, null, tint = c.sub, modifier = Modifier.padding(start = 4.dp).size(14.dp))
    }
}

/** Auto / Spark (Fast) / Flare (Balanced) / Inferno (Smartest). */
@Composable
fun ModelPicker(current: ModelChoice.Family, onPick: (ModelChoice.Family) -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.padding(bottom = 6.dp).widthIn(min = 200.dp).clip(RoundedCornerShape(18.dp)).glass(RoundedCornerShape(18.dp), GlassIntensity.High, shadow = false)
        .padding(vertical = 6.dp)) {
        ModelChoice.Family.values().forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { onPick(f) }.semantics { contentDescription = "Model ${f.pickerLabel}" }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.pickerLabel, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontWeight = if (f == current) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp, color = c.ink))
                if (f == current) Icon(Icons.Rounded.Check, null, tint = DuoColors.blazeBlue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- chat over Home

/**
 * The chat over the blurred widgets: user bubbles RIGHT with the user's avatar, Blaze LEFT with the Blaze logo
 * (typing dots, then the text types in), a centred timestamp, and a × that unblurs Home.
 */
@Composable
fun ChatOverHome(state: ComposerState, modifier: Modifier = Modifier, onOpenFull: () -> Unit = {}) {
    val all by Agent.messages.collectAsState()
    val busyChats by Agent.busyChats.collectAsState()
    val chatId by Agent.chatId.collectAsState()
    ChatOverHomeContent(state, all, chatId in busyChats, modifier, onOpenFull)
}

/** [ChatOverHome] on plain data (screenshots). */
@Composable
fun ChatOverHomeContent(state: ComposerState, all: List<ChatMessage>, busy: Boolean, modifier: Modifier = Modifier, onOpenFull: () -> Unit = {},
                        now: Long = System.currentTimeMillis()) {
    val c = LocalBlaze.current
    val shown = remember(all, state.sinceId) { ChatOverlayLogic.visible(all, state.sinceId) }
    val typing = ChatOverlayLogic.typing(shown, busy)
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, typing, shown.lastOrNull()?.text?.length) { val n = shown.size + (if (typing) 1 else 0) + 1; if (n > 0) list.scrollToItem(n - 1) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Rounded.OpenInFull, "Open the full chat", onOpenFull)
            Spacer(Modifier.width(8.dp))
            RoundButton(Icons.Rounded.Close, "Close chat", state::closeChat)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            item(key = "stamp") {
                val at = shown.firstOrNull()?.at ?: now
                Text(ChatOverlayLogic.stamp(at, now), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            items(shown, key = { it.id }) { m ->
                if (m.role == Role.USER) UserBubble(m, lift = m.id > state.liveFrom) else BlazeBubble(m, typeIn = m.id > state.liveFrom)
            }
            if (typing) item(key = "typing") { Row(verticalAlignment = Alignment.Bottom) { BlazeAvatar(); Spacer(Modifier.width(8.dp)); TypingDots() } }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(36.dp).clip(CircleShape).glass(CircleShape, GlassIntensity.High, shadow = false).clickable(onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(18.dp))
    }
}

/** The user's line: lifts out of the pill (rises from the bar) into a bubble on the RIGHT with their avatar on its right. */
@Composable
private fun UserBubble(m: ChatMessage, lift: Boolean) {
    val c = LocalBlaze.current
    val rise = remember(m.id) { Animatable(if (lift && MotionTokens.enabled) 1f else 0f) }
    LaunchedEffect(m.id) { if (rise.value > 0f) rise.animateTo(0f, tween(ComposerMotion.LIFT, easing = MotionTokens.Decelerate)) }
    Row(Modifier.fillMaxWidth().graphicsLayer { translationY = rise.value * 260f; alpha = 1f - rise.value * 0.4f; scaleX = 1f - rise.value * 0.06f; scaleY = scaleX },
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
        val text = com.blaze.assistant.ui.AttachView.bubbleText(m.text, m.media)
        Column(Modifier.weight(1f, fill = false).widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
            .background(DuoColors.blazeBlue.copy(alpha = if (LocalComposerSolid.current) 1f else if (c.dark) 0.85f else 0.95f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (m.files.isNotEmpty()) Text(m.files.joinToString(" \u00b7 "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f)))
            if (text.isNotBlank()) Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Color.White))
        }
        Spacer(Modifier.width(8.dp))
        MeAvatar(size = 30)
    }
}

/** Blaze's line on the LEFT with the Blaze logo; a fresh reply types in (reduce motion: shown whole). */
@Composable
private fun BlazeBubble(m: ChatMessage, typeIn: Boolean) {
    val c = LocalBlaze.current
    var shown by remember(m.id) { mutableIntStateOf(if (typeIn && MotionTokens.enabled) 0 else Int.MAX_VALUE) }
    LaunchedEffect(m.id, m.text.length) {
        while (shown < m.text.length) { delay(16); shown = minOf(m.text.length, shown + ChatOverlayLogic.step(m.text.length - shown)) }
    }
    val err = m.role == Role.ERROR
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        BlazeAvatar()
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f, fill = false).widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
            .background(ComposerLook.blazeBubble(c))   // RC1 readability: always opaque (over the blur too)
            .then(if (err) Modifier.border(1.dp, Color(0xFFE5484D).copy(alpha = 0.6f), RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(if (shown >= m.text.length) m.text else m.text.take(shown), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
        }
    }
}

/** Three bouncing dots (static with reduce motion). */
@Composable
fun TypingDots() {
    val c = LocalBlaze.current
    Row(Modifier.clip(RoundedCornerShape(18.dp))
        .background(ComposerLook.blazeBubble(c))
        .semantics { contentDescription = "Blaze is typing" }.padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!MotionTokens.enabled) repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(c.sub)) }
        else {
            val t = rememberInfiniteTransition(label = "dots")
            repeat(3) { i ->
                val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, delayMillis = i * 150), RepeatMode.Restart), label = "d$i")
                val lift = if (y < 0.35f) y / 0.35f else if (y < 0.7f) (0.7f - y) / 0.35f else 0f
                Box(Modifier.size(7.dp).graphicsLayer { translationY = -lift * 10f }.clip(CircleShape).background(c.sub))
            }
        }
    }
}
