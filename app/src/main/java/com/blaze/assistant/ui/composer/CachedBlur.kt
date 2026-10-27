package com.blaze.assistant.ui.composer

import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.blaze.assistant.composer.ComposerMotion
import com.blaze.assistant.composer.SoftBlur
import com.blaze.assistant.ui.MotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 0.48.0 RC2: the ONE cached blur, as a small reusable util (chat / Spotlight over Home; rc2-home's card focus).
 * Works on every phone: it is a software blur of a small picture, so low-RAM phones (no RenderEffect) get a real
 * blur too, and the shots renderer can draw it.
 *
 * Usage:
 * ```
 * val blur = rememberCachedBlur()
 * Box(Modifier.cachedBlurSource(blur)) { content() }       // the layer to blur (recorded only while active)
 * CachedBlurEffect(blur, active = overlayUp)              // capture once on open, refresh when the content redraws, free on close
 * if (overlayUp) CachedBlurBackdrop(blur, dim = Color.Black.copy(alpha = 0.28f), alpha = { fade })  // draw it, full screen
 * ```
 * Capture: the source is recorded into a GraphicsLayer only while active; on open we wait 2 frames, then render that
 * layer at 1/[SoftBlur.SCALE] into a small hardware bitmap (API 28+: one tiny GPU draw, the only main-thread step),
 * and copy + blur it on Dispatchers.Default. API 26-27: GraphicsLayer.toImageBitmap, scaled and blurred off main.
 * The bitmap is reused while open, refreshed at most every [REFRESH_MS] when the source redrew, and freed
 * [ComposerMotion.EXIT] ms after close. [lastMainMs] is the main-thread time of the newest capture (logged as
 * "BlazeBlur capture main=… ms blur=… ms").
 */
@Stable
class CachedBlur internal constructor(internal val layer: GraphicsLayer?) {
    /** The blurred, downscaled picture, or null (not captured yet / freed). */
    var image: ImageBitmap? by mutableStateOf(null)
        private set
    /** True when the newest capture failed (draw a solid scrim instead). */
    var failed by mutableStateOf(false)
        private set
    internal var recording by mutableStateOf(false)
    /** Bumped each time the source re-records (read off draw; not state). */
    @Volatile internal var redraws = 0
    @Volatile var lastMainMs: Double = -1.0
        private set
    @Volatile var lastBlurMs: Double = -1.0
        private set
    @Volatile var captures = 0
        private set

    /** Records the source and captures + blurs it once. Call on the main thread (a coroutine there). */
    suspend fun capture(density: Density, scale: Int = SoftBlur.SCALE, radius: Int = SoftBlur.RADIUS): Boolean {
        val l = layer ?: run { failed = true; return false }
        recording = true
        withFrameNanos { }; withFrameNanos { }
        val size = l.size
        if (size.width <= 0 || size.height <= 0) { failed = image == null; return false }
        val (sw, sh) = SoftBlur.smallSize(size.width, size.height, scale)
        // The JVM screenshot renderer has no hardware bitmaps: there the shots hand in the picture (presetForTests).
        val jvm = Build.FINGERPRINT == "robolectric"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val small: Bitmap? = if (!jvm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { smallHardware(l, size, sw, sh, density) }.getOrNull() else null
        var mainNs = SystemClock.elapsedRealtimeNanos() - t0
        val src: Bitmap? = if (jvm) null else small ?: run {
            val t1 = SystemClock.elapsedRealtimeNanos()
            val b = runCatching { l.toImageBitmap().asAndroidBitmap() }.getOrNull()
            mainNs += SystemClock.elapsedRealtimeNanos() - t1
            b
        }
        lastMainMs = mainNs / 1_000_000.0
        if (src == null) { failed = image == null; lastReport = "capture failed main=${"%.1f".format(lastMainMs)} ms"; android.util.Log.i("BlazeBlur", lastReport); return false }
        val t2 = SystemClock.elapsedRealtimeNanos()
        val out = withContext(Dispatchers.Default) { runCatching { blurBitmap(src, sw, sh, radius) }.getOrNull() }
        lastBlurMs = (SystemClock.elapsedRealtimeNanos() - t2) / 1_000_000.0
        if (out == null) { failed = image == null; return false }
        image = out.asImageBitmap(); failed = false; captures++
        lastReport = "capture main=${"%.1f".format(lastMainMs)} ms blur=${"%.1f".format(lastBlurMs)} ms ${sw}x$sh" + if (small != null) " (small hardware picture)" else " (toImageBitmap)"
        android.util.Log.i("BlazeBlur", lastReport)
        return true
    }

    /** Frees the picture and stops recording the source. */
    fun release() { image = null; recording = false; failed = false }

    /** Shots / tests: blur [src] (a full-size picture of the content) now and use it. */
    fun presetForTests(src: Bitmap, scale: Int = SoftBlur.SCALE, radius: Int = SoftBlur.RADIUS) {
        val (sw, sh) = SoftBlur.smallSize(src.width, src.height, scale)
        val t0 = SystemClock.elapsedRealtimeNanos()
        image = blurBitmap(src, sw, sh, radius).asImageBitmap(); failed = false
        lastBlurMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
    }

    companion object {
        const val REFRESH_MS = 3_000L
        /** The newest capture's timing line (also logged under the BlazeBlur tag). */
        @Volatile var lastReport: String = ""
            private set

        /** API 28+: the layer drawn at 1/scale into a small HARDWARE bitmap (one tiny render; no full-size readback). */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
        private fun smallHardware(l: GraphicsLayer, size: IntSize, sw: Int, sh: Int, density: Density): Bitmap? {
            val scope = CanvasDrawScope()
            val pic = object : Picture() {
                override fun beginRecording(width: Int, height: Int): android.graphics.Canvas = android.graphics.Canvas()
                override fun endRecording() {}
                override fun getWidth(): Int = sw
                override fun getHeight(): Int = sh
                override fun requiresHardwareAcceleration(): Boolean = true
                override fun draw(canvas: android.graphics.Canvas) {
                    canvas.scale(sw / size.width.toFloat(), sh / size.height.toFloat())
                    scope.draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), Size(size.width.toFloat(), size.height.toFloat())) {
                        drawLayer(l)
                    }
                }
            }
            return Bitmap.createBitmap(pic)
        }

        /** Copy (hardware → software), downscale to [sw] x [sh] and blur. Background thread. */
        internal fun blurBitmap(src: Bitmap, sw: Int, sh: Int, radius: Int): Bitmap {
            var b = src
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false)
            if (b.width != sw || b.height != sh) b = Bitmap.createScaledBitmap(b, sw, sh, true)
            val px = IntArray(sw * sh)
            b.getPixels(px, 0, sw, 0, 0, sw, sh)
            val out = SoftBlur.blur(px, sw, sh, radius)
            return Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
        }
    }
}

