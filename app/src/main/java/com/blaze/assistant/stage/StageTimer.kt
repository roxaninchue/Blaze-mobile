package com.blaze.assistant.stage

import org.json.JSONObject

/**
 * 0.46.0 Stage timer / pomodoro, the pure part. The clock is the wall clock at start plus the duration (an end time), never a
 * counter that ticks: the UI reads [remaining] once a second, the alarm fires at [endAt], and closing the app or a slow frame
 * can't make it drift. Pomodoro = work / break phases ([phases]); when one ends the next starts from the end time (not from when
 * the phone noticed), so a long session stays exact. Unit-tested in StageTimerTest.
 */
data class TimerState(
    val id: String,
    val label: String = "Timer",
    /** Length of each phase in ms (one entry = a plain timer). */
    val phases: List<Long> = listOf(25 * 60_000L),
    val phaseNames: List<String> = listOf("Focus"),
    val phase: Int = 0,
    /** Wall-clock end of the running phase (0 = not running). */
    val endAt: Long = 0L,
    /** Left in the phase while paused or not started. */
    val left: Long = phases.firstOrNull() ?: 0L,
    val done: Boolean = false,
) {
    val running: Boolean get() = endAt > 0L && !done
    val started: Boolean get() = running || left < (phases.getOrNull(phase) ?: 0L) || phase > 0 || done
    val phaseName: String get() = phaseNames.getOrElse(phase) { if (phase % 2 == 0) "Focus" else "Break" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("phases", org.json.JSONArray(phases)).put("names", org.json.JSONArray(phaseNames))
        .put("phase", phase).put("endAt", endAt).put("left", left).put("done", done)

    companion object {
        fun from(o: JSONObject): TimerState {
            val ph = o.optJSONArray("phases")?.let { a -> (0 until a.length()).map { a.optLong(it) } }?.filter { it > 0 }.orEmpty().ifEmpty { listOf(25 * 60_000L) }
            val nm = o.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return TimerState(o.optString("id"), o.optString("label", "Timer"), ph, nm, o.optInt("phase"), o.optLong("endAt"), o.optLong("left", ph.first()), o.optBoolean("done"))
        }
    }
}

object StageTimer {
    const val MAX_MS = 24 * 3_600_000L

    /** A plain timer of [minutes] (seconds allowed), or a pomodoro with [cycles] of work / short break. Not started. */
    fun create(id: String, label: String, minutes: Double, pomodoro: Boolean = false, breakMin: Double = 5.0, cycles: Int = 4): TimerState {
        val ms = (minutes * 60_000).toLong().coerceIn(1_000L, MAX_MS)
        if (!pomodoro) return TimerState(id, label.ifBlank { "Timer" }, listOf(ms), listOf(label.ifBlank { "Timer" }), 0, 0L, ms)
        val b = (breakMin * 60_000).toLong().coerceIn(60_000L, 60 * 60_000L)
        val n = cycles.coerceIn(1, 8)
        val ph = ArrayList<Long>(); val names = ArrayList<String>()
        for (i in 0 until n) { ph += ms; names += "Focus ${i + 1}/$n"; if (i < n - 1) { ph += b; names += "Break" } }
        return TimerState(id, label.ifBlank { "Pomodoro" }, ph, names, 0, 0L, ms)
    }

    fun start(t: TimerState, now: Long): TimerState = if (t.running || t.done) t else t.copy(endAt = now + t.left)
    fun pause(t: TimerState, now: Long): TimerState = if (!t.running) t else t.copy(endAt = 0L, left = (t.endAt - now).coerceAtLeast(0L))
    fun reset(t: TimerState): TimerState = t.copy(phase = 0, endAt = 0L, left = t.phases.first(), done = false)
    /** +[ms] (or minus) to the running or paused phase; never below one second. */
    fun add(t: TimerState, ms: Long, now: Long): TimerState =
        if (t.running) t.copy(endAt = maxOf(now + 1_000L, t.endAt + ms)) else t.copy(left = maxOf(1_000L, t.left + ms), done = false)

    /** Ms left in the phase at [now] (rounded by the caller). */
    fun remaining(t: TimerState, now: Long): Long = when {
        t.done -> 0L
        t.running -> (t.endAt - now).coerceAtLeast(0L)
        else -> t.left
    }

    /**
     * Catch up to [now]: phases that ended roll into the next one, each starting exactly where the last one ended. Returns the
     * new state and how many phases ended (for the "time's up" / "break" notifications).
     */
    fun advance(t: TimerState, now: Long): Pair<TimerState, Int> {
        if (!t.running) return t to 0
        var s = t; var ended = 0
        while (s.running && now >= s.endAt) {
            ended++
            val next = s.phase + 1
            s = if (next >= s.phases.size) s.copy(endAt = 0L, left = 0L, done = true)
                else s.copy(phase = next, endAt = s.endAt + s.phases[next], left = s.phases[next])
        }
        return s to ended
    }

    /** "29:59", "1:02:03". Rounds UP so the display reads 30:00 at start and 0:00 only at the end. */
    fun clock(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Delay to the next whole-second change of [clock] (the UI's only tick; no per-frame work). */
    fun nextTickDelay(ms: Long): Long { val r = ms % 1000; return if (r == 0L) 1000L else r }

    /** Fraction of the phase done 0..1 (the ring). */
    fun progress(t: TimerState, now: Long): Float {
        val len = t.phases.getOrNull(t.phase) ?: return 1f
        if (t.done) return 1f
        return (1f - remaining(t, now).toFloat() / len.toFloat()).coerceIn(0f, 1f)
    }

    /** "30 min", "1 h 30 min", "45 s" for labels and tool replies. */
    fun words(ms: Long): String {
        val s = (ms + 500) / 1000
        if (s < 60) return "$s s"
        val m = (s + 30) / 60
        return if (m >= 60) "${m / 60} h" + (if (m % 60 > 0) " ${m % 60} min" else "") else "$m min"
    }

    /** Minutes from loose words: "30", "30 min", "1h30", "1.5 hours", "90s", "half an hour". Null when none. */
    fun parseMinutes(s: String?): Double? {
        val t = s?.trim()?.lowercase() ?: return null
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it.takeIf { v -> v > 0 } }
        if (Regex("""\bhalf an? hour\b""").containsMatchIn(t)) return 30.0
        if (Regex("""\ban? hour\b""").containsMatchIn(t) && !Regex("""\d""").containsMatchIn(t)) return 60.0
        var total = 0.0; var any = false
        Regex("""(\d+(?:\.\d+)?)\s*(h|hr|hrs|hour|hours)\b""").find(t)?.let { m -> total += m.groupValues[1].toDouble() * 60; any = true }
        if (!any) Regex("""(\d+)h(\d+)""").find(t)?.let { m -> total += m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(); any = true }
        Regex("""(\d+(?:\.\d+)?)\s*(m|min|mins|minute|minutes)\b""").find(t)?.let { total += it.groupValues[1].toDouble(); any = true }
        Regex("""(\d+(?:\.\d+)?)\s*(s|sec|secs|second|seconds)\b""").find(t)?.let { total += it.groupValues[1].toDouble() / 60.0; any = true }
        return if (any && total > 0) total else null
    }
}
