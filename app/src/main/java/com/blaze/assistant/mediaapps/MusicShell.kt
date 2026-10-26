package com.blaze.assistant.mediaapps

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.BlazeMenu
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.GlassIntensity
import com.blaze.assistant.ui.KitSearch
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.PillButton
import com.blaze.assistant.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 0.46.4 Music + Video revamp (shared chrome and the new Music pages). Blaze's own look: the picked theme's scene shows
 * through, raised parts are glass, colours come from the cover art or the theme. Less text, more art; animations are
 * only fades and the existing sheet slide (cheap on every phone).
 */

// ---------------------------------------------------------------- shared chrome (Music and Video)

/** The account badge: the user's initial in a glass circle (top-left of Music and Video). */
@Composable
fun AccountBadge(onClick: () -> Unit) {
    val c = LocalBlaze.current
    val name = remember { runCatching { com.blaze.assistant.agent.Settings.userName }.getOrDefault("") }
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
    Box(Modifier.size(40.dp).clip(BlazeShapes.pill).glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false)
        .clickable(role = Role.Button, onClickLabel = "Your account") { onClick() }
        .semantics { contentDescription = if (name.isBlank()) "Your account" else "Your account, $name" }, contentAlignment = Alignment.Center) {
        if (initial.isNotEmpty()) Text(initial, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        else Icon(Icons.Rounded.Person, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
    }
}

/**
 * The top bar of Music and Video: account badge left, then [actions] (Blaze DJ in Music, the theme switch) and the close (X)
 * on the right. Close always goes back to the Blaze chat ([MusicNav.CLOSE_TARGET]).
 */
@Composable
fun MediaTopBar(title: String, onAccount: () -> Unit, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountBadge(onAccount)
        Spacer(Modifier.width(10.dp))
        Text(title, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
        actions()
        RoundIcon(Icons.Rounded.Close, "Close and go back to Blaze chat", onClick = onClose)
    }
}

data class NavItem(val id: String, val label: String, val icon: ImageVector)

/** The bottom nav of Music and Video: icon + short label (labels are allowed on the nav), glass, edge to edge. */
@Composable
fun MediaBottomNav(items: List<NavItem>, lit: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), GlassIntensity.Medium, shadow = false)
        .navigationBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { n ->
            val on = n.id == lit
            Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab, onClickLabel = n.label) { onPick(n.id) }.semantics { selected = on },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(n.icon, contentDescription = null, tint = if (on) c.ink else c.sub, modifier = Modifier.size(24.dp))
                Text(n.label, color = if (on) c.ink else c.sub, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** A chip row (filters): a quiet glass pill, the picked one solid ink. */
@Composable
fun FilterChips(items: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, label) ->
            val on = id == value
            Box(Modifier.clip(BlazeShapes.pill).then(if (on) Modifier.background(c.ink) else Modifier.glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false))
                .clickable(role = Role.Button, onClickLabel = label) { onPick(id) }.semantics { selected = on }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(label, color = if (on) c.paper else c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SectionTitle(t: String, modifier: Modifier = Modifier) = Text(t, color = LocalBlaze.current.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
    modifier = modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp).semantics { heading() })

// ---------------------------------------------------------------- covers

/** Custom playlist pictures (picked from the gallery, cropped to a centred square, 512 px), one file per playlist. */
object PlaylistCovers {
    val gen = kotlinx.coroutines.flow.MutableStateFlow(0)
    private fun dir(ctx: Context) = File(ctx.filesDir, "mediaapps/covers").apply { mkdirs() }
    fun path(ctx: Context, id: String): String = File(dir(ctx), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg").let { if (it.isFile) it.path else "" }
    fun save(ctx: Context, id: String, uri: android.net.Uri): Boolean = try {
        val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: error("no picture")
        val (x, y, s) = PlaylistMeta.cropSquare(src.width, src.height)
        val sq = android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createBitmap(src, x, y, s, s), 512, 512, true)
        File(dir(ctx), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg").outputStream().use { sq.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        gen.value++; true
    } catch (_: Throwable) { false }
    fun clear(ctx: Context, id: String) { File(dir(ctx), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg").delete(); gen.value++ }
}

/** A playlist / mix cover: the custom picture, else a 2x2 mosaic of 4 different covers, else one cover, else the placeholder. */
@Composable
fun CoverTile(id: String, tracks: List<Track>, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = BlazeShapes.small) {
    val ctx = LocalContext.current
    val g by PlaylistCovers.gen.collectAsState()
    val custom = remember(id, g) { if (id.isBlank()) "" else PlaylistCovers.path(ctx, id) }
    if (custom.isNotBlank()) { Art(Track("cover:$id", "", "", art = custom), modifier, shape, target = 480); return }
    val pick = remember(tracks) { CoverMosaic.pick(tracks) }
    if (pick.size == 4) {
        Column(modifier.clip(shape)) {
            Row(Modifier.weight(1f)) { Art(pick[0], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(2.dp), 160); Art(pick[1], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(2.dp), 160) }
            Row(Modifier.weight(1f)) { Art(pick[2], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(2.dp), 160); Art(pick[3], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(2.dp), 160) }
        }
    } else Art(pick.firstOrNull(), modifier, shape, target = 320)
}

/** The cover's dominant colour, or the theme accent when there's no usable picture ([CoverTint.of]). */
@Composable
fun rememberCoverTint(t: Track?): Color {
    val c = LocalBlaze.current
    val art = rememberArt(t, 64)
    return remember(art, c.accent) {
        val fallback = (c.accent.value shr 32).toInt()
        val px = art?.let { runCatching {
            val small = android.graphics.Bitmap.createScaledBitmap(it.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false), 16, 16, true)
            IntArray(256).also { a -> small.getPixels(a, 0, 16, 0, 0, 16, 16) } }.getOrNull() }
        Color(0xFF000000.toInt() or CoverTint.of(px, fallback))
    }
}

// ---------------------------------------------------------------- Home

@Composable
internal fun MusicHomePage(np: NowPlaying, openTile: (MusicHome.Tile) -> Unit, playList: (List<Track>, Int, Boolean) -> Unit, onDj: () -> Unit) {
    val c = LocalBlaze.current
    val songs by MediaLib.songs.collectAsState()
    val lists by MediaLib.playlists.collectAsState()
    val recent by MediaLib.recent.collectAsState()
    val liked by MediaLib.liked.collectAsState()
    val plays by MediaLib.plays.collectAsState()
    var chip by remember { mutableStateOf("all") }
    val byKey = remember(songs) { songs.associateBy { it.key } }
    val now = remember(plays.size) { System.currentTimeMillis() }
    val hour = remember(plays.size) { BlazeDj.hour() }
    val smart = remember(songs, plays.size, liked) { MusicSmart.smartLists(songs, plays, liked, now) }
    val tiles = remember(chip, songs, lists, smart, recent) { MusicHome.tiles(chip, songs, lists, smart, recent) }
    val mixes = remember(songs, plays.size) { MusicHome.mixes(songs, plays, now / 86_400_000L, hour) }
    val recentSongs = remember(songs, recent) { MusicHome.recentlyPlayed(songs, recent) }
    if (songs.isEmpty() && lists.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            FilterChips(MusicHome.chips, chip) { chip = it }
            EmptyState("No music yet", "Find songs in Search, or put music files on the phone.", mark = false)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
        item { FilterChips(MusicHome.chips, chip) { chip = it } }
        // 2-column grid of recent playlists (cover + name), 8 tiles.
        items(tiles.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> HomeTile(t, byKey, Modifier.weight(1f)) { openTile(t) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // The Blaze DJ tile.
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().clip(BlazeShapes.medium).glass(BlazeShapes.medium, GlassIntensity.Medium, shadow = false)
                .clickable(role = Role.Button, onClickLabel = "Start Blaze DJ") { onDj() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(BlazeShapes.pill).background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.9f), BlazeBrushTint.warm))),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Blaze DJ", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Songs from your library, picked for " + DjPlan.partOfDay(hour), color = c.sub, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = c.ink, modifier = Modifier.size(28.dp))
            }
        }
        if (mixes.isNotEmpty()) item {
            SectionTitle("Your mixes")
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(mixes, key = { it.id }) { m ->
                    Column(Modifier.width(150.dp).clip(BlazeShapes.small).clickable(role = Role.Button, onClickLabel = "Open ${m.name}") { openTile(m) }) {
                        CoverTile(m.id, m.keys.take(8).mapNotNull { byKey[it] }, Modifier.size(150.dp), BlazeShapes.small)
                        Text(m.sub.ifBlank { m.name }, color = c.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (recentSongs.isNotEmpty()) item {
            SectionTitle("Recently played")
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recentSongs, key = { "r-" + it.key }) { t ->
                    Column(Modifier.width(112.dp).clip(BlazeShapes.small).clickable(role = Role.Button, onClickLabel = "Play ${t.title}") {
                        MusicContext.set("Recently played"); playList(recentSongs, recentSongs.indexOf(t), false) }) {
                        Art(t, Modifier.size(112.dp), BlazeShapes.small, 240)
                        Text(t.title, color = c.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}

/** Blaze's own warm tint for gradients (not a brand colour). */
internal object BlazeBrushTint { val warm = Color(0xFFD6B48E) }

@Composable
private fun HomeTile(t: MusicHome.Tile, byKey: Map<String, Track>, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.height(56.dp).clip(BlazeShapes.xs).glass(BlazeShapes.xs, GlassIntensity.Low, shadow = false)
        .clickable(role = Role.Button, onClickLabel = "Open ${t.name}") { onClick() }, verticalAlignment = Alignment.CenterVertically) {
        CoverTile(if (t.kind == "playlist") t.id else "", t.keys.take(8).mapNotNull { byKey[it] }, Modifier.size(56.dp), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
        Text(t.name, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

// ---------------------------------------------------------------- Search

@Composable
internal fun MusicSearchPage(playList: (List<Track>, Int, Boolean) -> Unit, openTile: (MusicHome.Tile) -> Unit, onStore: () -> Unit, onVibe: (DjPlan.Vibe) -> Unit) {
    val c = LocalBlaze.current
    val songs by MediaLib.songs.collectAsState()
    val lists by MediaLib.playlists.collectAsState()
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("all") }
    val found = remember(songs, q) { LibraryGroups.search(songs, q) }
    val byKey = remember(songs) { songs.associateBy { it.key } }
    Column(Modifier.fillMaxSize()) {
        KitSearch(q, { q = it }, "Songs, artists, albums", Modifier.padding(horizontal = 14.dp).fillMaxWidth())
        if (q.trim().length < 1) {
            SectionTitle("Browse")
            val tiles = listOf(Triple("Online music", Color(0xFF5B6CFF), Icons.Rounded.Language)) +
                DjPlan.Vibe.values().filter { it != DjPlan.Vibe.AUTO }.mapIndexed { i, v -> Triple(v.label, listOf(Color(0xFF7C4DFF), Color(0xFF2E7DBA), Color(0xFFE0603A), Color(0xFF3D3A8C))[i % 4], Icons.Rounded.Headphones) }
            LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tiles.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, col, icon) ->
                            Box(Modifier.weight(1f).height(92.dp).clip(BlazeShapes.small).background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.55f))))
                                .clickable(role = Role.Button, onClickLabel = name) {
                                    if (name == "Online music") onStore() else DjPlan.Vibe.values().firstOrNull { it.label == name }?.let(onVibe)
                                }.padding(12.dp)) {
                                Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.BottomEnd).size(30.dp))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            return@Column
        }
        FilterChips(listOf("all" to "All", "songs" to "Songs", "artists" to "Artists", "albums" to "Albums", "playlists" to "Playlists"), cat) { cat = it }
        val artists = remember(found) { MusicHome.tiles("artists", found, emptyList(), emptyList(), emptyList(), 20) }
        val albums = remember(found) { MusicHome.tiles("albums", found, emptyList(), emptyList(), emptyList(), 20) }
        val pls = remember(lists, q) { lists.filter { it.name.contains(q.trim(), ignoreCase = true) }.map { MusicHome.Tile(it.id, it.name, it.keys, "playlist") } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
            if (found.isEmpty() && pls.isEmpty()) item {
                Column(Modifier.padding(16.dp)) {
                    Text("Nothing in your library", color = c.sub, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    PillButton("Search online", primary = true) { onStore() }
                }
            }
            if (cat in listOf("all", "artists")) items(artists.take(if (cat == "all") 3 else 20), key = { it.id }) { t -> ResultRow(t, byKey, round = true) { openTile(t) } }
            if (cat in listOf("all", "albums")) items(albums.take(if (cat == "all") 3 else 20), key = { it.id }) { t -> ResultRow(t, byKey) { openTile(t) } }
            if (cat in listOf("all", "playlists")) items(pls, key = { it.id }) { t -> ResultRow(t, byKey) { openTile(t) } }
            if (cat in listOf("all", "songs")) items(found.take(if (cat == "all") 30 else 200), key = { "s-" + it.key }) { t ->
                TrackRow(t, onClick = { MusicContext.set("Search"); playList(found, found.indexOf(t), false) })
            }
        }
    }
}

@Composable
private fun ResultRow(t: MusicHome.Tile, byKey: Map<String, Track>, round: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${t.name}") { onClick() }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CoverTile(if (t.kind == "playlist") t.id else "", t.keys.take(8).mapNotNull { byKey[it] }, Modifier.size(52.dp), if (round) BlazeShapes.pill else BlazeShapes.xs)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(t.name, color = c.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when (t.kind) { "artist" -> "Artist"; "album" -> "Album" + if (t.sub.isNotBlank()) " \u00b7 ${t.sub}" else ""; else -> "Playlist" }, color = c.sub, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** A track row: art, title, artist and a ⋮ menu. */
@Composable
internal fun TrackRow(t: Track, playing: Boolean = false, menu: List<Pair<String, () -> Unit>> = emptyList(), onClick: () -> Unit) {
    val c = LocalBlaze.current
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Play ${t.title}") { onClick() }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Art(t, Modifier.size(48.dp), BlazeShapes.xs, 160)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(t.title, color = if (playing) c.accent else c.ink, fontSize = 15.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.artist.isNotBlank()) Text(t.artist, color = c.sub, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu.isNotEmpty()) Box {
            RoundIcon(Icons.Rounded.MoreVert, "More for ${t.title}", tint = c.sub) { open = true }
            BlazeMenu(open, { open = false }, menu.map { (l, f) -> l to { open = false; f() } })
        }
    }
}

// ---------------------------------------------------------------- Playlist view

/**
 * A playlist, mix, album or artist: big centred cover, a header tinted from the cover blending into the Blaze background,
 * title / owner / total time, icon actions (offline, share, shuffle) + a big round Play, chips (Add, Edit, Name & details,
 * Sort; real playlists only) and the tracks.
 */
@Composable
internal fun PlaylistPage(tile: MusicHome.Tile, np: NowPlaying, playList: (List<Track>, Int, Boolean) -> Unit, onBack: () -> Unit,
                          onQueueNext: (Track) -> Unit, onAddSongs: (String) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by MediaLib.songs.collectAsState()
    val lists by MediaLib.playlists.collectAsState()
    val byKey = remember(songs) { songs.associateBy { it.key } }
    val real = tile.kind == "playlist"
    val pl = lists.firstOrNull { it.id == tile.id }
    val name = pl?.name ?: tile.name
    var sort by remember(tile.id) { mutableStateOf(PlaylistMeta.Sort.CUSTOM) }
    var shuffle by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }
    val base = remember(pl, tile, byKey) { (pl?.keys ?: tile.keys).mapNotNull { byKey[it] } }
    val tracks = remember(base, sort) { PlaylistMeta.sort(base, sort) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { PlaylistCovers.save(ctx, tile.id, uri) }
            if (!ok) Toast.makeText(ctx, "Couldn't use that picture.", Toast.LENGTH_SHORT).show()
        }
    }
    val tint = rememberCoverTint(tracks.firstOrNull())
    androidx.activity.compose.BackHandler { onBack() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint.copy(alpha = if (c.dark) 0.55f else 0.35f), Color.Transparent)))) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { RoundIcon(Icons.Rounded.KeyboardArrowDown, "Back", onClick = onBack) }
                    CoverTile(if (real) tile.id else "", tracks, Modifier.size(220.dp), BlazeShapes.small)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(name, color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    AccountBadgeSmall()
                    Text(listOf(if (tile.kind == "mix" || tile.kind == "smart") "Blaze" else "You", PlaylistMeta.total(tracks)).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                        color = c.sub, fontSize = 12.5.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val allSaved = tracks.isNotEmpty() && tracks.all { it.source != "stream" }
                    RoundIcon(if (allSaved) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (allSaved) "On this phone" else "Available offline", tint = if (allSaved) c.accent else c.sub) {
                        Toast.makeText(ctx, if (allSaved) "Every song here is on your phone." else "Download songs from Search to play them offline.", Toast.LENGTH_SHORT).show()
                    }
                    RoundIcon(Icons.Rounded.Share, "Share", tint = c.sub) {
                        val text = name + "\n" + tracks.take(50).joinToString("\n") { listOf(it.title, it.artist).filter { s -> s.isNotBlank() }.joinToString(" - ") }
                        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    RoundIcon(Icons.Rounded.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", tint = if (shuffle) c.accent else c.sub) { shuffle = !shuffle }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(56.dp).clip(BlazeShapes.pill).background(c.ink).clickable(role = Role.Button, onClickLabel = "Play $name") {
                        if (tracks.isNotEmpty()) { MusicContext.set(name); playList(tracks, if (shuffle) tracks.indices.random() else 0, shuffle) }
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = c.paper, modifier = Modifier.size(32.dp)) }
                }
                if (real) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChip("Add", Icons.Rounded.Add) { onAddSongs(tile.id) }
                    SmallChip(if (editing) "Done" else "Edit", Icons.Rounded.Edit) { editing = !editing }
                    SmallChip("Name & details", Icons.Rounded.Info) { naming = name }
                    Box {
                        SmallChip(sort.label, Icons.Rounded.SwapVert) { sortMenu = true }
                        BlazeMenu(sortMenu, { sortMenu = false }, PlaylistMeta.Sort.values().map { s -> s.label to { sort = s; sortMenu = false } })
                    }
                }
            }
        }
        if (tracks.isEmpty()) item { EmptyState("No songs here yet", if (real) "Tap Add to put songs in it." else "", mark = false) }
        items(tracks.size, key = { "pt-" + it + tracks[it].key }) { i ->
            val t = tracks[i]
            val idx = pl?.keys?.indexOf(t.key) ?: -1
            if (editing && real && idx >= 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TrackRow(t) {} }
                RoundIcon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = c.sub) { if (idx > 0) MediaLib.setPlaylists(PlaylistOps.move(lists, tile.id, idx, idx - 1, System.currentTimeMillis())) }
                RoundIcon(Icons.Rounded.RemoveCircleOutline, "Remove from playlist", tint = c.sub) { MediaLib.setPlaylists(PlaylistOps.removeAt(lists, tile.id, idx, System.currentTimeMillis())) }
            } else TrackRow(t, playing = np.item?.mediaId == t.key, menu = buildList {
                add("Play next" to { onQueueNext(t) })
                if (real && idx >= 0) add("Remove from playlist" to { MediaLib.setPlaylists(PlaylistOps.removeAt(lists, tile.id, idx, System.currentTimeMillis())) })
            }) { MusicContext.set(name); playList(tracks, i, false) }
        }
    }
    naming?.let { cur ->
        var text by remember { mutableStateOf(cur) }
        com.blaze.assistant.ui.BlazeDialog(onDismiss = { naming = null }, title = "Name & details", confirm = "Save", onConfirm = {
            val n = PlaylistOps.cleanName(text)
            if (n.isNotBlank()) MediaLib.setPlaylists(PlaylistOps.rename(lists, tile.id, n, System.currentTimeMillis()))
            naming = null
        }) {
            BlazeTextField(text, { text = it }, "Playlist name")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip("Choose picture", Icons.Rounded.Image) { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (PlaylistCovers.path(ctx, tile.id).isNotBlank()) SmallChip("Use song covers", Icons.Rounded.GridView) { PlaylistCovers.clear(ctx, tile.id) }
            }
        }
    }
}

