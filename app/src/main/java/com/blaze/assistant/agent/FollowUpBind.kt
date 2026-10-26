package com.blaze.assistant.agent

/**
 * 0.45.29, pure (FollowUpBindTest): what a short "yes" answers.
 *
 * The bug: Blaze offered to unsubscribe the user from a shop's emails; later Halo offered to add a song to their music.
 * The user said "Yes" to the song, and the older unsubscribe offer was acted on (and reported as done). A short
 * "yes" / "ok" / "sure" / "do it" now binds ONLY to the newest assistant message in the chat (Blaze or Halo), and only
 * while it is under [WINDOW_MS] old ([bind]). With no fresh offer, the model asks what the user means instead of
 * picking an older one ([block]). Outbound or destructive actions (send, email, message, post, call, unsubscribe,
 * delete, buy, order, pay, book) that the newest offer didn't name always get the confirm card, Autopilot or not
 * ([mustConfirm], [toolGate]).
 */
object FollowUpBind {
    /** A short yes binds to the newest assistant message only while it is younger than this. */
    const val WINDOW_MS = 10 * 60_000L

    enum class Kind { NONE, BOUND, STALE }

    /** An assistant message: [who] = "Blaze" or "Halo", [at] = when it was shown (ms). */
    data class Said(val who: String, val text: String, val at: Long)

    /** [offer] = the newest assistant message (BOUND: the one the yes answers; STALE: too old, or null when none). */
    data class Binding(val kind: Kind, val user: String, val offer: Said?, val ageMs: Long = -1L)

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private fun r(s: String) = Regex(s, ic)

    /** The rapid-message id the model sees on user messages ("[m7] ", "[m7 · sent while you were working] "). */
    private val tagRe = Regex("""^\s*\[m\d+[^\]]{0,60}]\s*""")

    /** "[m7] yes" → "yes". */
    fun untag(text: String): String = tagRe.replace(text, "").trim()

    /** Words a short yes is made of; at least one from [CORE] must be there. */
    private val WORDS = setOf("yes", "yeah", "yea", "yep", "yup", "ya", "y", "ok", "okay", "okey", "k", "kk", "sure", "please", "pls", "plz",
        "go", "ahead", "for", "it", "do", "that", "this", "fine", "alright", "allright", "sounds", "good", "great", "perfect", "absolutely",
        "definitely", "of", "course", "lets", "let's", "thanks", "thank", "you", "proceed", "cool", "nice", "right", "yes,", "ee", "eya",
        "inde", "ndiyo", "sawa", "oui", "daccord", "d'accord", "vas", "vas-y", "si")
    private val CORE = setOf("yes", "yeah", "yea", "yep", "yup", "ya", "y", "ok", "okay", "okey", "k", "kk", "sure", "please", "pls", "plz",
        "ahead", "go", "do", "fine", "alright", "allright", "absolutely", "definitely", "course", "proceed", "ee", "eya", "inde", "ndiyo", "sawa",
        "oui", "daccord", "d'accord", "vas", "vas-y", "si", "perfect", "good")
    private val thumbs = Regex("""^[\s\x{1F44D}\x{1F44C}\u2705\x{1F64F}\x{1F3FB}-\x{1F3FF}.!]+$""")

    /** Is [text] only a short yes ("Yes", "ok!", "sure, go ahead", "yes please", "👍")? A yes with more words isn't. */
    fun isShortYes(text: String): Boolean {
        val t = untag(text)
        if (t.isEmpty() || t.length > 40) return false
        if (thumbs.matches(t) && t.any { it.code > 0x2000 || it.isSurrogate() }) return true
        val words = t.lowercase().replace(Regex("""[^\p{L}\p{N}'\- ]"""), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 6) return false
        return words.all { it in WORDS } && words.any { it in CORE }
    }

    /** What [userText] answers, given the [newest] assistant message before it, at [now]. */
    fun bind(userText: String, newest: Said?, now: Long): Binding {
        val u = untag(userText)
        if (!isShortYes(u)) return Binding(Kind.NONE, u, null)
        if (newest == null || newest.text.isBlank()) return Binding(Kind.STALE, u, null)
        val age = now - newest.at
        return if (age in 0..WINDOW_MS) Binding(Kind.BOUND, u, newest, age) else Binding(Kind.STALE, u, newest, age)
    }

    /** The static rule in every system prompt. */
    const val RULE = "\nSHORT YES: a short \"yes\" / \"ok\" / \"sure\" / \"do it\" answers ONLY the newest message in the chat (yours or your partner's), " +
        "never an older offer. Never send, email, message, post, call, unsubscribe, delete, buy, order, pay or book anything unless the user asked " +
        "for that in their own words, or the newest message offered exactly that action. Never say something is done unless a tool result this " +
        "turn shows it succeeded. "

