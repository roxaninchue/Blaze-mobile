package com.blaze.assistant.composer

/**
 * 0.47 Spotlight: small, allocation-light fuzzy scoring (pure, FuzzyTest / SpotlightSearchTest).
 * 100 exact · 90 prefix · 80 word prefix · 70 every query word starts a word · 60 contains · 45 one typo in a word
 * or swap (query ≥ 4 letters) · 20–35 letters in order (query ≥ 3 letters, compact) · 0 no match.
 */
object Fuzzy {
    private val split = Regex("""[^\p{L}\p{N}]+""")

    fun norm(s: String): String = s.lowercase().trim()

    fun words(s: String): List<String> = norm(s).split(split).filter { it.isNotEmpty() }

    fun score(query: String, text: String): Int {
        val q = norm(query); val t = norm(text)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 100
        if (t.startsWith(q)) return 90
        val tw = words(t)
        if (tw.any { it.startsWith(q) }) return 80
        val qw = words(q)
        if (qw.size > 1 && qw.all { w -> tw.any { it.startsWith(w) } }) return 70
        if (t.contains(q)) return 60
        if (q.length >= 4 && qw.size == 1 && tw.any { typo1(q, it) || (it.length > q.length && typo1(q, it.take(q.length))) }) return 45
        if (q.length >= 3) { val s = inOrder(q.replace(" ", ""), t); if (s > 0) return s }
        return 0
    }

    /** Best score of [query] over several fields of one thing (title, subtitle, keywords); later fields count a bit less. */
    fun best(query: String, fields: List<String>): Int {
        var best = 0
        fields.forEachIndexed { i, f -> val s = score(query, f) - (if (i == 0) 0 else 5); if (s > best) best = s }
        return best
    }

    /** At most one edit (insert, delete or substitute) apart. */
    fun typo1(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length == b.length && a.length >= 2) {   // one swap of neighbours ("sintle" → "sintel") counts as one typo
            val d = a.indices.filter { a[it] != b[it] }
            if (d.size == 2 && d[1] == d[0] + 1 && a[d[0]] == b[d[1]] && a[d[1]] == b[d[0]]) return true
        }
        val la = a.length; val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when { la > lb -> i++; lb > la -> j++; else -> { i++; j++ } }
        }
        return edits + (la - i) + (lb - j) <= 1
    }

    /** Letters of [q] appear in order in [t] within a window ≤ 3× the query: 20..35, else 0. */
    fun inOrder(q: String, t: String): Int {
        if (q.isEmpty()) return 0
        var start = -1; var qi = 0
        for (ti in t.indices) {
            if (t[ti] == q[qi]) { if (qi == 0) start = ti; qi++; if (qi == q.length) {
                val span = ti - start + 1
                return if (span <= q.length * 3) 35 - ((span - q.length) * 15 / (q.length * 2)).coerceIn(0, 15) else 0
            } }
        }
        return 0
    }
}
