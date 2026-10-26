package com.blaze.assistant.mediaapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.movies.MovieDownloads
import com.blaze.assistant.movies.MovieHub
import com.blaze.assistant.movies.MovieInfo
import com.blaze.assistant.movies.MovieLink
import com.blaze.assistant.movies.MoviePlay
import com.blaze.assistant.movies.MoviePost
import com.blaze.assistant.movies.MovieSource
import com.blaze.assistant.movies.MovieStream
import com.blaze.assistant.movies.Movies
import com.blaze.assistant.movies.ProviderRepo
import com.blaze.assistant.movies.ProviderStore
import com.blaze.assistant.movies.Progress
import com.blaze.assistant.movies.SourceNotice
import com.blaze.assistant.ui.BlazeDialog
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.KitSearch
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.StageIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 0.46.2 Blaze Video › Movies: home rows per source, search across enabled sources (failed ones named), details with seasons /
 * episodes, server and quality choice, watchlist, continue watching, downloads, the user's Jellyfin server, and the Providers
 * screen for sources the user adds. Compact and icon-only like the 0.46.1 Stage. The stateless parts are screenshot-tested.
 */
@Composable
internal fun MoviesTab(onPlay: (MoviePlay) -> Unit, onPickFile: () -> Unit, incoming: String? = null, onIncomingDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gen by Movies.gen.collectAsState()
    val lib by Movies.lib.collectAsState()
    var panel by remember { mutableStateOf("") }          // "" | providers | jellyfin
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var detail by remember { mutableStateOf<Pair<MovieSource, MoviePost>?>(null) }
    var note by remember { mutableStateOf("") }
    val sources = remember(gen) { Movies.sources() }

    if (incoming != null) LinkInstallFlow(incoming) { m -> note = m; onIncomingDone(); Movies.gen.value++ }
    if (panel == "providers") { ProvidersScreen(onBack = { panel = ""; Movies.gen.value++ }); return }
    if (panel == "jellyfin") { JellyfinDialog(onDone = { panel = ""; note = it }); }

    detail?.let { (src, post) -> MovieDetailsLoader(src, post, lib.inWatchlist(post.link, post.source), onBack = { detail = null },
        onWatchlist = { Movies.toggleWatchlist(post) }, onPlay = onPlay); return }

    // Search across enabled sources (3 at a time), after a short pause in typing.
    val search by produceState<MovieHub.Search?>(null, query, filter, gen) {
        value = null
        if (query.trim().length < 2) return@produceState
        delay(500)
        val pool = if (filter == "all") sources else sources.filter { it.id == filter }
        value = withContext(Dispatchers.IO) { MovieHub.search(pool, query.trim()) }
        value?.results?.forEach { Movies.note(it.source, it.error) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            KitSearch(query, { query = it }, "Search movies", Modifier.weight(1f))
            StageIconButton(Icons.Rounded.FileOpen, "Open a video file", size = 40.dp) { onPickFile() }
            StageIconButton(Icons.Rounded.Dns, if (Movies.jellyfinAccount() != null) "Jellyfin server" else "Add a Jellyfin server", size = 40.dp) { panel = "jellyfin" }
            StageIconButton(Icons.Rounded.Extension, "Providers", size = 40.dp) { panel = "providers" }
        }
        SourceChips(sources.map { it.id to it.name }, filter) { filter = it }
        if (note.isNotBlank()) Text(note, color = LocalBlaze.current.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        val open: (MovieSource, MoviePost) -> Unit = { s, p -> detail = s to p }
        if (query.trim().length >= 2) SearchResults(search, sources, open)
        else MoviesHome(sources.filter { filter == "all" || it.id == filter }, lib.continueWatching, lib.watchlist, open,
            onResume = { pr -> scope.launch {
                try {
                    val p = if (pr.play.url.isNotBlank()) pr.play else withContext(Dispatchers.IO) { MovieHub.refresh(Movies.source(pr.play.source) ?: error("That source is gone"), pr.play, Movies.maxHeight(ctx)) }
                    onPlay(p)
                } catch (e: Exception) { note = e.message ?: "Couldn't continue" }
            } })
    }
}

@Composable
internal fun SourceChips(items: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("all" to "All") + items).forEach { (id, name) -> MovieChip(name, id == value) { onPick(id) } }
    }
}

