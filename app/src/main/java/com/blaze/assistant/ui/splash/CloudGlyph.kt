package com.blaze.assistant.ui.splash

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.ui.splash.CloudSplashSpec as S

/**
 * Draws one weather variant ([S.glyph]) as an outline: the two-lobe cloud plus its sun, moon, back cloud, rain, snow,
 * bolt or fog. The same drawing is the splash (large, centred) and the weather board's icon (small, white), so the
 * cloud can glide from one to the other by changing only [unitPx] and the origin. Paths are built once per variant in
 * cloud units and scaled with a canvas transform (the stroke is compensated, so it stays [strokePx] wide).
 */
class CloudGlyphPainter(val kind: WeatherKind, val night: Boolean) {
    private val g = S.glyph(kind, night)
    private val cloud = Path().apply {
        val c = S.cloudCubics()
        moveTo(c[0], c[1])
        for (k in 0 until S.SEGMENTS) { val o = k * 8; cubicTo(c[o + 2], c[o + 3], c[o + 4], c[o + 5], c[o + 6], c[o + 7]) }
        close()
    }
    private val back = Path().apply {
        val c = S.cloudCubics(S.BACK_DX + (1f - S.BACK_SCALE) / 2f, S.BACK_DY, S.BACK_SCALE)
        moveTo(c[0], c[1])
        for (k in 0 until S.SEGMENTS) { val o = k * 8; cubicTo(c[o + 2], c[o + 3], c[o + 4], c[o + 5], c[o + 6], c[o + 7]) }
        close()
    }
    /** Crescent: the disc minus a smaller offset disc (drawn as one outline). */
    private val moon = Path().apply {
        if (g.moon && g.discR > 0f) {
            val r = g.discR; val cx = g.discX; val cy = g.discY
            val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) }
            val inner = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx + r * 0.45f, cy - r * 0.32f), r * 0.82f)) }
            op(outer, inner, androidx.compose.ui.graphics.PathOperation.Difference)
        }
    }
    private val bolt = Path().apply {
        if (g.polyline && g.lines.size >= 4) {
            moveTo(g.lines[0], g.lines[1]); var i = 2
            while (i + 1 < g.lines.size) { lineTo(g.lines[i], g.lines[i + 1]); i += 2 }
        }
    }

    /**
     * Draws with the cloud's unit box at ([left], [top]) (the cloud body's top-left), [unitPx] px per cloud unit, in
     * [color], [strokePx] wide. [alpha] fades the whole glyph.
     */
    fun draw(scope: DrawScope, left: Float, top: Float, unitPx: Float, color: Color, strokePx: Float, alpha: Float = 1f) = with(scope) {
        if (unitPx <= 0f || alpha <= 0f) return@with
        val sw = strokePx / unitPx
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({ translate(left, top); scale(unitPx, unitPx, Offset.Zero) }) {
            // 1. what sits behind the cloud: only the parts outside it are drawn
            if (g.cloud && (g.discR > 0f || g.backCloud)) {
                clipPath(cloud, ClipOp.Difference) {
                    if (g.backCloud) drawPath(back, color, alpha = alpha, style = stroke)
                    if (g.discR > 0f) {
                        if (g.moon) drawPath(moon, color, alpha = alpha, style = stroke)
                        else drawCircle(color, g.discR, Offset(g.discX, g.discY), alpha = alpha, style = stroke)
                    }
                }
            } else if (g.discR > 0f) {
                if (g.moon) drawPath(moon, color, alpha = alpha, style = stroke)
                else drawCircle(color, g.discR, Offset(g.discX, g.discY), alpha = alpha, style = stroke)
            }
            var i = 0
            while (i + 3 < g.rays.size) {
                drawLine(color, Offset(g.rays[i], g.rays[i + 1]), Offset(g.rays[i + 2], g.rays[i + 3]), strokeWidth = sw, cap = StrokeCap.Round, alpha = alpha)
                i += 4
            }
            // 2. the cloud itself
            if (g.cloud) drawPath(cloud, color, alpha = alpha, style = stroke)
            // 3. under the cloud
            if (g.polyline) drawPath(bolt, color, alpha = alpha, style = stroke)
            else {
                i = 0
                while (i + 3 < g.lines.size) {
                    drawLine(color, Offset(g.lines[i], g.lines[i + 1]), Offset(g.lines[i + 2], g.lines[i + 3]), strokeWidth = sw, cap = StrokeCap.Round, alpha = alpha)
                    i += 4
                }
            }
            i = 0
            while (i + 2 < g.dots.size) {
                drawCircle(color, g.dots[i + 2] + sw / 2f, Offset(g.dots[i], g.dots[i + 1]), alpha = alpha)
                i += 3
            }
        }
    }

    companion object {
        /** Glyph box in cloud units (x from 0, width incl. the sun; y from [S.BOX_TOP]). */
        const val BOX_W = 1.08f
        val BOX_H = S.BOX_BOTTOM - S.BOX_TOP

        /** Unit size and body top-left that fit the glyph box centred in a w x h icon rect at (x, y). */
        fun fitIcon(x: Float, y: Float, w: Float, h: Float): FloatArray {
            val u = minOf(w / BOX_W, h / (0.53f - S.BOX_TOP + 0.04f))
            val left = x + (w - BOX_W * u) / 2f
            val top = y + (h - (0.53f - S.BOX_TOP) * u) / 2f - S.BOX_TOP * u
            return floatArrayOf(left, top, u)
        }
    }
}
