package com.blaze.assistant.composer

/**
 * 0.47 Spotlight v2: Blaze's self-map. ONE list of every view, action, setting and model Blaze has. Spotlight searches
 * it ([SpotlightSearch]) and the agent reads it ([describe], about_blaze section "map"), so the two never disagree.
 *
 * Views come from [com.blaze.assistant.ui.Views.all] (the same list open_view uses); actions, settings and models are
 * declared here. Pure apart from [default] reading Views (SelfMapTest builds maps from plain lists with [of]).
 */
object SelfMap {
    enum class Kind { VIEW, ACTION, SETTING, MODEL }

    /**
     * One thing Blaze can open or do. [target]: the view id a VIEW / SETTING opens, the action id, or the model family id.
     * [preview]: a VIEW that can also show inline inside Spotlight (stays on Home).
     */
    data class Entry(val id: String, val kind: Kind, val title: String, val subtitle: String, val target: String,
                     val keywords: List<String> = emptyList(), val preview: Boolean = false)

    /** A view as the map needs it (decoupled from the UI class so the map stays testable). */
    data class ViewInfo(val id: String, val name: String, val desc: String, val aliases: List<String> = emptyList())

    /** Views that also open as an inline preview inside Spotlight. */
    val PREVIEWABLE = setOf("browser", "music", "video", "terminal", "settings", "look")

    /** The composer / Spotlight actions (ids are handled by the composer host). */
    val ACTIONS = listOf(
        Entry("action:chat", Kind.ACTION, "Chat with Blaze", "Open the chat over Home", "chat", listOf("talk", "message", "ask")),
        Entry("action:new_chat", Kind.ACTION, "New chat", "A fresh start; nothing is deleted", "new_chat", listOf("clear", "fresh", "reset chat")),
        Entry("action:private", Kind.ACTION, "Private chat", "A chat Blaze doesn't keep", "private", listOf("incognito", "ghost", "secret chat")),
        Entry("action:voice", Kind.ACTION, "Talk", "Speak to Blaze", "voice", listOf("mic", "microphone", "speak", "listen", "hey blaze")),
        Entry("action:attach", Kind.ACTION, "Attach a file", "Add a photo or file to your message", "attach", listOf("photo", "picture", "image", "upload", "file", "document", "pdf")),
        // 0.48.0 RC4: everything the old chat screen's + menu and top bar did, now reachable from Spotlight.
        Entry("action:history", Kind.ACTION, "Chat history", "The whole conversation, over Home", "history", listOf("history", "earlier", "old messages", "full chat", "scroll back")),
        Entry("action:photos", Kind.ACTION, "Attach photos", "Pick photos for your message", "photos", listOf("gallery photo", "pictures", "images")),
        Entry("action:camera", Kind.ACTION, "Take a photo", "Camera, attached to your message", "camera", listOf("camera", "snap", "picture")),
        Entry("action:paste", Kind.ACTION, "Paste image", "The picture on your clipboard", "paste", listOf("clipboard", "paste")),
        Entry("action:call", Kind.ACTION, "Call Blaze", "A hands-free voice call", "call", listOf("phone call", "voice call", "ring")),
        Entry("action:plan", Kind.ACTION, "Plan mode", "Blaze plans before it acts (on / off)", "plan", listOf("plan first", "work mode")),
        Entry("action:approval", Kind.ACTION, "Ask before acting", "Autopilot or ask first (on / off)", "approval", listOf("autopilot", "approvals", "ask first", "drafts")),
        Entry("action:clear", Kind.ACTION, "Clear the screen", "/clear: hides the chat, nothing is forgotten", "clear", listOf("/clear", "clean")),
        Entry("action:account", Kind.ACTION, "Profile", "Account, settings, sign out", "profile", listOf("me", "sign out", "logout", "avatar")),
    )