@Composable
private fun MovieChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent.copy(alpha = 0.18f) else c.faint)
        .border(1.dp, if (selected) c.accent else c.hair, RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = text) { onClick() }
        .padding(horizontal = 12.dp, vertical = 6.dp)) { Text(text, color = if (selected) c.accent else c.ink, fontSize = 13.sp, maxLines = 1) }
}

/** Home: Continue watching, Watchlist, then each source's own rows (loaded per source, failures shown as one quiet line). */
@Composable
internal fun MoviesHome(sources: List<MovieSource>, cont: List<Progress>, watch: List<MoviePost>, open: (MovieSource, MoviePost) -> Unit,
                        onResume: (Progress) -> Unit, preloaded: Map<String, List<Pair<String, List<MoviePost>>>>? = null) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        if (cont.isNotEmpty()) item { RowTitle("Continue watching"); LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cont) { pr -> Poster(pr.play.title, pr.play.image, pr.fraction) { onResume(pr) } } } }
        if (watch.isNotEmpty()) item { RowTitle("Watchlist"); LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(watch) { p -> Poster(p.title, p.image) { sources.firstOrNull { it.id == p.source }?.let { open(it, p) } } } } }
        sources.forEach { s -> item(key = s.id) { SourceRows(s, open, preloaded?.get(s.id)) } }
    }
}

@Composable
private fun SourceRows(s: MovieSource, open: (MovieSource, MoviePost) -> Unit, pre: List<Pair<String, List<MoviePost>>>?) {
    val rows by produceState(pre?.let { Result.success(it) }, s.id) {
        if (pre == null) value = withContext(Dispatchers.IO) { runCatching { s.rows() } }.also { Movies.note(s, it.exceptionOrNull()?.message) }
    }
    val r = rows ?: return
    r.exceptionOrNull()?.let { Text("${s.name} didn't answer", color = LocalBlaze.current.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)); return }
    r.getOrNull().orEmpty().filter { it.second.isNotEmpty() }.forEach { (title, posts) ->
        RowTitle(if (title == s.name) title else "$title \u00b7 ${s.name}")
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(posts) { p -> Poster(p.title, p.image) { open(s, p) } } }
    }
}

@Composable
private fun RowTitle(t: String) = Text(t, color = LocalBlaze.current.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp).semantics { heading() })

