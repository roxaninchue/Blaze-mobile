package com.blaze.assistant.agent

/**
 * 0.46.3 (report: a reply about adding a video source came after "lots of ZRA searches"). Per turn, a web search runs only
 * when it is about the current request and isn't a third near-copy of an earlier one:
 *  - at most [SAME_MAX] near-identical queries (same words, any order, Jaccard ≥ 0.7) per turn;
 *  - a query built from an OLDER subject (earlier messages of this chat, saved memories) that shares no word with the request
 *    (the pinned goal and this turn's message) nor with what this turn's own searches and pages returned is not run: that is
 *    an old task or a memory bleeding into this one. A query in other words or another language than the request still runs.
 * Pure (SearchGuardTest).
 */
class SearchGuard {
    companion object {
        const val SAME_MAX = 2
        private val stop = setOf("the", "and", "for", "with", "from", "that", "this", "what", "how", "who", "when", "where", "why", "which", "are", "is",
            "was", "can", "does", "did", "get", "use", "about", "best", "new", "top", "free", "online", "2024", "2025", "2026", "near", "me", "my", "your", "you")
        fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.toSet()
        fun similar(a: String, b: String): Boolean {
            val x = words(a); val y = words(b)
            if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), true)
            return x.intersect(y).size.toDouble() / x.union(y).size >= 0.7
        }
        fun dupNote(q: String) = "Not run: \u201c${q.take(60)}\u201d is the third near-identical search in this task. Use what the earlier searches found, read a result with fetch_page, or answer."
        fun offNote(q: String) = "Not run: \u201c${q.take(60)}\u201d isn't about what the user asked now. Search only for the current request; don't bring back an older task."
    }

    private val ran = ArrayList<String>()
    private val seenWords = HashSet<String>()

    @Synchronized fun begin() { ran.clear(); seenWords.clear() }

    /** Words from this turn's search results and pages (leads the agent may follow). */
    @Synchronized fun saw(result: String) { seenWords += words(result.take(8_000)) }

    /** The note to return instead of searching, or null to run it. [goal] = the pinned request plus this turn's message. */
    @Synchronized fun check(q: String, goal: String, older: String = ""): String? {
        if (q.isBlank()) return null
        if (ran.count { similar(it, q) } >= SAME_MAX) return dupNote(q)
        val qw = words(q)
        val gw = words(goal)
        val ow = words(older) - gw
        if (qw.isNotEmpty() && qw.intersect(gw).isEmpty() && qw.intersect(seenWords).isEmpty() && qw.intersect(ow).isNotEmpty()) return offNote(q)
        ran.add(q)
        return null
    }
}
