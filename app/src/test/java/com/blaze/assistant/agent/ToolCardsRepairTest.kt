package com.blaze.assistant.agent

import com.blaze.assistant.ui.RichKit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.0 rich consistency: tool data becomes an exact table card (every row, unknown sizes marked, nothing dropped), and a
 * block that still can't be drawn gets one silent re-ask whose fixed block is spliced into the reply.
 */
class ToolCardsRepairTest {
    private fun healthResult(n: Int = 42): String = "Unused for 30+ days, $n apps (app size only; data and cache come on top; 6 without a known size):\n" +
        (1..n).joinToString("\n") { i -> "App $i · ${if (i % 7 == 0) "size unknown" else "${i * 3} MB"} · last opened ${30 + i} days ago" }

    @Test fun deterministicCardKeepsEveryRow() {
        val c = ToolCards.card("phone_health", JSONObject().put("detail", "unused_apps"), healthResult())!!
        assertEquals(42, c.rows)
        val body = c.block.removePrefix("```table\n").substringBefore("\n```")
        val t = RichKit.table(body)!!
        assertEquals(42, t.rows.size)
        assertEquals(listOf("App", "Size", "Last opened"), t.headers)
        assertEquals("size unknown", t.rows[6][1])
        assertTrue(t.title.startsWith("Unused for 30+ days, 42 apps"))
    }

    @Test fun onlyListToolsAndRealListsGetCards() {
        assertNull(ToolCards.card("phone_health", JSONObject(), "Phone health 80/100 (B). Battery 50%"))
        assertNull(ToolCards.card("web_search", JSONObject(), healthResult()))
        assertNull(ToolCards.card("phone_health", JSONObject().put("detail", "unused_apps"), healthResult(3)))
    }

    @Test fun attachReplacesTheModelsOwnTableOrAppends() {
        val c = ToolCards.card("phone_health", JSONObject().put("detail", "unused_apps"), healthResult())!!
        val appended = ToolCards.attach("You have 42 idle apps; the games take the most space.", listOf(c))
        assertTrue(appended.startsWith("You have 42 idle apps")); assertTrue(appended.contains("```table\n{"))
        val replaced = ToolCards.attach("Here:\n\n```table\n{\"title\":\"x\",\"rows\":[[\"a\",\"b\"]]}\n```\nDone.", listOf(c))
        assertFalse(replaced.contains("\"title\":\"x\"")); assertTrue(replaced.trimEnd().endsWith("Done."))
        assertEquals("hi", ToolCards.attach("hi", emptyList()))
    }

    @Test fun repairPathFindsBrokenBlockAndSplicesTheFix() {
        val reply = "Your plans:\n\n```table\n{\"title\":\"Plans\",\"columns\":[\"Plan\",\"Price\"],\"rows\":[[\"Basic\" \"K50\"] [\"Pro\" \"K120\"]]}\n```\nPick one."
        val b = RichRepair.broken(reply).single()
        assertEquals("table", b.kind)
        assertTrue(RichRepair.prompt(b).contains("```table") && RichRepair.prompt(b).contains("can't be drawn"))
        // The model's answer to the re-ask (with chatter around it): only a valid block of the same kind is taken.
        val answer = "Sure!\n```table\n{\"title\":\"Plans\",\"columns\":[\"Plan\",\"Price\"],\"rows\":[[\"Basic\",\"K50\"],[\"Pro\",\"K120\"]]}\n```"
        val fixed = RichRepair.fixedBlock("table", answer)
        assertNotNull(fixed)
        val out = RichRepair.splice(reply, b, fixed!!)
        assertTrue(RichRepair.broken(out).isEmpty())
        assertTrue(out.startsWith("Your plans:") && out.trimEnd().endsWith("Pick one."))
        assertNull(RichRepair.fixedBlock("table", "```chart\n{\"values\":[1,2]}\n```"))   // wrong kind
        assertNull(RichRepair.fixedBlock("table", "still broken {"))
        // Repairable blocks never need the re-ask.
        assertTrue(RichRepair.broken("```table\n{title:'A', columns:['a','b'], rows:[['1','2']]}\n```").isEmpty())
    }

    @Test fun richTurnsRunCooler() {
        assertTrue(RichRepair.richTurn("show my unused apps in a table"))
        assertTrue(RichRepair.richTurn("compare iPhone vs Galaxy"))
        assertFalse(RichRepair.richTurn("hey, how are you"))
        assertTrue(RichRepair.TEMPERATURE <= 0.3)
    }
}
