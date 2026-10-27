package com.blaze.assistant.device

import com.blaze.assistant.agent.DeliveryState
import com.blaze.assistant.mediaapps.BrowseState
import com.blaze.assistant.ui.DeliveryView
import com.blaze.assistant.ui.HomeImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.6: the other items of Rocky's 0.46.5 crash report (SM-A125F, low RAM), besides the QuickJS crash (ProviderConfinementTest):
 * the details screen's honest error with Retry, the main-thread accessibility calls, the slow start steps, the memory-trim
 * spam and the picture cache on low-RAM phones.
 */
class CrashReport465Test {
    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()

    @Test fun detailsSayWhatReallyWentWrong() {
        assertEquals("This title is too big for this source", BrowseState.openError("The source used too much memory").first)
        assertEquals("This source is slow right now", BrowseState.openError("The source took longer than 25 s").first)
        assertEquals("This source is slow right now", BrowseState.openError(com.blaze.assistant.movies.ProviderSandbox.BUSY).first)
        assertEquals("A source got stuck", BrowseState.openError(com.blaze.assistant.movies.ProviderSandbox.STUCK).first)
        assertEquals("This source has no details for this title", BrowseState.openError("X returned no details").first)
        assertEquals("Couldn't open this right now", BrowseState.openError("Unable to resolve host").first)
        assertEquals("Couldn't open this right now", BrowseState.openError(null).first)
        val ui = src("mediaapps/MoviesUi.kt")
        assertTrue(ui.contains("BrowseState.openError(i.exceptionOrNull()?.message)"))
        assertTrue(ui.contains("action = \"Retry\", onAction = { attempt++ }"))
        // the source's calls for what the user opened go ahead of browsing
        val js = src("movies/MovieSources.kt")
        assertTrue(js.contains("put(\"provider\", entry.value), interactive = true)"))
        assertTrue(js.contains("put(\"isDownload\", false), interactive = true)"))
        assertTrue(BrowseState.SOURCE_TIMEOUT_MS >= 90_000L)
    }

    @Test fun noMainThreadAccessibilityTreeOrLabelLookups() {
        val a11y = src("phone/BlazeAccessibilityService.kt")
        assertTrue(a11y.contains("if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return frontPkg"))
        assertTrue(a11y.contains("com.blaze.assistant.bank.BankMode.warm(this)"))
        assertTrue(a11y.contains("BankMode.labelOf(this, pkg)"))
        assertFalse(a11y.contains("fun currentPkg(): String = rootInActiveWindow"))
    }

    @Test fun slowStartStepsMovedOffTheMainThread() {
        val r = src("routine/Routines.kt")
        assertTrue(r.contains("try { _all.value = load() } finally { loaded.countDown() }"))
        assertTrue("a change waits for the routines to be read", r.contains("private fun cur(): List<Routine>"))
        val v = src("voice/Voice.kt")
        assertTrue(v.contains("postDelayed({ try { startTts() }"))
    }

    @Test fun memoryTrimsDropPicturesAndLogRarely() {
        val w = src("device/Watchdog.kt")
        assertTrue(w.contains("com.blaze.assistant.ui.HomeImages.trim()"))
        assertTrue(w.contains("if (now - lastLogged > 600_000L)"))
        assertEquals(2 * 1024 * 1024, HomeImages.cacheBytes(true)); assertEquals(6 * 1024 * 1024, HomeImages.cacheBytes(false))
    }

    @Test fun noReadChipUnderYourMessage() {
        assertFalse(DeliveryView.shown(DeliveryState.READ))
        assertTrue(DeliveryView.shown(DeliveryState.SENT)); assertTrue(DeliveryView.shown(DeliveryState.NOT_SENT))
    }
}
