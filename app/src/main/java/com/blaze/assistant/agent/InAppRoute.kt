package com.blaze.assistant.agent

/**
 * v0.45.6 (phone 21:27): "play music" went to the model, which ran `music_ops play` as a shell command (approval card),
 * then fell back to screen control and opened BlazeFX. Requests Blaze can do itself are now routed in-app FIRST, before
 * the model and long before screen control:
 *  - Blaze Music transport ("play music", "pause the music", "next song", "previous track", "what's playing") ->
 *    music_ops (no approval: safe local actions);
 *  - Blaze's own screens ("open Blaze Music", "open projects", "show my gallery") -> open_view.
 * [toolFor] does the same for a shell line that names an in-app tool ("music_ops play"), so it runs as that tool.
 * Anything that names another app or service (Spotify, YouTube…) is left to the normal turn. Pure; InAppRouteTest.
 */
object InAppRoute {
    sealed class Route {
        data class Music(val action: String) : Route()
        data class View(val id: String) : Route()
        /** 0.46.1: a card on the chat Stage (the browser card), shown in place, never a screen change. */
        data class Stage(val kind: String) : Route()
        /** 0.46.2 Movies on the Stage video card: action play | search | continue, with the title / search words. */
        data class Movie(val action: String, val query: String = "") : Route()
    }

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val otherApp = r("""\b(spotify|youtube|yt music|apple music|deezer|boomplay|audiomack|soundcloud|tidal|amazon music|on my (?:pc|computer|laptop|tv))\b""")

    private val play = r("""^(?:play|start|put on|turn on)(?: (?:some|the|my))? (?:music|songs?|tunes)(?: (?:in|on) blaze(?: music)?)?$|^play (?:something|a song)$|^(?:resume|continue|unpause)(?: (?:the|my))? (?:music|song|playback|track)$|^play blaze music$""")
    private val pause = r("""^(?:pause|stop|turn off)(?: (?:the|my))? (?:music|song|playback|track)$""")
    private val next = r("""^(?:next|skip)(?: (?:the|this))? (?:song|track)$|^(?:play )?(?:the )?next (?:song|track)$|^skip(?: it)?$""")
    private val prev = r("""^(?:play )?(?:the )?(?:previous|last) (?:song|track)$|^go back a (?:song|track)$""")
    private val now = r("""^(?:what'?s|what is) (?:playing|this song)(?: now)?$|^what song is (?:this|playing)$""")
    private val view = r("""^(?:open|show|go to|take me to|switch to)(?: me)?(?: (?:the|my))? (blaze music|music player|music|projects|gallery|terminal|blaze settings|memory|schedule|design(?: studio)?|home)(?: (?:screen|page|tab|view))?(?: in blaze)?$""")

    // 0.46.1 (report: "pull up browser card" opened the full-screen browser): the browser is a Stage card by default; the full
    // Browser screen only when they say full screen / full view.
    private val browserCard = r("""^(?:(?:can you|could you|pls)\s+)?(?:pull up|bring up|open|show|put up|launch|start|give me|get|show me)(?: (?:up|me))?(?: (?:the|my|a|your))?(?: blaze)? (?:browser|web browser|browser card|browser widget|internet)(?: card| widget| here| on (?:the )?(?:stage|screen|chat))?(?: for me)?$|^browser(?: card)?$""")
    private val browserFull = r("""^(?:pull up|bring up|open|show|launch|start|go to|switch to|take me to)(?: (?:the|my|a))?(?: blaze)? (?:web )?browser(?: card)? (?:in |on )?(?:full ?screen|full view|full page)$|^(?:open|show|make)(?: the)? browser (?:full ?screen|big|in full view)$|^full ?screen browser$""")

    // 0.46.2 Movies ("play the movie Nosferatu", "watch Sintel", "search movies about trains", "continue watching").
    private val movieContinue = r("""^(?:continue|resume|keep) watching(?: (?:my|the))?(?: (?:movie|film|show|video))?$|^(?:continue|resume) (?:my|the) (?:movie|film)$|^pick up (?:my|the) (?:movie|film)$""")
    private val movieSearch = r("""^(?:search|find|look up|look for|show me)(?: for)?(?: (?:free|public[- ]domain|some|old|classic))? (?:movies?|films?)(?: (?:about|for|like|with|called|named|on))? (.+)$|^(?:search|find) (?:the )?(?:movie|film) (.+)$""")
    private val moviePlay = r("""^(?:play|watch|put on|stream|start)(?: (?:the|a))? (?:movie|film) (.+)$|^(?:play|watch|put on|stream)(?: (?:the|a))? (.+?) (?:movie|film)$|^watch (?!(?:out|this|that|it|tv|later|me|youtube|videos?)\b)(.+)$""")

    fun clean(text: String): String = text.trim().lowercase()
        .replace(Regex("[?.!,]+"), " ").replace(Regex("\\s+"), " ").trim()
        .removePrefix("hey blaze ").removePrefix("blaze ").removePrefix("please ").removeSuffix(" please").removeSuffix(" blaze").trim()

    /** The in-app route for a request, or null (the normal turn handles it). */
    fun match(text: String): Route? {
        val t = clean(text)
        if (t.isEmpty() || t.length > 60 || otherApp.containsMatchIn(t)) return null
        return when {
            play.matches(t) -> Route.Music("play")
            pause.matches(t) -> Route.Music("pause")
            next.matches(t) -> Route.Music("next")
            prev.matches(t) -> Route.Music("previous")
            now.matches(t) -> Route.Music("now")
            movieContinue.matches(t) -> Route.Movie("continue")
            movieSearch.matchEntire(t) != null -> Route.Movie("search", movieSearch.matchEntire(t)!!.groupValues.drop(1).first { it.isNotBlank() }.trim())
            moviePlay.matchEntire(t) != null -> Route.Movie("play", moviePlay.matchEntire(t)!!.groupValues.drop(1).first { it.isNotBlank() }.trim())
            browserFull.matches(t) -> Route.View("browser")
            browserCard.matches(t) -> Route.Stage("browser")
            else -> view.matchEntire(t)?.groupValues?.get(1)?.let { v ->
                Route.View(when {
                    v.contains("music") -> "music"
                    v == "blaze settings" -> "settings"
                    v.startsWith("design") -> "design"
                    else -> v
                })
            }
        }
    }

    /** In-app tools a model sometimes types as a shell command. */
    val SHELL_ALIASES = setOf("music_ops", "open_view")

    /**
     * A shell line that is really an in-app tool call ("music_ops play", "music_ops next", "open_view music"): the tool and
     * its arguments, or null. Only one simple command (no pipes, no ; or &&).
     */
    fun toolFor(command: String): Pair<String, Map<String, String>>? {
        val c = command.trim()
        if (c.isEmpty() || Regex("""[;&|`$<>]""").containsMatchIn(c)) return null
        val w = c.split(Regex("\\s+"))
        val tool = w[0].substringAfterLast('/')
        if (tool !in SHELL_ALIASES) return null
        val arg = w.drop(1).map { it.trim('"', '\'').removePrefix("--action=").removePrefix("action=").removePrefix("--view=").removePrefix("view=") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("-") } ?: return null
        return when (tool) {
            "music_ops" -> tool to mapOf("action" to arg.lowercase())
            else -> tool to mapOf("view" to arg.lowercase())
        }
    }
}
