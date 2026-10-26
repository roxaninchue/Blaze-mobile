package com.blaze.assistant.mediaapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.movies.MovieHub
import com.blaze.assistant.movies.MoviePlay
import com.blaze.assistant.movies.MoviePost
import com.blaze.assistant.movies.MovieSource
import com.blaze.assistant.movies.Movies
import com.blaze.assistant.movies.Progress
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.GlassIntensity
import com.blaze.assistant.ui.KitSearch
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 0.46.4 Video revamp: Home (hero carousel + poster rows), Search (poster grid with source chips), Sources (providers,
 * Jellyfin, open a file). Posters first, little text. Every row comes from MovieSource as in 0.46.2; nothing new is fetched
 * from anywhere the user didn't add.
 */

/**
 * Rows per source id, loaded in parallel with a time limit; each lands as it answers and stays across refreshes
 * ([BrowseState.merge]); [retry] bumps to load again.
 */
@Composable
internal fun rememberSourceRows(sources: List<MovieSource>, gen: Int, retry: Int = 0): Map<String, Result<List<Pair<String, List<MoviePost>>>>> {
    val rows = remember { mutableStateMapOf<String, Result<List<Pair<String, List<MoviePost>>>>>() }
    LaunchedEffect(sources.map { it.id }, gen, retry) {
        sources.forEach { s ->
            launch {
                val r = withContext(Dispatchers.IO) {
                    try { kotlinx.coroutines.withTimeoutOrNull(BrowseState.SOURCE_TIMEOUT_MS) { runCatching { s.rows() } } ?: Result.failure(IllegalStateException("timeout")) }
                    catch (e: Exception) { Result.failure(e) }
                }
                Movies.note(s, r.exceptionOrNull()?.message)
                rows[s.id] = BrowseState.merge(rows[s.id], r)
            }
        }
    }
    return rows
}

@Composable
internal fun VideoHomePage(open: (MovieSource, MoviePost) -> Unit, onResume: (Progress) -> Unit) {
    val gen by Movies.gen.collectAsState()
    val lib by Movies.lib.collectAsState()
    val sources = remember(gen) { Movies.sources() }
    var retry by remember { mutableStateOf(0) }
    val rows = rememberSourceRows(sources, gen, retry)
    VideoHomeContent(sources, rows, lib.continueWatching, lib.watchlist, { p -> lib.inWatchlist(p.link, p.source) }, open, onResume, onRetry = { retry++ })
}

