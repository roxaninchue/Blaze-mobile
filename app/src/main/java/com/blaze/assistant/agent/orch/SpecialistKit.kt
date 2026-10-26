package com.blaze.assistant.agent.orch

/**
 * 0.47.0 the orchestrator's specialists (spec §1.2): who they are, which tools they may see (least privilege), their
 * step / time budget, and whether they need an exclusive lane (only one thing can drive the phone screen or the browser
 * at a time). Tool scopes use the existing groups (Specialists.group) plus explicit names; the light lane and the
 * memory specialist are read-mostly. Pure (OrchestratorTest, ToolScopeTest).
 */
object SpecialistKit {
    data class Def(val id: String, val label: String, val groups: Set<String>, val extra: Set<String>, val budget: Orchestrator.Budget,
                   val exclusive: Boolean = false, val lane: String = id, val brief: String, val verb: String)

    val DEFAULT_BUDGET = Orchestrator.Budget(steps = 10, wallMs = 60_000L, retries = 1)

    val all = listOf(
        Def("researcher", "Researcher", setOf("web", "memory"), setOf("web_search", "fetch_page"), Orchestrator.Budget(6, 45_000L, 1),
            brief = "Search, open the 2-3 best pages, cross-check, return the key facts with their links. Stop as soon as two sources agree.", verb = "Looking it up"),
        Def("phone", "Phone operator", setOf("phone", "device", "comms"), setOf("phone_task", "open_app", "search_in_app", "read_screen", "tap", "type_text", "scroll", "do_actions"),
            Orchestrator.Budget(20, 120_000L, 1), exclusive = true, lane = "screen",
            brief = "Operate the phone's apps to finish one task. Read the screen, act by element, check after each action, report exactly what you saw.", verb = "Using your phone"),
        Def("browser", "Browser", setOf("browser", "web"), setOf(), Orchestrator.Budget(16, 120_000L, 1), exclusive = true, lane = "browser",
            brief = "Use the background browser for pages that need clicks or a sign-in. Use secure-form handles for any secret. Never pay or submit without the confirm card.", verb = "Browsing"),
        Def("coder", "Coder", setOf("terminal", "web"), setOf("file_read", "file_write", "file_edit", "file_list", "file_search", "run_code", "run_shell"),
            Orchestrator.Budget(24, 240_000L, 1), brief = "Write, run and fix code in the terminal workspace. Run the tests to check. Keep changes inside the workspace.", verb = "Working on the code"),
        Def("files", "Files", setOf(), setOf("file_read", "file_write", "file_edit", "file_list", "file_search", "file_delete", "export_file", "download_file", "gallery", "drive_search"),
            Orchestrator.Budget(10, 60_000L, 1), brief = "Find, read, save and organise files. Preview before changing more than five files.", verb = "Sorting files"),
        Def("media", "Media", setOf("media"), setOf("media_control", "spotify_control", "spotify_play", "spotify_now_playing", "generate_image", "set_volume"),
            Orchestrator.Budget(8, 45_000L, 1), brief = "Play, find or make media (legal sources only). Report what is playing or what you made.", verb = "Setting up media"),
        Def("memory", "Memory", setOf("memory"), setOf("remember", "forget", "recall_memory", "memory_search", "memory_save", "memory_update", "search_past_chats"),
            Orchestrator.Budget(4, 20_000L, 0), brief = "Recall or save what the user told Blaze. Never store secrets; handles only.", verb = "Checking what I know"),
        Def("scheduler", "Scheduler", setOf("routines"), setOf("set_alarm", "set_timer", "create_routine", "schedule_command", "list_routines", "delete_routine", "calendar_events", "add_calendar_event", "gcal_events", "gcal_create", "move_event"),
            Orchestrator.Budget(8, 45_000L, 1), brief = "Set alarms, reminders, routines and calendar events. Read back what you set as proof.", verb = "Scheduling"),
    )

    fun byId(id: String): Def? = all.firstOrNull { it.id == id } ?: legacy[id]?.let { l -> all.firstOrNull { it.id == l } }

    /** Older specialist ids (delegate's built-ins) → the orchestrator's. */
    private val legacy = mapOf("operator" to "phone", "planner" to "scheduler", "inbox" to "researcher", "shopper" to "researcher", "booker" to "browser", "writer" to "memory")

    /** Least privilege: may [def] use [tool]? [groupOf] = Specialists.group on the phone. delegate / start_agent never. */
    fun allows(def: Def, tool: String, groupOf: (String) -> String): Boolean {
        if (tool in setOf("delegate", "start_agent", "message_agent", "cancel_agent", "create_specialist", "standing_permission")) return false
        return tool in def.extra || groupOf(tool) in def.groups
    }

    /** Tools the light lane (side questions while work runs) may use: read-only only. */
    fun lightAllows(tool: String): Boolean = Risk.classify(tool).level == Risk.Level.READ && tool !in setOf("ask_user", "read_screen", "look_at_screen")

    /**
     * Picks specialists for a plain request when the brain didn't plan (fallback, and for the "first pill line"):
     * independent parts get their own specialist. Pure, deterministic.
     */
    fun guess(text: String): List<String> {
        val t = text.lowercase()
        val out = LinkedHashSet<String>()
        if (Regex("\\b(search|look up|find out|what is|who is|price|news|compare|reviews?|latest|weather)\\b").containsMatchIn(t)) out += "researcher"
        if (Regex("\\b(open|tap|whatsapp|settings|wifi|bluetooth|turn on|turn off|text|call|message)\\b").containsMatchIn(t)) out += "phone"
        if (Regex("\\b(website|site|log ?in|sign ?in|form|checkout|book(ing)?)\\b").containsMatchIn(t)) out += "browser"
        if (Regex("\\b(code|script|bug|python|kotlin|javascript|repo|compile|test(s)?|terminal|shell)\\b").containsMatchIn(t)) out += "coder"
        if (Regex("\\b(file|folder|pdf|document|photos?|gallery|download)\\b").containsMatchIn(t)) out += "files"
        if (Regex("\\b(play|song|music|video|playlist|image|picture)\\b").containsMatchIn(t)) out += "media"
        if (Regex("\\b(remember|forget|recall|what did i)\\b").containsMatchIn(t)) out += "memory"
        if (Regex("\\b(alarm|remind|reminder|timer|calendar|schedule|every (day|morning|week))\\b").containsMatchIn(t)) out += "scheduler"
        return out.toList()
    }
}
