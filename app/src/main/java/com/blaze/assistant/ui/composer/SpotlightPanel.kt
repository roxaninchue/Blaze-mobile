package com.blaze.assistant.ui.composer

import android.annotation.SuppressLint
import com.blaze.assistant.composer.ComposerLook
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blaze.assistant.composer.SelfMap
import com.blaze.assistant.composer.SpotUrl
import com.blaze.assistant.composer.SpotlightSearch
import com.blaze.assistant.composer.SpotlightSearch.Act
import com.blaze.assistant.ui.DuoColors
import com.blaze.assistant.ui.GlassIntensity
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotlight v2 panel. 0.48.0 RC4 (owner: "detached from the chat box, floats on its own in the style of Apple Spotlight"):
 * one frosted panel with a large radius, the search field with a magnifier on top, a hairline, then result rows (icon tile,
 * title, subtitle), the top row highlighted; Enter runs it. No category pills. [maxHeight] is the room above the keyboard,
 * so the list and the inline previews shrink to fit instead of going under it. Inline previews (@browser, @terminal,
 * @music, @video, @settings) show inside the panel and keep the user on Home. Search runs off the main thread.
 */
@Composable
fun SpotlightPanel(state: ComposerState, onAct: (Act) -> Unit, modifier: Modifier = Modifier, maxHeight: androidx.compose.ui.unit.Dp = 560.dp) {
    val c = LocalBlaze.current
    val songs by com.blaze.assistant.mediaapps.MediaLib.songs.collectAsState()
    val videos by com.blaze.assistant.mediaapps.MediaLib.videos.collectAsState()
    // Installed distros: file checks, once per opening, on IO.
    val distros by produceState(emptyList<SpotlightSearch.Distro>(), state.spotlightOpen) {
        value = withContext(Dispatchers.IO) { runCatching { liveDistros() }.getOrDefault(emptyList()) }
    }
    val map = remember { SelfMap.default }
    val result by produceState(SpotlightSearch.Result(SpotlightSearch.Mode.CURATED, SpotlightSearch.curated(map)),
        state.query, songs, videos, distros, state.distro) {
        if (state.query.isNotEmpty()) delay(60)   // a short debounce while typing
        val q = state.query
        value = withContext(Dispatchers.Default) {
            SpotlightSearch.search(q, map, songs.map { SpotlightSearch.Media(it.key, it.title, it.artist) },
                videos.map { SpotlightSearch.Media(it.key, it.title, it.artist) }, distros, state.distro)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val solid = LocalComposerSolid.current
    val shape = RoundedCornerShape(26.dp)

    Column(modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = maxHeight)
        .shadow(24.dp, shape, clip = false)
        .clip(shape)
        .background(ComposerLook.spotlight(c, blur = !solid))
        .then(if (solid) Modifier else Modifier.glass(shape, GlassIntensity.High, shadow = false))
        .border(1.dp, Color.White.copy(alpha = if (c.dark) 0.14f else 0.55f), shape)
        // taps on the panel stay in it (the dim around it closes Spotlight)
        .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }
        .semantics { contentDescription = "Spotlight" }) {
        // ---- the field: magnifier, big text, the distro picker on the right in "!" mode
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = c.sub, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (state.query.isEmpty()) Text("Spotlight Search", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.sub))
                BasicTextField(state.query, { state.query = it; if (state.preview != null && !it.startsWith("@") && !it.startsWith("!")) state.preview = null },
                    singleLine = true, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.ink), cursorBrush = SolidColor(DuoColors.blazeBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { result.rows.firstOrNull()?.let { onAct(it.act) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Spotlight search" })
            }
            if (result.mode == SpotlightSearch.Mode.TERMINAL && result.distros.isNotEmpty()) DistroPicker(result.distros, state.distro) { state.distro = it.id; if (!it.installed) onAct(Act.OpenView("terminal")) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(c.ink.copy(alpha = 0.10f)))
        val pv = state.preview
        // the room left under the field (56 dp + hairline + padding)
        val rest = (maxHeight - 72.dp).coerceAtLeast(120.dp)
        if (pv != null) Box(Modifier.padding(8.dp)) { InlinePreview(pv, state, onAct, height = (rest - 56.dp).coerceIn(140.dp, 360.dp)) }
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = rest.coerceAtMost(430.dp)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            if (result.mode == SpotlightSearch.Mode.CURATED) item(key = "hint") {
                Text("Search views, settings, models, your music and video \u00b7 ! runs a command \u00b7 @ opens a view", maxLines = 2,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
            }
            itemsIndexed(result.rows, key = { _, r -> r.id }) { i, r -> SpotRow(r, top = i == 0) { onAct(r.act) } }
        }
    }
}

private fun liveDistros(): List<SpotlightSearch.Distro> {
    val installed = runCatching { com.blaze.assistant.terminal.Distros.installedIds() }.getOrDefault(emptySet())
    return com.blaze.assistant.terminal.TermLogic.DISTROS.map { SpotlightSearch.Distro(it.id, it.name, it.id in installed) }
}

fun spotIcon(i: SpotlightSearch.Icon): ImageVector = when (i) {
    SpotlightSearch.Icon.VIEW -> Icons.Rounded.Dashboard
    SpotlightSearch.Icon.TERMINAL -> Icons.Rounded.Terminal
    SpotlightSearch.Icon.MUSIC -> Icons.Rounded.MusicNote
    SpotlightSearch.Icon.VIDEO -> Icons.Rounded.Movie
    SpotlightSearch.Icon.BROWSER -> Icons.Rounded.Language
    SpotlightSearch.Icon.SETTINGS -> Icons.Rounded.Settings
    SpotlightSearch.Icon.MODEL -> Icons.Rounded.AutoAwesome
    SpotlightSearch.Icon.ACTION -> Icons.Rounded.Bolt
    SpotlightSearch.Icon.CHAT -> Icons.Rounded.ChatBubbleOutline
    SpotlightSearch.Icon.SEARCH -> Icons.Rounded.Search
    SpotlightSearch.Icon.PREVIEW -> Icons.Rounded.Visibility
}

/** One result: icon tile, title, subtitle; the top row is highlighted (Enter runs it), like macOS Spotlight. */
@Composable
fun SpotRow(r: SpotlightSearch.Row, top: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (top) DuoColors.blazeBlue.copy(alpha = if (c.dark) 0.34f else 0.20f) else Color.Transparent)
        .clickable(onClick = onClick).semantics { contentDescription = r.title + ". " + r.subtitle }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (top) DuoColors.blazeBlue else c.ink.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(spotIcon(r.icon), null, tint = if (top) Color.White else c.ink, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
            if (r.subtitle.isNotBlank()) Text(r.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        if (top) Text("\u21b5", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub), modifier = Modifier.padding(start = 8.dp))
    }
}

