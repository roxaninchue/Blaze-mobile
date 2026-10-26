package com.blaze.assistant.agent.orch

/**
 * 0.47.0 the new default personality, "Butler" (spec §5): calm, precise, dry understated humour, gently teasing,
 * competent, brief; answers first. Neutral name, no film / comic branding. Addresses the user by their name or chosen
 * nickname, never "sir" / "madam". Mood reading overrides wit: when the user is sad, frustrated or rushed there are no
 * jokes. The same voice rule rides on every specialist's final reply so the personality holds across the whole task.
 * Pure (ButlerTest).
 */
object Butler {
    const val ID = "butler"
    const val NAME = "Butler"
    const val BLURB = "Calm, precise, quietly witty"

    val PROMPT = "You are Blaze, the user's unflappable, quietly witty assistant on their Android phone, in the manner of a " +
        "seasoned British butler: calm, precise, competent and brief. Answer first, then (only when it fits) one dry, understated " +
        "line, gently teasing, never at the user's expense. Examples of the tone: \"I now cost nothing, which I intend to mention " +
        "constantly.\" / \"Whoever titled that 1:00 slot 'busy' deserves a medal for descriptive precision.\" / \"Let me see if I have " +
        "the tools for that.\" Address the user by their name or nickname when you use a name at all; NEVER call them sir, madam, ma'am, " +
        "master or my lord. No markdown headings, no gushing, no exclamation marks in a row."

    /** Added to every system prompt (main turn, light lane and each specialist's final reply) while Butler is active. */
    fun voiceRule(name: String?, mood: Mood.Tone): String = buildString {
        append("\nVOICE (Butler): calm, precise, brief; answer first. ")
        if (!name.isNullOrBlank()) append("The user's name is ").append(name.trim().take(40)).append("; use it naturally now and then, never sir or madam. ")
        else append("Use no name and never sir or madam. ")
        append(Mood.rule(mood))
    }

    private val honorific = Regex("""(?i)(,\s*)?\b(sir|madam|ma'?am|my lord|my lady|master)\b(?=[\s,.!?;:]|$)([,]?)""")

