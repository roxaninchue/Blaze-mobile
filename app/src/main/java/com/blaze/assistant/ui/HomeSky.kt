package com.blaze.assistant.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * 0.47 Home sky: the blurred time-of-day sky with three soft mountain ridges behind the splash and the settled grid.
 * This is the one cached blur: drawn ONCE off the main thread at 1/[SCALE] size, box-blurred, and kept as that small
 * bitmap (about 130 KB on a 720 x 1600 phone); every frame only draws it scaled up with bilinear filtering, which also
 * softens it further. No RenderEffect, no per-frame blur. Pure numbers in [HomeSkySpec] (shared with the v1 sky splash).
 */
object HomeSkySpec {
    /** Sky top, middle, horizon, nearest mountain (ARGB): day and night (the splash outline is black by day, white at night). */
    val DAY = intArrayOf(0xFF2F7FD0.toInt(), 0xFF78B6E8.toInt(), 0xFFD8ECF8.toInt(), 0xFF46627E.toInt())
    val NIGHT = intArrayOf(0xFF060A1C.toInt(), 0xFF14214A.toInt(), 0xFF2A3B6E.toInt(), 0xFF070B1A.toInt())
    val STOPS = floatArrayOf(0f, 0.42f, 0.66f, 1f)
    /** Per ridge: base (fraction of height), haze towards the horizon colour, then (amp, freq, phase) x 3. */
    val RIDGES = arrayOf(
        floatArrayOf(0.585f, 0.55f, 0.050f, 1.30f, 0.40f, 0.020f, 3.10f, 1.70f, 0.008f, 7.30f, 4.10f),
        floatArrayOf(0.650f, 0.30f, 0.038f, 1.90f, 2.20f, 0.016f, 4.30f, 0.60f, 0.007f, 9.10f, 3.30f),
        floatArrayOf(0.735f, 0.00f, 0.028f, 2.30f, 5.10f, 0.013f, 5.70f, 2.90f, 0.006f, 11.9f, 0.90f),
    )
    const val SCALE = 6
    const val RADIUS = 2
    const val PASSES = 3

    fun ridgeY(r: Int, x: Float): Float {
        val d = RIDGES[r]; var y = d[0]; var i = 2
        while (i < d.size) { y -= d[i] * sin(2.0 * PI * d[i + 1] * x + d[i + 2]).toFloat(); i += 3 }
        return y
    }

    fun lerpArgb(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int): Int { val x = (a shr sh) and 255; val y = (b shr sh) and 255; return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Separable box blur on opaque ARGB pixels, edges clamped. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, passes: Int) {
        val tmp = IntArray(px.size)
        repeat(passes) { pass(px, tmp, w, h, r, true); pass(tmp, px, w, h, r, false) }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w; val len = if (horizontal) w else h; val div = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) src[l * w + c] else src[c * w + l] }
            for (i in -r..r) { val c = at(i); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                dst[if (horizontal) l * w + i else i * w + l] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(i + r + 1); val sub = at(i - r)
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }
}

object HomeSkyArt {
    private val cache = mutableStateOf<Pair<String, ImageBitmap>?>(null)

    /** The small blurred sky for a w x h screen (cached; rendered on a worker thread). */
    suspend fun get(width: Int, height: Int, night: Boolean): ImageBitmap? {
        if (width <= 0 || height <= 0) return null
        val key = "${width}x$height:$night"
        cache.value?.let { if (it.first == key) return it.second }
        val img = withContext(Dispatchers.Default) {
            try { render(width, height, night).asImageBitmap() } catch (t: Throwable) { null }
        } ?: return null
        cache.value = key to img
        return img
    }

    fun render(width: Int, height: Int, night: Boolean): Bitmap {
        val p = if (night) HomeSkySpec.NIGHT else HomeSkySpec.DAY
        val sw = (width / HomeSkySpec.SCALE).coerceAtLeast(8); val sh = (height / HomeSkySpec.SCALE).coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, sh.toFloat(), intArrayOf(p[0], p[1], p[2], p[2]), HomeSkySpec.STOPS, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), paint)
        paint.shader = null
        val path = android.graphics.Path()
        for (r in HomeSkySpec.RIDGES.indices) {
            path.reset(); path.moveTo(0f, HomeSkySpec.ridgeY(r, 0f) * sh)
            for (i in 1..120) { val x = i / 120f; path.lineTo(x * sw, HomeSkySpec.ridgeY(r, x) * sh) }
            path.lineTo(sw.toFloat(), sh.toFloat()); path.lineTo(0f, sh.toFloat()); path.close()
            paint.color = HomeSkySpec.lerpArgb(p[3], p[2], HomeSkySpec.RIDGES[r][1]); c.drawPath(path, paint)
        }
        val px = IntArray(sw * sh); bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        HomeSkySpec.boxBlur(px, sw, sh, HomeSkySpec.RADIUS, HomeSkySpec.PASSES)
        bmp.setPixels(px, 0, sw, 0, 0, sw, sh)
        bmp.prepareToDraw()
        return bmp
    }
}

/**
 * The sky as a background. Draws the plain gradient until the cached bitmap lands (one frame or two later), on its own
 * layer so nothing above it ever redraws it.
 */
@Composable
fun HomeSkyBackground(night: Boolean, modifier: Modifier = Modifier) {
    val img = remember { mutableStateOf<ImageBitmap?>(null) }
    val px = remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(px.value, night) { if (px.value != IntSize.Zero) img.value = HomeSkyArt.get(px.value.width, px.value.height, night) }
    val p = if (night) HomeSkySpec.NIGHT else HomeSkySpec.DAY
    Box(modifier.onSizeChanged { px.value = it }.graphicsLayer { }.drawWithCache {
        val brush = Brush.verticalGradient(0f to Color(p[0]), HomeSkySpec.STOPS[1] to Color(p[1]), HomeSkySpec.STOPS[2] to Color(p[2]), 1f to Color(p[2]))
        val bmp = img.value
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        onDrawBehind {
            if (bmp != null) drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), dstSize = dst, filterQuality = FilterQuality.Low)
            else drawRect(brush)
        }
    })
}
