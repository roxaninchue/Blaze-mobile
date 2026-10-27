package com.blaze.assistant.device

import com.blaze.assistant.agent.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.6 cold start (low-RAM gate run 2): Agent.Session's verification (207 ms) and opening blaze.db leave the main thread;
 * StartWarm does them on a background thread started first in BlazeApp.onCreate.
 */
class StartWarmTest {
    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }

    @Test fun warmsAgentSessionByItsRealName() {
        assertEquals(listOf(Agent.Session::class.java.name), StartWarm.classes)
        assertEquals(1, StartWarm.warmClasses(StartWarm.classes))
    }

    @Test fun aMissingClassIsSkippedNeverThrown() {
        assertEquals(1, StartWarm.warmClasses(listOf("com.blaze.assistant.NoSuchClass", Agent.Session::class.java.name)))
    }

    @Test fun runWithoutAContextDoesNotThrow() {
        StartWarm.run(null)
        StartGuard.noteStep("test-step", 25)   // logcat line; no Android in JVM tests
    }

    @Test fun startsFirstInOnCreateOffTheMainThread() {
        val body = src("BlazeApp.kt").readText().substringAfter("override fun onCreate()").substringBefore("override fun onTrimMemory")
        val warm = body.indexOf("StartWarm.begin(this)")
        assertTrue("StartWarm.begin missing", warm > 0)
        assertTrue("warm-up must start before the first start step", warm < body.indexOf("StartGuard.step(\"Settings.init\")"))
        assertTrue(warm < body.indexOf("StartGuard.step(\"agent.Mind.openLater\")"))
        val w = src("device/StartWarm.kt").readText()
        assertTrue(w.contains("Thread({ run(app) }, \"blaze-start-warm\")") && w.contains("isDaemon = true"))
        assertTrue(w.contains("Class.forName(n, true, loader)") && w.contains("Mind.openLater(app)"))
        // Mind.init stays idempotent under its lock (openLater, HomeBoard and Mind.await may race)
        val m = src("agent/Mind.kt").readText()
        assertTrue(m.contains("if (db != null) return\n        synchronized(initLock) {\n            if (db != null) return"))
        assertTrue(src("device/Watchdog.kt").readText().contains("noteStep(name, SystemClock.elapsedRealtime() - t0)"))
    }
}
