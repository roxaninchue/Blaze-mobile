package com.blaze.assistant.composer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 0.48.0 RC2: the "one cached blur" in software (pure; SoftBlurTest). Works on every phone, including low-RAM ones
 * where RenderEffect blur is off: the Home picture is taken once at 1/[SCALE] size, blurred here on a background
 * thread, and drawn full-screen scaled back up (bilinear), which adds more softness for free.
 *
 * Three box passes per axis approximate a Gaussian. On a 1/8 picture of a 720 x 1600 screen (90 x 200 px) this is
 * well under 2 ms of CPU, never on the main thread.
 */
object SoftBlur {
    /** Downscale factor for the cached picture. */
    const val SCALE = 8
    /** Box radius (px of the small picture) at SCALE 8: ~ a 40 px Gaussian on the full screen. */
    const val RADIUS = 3
    const val PASSES = 3

    /** Size of the small picture for a [w] x [h] screen at 1/[scale]. Never 0. */
    fun smallSize(w: Int, h: Int, scale: Int = SCALE): Pair<Int, Int> = max(1, (w.toFloat() / scale).roundToInt()) to max(1, (h.toFloat() / scale).roundToInt())

    /** Blurs ARGB [px] ([w] x [h]) and returns a new array. [radius] 0 returns a copy. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int = RADIUS, passes: Int = PASSES): IntArray {
        require(px.size >= w * h && w > 0 && h > 0)
        var a = px.copyOf(w * h)
        if (radius <= 0) return a
        var b = IntArray(w * h)
        repeat(passes) {
            boxH(a, b, w, h, radius); val t = a; a = b; b = t
            boxV(a, b, w, h, radius); val u = a; a = b; b = u
        }
        return a
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + min(max(i, 0), w - 1)]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[row + min(x + r + 1, w - 1)]
                val rem = src[row + max(x - r, 0)]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[min(max(i, 0), h - 1) * w + x]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[min(y + r + 1, h - 1) * w + x]
                val rem = src[max(y - r, 0) * w + x]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
