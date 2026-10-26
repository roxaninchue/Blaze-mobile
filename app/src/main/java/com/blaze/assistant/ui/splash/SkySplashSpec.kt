package com.blaze.assistant.ui.splash

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 0.47.x "sky splash": the open animation's design numbers, with no Android types so they can be unit tested on the
 * JVM. The preview renderer (work/out/anim/sky_splash_preview.py) uses exactly these values: palettes, ridges, cloud
 * Bezier table, star LCG, blur recipe and the dot-wave timeline. Change both together.
 */
object SkySplashSpec {
    enum class Phase { DAWN, DAY, GOLDEN, NIGHT }

    /** Sky top, sky middle, horizon, nearest mountain (ARGB). Night also draws a few faint stars. */
    class Palette(val top: Int, val mid: Int, val horizon: Int, val mountain: Int, val stars: Boolean)

    private val DAWN = Palette(0xFF27335F.toInt(), 0xFF9C6E9E.toInt(), 0xFFF4B48C.toInt(), 0xFF3B3358.toInt(), false)
    private val DAY = Palette(0xFF2F7FD0.toInt(), 0xFF78B6E8.toInt(), 0xFFD8ECF8.toInt(), 0xFF46627E.toInt(), false)
    private val GOLDEN = Palette(0xFF3E4A86.toInt(), 0xFFD9875E.toInt(), 0xFFFFCF8A.toInt(), 0xFF4A3346.toInt(), false)
    private val NIGHT = Palette(0xFF060A1C.toInt(), 0xFF14214A.toInt(), 0xFF2A3B6E.toInt(), 0xFF070B1A.toInt(), true)

    /** Device local hour -> phase: dawn 05-07, day 07-17, golden hour 17-19, night 19-05. */
    fun phaseFor(hour: Int): Phase = when (hour) {
        5, 6 -> Phase.DAWN
        in 7..16 -> Phase.DAY
        17, 18 -> Phase.GOLDEN
        else -> Phase.NIGHT
    }

    fun palette(p: Phase): Palette = when (p) { Phase.DAWN -> DAWN; Phase.DAY -> DAY; Phase.GOLDEN -> GOLDEN; Phase.NIGHT -> NIGHT }

    /** Gradient stops (fraction of height): top -> mid -> horizon; the horizon colour continues to the bottom. */
    val SKY_STOPS = floatArrayOf(0f, 0.42f, 0.66f, 1f)

    // ---- distant mountains: 3 ridgelines, back to front ----
    /** Per ridge: base (fraction of height), haze towards the horizon colour, then (amp, freq, phase) x 3. */
    val RIDGES = arrayOf(
        floatArrayOf(0.585f, 0.55f, 0.050f, 1.30f, 0.40f, 0.020f, 3.10f, 1.70f, 0.008f, 7.30f, 4.10f),
        floatArrayOf(0.650f, 0.30f, 0.038f, 1.90f, 2.20f, 0.016f, 4.30f, 0.60f, 0.007f, 9.10f, 3.30f),
        floatArrayOf(0.735f, 0.00f, 0.028f, 2.30f, 5.10f, 0.013f, 5.70f, 2.90f, 0.006f, 11.9f, 0.90f),
    )
    const val RIDGE_SAMPLES = 160

    /** Ridge [r] height at x (0..1 of width) as a fraction of the screen height (y down). */
    fun ridgeY(r: Int, x: Float): Float {
        val d = RIDGES[r]; var y = d[0]; var i = 2
        while (i < d.size) { y -= d[i] * sin(2.0 * PI * d[i + 1] * x + d[i + 2]).toFloat(); i += 3 }
        return y
    }

    /** Ridge colour: the palette's mountain colour pushed towards the horizon by the ridge's haze (further = lighter). */
    fun ridgeColor(p: Palette, r: Int): Int = lerpArgb(p.mountain, p.horizon, RIDGES[r][1])

    /** The background is drawn once at 1/[BG_SCALE] size, box-blurred [BOX_PASSES]x with radius [BOX_RADIUS], then scaled up bilinearly. */
    const val BG_SCALE = 6
    const val BOX_RADIUS = 2
    const val BOX_PASSES = 3

