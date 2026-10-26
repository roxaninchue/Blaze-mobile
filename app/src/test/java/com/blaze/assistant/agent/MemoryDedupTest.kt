package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.store.Sql
import com.blaze.assistant.ui.MemGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.45.29: duplicates are stopped when a memory is saved (every save path goes through Store.save -> Mind.decide), and
 * Settings › Memory › Tidy up duplicates no longer freezes or crashes (253 memories / 564 links on a user's phone):
 * fast grouping, one transaction per merge, links kept, progress and Stop, nothing lost.
 */
class MemoryDedupTest {
    private lateinit var dir: File
    private val sqls = ArrayList<Sql>()

    @Before fun setUp() { dir = Files.createTempDirectory("memdedup").toFile() }
    @After fun tearDown() { Mind.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun open(): MindStore {
        val s = JdbcSql("jdbc:sqlite:" + File(dir, "blaze.db").path).also { sqls += it }
        return MindStore(BlazeDb(s), s).also { Mind.use(it) }
    }

    // ---------------- write time ----------------

    @Test fun sameFactThreeWaysIsOneMemory() {
        val st = open()
        assertEquals("saved", Mind.save("preference", "You", "Likes jazz", related = "Music").first)
        assertTrue(Mind.save("preference", "You", "Loves jazz music", related = "Evenings").first in setOf("same", "updated"))
        assertTrue(Mind.save("preference", "user", "Enjoys jazz").first in setOf("same", "updated"))
        val all = st.all()
        assertEquals(all.toString(), 1, all.size)
        // The more specific wording is kept and the links of every save are united.
        assertEquals("Loves jazz music", all.single().text)
        assertTrue(all.single().related, all.single().related.contains("Music") && all.single().related.contains("Evenings"))
    }

    @Test fun distinctFactsStillSaveSeparately() {
        val st = open()
        val facts = listOf("Likes jazz", "Likes rock", "Wakes at 6 on weekdays", "Doesn't like coffee", "Likes green tea",
            "Lives in Ndola", "Works at Acme", "Plays guitar at church")
        for (t in facts) assertEquals(t, "saved", Mind.save("preference", "You", t).first)
        assertEquals("saved", Mind.save("person", "Mary", "Sister, a nurse in Kitwe").first)
        assertEquals("saved", Mind.save("person", "Luke", "Plays chess on Sundays").first)
        assertEquals(facts.size + 2, st.all().size)
    }

    @Test fun keyRules() {
        assertEquals(setOf("like", "jazz"), MemoryKey.key("Likes jazz"))
        assertEquals(MemoryKey.key("Enjoys jazz"), MemoryKey.key("Loves jazz"))
        assertTrue(MemoryKey.sameFact(MemoryKey.key("Likes jazz"), MemoryKey.key("Loves jazz music"), false, false))
        assertFalse(MemoryKey.sameFact(MemoryKey.key("Wakes at 6"), MemoryKey.key("Wakes at 7"), false, false))
        assertTrue(MemoryKey.negative("Doesn't like coffee")); assertFalse(MemoryKey.negative("Likes coffee"))
        assertFalse(MemoryKey.sameFact(MemoryKey.key("Doesn't like coffee"), MemoryKey.key("Likes coffee"), true, false))
        assertEquals("you", MemoryKey.entity("User")); assertEquals("you", MemoryKey.entity(" me ")); assertEquals("mary", MemoryKey.entity("Mary's"))
    }

    // ---------------- Tidy up ----------------

    private val people = listOf("Mary", "Luke", "Gloria", "Mutale", "Ana", "Sam", "Joe", "Ruth", "Tom", "Eve")
    private val topics = listOf("church", "guitar", "jazz", "running", "chess", "cooking", "football", "reading", "coding", "gardening",
        "swimming", "painting", "hiking", "dancing", "photography")

    /** Made-up words (consonant-vowel pairs), so the filler lines share nothing by accident. */
    private val vocab: List<String> = run {
        val c = "bdfgklmnprstvz"; val v = "aeiou"
        val out = ArrayList<String>()
        for (a in c) for (b in v) for (d in c) for (e in v) { out += "$a$b$d$e"; if (out.size >= 2000) return@run out }
        out
    }
    private var seed = 12345L
    private fun next(n: Int): Int { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % n).toInt() }
    private fun sentence(k: Int) = (0 until k).joinToString(" ") { vocab[next(vocab.size)] }

    /** ~300 memories with near-duplicates among them, written straight to the table (like an old sync or another device). */
    private fun fill(st: MindStore): List<Mind.Mem> {
        var n = 0
        fun put(kind: String, subject: String, text: String, related: String) {
            n++
            st.sql.exec("INSERT INTO memories(text, norm, category, created, updated) VALUES(?,?,?,?,?)", text, BlazeDb.norm(text), kind, n.toLong(), n.toLong())
            val id = st.sql.query("SELECT last_insert_rowid()") { it.long(0) }.first()
            st.sql.exec("INSERT INTO mind_mem(id, created, subject, related, source_turn) VALUES(?,?,?,?,?)", id, n.toLong(), subject, related, (n / 3).toLong())
        }
        for ((j, t) in topics.withIndex()) {
            put("preference", "You", "Likes $t", "Weekends")
            put("preference", "You", "Loves $t a lot", people[j % people.size])
            put("preference", "user", "Enjoys $t", "Hobbies")
        }
        for ((i, p) in people.withIndex()) for (k in 0 until 9) put("person", p, sentence(6), people[(i + 1) % people.size])
        for (k in 0 until 170) put("fact", "Project ${k % 12}", sentence(7), "Project ${(k + 1) % 12}")
        return st.all()
    }

    @Test fun tidyThreeHundredIsFastAndKeepsEveryLink() {
        val st = open()
        val before = fill(st)
        assertEquals(305, before.size)
        val relatedBefore = before.flatMap { it.related.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val t0 = System.nanoTime()
        var last = -1 to -1
        val merged = Mind.consolidate(progress = { d, t -> last = d to t })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("tidy took $ms ms", ms < 5_000)
        assertEquals(15, merged)                                  // one group per topic preference, nothing else folded
        assertEquals(last.second, last.first)                     // progress reached the end
        val after = st.all()
        assertEquals(before.size - 30, after.size)
        // Nothing lost: every topic preference survives in one line, every other line is untouched.
        for (t in topics) assertEquals(t, 1, after.count { it.kind == "preference" && Regex("\\b$t\\b").containsMatchIn(it.text) })
        assertEquals(before.filter { it.kind != "preference" }.map { it.id to it.text }, after.filter { it.kind != "preference" }.map { it.id to it.text })
        // Links: every subject that was linked is still linked, and the graph's links all point at real nodes.
        val relatedAfter = after.flatMap { it.related.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(relatedBefore, relatedAfter)
        val g = MemGraph(after)
        assertTrue(g.edges.isNotEmpty())
        for (e in g.edges) assertTrue(e.a in g.nodes.indices && e.b in g.nodes.indices && e.a != e.b)
        val ids = after.map { it.id }.toSet()
        assertEquals(after.size, ids.size)
        // A second pass finds nothing more.
        assertEquals(0, Mind.consolidate())
    }

    @Test fun tidyStopsCleanlyAndLosesNothing() {
        val st = open()
        val before = fill(st)
        var stop = false
        val n = Mind.consolidate(cancelled = { stop }, progress = { d, _ -> if (d >= 5) stop = true })
        assertEquals(5, n)
        val after = st.all()
        assertEquals(before.size - 2 * n, after.size)               // five groups merged 3 -> 1, everything else untouched
        for (t in topics) assertTrue(t, after.any { Regex("\\b$t\\b").containsMatchIn(it.text) })
    }

    @Test fun aFailedMergeChangesNothing() {
        val st = open()
        Mind.save("fact", "You", "Has a red bike")
        val id = st.all().single().id
        // Keeping a line that is gone fails as a whole: the other line is not deleted.
        Mind.save("fact", "Luke", "Plays chess on Sundays")
        val other = st.all().first { it.id != id }.id
        assertFalse(st.mergeInto(999_999L, "x", "fact", "You", "", listOf(other)))
        assertEquals(2, st.all().size)
    }
}
