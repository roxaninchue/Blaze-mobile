package com.blaze.assistant.ui

import com.blaze.assistant.ui.RichReplies.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.2 regression from the phone (0.46.1): "World's best-selling cars" pie chart drawn as a grey code box labelled
 * `CHART {"TYPE":"PIE","TITLE":"WORLD…` with a Copy button. Cause: the model wrote the JSON on the fence's opening line
 * ("```chart {…}", as the prompt's own examples read), so the whole line became the code block's language and nothing matched.
 * Every variant now draws the chart; anything unrepairable is text / a table, never raw JSON in a code box.
 */
class ChartFenceTest {
    private val json = """{"type":"pie","title":"World's best-selling cars 2025 (share of the top ten)","labels":["Toyota RAV4","Tesla Model Y","Toyota Corolla","Honda CR-V","Toyota Camry","Ford F-Series","Hyundai Tucson","Toyota Hilux","Nissan Sentra","Kia Sportage"],"series":[{"name":"Units sold (millions)","values":[1.07,1.03,0.98,0.79,0.68,0.66,0.62,0.58,0.55,0.53]}],"unit":"M"}"""
    private val before = "2026 isn't over yet, so full-year numbers don't exist \u2014 this is the latest complete global count (2025), and it's the share among the ten bestsellers, not of all cars sold.\n\n"
    private val after = "\n\nThe Toyota RAV4 takes the top slice at just over a million, with the Tesla Model Y right behind it and the Corolla third."

    private fun blocks(md: String) = RichReplies.expand(AnswerCards.parse(md))
    private fun rawCode(b: List<Block>) = b.any { it is Block.Plain && it.seg is AnswerCards.Seg.Code }
    private fun pie(b: List<Block>): RichKit.Chart {
        assertFalse("raw code box: $b", rawCode(b))
        val c = b.filterIsInstance<Block.Chart>().single().chart
        assertEquals(RichKit.ChartType.PIE, c.type)
        return c
    }

    @Test fun theExactPhoneReply_jsonOnTheFenceLine() {
        val c = pie(blocks(before + "```chart " + json + "\n```" + after))
        assertEquals(RichKit.MAX_SLICES, c.labels.size); assertEquals("Toyota RAV4", c.labels[0]); assertEquals("Other", c.labels.last())   // ten slices: top 7 + Other
        assertTrue(c.title.startsWith("World's best-selling cars 2025"))
        // And the text around it is still there.
        val b = blocks(before + "```chart " + json + "\n```" + after)
        assertTrue(b.any { it is Block.Plain && it.seg is AnswerCards.Seg.Text && (it.seg as AnswerCards.Seg.Text).md.contains("RAV4 takes the top slice") })
    }

    @Test fun variants_uppercaseOneLineJsonFenceKindInBodyUppercaseKeys() {
        pie(blocks(before + "```CHART " + json + "```" + after))                        // upper-case kind, closed on the same line
        pie(blocks(before + "```Chart\n" + json + "\n```" + after))                     // capitalised kind on its own line
        pie(blocks(before + "```\nchart " + json + "\n```" + after))                    // kind word inside the fence
        pie(blocks(before + "```json\n" + json + "\n```" + after))                      // the chart's JSON in a json fence
        pie(blocks(before + "```json chart " + json + "\n```" + after))                 // json + chart on the opener
        pie(blocks(before + "```" + json + "```" + after))                              // bare one-line fence
        pie(blocks(before + "chart " + json + after))                                   // inline, no fence
        val upper = json.replace("\"type\"", "\"TYPE\"").replace("\"title\"", "\"TITLE\"").replace("\"labels\"", "\"LABELS\"")
            .replace("\"series\"", "\"SERIES\"").replace("\"values\"", "\"VALUES\"").replace("\"pie\"", "\"PIE\"")
        assertEquals(RichKit.MAX_SLICES, pie(blocks(before + "```chart " + upper + "\n```" + after)).labels.size)   // upper-case keys and type
    }

    @Test fun truncatedAndSingleQuotedStillDraw() {
        val cut = json.substring(0, json.indexOf("0.55") + 4)        // cut off inside the values array, fence never closed
        val b = blocks(before + "```chart " + cut)
        assertFalse(rawCode(b))
        val p = b.filterIsInstance<Block.Pending>().singleOrNull()
        assertTrue("a cut-off chart shows a chart or readable text: $b", p == null || p.fallback is Block.Chart || p.fallback is Block.Plain)
        assertFalse(rawCode(blocks("```chart\n" + json.replace('"', '\'') + "\n```")))   // single-quoted: never a raw code box
    }

    @Test fun barAndLineRenderToo() {
        val bar = """{"type":"BAR","title":"Sales","labels":["Jan","Feb"],"series":[{"name":"K","values":[12,19]}]}"""
        assertEquals(RichKit.ChartType.BAR, blocks("```chart $bar\n```").filterIsInstance<Block.Chart>().single().chart.type)
        assertEquals(RichKit.ChartType.LINE, blocks("```chart\n" + bar.replace("BAR", "line") + "\n```").filterIsInstance<Block.Chart>().single().chart.type)
    }

    @Test fun unrepairableIsTextNeverACodeBox() {
        val b = blocks(before + "```chart {\"type\":\"pie\",\"title\":\"Cars\",\"labels\":[\"RAV4\",\"Model Y\"],\"series\":[{\"values\":[\"lots\",\"many\"]}]}\n```")
        assertFalse(rawCode(b))
        assertTrue(b.none { it is Block.Plain && it.seg is AnswerCards.Seg.Text && (it.seg as AnswerCards.Seg.Text).md.contains("{\"") })
        // Ordinary code is still code.
        assertTrue(rawCode(blocks("```kotlin\nval x = mapOf(\"a\" to 1)\n```")))
        assertTrue(rawCode(blocks("```json\n{\"name\":\"Blaze\",\"version\":2}\n```")))
    }

    @Test fun promptShowsTheKindAloneOnTheFenceLine() {
        assertTrue(RichReplies.PROMPT.contains("kind word ALONE on the opening"))
    }
}
