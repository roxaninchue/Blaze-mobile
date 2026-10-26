package com.blaze.assistant.stage

import com.blaze.assistant.agent.ToolRouter
import com.blaze.assistant.agent.ViewRouter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.0 Stage tools: parsing, definitions, routing (Stage first, plain Q&A stays chat, "study 30 min" = timer card). */
class StageToolsTest {
    private fun j(s: String) = JSONObject(s)

    @Test fun showTimerParsesMinutesAndDoesNotAutostart() {
        val (a, err) = StageTools.parse(StageTools.SHOW, j("""{"kind":"timer","minutes":"30 min","title":"Study"}"""))
        a as StageAction.Show
        assertEquals("", err); assertEquals(StageKind.TIMER, a.kind); assertEquals(StageMode.HERO, a.mode)
        assertEquals("30", a.props["minutes"]); assertEquals("Study", a.props["title"]); assertNull(a.props["autostart"])
        val (p, _) = StageTools.parse(StageTools.SHOW, j("""{"kind":"pomodoro","minutes":25}"""))
        assertEquals("true", (p as StageAction.Show).props["pomodoro"])
    }

    @Test fun invalidShowsSayWhy() {
        assertTrue(StageTools.parse(StageTools.SHOW, j("""{"kind":"timer"}""")).second.contains("minutes"))
        assertTrue(StageTools.parse(StageTools.SHOW, j("""{"kind":"hologram"}""")).second.startsWith("Unknown kind"))
        assertTrue(StageTools.parse(StageTools.SHOW, j("""{"kind":"quiz","questions":[{"q":"x","options":["a"]}]}""")).second.contains("questions"))
        assertTrue(StageTools.parse(StageTools.SHOW, j("""{"kind":"flashcards"}""")).second.contains("deck"))
    }

    @Test fun quizAnswerShapes() {
        val q = StageTools.quiz(JSONArray("""[
            {"q":"2+2?","options":["3","4"],"answer":1},
            {"question":"Capital of Zambia?","choices":["Ndola","Lusaka","Kitwe"],"answer":"B"},
            {"q":"Sky?","options":["blue","green"],"answer":"blue"},
            {"q":"bad","options":["a","b"],"answer":7},
            {"q":"Last?","options":["a","b","c"],"answer":3}]"""))
        assertEquals(listOf(1, 1, 0, 2), q.map { it.answer })
    }

    @Test fun widgetModeAndSlot() {
        val (a, _) = StageTools.parse(StageTools.SHOW, j("""{"kind":"music","mode":"widget","slot":"side","query":"lofi"}"""))
        a as StageAction.Show
        assertEquals(StageMode.WIDGET, a.mode); assertEquals(StageSlot.SIDE, a.slot); assertEquals("lofi", a.props["query"])
    }

    @Test fun moveAnyElement() {
        assertEquals(StageAction.SetChat(ChatMode.LAST, 3, null, null), StageTools.parse(StageTools.MOVE, j("""{"target":"chat","mode":"last","count":3}""")).first)
        assertEquals(StageAction.SetChat(ChatMode.HIDDEN, null, null, null), StageTools.parse(StageTools.MOVE, j("""{"target":"chat","visible":false}""")).first)
        assertEquals(StageAction.SetChrome(topBar = false), StageTools.parse(StageTools.MOVE, j("""{"target":"top_bar","visible":false}""")).first)
        assertEquals(StageAction.SetChrome(reply = ReplyBar.PILL), StageTools.parse(StageTools.MOVE, j("""{"target":"reply_bar","mode":"hidden"}""")).first)
        assertEquals(StageAction.Expand("music2"), StageTools.parse(StageTools.MOVE, j("""{"target":"music2","slot":"hero"}""")).first)
        assertEquals(StageAction.Move("music2", StageSlot.BOTTOM), StageTools.parse(StageTools.MOVE, j("""{"target":"music2","slot":"bottom"}""")).first)
        assertEquals(StageAction.Resize("timer1", 0.6f), StageTools.parse(StageTools.MOVE, j("""{"target":"timer1","scale":0.6}""")).first)
    }

    @Test fun closeAndLayout() {
        assertEquals(StageAction.CloseAll, StageTools.parse(StageTools.CLOSE, j("""{"id":"all"}""")).first)
        assertEquals(StageAction.Layout("focus"), StageTools.parse(StageTools.LAYOUT, j("""{"preset":"focus"}""")).first)
        assertNull(StageTools.parse(StageTools.UPDATE, j("{}")).first)
    }

    @Test fun definitionsAreValid() {
        val d = StageTools.definitions()
        assertEquals(StageTools.NAMES, d.map { it.getJSONObject("function").getString("name") }.toSet())
        d.forEach { assertTrue(it.getJSONObject("function").getJSONObject("parameters").getJSONArray("required").length() >= 1) }
    }

    @Test fun ruleSaysStageFirstAndTimerNotReminder() {
        val r = StageTools.RULE
        assertTrue(r.contains("study 30 min")); assertTrue(r.contains("not a reminder")); assertTrue(r.contains("Plain questions"))
        assertTrue(r.contains("open_view only when the user asks")); assertTrue(r.contains("Open in full view"))
        assertTrue(r.length < 1100)
    }

    @Test fun routingStageFirst() {
        assertEquals("stage", ViewRouter.route("study 30 min")?.id)
        assertEquals("stage", ViewRouter.route("start a 25 minute focus session")?.id)
        assertEquals("stage", ViewRouter.route("let's play some music")?.id)
        assertEquals("stage", ViewRouter.route("pomodoro please")?.id)
        assertEquals("stage", ViewRouter.route("set a timer for 10 minutes")?.id)
        assertEquals("learn", ViewRouter.route("help me study for my CIT102 exam")?.id)
        assertNull(ViewRouter.route("what's the capital of Zambia?"))
        assertNull(ViewRouter.route("how does photosynthesis work"))
        assertTrue(ViewRouter.hint(ViewRouter.byId("stage")!!).contains("kind=timer"))
        assertTrue(ViewRouter.hint(ViewRouter.byId("stage")!!).contains("Don't make a reminder"))
        assertTrue(ViewRouter.hint(ViewRouter.byId("learn")!!).contains("stage_show kind=flashcards"))
        assertTrue(ViewRouter.PROMPT.contains("STAGE FIRST"))
    }

    @Test fun toolDietKeepsStageShowAndLoadsTheRest() {
        assertTrue("stage" in ToolRouter.guess("pause the timer"))
        assertTrue("stage" in ToolRouter.guess("hide the chat"))
        assertEquals("stage", ToolRouter.groupOf("stage_move"))
        val all = JSONArray(StageTools.definitions())
        val lean = ToolRouter.selectDefault(all, emptySet())
        val names = (0 until lean.length()).map { lean.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("stage_show"), names)
    }

    @Test fun propsArraysAsJson() {
        val p = StageTools.propsFrom(j("""{"items":["Read ch 3", {"text":"Past paper"}, ""],"text":"Friday"}"""))
        assertEquals(listOf("Read ch 3", "Past paper"), StageTools.items(p["items"]))
    }
}
