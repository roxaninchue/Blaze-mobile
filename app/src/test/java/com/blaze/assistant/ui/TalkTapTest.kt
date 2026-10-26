package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.28: tap the big mic while listening to stop and send; hold-to-talk still sends on release. */
class TalkTapTest {
    @Test fun tapWhileListeningStops() {
        assertEquals(TalkTap.Act.STOP, TalkTap.onPress(listening = true))
        assertTrue(TalkTap.stopOnRelease(TalkTap.Act.STOP, 80, released = true))     // a quick tap
        assertTrue(TalkTap.stopOnRelease(TalkTap.Act.STOP, 2_000, released = true))  // a long press too
        assertTrue(TalkTap.stopOnRelease(TalkTap.Act.STOP, 80, released = false))   // even if the gesture was cancelled
    }

    @Test fun holdToTalkUnchanged() {
        assertEquals(TalkTap.Act.START, TalkTap.onPress(listening = false))
        assertTrue(TalkTap.stopOnRelease(TalkTap.Act.START, 1_500, released = true))   // hold, let go: send
        assertFalse(TalkTap.stopOnRelease(TalkTap.Act.START, 120, released = true))   // quick tap: keep listening
        assertFalse(TalkTap.stopOnRelease(TalkTap.Act.START, 1_500, released = false))
    }

    @Test fun labels() {
        assertEquals("Hold to talk", TalkTap.label(busy = false, listening = false))
        assertTrue(TalkTap.label(busy = false, listening = true).contains("tap"))
        assertEquals("Tap to stop", TalkTap.label(busy = true, listening = true))
    }
}
