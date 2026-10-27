package com.blaze.assistant.composer

import androidx.compose.ui.graphics.Color
import com.blaze.assistant.ui.BlazeColors

/**
 * 0.48.0: what chat / Spotlight over Home sit on.
 * - Blur available (API 31+, not low-RAM, not hot): the blurred Home shows through a light dim (unchanged since 0.47).
 * - No blur (low-RAM phones): a strong solid scrim of the theme background, so Home's cards don't
 *   fight the chat text, and Blaze's reply bubble / Spotlight panel get an opaque fill.
 */
object ComposerLook {
    /** Share of the theme background in the no-blur scrim (the owner's bar: around 85-90 %). */
    const val SOLID_SCRIM = 0.88f
    const val BLUR_DIM_DARK = 0.45f
    const val BLUR_DIM_LIGHT = 0.35f

    fun scrim(c: BlazeColors, blur: Boolean): Color =
        if (blur) (if (c.dark) Color.Black.copy(alpha = BLUR_DIM_DARK) else Color.White.copy(alpha = BLUR_DIM_LIGHT))
        else c.paper.copy(alpha = SOLID_SCRIM)

    /** Blaze's bubble with no blur: the theme's own opaque Blaze tone, else a neutral opaque surface. */
    fun blazeBubble(c: BlazeColors): Color =
        if (c.bubbleBlaze != Color.Unspecified) c.bubbleBlaze.copy(alpha = 1f)
        else if (c.dark) Color(0xFF262A33) else Color(0xFFFFFFFF)

    /** Spotlight panel base with no blur (opaque; the glass rim/sheen still draws over it). */
    fun panel(c: BlazeColors): Color = if (c.dark) Color(0xFF16181D) else Color(0xFFFAFAFB)
}
