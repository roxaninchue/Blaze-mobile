package com.blaze.assistant.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * RC2: ONE place for Home's motion durations (ms), shared with the composer (chat bar, chat over Home, Spotlight) so the
 * whole Home moves at one pace. RC1 was too fast on a low-RAM phone; these are measured from the approved video
 * (home-sequence-interests-headlines.mp4, 30 fps, frames counted from the per-frame difference, see the RC2 report) and
 * land at about 1.5-2.5x RC1. Holds where the video only waits (a card sitting in the centre while its text types) are
 * shortened so the cold open stays under ~6 s after the splash; the motions themselves follow the video.
 *
 * Reduce motion (MotionTokens.enabled false) never runs these: the intro is skipped and card focus crossfades
 * ([FOCUS_REDUCED_FADE_MS]).
 */
object HomeMotion {
    // ---- intro (u = ms since the splash ended); video frame numbers at 30 fps in brackets
    /** Splash cloud gliding into the weather icon [72 -> 90: 600 ms]. RC1 520. */
    const val GLIDE_MS = 600L
    /** Board outline drawing itself [84 -> 102]. RC1 360 + 480. */
    const val OUTLINE_AT = 420L
    const val OUTLINE_MS = 600L
    /** The board's two tiles fading in [96 -> 114]. RC1 560 + 320. */
    const val TILES_AT = 700L
    const val TILES_MS = 500L
    /** Each board line types over 12 frames [place 114-126, condition 120-138, sunset 132-150]. RC1 260 per line. */
    const val TYPE_MS = 400L
    const val TYPE_PLACE_AT = 760L
    const val TYPE_COND_AT = 1_100L
    const val TYPE_SUN_AT = 1_450L
    /** Light glass -> settled navy [150 -> 162: 400 ms]. RC1 220. */
    const val BOARD_FILL_AT = 1_750L
    const val FILL_MS = 400L
    /** First centre card (Interests) [chips at 165]. RC1 1160. */
    const val CARDS_AT = 2_000L
    /**
     * Next card's start. The video waits ~2.3 s between cards [Interests 165, Music 245, To-do 313, Next up 402, Quick
     * actions 449]; here the next card emerges while the previous one moves into its cell. RC1 230.
     */
    const val CARD_STAGGER = 900L
    /** A card emerging at the centre [Music 245 -> 258, To-do 313 -> 326: ~430 ms]. RC1 220. */
    const val EMERGE_MS = 440L
    /** Its glide into the grid cell [Music 291 -> 310, To-do 371 -> 389: ~620 ms]. RC1 200 + 320. */
    const val SETTLE_AT = 600L
    const val SETTLE_MS = 620L
    /** It darkens from light glass while landing [~400 ms]. RC1 440. */
    const val CARD_FILL_AT = 900L

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
