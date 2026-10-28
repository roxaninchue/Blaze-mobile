package com.blaze.assistant.ui.composer

import com.blaze.assistant.composer.ComposerLook
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.delay

/** 0.47 chat composer: sizes the Home layout reserves under its cards for the bar. */
object ChatBarTokens {
    const val HEIGHT_DP = 64
    const val AVATAR_DP = 40
}

// ---------------------------------------------------------------- avatars

/**
 * The user's avatar (the chat bar, their bubbles): the signed-in Blaze account's photo, else the initial of its name /
 * email (the same person as the profile button); a neutral person icon when signed out. 0.48.0 RC4: never a static "Y".
 */
@Composable
fun MeAvatar(size: Int = ChatBarTokens.AVATAR_DP, onClick: (() -> Unit)? = null) {
    val acct by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    val u = acct.user
    val av = remember(u) { ChatOverlayLogic.avatar(u != null, u?.name, u?.email, u?.avatarUrl) }
    MeAvatarView(av, size, onClick)
}

/** [MeAvatar] on plain data (screenshots). */
@Composable
fun MeAvatarView(av: ChatOverlayLogic.Avatar, size: Int = ChatBarTokens.AVATAR_DP, onClick: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    val photo = (av as? ChatOverlayLogic.Avatar.Photo)?.let { com.blaze.assistant.ui.rememberHomeImage(it.url) }
    val letter = when (av) { is ChatOverlayLogic.Avatar.Photo -> av.letter; is ChatOverlayLogic.Avatar.Letter -> av.letter; else -> null }
    Box(Modifier.size(size.dp).clip(CircleShape).background(ComposerLook.solidChip(c))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = if (av is ChatOverlayLogic.Avatar.Neutral) "Profile: signed out" else "Your profile" }, contentAlignment = Alignment.Center) {
        when {
            photo != null -> androidx.compose.foundation.Image(photo, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            letter != null -> Text(letter, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = (size * 0.4f).sp, color = c.ink))
            else -> Icon(Icons.Rounded.Person, null, tint = c.sub, modifier = Modifier.size((size * 0.55f).dp))
        }
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
 * The chat bar that replaces the bottom Blaze button: profile avatar LEFT, the "Message Blaze" pill, "+" RIGHT.
 * A send arrow shows inside the pill while there's text; "+" always opens Spotlight. The model chip (and attachment
 * chips) slide up above the pill on the first typed character or attachment.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BlazeChatBar(state: ComposerState, modifier: Modifier = Modifier, onProfile: () -> Unit = {}) {
    val c = LocalBlaze.current
    val family by ModelChoice.current.collectAsState()
    // RC4: with the keyboard up the bar sits on its own ground, so the cards it floats over never read through it.
    val ime = WindowInsets.isImeVisible
    Column(modifier.fillMaxWidth()
        .then(if (ime) Modifier.background(Brush.verticalGradient(listOf(ComposerLook.dock(c).copy(alpha = 0f), ComposerLook.dock(c).copy(alpha = 0.94f), ComposerLook.dock(c)))) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(state.chipShown,
            enter = if (MotionTokens.enabled) slideInVertically(tween(MotionTokens.MEDIUM)) { it } + fadeIn(tween(MotionTokens.MEDIUM)) else fadeIn(tween(0)),
            exit = if (MotionTokens.enabled) slideOutVertically(tween(MotionTokens.SHORT)) { it } + fadeOut(tween(MotionTokens.SHORT)) else fadeOut(tween(0))) {
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
            Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(50)).background(ComposerLook.pill(c))
                .border(1.dp, c.ink.copy(alpha = 0.10f), RoundedCornerShape(50))
                .padding(start = 18.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (state.text.isEmpty()) Text("Message Blaze", style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.sub))
                    BasicTextField(state.text, { state.text = it },
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.ink), cursorBrush = SolidColor(DuoColors.blazeBlue),
                        maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (state.canSend) state.send() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(state.barFocus).semantics { contentDescription = "Message Blaze" })
                }
                // The send arrow lives INSIDE the pill and only while there's text.
                AnimatedVisibility(state.canSend, enter = fadeIn(tween(MotionTokens.ms(MotionTokens.SHORT).toInt())), exit = fadeOut(tween(MotionTokens.ms(MotionTokens.SHORT).toInt()))) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(DuoColors.blazeBlue).clickable { state.send() }
                        .semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // "+" always opens Spotlight (a floating panel of its own; the bar steps aside while it is up).
            Box(Modifier.size(44.dp).clip(CircleShape).background(ComposerLook.solidChip(c))
                .clickable { if (state.spotlightOpen) state.closeSpotlight() else state.openSpotlight() }
                .semantics { contentDescription = if (state.spotlightOpen) "Close Spotlight" else "Open Spotlight" }, contentAlignment = Alignment.Center) {
                val turn = remember { Animatable(0f) }
                LaunchedEffect(state.spotlightOpen) { if (MotionTokens.enabled) turn.animateTo(if (state.spotlightOpen) 45f else 0f, tween(MotionTokens.MEDIUM)) else turn.snapTo(if (state.spotlightOpen) 45f else 0f) }
                Icon(Icons.Rounded.Add, null, tint = c.ink, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn.value })
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, desc: String, trailing: Boolean = false, close: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(ComposerLook.solidChip(c)).border(1.dp, c.ink.copy(alpha = 0.10f), RoundedCornerShape(50))
        .clickable(onClick = onClick).semantics { contentDescription = desc }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
        if (trailing) Icon(Icons.Rounded.ExpandMore, null, tint = c.sub, modifier = Modifier.padding(start = 2.dp).size(16.dp))
        if (close) Icon(Icons.Rounded.Close, null, tint = c.sub, modifier = Modifier.padding(start = 4.dp).size(14.dp))
    }
}

