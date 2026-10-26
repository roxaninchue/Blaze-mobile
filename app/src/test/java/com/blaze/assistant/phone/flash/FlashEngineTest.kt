package com.blaze.assistant.phone.flash

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.3 flash engine: element indexing, action parsing/dispatch, visual fallback, verification, loop guard, explainer. */
class FlashEngineTest {
    private fun tree(screen: String, setup: (World) -> Unit = {}): Pair<World, List<UiNode>> {
        val w = World(screen); setup(w)
        return w to FlashTree.build(w.render(), BAccess, World.W, World.H).map { it.second }
    }

    @Test fun indexesFromOneWithLabelsStateAndBounds() {
        val (_, nodes) = tree("settings") { it.flags["wifi"] = true }
        assertEquals((1..nodes.size).toList(), nodes.map { it.index })
        val wifi = nodes.first { it.label.startsWith("Wi-Fi") }
        assertEquals("Wi-Fi · Connected", wifi.label)          // the row took its text children's words
        assertEquals(true, wifi.checked)                       // and the state of the switch inside it
        assertTrue(wifi.clickable)
        assertFalse(nodes.any { it.label == "Wi-Fi" })          // the absorbed children aren't listed again
        val line = FlashTree.line(wifi, World.W, World.H)
        assertTrue(line, line.matches(Regex("""\[\d+] item "Wi-Fi · Connected" on \[0,\d+]\[1000,\d+]""")))
        // rows scrolled out of view are not listed
        assertFalse(nodes.any { it.label.startsWith("About phone") })
    }

    @Test fun hintDisabledAndHitTest() {
        val (_, nodes) = tree("edit")
        val title = nodes.first { it.label == "Title" }
        assertTrue(title.hint && title.editable)
        assertTrue(FlashTree.line(title, World.W, World.H).contains("field hint \"Title\""))
        val hit = FlashTree.hitTest(nodes, title.cx, title.cy)
        assertEquals(title.index, hit?.index)
        assertNull(FlashTree.hitTest(nodes, -5, -5))
    }

    @Test fun parsesActionsLeniently() {
        assertEquals(PhoneAct.Tap(3, false, "Wi-Fi", "Wi-Fi"), FlashParse.parse("""Sure. ```json
{"action":"tap","target":3,"label":"Wi-Fi","expect":"Wi-Fi"}```"""))
        assertEquals(PhoneAct.TapAt(500, 600, "play button"), FlashParse.parse("""{"action":"click","target":[500,600],"target_description":"play button"}"""))
        assertEquals(PhoneAct.Type(2, "milk", true), FlashParse.parse("""{action:'input_text', target:'[2]', text:'milk', submit:true}"""))
        assertEquals(PhoneAct.Scroll("down"), FlashParse.parse("""{"action":"swipe","direction":"down"}"""))
        assertEquals(PhoneAct.Key("back"), FlashParse.parse("""{"action":"back"}"""))
        assertTrue(FlashParse.parse("""{"action":"report_task_status","status":"completed","summary":"ok"}""") is PhoneAct.Done)
        assertTrue(FlashParse.parse("""{"action":"fail","reason":"paywall"}""") is PhoneAct.Fail)
        assertNull(FlashParse.parse("I think I should tap Wi-Fi"))
        assertNull(FlashParse.parse("""{"action":"teleport"}"""))
    }

    /** A policy that replays fixed replies. */
    private class Replay(vararg val r: String) : FlashPolicy {
        var i = 0; val seen = ArrayList<FlashRequest>()
        override suspend fun next(req: FlashRequest): String { seen.add(req); return r.getOrElse(i++) { """{"action":"fail","reason":"script ended"}""" } }
    }

