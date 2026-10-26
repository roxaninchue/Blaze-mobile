package com.blaze.assistant.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.1 z-order (the 0.46.0 screenshot: the music widget slid UNDER a chat bubble while being dragged). A dragged item is on top of
 * everything; docked and floating widgets are always above the chat stream; the last-touched widget comes to the front.
 */
class StageLayersTest {
    private fun show(s: StageState, k: StageKind, mode: StageMode = StageMode.WIDGET, slot: StageSlot? = null, props: Map<String, String> = emptyMap()) =
        StageReducer.reduce(s, StageAction.Show(k, props, mode, slot))

    private fun stage(): StageState {
        var s = show(StageState(), StageKind.MUSIC, slot = StageSlot.TOP).state
        s = show(s, StageKind.PLAN, slot = StageSlot.FLOAT, props = mapOf("items" to "[\"a\"]")).state
        s = show(s, StageKind.NOTE, slot = StageSlot.FLOAT, props = mapOf("text" to "x")).state
        s = show(s, StageKind.QUIZ, slot = StageSlot.SIDE).state
        s = show(s, StageKind.TIMER, mode = StageMode.HERO, props = mapOf("minutes" to "5")).state
        return s
    }
    private fun id(s: StageState, k: StageKind) = s.items.first { it.kind == k }.id

    @Test fun chatIsBelowEveryWidgetAndTheHero() {
        val s = stage()
        val order = StageLayers.paintOrder(s)
        assertEquals("chat", order.first())
        for (i in s.items) assertTrue("${i.kind} must be above the chat", StageLayers.z(i, s) > StageLayers.CHAT)
        // Docked (top / bottom), side and floating widgets are all above the hero area too, so a drop near it stays visible.
        for (w in s.widgets) assertTrue(StageLayers.z(w, s) > StageLayers.HERO)
    }

    @Test fun draggedItemIsAboveEverything() {
        val s = stage()
        for (i in s.items) {
            val z = StageLayers.z(i, s, dragging = i.id)
            assertEquals(StageLayers.DRAG, z, 0f)
            assertTrue(s.items.filter { it.id != i.id }.all { StageLayers.z(it, s, i.id) < z })
            assertEquals(i.id, StageLayers.paintOrder(s, i.id).last())
        }
        assertTrue(StageLayers.DRAG_SCALE in 1.01f..1.1f)   // a slight lift, not a jump
    }

    @Test fun lastTouchedFloatingWidgetComesToTheFront() {
        var s = stage()
        val plan = id(s, StageKind.PLAN); val note = id(s, StageKind.NOTE)
        assertTrue(StageLayers.z(s.byId(note)!!, s) > StageLayers.z(s.byId(plan)!!, s))      // newest in front
        val r = StageReducer.reduce(s, StageAction.Touch(plan))
        assertTrue(r.handled); s = r.state
        assertTrue(StageLayers.z(s.byId(plan)!!, s) > StageLayers.z(s.byId(note)!!, s))
        assertFalse(StageReducer.reduce(s, StageAction.Touch(plan)).handled)                  // already in front: no save
        // Dropping (a move) also brings it to the front.
        s = StageReducer.reduce(s, StageAction.Move(note, StageSlot.FLOAT, 0.3f, 0.4f)).state
        assertTrue(StageLayers.z(s.byId(note)!!, s) > StageLayers.z(s.byId(plan)!!, s))
        val o = StageLayers.paintOrder(s)
        assertTrue(o.indexOf(note) > o.indexOf(plan)); assertTrue(o.indexOf("chat") < o.indexOf(plan))
    }

    @Test fun dropKeepsWidgetsAboveTheChat() {
        var s = stage()
        val music = id(s, StageKind.MUSIC)
        for (slot in StageSlot.entries) {
            s = StageReducer.reduce(s, StageAction.Move(music, slot, 0.5f, 0.5f)).state
            assertTrue("$slot", StageLayers.z(s.byId(music)!!, s) > StageLayers.CHAT)
        }
    }

    @Test fun zSurvivesTheSavedLayout() {
        var s = stage()
        s = StageReducer.reduce(s, StageAction.Touch(id(s, StageKind.PLAN))).state
        val back = StageTools.decode(StageTools.encode(s))
        assertEquals(s.items.map { it.id to it.z }, back.items.map { it.id to it.z })
        assertEquals(StageLayers.paintOrder(s), StageLayers.paintOrder(back))
    }
}
