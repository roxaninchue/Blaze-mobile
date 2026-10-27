package com.blaze.assistant.composer

import androidx.compose.ui.graphics.Color

/**
 * 0.48.0 RC2: Spotlight like macOS Spotlight (pure; SpotlightLookTest). A frosted panel floating at the top of the
 * screen, below the status bar: the blurred Home shows through it under a light tint, a thin light rim, big corners.
 * A large field with inline type-ahead ("Send| Message — Run"), a thin divider, then rows. No category pills.
 */
object SpotlightLook {
    const val CORNER_DP = 26
    /** Gap under the status bar. */
    const val TOP_GAP_DP = 8
    /** The panel never grows past this, nor past the space above the chat bar. */
    const val MAX_HEIGHT_DP = 560
    /** Inline previews (@browser, @terminal...) inside the panel; they grow downward up to the space available. */
    const val PREVIEW_MAX_DP = 440

    /** Panel tint over the blurred Home (translucent: the blur shows through). */
    fun tint(dark: Boolean): Color = if (dark) Color(0xFF1E2129).copy(alpha = 0.62f) else Color(0xFFF7F8FA).copy(alpha = 0.66f)
    /** Thin light rim. */
    fun rim(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.85f)
    /** The top row's translucent pill. */
    fun highlight(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    /** The inline completion's soft highlight. */
    fun completionFill(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.9f)

    /** Inline type-ahead: the rest of the top match after what was typed, and what Enter will do. */
    data class Completion(val full: String, val rest: String, val hint: String)

    /**
     * The completion for [query] given the top row. Matches the row's title, or its last part ("Views > Terminal" →
     * "Terminal"), case-insensitively by prefix. None for an empty query, "!" / "@" modes, or no prefix match.
     */
    fun completion(query: String, top: SpotlightSearch.Row?): Completion? {
        if (top == null || query.isBlank() || query.startsWith("!") || query.startsWith("@")) return null
        val candidates = listOf(top.title, top.title.substringAfterLast(" > "))
        val hit = candidates.firstOrNull { it.length > query.length && it.startsWith(query, ignoreCase = true) } ?: return null
        return Completion(hit, hit.substring(query.length), hint(top.act))
    }

    /** One word for what the top row does (the "— Run" hint). */
    fun hint(a: SpotlightSearch.Act): String = when (a) {
        is SpotlightSearch.Act.OpenView -> "Open"
        is SpotlightSearch.Act.Preview -> "Preview"
        is SpotlightSearch.Act.RunAction, is SpotlightSearch.Act.RunCommand -> "Run"
        is SpotlightSearch.Act.PickModel -> "Use"
        is SpotlightSearch.Act.PlaySong, is SpotlightSearch.Act.PlayVideo -> "Play"
        is SpotlightSearch.Act.Browse, is SpotlightSearch.Act.MusicSearch, is SpotlightSearch.Act.VideoSearch -> "Search"
        is SpotlightSearch.Act.AskBlaze -> "Ask"
        is SpotlightSearch.Act.Complete -> "Fill"
    }

    /** App-style icon colours (top, bottom of the gradient) per kind. */
    fun iconColors(i: SpotlightSearch.Icon): Pair<Color, Color> = when (i) {
        SpotlightSearch.Icon.CHAT -> Color(0xFF4FA3FF) to Color(0xFF1D6BE0)
        SpotlightSearch.Icon.MUSIC -> Color(0xFFFF6B8A) to Color(0xFFE5304F)
        SpotlightSearch.Icon.VIDEO -> Color(0xFFB07BFF) to Color(0xFF7440E0)
        SpotlightSearch.Icon.BROWSER -> Color(0xFF5AD1FF) to Color(0xFF168FD8)
        SpotlightSearch.Icon.TERMINAL -> Color(0xFF4A4F5C) to Color(0xFF1C1F26)
        SpotlightSearch.Icon.SETTINGS -> Color(0xFFA9AEB8) to Color(0xFF6B717D)
        SpotlightSearch.Icon.MODEL -> Color(0xFFFFC65A) to Color(0xFFF08A1C)
        SpotlightSearch.Icon.ACTION -> Color(0xFF6BE3A4) to Color(0xFF1FA866)
        SpotlightSearch.Icon.VIEW -> Color(0xFF7F8CFF) to Color(0xFF4A55D8)
        SpotlightSearch.Icon.SEARCH -> Color(0xFF8E96A3) to Color(0xFF5B6270)
        SpotlightSearch.Icon.PREVIEW -> Color(0xFF55C7C0) to Color(0xFF1E8F88)
    }

    /** WCAG relative luminance and contrast (for the shots' readability check). */
    fun luminance(c: Color): Double {
        fun ch(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    fun contrast(a: Color, b: Color): Double { val x = luminance(a); val y = luminance(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
}

/**
 * 0.48.0 RC2 calm pacing for the composer (the owner: RC1 was far too fast on the A12). About 1.7x the RC1 durations
 * (RC1: 150 / 220 / 320 ms), in line with the approved home-sequence video. Easing names are [com.blaze.assistant.ui.MotionTokens]'s
 * (Standard / Decelerate / Accelerate). With "Remove animations" every spec built from these snaps (callers check
 * MotionTokens.enabled).
 */
object ComposerMotion {
    /** Spotlight / chat coming in (RC1 220). */
    const val ENTER = 380
    /** Spotlight / chat going out (RC1 150). */
    const val EXIT = 260
    /** The blurred backdrop fade (RC1 220). */
    const val BACKDROP = 420
    /** Bar parts: chip slide, send arrow / orb swap, + turn (RC1 150-220). */
    const val BAR = 340
    /** The user's line lifting out of the pill (RC1 320). */
    const val LIFT = 560
    /** The orb's breathing ring period while Blaze works (RC1 overlay ring 1400). */
    const val ORB_BREATHE = 2200
}