/** The distro picker on the right of the field: a tick in a circle when installed, a download icon when not. */
@Composable
private fun DistroPicker(distros: List<SpotlightSearch.Distro>, current: String, onPick: (SpotlightSearch.Distro) -> Unit) {
    val c = LocalBlaze.current
    var open by remember { mutableStateOf(false) }
    val d = distros.firstOrNull { it.id == current } ?: distros.first()
    Box {
        Row(Modifier.clip(RoundedCornerShape(50)).background(c.ink.copy(alpha = 0.08f)).clickable { open = !open }
            .semantics { contentDescription = "Linux: ${d.name}" + (if (d.installed) ", installed" else ", not installed") + ". Tap to pick another" }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.name.substringBefore(' '), style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
            Spacer(Modifier.width(4.dp))
            InstallMark(d.installed)
        }
        if (open) androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
            distros.forEach { x ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(x.name, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp)) },
                    trailingIcon = { InstallMark(x.installed) }, onClick = { open = false; onPick(x) },
                    modifier = Modifier.semantics { contentDescription = x.name + if (x.installed) ", installed" else ", not installed: download" })
            }
        }
    }
}

@Composable
private fun InstallMark(installed: Boolean) {
    val c = LocalBlaze.current
    if (installed) Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF3DD68C), modifier = Modifier.size(16.dp))
    else Icon(Icons.Rounded.Download, null, tint = c.sub, modifier = Modifier.size(16.dp))
}

