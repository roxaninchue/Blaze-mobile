package com.blaze.assistant.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v0.43.0: the maths of the "who am I talking to" arc that opens from the header orb. Pure, unit tested in
 * TalkArcMathTest; drawing and gestures are in TalkArc.kt.
 *
 * v0.45.28: two icons, Blaze and Halo. The "Halo x Blaze" choice is gone from the arc: both of them join in by
 * themselves when a message needs them (see SimpleHome.tagged), and a saved "all" opens as Blaze ([normalize]).
 * The arc is a flat half-ellipse as wide as the chat bar ([offset] with rx ≫ ry).
 *
 * [pos] is the (fractional) index of the icon at the top: icon i sits at 90° + (pos − i) · STEP, so with Halo focused
 * Blaze is on the left and Halo on top; with Blaze focused Halo is on the right. Dragging around the arc (or sideways)
 * turns it; letting go snaps to the nearest icon.
 */
object TalkArcMath {
    val TARGETS = listOf("blaze", "halo")
    const val STEP = 60f

    /** v0.45.28: what an old saved pick means now ("all" = Blaze, with Halo x Blaze joining in automatically). */
    fun normalize(who: String?): String = if (who == "halo") "halo" else "blaze"

    fun indexOf(who: String): Int = TARGETS.indexOf(normalize(who)).coerceAtLeast(0)
    fun targetAt(i: Int): String = TARGETS[i.coerceIn(0, TARGETS.size - 1)]
    fun label(who: String): String = if (normalize(who) == "halo") "Halo" else "Blaze"

    /** v0.45.28: the flat arc's height for its [width]: about a third of a semicircle's, never under [minRy]. */
    fun flatRy(rx: Float, minRy: Float): Float = maxOf(minRy, rx * 0.34f)

    fun clamp(pos: Float): Float = pos.coerceIn(0f, (TARGETS.size - 1).toFloat())

    /** The icon a resting arc shows on top. */
    fun snap(pos: Float): Int = clamp(pos).roundToInt()

    /** Degrees (0 = right, 90 = top, 180 = left) of icon [i] when [pos] is on top. */
    fun angle(i: Int, pos: Float): Float = 90f + (pos - i) * STEP

    /** Icon [i]'s centre relative to the arc's centre, y pointing down as on screen. */
    fun offset(i: Int, pos: Float, radius: Float): Pair<Float, Float> = offset(i, pos, radius, radius)

    /** The same on a half-ellipse ([rx] wide, [ry] tall). */
    fun offset(i: Int, pos: Float, rx: Float, ry: Float): Pair<Float, Float> {
        val a = angle(i, pos) * PI.toFloat() / 180f
        return cos(a) * rx to -sin(a) * ry
    }

    /** 1 for the focused icon, falling to 0 one step away. */
    fun focus(i: Int, pos: Float): Float = (1f - abs(pos - i)).coerceIn(0f, 1f)

    /** Size factor: the focused icon is larger. */
    fun scale(i: Int, pos: Float): Float = 1f + 0.45f * focus(i, pos)

    /** Angle (degrees, as in [angle]) of a touch at ([x], [y]) from the arc's centre, y down. */
    fun touchAngle(x: Float, y: Float): Float = (atan2(0f - y, x) * 180f / PI.toFloat())

    /** The angle of a touch on a half-ellipse: the touch scaled to a circle first. */
    fun touchAngle(x: Float, y: Float, rx: Float, ry: Float): Float =
        if (rx <= 0f || ry <= 0f) touchAngle(x, y) else touchAngle(x / rx, y / ry)

    /**
     * Turn the arc as a finger moves from [fromDeg] to [toDeg] around the centre. The icons follow the finger: pulling the
     * right-hand icon up over the top (the angle rising) brings it to the top.
     */
    fun rotate(pos: Float, fromDeg: Float, toDeg: Float): Float {
        var d = toDeg - fromDeg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return clamp(pos + d / STEP)
    }

    /** A sideways swipe of [dx] px on an arc of [radius] px, as the same turn (a swipe to the left brings the right icon up). */
    fun swipe(pos: Float, dx: Float, radius: Float): Float =
        if (radius <= 0f) clamp(pos) else clamp(pos - dx / (radius * STEP * PI.toFloat() / 180f))

    /** The icon under a tap at ([x], [y]) from the centre, within [hit] px of its centre; -1 for none. */
    fun hit(x: Float, y: Float, pos: Float, radius: Float, hit: Float): Int = hit(x, y, pos, radius, radius, hit)

    fun hit(x: Float, y: Float, pos: Float, rx: Float, ry: Float, hit: Float): Int {
        var best = -1; var bestD = hit * hit
        for (i in TARGETS.indices) {
            val (ox, oy) = offset(i, pos, rx, ry)
            val d = (ox - x) * (ox - x) + (oy - y) * (oy - y)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }
}
