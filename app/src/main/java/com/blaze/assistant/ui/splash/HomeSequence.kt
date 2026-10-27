package com.blaze.assistant.ui.splash

import com.blaze.assistant.home.HomeGrid

/**
 * 0.48.0 RC3 Home entry sequence, card by card, timed from the approved preview videos (540 x 1200, 30 fps; frame times in
 * brackets are seconds into the video, where the splash hands over at ~2.4 s). Pure (HomeSequenceTest): no Android or
 * Compose types, every value is a function of the card's own clock (ms since that card started).
 *
 *  - Interests [5.5-8.1]: its topic chips pop in one by one at the centre, large; a light glass frame draws itself
 *    around them; the card glides up into its cell (top right) while the chips shrink into place; the title and each
 *    headline type in; the card darkens to navy.
 *  - Music [8.2-10.3] and To-do [10.4-12.8]: a light glass card emerges at the centre, its content appears / types in
 *    (To-do ticks its first done task), it holds, then glides into its cell and darkens.
 *  - Next up [13.0-14.5] and Quick actions [15.0-17.0]: drawn IN PLACE a little below their row: the outline draws
 *    itself, the text types in (Quick actions flashes its second chip, like "Catch up" in the video), the card darkens
 *    and lifts into its row.
 *  - Then the chat bar fades in [17.2].
 *
 * Without tasks the To-do step is left out and the rest move up.
 */
object HomeSequence {
    enum class Mode { CHIPS_FIRST, CENTER, IN_PLACE }

    /** One card's frame at its local time. [move] 0 = at the centre, 1 = in its cell; [lift] 1 = [LIFT_DP] below its row. */
    data class Frame(
        val alpha: Float, val scale: Float, val move: Float, val fill: Float, val content: Float,
        /** The frame's outline drawn so far (path trim, 0..1); 1 = the whole outline. */
        val outline: Float = 1f,
        /** The content's own scale (Interests' chips are large at the centre). */
        val contentScale: Float = 1f,
        val lift: Float = 0f,
        /** The frame's own alpha (the chips show before their frame does). */
        val frameAlpha: Float = 1f,
    )

    val SETTLED = Frame(1f, 1f, 1f, 1f, 1f)
    /** Before a card starts: nothing shows. */
    val HIDDEN = Frame(0f, 1f, 0f, 0f, 0f, outline = 0f, frameAlpha = 0f)

    const val LIFT_DP = 10f

    // ---- Interests (local ms)
    const val CHIP_STAGGER = 170L
    const val CHIP_POP_MS = 240L
    const val CHIPS_SCALE = 1.45f
    const val INT_FRAME_AT = 1_250L
    const val INT_FRAME_MS = 300L
    const val INT_GLIDE_AT = 1_500L
    const val INT_GLIDE_MS = 450L
    const val INT_TITLE_AT = 1_850L
    const val INT_HEADLINE_AT = 1_950L
    const val INT_HEADLINE_STAGGER = 160L
    const val INT_FILL_AT = 2_250L
    const val INT_SPAN = 2_670L

    // ---- Music / To-do (local ms)
    const val EMERGE_MS = 320L
    const val EMERGE_SCALE = 0.86f
    const val MUSIC_ART_AT = 250L
    const val MUSIC_PLAY_AT = 420L
    const val MUSIC_TITLE_AT = 480L
    const val MUSIC_ARTIST_AT = 760L
    const val MUSIC_BAR_AT = 860L
    const val MUSIC_GLIDE_AT = 1_550L
    const val MUSIC_SPAN = 2_260L
    const val TODO_ROW_AT = 380L
    const val TODO_ROW_STAGGER = 260L
    const val TODO_TICK_AT = 1_320L
    const val TODO_TICK_MS = 160L
    const val TODO_GLIDE_AT = 1_820L
    const val TODO_SPAN = 2_600L
    const val GLIDE_MS = 520L
    const val FILL_MS = 300L
    /** To-do emerges a little lower than the centre (fraction of the screen height). */
    const val TODO_CENTER_DROP = 0.12f