// ---------------------------------------------------------------- inline previews (stay on Home)

@Composable
private fun InlinePreview(p: Act.Preview, state: ComposerState, onAct: (Act) -> Unit, height: androidx.compose.ui.unit.Dp = 360.dp) {
    val c = LocalBlaze.current
    val name = SelfMap.default.firstOrNull { it.kind == SelfMap.Kind.VIEW && it.target == p.view }?.title ?: p.view.replaceFirstChar { it.uppercase() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(spotIcon(SpotlightSearch.iconFor(p.view)), null, tint = c.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(name + if (p.arg.isNotBlank()) " \u00b7 ${p.arg}" else "", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink))
            PreviewButton(Icons.Rounded.OpenInFull, "Open the full $name") { onAct(Act.OpenView(p.view, p.arg)) }
            Spacer(Modifier.width(6.dp))
            PreviewButton(Icons.Rounded.Close, "Close preview") { state.preview = null }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(c.ink.copy(alpha = 0.05f))) {
            when (p.view) {
                "browser" -> BrowserPreview(SpotUrl.of(p.arg.ifBlank { "google.com" }), height)
                "terminal" -> TerminalPreview(state)
                "music" -> MediaPreview(video = false, onAct)
                "video" -> MediaPreview(video = true, onAct)
                else -> SettingsPreview(onAct)
            }
        }
    }
}

@Composable
private fun PreviewButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(32.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.08f)).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.ink, modifier = Modifier.size(16.dp)) }
}

/** A preview browser inside Spotlight (one WebView per address, destroyed when the preview closes or the address changes). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserPreview(url: String, height: androidx.compose.ui.unit.Dp = 360.dp) {
    androidx.compose.runtime.key(url) {
        var view by remember { mutableStateOf<android.webkit.WebView?>(null) }
        AndroidView(factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = android.webkit.WebViewClient()
                loadUrl(url); view = this
            }
        }, modifier = Modifier.fillMaxWidth().height(height).semantics { contentDescription = "Preview browser: $url" })
        DisposableEffect(Unit) { onDispose { view?.apply { stopLoading(); destroy() }; view = null } }
    }
}

/** The inline terminal: the newest "!" command and its output in the picked distro. */
@Composable
private fun TerminalPreview(state: ComposerState) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(if (state.termCmd.isBlank()) "Type ! and a command in the field above, then Go." else "${state.distro} \u203a ${state.termCmd}",
            style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, color = c.sub))
        Spacer(Modifier.height(6.dp))
        Text(if (state.termBusy) "Running\u2026" else state.termOut, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.ink))
    }
}

@Composable
private fun MediaPreview(video: Boolean, onAct: (Act) -> Unit) {
    val c = LocalBlaze.current
    val items by (if (video) com.blaze.assistant.mediaapps.MediaLib.videos else com.blaze.assistant.mediaapps.MediaLib.songs).collectAsState()
    if (items.isEmpty()) Box(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(if (video) "No videos in your library yet." else "No songs in your library yet.", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
    } else LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(items.take(50), key = { _, t -> t.key }) { _, t ->
            SpotRow(SpotlightSearch.Row(t.key, if (video) SpotlightSearch.Icon.VIDEO else SpotlightSearch.Icon.MUSIC, t.title, t.artist.ifBlank { if (video) "Video" else "Song" },
                if (video) Act.PlayVideo(t.key) else Act.PlaySong(t.key)), top = false) { onAct(if (video) Act.PlayVideo(t.key) else Act.PlaySong(t.key)) }
        }
    }
}

@Composable
private fun SettingsPreview(onAct: (Act) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(SelfMap.default.filter { it.kind == SelfMap.Kind.SETTING }, key = { _, e -> e.id }) { _, e ->
            SpotRow(SpotlightSearch.Row(e.id, SpotlightSearch.Icon.SETTINGS, e.title, e.subtitle, Act.OpenView(e.target)), top = false) { onAct(Act.OpenView(e.target)) }
        }
    }
}

