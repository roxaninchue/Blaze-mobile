package com.blaze.assistant.agent

/**
 * Ack-and-background (HQ 22 Oct 20:35), 0.45.29: with no ack text at all.
 *
 * 0.45.29 (user report, Mon 26 Oct 09:02): a user answered a heads-up from its notification with "I play guitar" and the
 * thread then showed "Playing guitar a little." as if Blaze had said it: an ack line (made on the phone by rewording the
 * message, or sent by the gateway's `ack` event) instead of a real answer. Blaze never shows a canned or paraphrased line
 * between a message and its answer any more, in the chat or in a notification: only what the model actually wrote
 * (its reply, and the 0.45.28 wait line [WaitNudge], which the model writes live). Removed: the on-device ack
 * ("Turning on dark mode."), the gateway ack, the ack notification, the "Blaze got your message…" line and the
 * "Done ✓" prefix that was added to an acknowledged turn's reply.
 *
 * What stays is the bookkeeping, without words: a task (a message that asks Blaze to do something, [wants]) still
 * running after [FAST_MS] is written on the task ledger (TaskKeeper) so it keeps going when the user leaves and is never
 * lost silently, and the quiet task chip ([chip]) shows while it runs. Pure parts: AckTest / ChatAck19Test.
 */
object Ack {
    /**
     * The meta of a Blaze line that is not the turn's answer: acks saved by versions before 0.45.29 (still in old chats)
     * and the model-written wait line ([WaitNudge]). Such a line never counts as "answered".
     */
    const val META = "ack"
    /** The verified-toggle result line's lead (StateCheck.doneLine, ToggleReply); no longer added to any other reply. */
    const val DONE = "Done \u2713"
    /** A task that finishes within this long is never written on the task ledger (a single toggle answers in under 2 s). */
    const val FAST_MS = 2_000L

    /** How much longer before a task started at [startedAt] is written down. Pure. */
    fun holdMs(startedAt: Long, now: Long): Long = (FAST_MS - (now - startedAt)).coerceIn(0L, FAST_MS)

    private val greeting = Regex(
        "^(hi|hey|hello|yo|hiya|howdy|sup|good (morning|afternoon|evening|night)|gm|gn|thanks?( you)?|thank u|thx|ty|ok(ay)?|cool|nice|great|" +
            "lol|haha+|bye|see ya|how are you|how's it going|what's up|wassup)( (blaze|halo|there|mate|man|bro))?[ .!?\u2026]*$",
        RegexOption.IGNORE_CASE)

    /** Verbs that make a message a task (something to do, not something to answer). */
    private val doVerb = Regex(
        "\\b(remind|set|schedule|book|order|buy|send|text|message|email|call|create|make|add|delete|remove|turn (on|off)|switch|enable|disable|" +
            "open|play|download|save|export|share|install|update|find (me|a|the)|search for|look up|research|write|draft|plan|build|generate|" +
            "translate|summari[sz]e|clean|organi[sz]e|move|copy|rename|cancel|wake me|alarm|timer|track|post|upload|record|start|stop)\\b",
        RegexOption.IGNORE_CASE)

    /**
     * A question gets answered, never acked ("did you already book?", "what is Blaze phone doing"). A polite request
     * phrased as a question ("can you turn on dark mode?") is still a task.
     */
    private val askLead = Regex(
        "^(did|do|does|is|are|was|were|have|has|had|am|what|what's|whats|why|how|when|where|who|whose|which|should|shall|" +
            "isn't|aren't|didn't|doesn't|haven't|hasn't|wasn't)\\b", RegexOption.IGNORE_CASE)
    private val politeAsk = Regex("^(please\\s+)?(can|could|would|will) you\\b", RegexOption.IGNORE_CASE)

    /** True when [text] asks something rather than asks for something. Pure. */
    fun isQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (askLead.containsMatchIn(t)) return true
        return t.endsWith("?") && !politeAsk.containsMatchIn(t)
    }

    /**
     * Placeholder lines that say nothing about the task ("Got it, I'm on it. I'll message you here when it's done.",
     * Halo's "passing that straight to Blaze"). Never shown: an ack is specific or there is none.
     */
    private val generic = Regex(
        "(\\bi'?m on it\\b|^\\s*((got it|ok(ay)?|sure|alright)[,.!\\s\u2014-]*)?on it\\b|\\bi'?ll (message|text|ping|update) you\\b|\\bi'?ll let you know\\b|" +
            "\\bi'?ll get back to you\\b|\\bworking on it\\b|\\bpass(ing|ed)? (that|this|it) (straight |right )?(on |over )?to blaze\\b|" +
            "\\bpassed to blaze\\b|\\bhanding (that|this|it) (over )?to blaze\\b|\\blet me know if you need anything\\b|\\b(one|a) (moment|sec|second)\\b|" +
            "\\bgive me a (moment|sec|second)\\b|^\\s*got it[.!]?\\s*$|^\\s*halo here\\b)",
        RegexOption.IGNORE_CASE)

    /** True for a placeholder line that names nothing Blaze is doing. Pure. */
    fun isGeneric(ack: String): Boolean = generic.containsMatchIn(ack.trim())

    /** True when [text] is a task (kept on the ledger while it runs), not a question, greeting or small talk. */
    fun wants(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("[Scheduled routine") || t.startsWith("/")) return false
        if (greeting.matches(t)) return false
        if (isQuestion(t)) return false
        return doVerb.containsMatchIn(t)
    }

    private val reminder = Regex("\\bremind(er)?\\b", RegexOption.IGNORE_CASE)
    private val alarm = Regex("\\b(alarm|wake me)\\b", RegexOption.IGNORE_CASE)
    private val timer = Regex("\\btimer\\b", RegexOption.IGNORE_CASE)

    /** The task chip's short label while the work runs in the background ("Reminder", "Task"). */
    fun chip(text: String): String = when {
        timer.containsMatchIn(text) -> "Timer"
        alarm.containsMatchIn(text) -> "Alarm"
        reminder.containsMatchIn(text) -> "Reminder"
        else -> "Task"
    } + " \u00b7 in progress"

    /**
     * One tracked task per turn (the ledger's entry), however many times it is marked: the phone's own check after
     * [FAST_MS] and the gateway's `ack` event (whose text is never shown) both only [track] it once.
     */
    class Gate(val at: Long = System.currentTimeMillis()) {
        @Volatile var finished = false; private set
        @Volatile var tracked = false; private set
        /** The user message the task is (the task ledger's key). */
        @Volatile var user = 0L
        /** What the user said (the ledger's honest "it stopped" line names it). */
        @Volatile var said = ""
        /** True the first time, while the turn hasn't finished: the caller writes the task down. */
        @Synchronized fun track(): Boolean {
            if (tracked || finished) return false
            tracked = true; return true
        }
        @Synchronized fun finish() { finished = true }
    }
}
