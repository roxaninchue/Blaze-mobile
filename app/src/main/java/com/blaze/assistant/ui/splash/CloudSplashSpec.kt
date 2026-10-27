package com.blaze.assistant.ui.splash

import com.blaze.assistant.home.WeatherKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 0.47 splash -> Home, the design numbers with no Android types (CloudSplashSpecTest). The splash is a simple two-lobe
 * OUTLINED cloud (transparent inside) at the v1 splash's size and position (62% of the screen width, centred at 40% of
 * the height), with one weather variant on top: clear, partly cloudy (day: sun / night: moon), overcast, rain, snow,
 * thunderstorm, fog. Black outline by day, white at night. Three dots under it hop in a wave from right to left.
 *
 * All glyph geometry is in "cloud units": x 0..1 across the cloud's width, y down from the cloud's top, so the same
 * numbers draw the splash (cloud ~220 dp wide) and the weather board's icon (~26 dp) the cloud glides into.
 */
object CloudSplashSpec {
    // ---------------- placement (same as the v1 sky splash) ----------------
    /** The cloud body's width (59% of the screen width; with the sun the glyph is ~62%, like v1) and its centre (of the height). */
    const val CLOUD_WIDTH = 0.59f
    const val CLOUD_CENTER_Y = 0.376f
    const val STROKE_DP = 3f
    /** Height of the glyph box in cloud units (the sun's rays reach above the cloud, the precipitation below). */
    const val BOX_TOP = -0.30f
    const val BOX_BOTTOM = 0.78f
    /** The cloud body's own height in cloud units. */
    const val BODY_BOTTOM = 0.53f

    // ---------------- the two-lobe cloud ----------------
    /**
     * Valley points of the outline (x, y in cloud units, clockwise on screen) and the bulge of the arc that starts there
     * (as a fraction of its chord): left end, big left lobe, smaller right lobe, right end, flat bottom.
     */
    val CLOUD = floatArrayOf(
        0.17f, 0.53f, 0.72f,    // bottom-left -> up the left end
        0.16f, 0.205f, 0.70f,   // left shoulder -> big lobe
        0.52f, 0.145f, 0.64f,   // notch -> small lobe
        0.815f, 0.205f, 0.72f,  // right shoulder -> right end
        0.835f, 0.53f, 0.0f,    // bottom-right -> flat bottom
    )
    val SEGMENTS = CLOUD.size / 3
    /** Control points pulled towards each other along the chord, so lobes meet in clean V notches. */
    const val SKEW = 0.20f

    /** The closed outline as cubics (ax, ay, c1x, c1y, c2x, c2y, bx, by) in cloud units, scaled by [s] and moved by (ox, oy). */
    fun cloudCubics(ox: Float = 0f, oy: Float = 0f, s: Float = 1f): FloatArray {
        val out = FloatArray(SEGMENTS * 8)
        for (k in 0 until SEGMENTS) {
            val n = (k + 1) % SEGMENTS
            val ax = CLOUD[k * 3]; val ay = CLOUD[k * 3 + 1]
            val bx = CLOUD[n * 3]; val by = CLOUD[n * 3 + 1]
            val dx = bx - ax; val dy = by - ay; val len = hypot(dx, dy)
            val nx = dy / len; val ny = -dx / len   // outward normal for a clockwise outline (y down)
            val h = CLOUD[k * 3 + 2] * len
            val o = k * 8
            out[o] = ax; out[o + 1] = ay
            out[o + 2] = ax + nx * h + dx * SKEW; out[o + 3] = ay + ny * h + dy * SKEW
            out[o + 4] = bx + nx * h - dx * SKEW; out[o + 5] = by + ny * h - dy * SKEW
            out[o + 6] = bx; out[o + 7] = by
        }
        for (i in out.indices) out[i] = if (i % 2 == 0) ox + out[i] * s else oy + out[i] * s
        return out
    }

