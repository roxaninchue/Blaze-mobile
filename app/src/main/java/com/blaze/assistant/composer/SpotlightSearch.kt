package com.blaze.assistant.composer

/**
 * 0.47 Spotlight v2 search (pure; SpotlightSearchTest). Everything comes from the [SelfMap] plus the user's media library.
 *
 *  - empty field → a short curated list (no category pills);
 *  - "!cmd"      → a terminal command for the distro picked on the right of the field (TERMINAL mode);
 *  - "@view arg" → views; a known view + Enter opens its inline preview inside Spotlight ("@browser google.com/food");
 *  - plain text  → map matches; a view shows twice: "Views > Terminal" (full view, leaves Home) and "Terminal"
 *                  (inline preview, stays on Home); fuzzy library hits "Music > Library > {song}" / "Video > Library > {title}";
 *                  models ("inf" → Blaze Inferno · Smartest).
 * Never an empty list (no "nothing found" row): every query ends with Browse, Music, Video and "Ask Blaze about '<q>'" (sends into chat).
 */
object SpotlightSearch {
    enum class Icon { VIEW, TERMINAL, MUSIC, VIDEO, BROWSER, SETTINGS, MODEL, ACTION, CHAT, SEARCH, PREVIEW }
    enum class Mode { CURATED, PLAIN, VIEWS, TERMINAL }

    sealed class Act {
        /** Open the full view (leaves Home). */
        data class OpenView(val view: String, val target: String = "") : Act()
        /** Inline preview inside Spotlight (stays on Home). */
        data class Preview(val view: String, val arg: String = "") : Act()
        data class RunAction(val action: String) : Act()
        data class PickModel(val family: String) : Act()
        data class PlaySong(val key: String) : Act()
        data class PlayVideo(val key: String) : Act()
        data class Browse(val query: String) : Act()
        data class MusicSearch(val query: String) : Act()
        data class VideoSearch(val query: String) : Act()
        data class AskBlaze(val query: String) : Act()
        data class RunCommand(val command: String, val distro: String) : Act()
        /** Replace the field with [text] (e.g. "@browser "), keep Spotlight open. */
        data class Complete(val text: String) : Act()
    }

    data class Row(val id: String, val icon: Icon, val title: String, val subtitle: String, val act: Act, val score: Int = 0)

    /** A library item as search needs it. */
    data class Media(val key: String, val title: String, val artist: String = "")

    /** A Linux distro for the "!" picker: [installed] shows a tick in a circle, else a download icon. */
    data class Distro(val id: String, val name: String, val installed: Boolean)

    data class Result(val mode: Mode, val rows: List<Row>, val distros: List<Distro> = emptyList())

    const val MAX_ROWS = 12
    /** "@" lists every view (the panel scrolls). */
    const val VIEWS_MAX = 60
    const val LIBRARY_MAX = 3
    /** Library fuzzy hits need at least this score (one typo / word prefix; plain letters-in-order is too loose for songs). */
    const val LIBRARY_MIN = 45
    const val MAP_MIN = 30

    fun search(query: String, map: List<SelfMap.Entry> = SelfMap.default, songs: List<Media> = emptyList(),
               videos: List<Media> = emptyList(), distros: List<Distro> = emptyList(), distro: String = "alpine"): Result {
        val q = query.trim()
        return when {
            q.isEmpty() -> Result(Mode.CURATED, curated(map))
            q.startsWith("!") -> terminal(q.removePrefix("!").trim(), distros, distro)
            q.startsWith("@") -> views(q.removePrefix("@"), map)
            else -> Result(Mode.PLAIN, plain(q, map, songs, videos))
        }
    }