/** Auto / Spark (Fast) / Flare (Balanced) / Inferno (Smartest). */
@Composable
fun ModelPicker(current: ModelChoice.Family, onPick: (ModelChoice.Family) -> Unit) {
    val c = LocalBlaze.current
    // RC4: opaque (the glass menu let the cards behind it read through) and lifted above them.
    Column(Modifier.padding(bottom = 6.dp).widthIn(min = 200.dp).zIndex(10f)
        .shadow(12.dp, RoundedCornerShape(18.dp), clip = false)
        .clip(RoundedCornerShape(18.dp)).background(ComposerLook.menu(c)).border(1.dp, c.ink.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
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
fun ChatOverHome(state: ComposerState, modifier: Modifier = Modifier, bottom: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {}) {
    val all by Agent.messages.collectAsState()
    val busyChats by Agent.busyChats.collectAsState()
    val chatId by Agent.chatId.collectAsState()
    ChatOverHomeContent(state, all, chatId in busyChats, modifier, bottom = bottom)
}

/**
 * [ChatOverHome] on plain data (screenshots). 0.48.0 RC4: the only chat surface (the old full chat screen is gone):
 * a title for project / private chats, "Show earlier messages" for the whole thread, Blaze's replies with their rich
 * formatting (lists, tables, links, pictures), and [bottom] for what Blaze is waiting on (approvals, questions, secure
 * forms, the listening strip), above the bar.
 */
@Composable
fun ChatOverHomeContent(state: ComposerState, all: List<ChatMessage>, busy: Boolean, modifier: Modifier = Modifier,
                        now: Long = System.currentTimeMillis(), bottom: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {}) {
    val c = LocalBlaze.current
    val shown = remember(all, state.sinceId) { ChatOverlayLogic.visible(all, state.sinceId) }
    val more = remember(all, state.sinceId) { state.sinceId > 0L && ChatOverlayLogic.visible(all, 0L).size > shown.size }
    val earlier = more
    val typing = ChatOverlayLogic.typing(shown, busy)
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, typing, shown.lastOrNull()?.text?.length) { val n = shown.size + (if (typing) 1 else 0) + 2; if (n > 0) list.scrollToItem(n - 1) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = if (state.privateChat) "Private chat" else state.title
            if (title.isNotBlank()) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
            else Spacer(Modifier.weight(1f))
            if (earlier) { RoundButton(Icons.Rounded.History, "Show earlier messages", state::showEarlier); Spacer(Modifier.width(8.dp)) }
            RoundButton(Icons.Rounded.Close, if (state.privateChat) "Leave private chat" else "Close chat", state::closeChat)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            if (earlier) item(key = "earlier") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Show earlier messages", modifier = Modifier.clip(RoundedCornerShape(50)).background(ComposerLook.solidChip(c))
                        .clickable { state.showEarlier() }.padding(horizontal = 14.dp, vertical = 7.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
                }
            }
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
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { bottom() }
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
    LaunchedEffect(m.id) { if (rise.value > 0f) rise.animateTo(0f, tween(MotionTokens.LONG, easing = MotionTokens.Decelerate)) }
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
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        BlazeAvatar()
        Spacer(Modifier.width(8.dp))
        val solid = LocalComposerSolid.current
        Box(Modifier.weight(1f, fill = false).widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
            .then(if (solid) Modifier.background(ComposerLook.blazeBubble(c)) else Modifier.glass(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), GlassIntensity.High, shadow = false))
            .then(if (err) Modifier.border(1.dp, Color(0xFFE5484D).copy(alpha = 0.6f), RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (shown >= m.text.length && !err) Column {
                // RC4: the whole reply with its formatting once it has typed in (lists, tables, code, links), like the old chat.
                val body = remember(m.text) { runCatching { com.blaze.assistant.agent.ToolLeak.bubbleText(m.text) }.getOrDefault(m.text) }
                com.blaze.assistant.ui.AnswerBody(m.id, body)
            }
            else Text(if (shown >= m.text.length) m.text else m.text.take(shown), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
        }
    }
    // Pictures, music and files Blaze made for this reply (tap to open), as in the old chat.
    if (m.media.isNotEmpty() && shown >= m.text.length) Box(Modifier.padding(start = 38.dp, top = 6.dp)) { com.blaze.assistant.ui.MediaStrip(m.media) }
    }
}

/** Three bouncing dots (static with reduce motion). */
@Composable
fun TypingDots() {
    val c = LocalBlaze.current
    val solid = LocalComposerSolid.current
    Row(Modifier.clip(RoundedCornerShape(18.dp))
        .then(if (solid) Modifier.background(ComposerLook.blazeBubble(c)) else Modifier.glass(RoundedCornerShape(18.dp), GlassIntensity.High, shadow = false))
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
