package com.blaze.assistant.mediaapps

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.1 the Stage music card: queue / shuffle / repeat, the seek slider, the time labels, and the card mirroring the media session. */
class StageMusicLogicTest {
    private fun q(n: Int) = (0 until n).map { StageMusicLogic.Entry("k$it", "Song $it", "Artist $it") }
    private fun snap(n: Int = 5, index: Int = 0, pos: Long = 30_000, dur: Long = 144_000, state: Int = 3, playing: Boolean = true,
                     shuffle: Boolean = false, repeat: Repeat = Repeat.OFF, order: List<Int> = emptyList()) =
        StageMusicLogic.Snapshot(true, playing, "Song $index", "Artist $index", pos, dur, state, shuffle, repeat, q(n), index, order)

    // ---- the bug from the 0.46.0 screenshot: a full bar at 2:24 / 2:24 before anything played
    @Test fun endedQueueShowsTheStartNotAFullBar() {
        val c = StageMusicLogic.card(snap(pos = 144_000, state = StageMusicLogic.STATE_ENDED, playing = false))
        assertEquals(0f, c.frac, 0f)
        assertEquals("0:00", c.elapsed)
        assertEquals("-2:24", c.remaining)
        assertEquals("restart", StageMusicLogic.playOp(StageMusicLogic.STATE_ENDED))
        assertEquals("prepare_play", StageMusicLogic.playOp(StageMusicLogic.STATE_IDLE))
        assertEquals("play", StageMusicLogic.playOp(3))
    }

    @Test fun positionPastTheEndIsClampedAndNoDurationIsAnEmptyBar() {
        assertEquals(1f, StageMusicLogic.card(snap(pos = 200_000)).frac, 0f)
        val none = StageMusicLogic.card(snap(pos = 5_000, dur = 0))
        assertEquals(0f, none.frac, 0f); assertEquals("", none.remaining); assertEquals("0:05", none.elapsed)
    }

    @Test fun seekMapping() {
        assertEquals(72_000L, StageMusicLogic.seekTo(150f, 300f, 144_000))
        assertEquals(0L, StageMusicLogic.seekTo(-20f, 300f, 144_000))
        assertEquals(144_000L, StageMusicLogic.seekTo(999f, 300f, 144_000))
        assertEquals(0L, StageMusicLogic.seekTo(150f, 0f, 144_000))
        assertEquals(0L, StageMusicLogic.seekTo(150f, 300f, 0))
        assertEquals(0.5f, StageMusicLogic.frac(72_000, 144_000), 0.0001f)
        // Round trip: the slider position a seek lands on draws the same fill.
        for (x in listOf(0f, 33f, 150f, 299f, 300f)) assertEquals(x / 300f, StageMusicLogic.frac(StageMusicLogic.seekTo(x, 300f, 144_000), 144_000), 0.001f)
    }

    @Test fun timeLabels() {
        assertEquals("0:00", StageMusicLogic.elapsed(999))      // never a second ahead
        assertEquals("1:05", StageMusicLogic.elapsed(65_400))
        assertEquals("-0:01", StageMusicLogic.remaining(143_500, 144_000))
        assertEquals("-0:00", StageMusicLogic.remaining(144_000, 144_000))
        assertEquals("1:00:00", StageMusicLogic.elapsed(3_600_000))
    }

    @Test fun repeatCyclesLikeTheNotificationButton() {
        assertEquals(Repeat.ALL, StageMusicLogic.nextRepeat(Repeat.OFF))
        assertEquals(Repeat.ONE, StageMusicLogic.nextRepeat(Repeat.ALL))
        assertEquals(Repeat.OFF, StageMusicLogic.nextRepeat(Repeat.ONE))
        // Same order as the media session's custom button (MusicService.nextRepeat on Player modes).
        val map = mapOf(Repeat.OFF to Player.REPEAT_MODE_OFF, Repeat.ALL to Player.REPEAT_MODE_ALL, Repeat.ONE to Player.REPEAT_MODE_ONE)
        for (r in Repeat.entries) assertEquals(map[StageMusicLogic.nextRepeat(r)], MusicService.nextRepeat(map[r]!!))
    }