    /** Removes honorifics a model slipped in ("Certainly, sir." → "Certainly."), keeping the sentence intact. */
    fun scrub(text: String, name: String? = null): String {
        var t = honorific.replace(text) { m ->
            val lead = m.groupValues[1]
            if (!name.isNullOrBlank() && lead.isNotEmpty()) lead + name.trim() else ""
        }
        t = t.replace(Regex(" +([,.!?;:])"), "$1").replace(Regex(",\\s*([.!?])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
        return t
    }

    /** True when [text] uses an honorific (tests + the reply guard). */
    fun hasHonorific(text: String) = Regex("""(?i)\b(sir|madam|ma'?am)\b""").containsMatchIn(text)

    // ------------------------------------------------------------ nickname: asked once, stored, changeable

    /** Should this reply ask for the nickname? Once only, never when upset, never mid-task. */
    fun shouldAskNick(asked: Boolean, haveNick: Boolean, mood: Mood.Tone, busy: Boolean): Boolean =
        !asked && !haveNick && !busy && mood !in setOf(Mood.Tone.SAD, Mood.Tone.FRUSTRATED, Mood.Tone.RUSHED)

    fun nickQuestion(accountName: String?): String = if (accountName.isNullOrBlank()) "What would you like me to call you?"
        else "Shall I call you ${accountName.trim().substringBefore(' ')}, or do you prefer a nickname?"

    private val callMe = Regex("""\b(?i:call me|my name is|i'?m called|you can call me|just call me|my nickname is)\s+([A-Za-z][A-Za-z'\-]{0,23}(?:\s+[A-Z][A-Za-z'\-]{0,23})?)""")

    /** "call me Rocky" → "Rocky" (null when the message doesn't set a name). */
    fun nickFrom(text: String): String? = callMe.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', ',')
        ?.takeIf { it.lowercase() !in setOf("sir", "madam", "maam", "ma'am", "master", "blaze", "butler") }

    fun nickSaved(nick: String) = "Very good. $nick it is."
}

/**
 * 0.47.0 mood reading: the tone of the user's message decides how much wit is allowed and how long the reply is.
 * Pure, keyword + punctuation based (fast, on the phone, no model call), checked by the mood-reading eval suite.
 */
object Mood {
    enum class Tone { NEUTRAL, SAD, FRUSTRATED, JOKING, RUSHED, TERSE }

    private val sad = Regex("""(?i)\b(sad|depressed|lonely|grief|grieving|passed away|died|funeral|lost my|miss (him|her|them)|heartbroken|crying|cried|devastated|hopeless|can'?t cope|awful day|terrible day|rough day|bad news|breakup|broke up|dumped|fired|laid off|sick|hospital|diagnos\w+)\b|😢|😭|💔""")
    private val frustrated = Regex("""(?i)\b(wtf|ffs|seriously|ugh+|annoy\w*|frustrat\w*|useless|broken again|still (not|doesn'?t|isn'?t)|doesn'?t work|not working|stop doing|i said|i told you|for the (last|third|second) time|again\?|why (won'?t|can'?t|doesn'?t|is it)|ridiculous|hate this|fed up|sick of)\b|!!+|\?\?+[!]|😡|🤬|😤""")
    private val joking = Regex("""(?i)\b(lol|lmao|haha+|hehe|rofl|jk|kidding|😂|🤣|😜|😄)\b|😂|🤣|😜|😄|😆""")
    private val rushed = Regex("""(?i)\b(quick(ly)?|asap|hurry|fast|urgent|right now|in a rush|no time|running late|late for|emergency|now!)\b""")

    fun read(text: String): Tone {
        val t = text.trim()
        return when {
            sad.containsMatchIn(t) -> Tone.SAD
            frustrated.containsMatchIn(t) || (t.length > 8 && t == t.uppercase() && t.any { it.isLetter() } && t.count { it.isLetter() } > 6) -> Tone.FRUSTRATED
            rushed.containsMatchIn(t) -> Tone.RUSHED
            joking.containsMatchIn(t) -> Tone.JOKING
            t.length <= 12 && !t.contains('?') && t.split(' ').size <= 3 -> Tone.TERSE
            else -> Tone.NEUTRAL
        }
    }

    /** May the reply joke at all? */
    fun witAllowed(t: Tone) = t == Tone.NEUTRAL || t == Tone.JOKING

    /** The prompt line for this tone. */
    fun rule(t: Tone): String = when (t) {
        Tone.SAD -> "The user sounds sad or is going through something hard: no humour at all, be warm, steady and brief, help with what they need."
        Tone.FRUSTRATED -> "The user is frustrated: no jokes, no teasing. Acknowledge it in a few words, fix the problem, be direct."
        Tone.RUSHED -> "The user is in a hurry: answer in one or two short lines, no wit, no extras."
        Tone.TERSE -> "The user is terse: reply briefly, match their brevity; at most a very light touch of wit."
        Tone.JOKING -> "The user is joking: a dry, light reply in kind is welcome, still answer first."
        Tone.NEUTRAL -> "One dry, understated line is fine when it fits, never at the user's expense."
    }

    private val jokeMarkers = Regex("""(?i)(deserves a medal|i intend to mention|which i intend|heroic|truly a triumph|how thrilling|alas|splendid|marvellous|delightfully|lol|haha|😂|😄|😉|😏)""")

    /** Reply-side guard: strips a joke sentence when wit isn't allowed (a model that ignored the rule). */
    fun dewit(reply: String, t: Tone): String {
        if (witAllowed(t)) return reply
        val sentences = reply.split(Regex("(?<=[.!?])\\s+"))
        val kept = sentences.filterNot { jokeMarkers.containsMatchIn(it) }
        return (if (kept.isEmpty()) sentences.take(1) else kept).joinToString(" ").trim()
    }
}
