package com.blaze.assistant.agent

/**
 * Lazy start (low-RAM release gate): the rules for what BlazeApp.onCreate may still do on the main thread, as pure
 * functions so they are unit-tested (LazyStartTest). The work itself lives in Agent.init (main chat loaded off the main
 * thread), Mind.openLater / Mind.await (blaze.db opened off the main thread) and device/StartLater (after the first frame).
 */
object LazyStart {
    /** The goal for BlazeApp.onCreate on the owner's phone class (logged as "onCreate N ms"; over budget is a slow-log line). */
    const val ON_CREATE_BUDGET_MS = 200L

    /** How long a background caller waits for blaze.db to open before it falls back to the old list (never on main). */
    const val MIND_WAIT_MS = 10_000L

    /**
     * Whether the main chat, once loaded in the background, becomes the open chat: only when nothing is open yet or only
     * the start placeholder is. A chat the user (or a notification) opened meanwhile is never replaced.
     */
    fun showMain(openId: String?): Boolean = openId == null || openId == Agent.BOOT

    /** Whether a caller may block until blaze.db is open: background threads may (bounded), the main thread never does. */
    fun mayWaitForMind(onMainThread: Boolean, opening: Boolean): Boolean = opening && !onMainThread
}
