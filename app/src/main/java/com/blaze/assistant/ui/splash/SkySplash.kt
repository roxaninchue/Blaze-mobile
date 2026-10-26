package com.blaze.assistant.ui.splash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.blaze.assistant.ui.splash.SkySplashSpec as S

/**
 * The blurred sky + mountains, drawn ONCE off the main thread into a bitmap: 1/6 size, box-blurred, scaled up
 * bilinearly and dithered. Nothing is blurred per frame (no RenderEffect: a full-screen live blur is too heavy for the
 * PowerVR GE8320 in the Galaxy A12, and a static image does not need it). Start [prepare] as early as possible
 * (MainActivity.onCreate); the overlay draws the plain gradient until it lands.
 */
object SkyArt {
    val art = mutableStateOf<ImageBitmap?>(null)
    @Volatile private var preparedFor: String? = null

    fun prepare(width: Int, height: Int, phase: S.Phase) {
        if (width <= 0 || height <= 0) return
        val key = "$width x $height $phase"
        if (preparedFor == key && art.value != null) return
        preparedFor = key; art.value = null
        kotlin.concurrent.thread(isDaemon = true, name = "sky-art", priority = Thread.NORM_PRIORITY - 1) {
            val bmp = try { render(width, height, phase) } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("SkyArt", t); null }
            if (bmp != null && preparedFor == key) art.value = bmp.asImageBitmap()
        }
    }

    /** Free the bitmap once the splash is gone (low-RAM phones). */
    fun release() { art.value = null; preparedFor = null }

    fun render(width: Int, height: Int, phase: S.Phase): Bitmap {
        val p = S.palette(phase)
        val sw = (width / S.BG_SCALE).coerceAtLeast(8); val sh = (height / S.BG_SCALE).coerceAtLeast(8)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, sh.toFloat(), intArrayOf(p.top, p.mid, p.horizon, p.horizon), S.SKY_STOPS, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), paint)
        paint.shader = null
        val path = android.graphics.Path()
        for (r in S.RIDGES.indices) {
            path.reset(); path.moveTo(0f, S.ridgeY(r, 0f) * sh)
            for (i in 1..S.RIDGE_SAMPLES) { val x = i / S.RIDGE_SAMPLES.toFloat(); path.lineTo(x * sw, S.ridgeY(r, x) * sh) }
            path.lineTo(sw.toFloat(), sh.toFloat()); path.lineTo(0f, sh.toFloat()); path.close()
            paint.color = S.ridgeColor(p, r); c.drawPath(path, paint)
        }
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        S.boxBlur(px, IntArray(px.size), sw, sh, S.BOX_RADIUS, S.BOX_PASSES)
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val full = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()
        val fp = IntArray(width * height); full.getPixels(fp, 0, width, 0, 0, width, height)
        S.dither(fp); full.setPixels(fp, 0, width, 0, 0, width, height)
        full.prepareToDraw()
        return full
    }
}

/**
 * The open animation: blurred time-of-day sky, a transparent black-outlined cloud, three dots bouncing right to left.
 * It overlays the app (composed underneath) and never blocks it: after [S.MIN_CYCLES] waves it cross-fades out on the
 * first wave boundary where [ready] is true (at the latest [S.MAX_MS]). Tap skips. With animations off it shows the
 * static frame for [S.STATIC_MS] and cuts.
 *
 * Per frame only one Long state changes; it is read in draw/layer lambdas, so nothing recomposes, and the cloud path,
 * stroke, stars and dot positions are built once in drawWithCache. [frozenAtMs] pins the clock (screenshot tests).
 */
