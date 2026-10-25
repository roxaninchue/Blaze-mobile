package com.blaze.assistant.agent

/**
 * 0.45.24 memory tidy-up. Seen on a phone: Blaze kept "Likes jazz", "Likes jazz music" and "Enjoys jazz, mostly
 * live" as three lines, and some lines ran to several paragraphs.
 *
 *  - A normal save keeps at most [Mind.TEXT_MAX] characters, cut on a word / letter boundary ([cap]).
 *  - A save that is a near-copy of a saved line is skipped or replaces it ([Mind.decide] uses [nearDup] / [covers]).
 *  - Merging several related lines into one ([combine]) may keep up to [Mind.MERGED_MAX] characters. Merge is offered
 *    as the memory_merge tool, on the Memory screen (select, then Merge) and as an automatic pass over near-duplicates
 *    ([groups], after the background memory review).
 *  - Lines already stored are never cut: an edit keeps up to the line's current length, a merge that would not fit in
 *    [Mind.MERGED_MAX] is refused instead of trimmed.
 * Pure (MemoryMergeTest).
 */
object MemoryMerge {
    /** Two lines this close (word Jaccard) say the same thing. */
    const val NEAR = 0.85
    /** Lines about the same subject this close are folded together by the automatic pass. */
    const val GROUP = 0.6
    /** At most this many lines in one automatic merge. */
    const val GROUP_MAX = 6

    /** [text] cut to at most [max] characters: at a space when one is near, never inside a letter or an emoji, with "…". */
    fun cap(text: String, max: Int): String {
        if (text.length <= max) return text
        val room = (max - 1).coerceAtLeast(1)
        var cut = Scripts.safeCut(text, room)
        val space = text.lastIndexOf(' ', cut)
        if (space >= room * 3 / 4) cut = space
        return text.substring(0, cut).trimEnd(' ', ',', ';', ':', '-') + "\u2026"
    }

    /** Lowercase letters and digits only, single spaces ("Likes jazz!" and "likes  jazz" are the same). */
    fun norm(s: String): String = s.lowercase().replace(Mind.INFERRED, " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** True when [a] and [b] are the same line in other words. */
    fun nearDup(a: String, b: String): Boolean = norm(a) == norm(b) || Mind.jaccard(a, b) >= NEAR

    /** True when every word of [small] (3+ words) is already in [big]: saving [small] adds nothing. */
    fun covers(big: String, small: String): Boolean {
        val s = Mind.words(small); if (s.size < 3) return false
        return Mind.words(big).containsAll(s)
    }

    /**
     * One line from [texts], oldest first: each distinct piece once (a piece whose words are all in an earlier piece
     * is dropped), joined with "; ". "(inferred)" stays only when every line was inferred. Null when it would not fit
     * in [max] (nothing is ever trimmed by a merge).
     */
    fun combine(texts: List<String>, max: Int = Mind.MERGED_MAX): String? {
        val all = texts.map { Mind.squash(it) }.filter { it.isNotBlank() }
        if (all.isEmpty()) return null
        val inferred = all.all { it.contains(Mind.INFERRED, true) }
        val pieces = ArrayList<String>()
        for (t in all) {
            val bare = t.replace(Mind.INFERRED, "", ignoreCase = true).trim().trimEnd('.', ';', ' ')
            for (p0 in bare.split(Regex("(?<=[.!?;])\\s+"))) {
                val p = p0.trim().trimEnd('.', ';', ' ')
                if (p.isEmpty()) continue
                val w = Mind.words(p)
                // Drop a piece already said; a longer piece replaces a shorter one it covers.
                if (pieces.any { norm(it) == norm(p) || (w.isNotEmpty() && Mind.words(it).containsAll(w)) }) continue
                pieces.removeAll { q -> Mind.words(q).let { it.isNotEmpty() && w.containsAll(it) } }
                pieces += p
            }
        }
        var out = pieces.joinToString("; ")
        if (inferred) out = "$out ${Mind.INFERRED}"
        return out.takeIf { it.length <= max }
    }

    /** The subject and kind the merged line keeps: the most common among [mems], the oldest on a tie. */
    fun pick(mems: List<Mind.Mem>): Pair<String, String> {
        val sorted = mems.sortedWith(compareBy({ it.created }, { it.id }))
        val subject = sorted.groupBy { it.subject.lowercase() }.maxByOrNull { it.value.size }?.value?.first()?.subject ?: "You"
        val kind = sorted.groupBy { it.kind }.maxByOrNull { it.value.size }?.key ?: "fact"
        return kind to subject
    }

    /** Related subjects of all [mems], each once. */
    fun related(mems: List<Mind.Mem>): String =
        mems.flatMap { it.related.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(", ").take(200)

    /**
     * Near-duplicate groups for the automatic pass: lines about the same subject at least [GROUP] alike, or any two
     * lines that are [nearDup] / one [covers] the other. Each group has 2..[GROUP_MAX] lines, oldest first, and its
     * merged text fits in [Mind.MERGED_MAX].
     */
    fun groups(mems: List<Mind.Mem>): List<List<Mind.Mem>> {
        val sorted = mems.sortedWith(compareBy({ it.created }, { it.id }))
        val used = HashSet<Long>()
        val out = ArrayList<List<Mind.Mem>>()
        for (m in sorted) {
            if (m.id in used) continue
            val g = arrayListOf(m)
            for (o in sorted) {
                if (o.id == m.id || o.id in used || g.size >= GROUP_MAX) continue
                if (g.any { alike(it, o) }) g += o
            }
            if (g.size < 2) continue
            if (combine(g.map { it.text }) == null) continue
            used += g.map { it.id }
            out += g
        }
        return out
    }

    private fun alike(a: Mind.Mem, b: Mind.Mem): Boolean =
        nearDup(a.text, b.text) || covers(a.text, b.text) || covers(b.text, a.text) ||
            (a.subject.equals(b.subject, true) && a.kind == b.kind && Mind.jaccard(a.text, b.text) >= GROUP)
}
