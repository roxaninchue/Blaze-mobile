package com.blaze.assistant.agent.orch

import org.json.JSONObject

/**
 * 0.47.0 action-risk classifier (spec §2.2). Every tool call is one of:
 *  - READ: only looks (search, read, list, recall) → runs free;
 *  - REVERSIBLE: changes something Blaze can put back (a file write, a setting, a draft, a routine) → runs with a
 *    checkpoint in [UndoLog];
 *  - OUTWARD: leaves the phone or can't be taken back (send, call, pay, delete, post, install, account changes) → always
 *    the native confirm card showing exactly what will happen, unless the user's own words in this task pre-approve that
 *    exact action ([preApproved]) and the turn has not read untrusted content.
 * Unknown tools are OUTWARD (fail safe). Decided from the tool name and its arguments, never from the model's words. Pure.
 */
object Risk {
    enum class Level { READ, REVERSIBLE, OUTWARD }

    /** Why a call is OUTWARD, in plain words for the card ("Send a message", "Delete files"). */
    enum class Kind(val verb: String) {
        NONE(""), SEND("Send"), CALL("Call"), PAY("Pay"), DELETE("Delete"), POST("Post"), INSTALL("Install"),
        ACCOUNT("Change account"), SHARE("Share"), TAP("Tap"), UNKNOWN("Do")
    }

    data class Verdict(val level: Level, val kind: Kind = Kind.NONE, val target: String = "", val count: Int = 1, val what: String = "")

    val READ_TOOLS = setOf(
        "web_search", "fetch_page", "browser_read", "browser_find", "browser_tabs", "browser_wait", "browser_scroll", "browser_back", "browser_open", "browser_show",
        "recall_memory", "memory_search", "search_past_chats", "search_screen_memory", "search_chat", "read_turn", "read_artifact",
        "device_status", "battery_level", "list_apps", "app_usage", "read_screen", "look_at_screen", "phone_read", "phone_screenshot", "phone_status", "phone_wait",
        "recent_notifications", "read_messages", "recent_calls", "find_contact", "calendar_events", "notif_conversations", "notif_read",
        "gcal_events", "gmail_search", "gmail_read", "drive_search", "gcontacts_search", "classroom_courses", "classroom_coursework",
        "github_issues", "github_my_prs", "github_my_repos", "github_notifications", "github_search_repos", "github_ci",
        "notion_search", "slack_read", "slack_channels", "spotify_now_playing", "file_read", "file_list", "file_search",
        "terminal_status", "terminal_jobs", "list_routines", "list_triggers", "list_agents", "check_agent", "find_skill", "find_tool",
        "load_tools", "self_test", "processes", "wait", "scroll", "ask_user", "goal", "pin",
    )

    val REVERSIBLE_TOOLS = setOf(
        "file_write", "file_edit", "export_file", "download_file", "remember", "memory_save", "memory_update", "memory_merge",
        "create_routine", "schedule_command", "create_trigger", "set_alarm", "set_timer", "set_volume", "device_setting", "add_calendar_event",
        "gcal_create", "move_event", "open_app", "open_url", "open_settings_page", "open_camera", "navigate_to", "search_in_app",
        "media_control", "spotify_control", "spotify_play", "clipboard", "generate_image", "compose_sms", "run_code", "preview_server",
        "start_agent", "message_agent", "cancel_agent", "save_workflow", "write_skill", "skill_file", "update_brief", "press_button",
        "type_text", "browser_type", "browser_select", "browser_key", "browser_vpn", "phone_open", "phone_mode", "blaze_camera", "post_notification",
        "add_contact", "edit_contact", "delegate", "use_skill", "run_skill", "watch",
    )