@Composable
fun SkySplash(phase: S.Phase, ready: () -> Boolean, motion: Boolean, onFinished: () -> Unit, frozenAtMs: Long? = null) {
    val palette = remember(phase) { S.palette(phase) }
    val clock = remember { mutableLongStateOf(frozenAtMs ?: if (motion) 0L else S.WAVE_T0_MS) }
    val fadeAt = remember { mutableLongStateOf(-1L) }
    val skip = remember { mutableStateOf(false) }
    val readyNow by rememberUpdatedState(ready)
    val done by rememberUpdatedState(onFinished)

    if (frozenAtMs == null) LaunchedEffect(motion) {
        var t0 = -1L
        while (true) {
            val now = withFrameMillis { it }
            if (t0 < 0) t0 = now
            val t = now - t0
            if (!motion) {
                // static frame: no hops, no fades
                if (skip.value || (t >= S.STATIC_MS && readyNow()) || t >= S.MAX_MS) { done(); return@LaunchedEffect }
                continue
            }
            clock.longValue = t
            if (fadeAt.longValue < 0) {
                val f = if (skip.value) t else S.fadeStart(t, readyNow())
                if (f >= 0) fadeAt.longValue = f
            }
            if (fadeAt.longValue >= 0 && t - fadeAt.longValue >= S.CROSSFADE_MS) { done(); return@LaunchedEffect }
        }
    }

    val cycles = { if (fadeAt.longValue >= 0 && !skip.value) S.cyclesDone(fadeAt.longValue) else Int.MAX_VALUE }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val f = fadeAt.longValue
                alpha = if (f < 0) 1f else (1f - (clock.longValue - f).toFloat() / S.CROSSFADE_MS).coerceIn(0f, 1f)
            }
            .pointerInput(Unit) { detectTapGestures { skip.value = true } }
    ) {
        // 1. background: cached blurred bitmap (or the plain gradient until it lands) + night stars. Own layer: never redrawn per frame.
        Box(Modifier.fillMaxSize().graphicsLayer { }.drawWithCache {
            val img = SkyArt.art.value
            val brush = Brush.verticalGradient(
                0f to Color(palette.top), S.SKY_STOPS[1] to Color(palette.mid), S.SKY_STOPS[2] to Color(palette.horizon), 1f to Color(palette.horizon))
            val starData = if (palette.stars) S.stars() else null
            val dst = IntSize(size.width.toInt(), size.height.toInt())
            val starColor = Color(0xFFEBF0FF)
            onDrawBehind {
                if (img != null) drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = dst, filterQuality = FilterQuality.Low)
                else drawRect(brush)
                if (starData != null) {
                    var k = 0
                    while (k < starData.size) {
                        drawCircle(starColor, radius = starData[k + 2] * density, center = Offset(starData[k] * size.width, starData[k + 1] * size.height), alpha = starData[k + 3])
                        k += 4
                    }
                }
            }
        })
        // 2. cloud: one closed cubic outline, 3 dp black, round joins, transparent inside. Fades + scales in on its layer.
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val p = S.easeOutCubic(clock.longValue.toFloat() / S.CLOUD_IN_MS)
                alpha = p; val s = S.CLOUD_SCALE_FROM + (1f - S.CLOUD_SCALE_FROM) * p; scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.5f, S.CLOUD_CENTER_Y)
            }
            .drawWithCache {
                val seg = FloatArray(8 * S.CLOUD_SEGMENTS)
                S.cloudCubics(size.width / 2f, size.height * S.CLOUD_CENTER_Y, size.width * S.CLOUD_WIDTH, seg)
                val path = Path().apply {
                    moveTo(seg[0], seg[1])
                    for (k in 0 until S.CLOUD_SEGMENTS) { val o = k * 8; cubicTo(seg[o + 2], seg[o + 3], seg[o + 4], seg[o + 5], seg[o + 6], seg[o + 7]) }
                    close()
                }
                val stroke = Stroke(width = S.STROKE_DP * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, Color.Black, style = stroke) }
            })
        // 3. dots: positions computed once; per frame only the three lifts are read from the clock.
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val t = clock.longValue
                alpha = if (t <= S.DOTS_IN_AT_MS) 0f else S.easeOutCubic((t - S.DOTS_IN_AT_MS).toFloat() / S.DOTS_IN_MS)
            }
            .drawWithCache {
                val bottom = S.cloudCubics(size.width / 2f, size.height * S.CLOUD_CENTER_Y, size.width * S.CLOUD_WIDTH, FloatArray(8 * S.CLOUD_SEGMENTS))
                val r = S.DOT_DP * density / 2f; val gap = S.DOT_GAP_DP * density
                val cy = bottom + S.DOTS_BELOW_CLOUD_DP * density + r
                val x0 = size.width / 2f - (3 * 2 * r + 2 * gap) / 2f + r
                val step = 2 * r + gap; val hopPx = S.HOP_DP * density
                onDrawBehind {
                    val t = clock.longValue; val n = cycles()
                    for (i in 0..2) drawCircle(Color.Black, radius = r, center = Offset(x0 + i * step, cy - S.dotLift(i, t, n) * hopPx))
                }
            })
    }
}