    /** Settings people look for by name (each opens its page). */
    val SETTINGS = listOf(
        Entry("setting:wallpaper", Kind.SETTING, "Wallpaper", "Living backgrounds and your photo", "look", listOf("background", "backgrounds", "photo background")),
        Entry("setting:theme", Kind.SETTING, "Light or dark", "Light / Dark / Auto and accent colour", "look", listOf("dark mode", "light mode", "theme", "accent", "colour", "color")),
        Entry("setting:voice", Kind.SETTING, "Voice", "Hey Blaze, spoken replies", "voice", listOf("wake word", "speech", "read aloud")),
        Entry("setting:brain", Kind.SETTING, "Brain", "On-phone brain and Blaze Cloud", "brain", listOf("models", "ai", "on-phone brain")),
        Entry("setting:sync", Kind.SETTING, "Cloud sync", "Keep phones the same", "sync", listOf("backup")),
        Entry("setting:logins", Kind.SETTING, "Saved logins", "Sign-ins from the secure form", "logins", listOf("passwords", "vault")),
        Entry("setting:control", Kind.SETTING, "Phone control", "Accessibility and permissions", "control", listOf("permissions", "accessibility")),
        Entry("setting:proactive", Kind.SETTING, "Proactive", "When Blaze speaks up on its own", "proactive", listOf("notifications", "triggers")),
    )

    /** The model families, searchable by name and speed ("inf" → Blaze Inferno · Smartest). */
    val MODELS: List<Entry> = ModelChoice.Family.values().map { f ->
        Entry("model:${f.id}", Kind.MODEL, "${f.blazeName} \u00b7 ${f.sub}", "Model", f.id,
            listOf(f.label.lowercase(), f.sub.lowercase(), "model", "brain"))
    }

    fun of(views: List<ViewInfo>): List<Entry> {
        val v = views.map { Entry("view:${it.id}", Kind.VIEW, it.name, it.desc, it.id, it.aliases + it.id, preview = it.id in PREVIEWABLE) }
        return v + ACTIONS + SETTINGS + MODELS
    }

    /** The live map: Views.all + actions + settings + models. Never throws (falls back to a small built-in view list). */
    val default: List<Entry> by lazy { of(liveViews()) }

    private fun liveViews(): List<ViewInfo> = runCatching {
        com.blaze.assistant.ui.Views.all.map { ViewInfo(it.id, it.name, it.desc, it.aliases) }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK_VIEWS

    val FALLBACK_VIEWS = listOf(
        ViewInfo("home", "Home", "The Home cards"),
        ViewInfo("browser", "Browser", "Blaze's own browser and tabs"),
        ViewInfo("music", "Music", "Blaze Music: your songs and playlists", listOf("songs", "player")),
        ViewInfo("video", "Video", "Blaze Video: your videos", listOf("videos", "movies")),
        ViewInfo("terminal", "Terminal", "A real terminal: your Alpine Linux or the phone shell", listOf("shell", "console")),
        ViewInfo("gallery", "Gallery", "Images, music and files Blaze made"),
        ViewInfo("look", "Look & feel", "Backgrounds and theme", listOf("wallpaper", "theme")),
        ViewInfo("settings", "Settings", "Behaviour, voice, privacy, backup"),
    )

    fun find(id: String, map: List<Entry> = default): Entry? = map.firstOrNull { it.id == id }

    /** For the agent (about_blaze section "map"): one line per entry. */
    fun describe(map: List<Entry> = default): String = buildString {
        for (k in Kind.values()) {
            val rows = map.filter { it.kind == k }
            if (rows.isEmpty()) continue
            append("## ").append(k.name.lowercase().replaceFirstChar { it.uppercase() }).append("s\n")
            rows.forEach { e ->
                append("- ").append(e.title).append(" [").append(e.id).append("]: ").append(e.subtitle)
                if (e.preview) append(" (inline preview in Spotlight: @").append(e.target).append(")")
                append('\n')
            }
        }
        append("Spotlight prefixes: ! runs a terminal command in the picked distro; @view opens a view's inline preview (e.g. @browser google.com).")
    }
}
