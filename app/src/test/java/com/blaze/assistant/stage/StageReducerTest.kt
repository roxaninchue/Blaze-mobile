package com.blaze.assistant.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.0 Blaze Stage: the layout reducer (hero <-> widget, slots, max widgets, the movable chat, back / reset). */
class StageReducerTest {
    private fun show(s: StageState, k: StageKind, mode: StageMode = StageMode.HERO, props: Map<String, String> = emptyMap(), slot: StageSlot? = null) =
        StageReducer.reduce(s, StageAction.Show(k, props, mode, slot))

    @Test fun firstHeroDocksTheChatAndKeepsChrome() {
        val r = show(StageState(), StageKind.MUSIC)
        assertEquals(StageKind.MUSIC, r.state.hero?.kind)
        assertEquals(ChatMode.LAST, r.state.chat.mode); assertEquals(1, r.state.chat.count)
        assertTrue(r.state.topBar); assertEquals(ReplyBar.FULL, r.state.reply)
        assertTrue(r.note.contains("id=" + r.id))
    }

    @Test fun newHeroTurnsTheOldOneIntoAWidgetAndBothStay() {
        val a = show(StageState(), StageKind.MUSIC)
        val b = show(a.state, StageKind.TIMER, props = mapOf("minutes" to "30"))
        assertEquals(StageKind.TIMER, b.state.hero?.kind)
        val music = b.state.items.first { it.kind == StageKind.MUSIC }
        assertEquals(StageMode.WIDGET, music.mode); assertEquals(a.id, music.id)   // same object, morphed
        assertEquals(1, b.state.items.count { it.mode == StageMode.HERO })
        assertTrue(b.note.contains("Music moved to a widget"))
    }

    @Test fun tapWidgetExpandsAndSwapsHero() {
        val a = show(StageState(), StageKind.MUSIC)
        val b = show(a.state, StageKind.TIMER, props = mapOf("minutes" to "5"))
        val c = StageReducer.reduce(b.state, StageAction.Expand(a.id!!))
        assertEquals(StageKind.MUSIC, c.state.hero?.kind)
        assertEquals(StageMode.WIDGET, c.state.byId(b.id!!)!!.mode)
    }

    @Test fun musicAndVideoAreSingleInstances() {
        val a = show(StageState(), StageKind.MUSIC)
        val b = show(a.state, StageKind.MUSIC, StageMode.WIDGET, mapOf("query" to "lofi"))
        assertEquals(1, b.state.items.count { it.kind == StageKind.MUSIC }); assertEquals(a.id, b.id)
        assertEquals("lofi", b.state.byId(a.id!!)!!.props["query"])
    }

    @Test fun maxWidgetsEvictsOldestIdleFirst() {
        var s = StageState()
        s = show(s, StageKind.MUSIC, StageMode.WIDGET).state
        s = show(s, StageKind.NOTE, StageMode.WIDGET, mapOf("text" to "a")).state
        s = show(s, StageKind.PLAN, StageMode.WIDGET, mapOf("items" to "[\"x\"]")).state
        s = show(s, StageKind.TIMER, StageMode.WIDGET, mapOf("minutes" to "1")).state
        val r = show(s, StageKind.NOTE, StageMode.WIDGET, mapOf("text" to "b"))
        assertEquals(StageReducer.MAX_WIDGETS, r.state.widgets.size)
        assertEquals(listOf(StageKind.NOTE), r.evicted.map { it.kind })
        assertEquals("a", r.evicted.single().props["text"])
        assertTrue(r.state.items.any { it.kind == StageKind.MUSIC })   // running things stay
        assertTrue(r.note.contains("max 4 widgets"))
    }

    @Test fun heroDoesNotCountAsWidget() {
        var s = StageState()
        repeat(4) { s = show(s, StageKind.NOTE, StageMode.WIDGET, mapOf("text" to "$it")).state }
        val r = show(s, StageKind.TIMER, props = mapOf("minutes" to "3"))
        assertTrue(r.evicted.isEmpty()); assertEquals(5, r.state.items.size)
    }

    @Test fun chatIsAMovableElementNotAFixedRule() {
        val h = show(StageState(), StageKind.TIMER, props = mapOf("minutes" to "30")).state
        val three = StageReducer.reduce(h, StageAction.SetChat(ChatMode.LAST, 3)).state
        assertEquals(3, three.chat.count)
        val tile = StageReducer.reduce(three, StageAction.SetChat(ChatMode.WIDGET, slot = StageSlot.TOP)).state
        assertEquals(ChatMode.WIDGET, tile.chat.mode); assertEquals(StageSlot.TOP, tile.chat.slot)
        val hidden = StageReducer.reduce(tile, StageAction.SetChat(ChatMode.HIDDEN)).state
        assertEquals(ChatMode.HIDDEN, hidden.chat.mode)
        val full = StageReducer.reduce(hidden, StageAction.SetChat(ChatMode.FULL)).state
        assertEquals(ChatMode.FULL, full.chat.mode); assertNotNull(full.hero)   // hero and full chat can share the screen
        // The layout chosen during a hero is remembered for the next hero.
        val back = StageReducer.reduce(three, StageAction.Back).state
        val again = StageReducer.reduce(back, StageAction.Expand(back.items.first().id)).state
        assertEquals(ChatLayout(ChatMode.LAST, 3), again.chat)
    }

