package com.blaze.assistant.agent.orch

/**
 * 0.47.0 one Blaze (spec §1.3): Halo is gone; while work runs, Blaze itself keeps talking. A message that arrives during
 * a running task is routed here, never queued behind the work:
 *  - STOP: the kill switch ("stop", "cancel", "never mind") — every running part stops, one line confirms;
 *  - CHANGE: a new instruction that replaces the task ("forget that, book Friday instead", "actually, do X instead") —
 *    running work is cancelled and the new instruction starts at once (said back in one line);
 *  - STEER: a correction or addition folded into the running work at its next step ("also check Y", "make it 7");
 *  - SIDE: anything else — answered instantly on the light lane (fast brain, read-only tools), as Blaze, with the task's
 *    read-only status.
 * Pure (LightLaneTest).
 */
object LightLane {
    enum class Kind { STOP, CHANGE, STEER, SIDE }

    private val stop = Regex("""(?i)^\s*(?:@?blaze[,:]?\s+)?(?:please\s+)?(?:stop|cancel|abort|halt|quit|never ?mind|forget it|leave it|kill it|stop everything)(?:\s+(?:it|that|this|now|please|the task|everything|all))*\s*(?:please)?[.!]*\s*$""")
    private val change = Regex("""(?i)^\s*(?:no[,.!]?\s+)?(?:forget (?:that|it|about that)[,.]?\s+(?:and\s+)?|scrap (?:that|it)[,.]?\s+|cancel (?:that|it)[,.]?\s+(?:and\s+)?|stop (?:that|it)[,.]?\s+(?:and\s+)?|instead[,]?\s+|new (?:task|plan)[:,]?\s+|change of plan[:,.]?\s+|actually[,]?\s+(?:just\s+)?(?:do|make|book|send|find|get|write|open|play)\b.*\binstead\b)""")
    private val steer = Regex("""(?i)^\s*(?:also\b|and also\b|plus\b|actually\b|rather\b|no[,.]\s|not that\b|wait[,.]?\s|oh and\b|and\s+(?:check|add|make|use|look|find|include)\b|change\b|make it\b|use\b|skip\b|don'?t\b|do not\b|only\b|add\b|include\b|exclude\b|but\b|correction\b|sorry,?\s+i meant\b|i meant\b|@blaze\b|@halo\b)""")
    private val question = Regex("""(?i)^\s*(?:how(?:'s| is| long)|what(?:'s| is| are)|when|where|why|who|is it|are you|can you tell|any update|status|progress)\b|\?\s*$""")

    /** [toolShaped] = the message needs Blaze's tools (an action, not a question about the work). */
    fun route(text: String, toolShaped: Boolean = false): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.SIDE
        if (stop.matches(t)) return Kind.STOP
        if (change.containsMatchIn(t)) return Kind.CHANGE
        if (steer.containsMatchIn(t)) return Kind.STEER
        if (question.containsMatchIn(t)) return Kind.SIDE
        if (toolShaped) return Kind.STEER
        return Kind.SIDE
    }

    /** The new instruction inside a CHANGE ("forget that, book Friday" → "book Friday"). */
    fun newInstruction(text: String): String = text.trim()
        .replace(Regex("""(?i)^\s*(?:no[,.!]?\s+)?(?:forget (?:that|it|about that)|scrap (?:that|it)|cancel (?:that|it)|stop (?:that|it)|new (?:task|plan)|change of plan)[,.:]?\s*(?:and\s+)?"""), "")
        .replace(Regex("""(?i)^\s*instead[,]?\s+"""), "").trim().ifEmpty { text.trim() }

    // One-line confirmations, first person, plain (Blaze's own voice; the personality layer may restyle them).
    const val STOPPED = "Stopped. Nothing more will run for that."
    fun changed(newTask: String) = "Switching to that now: \u201c${newTask.trim().take(60)}\u201d. The earlier task is stopped."
    fun steered(task: String) = if (task.isBlank()) "Got it, adding that to what I'm doing." else "Got it, adding that to \u201c${task.trim().take(60)}\u201d."

    /** The side reply's holding line when no word came within ~2 s (from the app's own state, no model). */
    fun holding(task: String, now: String): String = buildString {
        append("Still on ").append(if (task.isBlank()) "your request" else "\u201c${task.trim().take(60)}\u201d")
        val n = now.trim().trimEnd('.', '\u2026')
        if (n.isNotBlank() && n !in WorkStatus.GENERIC && !n.startsWith("Working on it")) append(". Right now: ").append(n.take(80))
        append('.')
    }

    /** What the light lane is told about the running work (read only). */
    fun status(task: String, now: String, parts: List<String>): String = buildString {
        append("You (Blaze) are working on: ").append(task.take(160).ifBlank { "a task" }).append('.')
        if (now.isNotBlank()) append(" Right now: ").append(now.take(120)).append('.')
        if (parts.isNotEmpty()) append(" Parts running: ").append(parts.take(6).joinToString(", ")).append('.')
        append(" Answer the user's side message briefly as Blaze. Don't start actions here; if they ask how it's going, say what you're doing in plain words.")
    }
}
