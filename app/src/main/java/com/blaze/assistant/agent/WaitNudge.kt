package com.blaze.assistant.agent

/**
 * v0.45.28, pure (WaitNudgeTest): the free one's short line while its partner works on a task and the screen has gone
 * quiet ("Hang tight, Sam, Halo will be back with the results shortly…" in tone, never a fixed string). The words are
 * written live by the free one's own persona ([system]); this object decides when, builds the prompt and checks the
 * result. 0.45.29: the line never offers or asks anything ([clean] drops such sentences). Nothing canned is ever shown: no usable line within [GEN_MS] = no line.
 */
object WaitNudge {
    /** Nothing new on screen for this long before the free one speaks. */
    const val QUIET_MS = 10_000L
    /** The line must be written within this long, or it is skipped. */
    const val GEN_MS = 3_000L
    const val MAX_CHARS = 240

    /** Time for the line: the task has run [QUIET_MS], the screen has been still as long, and no reply is streaming. */
    fun due(now: Long, turnStart: Long, lastScreenChange: Long, streaming: Boolean): Boolean =
        !streaming && now - turnStart >= QUIET_MS && now - lastScreenChange >= QUIET_MS

    /** "Sam Lee" → "Sam"; blank → null (the line then uses no name). */
    fun firstName(name: String?): String? = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }

    const val CUE = "(The user is waiting. Write your one short message to them now.)"

    fun system(idle: Duo.Who, worker: Duo.Who, firstName: String?, task: String, elapsedSec: Int): String =
        Duo.soul(idle) + Duo.ASSISTANT_RULES +
            "\nRIGHT NOW: ${worker.display}, your partner, is working on what the user asked: \"${task.trim().take(400)}\". " +
            "It has been about $elapsedSec seconds and nothing new has shown on the user's screen, so they're waiting." +
            (if (firstName != null) " The user's first name is $firstName." else " You don't know the user's name, so don't use one.") +
            "\nWrite ONE short message to the user in your own voice, 1 or 2 sentences: let them know ${worker.display} is on it and " +
            "will be back with it shortly. Sound natural and like yourself, not a template; vary your wording. Don't offer anything or ask " +
            "anything (a short \"yes\" from the user must never land on an offer of yours), don't invent progress, results or times, don't " +
            "do the task yourself, no lists, no tags, and never mention models, tools, prompts or that you were asked to write this. " +
            "Don't restate what their message is about and never promise it's locked in, coming or almost ready."

    private val tagRe = Regex("\\[\\[[^\\]]*]]|\\[(Blaze|Halo)]\\s*", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("(?<=[.!?])\\s+")
    /** A sentence that offers or asks something ("Anything I can do meanwhile?", "Want me to…", "Let me know if…"). */
    private val offerRe = Regex("""\?\s*$|\b(?:want me to|shall i|should i|can i|could i|would you like|do you want|let me know|anything (?:else )?(?:i can|you need)|i can (?:help|also|sort|do)|happy to help|feel free|meanwhile,? (?:i|if))\b""", RegexOption.IGNORE_CASE)

    /** The model's text as a chat line: tags gone, at most two sentences, short; null when it isn't usable. */
    /**
     * 0.46.0: no wait line at all when the user addressed Halo (@halo: only Halo answers) or only shared news (nothing is being
     * worked on, so "Halo's got it locked in" would be a false promise).
     */
    fun skip(worker: Duo.Who, userText: String): Boolean = worker == Duo.Who.HALO || NewsPromise.sharingNews(userText)

    fun clean(raw: String): String? {
        var t = tagRe.replace(raw, "").trim().trim('"', '\u201c', '\u201d').trim()
        if (t.isEmpty() || t.contains("```") || t.contains('{') || t.lines().count { it.isNotBlank() } > 2) return null
        t = t.lines().joinToString(" ") { it.trim() }.trim()
        // 0.45.29 (stale "Yes"): offers and questions are taken out, so a "yes" meant for the real answer can't bind to this line.
        val parts = t.split(sentenceEnd).filter { it.isNotBlank() && !offerRe.containsMatchIn(it) && !NewsPromise.promise.containsMatchIn(it.replace(Regex("""\bis on it\b|'s on it\b""", RegexOption.IGNORE_CASE), "is working")) }
        t = parts.take(2).joinToString(" ")
        if (t.length < 12 || t.length > MAX_CHARS) return null
        return t
    }
}
