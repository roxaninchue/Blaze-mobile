package com.blaze.assistant.ui.composer

import com.blaze.assistant.composer.ComposerLook
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.TransformOrigin
import com.blaze.assistant.composer.ComposerMotion
import com.blaze.assistant.composer.SpotlightLook
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
 *  - the chat bar at the bottom (profile · "Message Blaze" pill with the Blaze orb · +), never blurred;
 *  - while chat or Spotlight is up (RC2): ONE cached software blur of the Home content ([CachedBlur]: captured once at
 *    1/8 size, blurred off the main thread, drawn full screen with a light dim), on EVERY phone, low-RAM included;
 *    the chat over Home, or the Spotlight panel floating at the TOP of the screen below the status bar.
 * Home should reserve [ChatBarTokens.HEIGHT_DP] (+ the navigation bar) under its cards so the bar never hides a card.
 *
 * [barModifier] lets the host keep its own gesture on the bar (e.g. swipe up for the full chat). [onLeaveHome] shows the
 * full app for things that live there (settings pages, private chat, "Open the full chat").
 *
 * For a layout with its own bottom slot, use the parts instead: [rememberComposerState], [BlazeChatBar],
 * [ComposerOverlay], [rememberCachedBlur] + [cachedBlurSource] + [CachedBlurEffect].
 */
@Composable
fun BlazeComposerHome(modifier: Modifier = Modifier, state: ComposerState = rememberComposerState(), barModifier: Modifier = Modifier,
                      onLeaveHome: () -> Unit = {}, onProfile: () -> Unit = {}, content: @Composable () -> Unit) {
    val blur = rememberCachedBlur()
    CachedBlurEffect(blur, state.covering)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().cachedBlurSource(blur)) { content() }
        ComposerOverlay(state, onLeaveHome = onLeaveHome, modifier = Modifier.fillMaxSize(), blur = blur)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().then(barModifier)) {
            BlazeChatBar(state, onProfile = onProfile)
        }
    }
}

/**
 * The blurred Home + chat over Home / Spotlight panel. Draw it above the Home content and below [BlazeChatBar].
 * With [blur] the backdrop is the cached software blur (falls back to the strong solid scrim if a capture fails);
 * without it, the 0.48.0 RC1 look (dim, or the solid scrim where RenderEffect blur is off).
 * Spotlight floats at the top, below the status bar, sized to its content up to the space above the bar; the chat
 * fills the area above the bar ([chatContent] replaces the live chat, for shots). Handles Back (preview, Spotlight, then the chat).
 */
@Composable
fun ComposerOverlay(state: ComposerState, onLeaveHome: () -> Unit = {}, modifier: Modifier = Modifier, blur: CachedBlur? = null,
                    chatContent: (@Composable () -> Unit)? = null) {
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
    val actions = rememberSpotlightActions(state, onLeaveHome = { state.closeChat(); onLeaveHome() }, onAttach = { runCatching { pick.launch("*/*") } })
    val dim by animateFloatAsState(if (state.covering) 1f else 0f,
        if (MotionTokens.enabled) tween(if (state.covering) ComposerMotion.ENTER else ComposerMotion.EXIT, easing = MotionTokens.Standard) else snap(), label = "dim")
    val solidNoBlur = rememberComposerSolid()
    val solid = if (blur != null) blur.failed else solidNoBlur
    androidx.compose.runtime.CompositionLocalProvider(LocalComposerSolid provides solid) {
    Box(modifier) {
        if (dim > 0f) {
            if (blur != null) CachedBlurBackdrop(blur, dim = ComposerLook.backdropDim(c), fallback = ComposerLook.scrim(c, blur = false), alpha = { dim })
            else Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(ComposerLook.scrim(c, blur = !solid)))
            // a tap outside the panel closes Spotlight
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { if (state.spotlightOpen) state.closeSpotlight() })
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(bottom = (ChatBarTokens.HEIGHT_DP + 8).dp)) {
            AnimatedVisibility(state.chatOpen && !state.spotlightOpen, modifier = Modifier.weight(1f),
                enter = if (MotionTokens.enabled) fadeIn(tween(ComposerMotion.ENTER, easing = MotionTokens.Decelerate)) +
                    slideInVertically(tween(ComposerMotion.ENTER, easing = MotionTokens.Decelerate)) { it / 14 } else EnterTransition.None,
                exit = if (MotionTokens.enabled) fadeOut(tween(ComposerMotion.EXIT, easing = MotionTokens.Accelerate)) else ExitTransition.None) {
                if (chatContent != null) chatContent() else ChatOverHome(state, onOpenFull = { state.closeChat(); onLeaveHome() })
            }
        }
        // Spotlight: floating near the TOP, below the status bar; never a bottom sheet. The bar stays visible below.
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(top = SpotlightLook.TOP_GAP_DP.dp, bottom = (ChatBarTokens.HEIGHT_DP + 8).dp), contentAlignment = Alignment.TopCenter) {
            SpotlightSlot(state, actions)
        }
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

/** The Spotlight panel with its calm enter / exit: drops in from just above, from its top edge (reduce motion: none). */
@Composable
private fun SpotlightSlot(state: ComposerState, actions: SpotlightActions) {
    val calm = MotionTokens.enabled
    AnimatedVisibility(state.spotlightOpen,
        enter = if (calm) fadeIn(tween(ComposerMotion.ENTER, easing = MotionTokens.Decelerate)) +
            slideInVertically(tween(ComposerMotion.ENTER, easing = MotionTokens.Decelerate)) { -it / 12 } +
            scaleIn(tween(ComposerMotion.ENTER, easing = MotionTokens.Decelerate), initialScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 0f)) else EnterTransition.None,
        exit = if (calm) fadeOut(tween(ComposerMotion.EXIT, easing = MotionTokens.Accelerate)) +
            slideOutVertically(tween(ComposerMotion.EXIT, easing = MotionTokens.Accelerate)) { -it / 20 } +
            scaleOut(tween(ComposerMotion.EXIT, easing = MotionTokens.Accelerate), targetScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 0f)) else ExitTransition.None) {
        SpotlightPanel(state, onAct = actions::run)
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
