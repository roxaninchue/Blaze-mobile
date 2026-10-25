package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TabStoreStateFileTest {
    @Test fun sleepingTabStateComesBackFromDisk() {
        val dir = Files.createTempDirectory("tabs").toFile()
        TabStore.dir = dir
        val f = TabStore.stateFileFor(3)!!
        val st = mapOf("WEBVIEW_CHROMIUM_STATE" to "b:AAEC", "x" to "s:y")
        TabStore.writeState(f, st)
        assertEquals(st, TabStore.readState(f))
        val snap = TabStore.Snap("https://bbc.com/", "BBC", emptyMap(), f)
        assertEquals(st, snap.fullState())
        val back = TabStore.decode(TabStore.encode(TabStore.Saved(listOf(snap), 0)))
        assertEquals(st, back.tabs.single().state)
        TabStore.clearStateFiles()
        assertTrue(!f.exists())
        dir.deleteRecursively()
    }
}