    // ---- cloud ----
    /** Valley points of the outline in a unit box (x 0..1 of the cloud width, y down) and the bulge of the lobe that starts there. */
    val CLOUD = floatArrayOf(
        0.08f, 0.52f, 0.62f,
        0.21f, 0.31f, 0.58f,
        0.39f, 0.17f, 0.66f,
        0.66f, 0.19f, 0.58f,
        0.85f, 0.33f, 0.62f,
        0.92f, 0.52f, 0.06f,   // bottom-right -> nearly flat bottom with a slight belly
    )
    /** Control points pulled towards each other along the chord, so lobes meet in clean V notches (no hooks). */
    const val SKEW = 0.22f
    const val CLOUD_WIDTH = 0.62f      // of the screen width (the visible outline is ~58%)
    const val CLOUD_CENTER_Y = 0.40f   // of the screen height
    const val STROKE_DP = 3f
    val CLOUD_SEGMENTS = CLOUD.size / 3

    /**
     * Writes the closed outline as cubic segments (ax, ay, c1x, c1y, c2x, c2y, bx, by) into [out] (size 8 x
     * [CLOUD_SEGMENTS]) for a cloud [cw] px wide centred on (cx, cy). Returns the lowest y of the outline.
     */
    fun cloudCubics(cx: Float, cy: Float, cw: Float, out: FloatArray): Float {
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until CLOUD_SEGMENTS) { val y = CLOUD[k * 3 + 1]; if (y < minY) minY = y; if (y > maxY) maxY = y }
        val oy = cy - cw * (minY + (maxY - minY) / 2f) - cw * 0.04f
        var bottom = -Float.MAX_VALUE
        for (k in 0 until CLOUD_SEGMENTS) {
            val n = (k + 1) % CLOUD_SEGMENTS
            val ax = cx + (CLOUD[k * 3] - 0.5f) * cw; val ay = oy + CLOUD[k * 3 + 1] * cw
            val bx = cx + (CLOUD[n * 3] - 0.5f) * cw; val by = oy + CLOUD[n * 3 + 1] * cw
            val dx = bx - ax; val dy = by - ay; val len = hypot(dx, dy)
            val nx = dy / len; val ny = -dx / len            // outward normal for a clockwise outline (y down)
            val h = CLOUD[k * 3 + 2] * len
            val o = k * 8
            out[o] = ax; out[o + 1] = ay
            out[o + 2] = ax + nx * h + dx * SKEW; out[o + 3] = ay + ny * h + dy * SKEW
            out[o + 4] = bx + nx * h - dx * SKEW; out[o + 5] = by + ny * h - dy * SKEW
            out[o + 6] = bx; out[o + 7] = by
            // lowest point of this cubic (sampled; bounded by its control polygon)
            for (s in 0..16) {
                val t = s / 16f; val u = 1 - t
                val y = u * u * u * ay + 3 * u * u * t * out[o + 3] + 3 * u * t * t * out[o + 5] + t * t * t * by
                if (y > bottom) bottom = y
            }
        }
        return bottom
    }

    // ---- dots + timeline (ms from the overlay's first frame) ----
    const val DOT_DP = 8f
    const val DOT_GAP_DP = 10f
    const val HOP_DP = 10f
    const val DOTS_BELOW_CLOUD_DP = 22f
    const val CLOUD_IN_MS = 300L
    const val CLOUD_SCALE_FROM = 0.92f
    const val DOTS_IN_AT_MS = 150L
    const val DOTS_IN_MS = 150L
    const val WAVE_T0_MS = 300L
    const val PERIOD_MS = 640L
    const val STAGGER_MS = 110L
    const val HOP_MS = 360L
    const val MIN_CYCLES = 3
    const val CROSSFADE_MS = 250L
    /** With animations off: the static frame shows this long (or until ready), then cuts to the app. */
    const val STATIC_MS = 600L
    /** Never hold the user longer than this, ready or not. */
    const val MAX_MS = 6_000L

    /** End of the minimum wave cycles: the earliest moment the cross-fade may start. */
    const val MIN_END_MS = WAVE_T0_MS + MIN_CYCLES * PERIOD_MS

    fun easeOutCubic(t: Float): Float { val c = t.coerceIn(0f, 1f); val u = 1 - c; return 1 - u * u * u }

    /** One hop, 0..1 -> 0..1 of [HOP_DP]: up ease-out, down ease-in, then a small landing rebound. */
    fun hop(p: Float): Float = when {
        p < 0f || p >= 1f -> 0f
        p < 0.40f -> { val q = p / 0.40f; 1 - (1 - q) * (1 - q) }
        p < 0.85f -> { val q = (p - 0.40f) / 0.45f; 1 - q * q }
        else -> 0.12f * sin(PI * (p - 0.85f) / 0.15f).toFloat()
    }

    /**
     * Lift (0..1 of [HOP_DP]) of dot [i] (0 = left, 2 = right) at [tMs]. The wave starts at the RIGHTMOST dot and
     * travels left; [cycles] is how many waves run (Int.MAX_VALUE = keep looping).
     */
    fun dotLift(i: Int, tMs: Long, cycles: Int): Float {
        val t = tMs - WAVE_T0_MS
        if (t < 0) return 0f
        if (t / PERIOD_MS >= cycles) return 0f
        val local = (t % PERIOD_MS) - (2 - i) * STAGGER_MS
        return hop(local.toFloat() / HOP_MS)
    }

    /** Waves completed by [tMs]. */
    fun cyclesDone(tMs: Long): Int = if (tMs < WAVE_T0_MS) 0 else ((tMs - WAVE_T0_MS) / PERIOD_MS).toInt()

    /**
     * When the cross-fade starts, given that at [tMs] the app is [ready]: only on a wave boundary (every dot resting),
     * never before [MIN_END_MS], at the latest [MAX_MS]. Returns the fade start, or -1 to keep looping.
     */
    fun fadeStart(tMs: Long, ready: Boolean): Long {
        if (tMs >= MAX_MS) return tMs
        if (!ready || tMs < MIN_END_MS) return -1
        return WAVE_T0_MS + cyclesDone(tMs) * PERIOD_MS
    }

    // ---- stars (night) ----
    const val STAR_COUNT = 16
    private const val STAR_SEED = 20261027L

    /** x, y (fractions of the screen), radius dp, alpha per star; the cloud + dots area is kept clear. */
    fun stars(): FloatArray {
        var s = STAR_SEED and 0x7fffffffL
        fun next(): Float { s = (s * 1103515245L + 12345L) and 0x7fffffffL; return s.toFloat() / 0x7fffffff.toFloat() }
        val out = FloatArray(STAR_COUNT * 4)
        for (k in 0 until STAR_COUNT) {
            val x = next(); var y = next() * 0.48f
            if (x > 0.15f && x < 0.85f && y > 0.28f) y *= 0.55f   // a star must never read as a 4th dot
            out[k * 4] = x; out[k * 4 + 1] = y; out[k * 4 + 2] = 0.8f + next() * 0.7f; out[k * 4 + 3] = 0.30f + next() * 0.40f
        }
        return out
    }

    fun lerpArgb(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int): Int { val x = (a shr sh) and 255; val y = (b shr sh) and 255; return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Separable box blur on ARGB pixels (alpha kept opaque), edges clamped. [tmp] must be the same size as [px]. */
    fun boxBlur(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int, passes: Int) {
        repeat(passes) { pass1(px, tmp, w, h, r, true); pass1(tmp, px, w, h, r, false) }
    }

    private fun pass1(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w; val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) src[l * w + c] else src[c * w + l] }
            for (i in -r..r) { val c = at(i); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                val o = if (horizontal) l * w + i else i * w + l
                dst[o] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(i + r + 1); val sub = at(i - r)
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }

    /** One-time +-1 LSB dither against gradient banding on the full-size background. */
    fun dither(px: IntArray, seed: Int = 7) {
        var s = seed or 1
        for (i in px.indices) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            val d = ((s ushr 8) % 3) - 1
            if (d == 0) continue
            val c = px[i]
            val r = (((c shr 16) and 255) + d).coerceIn(0, 255); val g = (((c shr 8) and 255) + d).coerceIn(0, 255); val b = ((c and 255) + d).coerceIn(0, 255)
            px[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * When the sky splash shows. Every open: a launcher (MAIN) cold start, and coming back to Blaze from outside
 * (launcher, Recents). Not when Blaze returns from one of its own screens, after the screen was only switched off,
 * out of picture-in-picture, behind the app lock, or for any other intent (call, listen, sign-in link, share, a
 * notification): those want Blaze at once.
 */
object SkySplashGate {
    const val MAIN = "android.intent.action.MAIN"

    fun onCold(action: String?, listen: Boolean, locked: Boolean): Boolean =
        !locked && !listen && (action == null || action == MAIN)

    fun onReturn(ownScreenRecently: Boolean, stoppedByScreenOff: Boolean, pip: Boolean, locked: Boolean): Boolean =
        !ownScreenRecently && !stoppedByScreenOff && !pip && !locked

    /** A new intent that wants Blaze straight away cancels a pending or running splash. */
    fun cancelFor(action: String?, listen: Boolean): Boolean = listen || (action != null && action != MAIN)
}