    private val OUTWARD: Map<String, Kind> = mapOf(
        "send_sms" to Kind.SEND, "send_text" to Kind.SEND, "gmail_send" to Kind.SEND, "email" to Kind.SEND, "notif_reply" to Kind.SEND,
        "reply_to_notification" to Kind.SEND, "slack_post" to Kind.POST, "share_text" to Kind.SHARE, "share_to_app" to Kind.SHARE,
        "call_contact" to Kind.CALL, "call_number" to Kind.CALL, "dial" to Kind.CALL, "call_control" to Kind.CALL, "run_ussd" to Kind.PAY,
        "file_delete" to Kind.DELETE, "delete_routine" to Kind.DELETE, "delete_event" to Kind.DELETE, "delete_trigger" to Kind.DELETE,
        "memory_delete" to Kind.DELETE, "forget" to Kind.DELETE, "uninstall_app" to Kind.INSTALL, "linux_install" to Kind.INSTALL,
        "install_skill" to Kind.INSTALL, "github_install_apk" to Kind.INSTALL, "github_create_issue" to Kind.POST, "github_create_repo" to Kind.POST,
        "notion_add_page" to Kind.POST, "password" to Kind.ACCOUNT, "username" to Kind.ACCOUNT, "standing_permission" to Kind.ACCOUNT,
        "http_request" to Kind.UNKNOWN, "ask_pc" to Kind.UNKNOWN, "system_action" to Kind.UNKNOWN,
    )

    /** Tap-like tools: reversible unless the thing tapped is a send / pay / delete / install / post button. */
    private val TAPS = setOf("tap", "browser_click", "phone_act", "do_actions", "run_plan")
    private val dangerWord = Regex("""(?i)\b(send|post|publish|pay|buy|purchase|checkout|check out|place order|order now|transfer|delete|remove|erase|wipe|uninstall|install|confirm payment|submit|share|sign out|log ?out|reset|factory|format|unsubscribe|deactivate|close account)\b""")
    private val shellDanger = Regex("""(?i)(\brm\s+-[a-z]*[rf]|\brm\s+\S|\bmv\s+\S+\s+/dev/null|\bdd\s+if=|\bmkfs|\bchmod\s+-R|\bgit\s+push|\bgit\s+reset\s+--hard|\bcurl\b[^|]*\|\s*(sh|bash)|\bpip\s+install|\bapt(-get)?\s+(install|remove|purge)|\bpkg\s+(install|uninstall)|\bnpm\s+(i|install|publish)|>\s*/(?!tmp))""")

    fun classify(tool: String, args: JSONObject = JSONObject()): Verdict {
        val v = classify0(tool, args)
        return if (v.level == Level.READ || v.count > 1) v else v.copy(count = count(args))
    }

    private fun classify0(tool: String, args: JSONObject): Verdict {
        val a = args.toString()
        OUTWARD[tool]?.let { k -> return Verdict(Level.OUTWARD, k, target(args), count(args), what(tool, args, k)) }
        if (tool in TAPS) {
            val label = listOf("label", "text", "element_text", "button", "plan", "actions", "goal").joinToString(" ") { args.optString(it) } + " " + a
            return if (dangerWord.containsMatchIn(label)) Verdict(Level.OUTWARD, Kind.TAP, target(args), 1, "Tap \u201c${shortLabel(args)}\u201d")
                else Verdict(Level.REVERSIBLE)
        }
        if (tool == "run_shell" || tool == "termux_run" || tool == "git" || tool.startsWith("terminal")) {
            if (tool == "terminal_status" || tool == "terminal_jobs") return Verdict(Level.READ)
            val cmd = args.optString("command", args.optString("cmd", a))
            val policyRisky = try { com.blaze.assistant.agent.CommandPolicy.shell(cmd) == com.blaze.assistant.agent.CommandPolicy.Level.RISKY } catch (_: Throwable) { false }
            return if (policyRisky || shellDanger.containsMatchIn(cmd)) Verdict(Level.OUTWARD, if (Regex("(?i)\\brm\\b|reset --hard|mkfs|dd if=").containsMatchIn(cmd)) Kind.DELETE else Kind.INSTALL,
                "", 1, "Run \u201c${cmd.take(80)}\u201d") else Verdict(Level.REVERSIBLE)
        }
        if (tool == "gallery") return if (args.optString("action") == "delete") Verdict(Level.OUTWARD, Kind.DELETE, args.optString("path"), 1, "Delete from Gallery") else Verdict(Level.READ)
        if (tool == "device_setting" && Regex("(?i)airplane|mobile data|factory|reset").containsMatchIn(a)) return Verdict(Level.REVERSIBLE, what = "Change a setting")
        if (tool in READ_TOOLS) return Verdict(Level.READ)
        if (tool in REVERSIBLE_TOOLS) return Verdict(Level.REVERSIBLE)
        if (tool.startsWith("mcp_")) return Verdict(Level.OUTWARD, Kind.UNKNOWN, "", 1, "Use a connected app")
        return Verdict(Level.OUTWARD, Kind.UNKNOWN, "", 1, "Run ${tool.replace('_', ' ')}")
    }