    /** Point on cubic [k] of [c] at t. */
    fun cubicAt(c: FloatArray, k: Int, t: Float): Pair<Float, Float> {
        val o = k * 8; val u = 1 - t
        val x = u * u * u * c[o] + 3 * u * u * t * c[o + 2] + 3 * u * t * t * c[o + 4] + t * t * t * c[o + 6]
        val y = u * u * u * c[o + 1] + 3 * u * u * t * c[o + 3] + 3 * u * t * t * c[o + 5] + t * t * t * c[o + 7]
        return x to y
    }

    /** Bounding box (minX, minY, maxX, maxY) of the outline, sampled. */
    fun cloudBounds(c: FloatArray = cloudCubics()): FloatArray {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (k in 0 until SEGMENTS) for (i in 0..24) {
            val (x, y) = cubicAt(c, k, i / 24f)
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    // ---------------- variant glyphs (cloud units) ----------------
    /** What a variant draws besides (or instead of) the cloud. */
    class Glyph(
        val cloud: Boolean,
        /** Sun / moon disc centre + radius (r <= 0: none). Parts inside the cloud are not drawn. */
        val discX: Float, val discY: Float, val discR: Float,
        val moon: Boolean,
        /** Rays as line segments (x0, y0, x1, y1 …). */
        val rays: FloatArray,
        /** A second cloud behind (overcast): offset and scale of the same outline, drawn outside the front cloud only. */
        val backCloud: Boolean,
        /** Strokes under the cloud: rain dashes, fog bars or the bolt (polyline) (x0, y0, x1, y1 …). */
        val lines: FloatArray,
        /** Snow dots (x, y, r …). */
        val dots: FloatArray,
        /** Lines are one polyline (the bolt) rather than separate segments. */
        val polyline: Boolean = false,
    )

    const val BACK_DX = 0.16f
    const val BACK_DY = -0.17f
    const val BACK_SCALE = 0.72f

    private fun rays(cx: Float, cy: Float, r0: Float, r1: Float, anglesDeg: IntArray): FloatArray {
        val out = FloatArray(anglesDeg.size * 4)
        anglesDeg.forEachIndexed { i, a ->
            val rad = a * PI.toFloat() / 180f
            out[i * 4] = cx + cos(rad) * r0; out[i * 4 + 1] = cy + sin(rad) * r0
            out[i * 4 + 2] = cx + cos(rad) * r1; out[i * 4 + 3] = cy + sin(rad) * r1
        }
        return out
    }

    private val NONE = FloatArray(0)

    /** The glyph for a variant. Clear has no cloud: a sun (or a crescent moon at night) in the cloud's place. */
    fun glyph(kind: WeatherKind, night: Boolean): Glyph = when (kind) {
        WeatherKind.CLEAR -> if (night) Glyph(false, 0.50f, 0.22f, 0.25f, true, NONE, false, NONE, floatArrayOf(0.86f, 0.02f, 0.025f, 0.13f, 0.48f, 0.018f))
            else Glyph(false, 0.50f, 0.24f, 0.19f, false, rays(0.50f, 0.24f, 0.27f, 0.36f, intArrayOf(-90, -45, 0, 45, 90, 135, 180, -135)), false, NONE, NONE)
        WeatherKind.PARTLY_CLOUDY -> if (night) Glyph(true, 0.78f, 0.05f, 0.15f, true, NONE, false, NONE, NONE)
            else Glyph(true, 0.80f, 0.03f, 0.14f, false, rays(0.80f, 0.03f, 0.21f, 0.27f, intArrayOf(-90, -135, -45, 0, 45)), false, NONE, NONE)
        WeatherKind.OVERCAST -> Glyph(true, 0f, 0f, 0f, false, NONE, true, NONE, NONE)
        WeatherKind.RAIN -> Glyph(true, 0f, 0f, 0f, false, NONE, false,
            floatArrayOf(0.32f, 0.62f, 0.27f, 0.74f, 0.50f, 0.62f, 0.45f, 0.74f, 0.68f, 0.62f, 0.63f, 0.74f), NONE)
        WeatherKind.SNOW -> Glyph(true, 0f, 0f, 0f, false, NONE, false, NONE,
            floatArrayOf(0.30f, 0.65f, 0.022f, 0.50f, 0.70f, 0.022f, 0.70f, 0.65f, 0.022f, 0.40f, 0.76f, 0.022f, 0.60f, 0.76f, 0.022f))
        WeatherKind.THUNDER -> Glyph(true, 0f, 0f, 0f, false, NONE, false,
            floatArrayOf(0.54f, 0.58f, 0.44f, 0.68f, 0.55f, 0.68f, 0.45f, 0.78f), NONE, polyline = true)
        WeatherKind.FOG -> Glyph(true, 0f, 0f, 0f, false, NONE, false,
            floatArrayOf(0.16f, 0.63f, 0.78f, 0.63f, 0.28f, 0.72f, 0.90f, 0.72f), NONE)
    }

    /** Extra space the dots keep below the cloud when the variant draws under it (rain, snow, bolt, fog). */
    fun dotsDrop(kind: WeatherKind): Float = when (kind) {
        WeatherKind.RAIN, WeatherKind.SNOW, WeatherKind.THUNDER, WeatherKind.FOG -> 0.28f
        else -> 0f
    }

    // ---------------- colours ----------------
    const val INK_DAY = 0xFF111418.toInt()
    const val INK_NIGHT = 0xFFFFFFFF.toInt()
    fun ink(night: Boolean): Int = if (night) INK_NIGHT else INK_DAY

    // ---------------- dots ----------------
    const val DOT_DP = 8f
    const val DOT_GAP_DP = 10f
    const val HOP_DP = 10f
    const val DOTS_BELOW_CLOUD_DP = 22f
    const val WAVE_T0_MS = 300L
    const val PERIOD_MS = 640L
    const val STAGGER_MS = 110L
    const val HOP_MS = 360L

    /** One hop 0..1 -> lift 0..1: up ease-out, down ease-in, small landing rebound. */
    fun hop(p: Float): Float = when {
        p < 0f || p >= 1f -> 0f
        p < 0.40f -> { val q = p / 0.40f; 1 - (1 - q) * (1 - q) }
        p < 0.85f -> { val q = (p - 0.40f) / 0.45f; 1 - q * q }
        else -> 0.12f * sin(PI * (p - 0.85f) / 0.15f).toFloat()
    }

    /** Lift of dot [i] (0 = left, 2 = right) at [tMs]: the wave starts at the RIGHTMOST dot and travels left. */
    fun dotLift(i: Int, tMs: Long): Float {
        val t = tMs - WAVE_T0_MS
        if (t < 0) return 0f
        val local = (t % PERIOD_MS) - (2 - i) * STAGGER_MS
        return hop(local.toFloat() / HOP_MS)
    }

    // ---------------- the splash's own timeline ----------------
    const val CLOUD_IN_MS = 300L
    const val CLOUD_SCALE_FROM = 0.92f
    const val DOTS_IN_AT_MS = 150L
    const val DOTS_IN_MS = 150L
    /** At least one full wave, then leave on a wave boundary once Home's data is ready, at the latest [SPLASH_MAX_MS]. */
    const val SPLASH_MIN_MS = WAVE_T0_MS + PERIOD_MS
    const val SPLASH_MAX_MS = 2_500L

    /** When the splash hands over to the glide, given that at [tMs] Home is [ready]; -1 = keep looping. */
    fun splashEnd(tMs: Long, ready: Boolean): Long {
        if (tMs >= SPLASH_MAX_MS) return tMs
        if (!ready || tMs < SPLASH_MIN_MS) return -1
        val waves = (tMs - WAVE_T0_MS) / PERIOD_MS
        return WAVE_T0_MS + waves * PERIOD_MS
    }

    fun easeOutCubic(t: Float): Float { val c = t.coerceIn(0f, 1f); val u = 1 - c; return 1 - u * u * u }
    fun easeInOutCubic(t: Float): Float { val c = t.coerceIn(0f, 1f); return if (c < 0.5f) 4 * c * c * c else 1 - (-2 * c + 2).let { it * it * it } / 2 }
    fun progress(u: Long, start: Long, dur: Long): Float = if (dur <= 0) (if (u >= start) 1f else 0f) else ((u - start).toFloat() / dur).coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float, t: Float) = if (t >= 1f) b else a + (b - a) * t
}

/**
 * The hand-over after the splash (u = ms since the splash ended): the cloud shrinks and glides top-left into the weather
 * board's icon, the board's outline draws itself and its text types in (location, condition + temperature, sunset),
 * then each other card emerges at the centre, expands, and settles into its grid cell. Pure (CloudSplashSpecTest).
 */
object HomeIntroSpec {
    const val DOTS_OUT_MS = 150L
    const val GLIDE_MS = 520L
    const val OUTLINE_AT = 360L
    const val OUTLINE_MS = 480L
    const val TILES_AT = 560L
    const val TILES_MS = 320L
    const val TYPE_PLACE_AT = 480L
    const val TYPE_COND_AT = 760L
    const val TYPE_SUN_AT = 980L
    const val TYPE_MS = 260L
    const val BOARD_FILL_AT = 1_120L
    const val FILL_MS = 220L
    const val CARDS_AT = 1_160L
    const val CARD_STAGGER = 230L
    const val EMERGE_MS = 220L
    const val SETTLE_AT = 200L
    const val SETTLE_MS = 320L
    const val CARD_FILL_AT = 440L
    const val CENTER_SCALE = 0.55f

