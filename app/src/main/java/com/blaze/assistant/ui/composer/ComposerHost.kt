package com.blaze.assistant.ui.composer

import com.blaze.assistant.composer.ComposerLook
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.GlassTokens
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import kotlinx.coroutines.launch

/**
 * 0.47 chat composer + Spotlight v2: the ONE integration entry point.
 *
 * Wrap the Home content (the widget area) in it and it adds, on top:
 *  - the chat bar at the bottom (profile · "Message Blaze" · +), never blurred;
 *  - while chat or Spotlight is up: ONE cached blur over the Home content (API 31+, off on low-RAM / hot phones, where the
 *    dim alone shows) plus a dim, and the chat over Home or the Spotlight panel in the widget area.
 * Home should reserve [ChatBarTokens.HEIGHT_DP] (+ the navigation bar) under its cards so the bar never hides a card.
 *
 * [barModifier] lets the host keep its own gesture on the bar (e.g. swipe up for the full chat). [onLeaveHome] shows the
 * full app for things that live there (settings pages, private chat, "Open the full chat").
 *
 * For a layout with its own bottom slot, use the parts instead: [rememberComposerState], [BlazeChatBar],
 * [ComposerOverlay] and [Modifier.composerBlur].
 */
@Composable
fun BlazeComposerHome(modifier: Modifier = Modifier, state: ComposerState = rememberComposerState(), barModifier: Modifier = Modifier,
                      onLeaveHome: () -> Unit = {}, onProfile: () -> Unit = {},
                      /** RC4: what Blaze is waiting on (approvals, questions, secure forms…), shown under the chat over Home. */
                      cards: @Composable ColumnScope.() -> Unit = {},
                      content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().composerBlur(state.covering)) { content() }
        ComposerOverlay(state, onLeaveHome = onLeaveHome, modifier = Modifier.fillMaxSize(), cards = cards)
        // RC4: Spotlight floats on its own (like macOS Spotlight), so the bar steps aside while it is up.
        val barShown by animateFloatAsState(if (state.spotlightOpen) 0f else 1f, MotionTokens.fast(), label = "bar")
        if (barShown > 0f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
            .graphicsLayer { alpha = barShown }.then(barModifier)) {
            BlazeChatBar(state, onProfile = onProfile)
        }
    }
}

/**
 * The dim + chat over Home, and the floating Spotlight panel. Draw it above the (blurred) Home content and below
 * [BlazeChatBar]. Handles Back (closes Spotlight, then the chat).
 */
@Composable
fun ComposerOverlay(state: ComposerState, onLeaveHome: () -> Unit = {}, modifier: Modifier = Modifier, cards: @Composable ColumnScope.() -> Unit = {}) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            state.attaching = true
            scope.launch {
                val a = runCatching { com.blaze.assistant.agent.Attachments.load(ctx, uri) }.getOrNull()
                if (a != null) state.attachments.add(a)
                state.attaching = false
            }
        }
    }
    val actions = rememberSpotlightActions(state, onLeaveHome = { onLeaveHome() }, onAttach = { runCatching { pick.launch("*/*") } })
    val dim by animateFloatAsState(if (state.covering) 1f else 0f, MotionTokens.medium(), label = "dim")
    val solid = rememberComposerSolid()
    androidx.compose.runtime.CompositionLocalProvider(LocalComposerSolid provides solid) {
    Box(modifier) {
        if (dim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(ComposerLook.scrim(c, blur = !solid))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (state.spotlightOpen) state.closeSpotlight() })
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(bottom = (ChatBarTokens.HEIGHT_DP + 8).dp)) {
            AnimatedVisibility(state.chatOpen && !state.spotlightOpen, modifier = Modifier.weight(1f),
                enter = fadeIn(MotionTokens.medium()), exit = fadeOut(MotionTokens.fast())) {
                ChatOverHome(state, bottom = cards)
            }
        }
        SpotlightLayer(state, actions)
    }
    }
    BackHandler(enabled = state.spotlightOpen || state.chatOpen || state.pickerOpen) {
        when {
            state.pickerOpen -> state.pickerOpen = false
            state.preview != null -> state.preview = null
            state.spotlightOpen -> state.closeSpotlight()
            else -> state.closeChat()
        }
    }
}

/**
 * RC4 Spotlight: a standalone floating panel, centred over the dimmed Home in the space above the keyboard (upper third
 * like macOS; just under the status bar while the keyboard is up), never attached to the chat bar. Its height is capped
 * to that space, so the keyboard never covers it.
 */
@Composable
private fun SpotlightLayer(state: ComposerState, actions: SpotlightActions) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        val room = maxHeight
        // Upper third when there is room (no keyboard); right under the top when the keyboard leaves less.
        val top = if (room > 560.dp) room * 0.14f else 4.dp
        AnimatedVisibility(state.spotlightOpen, modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
            enter = fadeIn(MotionTokens.medium()) + (if (MotionTokens.enabled) scaleIn(MotionTokens.medium(), initialScale = 0.96f) else fadeIn(MotionTokens.fast())),
            exit = fadeOut(MotionTokens.fast()) + (if (MotionTokens.enabled) scaleOut(MotionTokens.fast(), targetScale = 0.98f) else fadeOut(MotionTokens.fast()))) {
            SpotlightPanel(state, onAct = actions::run, maxHeight = room - top)
        }
    }
}

/**
 * ONE cached blur on the Home content while [on]: a single remembered RenderEffect at a fixed radius (no per-frame blur
 * animation; the dim fades instead). API 31+ only and off where GlassTokens says blur is too costly (low-RAM, hot).
 */
fun Modifier.composerBlur(on: Boolean, radius: androidx.compose.ui.unit.Dp = 22.dp): Modifier = composed {
    val ctx = LocalContext.current
    val ok = remember { runCatching { GlassTokens.blurSupported(ctx) }.getOrDefault(false) }
    if (!ok || !on) this else {
        val px = with(LocalDensity.current) { radius.toPx() }
        val effect = remember(px) { BlurEffect(px, px, TileMode.Clamp) }
        graphicsLayer { renderEffect = effect }
    }
}

/**
 * 0.48.0: true where the Home blur is unavailable ([GlassTokens.blurSupported] false: low-RAM phones, API < 31, hot
 * phones). Chat and Spotlight then sit on a strong solid scrim and Blaze's bubble is opaque.
 */
val LocalComposerSolid = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun rememberComposerSolid(): Boolean {
    val ctx = LocalContext.current
    return remember { !runCatching { GlassTokens.blurSupported(ctx) }.getOrDefault(false) }
}

/** Overload for the parts API. */
fun Modifier.composerBlur(state: ComposerState): Modifier = composerBlur(state.covering)