    /** The short list shown before typing. */
    fun curated(map: List<SelfMap.Entry>): List<Row> {
        val out = ArrayList<Row>()
        SelfMap.find("action:chat", map)?.let { out += actionRow(it, 0) }
        for (id in listOf("music", "video", "browser")) map.firstOrNull { it.kind == SelfMap.Kind.VIEW && it.target == id }?.let { out += fullRow(it, 0) }
        map.firstOrNull { it.kind == SelfMap.Kind.VIEW && it.target == "terminal" }?.let { out += previewRow(it, 0) }
        map.firstOrNull { it.kind == SelfMap.Kind.VIEW && it.target == "settings" }?.let { out += fullRow(it, 0) }
        return out.ifEmpty { fallbacks("") }
    }

    fun terminal(cmd: String, distros: List<Distro>, distro: String): Result {
        val d = distros.firstOrNull { it.id == distro } ?: distros.firstOrNull() ?: Distro(distro, distro.replaceFirstChar { it.uppercase() }, false)
        val rows = ArrayList<Row>()
        if (cmd.isNotEmpty()) {
            rows += Row("cmd", Icon.TERMINAL, "! $cmd", if (d.installed) "Run in ${d.name} \u00b7 here on Home" else "${d.name} isn't installed \u00b7 tap to set it up",
                if (d.installed) Act.RunCommand(cmd, d.id) else Act.OpenView("terminal"), 100)
            rows += Row("cmd:ask", Icon.CHAT, "Ask Blaze to run it", "Blaze runs \u201c$cmd\u201d and explains the result", Act.AskBlaze("Run this in the terminal (${d.name}): $cmd"), 50)
        } else {
            rows += Row("cmd:hint", Icon.TERMINAL, "Type a command", "Runs in ${d.name}" + (if (d.installed) "" else " (not installed yet)"), Act.Complete("!"), 100)
        }
        rows += Row("view:terminal:full", Icon.TERMINAL, "Views > Terminal", "Open the full terminal", Act.OpenView("terminal"), 40)
        return Result(Mode.TERMINAL, rows, distros)
    }

    fun views(rest: String, map: List<SelfMap.Entry>): Result {
        val token = rest.substringBefore(' ').trim().lowercase()
        val arg = if (rest.contains(' ')) rest.substringAfter(' ').trim() else ""
        val views = map.filter { it.kind == SelfMap.Kind.VIEW }
        val exact = views.firstOrNull { it.target == token } ?: views.firstOrNull { e -> e.keywords.any { it.lowercase() == token } }
        val rows = ArrayList<Row>()
        if (exact != null) {
            if (exact.preview) rows += Row("at:${exact.target}:preview", iconFor(exact.target), exact.name(),
                if (arg.isNotEmpty()) previewSub(exact.target, arg) else "Preview here \u00b7 stays on Home", Act.Preview(exact.target, arg), 100)
            rows += Row("at:${exact.target}:full", iconFor(exact.target), "Views > ${exact.name()}", "Open the full view" + (if (arg.isNotEmpty()) " \u00b7 $arg" else ""),
                Act.OpenView(exact.target, arg), 90)
        }
        // "@" alone: the views with an inline preview first (@browser, @music, @video, @terminal, @settings, …), then the rest.
        val list = if (token.isEmpty()) views.sortedByDescending { it.preview } else views.map { it to maxOf(Fuzzy.score(token, it.target), Fuzzy.best(token, listOf(it.title) + it.keywords)) }
            .filter { it.second >= MAP_MIN }.sortedByDescending { it.second }.map { it.first }
        for (e in list) if (e != exact && rows.size < VIEWS_MAX)
            rows += Row("at:${e.target}", iconFor(e.target), "@${e.target}", e.title + " \u00b7 " + e.subtitle, Act.Complete("@${e.target} "), 60)
        if (rows.isEmpty()) rows += fallbacks("@$rest".trim())
        return Result(Mode.VIEWS, rows)
    }