    @Test fun chatDragCounts() {
        assertEquals(ChatLayout(ChatMode.LAST, 3), StageReducer.chatCountForDrag(1, -112f))
        assertEquals(ChatMode.WIDGET, StageReducer.chatCountForDrag(1, 80f).mode)
        assertEquals(ChatMode.FULL, StageReducer.chatCountForDrag(3, -2000f).mode)
    }

    @Test fun replyBarIsNeverGone() {
        val s = StageReducer.reduce(StageState(), StageAction.Layout("focus")).state
        assertEquals(ReplyBar.PILL, s.reply); assertFalse(s.topBar); assertEquals(ChatMode.HIDDEN, s.chat.mode)
        // There is no "hidden" reply state at all: the enum only has FULL and PILL, and decode can't produce anything else.
        assertEquals(setOf("FULL", "PILL"), ReplyBar.entries.map { it.name }.toSet())
        assertEquals(ReplyBar.PILL, StageTools.decode(StageTools.encode(s)).reply)
        assertEquals(ReplyBar.FULL, StageTools.decode("{\"reply\":\"GONE\"}").reply)
    }

    @Test fun immersiveHeroHidesChromeAndBackRestoresEverything() {
        val a = show(StageState(), StageKind.VIDEO, props = mapOf("url" to "/x.mp4", "immersive" to "true"))
        assertFalse(a.state.topBar); assertEquals(ReplyBar.PILL, a.state.reply)
        val b = StageReducer.reduce(a.state, StageAction.Back)
        assertTrue(b.handled); assertNull(b.state.hero)
        assertTrue(b.state.topBar); assertEquals(ReplyBar.FULL, b.state.reply); assertEquals(ChatMode.FULL, b.state.chat.mode)
        assertEquals(1, b.state.widgets.size)   // widgets persist as pills
        assertTrue(b.state.isNormal)
        assertFalse(StageReducer.reduce(b.state, StageAction.Back).handled)   // then the app's own back
    }

    @Test fun backWithoutHeroRestoresChromeFirst() {
        val s = StageReducer.reduce(StageState(), StageAction.SetChrome(topBar = false, reply = ReplyBar.PILL)).state
        val b = StageReducer.reduce(s, StageAction.Back)
        assertTrue(b.handled); assertTrue(b.state.isNormal)
    }

    @Test fun resetLayoutKeepsCardsAsWidgets() {
        var s = show(StageState(), StageKind.MUSIC).state
        s = StageReducer.reduce(s, StageAction.Layout("minimal")).state
        val r = StageReducer.reduce(s, StageAction.Reset).state
        assertTrue(r.isNormal); assertEquals(1, r.widgets.size)
    }

    @Test fun moveHeroToSlotMakesWidgetAndUnknownIdsAreReported() {
        val a = show(StageState(), StageKind.TIMER, props = mapOf("minutes" to "10"))
        val m = StageReducer.reduce(a.state, StageAction.Move(a.id!!, StageSlot.SIDE))
        assertNull(m.state.hero); assertEquals(StageSlot.SIDE, m.state.byId(a.id!!)!!.slot); assertTrue(m.state.isNormal)
        assertFalse(StageReducer.reduce(a.state, StageAction.Close("nope")).handled)
        assertFalse(StageReducer.reduce(a.state, StageAction.Layout("zigzag")).handled)
    }

    @Test fun closeHeroRestoresChat() {
        val a = show(StageState(), StageKind.QUIZ, props = mapOf("questions" to "[]"))
        val c = StageReducer.reduce(a.state, StageAction.Close(a.id!!))
        assertTrue(c.state.isEmpty)
    }

    @Test fun dropSlots() {
        assertEquals(StageSlot.TOP, StageReducer.slotForDrop(0.5f, 0.1f))
        assertEquals(StageSlot.BOTTOM, StageReducer.slotForDrop(0.5f, 0.9f))
        assertEquals(StageSlot.SIDE, StageReducer.slotForDrop(0.9f, 0.5f))
        assertEquals(StageSlot.FLOAT, StageReducer.slotForDrop(0.4f, 0.5f))
    }

    @Test fun codecRoundTripAndNeverTwoHeroes() {
        var s = show(StageState(), StageKind.MUSIC).state
        s = show(s, StageKind.TIMER, props = mapOf("minutes" to "30", "title" to "Study")).state
        s = StageReducer.reduce(s, StageAction.SetChat(ChatMode.LAST, 4)).state
        val d = StageTools.decode(StageTools.encode(s))
        assertEquals(s, d)
        val two = StageTools.encode(s).replace("\"WIDGET\"", "\"HERO\"")
        assertEquals(1, StageTools.decode(two).items.count { it.mode == StageMode.HERO })
        assertEquals(StageState(), StageTools.decode("not json"))
    }
}
