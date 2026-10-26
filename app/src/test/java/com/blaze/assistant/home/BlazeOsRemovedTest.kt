package com.blaze.assistant.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.29: BlazeOS ("Blaze Phone", the virtual phone on Home) is gone from the code, the manifest and the build, the
 * one-time cleanup runs at start, and the Blaze Phone browser engine (browser/phone) is kept.
 */
class BlazeOsRemovedTest {
    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: File("app/$rel")
    private val main get() = file("src/main/java/com/blaze/assistant")

    @Test fun codeAndManifestAreGone() {
        assertFalse(File(main, "blazeos").exists())
        assertFalse(file("src/main/assets/blazeos").exists())
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("BlazeOsActivity"))
        assertFalse(manifest.contains(".blazeos."))
        assertFalse(file("build.gradle.kts").readText().contains("chicory"))
        val leftovers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("com.blaze.assistant.blazeos") }.map { it.name }.toList()
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }

    @Test fun cleanupRunsOnceAndOnlyTouchesBlazeOs() {
        assertTrue(BlazeOsCleanup.LEGACY_PREFS.isNotEmpty())
        assertTrue(BlazeOsCleanup.LEGACY_PREFS.all { it.startsWith("bos_") })
        assertTrue(File(main, "BlazeApp.kt").readText().contains("BlazeOsCleanup.runOnce"))
    }

    @Test fun blazePhoneBrowserEngineStays() {
        assertTrue(File(main, "browser/phone/PhoneBrowser.kt").exists())
        assertTrue(File(main, "browser/phone/PhoneBrowserTools.kt").exists())
    }
}
