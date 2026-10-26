package com.blaze.assistant.stage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.0 Stage layouts persist: what the user arranged (positions, sizes, hidden bars, open widgets, the chat's state) is saved
 * and comes back exactly after the app is killed, until Reset / "restore the original layout". Broken or newer saved state ->
 * the default layout; a turned screen sends free-floating widgets back to their docks; everything is clamped on screen.
 */
class StagePersistTest {
    private fun arranged(): StageState {
        var s = StageReducer.reduce(StageState(), StageAction.Show(StageKind.MUSIC)).state
        s = StageReducer.reduce(s, StageAction.Show(StageKind.TIMER, mapOf("minutes" to "30", "title" to "Study"))).state
        s = StageReducer.reduce(s, StageAction.Move(s.items.first { it.kind == StageKind.MUSIC }.id, StageSlot.FLOAT, 0.3f, 0.5f)).state
        s = StageReducer.reduce(s, StageAction.Expand(s.items.first { it.kind == StageKind.TIMER }.id)).state
        s = StageReducer.reduce(s, StageAction.Resize(s.hero!!.id, 0.7f)).state
        s = StageReducer.reduce(s, StageAction.SetChat(ChatMode.WIDGET, slot = StageSlot.TOP)).state
        s = StageReducer.reduce(s, StageAction.SetChrome(topBar = false, reply = ReplyBar.PILL)).state
        return s
    }

    @Test fun saveKillRestoreIsExact() {
        val s = arranged()
        val saved = StageTools.encode(s, 0.46f)          // written on every change
        val restored = StageTools.decode(saved, 0.46f)    // a fresh process reading it back
        assertEquals(s, restored)
        assertFalse(restored.topBar); assertEquals(ReplyBar.PILL, restored.reply); assertEquals(ChatMode.WIDGET, restored.chat.mode)
        assertEquals(0.7f, restored.hero!!.scale, 0f)
        val music = restored.items.first { it.kind == StageKind.MUSIC }
        assertEquals(StageSlot.FLOAT, music.slot); assertEquals(0.3f, music.x, 0f); assertEquals(0.5f, music.y, 0f)
        assertEquals(StageTools.SCHEMA, JSONObject(saved).getInt("v"))
    }

    @Test fun resetRestoresTheOriginalAndThatPersistsToo() {
        val r = StageReducer.reduce(arranged(), StageAction.Reset).state
        val back = StageTools.decode(StageTools.encode(r, 0.46f), 0.46f)
        assertTrue(back.isNormal); assertEquals(2, back.widgets.size)
        assertEquals(StageReducer.reduce(arranged(), StageAction.Layout("normal")).state.isNormal, true)
    }

    @Test fun corruptOrNewerStateIsTheDefault() {
        for (bad in listOf("", "{", "null", "[]", "{\"v\":99,\"items\":[]}", "{\"items\":[{\"kind\":\"music\"}]}"))
            assertTrue(bad, StageTools.decode(bad).isEmpty)
        // Junk values don't crash: NaN / huge positions and counts are clamped.
        val s = StageTools.decode("""{"v":1,"items":[{"id":"n1","kind":"note","props":{"text":"x"},"mode":"WIDGET","slot":"FLOAT","x":9,"y":-3,"scale":"NaN"}],"chat":{"mode":"LAST","count":500},"reply":"GONE"}""")
        val n = s.items.single()
        assertEquals(0.85f, n.x, 0f); assertEquals(0f, n.y, 0f); assertEquals(1f, n.scale, 0f)
        assertEquals(20, s.chat.count); assertEquals(ReplyBar.FULL, s.reply)
    }

    @Test fun rotationSendsFloatingWidgetsHome() {
        val saved = StageTools.encode(arranged(), 0.46f)   // portrait
        val landscape = StageTools.decode(saved, 2.1f)
        val music = landscape.items.first { it.kind == StageKind.MUSIC }
        assertEquals(StageReducer.defaultSlot(StageKind.MUSIC), music.slot)
        // Same orientation, slightly different size (another phone, split screen a bit): kept where it was.
        assertEquals(StageSlot.FLOAT, StageTools.decode(saved, 0.5f).items.first { it.kind == StageKind.MUSIC }.slot)
        // Unknown screen size: kept.
        assertEquals(StageSlot.FLOAT, StageTools.decode(saved).items.first { it.kind == StageKind.MUSIC }.slot)
    }

    @Test fun tooManySavedWidgetsAreCapped() {
        val items = (1..7).joinToString(",") { """{"id":"n$it","kind":"note","props":{"text":"$it"},"mode":"WIDGET","slot":"TOP","order":$it}""" }
        assertEquals(StageReducer.MAX_WIDGETS, StageTools.decode("""{"v":1,"items":[$items]}""").widgets.size)
    }
}