    /** u when everything has settled for [cards] centre-emerging cards. */
    fun end(cards: Int): Long = CARDS_AT + (cards - 1).coerceAtLeast(0) * CARD_STAGGER + CARD_FILL_AT + FILL_MS

    /** Glide progress of the cloud into the icon (eased). */
    fun glide(u: Long): Float = CloudSplashSpec.easeInOutCubic(CloudSplashSpec.progress(u, 0, GLIDE_MS))

    /** Characters of a [len]-long line typed by u (the line types over [TYPE_MS] from [at]). */
    fun typed(len: Int, at: Long, u: Long): Int = (len * CloudSplashSpec.progress(u, at, TYPE_MS) + 0.0001f).toInt().coerceIn(0, len)

    /** One card's state: [alpha], [scale], [move] 0 (at the centre) -> 1 (in its cell), [fill] 0 (light glass outline) -> 1 (settled), [content] alpha. */
    data class CardT(val alpha: Float, val scale: Float, val move: Float, val fill: Float, val content: Float)

    val SETTLED = CardT(1f, 1f, 1f, 1f, 1f)

    /** The [i]-th centre-emerging card (0 = Interests) at u. */
    fun card(i: Int, u: Long): CardT {
        val s = CARDS_AT + i * CARD_STAGGER
        val e = CloudSplashSpec.easeOutCubic(CloudSplashSpec.progress(u, s, EMERGE_MS))
        val m = CloudSplashSpec.easeInOutCubic(CloudSplashSpec.progress(u, s + SETTLE_AT, SETTLE_MS))
        val f = CloudSplashSpec.progress(u, s + CARD_FILL_AT, FILL_MS)
        val content = CloudSplashSpec.progress(u, s + EMERGE_MS / 2, EMERGE_MS)
        return CardT(alpha = e, scale = CloudSplashSpec.lerp(CENTER_SCALE, 1f, CloudSplashSpec.easeOutCubic(CloudSplashSpec.progress(u, s, EMERGE_MS + SETTLE_AT))),
            move = m, fill = f, content = content)
    }

    /** The weather board: outline trim 0..1, tiles 0..1, fill 0..1. */
    fun outline(u: Long) = CloudSplashSpec.progress(u, OUTLINE_AT, OUTLINE_MS)
    fun tiles(u: Long) = CloudSplashSpec.progress(u, TILES_AT, TILES_MS)
    fun boardFill(u: Long) = CloudSplashSpec.progress(u, BOARD_FILL_AT, FILL_MS)
}
