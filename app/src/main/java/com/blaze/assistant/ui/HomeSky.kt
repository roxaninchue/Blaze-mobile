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
 * 0.47 Home sky: the blurred time-of-day sky with three misty mountain ridges (traced from the approved video) behind the
 * splash and the settled grid.
 * This is the one cached blur: drawn ONCE off the main thread at 1/[SCALE] size, box-blurred, and kept as that small
 * bitmap (about 130 KB on a 720 x 1600 phone); every frame only draws it scaled up with bilinear filtering, which also
 * softens it further. No RenderEffect, no per-frame blur. Pure numbers in [HomeSkySpec] (shared with the v1 sky splash).
 */
object HomeSkySpec {
    /**
     * Sky top, upper sky, horizon glow, and the three ridges far -> near (ARGB), sampled from the approved video's
     * frames (540 x 1200): #2D7ECD at the top, #60A5E0 at 30 %, #B6D7EE just above the ridges, ridges #94ADC0 / #6F8AA1 /
     * #45617C. Night keeps the same layering in deep blues (the splash outline is black by day, white at night).
     */
    val DAY = intArrayOf(0xFF2D7ECD.toInt(), 0xFF60A5E0.toInt(), 0xFFB6D7EE.toInt(), 0xFF94ADC0.toInt(), 0xFF6F8AA1.toInt(), 0xFF45617C.toInt())
    val NIGHT = intArrayOf(0xFF060A1C.toInt(), 0xFF14214A.toInt(), 0xFF2A3B6E.toInt(), 0xFF1F2B4E.toInt(), 0xFF151F3C.toInt(), 0xFF0B1226.toInt())
    val STOPS = floatArrayOf(0f, 0.30f, 0.58f, 1f)
    /**
     * The video's three misty mountain ridges, far -> near: the ridge's top edge in thousandths of the screen height at
     * 46 evenly spaced points across the width (traced from the approved video's frame, every 12 px of 540).
     */
    val PROFILES = arrayOf(
        shortArrayOf(550, 548, 543, 540, 543, 549, 558, 560, 555, 548, 547, 547, 552, 557, 560, 560, 570, 580, 593, 583, 583, 583, 587, 587,
            583, 583, 583, 583, 585, 587, 587, 586, 580, 580, 573, 573, 570, 570, 560, 545, 530, 522, 520, 527, 533, 535),
        shortArrayOf(617, 620, 625, 632, 652, 680, 694, 694, 692, 689, 687, 680, 668, 657, 656, 661, 662, 654, 638, 630, 624, 617, 608, 604,
            623, 642, 655, 664, 671, 678, 683, 683, 675, 670, 672, 683, 688, 682, 672, 664, 655, 639, 617, 601, 599, 603),
        shortArrayOf(757, 762, 765, 758, 733, 718, 710, 705, 705, 718, 728, 725, 722, 728, 733, 739, 755, 775, 778, 769, 760, 751, 738, 728,
            730, 734, 728, 715, 710, 705, 702, 711, 735, 749, 753, 759, 760, 755, 750, 757, 764, 762, 752, 742, 730, 718),
    )
    /** The tree-line roughness on top of each traced ridge (fraction of the height, number of small peaks across). */
    val ROUGH_AMP = floatArrayOf(0.0045f, 0.0055f, 0.0065f)
    val ROUGH_PEAKS = floatArrayOf(23f, 29f, 37f)

    /** Top of ridge [r] (0 = far) at x in 0..1, as a fraction of the height: the traced profile (linear between points) plus a small zigzag. */
    fun ridgeY(r: Int, x: Float): Float {
        val p = PROFILES[r]; val n = p.size - 1
        val f = (x.coerceIn(0f, 1f) * n); val i = f.toInt().coerceAtMost(n - 1); val t = f - i
        val base = (p[i] + (p[i + 1] - p[i]) * t) / 1000f
        val z = (x * ROUGH_PEAKS[r]) % 1f
        val tri = if (z < 0.5f) z * 2f else 2f - z * 2f      // 0..1..0, a sharp peak per step
        return base - ROUGH_AMP[r] * tri
    }

    const val SCALE = 6
    const val RADIUS = 2
    const val PASSES = 3

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
        val steps = (sw * 2).coerceIn(90, 400)
        for (r in HomeSkySpec.PROFILES.indices) {
            path.reset(); path.moveTo(0f, HomeSkySpec.ridgeY(r, 0f) * sh)
            for (i in 1..steps) { val x = i / steps.toFloat(); path.lineTo(x * sw, HomeSkySpec.ridgeY(r, x) * sh) }
            path.lineTo(sw.toFloat(), sh.toFloat()); path.lineTo(0f, sh.toFloat()); path.close()
            paint.color = p[3 + r]; c.drawPath(path, paint)
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
        val brush = Brush.verticalGradient(0f to Color(p[0]), HomeSkySpec.STOPS[1] to Color(p[1]), HomeSkySpec.STOPS[2] to Color(p[2]), 0.62f to Color(p[4]), 1f to Color(p[5]))
        val bmp = img.value
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        onDrawBehind {
            if (bmp != null) drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), dstSize = dst, filterQuality = FilterQuality.Low)
            else drawRect(brush)
        }
    })
}

/**
 * When the redesigned Home draws its sky (pure, HomeSequenceTest). The approved design is one look: the sky and mountains
 * are drawn on the new Home whatever living theme or Home colour is picked (0.48.0 showed the theme's dark scene there,
 * so the phone never looked like the video). Only a photo the user picked as Home's background still shows instead.
 */
object HomeSkyRule {
    fun drawsSky(redesign: Boolean, background: String, hasPhoto: Boolean): Boolean = redesign && !(background == "image" && hasPhoto)
}