    // ---- Next up / Quick actions (local ms)
    const val OUTLINE_MS = 400L
    const val NEXT_TEXT_AT = 380L
    const val NEXT_FILL_AT = 900L
    const val NEXT_LIFT_AT = 1_200L
    const val NEXT_SPAN = 1_900L
    const val QUICK_ROW_AT = 300L
    const val QUICK_ROW_STAGGER = 320L
    const val QUICK_ROW_MS = 520L
    const val QUICK_FLASH_AT = 1_300L
    const val QUICK_FLASH_MS = 520L
    const val QUICK_FILL_AT = 1_500L
    const val QUICK_LIFT_AT = 1_800L
    const val QUICK_SPAN = 2_100L
    const val LIFT_MS = 300L

    // ---- typing
    /** ms per typed character in the cards (the video types ~26 chars/s), clamped per line. */
    const val CHAR_MS = 38L
    const val LINE_MIN_MS = 220L
    const val LINE_MAX_MS = 620L

    /** The chat bar fades in after the last card. */
    const val BAR_MS = 300L

    fun mode(c: HomeGrid.Card): Mode = when (c) {
        HomeGrid.Card.INTERESTS -> Mode.CHIPS_FIRST
        HomeGrid.Card.NEXT_UP, HomeGrid.Card.QUICK -> Mode.IN_PLACE
        else -> Mode.CENTER
    }

    /** How long a card holds the stage before the next one starts. */
    fun span(c: HomeGrid.Card): Long = when (c) {
        HomeGrid.Card.INTERESTS -> INT_SPAN
        HomeGrid.Card.MUSIC -> MUSIC_SPAN
        HomeGrid.Card.TODO -> TODO_SPAN
        HomeGrid.Card.NEXT_UP -> NEXT_SPAN
        HomeGrid.Card.QUICK -> QUICK_SPAN
        HomeGrid.Card.WEATHER -> 0L
    }

    /** The centre-and-in-place cards in the order they come (the weather board comes from the splash cloud). */
    fun order(hasTasks: Boolean): List<HomeGrid.Card> = HomeGrid.order(hasTasks).filter { it != HomeGrid.Card.WEATHER }

    /** Legacy: the order for [n] centre cards (5 = with To-do). */
    fun orderFor(n: Int): List<HomeGrid.Card> = order(hasTasks = n >= 5)

    /** Each card's start, u = ms since the splash ended. */
    fun starts(order: List<HomeGrid.Card>, cardsAt: Long): LongArray {
        val out = LongArray(order.size); var t = cardsAt
        order.forEachIndexed { i, c -> out[i] = t; t += span(c) }
        return out
    }

    /** u when the last card has landed. */
    fun cardsEnd(order: List<HomeGrid.Card>, cardsAt: Long): Long = cardsAt + order.sumOf { span(it) }

    /** u when everything, the chat bar included, is in place. */
    fun end(order: List<HomeGrid.Card>, cardsAt: Long): Long = cardsEnd(order, cardsAt) + BAR_MS

    /** The chat bar's alpha at u (0 during the splash and the cards). */
    fun barAlpha(order: List<HomeGrid.Card>, cardsAt: Long, u: Long): Float = if (u < 0) 0f else p(u, cardsEnd(order, cardsAt), BAR_MS)