    @Test fun dispatchesByIndexAndVerifiesTheSwitch() {
        val w = World("settings")
        val wifi = FlashTree.build(w.render(), BAccess, World.W, World.H).first { it.second.label.startsWith("Wi-Fi") }.second.index
        val p = Replay("""{"action":"tap","target":$wifi,"label":"Wi-Fi","expect":"Connected"}""", """{"action":"done","answer":"Wi-Fi is on"}""")
        val r = runBlocking { FlashLoop(FlashBench.SimDevice(w), p, 10).run("turn on wifi") }
        assertTrue(r.error, r.ok)
        assertTrue(w.f("wifi"))
        assertEquals(2, r.modelCalls)
        assertTrue(r.log.first(), r.log.first().contains("is now on"))
    }

    @Test fun emptyTreeFallsBackToScreenshotAndCoordinates() {
        val w = World("game")
        val p = Replay("""{"action":"tap","target":[500,666],"target_description":"Start","expect":"Score"}""", """{"action":"done"}""")
        val r = runBlocking { FlashLoop(FlashBench.SimDevice(w), p, 10).run("start the game") }
        assertNotNull("a screenshot is attached when the tree is empty", p.seen[0].shot)
        assertTrue(p.seen[0].screen.contains("0-1000 grid"))
        assertTrue(r.error, r.ok)
        assertEquals("game_play", w.screen)
        assertTrue(r.visualSteps >= 1)
    }

    @Test fun honestErrorsForMissingTargetsAndNoChange() {
        val w = World("player")
        val p = Replay("""{"action":"tap","target":99}""", """{"action":"fail","reason":"stuck"}""")
        val r = runBlocking { FlashLoop(FlashBench.SimDevice(w), p, 10).run("x") }
        assertFalse(r.ok)
        assertTrue(p.seen[1].note.contains("no element with that index"))
        assertEquals(FlashMode.PRO, p.seen[1].mode)
    }

    @Test fun loopGuardEscalatesThenBlocksThenStops() {
        val g = FlashGuard()
        g.seen(1L)
        assertEquals(FlashGuard.Verdict.OK, g.check(1L, "tap:3"))
        g.record(1L, "tap:3", changed = false)
        assertEquals(FlashGuard.Verdict.ESCALATE, g.check(1L, "tap:3"))
        g.record(1L, "tap:3", changed = false)
        assertEquals(FlashGuard.Verdict.BLOCK, g.check(1L, "tap:3"))
        assertEquals(FlashGuard.Verdict.OK, g.check(1L, "tap:4"))
        val b = FlashGuard()
        repeat(4) { b.seen(10L); b.seen(20L) }
        assertEquals(FlashGuard.Verdict.STOP, b.check(20L, "tap:1"))
    }

    @Test fun repeatingANoOpTapEndsInsteadOfSpinning() {
        val w = World("notes")
        val export = FlashTree.build(w.render(), BAccess, World.W, World.H).first { it.second.label == "Export" }.second.index
        val tap = """{"action":"tap","target":$export,"label":"Export","expect":"Exported"}"""
        val p = Replay(*Array(12) { tap })
        val r = runBlocking { FlashLoop(FlashBench.SimDevice(w), p, 20).run("export") }
        assertFalse(r.ok)
        assertTrue(r.error, r.steps < 10)
        assertTrue(r.log.any { it.contains("pro") })
    }

    @Test fun explainerShownOnceOnly() {
        PhoneExplainer.requested.value = false
        val store = HashMap<String, Boolean>()
        assertTrue(PhoneExplainer.request(store[PhoneExplainer.SEEN_KEY] == true, connected = false))
        assertTrue(PhoneExplainer.requested.value)
        PhoneExplainer.markShown { k, v -> store[k] = v }
        assertFalse(PhoneExplainer.requested.value)
        assertFalse(PhoneExplainer.request(store[PhoneExplainer.SEEN_KEY] == true, connected = false))
        assertFalse(PhoneExplainer.requested.value)
        assertFalse(PhoneExplainer.shouldShow(seen = false, connected = true))
        assertTrue(PhoneExplainer.POINTS.any { it.contains("ADB") })
    }
}
