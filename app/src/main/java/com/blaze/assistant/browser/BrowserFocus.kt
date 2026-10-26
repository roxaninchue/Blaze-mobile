package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.29 browser tasks: once a turn has used Blaze's background browser, the rest of its steps carry only the browser
 * tools and the few a browser task needs (asking the user, the secure form, web search, memory, skills), not the whole
 * registered list (150+ tools on every step of a 10-25 step task, which crowded the page out on free models). A call to a
 * tool outside the set widens the turn back to the full list (Agent.pickTools' existing "widened" path). Pure (BrowserReadTest).
 */
object BrowserFocus {
    /** Tools kept alongside browser_* during a browser task. */
    val KEEP = setOf("ask_user", "web_search", "fetch_page", "memory_search", "memory_save", "memory_update", "use_skill", "skill_file",
        "find_tool", "load_tools", "update_brief", "read_turn", "search_chat")

    /** Asking for these means the task needs something else: the rest of the turn gets the full list again. */
    val WIDEN = setOf("find_tool", "load_tools")

    /** A browser task: a background-browser tool (not just the VPN switch or showing it) ran this turn, and no tool search since. */
    fun active(used: Collection<String>): Boolean = used.any { it.startsWith("browser_") && it != "browser_vpn" && it != "browser_show" } && used.none { it in WIDEN }

    private fun nameOf(t: Any?): String = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    fun keeps(name: String): Boolean = name.startsWith("browser_") || name in KEEP

    /** [all] narrowed to the browser set, in the same order (prompt caching); [all] itself when that would leave nothing. */
    fun subset(all: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until all.length()) { val t = all.opt(i); if (keeps(nameOf(t))) out.put(t) }
        return if (out.length() == 0) all else out
    }
}