    fun plain(q: String, map: List<SelfMap.Entry>, songs: List<Media>, videos: List<Media>): List<Row> {
        val hits = ArrayList<Row>()
        for (e in map) {
            val s = Fuzzy.best(q, listOf(e.title) + e.keywords + listOf(e.subtitle))
            if (s < MAP_MIN) continue
            when (e.kind) {
                SelfMap.Kind.VIEW -> {
                    hits += fullRow(e, s)
                    if (e.preview) hits += previewRow(e, s - 1)
                }
                SelfMap.Kind.ACTION -> hits += actionRow(e, s)
                SelfMap.Kind.SETTING -> hits += Row(e.id, Icon.SETTINGS, "Settings > ${e.title}", e.subtitle, Act.OpenView(e.target), s - 2)
                SelfMap.Kind.MODEL -> hits += Row(e.id, Icon.MODEL, e.title, "Model \u00b7 tap to use", Act.PickModel(e.target), s)
            }
        }
        hits += library(q, songs, "Music", Icon.MUSIC) { Act.PlaySong(it) }
        hits += library(q, videos, "Video", Icon.VIDEO) { Act.PlayVideo(it) }
        val top = hits.sortedByDescending { it.score }.distinctBy { it.id }.take(MAX_ROWS - 4)
        return top + fallbacks(q)
    }

    private fun library(q: String, items: List<Media>, app: String, icon: Icon, act: (String) -> Act): List<Row> =
        items.asSequence().map { it to Fuzzy.best(q, listOf(it.title, it.artist)) }.filter { it.second >= LIBRARY_MIN }
            .sortedByDescending { it.second }.take(LIBRARY_MAX)
            .map { (m, s) -> Row("lib:${app.lowercase()}:${m.key}", icon, "$app > Library > ${m.title}", m.artist.ifBlank { "In your library" }, act(m.key), s - 3) }.toList()

    /** Never an empty list: Browse, Music, Video and Ask Blaze, always in this order at the end. */
    fun fallbacks(q: String): List<Row> {
        val shown = if (q.isBlank()) "anything" else "\u2018$q\u2019"
        return listOf(
            Row("fb:browse", Icon.BROWSER, "Browse $shown", "Search the web in a preview", Act.Browse(q), -1),
            Row("fb:music", Icon.MUSIC, "Music: search $shown", "Find songs in Blaze Music", Act.MusicSearch(q), -2),
            Row("fb:video", Icon.VIDEO, "Video: search $shown", "Find videos in Blaze Video", Act.VideoSearch(q), -3),
            Row("fb:ask", Icon.CHAT, "Ask Blaze about \u2018$q\u2019".let { if (q.isBlank()) "Ask Blaze" else it }, "Sends it into the chat", Act.AskBlaze(q), -4),
        )
    }

    private fun SelfMap.Entry.name() = title

    private fun fullRow(e: SelfMap.Entry, s: Int) = Row("${e.id}:full", iconFor(e.target), "Views > ${e.title}", "Open the full view", Act.OpenView(e.target), s)
    private fun previewRow(e: SelfMap.Entry, s: Int) = Row("${e.id}:preview", iconFor(e.target), e.title, "Preview here \u00b7 stays on Home", Act.Preview(e.target), s)
    private fun actionRow(e: SelfMap.Entry, s: Int) = Row(e.id, if (e.target == "chat") Icon.CHAT else Icon.ACTION, e.title, e.subtitle, Act.RunAction(e.target), s)

    private fun previewSub(view: String, arg: String) = when (view) {
        "browser" -> "Open ${SpotUrl.of(arg).removePrefix("https://")} here"
        else -> "Preview \u00b7 $arg"
    }

    fun iconFor(view: String): Icon = when (view) {
        "terminal", "jobs" -> Icon.TERMINAL
        "music" -> Icon.MUSIC
        "video" -> Icon.VIDEO
        "browser", "phonebrowser" -> Icon.BROWSER
        "settings", "look", "voice", "brain", "router", "sync", "logins", "secrets", "control", "proactive" -> Icon.SETTINGS
        else -> Icon.VIEW
    }
}

/** Address-bar text → a URL or a web search (same rules as the browser's own bar). Pure. */
object SpotUrl {
    fun of(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
    }
}
