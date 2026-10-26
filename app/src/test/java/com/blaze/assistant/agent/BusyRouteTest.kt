package com.blaze.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.3: a message during a running task is answered at once. Routing (stop / steer / side) is tested on the real
 * [BusyRoute]; the lanes are modelled the way Agent wires them (the task on its own job reading its inbox at each step,
 * Halo on a separate coroutine and model call), to check the reply comes before the task ends, steer is read once, stop
 * cancels, and nothing deadlocks or answers twice. The Agent wiring itself is checked in its source.
 */
class BusyRouteTest {
    /** CI run5: missing-Main launch failures from other tests surfaced here; drop only those ([LeakedMainLaunches]). */
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()

    @Test fun routesStopSteerAndSide() {
        listOf("stop", "Stop!", "cancel that", "never mind", "@blaze stop", "stop it please").forEach { assertEquals(it, BusyRoute.Kind.STOP, BusyRoute.route(it)) }
        listOf("also check the price on Jumia", "actually make it 7 pm", "instead use WhatsApp", "no, the other one", "@blaze add milk too", "don't send it yet",
            "and check the reviews").forEach { assertEquals(it, BusyRoute.Kind.STEER, BusyRoute.route(it)) }
        listOf("how's it going?", "what's the capital of France", "thanks", "tell me a joke").forEach { assertEquals(it, BusyRoute.Kind.SIDE, BusyRoute.route(it)) }
        assertEquals(BusyRoute.Kind.STEER, BusyRoute.route("open settings", toolShaped = true))
        assertFalse(BusyRoute.route("stop sign colours in Zambia") == BusyRoute.Kind.STOP)
    }

    @Test fun haloGetsAPlainReadOnlyStatus() {
        val s = BusyRoute.status("compare phone prices", "Reading 3 pages", 4)
        assertTrue(s, s.contains("compare phone prices") && s.contains("Reading 3 pages") && s.contains("step 4"))
        assertTrue(BusyRoute.steered("compare phone prices").startsWith("Got it, I've passed that to Blaze"))
        assertTrue(BusyRoute.STOPPED.startsWith("Stopped."))
    }

    /** The lanes as Agent runs them. */
    private class Lanes(val scope: kotlinx.coroutines.CoroutineScope) {
        val inbox = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val replies = ArrayList<Pair<Long, String>>()
        var task: Job? = null
        val read = ArrayList<String>()
        fun startTask(steps: Int) { task = scope.launch { repeat(steps) { delay(5_000); generateSequence { inbox.poll() }.forEach { read.add(it) } }; replies.add(currentTimeOf() to "BLAZE: task done") } }
        var clock: () -> Long = { 0L }
        fun currentTimeOf() = clock()
        fun send(text: String) {
            if (task?.isActive != true) { replies.add(clock() to "BLAZE: $text"); return }
            when (BusyRoute.route(text)) {
                BusyRoute.Kind.STOP -> { task?.cancel(); replies.add(clock() to "HALO: " + BusyRoute.STOPPED) }
                BusyRoute.Kind.STEER -> { inbox.add(text); replies.add(clock() to "HALO: " + BusyRoute.steered("task")) }
                BusyRoute.Kind.SIDE -> scope.launch { delay(2_000); replies.add(clock() to "HALO: answer to $text") }   // its own model call
            }
        }
    }

    @Test fun aMessageDuringATaskGetsHalosReplyBeforeTheTaskEnds() = runTest {
        val l = Lanes(this); l.clock = { currentTime }
        l.startTask(steps = 6)                 // 30 s of work
        delay(3_000); l.send("how's it going?")
        withTimeout(60_000) { l.task!!.join() }
        val halo = l.replies.first { it.second.startsWith("HALO") }
        val done = l.replies.first { it.second == "BLAZE: task done" }
        assertTrue(halo.first < done.first)
        assertEquals(1, l.replies.count { it.second.startsWith("HALO") })    // no double reply
        assertEquals(1, l.replies.count { it.second == "BLAZE: task done" })
    }

    @Test fun steerIsReadOnceByTheRunningTaskAndStopCancels() = runTest {
        val l = Lanes(this); l.clock = { currentTime }
        l.startTask(steps = 4)
        delay(1_000); l.send("also check the second shop")
        delay(12_000)
        assertEquals(listOf("also check the second shop"), l.read)
        l.send("stop")
        withTimeout(5_000) { l.task!!.join() }
        assertTrue(l.task!!.isCancelled)
        assertFalse(l.replies.any { it.second == "BLAZE: task done" })
        assertEquals(listOf("HALO: " + BusyRoute.steered("task"), "HALO: " + BusyRoute.STOPPED), l.replies.map { it.second })
    }

    @Test fun agentRoutesBusyMessagesBeforeTheQueue() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve("com/blaze/assistant/agent/Agent.kt").readText()
        val send = src.substring(src.indexOf("        fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList(), notificationContext"))
        val route = send.indexOf("BusyRoute.route(")
        val queue = send.indexOf("RapidMessages.route(")
        assertTrue(route in 1 until queue)
        assertTrue(send.contains("BusyRoute.Kind.STEER -> { steer(shown); return }"))
        assertTrue(src.contains("BusyRoute.status(runningTask(), status, currentStep)"))
        assertTrue(src.contains("private fun markSent(id: Long)"))
    }
}
