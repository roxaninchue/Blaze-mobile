package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** perf-browser: the pure decisions behind the in-app browser speed fixes. */
class BrowserPerfTest {
    private val snap = TabStore.Snap("https://example.com/a", "Example", mapOf("k" to "b:AAAA"))

    @Test fun snapReusedOnlyWhenNothingChanged() {
        // Background tab, no navigation since the last save, same page: the old snapshot (and its saveState) is reused.
        assertSame(snap, BrowserPerf.reuseSnap(snap, dirty = false, onScreen = false, url = "https://example.com/a", title = "Example"))
        // A new title is carried over without re-taking saveState.
        val renamed = BrowserPerf.reuseSnap(snap, dirty = false, onScreen = false, url = "https://example.com/a", title = "Renamed")
        assertEquals("Renamed", renamed?.title); assertEquals(snap.state, renamed?.state); assertEquals(snap.url, renamed?.url)
    }

    @Test fun snapRetakenAfterNavigationOrOnScreen() {
        assertNull(BrowserPerf.reuseSnap(null, dirty = false, onScreen = false, url = "https://example.com/a", title = "Example"))
        assertNull(BrowserPerf.reuseSnap(snap, dirty = true, onScreen = false, url = "https://example.com/a", title = "Example"))
        // The tab on screen moves (scroll position lives in saveState), so it is always taken fresh.
        assertNull(BrowserPerf.reuseSnap(snap, dirty = false, onScreen = true, url = "https://example.com/a", title = "Example"))
        // A different URL than the snapshot (a navigation the flags missed) is never papered over.
        assertNull(BrowserPerf.reuseSnap(snap, dirty = false, onScreen = false, url = "https://example.com/b", title = "Example"))
    }

    @Test fun blankTabsKeepTheirEmptySnap() {
        val blank = TabStore.Snap("", "", emptyMap())
        assertSame(blank, BrowserPerf.reuseSnap(blank, dirty = false, onScreen = false, url = "about:blank", title = "New tab"))
        assertSame(blank, BrowserPerf.reuseSnap(blank, dirty = false, onScreen = false, url = "", title = "New tab"))
    }

    @Test fun fastThumbOnlyWhenTheCopyLooksTheSame() {
        assertTrue(BrowserPerf.fastThumb(34, attached = true, width = 1080, height = 1900, dockBottom = true, overlay = false))
        assertFalse(BrowserPerf.fastThumb(25, attached = true, width = 1080, height = 1900, dockBottom = true, overlay = false))
        assertFalse(BrowserPerf.fastThumb(34, attached = false, width = 1080, height = 1900, dockBottom = true, overlay = false))
        assertFalse(BrowserPerf.fastThumb(34, attached = true, width = 0, height = 1900, dockBottom = true, overlay = false))
        // The toolbar docked at the top or a side would show in the card; something drawn over the page would too.
        assertFalse(BrowserPerf.fastThumb(34, attached = true, width = 1080, height = 1900, dockBottom = false, overlay = false))
        assertFalse(BrowserPerf.fastThumb(34, attached = true, width = 1080, height = 1900, dockBottom = true, overlay = true))
    }

    @Test fun barShowsWhenScrolledUpOrPinned() {
        assertTrue(BrowserPerf.barShown(scrolledShown = true, pinned = false))
        assertTrue(BrowserPerf.barShown(scrolledShown = false, pinned = true))
        assertFalse(BrowserPerf.barShown(scrolledShown = false, pinned = false))
    }
}
