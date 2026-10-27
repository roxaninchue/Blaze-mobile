package com.blaze.assistant.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * RC2: ONE place for Home's motion durations (ms), shared with the composer (chat bar, chat over Home, Spotlight) so the
 * whole Home moves at one pace. 0.48.0 RC3: the intro follows the approved videos 1:1 (holds included): splash ~2.2 s, board
 * ~3 s, then each card in turn, ~17 s in all with To-do (a tap skips straight to the settled Home; it plays once per
 * cold start; reduce motion shows the settled Home at once).
 *
 * Reduce motion (MotionTokens.enabled false) never runs these: the intro is skipped and card focus crossfades
 * ([FOCUS_REDUCED_FADE_MS]).
 */
object HomeMotion {
    // ---- intro (u = ms since the splash ended); 0.48.0 RC3: re-timed 1:1 from the approved videos (seconds into the video in
    // brackets, the splash hands over at ~2.4 s). The cards after the board are timed in ui/splash/HomeSequence.kt.
    /** Splash cloud shrinking and gliding into the weather icon [2.45 -> 3.0]. */
    const val GLIDE_MS = 560L
    /** Board outline drawing itself [3.0 -> 3.5]. */
    const val OUTLINE_AT = 500L
    const val OUTLINE_MS = 520L
    /** The board's two tiles fading in [2.9 -> 3.3]. */
    const val TILES_AT = 450L
    const val TILES_MS = 420L
    /** Each board line types in [place 3.2-3.6, condition + temperature 3.6-4.1, sunset 4.2-4.8]. */
    const val TYPE_MS = 420L
    const val TYPE_PLACE_AT = 800L
    const val TYPE_COND_AT = 1_250L
    const val TYPE_SUN_AT = 1_850L
    /** Light glass -> settled navy [5.0 -> 5.3]. */
    const val BOARD_FILL_AT = 2_550L
    const val FILL_MS = 320L
    /** First card after the board (Interests' chips) [5.5]. */
    const val CARDS_AT = 3_100L

    // ---- transitions on Home
    /** Card focus: the card lifts from its cell to the centre (and back) and Home blurs behind it. */
    const val FOCUS_MS = 520
    /** Its content fades in once it is mostly open. */
    const val FOCUS_CONTENT_MS = 260
    /** Reduce motion: focus is a plain crossfade. */
    const val FOCUS_REDUCED_FADE_MS = 150
    /** Chat over Home / Spotlight appearing and the sheet cross-fades (composer: use instead of MotionTokens.MEDIUM 220). */
    const val SURFACE_MS = 380
    const val SURFACE_FAST_MS = 240

    /** The video's ease: slow out, long soft landing (cards, focus). */
    val Ease: Easing = CubicBezierEasing(0.33f, 0f, 0.15f, 1f)
}