// ---------------------------------------------------------------- running a row

/** What a Spotlight row does. [onLeaveHome] shows the full app (chat sheet) for views that live there. */
class SpotlightActions(private val ctx: android.content.Context, private val state: ComposerState, private val scope: kotlinx.coroutines.CoroutineScope,
                       var onLeaveHome: () -> Unit, var onAttach: () -> Unit) {
    private val activityViews = setOf("music", "video", "terminal", "browser", "phonebrowser", "gallery")

    fun run(a: Act) {
        when (a) {
            is Act.OpenView -> {
                state.closeSpotlight()
                com.blaze.assistant.ui.Views.open(a.view, a.target)
                if (a.view !in activityViews) onLeaveHome()
            }
            is Act.Preview -> { state.preview = a; if (a.view == "terminal" && a.arg.isNotBlank()) runCommand(a.arg, state.distro) }
            is Act.RunAction -> when (a.action) {
                "chat" -> state.openChat()
                // RC4: the whole thread over Home (the old full chat screen is gone)
                "history" -> state.openChat(full = true)
                "new_chat" -> { state.closeSpotlight(); com.blaze.assistant.agent.Agent.clear(); state.openChat() }
                "private" -> { state.closeSpotlight(); com.blaze.assistant.agent.Agent.enterPrivate(); state.openChat(full = true) }
                "voice" -> { state.closeSpotlight(); com.blaze.assistant.voice.Voice.listenRequests.value = com.blaze.assistant.voice.Voice.listenRequests.value + 1 }
                "attach" -> { state.closeSpotlight(); if (state.hostAction?.invoke("files") != true) onAttach() }
                else -> { state.closeSpotlight(); state.hostAction?.invoke(a.action) }
            }
            is Act.PickModel -> { com.blaze.assistant.composer.ModelChoice.set(com.blaze.assistant.composer.ModelChoice.of(a.family)); state.closeSpotlight() }
            is Act.PlaySong -> { state.closeSpotlight(); playSong(a.key) }
            is Act.PlayVideo -> { state.closeSpotlight(); com.blaze.assistant.mediaapps.VideoActivity.openAndPlay(ctx, a.key) }
            is Act.Browse -> state.preview = Act.Preview("browser", a.query)
            is Act.MusicSearch -> { state.closeSpotlight(); com.blaze.assistant.mediaapps.MusicActivity.open(ctx, "search") }
            is Act.VideoSearch -> { state.closeSpotlight(); com.blaze.assistant.mediaapps.VideoActivity.open(ctx, "search") }
            is Act.AskBlaze -> { state.closeSpotlight(); if (a.query.isBlank()) state.openChat() else state.send(a.query) }
            is Act.RunCommand -> { state.preview = Act.Preview("terminal", a.command); runCommand(a.command, a.distro) }
            is Act.Complete -> state.query = a.text
        }
    }

    private fun playSong(key: String) {
        val svc = com.blaze.assistant.mediaapps.MusicService.current
        if (svc != null) svc.playKeys(listOf(key))
        else { com.blaze.assistant.mediaapps.MusicService.pendingPlay = listOf(key) to false; com.blaze.assistant.mediaapps.MusicActivity.open(ctx, "player") }
    }

    private fun runCommand(cmd: String, distro: String) {
        state.termCmd = cmd; state.termBusy = true; state.termOut = ""
        scope.launch {
            val out = runCatching { com.blaze.assistant.terminal.Terminal.run(cmd, 30, linux = true, distro = distro) }.getOrElse { "Error: ${it.message}" }
            state.termOut = out.take(8_000); state.termBusy = false
        }
    }
}

@Composable
fun rememberSpotlightActions(state: ComposerState, onLeaveHome: () -> Unit, onAttach: () -> Unit): SpotlightActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = remember(state) { SpotlightActions(ctx, state, scope, onLeaveHome, onAttach) }
    a.onLeaveHome = onLeaveHome; a.onAttach = onAttach
    return a
}

