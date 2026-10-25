package com.blaze.assistant.ui

/**
 * 0.45.24: the live line while Blaze works (the step pill, or an acknowledged task's chip) sits beside the Blaze drop
 * in one row, vertically centred, instead of above or below it, and goes away as soon as the reply's first words
 * stream in, not only when the reply is done. Pure (WaitRowTest).
 */
object WaitRow {
    /**
     * True while the live line may show: Blaze is working, nothing of the reply is on screen yet ([streaming] = a
     * reply is streaming in; [replyAtEnd] = the newest thing in the thread is Blaze's text for this turn).
     */
    fun showLine(working: Boolean, streaming: Boolean, replyAtEnd: Boolean): Boolean = working && !streaming && !replyAtEnd

    /** The drop shows while waiting, unless an acknowledged task chip or a side-by-side reply takes its place. */
    fun showDrop(working: Boolean, streaming: Boolean, hasChip: Boolean, duoLive: Boolean): Boolean =
        working && !streaming && !hasChip && !duoLive

    /**
     * The text beside the drop, or null for none: the step pill's line first; in Simple mode with steps running, the
     * short hint that used to sit above; otherwise the live status ("Searching the web"), checked like a step pill.
     */
    fun line(pill: String?, status: String, lastStep: String, simple: Boolean, on: Boolean): String? {
        if (!on) return null
        pill?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (simple) return com.blaze.assistant.agent.SimpleMode.hint(status, lastStep)
        val t = status.trim()
        if (t.isEmpty()) return null
        return com.blaze.assistant.agent.StepPill.clean(t)
    }
}
