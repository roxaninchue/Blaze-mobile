package com.blaze.assistant.mediaapps

/**
 * 0.46.1 the Stage music card, the pure part (no Android): what the card shows from the player's state, the seek slider's
 * mapping, the elapsed / remaining labels, and the queue edits (move, remove, play next) as index moves for the player.
 * The card never keeps its own copy of the playback state: it reads the one MusicService (the media session that also
 * feeds the notification, the lock screen and the headset buttons) once a second, so the three can't drift apart.
 * Unit-tested in StageMusicLogicTest.
 */
object StageMusicLogic {
    /** One song in the queue as the card lists it. */
    data class Entry(val key: String, val title: String, val artist: String, val art: String = "", val uri: String = "")

    /** The player as read from MusicService. [state] = Player.STATE_* (1 idle, 2 buffering, 3 ready, 4 ended). */
    data class Snapshot(
        val service: Boolean = false, val playing: Boolean = false, val title: String = "", val artist: String = "",
        val posMs: Long = 0, val durMs: Long = 0, val state: Int = 1, val shuffle: Boolean = false, val repeat: Repeat = Repeat.OFF,
        val queue: List<Entry> = emptyList(), val index: Int = -1, val order: List<Int> = emptyList(),
    )

    /** What the card draws. [frac] 0..1 for the slider. */
    data class Card(
        val hasService: Boolean, val playing: Boolean, val title: String, val artist: String, val posMs: Long, val durMs: Long,
        val frac: Float, val elapsed: String, val remaining: String, val shuffle: Boolean, val repeat: Repeat,
        val canPrev: Boolean, val canNext: Boolean, val current: Entry?, val upNext: List<Int>,
    )

    const val STATE_IDLE = 1; const val STATE_ENDED = 4

    /**
     * The card from the player. A song that ENDED (the queue ran out) is shown at its start, not as a full bar: Play
     * restarts it ([playOp]). 0.46.0 report: the bar showed 2:24 / 2:24 before anything played. A position past the
     * duration is clamped; with no duration yet the bar is empty and the times show "0:00" / "".
     */
    fun card(s: Snapshot): Card {
        val ended = s.state == STATE_ENDED && !s.playing
        val dur = s.durMs.coerceAtLeast(0L)
        val pos = if (ended) 0L else if (dur > 0) s.posMs.coerceIn(0L, dur) else s.posMs.coerceAtLeast(0L)
        val n = s.queue.size
        val cur = s.queue.getOrNull(s.index)
        val ord = QueueLogic.playOrder(n, s.shuffle, s.order)
        return Card(s.service, s.playing, s.title.ifBlank { cur?.title.orEmpty() }, s.artist.ifBlank { cur?.artist.orEmpty() }, pos, dur,
            frac(pos, dur), elapsed(pos), if (dur > 0) remaining(pos, dur) else "", s.shuffle, s.repeat,
            canPrev = n > 0, canNext = n > 0 && (QueueLogic.nextIndex(s.index, n, if (s.repeat == Repeat.ONE) Repeat.OFF else s.repeat, ord) >= 0),
            current = cur, upNext = QueueLogic.upNext(s.index, n, s.repeat, ord))
    }

    /** The slider's fill for a position. */
    fun frac(posMs: Long, durMs: Long): Float = if (durMs <= 0) 0f else (posMs.toFloat() / durMs).coerceIn(0f, 1f)

    /** Where a drag / tap at [x] of [width] seeks to (ms), clamped to the song. */
    fun seekTo(x: Float, width: Float, durMs: Long): Long {
        if (durMs <= 0 || width <= 0f) return 0L
        val f = (x / width).coerceIn(0f, 1f)
        return (f * durMs).toLong().coerceIn(0L, durMs)
    }

    /** Elapsed time, rounded DOWN ("0:00" at the start, never a second ahead). */
    fun elapsed(ms: Long): String = clock(ms.coerceAtLeast(0L) / 1000)

    /** "-1:23": time left, rounded UP so it reaches "-0:00" only at the end. */
    fun remaining(posMs: Long, durMs: Long): String = "-" + clock(((durMs - posMs).coerceAtLeast(0L) + 999) / 1000)

    private fun clock(totalSec: Long): String {
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val sec = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** What Play does: restart an ended song from its start, prepare an idle player, else just play. */
    fun playOp(state: Int): String = when (state) { STATE_ENDED -> "restart"; STATE_IDLE -> "prepare_play"; else -> "play" }

    /** The repeat button: off -> all -> one -> off, the same order as the notification's button and Blaze Music. */
    fun nextRepeat(r: Repeat): Repeat = r.next()

    /** A card op ("seek:72000", "at:3", "shuffle") as the command and its number; the card and the widget send these. */
    val COMMANDS = setOf("play", "pause", "next", "previous", "shuffle", "repeat", "seek", "at", "up", "down", "remove", "next_up")
    fun parse(op: String): Pair<String, Long?>? {
        val c = op.substringBefore(':').trim().lowercase()
        if (c !in COMMANDS) return null
        val n = if (op.contains(':')) op.substringAfter(':').trim().toLongOrNull() ?: return null else null
        if (c in setOf("seek", "at", "up", "down", "remove", "next_up") && (n == null || n < 0)) return null
        return c to n
    }

    // ---------------- queue edits (indices in the player's list, not the shuffled order) ----------------

    /** "Play next": where [from] has to move so it plays right after [current] (the player's list order). -1 = nothing to do. */
    fun playNextTarget(from: Int, current: Int, size: Int): Int {
        if (from !in 0 until size || current !in 0 until size || from == current) return -1
        val to = if (from < current) current else current + 1
        return if (to == from) -1 else to
    }

    /** The queue after moving [from] to [to] (same as the player's moveMediaItem). */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> = QueueLogic.move(list, from, to)

    /** The current index after a move in the player's list. */
    fun indexAfterMove(current: Int, from: Int, to: Int): Int = when {
        from == current -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    /** The current index after removing [at]; the playing song itself can't be removed from the card (skip it instead). */
    fun indexAfterRemove(current: Int, at: Int): Int = if (at < current) current - 1 else current
    fun canRemove(at: Int, current: Int, size: Int): Boolean = at in 0 until size && at != current
}