@Composable
internal fun Poster(title: String, image: String, progress: Float = -1f, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open $title") { onClick() }) {
        NetThumb(image, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
            if (progress >= 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(3.dp).background(c.accent))
        }
        Text(title, color = c.ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun SearchResults(s: MovieHub.Search?, sources: List<MovieSource>, open: (MovieSource, MoviePost) -> Unit) {
    val c = LocalBlaze.current
    if (s == null) { Text("Searching\u2026", color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        if (s.failed.isNotEmpty()) item { Text("Didn't answer: " + s.failed.joinToString(", ") { it.source.name + " (" + it.error + ")" }, color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (s.posts.isEmpty()) item { EmptyState("Nothing found", "Try other words, or another source.", mark = false) }
        s.results.filter { it.posts.isNotEmpty() }.forEach { r -> item(key = r.source.id) {
            RowTitle(r.source.name)
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(r.posts) { p -> Poster(p.title, p.image) { open(r.source, p) } } }
        } }
    }
}

// ---------------------------------------------------------------- details

@Composable
private fun MovieDetailsLoader(src: MovieSource, post: MoviePost, saved: Boolean, onBack: () -> Unit, onWatchlist: () -> Unit, onPlay: (MoviePlay) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by produceState<Result<MovieInfo>?>(null, post.link) { value = withContext(Dispatchers.IO) { runCatching { src.info(post) } }.also { Movies.note(src, it.exceptionOrNull()?.message) } }
    var choose by remember { mutableStateOf<Pair<MovieLink, List<MovieStream>>?>(null) }
    var msg by remember { mutableStateOf("") }
    val pick: (MovieLink) -> Unit = { link -> scope.launch {
        msg = "Finding streams\u2026"
        val r = withContext(Dispatchers.IO) { runCatching { src.streams(link) } }
        Movies.note(src, r.exceptionOrNull()?.message)
        val list = r.getOrNull().orEmpty().filter { it.type.lowercase() in MovieHub.PLAYABLE || it.type.isBlank() }
        msg = r.exceptionOrNull()?.message ?: if (list.isEmpty()) "No playable stream" else ""
        if (list.size == 1) onPlay(MovieHub.play(src, post, info?.getOrNull(), link, list[0])) else if (list.isNotEmpty()) choose = link to list
    } }
    val i = info
    when {
        i == null -> Column(Modifier.fillMaxSize()) { DetailsTop(post.title, saved, onBack, onWatchlist); Text("Loading\u2026", color = LocalBlaze.current.sub, modifier = Modifier.padding(16.dp)) }
        i.isFailure -> Column(Modifier.fillMaxSize()) { DetailsTop(post.title, saved, onBack, onWatchlist); EmptyState("${src.name} didn't answer", i.exceptionOrNull()?.message.orEmpty(), mark = false) }
        else -> MovieDetails(i.getOrThrow(), src.name, saved, msg, onBack, onWatchlist, pick,
            loadEpisodes = { link -> withContext(Dispatchers.IO) { runCatching { src.episodes(link) }.getOrDefault(emptyList()) } })
    }
    choose?.let { (link, streams) ->
        StreamChooser(streams, onDismiss = { choose = null },
            onPlay = { s -> choose = null; onPlay(MovieHub.play(src, post, info?.getOrNull(), link, s)) },
            onDownload = { s -> choose = null; msg = MovieDownloads.start(ctx, src, MovieHub.play(src, post, info?.getOrNull(), link, s), s) })
    }
}

@Composable
private fun DetailsTop(title: String, saved: Boolean, onBack: () -> Unit, onWatchlist: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StageIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Movies", size = 40.dp) { onBack() }
        Text(title, color = LocalBlaze.current.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        StageIconButton(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Remove from watchlist" else "Add to watchlist", on = saved, size = 40.dp) { onWatchlist() }
    }
}

/** Poster, plot, tags; each group (a season or quality) with its episodes / links, each a play row. */
@Composable
internal fun MovieDetails(info: MovieInfo, sourceName: String, saved: Boolean, msg: String, onBack: () -> Unit, onWatchlist: () -> Unit,
                          onPick: (MovieLink) -> Unit, loadEpisodes: suspend (String) -> List<MovieLink>) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxSize()) {
        DetailsTop(info.title, saved, onBack, onWatchlist)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp)) {
            item {
                Row {
                    NetThumb(info.image, Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(listOf(sourceName, info.licence).filter { it.isNotBlank() }.joinToString(" \u00b7 "), color = c.sub, fontSize = 12.sp)
                        if (info.tags.isNotEmpty()) Text(info.tags.joinToString(" \u00b7 "), color = c.sub, fontSize = 12.sp, maxLines = 2)
                        if (info.rating.isNotBlank()) Text(info.rating, color = c.sub, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        info.groups.firstNotNullOfOrNull { it.direct.firstOrNull() }?.let { first ->
                            StageIconButton(Icons.Rounded.PlayArrow, "Play ${info.title}", primary = true, size = 52.dp) { onPick(first) } }
                    }
                }
                if (msg.isNotBlank()) Text(msg, color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (info.synopsis.isNotBlank()) Text(info.synopsis, color = c.ink, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
            }
            info.groups.forEach { g -> item {
                Text(listOf(g.title, g.quality).filter { it.isNotBlank() }.joinToString(" \u00b7 "), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                val eps by produceState(g.direct, g.episodesLink) { if (g.direct.isEmpty() && g.episodesLink.isNotBlank()) value = loadEpisodes(g.episodesLink) }
                eps.forEach { l -> LinkRow(l.title) { onPick(l) } }
            } }
        }
    }
}

@Composable
private fun LinkRow(title: String, onPlay: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Play $title") { onPlay() }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        StageIconButton(Icons.Rounded.PlayArrow, "Play $title", size = 38.dp) { onPlay() }
    }
}

/** Server / quality choice: each stream with play and (when possible) download. */
@Composable
internal fun StreamChooser(streams: List<MovieStream>, onDismiss: () -> Unit, onPlay: (MovieStream) -> Unit, onDownload: (MovieStream) -> Unit) {
    BlazeDialog("Choose a stream", confirm = "Close", onConfirm = onDismiss, onDismiss = onDismiss) {
        streams.sortedByDescending { com.blaze.assistant.movies.MovieJson.qualityRank(it.quality) }.take(12).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.server, color = LocalBlaze.current.ink, fontSize = 14.sp, maxLines = 1)
                    Text(listOf(s.quality, s.type.uppercase(), if (s.subtitles.isNotEmpty()) "subtitles" else "").filter { it.isNotBlank() }.joinToString(" \u00b7 "), color = LocalBlaze.current.sub, fontSize = 12.sp)
                }
                StageIconButton(Icons.Rounded.PlayArrow, "Play from ${s.server} ${s.quality}", size = 38.dp) { onPlay(s) }
                if (MovieDownloads.why(s) == null) StageIconButton(Icons.Rounded.Download, "Download from ${s.server} ${s.quality}", size = 38.dp) { onDownload(s) }
            }
        }
    }
}

