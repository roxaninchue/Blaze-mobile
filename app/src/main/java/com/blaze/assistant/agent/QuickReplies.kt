package com.blaze.assistant.agent

/**
 * 0.45.24: the chips under a reply are quick replies the user would tap next (an answer to a question Blaze just asked,
 * or the logical next action), never a mirror of what the user just said. Seen on a phone: "plan a trip to Livingstone"
 * came back with "Plan a trip to Livingstone" / "Plan my Livingstone trip" as chips.
 *
 * [filter] drops a chip whose words overlap the user's message too much (Jaccard of normalized tokens above
 * [ECHO], or every word of the chip already in the message), and a chip too close to an earlier chip in the same row.
 * Nothing is better than an echo, so the row can end up empty. App-made chips ([KEEP]) always stay. Pure (QuickRepliesTest).
 */
object QuickReplies {
    const val ECHO = 0.6
    const val TWIN = 0.6

    /** Chips the app adds itself (Try again, Continue where you stopped): never filtered. */
    val KEEP = setOf(NudgePolicy.TRY_AGAIN, StopResume.CHIP)

    /** The prompt line asking the model for quick replies. */
    const val RULE = "At the very end of each final answer (never when calling tools), add one line exactly like <<follow-ups: first | second | third>> " +
        "with up to three quick replies the user would tap next (max 6 words each): short answers to a question you just asked " +
        "(\"Yes, book it\", \"Tomorrow morning\"), or the logical next step after your answer. Never repeat or rephrase the user's message, " +
        "never restate your answer, and leave the line out when nothing useful fits."

    private val STOP = setOf("a", "an", "the", "to", "of", "and", "or", "for", "in", "on", "at", "my", "me", "i", "you", "your",
        "is", "are", "it", "this", "that", "with", "can", "could", "please", "pls", "blaze", "do", "be", "some", "about", "just")

    /** Lowercase words without punctuation, stop words and a trailing plural "s". */
    fun tokens(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}']+").findAll(s.lowercase()).map { it.value.trim('\'') }
            .filter { it.isNotEmpty() && it !in STOP }
            .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** True when [chip] mostly says what [user] already said. */
    fun echoes(chip: String, user: String): Boolean {
        val c = tokens(chip)
        val u = tokens(user)
        if (c.isEmpty() || u.isEmpty()) return false
        if (jaccard(c, u) > ECHO) return true
        // Every content word of a 2+ word chip is already in the message ("Livingstone trip" after "plan a trip to Livingstone").
        return c.size >= 2 && u.containsAll(c)
    }

    /** The chips worth showing after [user] said something. */
    fun filter(chips: List<String>, user: String): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableListOf<Set<String>>()
        for (raw in chips) {
            val chip = raw.trim()
            if (chip.isEmpty()) continue
            if (chip in KEEP) { out += chip; continue }
            if (echoes(chip, user)) continue
            val t = tokens(chip)
            if (out.any { it.equals(chip, ignoreCase = true) }) continue
            if (t.isNotEmpty() && seen.any { jaccard(it, t) > TWIN }) continue
            out += chip
            seen += t
        }
        return out
    }
}