    fun target(args: JSONObject): String = listOf("to", "contact", "name", "number", "recipient", "recipients", "path", "paths", "app", "package", "channel", "url")
        .firstNotNullOfOrNull { k -> args.opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" } }.orEmpty().take(120)

    /** How many items one call touches (paths / ids / recipients lists), for the blast-radius guard. */
    fun count(args: JSONObject): Int = listOf("paths", "ids", "items", "recipients", "files", "to").maxOf { k ->
        when (val v = args.opt(k)) {
            is org.json.JSONArray -> v.length()
            is String -> if (k == "to" || k == "recipients") v.split(',', ';').count { it.isNotBlank() } else 0
            else -> 0
        }
    }.coerceAtLeast(1)

    private fun shortLabel(args: JSONObject) = listOf("label", "text", "button").firstNotNullOfOrNull { args.optString(it).takeIf { s -> s.isNotBlank() } }?.take(40) ?: "this"

    fun what(tool: String, args: JSONObject, k: Kind): String {
        val t = target(args)
        val body = listOf("message", "text", "body", "content", "subject").firstNotNullOfOrNull { args.optString(it).takeIf { s -> s.isNotBlank() } }.orEmpty()
        return buildString {
            append(when (k) {
                Kind.SEND -> if (tool.startsWith("gmail") || tool == "email") "Send an email" else "Send a message"
                Kind.CALL -> "Call"; Kind.PAY -> "Dial a money code"; Kind.DELETE -> "Delete"; Kind.POST -> "Post"
                Kind.INSTALL -> if (tool == "uninstall_app") "Uninstall" else "Install"; Kind.ACCOUNT -> "Change account details"
                Kind.SHARE -> "Share"; Kind.TAP -> "Tap"; else -> "Run ${tool.replace('_', ' ')}"
            })
            if (t.isNotBlank()) append(" \u2192 ").append(t)
            if (body.isNotBlank()) append(": \u201c").append(body.take(160)).append('\u201d')
        }
    }

    // ------------------------------------------------------------------ pre-approval from the user's own words

    private val verbs: Map<Kind, Regex> = mapOf(
        Kind.SEND to Regex("""(?i)\b(send|text|sms|message|whatsapp|email|e-mail|mail|reply|tell)\b"""),
        Kind.CALL to Regex("""(?i)\b(call|ring|phone|dial)\b"""),
        Kind.DELETE to Regex("""(?i)\b(delete|remove|erase|clear|bin|trash|wipe)\b"""),
        Kind.POST to Regex("""(?i)\b(post|publish|tweet|open an issue|create (an )?issue|create (a )?repo)\b"""),
        Kind.INSTALL to Regex("""(?i)\b(install|uninstall|download and install)\b"""),
        Kind.SHARE to Regex("""(?i)\b(share|forward)\b"""),
        Kind.PAY to Regex("""(?i)\b(pay|buy|send money|transfer)\b"""),
    )
    private val negation = Regex("""(?i)\b(don'?t|do not|never|no|not yet|without|ask (me )?first|check with me|draft|preview|before (you )?send)\b""")

    /**
     * True only when [userWords] (this task's own messages) explicitly ask for this exact action: the same kind of verb
     * AND the target named (a contact / file / app) or the very text to send quoted, with no "don't / draft / ask first".
     * A turn that read untrusted content ([tainted]) never pre-approves. Payments, account changes and unknown tools are
     * never pre-approved.
     */
    fun preApproved(v: Verdict, userWords: String, tainted: Boolean): Boolean {
        if (v.level != Level.OUTWARD || tainted) return false
        if (v.kind in setOf(Kind.PAY, Kind.ACCOUNT, Kind.UNKNOWN, Kind.TAP)) return false
        val w = userWords.trim()
        if (w.isEmpty() || negation.containsMatchIn(w)) return false
        val verb = verbs[v.kind] ?: return false
        if (!verb.containsMatchIn(w)) return false
        val tgt = v.target.trim().lowercase()
        if (tgt.isEmpty()) return false
        val words = tgt.split(Regex("[\\s,;/._@+-]+")).filter { it.length >= 3 }
        val named = w.lowercase().contains(tgt) || words.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(w.lowercase()) }
        return named
    }
}