    @Test fun nextAndPreviousFollowRepeatAndShuffle() {
        assertFalse(StageMusicLogic.card(snap(index = 4)).canNext)                       // last song, repeat off
        assertTrue(StageMusicLogic.card(snap(index = 4, repeat = Repeat.ALL)).canNext)   // wraps
        assertFalse(StageMusicLogic.card(snap(index = 4, repeat = Repeat.ONE)).canNext)  // next still moves on (repeat one only loops on its own)
        val order = listOf(2, 0, 4, 1, 3)
        val c = StageMusicLogic.card(snap(index = 2, shuffle = true, order = order))
        assertEquals(listOf(0, 4, 1, 3), c.upNext)                                       // up next in the shuffled order
        assertTrue(c.canNext)
        assertEquals(listOf(1, 2, 3, 4), StageMusicLogic.card(snap(index = 0)).upNext)
        assertEquals(listOf(4, 0, 1, 2), StageMusicLogic.card(snap(index = 3, repeat = Repeat.ALL)).upNext)
        val sh = QueueLogic.shuffleOrder(10, 3, 42L)
        assertEquals(3, sh.first()); assertEquals((0 until 10).toSet(), sh.toSet())
    }

    @Test fun queueEditsMatchThePlayersMoves() {
        // "Play next" moves the song to right after the one playing (player list order).
        assertEquals(3, StageMusicLogic.playNextTarget(5, 2, 8))
        assertEquals(2, StageMusicLogic.playNextTarget(0, 2, 8))
        assertEquals(-1, StageMusicLogic.playNextTarget(3, 2, 8))   // already next
        assertEquals(-1, StageMusicLogic.playNextTarget(2, 2, 8))   // the song playing
        val list = listOf("a", "b", "c", "d", "e", "f")
        val cur = 2
        for (from in list.indices) {
            val to = StageMusicLogic.playNextTarget(from, cur, list.size)
            if (to < 0) continue
            val moved = StageMusicLogic.moved(list, from, to)
            val nowAt = StageMusicLogic.indexAfterMove(cur, from, to)
            assertEquals("c", moved[nowAt])                 // the playing song is tracked
            assertEquals(list[from], moved[nowAt + 1])      // and the picked one plays right after it
        }
        assertEquals(listOf("b", "a", "c"), StageMusicLogic.moved(listOf("a", "b", "c"), 1, 0))
        assertEquals(1, StageMusicLogic.indexAfterRemove(2, 0)); assertEquals(2, StageMusicLogic.indexAfterRemove(2, 4))
        assertFalse(StageMusicLogic.canRemove(2, 2, 5)); assertTrue(StageMusicLogic.canRemove(4, 2, 5)); assertFalse(StageMusicLogic.canRemove(5, 2, 5))
    }

    @Test fun cardMirrorsTheMediaSession() {
        // The card has no state of its own: whatever the session says (notification, lock screen, headset) is what it shows.
        val s = snap(index = 1, pos = 72_000, playing = false, shuffle = true, repeat = Repeat.ONE, order = listOf(1, 0, 2, 3, 4))
        val c = StageMusicLogic.card(s)
        assertFalse(c.playing); assertTrue(c.shuffle); assertEquals(Repeat.ONE, c.repeat)
        assertEquals("Song 1", c.title); assertEquals("k1", c.current?.key); assertEquals(0.5f, c.frac, 0.001f)
        assertEquals(c.copy(playing = true), StageMusicLogic.card(s.copy(playing = true)).let { it.copy() })
        // Title falls back to the queue entry when the session has no metadata yet.
        assertEquals("Song 3", StageMusicLogic.card(s.copy(title = "", index = 3)).title)
        assertFalse(StageMusicLogic.card(StageMusicLogic.Snapshot()).hasService)
    }

    @Test fun cardOpsParse() {
        assertEquals("seek" to 72_000L, StageMusicLogic.parse("seek:72000"))
        assertEquals("play" to null, StageMusicLogic.parse("play"))
        assertEquals("next_up" to 4L, StageMusicLogic.parse("next_up:4"))
        assertNull(StageMusicLogic.parse("seek:")); assertNull(StageMusicLogic.parse("seek:-5")); assertNull(StageMusicLogic.parse("rm -rf"))
        assertNull(StageMusicLogic.parse("at"))
    }
}
