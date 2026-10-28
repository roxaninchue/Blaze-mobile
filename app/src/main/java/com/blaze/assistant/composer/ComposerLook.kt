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

    /**
     * 0.48.0 RC4: the chat bar's own fills. Glass let the Home cards behind show through the pill (Quick-action chips read
     * inside the input with the keyboard up), so the pill, chips, avatar, + and the model picker are near-opaque.
     */
    fun solidChip(c: BlazeColors): Color = if (c.dark) Color(0xFF1C2330).copy(alpha = 0.96f) else Color(0xFFF3F5F9).copy(alpha = 0.97f)
    fun pill(c: BlazeColors): Color = if (c.dark) Color(0xFF18202C).copy(alpha = 0.96f) else Color(0xFFFFFFFF).copy(alpha = 0.97f)
    /** The model picker's menu: fully opaque, drawn above everything on Home. */
    fun menu(c: BlazeColors): Color = if (c.dark) Color(0xFF1B2230) else Color(0xFFFFFFFF)
    /** The ground the bar sits on while the keyboard is up (cards behind it never read through). */
    fun dock(c: BlazeColors): Color = if (c.dark) Color(0xFF0D1320) else Color(0xFFF4F6FA)
    /** Spotlight's frosted panel where blur is available (a light veil over the blur) and its opaque base where it isn't. */
    fun spotlight(c: BlazeColors, blur: Boolean): Color =
        if (blur) (if (c.dark) Color(0xFF1A2130).copy(alpha = 0.78f) else Color(0xFFF7F8FA).copy(alpha = 0.82f)) else panel(c)
}