/** Home, stateless (screenshot-tested): hero carousel, Continue watching, Watchlist, then each source's poster rows. */
@Composable
internal fun VideoHomeContent(sources: List<MovieSource>, rows: Map<String, Result<List<Pair<String, List<MoviePost>>>>>, cont: List<Progress>, watch: List<MoviePost>,
                              saved: (MoviePost) -> Boolean, open: (MovieSource, MoviePost) -> Unit, onResume: (Progress) -> Unit, onRetry: () -> Unit = {}) {
    val c = LocalBlaze.current
    val byId = remember(sources) { sources.associateBy { it.id } }
    val snap = rows.toMap()
    val flat = remember(snap) { snap.mapValues { (_, r) -> r.getOrNull().orEmpty().flatMap { it.second } } }
    val hero = remember(cont, flat) { VideoHome.hero(cont, flat) }
    val ids = sources.map { it.id }
    val note = BrowseState.note(sources.associate { it.id to it.name }, ids.filter { BrowseState.isUserAdded(it) }, ids.associateWith { rows[it] })
    if (BrowseState.nothing(ids, ids.associateWith { rows[it] }, cont.size + watch.size)) {
        EmptyState("Nothing to show right now", "Check your connection, then try again.", mark = false, action = "Retry", onAction = onRetry); return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
        if (hero.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(VideoKeys.unique(hero) { heroKey(it) }, key = { heroKey(it) }) { h ->
                    HeroCard(h, saved = h.post?.let(saved) == true,
                        onPlay = { h.resume?.let(onResume) ?: h.post?.let { p -> byId[p.source]?.let { open(it, p) } } },
                        onWatchlist = { h.post?.let { Movies.toggleWatchlist(it) } })
                }
            }
        }
        if (cont.isNotEmpty()) item {
            SectionTitle("Continue watching")
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(VideoKeys.unique(cont) { "cw-" + it.play.key }, key = { "cw-" + it.play.key }) { pr -> Poster(pr.play.title, pr.play.image, pr.fraction) { onResume(pr) } }
            }
        }
        if (watch.isNotEmpty()) item {
            SectionTitle("Watchlist")
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(VideoKeys.posts(watch), key = { "wl-" + VideoKeys.post(it) }) { p -> Poster(p.title, p.image) { byId[p.source]?.let { open(it, p) } } }
            }
        }
        if (note.isNotBlank()) item(key = "note") { Text(note, color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        VideoHome.order(sources.map { it.id }).forEach { id ->
            val s = byId[id] ?: return@forEach
            val r = rows[id]
            when {
                r == null -> item(key = "load-$id") { SectionTitle(VideoHome.rowTitle(id, s.name, "")); PosterSkeletons() }
                !BrowseState.loaded(r) -> {}   // failed, timed out or empty: hidden
                else -> r.getOrNull().orEmpty().filter { it.second.isNotEmpty() }.forEachIndexed { i, (title, posts) ->
                    item(key = "row-$id-$i") {
                        SectionTitle(VideoHome.rowTitle(id, s.name, title))
                        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(VideoKeys.posts(posts), key = { "p-" + VideoKeys.post(it) }) { p -> Poster(p.title, p.image) { open(s, p) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterSkeletons() {
    val c = LocalBlaze.current
    Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { Box(Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(c.faint)) }
    }
}

/** A hero card: the backdrop art, its title, Play / Resume and Watchlist. */
@Composable
private fun HeroCard(h: VideoHome.Hero, saved: Boolean, onPlay: () -> Unit, onWatchlist: () -> Unit) {
    Box(Modifier.width(300.dp).aspectRatio(16f / 10f).clip(BlazeShapes.medium).clickable(role = Role.Button, onClickLabel = "Open ${h.title}") { onPlay() }) {
        PosterArt(h.title, h.image, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(h.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.clip(BlazeShapes.pill).background(Color.White).clickable(role = Role.Button, onClickLabel = if (h.resume != null) "Resume ${h.title}" else "Play ${h.title}") { onPlay() }
                    .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Text(if (h.resume != null) "Resume" else "Play", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (h.post != null) RoundIcon(if (saved) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkBorder,
                    if (saved) "Remove from watchlist" else "Add to watchlist", size = 38, tint = Color.White, fill = Color.Black.copy(alpha = 0.35f)) { onWatchlist() }
            }
        }
    }
}

/** Search: instant (after a short pause in typing) across sources, posters in a grid, source filter chips. */
@Composable
internal fun VideoSearchPage(open: (MovieSource, MoviePost) -> Unit, onOnline: () -> Unit) {
    val c = LocalBlaze.current
    val gen by Movies.gen.collectAsState()
    val sources = remember(gen) { Movies.sources() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val search by produceState<MovieHub.Search?>(null, query, filter, gen) {
        value = null
        if (query.trim().length < 2) return@produceState
        delay(LiveSearch.DEBOUNCE_MS)
        val pool = if (filter == "all") sources else sources.filter { it.id == filter }
        value = withContext(Dispatchers.IO) { MovieHub.search(pool, query.trim()) }
        value?.results?.forEach { Movies.note(it.source, it.error) }
    }
    Column(Modifier.fillMaxSize()) {
        KitSearch(query, { query = it }, "Films, shows, your videos", Modifier.padding(horizontal = 14.dp).fillMaxWidth())
        FilterChips(listOf("all" to "All") + sources.map { it.id to VideoHome.rowTitle(it.id, it.name, "") }, filter) { filter = it }
        val s = search
        when {
            query.trim().length < 2 -> Column(Modifier.padding(16.dp)) {
                SmallChip("Online videos", Icons.Rounded.Language) { onOnline() }
            }
            s == null -> Text("Searching\u2026", color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            else -> {
                val all = s.results.flatMap { r -> BrowseState.clean(r.posts).map { r.source to it } }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val one = sources.filter { BrowseState.isUserAdded(it.id) }
                    if (all.isEmpty() && one.size == 1 && s.failed.any { it.source.id == one[0].id }) item { Text("Couldn't reach ${one[0].name}. Check your connection.", color = c.sub, fontSize = 12.sp) }
                    if (all.isEmpty()) item { EmptyState("Nothing found", "Try other words, or another source.", mark = false) }
                    items(all.chunked(3)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (src, p) -> Box(Modifier.weight(1f)) { GridPoster(p) { open(src, p) } } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridPoster(p: MoviePost, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open ${p.title}") { onClick() }) {
        PosterArt(p.title, p.image, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
        Text(p.title, color = c.ink, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Library › Watchlist: the saved films as a poster grid. */
@Composable
internal fun WatchlistGrid(open: (MovieSource, MoviePost) -> Unit) {
    val lib by Movies.lib.collectAsState()
    val gen by Movies.gen.collectAsState()
    val byId = remember(gen) { Movies.sources().associateBy { it.id } }
    if (lib.watchlist.isEmpty()) { EmptyState("Your watchlist is empty", "Tap the bookmark on any film to keep it here.", mark = false); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(lib.watchlist.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p -> Box(Modifier.weight(1f)) { GridPoster(p) { byId[p.source]?.let { open(it, p) } } } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Sources: the provider manager (unchanged), the user's Jellyfin server, and opening a video file. */
@Composable
internal fun VideoSourcesPage(onPickFile: () -> Unit, incoming: String?, onIncomingDone: () -> Unit) {
    val c = LocalBlaze.current
    var panel by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    if (incoming != null) LinkInstallFlow(incoming) { m -> note = m; onIncomingDone(); Movies.gen.value++ }
    if (panel == "providers") { ProvidersScreen(onBack = { panel = ""; Movies.gen.value++ }); return }
    if (panel == "jellyfin") JellyfinDialog(onDone = { panel = ""; note = it })
    val gen by Movies.gen.collectAsState()
    val sources = remember(gen) { Movies.sources() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile("Providers", Icons.Rounded.Extension, Modifier.weight(1f)) { panel = "providers" }
                SourceTile(if (Movies.jellyfinAccount() != null) "Jellyfin" else "Add Jellyfin", Icons.Rounded.Dns, Modifier.weight(1f)) { panel = "jellyfin" }
                SourceTile("Open a file", Icons.Rounded.FileOpen, Modifier.weight(1f)) { onPickFile() }
            }
        }
        if (note.isNotBlank()) item { Text(note, color = c.sub, fontSize = 12.sp) }
        item { SectionTitle("On now", Modifier.offset(x = (-16).dp)) }
        items(VideoKeys.unique(sources) { it.id }, key = { "src-" + it.id }) { s ->
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).glass(BlazeShapes.small, GlassIntensity.Low, shadow = false).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.official) Icons.Rounded.Verified else Icons.Rounded.Extension, contentDescription = null, tint = c.sub, modifier = Modifier.size(20.dp))
                Text(VideoHome.rowTitle(s.id, s.name, ""), color = c.ink, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun SourceTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(modifier.height(92.dp).clip(BlazeShapes.small).glass(BlazeShapes.small, GlassIntensity.Medium, shadow = false)
        .clickable(role = Role.Button, onClickLabel = label) { onClick() }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(26.dp))
        Text(label, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Opens a film from a hero / poster / watchlist on the details page, and plays a Continue-watching entry. */
internal suspend fun resumePlay(ctx: android.content.Context, pr: Progress): MoviePlay =
    if (pr.play.url.isNotBlank()) pr.play else withContext(Dispatchers.IO) {
        MovieHub.refresh(Movies.source(pr.play.source) ?: error("That source is gone"), pr.play, Movies.maxHeight(ctx))
    }

private fun heroKey(h: VideoHome.Hero): String = "hero-" + (h.post?.let { VideoKeys.post(it) } ?: h.resume?.play?.key?.let { "r|$it" } ?: "t|" + h.title)
