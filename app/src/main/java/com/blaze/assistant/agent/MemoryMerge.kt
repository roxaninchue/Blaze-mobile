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

    /** Related subjects of two lines, each once (a merge or a duplicate save keeps the links of both). */
    fun unionRelated(a: String, b: String): String =
        (a.split(',') + b.split(',')).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(", ").take(200)

    /** One line's precomputed features (computed once per pass, not per pair). */
    private class Feat(val m: Mind.Mem, val norm: String, val words: Set<String>, val key: Set<String>, val entity: String, val neg: Boolean) {
        val tokens: Set<String> = words + key + ("#" + norm)
    }

    private fun feat(m: Mind.Mem) = Feat(m, norm(m.text), Mind.words(m.text), MemoryKey.key(m.text), MemoryKey.entity(m.subject), MemoryKey.negative(m.text))

    private fun jac(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    private fun coversW(big: Set<String>, small: Set<String>) = small.size >= 3 && big.containsAll(small)

    private fun alike(a: Feat, b: Feat): Boolean {
        val j = jac(a.words, b.words)
        return a.norm == b.norm || j >= NEAR || coversW(a.words, b.words) || coversW(b.words, a.words) ||
            // 0.45.29: and their content keys overlap too: "Likes jazz; Loves jazz a lot" and "Likes chess; Loves chess a lot"
            // share most words but are different facts (a second Tidy up merged unrelated preferences together).
            (a.m.subject.equals(b.m.subject, true) && a.m.kind == b.m.kind && j >= GROUP && jac(a.key, b.key) >= 0.5) ||
            (a.m.kind == b.m.kind && a.entity == b.entity && MemoryKey.sameFact(a.key, b.key, a.neg, b.neg))
    }

    /**
     * Near-duplicate groups for the automatic pass: lines about the same subject at least [GROUP] alike, any two lines
     * that are [nearDup] / one [covers] the other, or the same fact about the same subject and kind in other words
     * ([MemoryKey.sameFact]). Each group has 2..[GROUP_MAX] lines, oldest first, and its merged text fits in
     * [Mind.MERGED_MAX].
     *
     * 0.45.29 (Tidy up froze and crashed on 253 lines): every line's words are worked out once, and a pair is only
     * compared when the line shares a word with the group (lines with nothing in common can't be alike), so a pass over
     * a few hundred lines takes milliseconds. [cancelled] stops it between lines.
     */
    fun groups(mems: List<Mind.Mem>, cancelled: () -> Boolean = { false }): List<List<Mind.Mem>> {
        val feats = mems.sortedWith(compareBy({ it.created }, { it.id })).map { feat(it) }
        val used = BooleanArray(feats.size)
        val out = ArrayList<List<Mind.Mem>>()
        for (i in feats.indices) {
            if (used[i]) continue
            if (cancelled()) break
            val g = arrayListOf(feats[i])
            val toks = HashSet(feats[i].tokens)
            for (o in feats.indices) {
                if (o == i || used[o] || g.size >= GROUP_MAX) continue
                val f = feats[o]
                if (f.tokens.none { it in toks }) continue
                if (g.any { alike(it, f) }) { g += f; toks += f.tokens }
            }
            if (g.size < 2) continue
            if (combine(g.map { it.m.text }) == null) continue
            for (f in g) used[feats.indexOf(f)] = true
            out += g.map { it.m }
        }
        return out
    }
}

/**
 * 0.45.29 write-time de-duplication (and the Tidy up pass): the same fact said another way. A line's key is its content
 * words, lower-cased, with filler words left out, a light stem ("plays" / "playing" -> "play") and the usual ways of
 * saying you like something folded together ("loves", "enjoys", "a fan of" -> "like"). Two lines about the same entity
 * and kind are the same fact when their keys mostly overlap, or one key is the other plus a detail or two (then the
 * fuller wording is kept). A "not" / "never" / "dislike" on one side only means a different fact. Numbers count, so
 * "wakes at 6" and "wakes at 7" stay apart. Pure (MemoryDedupTest).
 */
