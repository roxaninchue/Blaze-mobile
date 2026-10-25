package com.blaze.assistant.browser

/**
 * perf-browser: the small decisions behind the in-app browser's speed fixes, kept pure so they're tested on the JVM
 * (BrowserPerfTest). The Android parts live in [BrowserEngine] and BrowserActivity.
 */
object BrowserPerf {
    /**
     * Saving the tab list: a tab's WebView.saveState (back/forward list, base64) is only re-taken when the tab navigated
     * since the last save, or it is the tab on screen (its scroll position moves). Otherwise the last snapshot is reused,
     * with the current title. Returns null when it has to be taken again.
     */
    fun reuseSnap(cached: TabStore.Snap?, dirty: Boolean, onScreen: Boolean, url: String, title: String): TabStore.Snap? {
        if (cached == null || dirty || onScreen) return null
        val u = url.takeIf { it.isNotBlank() && it != "about:blank" } ?: ""
        if (cached.url != u) return null
        val t = if (u.isEmpty()) "" else title
        return if (cached.title == t) cached else cached.copy(title = t)
    }

    /**
     * The tab-grid thumbnail is copied from the screen (PixelCopy, GPU, async) instead of drawing the WebView into a
     * software canvas (which re-rasterises the whole page on the main thread). Only when the copy would look the same:
     * API 26+, the page is on screen (size: v0.45.25's TabDiet.thumbSize) with a size, nothing is drawn over it (pointer, sign-in bars) and the floating
     * toolbar is docked at the bottom, where the grid card's top-aligned crop cuts it off anyway.
     */
    fun fastThumb(sdk: Int, attached: Boolean, width: Int, height: Int, dockBottom: Boolean, overlay: Boolean): Boolean =
        sdk >= 26 && attached && width > 0 && height > 0 && dockBottom && !overlay

    /**
     * Whether the floating toolbar shows: the last scroll said so ([scrolledShown]: up, or at the top), or it is [pinned]
     * (a panel or the pointer is open, or the tab is blank). Same rule as v0.33.3.
     */
    fun barShown(scrolledShown: Boolean, pinned: Boolean): Boolean = scrolledShown || pinned
}
