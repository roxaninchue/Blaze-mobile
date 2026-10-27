package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * RC2: the cached blur behind something lifted over Home (card focus now; the composer's chat over Home / Spotlight can
 * use the same pieces: see the RC2 report). Home's content is recorded into ONE [GraphicsLayer] while a backdrop is up
 * ([homeBackdropSource]); [HomeBackdropBlur] draws it blurred:
 *  - [Mode.RENDER_EFFECT] (API 31+, [GlassTokens.blurSupported]): the recorded layer under one remembered BlurEffect,
 *    faded in with alpha (the effect object is never rebuilt per frame);
 *  - [Mode.SOFTWARE] (low-RAM phones, API < 31, hot phones): Home is drawn ONCE into a quarter-size bitmap (one frame,
 *    software canvas, so it works without hardware layers), shrunk in halving steps (a cheap box blur) and drawn stretched
 *    back with bilinear filtering; nothing is recomputed per frame.
 *  If the capture fails the backdrop is just the dim drawn by the caller.
 */
@Stable
class HomeBackdrop(val layer: GraphicsLayer, val mode: Mode, val radiusPx: Float) {
    enum class Mode { RENDER_EFFECT, SOFTWARE }
    /** True while the source records into [layer] (only while a backdrop is shown). */
    var recording by mutableStateOf(false)
    /** The software path's blurred snapshot (null until captured). */
    var soft by mutableStateOf<ImageBitmap?>(null)
    /** The software path: draw Home once into a small bitmap on the next frame. */
    var captureRequested by mutableStateOf(false)
    val effect by lazy { BlurEffect(radiusPx, radiusPx, TileMode.Clamp) }
}

@Composable
fun rememberHomeBackdrop(radius: Dp = 22.dp, forceSoftware: Boolean = false): HomeBackdrop {
    val ctx = LocalContext.current
    val layer = rememberGraphicsLayer()
    val px = with(LocalDensity.current) { radius.toPx() }
    return remember(layer, forceSoftware) {
        val re = !forceSoftware && runCatching { GlassTokens.blurSupported(ctx) }.getOrDefault(false)
        HomeBackdrop(layer, if (re) HomeBackdrop.Mode.RENDER_EFFECT else HomeBackdrop.Mode.SOFTWARE, px)
    }
}

/** Put on Home's content. The chain is the same shape whether or not a backdrop is up (no layer is added or removed). */
fun Modifier.homeBackdropSource(b: HomeBackdrop): Modifier = drawWithContent {
    if (b.mode == HomeBackdrop.Mode.SOFTWARE && b.captureRequested) {
        drawContent()
        b.captureRequested = false
        b.soft = runCatching {
            val w = (size.width / SoftBlur.CAPTURE).toInt().coerceAtLeast(1); val h = (size.height / SoftBlur.CAPTURE).toInt().coerceAtLeast(1)
            val img = ImageBitmap(w, h)
            val c = androidx.compose.ui.graphics.Canvas(img)
            c.scale(1f / SoftBlur.CAPTURE, 1f / SoftBlur.CAPTURE)
            draw(this, layoutDirection, c, size) { this@drawWithContent.drawContent() }
            SoftBlur.blur(img, SoftBlur.FACTOR / SoftBlur.CAPTURE)
        }.getOrNull()
    } else if (b.mode == HomeBackdrop.Mode.RENDER_EFFECT && b.recording && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
        b.layer.record { this@drawWithContent.drawContent() }
        drawLayer(b.layer)
    } else drawContent()
}

/** The blurred copy of Home, at [alpha] (read in draw only). Compose it while the backdrop is up. */
@Composable
fun HomeBackdropBlur(b: HomeBackdrop, alpha: () -> Float, modifier: Modifier = Modifier) {
    LaunchedEffect(b) {
        if (b.mode == HomeBackdrop.Mode.SOFTWARE) b.captureRequested = true else b.recording = true
    }
    androidx.compose.runtime.DisposableEffect(b) { onDispose { b.recording = false; b.soft = null; b.captureRequested = false } }
    if (b.mode == HomeBackdrop.Mode.RENDER_EFFECT) {
        // (a software canvas, e.g. a test's screenshot, can't draw hardware layers: then only the caller's dim shows)
        Canvas(modifier.graphicsLayer { renderEffect = b.effect; this.alpha = alpha() }) {
            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) drawLayer(b.layer)
        }
    } else {
        Canvas(modifier.graphicsLayer { this.alpha = alpha() }) {
            val img = b.soft ?: return@Canvas
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
        }
    }
}

/** One-shot software blur: shrink in halving steps (each a 2x2 box filter) to about 1/16, drawn back stretched. Pure Bitmap work. */
object SoftBlur {
    const val FACTOR = 16
    /** Home is first drawn at 1/CAPTURE size (cheap), then halved down to 1/FACTOR. */
    const val CAPTURE = 4

    /** Sizes of each halving step from [w] x [h] down to about 1/[FACTOR] (pure, tested). */
    fun steps(w: Int, h: Int, factor: Int = FACTOR): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var cw = w; var ch = h
        val tw = (w / factor).coerceAtLeast(1); val th = (h / factor).coerceAtLeast(1)
        while (cw > tw || ch > th) {
            cw = (cw / 2).coerceAtLeast(tw); ch = (ch / 2).coerceAtLeast(th)
            out += cw to ch
        }
        return out
    }

    fun blur(src: ImageBitmap, factor: Int = FACTOR): ImageBitmap {
        var b: Bitmap = src.asAndroidBitmap()
        if (b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false)
        for ((w, h) in steps(b.width, b.height, factor)) b = Bitmap.createScaledBitmap(b, w, h, true)
        return b.asImageBitmap()
    }
}