    fun frame(c: HomeGrid.Card, local: Long): Frame {
        if (local < 0) return HIDDEN
        if (local >= span(c)) return SETTLED
        return when (mode(c)) {
            Mode.CHIPS_FIRST -> {
                val frameA = p(local, INT_FRAME_AT, INT_FRAME_MS)
                val g = io(p(local, INT_GLIDE_AT, INT_GLIDE_MS))
                Frame(alpha = 1f, scale = 1f, move = g, fill = p(local, INT_FILL_AT, FILL_MS), content = 1f,
                    outline = frameA, contentScale = lerp(CHIPS_SCALE, 1f, g), frameAlpha = frameA)
            }
            Mode.CENTER -> {
                val glideAt = if (c == HomeGrid.Card.TODO) TODO_GLIDE_AT else MUSIC_GLIDE_AT
                val e = out(p(local, 0, EMERGE_MS))
                val g = io(p(local, glideAt, GLIDE_MS))
                Frame(alpha = e, scale = lerp(EMERGE_SCALE, 1f, e), move = g, fill = p(local, glideAt + GLIDE_MS / 2, FILL_MS),
                    content = 1f, outline = 1f, frameAlpha = 1f)
            }
            Mode.IN_PLACE -> {
                val fillAt = if (c == HomeGrid.Card.QUICK) QUICK_FILL_AT else NEXT_FILL_AT
                val liftAt = if (c == HomeGrid.Card.QUICK) QUICK_LIFT_AT else NEXT_LIFT_AT
                val o = p(local, 0, OUTLINE_MS)
                Frame(alpha = 1f, scale = 1f, move = 1f, fill = p(local, fillAt, FILL_MS), content = 1f, outline = o,
                    lift = 1f - io(p(local, liftAt, LIFT_MS)), frameAlpha = o)
            }
        }
    }

    // ---- content timing inside a card (all local ms)

    /** Interests chip [i]: (alpha, scale) of its pop. */
    fun chipPop(i: Int, local: Long): Float = out(p(local, i * CHIP_STAGGER, CHIP_POP_MS))

    /** How long a line of [len] characters takes to type. */
    fun lineMs(len: Int): Long = (len * CHAR_MS).coerceIn(LINE_MIN_MS, LINE_MAX_MS)

    /** Characters of a [len]-long line typed by [local] when it starts at [at]. */
    fun typed(len: Int, at: Long, local: Long): Int = if (local >= at + lineMs(len)) len else (len * p(local, at, lineMs(len)) + 0.0001f).toInt().coerceIn(0, len)

    /** When Interests' headline [i] starts typing. */
    fun headlineAt(i: Int): Long = INT_HEADLINE_AT + i * INT_HEADLINE_STAGGER

    /** When To-do's row [i] starts typing. */
    fun todoRowAt(i: Int): Long = TODO_ROW_AT + i * TODO_ROW_STAGGER

    /** Is To-do's done task [i] shown ticked yet (the first one ticks in the video; later done ones are ticked from the start). */
    fun todoTicked(i: Int, done: Boolean, firstDone: Int, local: Long): Boolean = done && (i != firstDone || local >= TODO_TICK_AT)

    /** Quick actions row [i]: share of its words shown (0..1). */
    fun quickRow(i: Int, local: Long): Float = p(local, QUICK_ROW_AT + i * QUICK_ROW_STAGGER, QUICK_ROW_MS)

    /** Quick actions' flash on its second chip: 0..1..0. */
    fun quickFlash(local: Long): Float { val f = p(local, QUICK_FLASH_AT, QUICK_FLASH_MS); return if (f <= 0f || f >= 1f) 0f else (if (f < 0.25f) f / 0.25f else (1f - f) / 0.75f) }

    /** An element of a card that fades in over [ms] from [at]. */
    fun appear(local: Long, at: Long, ms: Long = 180L): Float = p(local, at, ms)

    // ---- easing
    fun p(u: Long, start: Long, dur: Long): Float = if (dur <= 0) (if (u >= start) 1f else 0f) else ((u - start).toFloat() / dur).coerceIn(0f, 1f)
    fun out(t: Float): Float = CloudSplashSpec.easeOutCubic(t)
    /** The video's ease for moves: slow out, long soft landing (cubic in-out). */
    fun io(t: Float): Float = CloudSplashSpec.easeInOutCubic(t)
    fun lerp(a: Float, b: Float, t: Float): Float = if (t >= 1f) b else a + (b - a) * t
}