// ---------------------------------------------------------------- Jellyfin

@Composable
private fun JellyfinDialog(onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val acct = remember { Movies.jellyfinAccount() }
    if (acct != null) {
        BlazeDialog("Jellyfin", "Signed in to ${acct.name} (${acct.server}) as ${acct.user}.", confirm = "Done", onConfirm = { onDone("") },
            dismiss = "Sign out", onDismiss = { Movies.removeJellyfin(); onDone("Signed out of Jellyfin.") })
        return
    }
    var server by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var reveal by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    BlazeDialog("Add your Jellyfin server", "Your own server only. The password is used once to sign in; Blaze keeps the sign-in encrypted on this phone.",
        confirm = if (busy) "Signing in\u2026" else "Sign in", onConfirm = {
            if (!busy) scope.launch { busy = true; err = ""
                try { val n = Movies.addJellyfin(server, user, pass); pass = ""; onDone("Jellyfin: signed in to $n.") } catch (e: Exception) { err = e.message ?: "Couldn't sign in" }
                busy = false }
        }, dismiss = "Cancel", onDismiss = { onDone("") }) {
        BlazeTextField(server, { server = it }, "Server address (http://192.168.1.20:8096)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(8.dp)); BlazeTextField(user, { user = it }, "User name")
        Spacer(Modifier.height(8.dp)); BlazeTextField(pass, { pass = it }, "Password", visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Text(if (reveal) "Hide password" else "Show password", color = LocalBlaze.current.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).clickable { reveal = !reveal })
        if (err.isNotBlank()) Text(err, color = LocalBlaze.current.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---------------------------------------------------------------- Providers

@Composable
private fun ProvidersScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { Movies.providers() }
    var st by remember { mutableStateOf(store.state) }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf<Pair<String, List<ProviderRepo.Entry>>?>(null) }
    var settingsFor by remember { mutableStateOf<ProviderStore.Installed?>(null) }
    var msg by remember { mutableStateOf("") }
    val busy = remember { mutableStateListOf<String>() }
    fun refreshState() { st = store.state }
    fun run(tag: String, block: suspend () -> String) { scope.launch { busy += tag; msg = try { block() } catch (e: Exception) { e.message ?: "Error" }; busy -= tag; refreshState() } }
    ProvidersList(st, busy, msg, onBack = onBack,
        onAdd = { if (SourceNotice.shouldShow(st.noticeSeen)) notice = true else adding = true },
        onRefresh = { run("refresh") { val n = withContext(Dispatchers.IO) { store.refresh() }; if (n == 0) "Everything is up to date." else "$n update${if (n == 1) "" else "s"} available." } },
        onTest = { v -> run(v) { val s = store.test(v); "${st.installed.firstOrNull { it.value == v }?.entry?.name}: ${s.label}" } },
        onUpdate = { v -> run(v) { withContext(Dispatchers.IO) { store.update(v) }; "Updated." } },
        onRemove = { v -> store.remove(v); refreshState(); msg = "Removed." },
        onActive = { v -> store.setActive(v); refreshState() },
        onSettings = { settingsFor = it })
    if (notice) BlazeDialog("Before you add a source", SourceNotice.TEXT, confirm = "I understand", onConfirm = { store.markNoticeSeen(); refreshState(); notice = false; adding = true },
        dismiss = "Cancel", onDismiss = { notice = false })
    if (adding) AddSourceDialog(onCancel = { adding = false }, onAdd = { url -> adding = false
        run("add") { val r = withContext(Dispatchers.IO) { store.addRepo(url) }; offer = r; "Found ${r.second.size} provider${if (r.second.size == 1) "" else "s"}." } })
    offer?.let { (base, entries) -> InstallDialog(entries, st.installed.map { it.value }.toSet(), onCancel = { offer = null },
        install = { e -> withContext(Dispatchers.IO) { store.install(base, e) }; refreshState() },
        onDone = { m -> offer = null; refreshState(); msg = m }) }
    settingsFor?.let { inst -> ProviderSettingsDialog(inst, onDone = { set, on -> store.setSettings(inst.value, set); store.setEnabled(inst.value, on); refreshState(); settingsFor = null }, onCancel = { settingsFor = null }) }
}

/** The compact, icon-only Providers list (stateless; screenshot-tested). */
@Composable
internal fun ProvidersList(st: ProviderStore.State, busy: List<String>, msg: String, onBack: () -> Unit, onAdd: () -> Unit, onRefresh: () -> Unit,
                           onTest: (String) -> Unit, onUpdate: (String) -> Unit, onRemove: (String) -> Unit, onActive: (String) -> Unit, onSettings: (ProviderStore.Installed) -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StageIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Movies", size = 40.dp) { onBack() }
            Text("Providers", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            StageIconButton(Icons.Rounded.Refresh, "Check for updates", size = 40.dp, enabled = "refresh" !in busy && st.repos.isNotEmpty()) { onRefresh() }
            StageIconButton(Icons.Rounded.Add, "Add source", primary = true, size = 40.dp) { onAdd() }
        }
        Text("Built in: Internet Archive, Blender open movies, your videos" + (if (Movies.jellyfinAccount() != null) ", Jellyfin" else "") + ".",
            color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        if (msg.isNotBlank()) Text(msg, color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (st.installed.isEmpty()) { EmptyState("No added sources", "Add a source by its URL with the + button. Blaze doesn't suggest or check sources.", mark = false); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 10.dp, end = 6.dp, bottom = 90.dp)) {
            items(st.installed, key = { it.value }) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    StageIconButton(if (st.active == i.value) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        if (st.active == i.value) "${i.entry.name} is the active provider" else "Make ${i.entry.name} the active provider", on = st.active == i.value, size = 38.dp) { onActive(i.value) }
                    Column(Modifier.weight(1f)) {
                        Text(i.entry.name, color = if (i.enabled) c.ink else c.sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("v${i.entry.version} \u00b7 ${i.status.label}" + (if (!i.enabled) " \u00b7 off" else "") + (if (i.detail.isNotBlank()) " \u00b7 ${i.detail}" else ""),
                            color = c.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusIcon(i.status)
                    if (i.updateAvailable) StageIconButton(Icons.Rounded.SystemUpdate, "Update ${i.entry.name} to ${i.latest}", size = 38.dp, enabled = i.value !in busy) { onUpdate(i.value) }
                    StageIconButton(Icons.Rounded.Science, "Test ${i.entry.name}", size = 38.dp, enabled = i.value !in busy) { onTest(i.value) }
                    StageIconButton(Icons.Rounded.Tune, "${i.entry.name} settings", size = 38.dp) { onSettings(i) }
                    StageIconButton(Icons.Rounded.Delete, "Remove ${i.entry.name}", size = 38.dp) { onRemove(i.value) }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(s: ProviderStore.Status) {
    val c = LocalBlaze.current
    val (icon, tint) = when (s) { ProviderStore.Status.WORKING -> Icons.Rounded.CheckCircle to c.accent; ProviderStore.Status.FAILING -> Icons.Rounded.Error to androidx.compose.ui.graphics.Color(0xFFE5484D); else -> Icons.Rounded.HelpOutline to c.sub }
    Icon(icon, contentDescription = s.label, tint = tint, modifier = Modifier.size(18.dp).semantics { contentDescription = s.label })
}

@Composable
internal fun AddSourceDialog(onCancel: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    // The field starts empty: the hint is Blaze's own example repo (what a link looks like), never a third-party one.
    BlazeDialog("Add source", "Type or paste a source: owner/repo, a GitHub or GitLab link, or a folder with manifest.json. Blaze finds the manifest.",
        confirm = "Add", onConfirm = { if (url.isNotBlank()) onAdd(url.trim()) }, dismiss = "Cancel", onDismiss = onCancel) {
        BlazeTextField(url, { url = it }, ProviderRepo.EXAMPLE, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Text("Example: " + ProviderRepo.EXAMPLE, color = LocalBlaze.current.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** An add-source link: find the repo's manifest, then show [LinkInstallCard]; Install adds every provider in one tap. */
@Composable
private fun LinkInstallFlow(url: String, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { Movies.providers() }
    val found by produceState<Result<Pair<String, List<ProviderRepo.Entry>>>?>(null, url) { value = withContext(Dispatchers.IO) { runCatching { store.resolve(url) } } }
    var busy by remember { mutableStateOf(false) }
    val f = found
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDone("") }) {
        LinkInstallCard(url, f?.getOrNull()?.second, f?.exceptionOrNull()?.message, showNotice = SourceNotice.shouldShow(store.state.noticeSeen), busy = busy,
            onInstall = { if (!busy && f?.isSuccess == true) scope.launch {
                busy = true
                val msg = try { store.markNoticeSeen(); val n = withContext(Dispatchers.IO) { store.addAndInstallAll(url) }; "Installed $n provider${if (n == 1) "" else "s"}. Test them in Providers." }
                    catch (e: Exception) { "Couldn't install: " + (e.message ?: "error") }
                busy = false; onDone(msg)
            } }, onCancel = { onDone("") })
    }
}

/** The add-source link card (stateless; screenshot-tested): the source URL, its providers, the one-time notice, one Install button. */
@Composable
internal fun LinkInstallCard(url: String, entries: List<ProviderRepo.Entry>?, error: String?, showNotice: Boolean, busy: Boolean, onInstall: () -> Unit, onCancel: () -> Unit) {
    val body = when {
        error != null -> "Couldn't read this source: $error"
        entries == null -> "Reading the source\u2026"
        else -> "${entries.count { !it.disabled }} provider${if (entries.count { !it.disabled } == 1) "" else "s"}: " + entries.filter { !it.disabled }.take(8).joinToString(", ") { it.name }
    }
    com.blaze.assistant.ui.BlazeDialogCard("Add source", body, confirm = if (busy) "Installing\u2026" else "Install", onConfirm = { if (entries != null && error == null) onInstall() },
        dismiss = "Cancel", onDismiss = onCancel) {
        Text(url, color = LocalBlaze.current.ink, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { contentDescription = "Source URL $url" })
        if (showNotice) Text(SourceNotice.TEXT, color = LocalBlaze.current.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * 0.46.3 (report: the "Install providers" list had no visible Install button: a long list pushed it off screen). The pure
 * rules of the install sheet (InstallSheetTest).
 */
internal object InstallPick {
    /** What the sheet lists (disabled entries never). */
    fun listed(entries: List<ProviderRepo.Entry>): List<ProviderRepo.Entry> = entries.filter { !it.disabled }
    fun label(n: Int) = "Install ($n)"
    fun canInstall(n: Int, installing: Boolean) = n > 0 && !installing
    fun allPicked(listed: List<ProviderRepo.Entry>, picked: Collection<String>) = listed.isNotEmpty() && listed.all { it.value in picked }
    /** The header toggle: everything when not all are picked, else nothing. */
    fun toggleAll(listed: List<ProviderRepo.Entry>, picked: Collection<String>): List<String> = if (allPicked(listed, picked)) emptyList() else listed.map { it.value }
    const val INSTALLING = "Installing\u2026"
    const val DONE = "Installed"
}

/**
 * The install sheet (stateless; screenshot-tested): a header with a select all / none toggle, the providers in a list that
 * scrolls, and a bottom bar that always stays on screen: Cancel and the primary "Install (N)" (disabled at 0). While it
 * installs, each provider shows its own progress.
 */
@Composable
internal fun InstallSheet(entries: List<ProviderRepo.Entry>, have: Set<String>, picked: List<String>, progress: Map<String, String>, installing: Boolean,
                          onToggle: (String) -> Unit, onAll: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit) {
    val c = LocalBlaze.current
    val listed = InstallPick.listed(entries)
    val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().heightIn(max = maxH)
        .background(com.blaze.assistant.ui.DialogColors.surface, shape).padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Install providers", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Pick the ones you want.", color = c.sub, fontSize = 13.sp)
            }
            val all = InstallPick.allPicked(listed, picked)
            StageIconButton(if (all) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (all) "Select none" else "Select all",
                on = all, size = 40.dp, enabled = !installing) { onAll() }
        }
        LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 8.dp).semantics { contentDescription = "Providers to install" }) {
            items(listed, key = { it.value }) { e ->
                val on = e.value in picked
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StageIconButton(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, (if (on) "Don't install " else "Install ") + e.name,
                        on = on, size = 36.dp, enabled = !installing) { onToggle(e.value) }
                    Text(e.name + " \u00b7 v" + e.version + if (e.value in have) " (installed)" else "", color = c.ink, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    progress[e.value]?.let { Text(it, color = c.sub, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(end = 6.dp)) }
                }
            }
        }
        // the bottom bar: never scrolls away
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            com.blaze.assistant.ui.PillButton("Cancel", primary = false, onClick = onCancel)
            val n = picked.count { v -> listed.any { it.value == v } }
            val ok = InstallPick.canInstall(n, installing)
            Row(Modifier.height(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(if (ok) c.accent else c.accent.copy(alpha = 0.3f))
                .clickable(enabled = ok, onClick = onInstall).padding(horizontal = 16.dp)
                .semantics { contentDescription = if (installing) InstallPick.INSTALLING else InstallPick.label(n) },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (installing) InstallPick.INSTALLING else InstallPick.label(n), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun InstallDialog(entries: List<ProviderRepo.Entry>, have: Set<String>, onCancel: () -> Unit, install: suspend (ProviderRepo.Entry) -> Unit, onDone: (String) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    val progress = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var installing by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val listed = InstallPick.listed(entries)
    // back (and a tap outside) cancels; while installing it stops after the provider in progress
    androidx.compose.ui.window.Dialog(onDismissRequest = { work?.cancel(); onCancel() }) {
        InstallSheet(entries, have, picked, progress, installing,
            onToggle = { v -> if (!picked.remove(v)) picked += v },
            onAll = { val next = InstallPick.toggleAll(listed, picked); picked.clear(); picked.addAll(next) },
            onCancel = { work?.cancel(); onCancel() },
            onInstall = {
                if (InstallPick.canInstall(picked.size, installing)) work = scope.launch {
                    installing = true
                    var ok = 0
                    for (e in listed.filter { it.value in picked }) {
                        progress[e.value] = InstallPick.INSTALLING
                        progress[e.value] = try { install(e); ok++; InstallPick.DONE } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { "Failed" }
                    }
                    kotlinx.coroutines.delay(500)
                    installing = false
                    onDone("Installed $ok of ${picked.size}.")
                }
            })
    }
}

@Composable
private fun ProviderSettingsDialog(i: ProviderStore.Installed, onDone: (Map<String, String>, Boolean) -> Unit, onCancel: () -> Unit) {
    var base by remember { mutableStateOf(i.settings["baseUrl"].orEmpty()) }
    var on by remember { mutableStateOf(i.enabled) }
    BlazeDialog(i.entry.name, "Runs in Blaze's sandbox: web requests only, no files, no Blaze data.", confirm = "Save",
        onConfirm = { onDone(i.settings + ("baseUrl" to base.trim()), on) }, dismiss = "Cancel", onDismiss = onCancel) {
        BlazeTextField(base, { base = it }, "Site address the provider uses (optional)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Use this provider", color = LocalBlaze.current.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            com.blaze.assistant.ui.KitSwitch(on) { on = !on }
        }
    }
}
