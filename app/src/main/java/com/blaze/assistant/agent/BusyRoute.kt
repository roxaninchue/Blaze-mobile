package com.blaze.assistant.agent

/**
 * 0.46.3 (report: while Blaze works on a long task, a new message sits at "Sending" until it finishes). A message that
 * arrives while a task runs is never queued behind the agent loop:
 *  - STOP ("stop", "cancel that", "never mind"): the running task is stopped at once, one plain line confirms;
 *  - STEER (a correction or addition: "also check Y", "actually make it 7", "instead…", "@blaze …", or anything that needs
 *    Blaze's tools): it joins the running task at its next step, the bubble is marked sent at once, Halo confirms in one line;
 *  - SIDE (anything else, "how's it going?" too): Halo answers in parallel on its own model call, with the running task's
 *    read-only status, without blocking or cancelling it.
 * Pure (BusyRouteTest).
 */
object BusyRoute {
    enum class Kind { STOP, STEER, SIDE }

    private val stop = Regex("""(?i)^\s*(?:@blaze\s+)?(?:stop|cancel|abort|halt|quit|never ?mind|forget it|leave it)(?:\s+(?:it|that|this|now|please|the task|everything))*\s*(?:please)?[.!]*\s*$""")
    private val steer = Regex("""(?i)^\s*(?:also\b|and also\b|plus\b|actually\b|instead\b|rather\b|no[,.]\s|not that\b|wait[,.]?\s|oh and\b|and\s+(?:check|add|make|use|look|find|include)\b|change\b|make it\b|use\b|skip\b|don'?t\b|do not\b|only\b|add\b|include\b|exclude\b|but\b|correction\b|sorry,?\s+i meant\b|i meant\b|@blaze\b)""")

    fun route(text: String, toolShaped: Boolean = false): Kind {
        val t = text.trim()
        if (stop.matches(t)) return Kind.STOP
        if (steer.containsMatchIn(t)) return Kind.STEER
        if (toolShaped) return Kind.STEER
        return Kind.SIDE
    }

    /** The one-line confirmations, first person (Halo), plain. */
    const val STOPPED = "Stopped. Nothing more will run for that task."
    fun steered(task: String): String = if (task.isBlank()) "Got it, I've passed that to Blaze. It's added to what Blaze is doing now."
        else "Got it, I've passed that to Blaze. It's added to \u201c${task.take(60)}\u201d."

    /** What Halo is told about the running task (read only), e.g. "Blaze is working on: X. Now: Reading 3 pages (step 4)." */
    fun status(task: String, now: String, step: Int): String = buildString {
        append("Blaze is working on: ").append(task.take(160).ifBlank { "a task" }).append('.')
        if (now.isNotBlank()) append(" Right now: ").append(now.take(120)).append(if (step > 0) " (step $step)." else ".")
        append(" You can't change or stop it yourself; answer the user, and if they ask how it's going, say what it's doing in plain words.")
    }
}