@Composable
private fun AccountBadgeSmall() {
    val c = LocalBlaze.current
    val name = remember { runCatching { com.blaze.assistant.agent.Settings.userName }.getOrDefault("") }
    Box(Modifier.size(22.dp).clip(BlazeShapes.pill).background(c.faint), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "", color = c.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SmallChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.clip(BlazeShapes.pill).glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false).clickable(role = Role.Button, onClickLabel = text) { onClick() }
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.ink, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Create

@Composable
internal fun MusicCreatePage(onCreated: (MusicHome.Tile) -> Unit, onAskBlaze: (String) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<android.net.Uri?>(null) }
    var ask by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) image = uri }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("New playlist", Modifier.offset(x = (-16).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp).clip(BlazeShapes.small).glass(BlazeShapes.small, GlassIntensity.Low, shadow = false)
                .clickable(role = Role.Button, onClickLabel = "Choose a picture") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center) {
                Icon(if (image == null) Icons.Rounded.AddPhotoAlternate else Icons.Rounded.CheckCircle, contentDescription = null, tint = c.ink, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            BlazeTextField(name, { name = it }, "Name", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Create", primary = true) {
            val lists = MediaLib.playlists.value
            val n = PlaylistOps.uniqueName(lists, PlaylistOps.cleanName(name).ifBlank { "My playlist" })
            val id = MediaLib.newPlaylistId()
            MediaLib.setPlaylists(PlaylistOps.create(lists, n, id, System.currentTimeMillis()))
            val img = image
            scope.launch {
                if (img != null) withContext(Dispatchers.IO) { PlaylistCovers.save(ctx, id, img) }
                name = ""; image = null
                onCreated(MusicHome.Tile(id, n, emptyList(), "playlist"))
            }
        }
        SectionTitle("Ask Blaze", Modifier.offset(x = (-16).dp))
        BlazeTextField(ask, { ask = it }, "Make a chill playlist", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Make a chill playlist", "Songs for a workout", "Focus music for studying").forEach { s -> SmallChip(s, Icons.Rounded.AutoAwesome) { onAskBlaze(s) } }
        }
        Spacer(Modifier.height(10.dp))
        PillButton("Ask Blaze", primary = false) { onAskBlaze(ask.ifBlank { "Make a chill playlist" }) }
        Spacer(Modifier.height(150.dp))
    }
}

