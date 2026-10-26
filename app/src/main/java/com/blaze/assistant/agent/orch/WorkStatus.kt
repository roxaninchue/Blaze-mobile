package com.blaze.assistant.agent.orch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 0.47.0 the live status the chat's glass pill shows (spec §3.2): one line + the list of parts, per chat. Set at once on
 * send ([begin], local, no network: the first line within 300 ms), moved by the orchestrator's events and the main
 * loop's tool steps, cleared when the answer lands ([end]). Android-free (StateFlow only) so tests drive it directly.
 */
object WorkStatus {
    data class View(val chat: String, val line: String, val steps: List<StatusLine.Step>, val startedAt: Long)

    private val _current = MutableStateFlow<View?>(null)
    val current: StateFlow<View?> = _current.asStateFlow()
    private val model = StatusLine.Steps()
    private var chat = ""
    private var at = 0L
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    @Synchronized fun begin(chatId: String, text: String) { chat = chatId; at = clock(); model.begin(text); publish() }
    @Synchronized fun end(chatId: String) { if (chatId == chat || chatId.isEmpty()) { model.end(); _current.value = null } }
    @Synchronized fun on(chatId: String, e: Orchestrator.Event) { if (chatId == chat && model.open) { model.on(e); publish() } }
    @Synchronized fun step(chatId: String, line: String) { if (chatId == chat && model.open) { model.local(line); publish() } }
    @Synchronized fun tool(chatId: String, tool: String, args: org.json.JSONObject, pages: Int = 0) = step(chatId, StatusLine.forTool(tool, args, pages))

    private fun publish() { _current.value = if (model.open && model.line.isNotBlank()) View(chat, model.line, model.list(), at) else null }

    /** Lines the old Cloud-run status used for "waiting" that the pill never shows (the step line is better). */
    val GENERIC = setOf("Sending\u2026", "Working on it\u2026", "Working on it")
}
