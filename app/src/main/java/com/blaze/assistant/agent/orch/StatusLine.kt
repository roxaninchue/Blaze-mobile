package com.blaze.assistant.agent.orch

/**
 * 0.47.0 the status pill's words (spec §3.2): ONE neutral live line of what Blaze is doing right now ("Searching flights",
 * "Reading 3 pages", "Opening Settings"), from the orchestrator's events and the tools the specialists run. No internal
 * words (model, brain, specialist, orchestrator, agent, tool names, provider names, "Hark"), no trailing period, at most
 * [MAX] characters before the pill's ticker / ellipsis takes over. [first] gives the line shown within 300 ms of the tap,
 * from the text alone (no network). The expanded list is [Steps]. Pure (StatusLineTest).
 */
object StatusLine {
    const val MAX = 60
    const val FIRST_LINE_BUDGET_MS = 300L

    private val jargon = Regex("""(?i)\b(hark|model|brain|llm|specialist|sub-?agent|orchestrat\w*|agent|tool|provider|gateway|route|routing|token|prompt|json|api|nous|opencode|nvidia|nemotron|jev|system one|thinking)\b""")

    /** A clean line or null (dropped rather than half-cleaned). */
    fun clean(s: String): String? {
        var t = s.replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ', '\u2026')
        if (t.isEmpty() || jargon.containsMatchIn(t) || Regex("[{}\\[\\]<>`|\\\\=;$#@]|\\b[a-z]+_[a-z0-9_]+\\b|https?://").containsMatchIn(t)) return null
        t = t.replaceFirstChar { it.uppercase() }
        return t
    }

    /** Pill line for a tool a specialist is running (plain verb + object). */
    fun forTool(tool: String, args: org.json.JSONObject = org.json.JSONObject(), pages: Int = 0): String {
        val q = args.optString("query", args.optString("q")).trim().take(36)
        val app = args.optString("app", args.optString("package")).substringAfterLast('.').trim().replaceFirstChar { it.uppercase() }
        return when (tool) {
            "web_search" -> if (q.isNotBlank()) "Searching ${q.trimEnd('?')}" else "Searching the web"
            "fetch_page", "browser_read" -> if (pages > 1) "Reading $pages pages" else "Reading a page"
            "browser_open" -> "Opening a website"
            "browser_click", "browser_type", "browser_select" -> "Filling in the page"
            "open_app", "phone_open" -> if (app.isNotBlank()) "Opening $app" else "Opening the app"
            "open_settings_page", "device_setting" -> "Opening Settings"
            "read_screen", "phone_read", "look_at_screen", "phone_screenshot" -> "Checking the screen"
            "tap", "phone_act", "do_actions", "run_plan", "type_text", "scroll" -> "Working in ${app.ifBlank { "the app" }}"
            "send_sms", "send_text", "notif_reply", "reply_to_notification" -> "Preparing the message"
            "gmail_send", "email" -> "Preparing the email"
            "set_alarm" -> "Setting the alarm"
            "set_timer" -> "Setting a timer"
            "create_routine", "schedule_command" -> "Setting a reminder"
            "add_calendar_event", "gcal_create" -> "Adding to your calendar"
            "calendar_events", "gcal_events" -> "Checking your calendar"
            "file_read", "file_list", "file_search" -> "Looking through files"
            "file_write", "file_edit" -> "Saving the file"
            "run_shell", "run_code", "termux_run", "git" -> "Running the code"
            "recall_memory", "memory_search", "search_past_chats" -> "Checking what I know"
            "remember", "memory_save" -> "Saving that for later"
            "media_control", "spotify_play", "spotify_control" -> "Starting playback"
            "generate_image" -> "Making the image"
            else -> "Working on it"
        }.let { clean(it) ?: "Working on it" }
    }

    /** The line before anything else has happened (≤ 300 ms after the tap): from the request's words only. */
    fun first(text: String): String {
        val t = text.lowercase()
        val app = Regex("""\bopen\s+([a-z][a-z0-9 ]{1,20}?)(?:\s+and\b|[,.!?]|$)""").find(t)?.groupValues?.get(1)?.trim()
        return when {
            app != null && app.isNotBlank() -> "Opening ${app.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }}"
            Regex("\\b(flights?)\\b").containsMatchIn(t) -> "Searching flights"
            Regex("\\b(hotels?|stay)\\b").containsMatchIn(t) -> "Searching hotels"
            Regex("\\b(settings|wifi|wi-fi|bluetooth|brightness)\\b").containsMatchIn(t) -> "Opening Settings"
            Regex("\\b(alarm|wake me)\\b").containsMatchIn(t) -> "Setting the alarm"
            Regex("\\b(remind|reminder)\\b").containsMatchIn(t) -> "Setting a reminder"
            Regex("\\b(text|message|whatsapp|sms|email)\\b").containsMatchIn(t) -> "Preparing the message"
            Regex("\\b(code|bug|script|test|compile)\\b").containsMatchIn(t) -> "Looking at the code"
            Regex("\\b(play|song|music|playlist)\\b").containsMatchIn(t) -> "Finding the music"
            Regex("\\b(search|look up|find|price|news|weather|who|what|when|where|latest)\\b").containsMatchIn(t) -> "Looking that up"
            else -> "Working on it"
        }
    }

    /** One part's line in the expanded list. */
    data class Step(val id: String, val label: String, val line: String, val state: State)
    enum class State { RUNNING, DONE, FAILED, STOPPED }

    /** The pill's model: the newest line + the list of parts, fed by orchestrator events. */
    class Steps {
        private val parts = LinkedHashMap<String, Step>()
        var line: String = ""; private set
        var open: Boolean = false; private set

        fun begin(text: String) { parts.clear(); line = first(text); open = true }
        fun end() { open = false; line = "" }

        fun on(e: Orchestrator.Event) {
            val label = SpecialistKit.byId(when (e) { is Orchestrator.Event.Started -> e.specialist; is Orchestrator.Event.Step -> e.specialist
                is Orchestrator.Event.Retrying -> e.specialist; is Orchestrator.Event.Finished -> e.summary.specialist })?.verb ?: "Working on it"
            when (e) {
                is Orchestrator.Event.Started -> { parts[e.id] = Step(e.id, label, label, State.RUNNING); line = label }
                is Orchestrator.Event.Step -> clean(e.line)?.let { l -> parts[e.id] = Step(e.id, label, l, State.RUNNING); line = l }
                is Orchestrator.Event.Retrying -> { parts[e.id] = Step(e.id, label, "Trying another way", State.RUNNING); line = "Trying another way" }
                is Orchestrator.Event.Finished -> {
                    val st = when (e.summary.status) { Orchestrator.Status.DONE, Orchestrator.Status.PARTIAL -> State.DONE
                        Orchestrator.Status.CANCELLED -> State.STOPPED; else -> State.FAILED }
                    parts[e.id] = Step(e.id, label, parts[e.id]?.line ?: label, st)
                    parts.values.lastOrNull { it.state == State.RUNNING }?.let { line = it.line }
                }
            }
        }

        fun local(l: String) { clean(l)?.let { line = it } }
        fun list(): List<Step> = parts.values.toList()
    }
}
