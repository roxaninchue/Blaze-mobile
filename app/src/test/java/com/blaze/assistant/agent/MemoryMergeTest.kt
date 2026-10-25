package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 0.45.24: memory cap, near-duplicate saves, merge (tool, screen, automatic pass), and no cut of stored lines. */
class MemoryMergeTest {
    private lateinit var dir: File
    private val sqls = ArrayList<Sql>()

    @Before fun setUp() { dir = Files.createTempDirectory("memmerge").toFile() }
    @After fun tearDown() { Mind.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun open(): MindStore {
        val s = JdbcSql("jdbc:sqlite:" + File(dir, "blaze.db").path).also { sqls += it }
        return MindStore(BlazeDb(s), s).also { Mind.use(it) }
    }

    private fun mem(id: Long, text: String, subject: String = "You", kind: String = "preference", created: Long = id) =
        Mind.Mem(id, kind, subject, text, created = created, updated = created)

    // ---------------- pure ----------------

    @Test fun capCutsAtAWordAndNeverInsideAnEmoji() {
        val long = "word ".repeat(100).trim()
        val c = MemoryMerge.cap(long, Mind.TEXT_MAX)
        assertTrue(c.length <= Mind.TEXT_MAX)
        assertTrue(c.endsWith("word\u2026"))
        val emoji = "a".repeat(278) + "\uD83D\uDE00\uD83D\uDE00"
        val e = MemoryMerge.cap(emoji, 280)
        assertFalse(Character.isHighSurrogate(e[e.length - 2]) && !Character.isLowSurrogate(e[e.length - 1]))
        assertEquals("short", MemoryMerge.cap("short", 280))
    }

    @Test fun cleanCapsANormalLineAndKeepsTheInferredMark() {
        assertTrue(Mind.clean("x ".repeat(400)).length <= Mind.TEXT_MAX)
        val inf = Mind.clean("likes ".repeat(100), inferred = true)
        assertTrue(inf.length <= Mind.TEXT_MAX && inf.endsWith(Mind.INFERRED))
        assertEquals(900, Mind.clean("y".repeat(900), max = Mind.MERGED_MAX).length)
    }

    @Test fun nearDuplicatesAreSkipped() {
        val have = listOf(mem(1, "Likes jazz music, mostly live shows"))
        assertEquals(Mind.Decision.Same(1), Mind.decide(have, "preference", "Music", "likes jazz music, mostly live shows!"))
        assertEquals(Mind.Decision.Same(1), Mind.decide(have, "fact", "Other", "Likes jazz music live"))
        assertEquals(Mind.Decision.Insert, Mind.decide(have, "preference", "Food", "No pork on weekdays"))
    }

    @Test fun aMergedLineIsNeverOverwrittenByAShortSave() {
        val merged = "Works at Acme as an engineer; " + "on the payments team, ".repeat(15) + "since 2024"
        val have = listOf(mem(1, merged, "Work", "profile"))
        assertTrue(merged.length > Mind.TEXT_MAX)
        val d = Mind.decide(have, "profile", "Work", "Works at Zed as an engineer on the payments team")
        assertTrue(d is Mind.Decision.Insert || d is Mind.Decision.Same)
    }

    @Test fun combineKeepsEveryFactOnce() {
        val out = MemoryMerge.combine(listOf("Likes jazz", "Likes jazz. Goes to live shows on Fridays", "Goes to live shows on Fridays"))
        assertEquals("Likes jazz; Goes to live shows on Fridays", out)
        assertEquals("Lives in Lusaka (inferred)", MemoryMerge.combine(listOf("Lives in Lusaka (inferred)", "lives in Lusaka (inferred)")))
        assertNull(MemoryMerge.combine(listOf("a".repeat(700), "b".repeat(700))))
    }

    @Test fun groupsFindNearDuplicatesOnly() {
        val mems = listOf(
            mem(1, "Sister is Mary, a nurse in Kitwe", "Mary", "person"),
            mem(2, "Sister is Mary, a nurse in Kitwe hospital", "Mary", "person"),
            mem(3, "No pork on weekdays", "Food"),
            mem(4, "Prefers window seats on long flights", "Travel"),
        )
        val g = MemoryMerge.groups(mems)
        assertEquals(1, g.size)
        assertEquals(listOf(1L, 2L), g.first().map { it.id })
    }

    // ---------------- on the store ----------------

    @Test fun saveSkipsANearCopy() {
        open()
        assertEquals("saved", Mind.save("preference", "Music", "Likes jazz music, mostly live shows").first)
        assertEquals("same", Mind.save("preference", "Music", "likes jazz music - mostly live shows").first)
        assertEquals(1, Mind.all().size)
    }

    @Test fun normalSaveIsCapped() {
        open()
        Mind.save("fact", "You", "Long note " + "about many things ".repeat(40))
        assertTrue(Mind.all().single().text.length <= Mind.TEXT_MAX)
    }

    @Test fun mergeToolJoinsLinesUpToTheLargerCap() {
        open()
        val a = Mind.save("person", "Mary", "Sister is Mary").second
        val b = Mind.save("person", "Mary", "Mary is a nurse at Kitwe Central Hospital").second
        val c = Mind.save("person", "Mary", "Mary's birthday is 4 June").second
        val r = Mind.run("memory_merge", JSONObject().put("ids", JSONArray(listOf(a, b, c))))
        assertEquals("Merged 3 lines into #$a.", r)
        val all = Mind.all()
        assertEquals(1, all.size)
        assertEquals("Sister is Mary; Mary is a nurse at Kitwe Central Hospital; Mary's birthday is 4 June", all.single().text)
        assertEquals("Mary", all.single().subject)

        // The model may write the merged line itself, longer than a normal save allows.
        val x = Mind.save("project", "Trip", "Trip to Livingstone in May").second
        val y = Mind.save("project", "Lodge", "Booked lodge near the falls").second
        val long = "Trip to Livingstone in May with the family; " + "booked a lodge near the falls with breakfast and a pool, ".repeat(10).trim()
        assertTrue(long.length in (Mind.TEXT_MAX + 1)..Mind.MERGED_MAX)
        assertTrue(Mind.run("memory_merge", JSONObject().put("ids", JSONArray(listOf(x, y))).put("text", long)).startsWith("Merged 2"))
        assertEquals(long, Mind.all().first { it.id == x }.text)
    }

    @Test fun mergeRefusesInsteadOfCutting() {
        open()
        val a = Mind.save("fact", "A", "a".repeat(250), max = Mind.MERGED_MAX).second
        val b = Mind.save("fact", "B", "b ".repeat(600).trim(), max = Mind.MERGED_MAX).second
        val (id, why) = Mind.merge(listOf(a, b))
        assertEquals(0L, id)
        assertTrue(why.startsWith("Too long"))
        assertEquals(2, Mind.all().size)
    }

    @Test fun editingALongStoredLineNeverCutsIt() {
        val st = open()
        // A line saved before the cap (up to 600 characters).
        val old = "Old long line " + "with lots of detail ".repeat(25)
        val id = st.save("fact", "You", old, max = Mind.MERGED_MAX).second
        val before = st.mem(id)!!.text
        assertTrue(before.length > Mind.TEXT_MAX)
        assertTrue(Mind.update(id, before + " and one more", null, null))
        assertEquals(before + " and one more", st.mem(id)!!.text)
        assertTrue(st.mem(id)!!.text.length > Mind.TEXT_MAX)
    }

    @Test fun importKeepsLongLegacyLinesWhole() {
        val st = open()
        val long = "Legacy fact " + "kept whole ".repeat(50)
        assertEquals(1, Mind.importFacts(st, listOf(long)))
        assertEquals(Mind.clean(long, max = Mind.MERGED_MAX), st.all().single().text)
        // Importing it again (a restore) adds nothing and doesn't cut it.
        assertEquals(0, Mind.importFacts(st, listOf(long)))
        assertEquals(1, st.all().size)
    }

    @Test fun consolidateMergesNearDuplicates() {
        val st = open()
        st.save("person", "Mary", "Sister is Mary, a nurse in Kitwe", now = 1000)
        st.save("person", "Mary", "Prefers tea", now = 2000)
        // Written by another device / an older Blaze, so the save-time dedupe never saw them together.
        st.sql.exec("INSERT INTO memories(text, norm, category, created, updated) VALUES(?,?,?,?,?)",
            "Sister is Mary, a nurse in Kitwe hospital", BlazeDb.norm("Sister is Mary, a nurse in Kitwe hospital"), "person", 3, 3)
        assertEquals(3, st.all().size)
        assertEquals(1, Mind.consolidate())
        val left = st.all()
        assertEquals(2, left.size)
        assertNotNull(left.firstOrNull { it.text == "Sister is Mary, a nurse in Kitwe hospital" || it.text.contains("Kitwe hospital") })
    }
}
