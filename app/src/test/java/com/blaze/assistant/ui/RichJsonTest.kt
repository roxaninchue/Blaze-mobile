package com.blaze.assistant.ui

import com.blaze.assistant.ui.RichReplies.Block
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.0: rich blocks always draw. Regression for the "TABLE {"TITLE":"42 IDLE APPS · APP…" grey code box: the kind word inside
 * the fence / inline, single quotes, unquoted keys, a 42-row table cut off midway; and never raw rich JSON in a code box.
 */
class RichJsonTest {
    private fun apps42(): String {
        val rows = (1..42).joinToString(",") { i -> if (i % 7 == 0) "[\"App $i\",\"com.app$i\",\"\"]" else "[\"App $i\",\"com.app$i\",\"${i * 3} MB\"]" }
        return """{"title":"42 idle apps · App, package, size","columns":["App","Package","Size"],"rows":[$rows]}"""
    }
    /** The reply as it came: cut off in the middle of row 23. */
    private fun cut(): String = apps42().let { it.substring(0, it.indexOf("App 23") + 4) }
    private fun blocks(md: String) = RichReplies.expand(AnswerCards.parse(md))
    private fun rawCode(b: List<Block>) = b.any { it is Block.Plain && it.seg is AnswerCards.Seg.Code }

    @Test fun fullTableWith42RowsKeepsEveryRowAndMarksMissingSizes() {
        val t = RichKit.table(apps42())!!
        assertEquals(42, t.rows.size)
        assertEquals("\u2014", t.rows[6][2])   // App 7 had no size: shown as —, not dropped
    }

    @Test fun cutOff42RowTableRecoversTheCompleteRows() {
        val t = RichKit.table(cut())
        assertNotNull(t)
        assertEquals(22, t!!.rows.size)   // rows 1..22 were complete; the half row 23 is dropped
        assertEquals("42 idle apps · App, package, size", t.title)
        // As a whole reply (the fence never closed because the reply was cut): a table, never a code box.
        val b = blocks("Here are your idle apps:\n\n```table\n" + cut())
        val p = b.filterIsInstance<Block.Pending>().single()
        assertTrue(p.fallback is Block.Table)
        assertEquals(22, (p.fallback as Block.Table).table.rows.size)
    }

    @Test fun kindWordInsideTheFence() {
        for (md in listOf("```\ntable " + apps42() + "\n```", "```json\ntable " + apps42() + "\n```", "```table\ntable " + apps42() + "\n```",
                "```\nTABLE\n" + apps42() + "\n```")) {
            val b = blocks("Idle apps:\n\n$md")
            assertTrue(md.take(16), b.any { it is Block.Table && it.table.rows.size == 42 })
            assertFalse(rawCode(b))
        }
    }

    @Test fun inlineKindAndJson() {
        val b = blocks("Here you go: \n`table " + apps42() + "`\nAnything else?")
        assertTrue(b.any { it is Block.Table && it.table.rows.size == 42 })
        assertTrue(b.any { it is Block.Plain && (it.seg as? AnswerCards.Seg.Text)?.md?.contains("Anything else?") == true })
        // Inline and cut off.
        val c = blocks("Here:\ntable " + cut())
        assertFalse(rawCode(c)); assertTrue(c.any { it is Block.Table || it is Block.Pending })
    }

    @Test fun lenientRepairs() {
        val o = RichJson.parse("{title: 'Plans', columns: ['Plan','Price'], rows: [['Basic','K50'], ['Pro','K120'],]}") as JSONObject
        assertEquals("Plans", o.getString("title")); assertEquals(2, o.getJSONArray("rows").length())
        assertEquals("it's", (RichJson.parse("{'a': 'it\\'s'}") as JSONObject).getString("a"))
        assertEquals("say \"hi\"", (RichJson.parse("{'a': 'say \"hi\"'}") as JSONObject).getString("a"))
        assertNotNull(RichJson.parse("""{"a":[1,2,{"b":"c"""))
        assertNotNull(RichJson.parse("""{"labels":["a","b"],"values":[1,2"""))
        assertNull(RichJson.parse("no json here"))
        // true / false / null stay values, not keys.
        assertEquals(true, (RichJson.parse("{ok: true}") as JSONObject).getBoolean("ok"))
    }

    @Test fun sameRepairForEveryKind() {
        assertNotNull(RichKit.chart("{type: 'bar', labels: ['Jan','Feb'], series: [{name: 'Sales', values: [3, 5]}]}"))
        assertNotNull(RichKit.choices("['Yes', 'No'"))
        assertNotNull(RichKit.compare("{title:'A vs B', columns:['A','B'], rows:[['Price','K1','K2'],['Size','S'"))
        assertNotNull(RichKit.form("{title:'Book', fields:[{id:'name', label:'Name'}]"))
    }

    @Test fun unparseableClosedBlockIsTextNeverCode() {
        val b = blocks("```table\n{\"title\":\"Broken\",\"columns\":[\"A\",\"B\"],\"rows\":[[\"x\" \"y\"] [\"z\" \"w\"]]}\n```")
        assertFalse(rawCode(b))
        assertTrue(b.isNotEmpty())
        assertTrue(RichJson.salvage("chart", "{\"title\":\"Sales\" oops").contains("Sales"))
        assertFalse(RichJson.salvage("chart", "{\"title\":\"Sales\" oops").contains("{"))
    }

    @Test fun normalCodeIsUntouched() {
        val k = "```kotlin\nval table = mapOf(1 to 2)\n```"
        assertEquals(k, RichJson.fixSource(k))
        assertTrue(rawCode(blocks(k)))
        assertEquals("plain words, no json", RichJson.fixSource("plain words, no json"))
    }
}