@Composable
fun rememberCachedBlur(): CachedBlur {
    val layer = rememberGraphicsLayer()
    return remember(layer) { CachedBlur(layer) }
}

/** Marks the content to blur. Recorded into the blur's layer only while the blur is active (no cost otherwise). */
fun Modifier.cachedBlurSource(blur: CachedBlur): Modifier = drawWithContent {
    val l = blur.layer
    // Only on a hardware canvas: a software draw of the view tree (a View-to-bitmap screenshot) can't draw a RenderNode.
    if (blur.recording && l != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
        l.record { this@drawWithContent.drawContent() }
        blur.redraws++
        drawLayer(l)
    } else drawContent()
}

/** Captures when [active] turns true, refreshes while the source keeps redrawing (at most every REFRESH_MS), frees on close. */
@Composable
fun CachedBlurEffect(blur: CachedBlur, active: Boolean) {
    val density = LocalDensity.current
    LaunchedEffect(active) {
        if (!active) {
            if (blur.image != null || blur.recording) { delay(MotionTokens.ms(ComposerMotion.EXIT) + 60); blur.release() }
            return@LaunchedEffect
        }
        blur.capture(density)
        var seen = blur.redraws
        while (true) {
            delay(CachedBlur.REFRESH_MS)
            if (blur.redraws != seen) { blur.capture(density); seen = blur.redraws }
        }
    }
    DisposableEffect(blur) { onDispose { blur.release() } }
}

/**
 * Draws the blurred picture full screen (scaled up, bilinear) with [dim] on top, fading in when it arrives.
 * No picture yet: the dim alone; capture failed: [fallback] (a strong solid scrim).
 */
@Composable
fun CachedBlurBackdrop(blur: CachedBlur, dim: Color, fallback: Color, modifier: Modifier = Modifier.fillMaxSize(), alpha: () -> Float = { 1f }) {
    val img = blur.image
    val fade by animateFloatAsState(if (img != null) 1f else 0f, if (MotionTokens.enabled) tween(ComposerMotion.BACKDROP) else snap(), label = "blurIn")
    val failed = blur.failed
    Canvas(modifier) {
        val a = alpha().coerceIn(0f, 1f)
        if (a <= 0f) return@Canvas
        if (img != null) drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt() + 1, size.height.toInt() + 1), alpha = a * fade, filterQuality = FilterQuality.Low)
        if (failed && img == null) drawRect(fallback, alpha = a) else drawRect(dim, alpha = a)
    }
}
