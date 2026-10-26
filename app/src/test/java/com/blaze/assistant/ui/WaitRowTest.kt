package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.24: the live line sits beside the drop and goes as soon as the reply starts streaming. */
class WaitRowTest {
    @Test fun lineGoesWhenTheReplyStarts() {
        assertTrue(WaitRow.showLine(working = true, streaming = false, replyAtEnd = false))
        assertFalse(WaitRow.showLine(working = true, streaming = true, replyAtEnd = false))
        assertFalse(WaitRow.showLine(working = true, streaming = false, replyAtEnd = true))
        assertFalse(WaitRow.showLine(working = false, streaming = false, replyAtEnd = false))
    }

    @Test fun dropMakesWayForAChipOrSideReplies() {
        assertTrue(WaitRow.showDrop(true, false, hasChip = false, duoLive = false))
        assertFalse(WaitRow.showDrop(true, false, hasChip = true, duoLive = false))
        assertFalse(WaitRow.showDrop(true, false, hasChip = false, duoLive = true))
        assertFalse(WaitRow.showDrop(true, true, hasChip = false, duoLive = false))
    }

    @Test fun lineText() {
        assertEquals("Checking flights", WaitRow.line("Checking flights", "Searching the web", "", simple = false, on = true))
        assertEquals("Searching the web", WaitRow.line(null, "Searching the web", "", simple = false, on = true))
        assertNull(WaitRow.line(null, "", "", simple = false, on = true))
        assertNull(WaitRow.line("Checking flights", "x", "", simple = false, on = false))
        // Never a raw tool name.
        assertNull(WaitRow.line(null, "web_search(query)", "", simple = false, on = true))
        assertEquals("Opening Maps\u2026", WaitRow.line(null, "", "Opening Maps", simple = true, on = true))
    }

    @Test fun chatScreenPutsThePillBesideTheDrop() {
        val src = listOf("src/main/java/com/blaze/assistant/ui/ChatScreen.kt", "app/src/main/java/com/blaze/assistant/ui/ChatScreen.kt")
            .map { java.io.File(it) }.first { it.isFile }.readText()
        val row = src.substringAfter("0.45.24: the live line sits beside the drop").substringBefore("// v0.26.0:")
        assertTrue(row.contains("verticalAlignment = Alignment.CenterVertically"))
        // 0.47.0: the glass work pill follows the drop (and replaces it while it shows a line).
        assertTrue(row.indexOf("ThinkingOrb(") < row.indexOf("WorkPill("))
        assertTrue(row.contains("workNow == null"))
    }
}