object MemoryKey {
    private val STOP = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am", "to", "of", "in", "on", "at", "for", "and", "or",
        "but", "my", "me", "i", "you", "your", "user", "with", "so", "just", "also", "really", "very", "quite", "some", "bit",
        "little", "lot", "lots", "much", "mostly", "usually", "often", "always", "that", "this", "it", "its", "has", "have",
        "had", "does", "do", "did", "than", "as", "by", "from", "about", "into", "big", "huge", "inferred", "they", "them",
        "their", "he", "she", "his", "her", "s",
    )
    private val SYN = mapOf(
        "love" to "like", "loves" to "like", "loved" to "like", "loving" to "like", "enjoy" to "like", "enjoys" to "like",
        "enjoyed" to "like", "enjoying" to "like", "adore" to "like", "adores" to "like", "fan" to "like", "fans" to "like", "likes" to "like", "liked" to "like",
        "dislikes" to "dislike", "disliked" to "dislike", "hate" to "dislike", "hates" to "dislike", "hated" to "dislike",
        "prefers" to "prefer", "preferred" to "prefer", "favourite" to "favorite", "favourites" to "favorite", "favorites" to "favorite",
    )
    private val NEG = setOf("not", "no", "never", "nt", "cannot", "without", "dislike")

    /** Words, lower case: "doesn't" -> "doesnt", digits kept at any length, letters from two up. */
    fun tokens(text: String): List<String> = text.lowercase().replace("\u2019", "'").replace("n't", "nt")
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && (it.length > 1 || it[0].isDigit()) }

    fun stem(w0: String): String {
        SYN[w0]?.let { return it }
        var w = w0
        if (w.length > 5 && w.endsWith("ing")) w = w.dropLast(3)
        else if (w.length > 4 && w.endsWith("ies")) w = w.dropLast(3) + "y"
        else if (w.length > 4 && w.endsWith("ed")) w = w.dropLast(2)
        else if (w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes"))) w = w.dropLast(2)
        else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) w = w.dropLast(1)
        return SYN[w] ?: w
    }

    /** True when the line says "not" / "never" / "dislikes" (a different fact from the plain one). */
    fun negative(text: String): Boolean = tokens(text).any { it in NEG || it.endsWith("nt") && it.length in 4..8 && it.dropLast(2) in setOf("does", "do", "did", "is", "are", "was", "were", "ca", "wo", "has", "have", "should", "would", "could") || SYN[it] == "dislike" }

    /** The content-word key of a line. */
    fun key(text: String): Set<String> = tokens(text).map { stem(it) }.filter { it !in STOP && it !in NEG }.toSet()

    /** Who a line is about, for matching: the user under any of their names ("You", "me", "user") is one entity. */
    fun entity(subject: String): String {
        val s = subject.trim().lowercase().removeSuffix("'s").replace(Regex("\\s+"), " ")
        return if (s.isEmpty() || s in setOf("you", "me", "i", "myself", "user", "the user", "yourself", "self", "owner")) "you" else s
    }

    /** True when keys [a] and [b] (with their negation) are the same fact. */
    fun sameFact(a: Set<String>, b: Set<String>, negA: Boolean, negB: Boolean): Boolean {
        if (a.isEmpty() || b.isEmpty() || negA != negB) return false
        val inter = a.count { it in b }
        if (inter == 0) return false
        val union = a.size + b.size - inter
        if (inter.toDouble() / union >= 0.6) return true
        val (small, big) = if (a.size <= b.size) a to b else b to a
        return small.size >= 2 && inter == small.size && big.size - small.size <= 2
    }

    /** True when [newer] says more than [older] (a fuller key, or the same key in more words). */
    fun moreSpecific(newKey: Set<String>, oldKey: Set<String>, newer: String, older: String): Boolean =
        newKey.size > oldKey.size || (newKey.size == oldKey.size && newer.trim().length > older.trim().length + 3)
}