    /** The per-turn block for the system prompt ("" when this turn isn't a short yes). */
    fun block(b: Binding?): String {
        if (b == null || b.kind == Kind.NONE) return ""
        val said = "\u201c${b.user.take(40)}\u201d"
        return when (b.kind) {
            Kind.BOUND -> {
                val o = b.offer!!
                "\nTHIS TURN: the user's $said answers ONLY ${o.who}'s newest message: \u201c${o.text.trim().take(600)}\u201d. Do what that message " +
                    "offered and nothing else. Any earlier offer or suggestion in this chat is NOT accepted by this reply: don't act on it, don't mention doing it."
            }
            else -> "\nTHIS TURN: the user said only $said, but there is no message from you in the last ${WINDOW_MS / 60_000} minutes for it to answer. " +
                "Do NOT act on any earlier offer or request. Ask the user, in one short sentence, what they'd like you to do, and end your turn."
        }
    }

    /** Kinds of outbound / destructive action named in [text]. */
    fun risks(text: String): Set<String> = RISKS.filterValues { it.containsMatchIn(text) }.keys

    private val RISKS: Map<String, Regex> = mapOf(
        "send" to r("""\b(send|sending|sent|email|e-mail|emails|mail|message|messages|text|sms|whatsapp|reply|forward|post|publish|tweet|dm|share)\b"""),
        "call" to r("""\b(call|calling|dial|ring)\b"""),
        "unsubscribe" to r("""\b(unsubscribe|unsubscribed|unsubscribing|opt[- ]?out|stop (?:these |the |their )?(?:emails|newsletters))\b"""),
        "delete" to r("""\b(delete|deleting|remove|removing|erase|wipe|trash|uninstall|clear data|archive)\b"""),
        "buy" to r("""\b(buy|buying|purchase|order|ordering|pay|paying|payment|checkout|check out|book|booking|reserve|subscribe|transfer|send money)\b"""),
    )

    /** Tools that act in the user's name whatever their arguments say. */
    private val TOOL_RISK: Map<String, String> = mapOf(
        "message_contact" to "send", "send_sms" to "send", "send_text" to "send", "reply_to_notification" to "send", "notif_reply" to "send",
        "gmail_send" to "send", "send_email" to "send", "slack_post" to "send", "share_to_app" to "send",
        "call_number" to "call", "call_contact" to "call", "dial" to "call",
        "file_delete" to "delete", "uninstall_app" to "delete",
    )

    /** Tools whose risk is in what they act on (a button's words, a plan's steps). */
    private val BY_ARGS = setOf("browser_click", "browser_key", "phone_act", "tap", "press_button", "run_plan", "do_actions")

    /** Kinds of risk calling [tool] with [args] (its JSON) carries. */
    fun toolRisks(tool: String, args: String): Set<String> {
        val out = HashSet<String>()
        TOOL_RISK[tool]?.let { out += it }
        if (tool.contains("delete") || tool.contains("unsubscribe")) out += if (tool.contains("unsubscribe")) "unsubscribe" else "delete"
        if (tool in BY_ARGS) out += risks(args)
        return out
    }

    /**
     * Must the confirm card show for an action described by [action] (a card's title + detail), whatever Autopilot or a
     * standing OK say? Yes on a short-yes turn when the action is outbound / destructive and the newest offer didn't
     * name that kind of action (or there was no fresh offer at all).
     */
    fun mustConfirm(b: Binding?, action: String): Boolean = unnamed(b, risks(action))

    private fun unnamed(b: Binding?, r: Set<String>): Boolean {
        if (b == null || b.kind == Kind.NONE || r.isEmpty()) return false
        if (b.kind == Kind.STALE) return true
        val named = risks(b.offer?.text.orEmpty())
        return !named.containsAll(r)
    }

    /**
     * The confirm card (title to detail) to show before running [tool] on a short-yes turn, or null when it may run.
     * Shown for outbound / destructive tools the newest offer didn't name.
     */
    fun toolGate(b: Binding?, tool: String, args: String): Pair<String, String>? {
        val r = toolRisks(tool, args)
        if (!unnamed(b, r)) return null
        val what = r.sorted().joinToString(", ")
        val why = if (b?.kind == Kind.BOUND) "You said \u201c${b.user.take(40)}\u201d to: \u201c${b.offer?.text?.trim()?.take(140)}\u201d, which didn't offer this."
            else "You said \u201c${b?.user?.take(40)}\u201d, but there was no recent offer it answers."
        return "Do this ($what)?" to "$why\n${tool.replace('_', ' ')}: ${args.take(200)}"
    }

    /** The tool result when the user didn't approve the card from [toolGate]. */
    const val REFUSED = "Not run: the user's short reply didn't ask for this and they did NOT approve it. Don't retry it and don't say it was done; " +
        "ask them what they'd like."
}