// ---------------------------------------------------------------- Blaze DJ sheet

/** Blaze DJ controls: start / stop, vibe chips, More like this, spoken intros on or off. */
@Composable
internal fun DjSheet(onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val on by BlazeDj.on.collectAsState()
    val vibe by BlazeDj.vibe.collectAsState()
    var voice by remember { mutableStateOf(BlazeDj.voiceOn(ctx)) }
    val low = remember { BlazeDj.lowPower(ctx) }
    fun svc(block: (MusicService) -> Unit) {
        val s = MusicService.current
        if (s == null) { ctx.startService(Intent(ctx, MusicService::class.java)); Toast.makeText(ctx, "Starting the player. Tap again in a moment.", Toast.LENGTH_SHORT).show() } else block(s)
    }
    com.blaze.assistant.ui.BlazeDialog(onDismiss = onDismiss, title = "Blaze DJ", confirm = if (on) "Stop DJ" else "Start", onConfirm = {
        if (on) svc { it.startDj(false) } else svc { it.djFresh(null) }
        onDismiss()
    }) {
        Text("Plays songs from your library, picked from what you play and like, the time of day and the vibe.", color = c.sub, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DjPlan.Vibe.values().forEach { v ->
                val sel = v == vibe
                Box(Modifier.clip(BlazeShapes.pill).background(if (sel) c.ink else c.faint).clickable(role = Role.Button, onClickLabel = v.label) {
                    BlazeDj.setVibe(ctx, v); if (on) svc { it.djFresh(v) }
                }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text(v.label, color = if (sel) c.paper else c.ink, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("More like this", Icons.Rounded.ThumbUp) { svc { it.djMoreLikeThis() } }
            SmallChip("Change vibe", Icons.Rounded.Autorenew) {
                val next = DjPlan.Vibe.values()[(vibe.ordinal + 1) % DjPlan.Vibe.values().size]
                BlazeDj.setVibe(ctx, next); svc { it.djFresh(next) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable(role = Role.Switch, onClickLabel = "Spoken intros") { voice = !voice; BlazeDj.setVoice(ctx, voice) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (voice) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VoiceOverOff, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
            Text(if (low) "Spoken intros (off while battery saver is on)" else if (voice) "Spoken intros: on" else "Spoken intros: off", color = c.ink, fontSize = 14.sp,
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}

// ---------------------------------------------------------------- now playing extras

/** The lyrics card under the player: only when lyrics are on the phone (a .lrc next to the song, or saved earlier). */
@Composable
internal fun LyricsCard(t: Track, pos: Long, onOpen: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val res by produceState<Lyrics.Result?>(null, t.key) { value = withContext(Dispatchers.IO) { Lyrics.local(ctx.filesDir, t) } }
    val r = res ?: return
    if (r.empty || r.instrumental) return
    val lines: List<String> = if (r.synced.isNotEmpty()) {
        val i = Lrc.current(r.synced, pos).coerceAtLeast(0)
        r.synced.drop(i).take(4).map { it.text }
    } else r.plain.lines().filter { it.isNotBlank() }.take(4)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(BlazeShapes.medium).glass(BlazeShapes.medium, GlassIntensity.Medium, shadow = false)
        .clickable(role = Role.Button, onClickLabel = "Open lyrics") { onOpen() }.padding(16.dp)) {
        Text("Lyrics", color = c.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        lines.forEachIndexed { i, l -> Text(l, color = if (i == 0) c.ink else c.sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2) }
    }
}

/** Open the system's output switcher (Bluetooth, speakers); falls back to Bluetooth settings. */
fun openOutputPicker(ctx: Context) {
    val tries = listOf(Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", ctx.packageName),
        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

/** A blurred-looking art backdrop: the cover decoded tiny (64 px) and stretched, faint over the theme. Costs one small bitmap. */
@Composable
internal fun ArtBackdrop(t: Track?, modifier: Modifier) {
    val img = rememberArt(t, 64) ?: return
    Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier, alpha = 0.32f)
}
