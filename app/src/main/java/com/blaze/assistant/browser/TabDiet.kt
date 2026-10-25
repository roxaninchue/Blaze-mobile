package com.blaze.assistant.browser

/**
 * v0.45.25 RAM diet (pure, TabDietTest): which tabs keep a live WebView, how big a tab-grid picture is, and how
 * often the load feed may touch the UI. Every other tab is a record (url, title, back/forward state on disk,
 * a tiny picture on disk) and gets its WebView back, state restored, the moment it is shown or Blaze uses it.
 */
object TabDiet {
    /** A tab Blaze drove keeps its page this long while the browser is hidden (a secure-input wait, a slow site). */
    const val AGENT_KEEP_MS = 10 * 60_000L
    /** On a memory warning the tab Blaze drove is only kept if it acted this recently. */
    const val AGENT_KEEP_PRESSED_MS = 30_000L
    /** A tab you left is put to sleep this long after you switch away (quick back-and-forth stays instant). */
    const val SLEEP_DELAY_MS = 3_000L
    /** Tab-grid picture width in px (the card crops it top-centre at aspect 0.78). */
    const val THUMB_W = 160
    const val THUMB_ASPECT = 0.78f
    /** Favicons are kept at most this size (pages hand over 192 px icons; the grid shows 16 / 36 dp). */
    const val ICON_PX = 64
    /** The load feed label changes at most this often (it used to change on every resource, 100+ times a load). */
    const val FEED_MS = 250L

    /**
     * The ids that keep a live WebView. [attached] = tabs whose WebView is in a window right now (the browser screen,
     * the Home live preview); [pressed] = Android asked us to trim.
     */
    fun keepLive(
        ids: List<Int>, currentId: Int?, uiVisible: Boolean, attached: Set<Int>,
        agentTabId: Int?, agentIdleMs: Long, fullScreenId: Int? = null, pressed: Boolean = false,
    ): Set<Int> {
        val keep = HashSet<Int>()
        keep += attached.filter { it in ids }
        fullScreenId?.takeIf { it in ids }?.let { keep += it }
        if (currentId != null && currentId in ids && uiVisible) keep += currentId
        val agentWindow = if (pressed) AGENT_KEEP_PRESSED_MS else AGENT_KEEP_MS
        if (agentTabId != null && agentTabId in ids && agentIdleMs in 0 until agentWindow) keep += agentTabId
        return keep
    }

    /** The grid picture's size for a [w] x [h] px page: [THUMB_W] wide, card-shaped, never taller than the page. */
    fun thumbSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val tw = minOf(THUMB_W, w)
        val scale = tw.toFloat() / w
        val th = minOf((tw / THUMB_ASPECT).toInt(), (h * scale).toInt()).coerceAtLeast(1)
        return tw to th
    }

    /** The size a favicon is kept at (aspect kept, never upscaled). */
    fun iconSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= ICON_PX && h <= ICON_PX) return w to h
        val s = ICON_PX.toFloat() / maxOf(w, h)
        return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
    }

    /** Progress in 5 % steps (100 % and the first tick always pass): far fewer recompositions of the address row. */
    fun progressStep(p: Int): Int = when { p >= 100 -> 100; p <= 5 -> 5; else -> (p / 5) * 5 }

    fun feedDue(now: Long, last: Long): Boolean = now - last >= FEED_MS || now < last
}
