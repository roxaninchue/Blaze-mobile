package com.blaze.assistant.agent.orch

/**
 * 0.47.0 speed (spec §4): the message is classified while it is being typed (debounced), so Send finds the decision
 * ready: the pill's first line, which specialists it likely needs, whether it's small talk (no pill, no tools), and how
 * it routes if work is already running. Pure with an injectable clock; [take] returns the cached result only for the
 * exact text that was classified (TypeaheadTest / bench).
 */
class Typeahead(private val now: () -> Long = { System.currentTimeMillis() }, private val debounceMs: Long = DEBOUNCE_MS,
                private val chitChat: (String) -> Boolean = { false }) {
    companion object { const val DEBOUNCE_MS = 250L }

    data class Pre(val text: String, val firstLine: String, val specialists: List<String>, val smallTalk: Boolean, val busyRoute: LightLane.Kind, val at: Long)

    @Volatile private var lastKey = 0L
    @Volatile private var pending = ""
    @Volatile private var ready: Pre? = null

    /** A keystroke. Returns true when the debounce window has passed and the text was classified now. */
    @Synchronized fun typed(text: String): Boolean {
        val t = now()
        val due = t - lastKey >= debounceMs && pending == text
        lastKey = t; pending = text
        if (due) { classify(text); return true }
        return false
    }

    /** Called by the debounce timer [debounceMs] after the last keystroke. */
    @Synchronized fun settle(): Pre? { if (pending.isBlank()) return null; if (now() - lastKey >= debounceMs) classify(pending); return ready }

    @Synchronized fun classify(text: String): Pre {
        val t = text.trim()
        val p = Pre(t, StatusLine.first(t), SpecialistKit.guess(t), chitChat(t), LightLane.route(t), now())
        ready = p; return p
    }

    /** The decision for exactly [text] (made while typing), or a fresh one. */
    @Synchronized fun take(text: String): Pre { val r = ready; return if (r != null && r.text == text.trim()) r else classify(text) }
}
