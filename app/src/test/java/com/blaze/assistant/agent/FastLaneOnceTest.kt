package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.48.0: the direct-command shortcut runs at most one command per message (no retry loop through cache → rules → model). */
class FastLaneOnceTest {

    @Test fun aFailedCommandIsNeverRunAgain() = runBlocking {
        var calls = 0
        val one = FastLane.OneCommand { _, _ -> calls++; "Couldn't turn Wi-Fi on." }
        // cache, rules and model all pick the same command
        assertNull(one.run("wifi", JSONObject().put("state", "on")))
        assertNull(one.run("wifi", JSONObject().put("state", "on")))
        assertNull(one.run("wifi", JSONObject().put("state", "on")))
        assertEquals(1, calls)
        assertTrue(one.used)
    }

    @Test fun aDifferentCommandAfterARunIsAlsoBlocked() = runBlocking {
        val ran = mutableListOf<String>()
        val one = FastLane.OneCommand { n, _ -> ran += n; "Failed: no answer" }
        one.run("bluetooth", JSONObject()); one.run("wifi", JSONObject())
        assertEquals(listOf("bluetooth"), ran)
    }

    @Test fun aSuccessReturnsTheReplyOnce() = runBlocking {
        var calls = 0
        val one = FastLane.OneCommand { _, _ -> calls++; "Wi-Fi is on." }
        assertEquals("Wi-Fi is on.", one.run("wifi", JSONObject()))
        assertNull(one.run("wifi", JSONObject()))
        assertEquals(1, calls)
    }

    @Test fun aToolTheShortcutDoesNotHandleIsNotARun() = runBlocking {
        val ran = mutableListOf<String>()
        val one = FastLane.OneCommand { n, _ -> ran += n; if (n == "set_timer") null else "Torch on." }
        assertNull(one.run("set_timer", JSONObject()))
        assertFalse(one.used)
        assertEquals("Torch on.", one.run("flashlight", JSONObject()))
        assertEquals(listOf("set_timer", "flashlight"), ran)
    }

    @Test fun anExceptionCountsAsARun() = runBlocking {
        var calls = 0
        val one = FastLane.OneCommand { _, _ -> calls++; error("boom") }
        assertNull(one.run("wifi", JSONObject())); assertNull(one.run("wifi", JSONObject()))
        assertEquals(1, calls)
    }

    @Test fun theSameMessageIsClaimedOnce() {
        FastLane.Once.resetForTests()
        assertTrue(FastLane.Once.claim(42L))
        assertFalse(FastLane.Once.claim(42L))
        assertTrue(FastLane.Once.claim(43L))
    }

    @Test fun agentPassesTheMessageKey() {
        val src = listOf("app/src/main/java/com/blaze/assistant/agent/Agent.kt", "src/main/java/com/blaze/assistant/agent/Agent.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("FastLane.tryHandle(app, shown, offline, key = turnUser)"))
    }
}
