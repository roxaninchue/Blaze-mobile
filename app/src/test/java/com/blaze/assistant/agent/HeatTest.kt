package com.blaze.assistant.agent

import com.blaze.assistant.device.Thermal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.46.4 heat audit (Rocky 19:37): thermal switches and the fixes that stop idle work. */
class HeatTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()
    @After fun cool() = Thermal.set(0, false)

    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()

    @Test fun thermalPolicy() {
        assertEquals(Thermal.COOL, Thermal.policy(0, false))
        assertEquals(Thermal.COOL, Thermal.policy(1, false))           // LIGHT
        assertEquals(Thermal.HOT, Thermal.policy(Thermal.MODERATE, false))
        assertEquals(Thermal.HOT, Thermal.policy(4, false))             // CRITICAL
        assertEquals(Thermal.HOT, Thermal.policy(0, true))              // Battery Saver
        val h = Thermal.HOT
        assertFalse(h.hedge); assertFalse(h.animate); assertFalse(h.blur); assertFalse(h.warmPhoneBrain)
    }

    @Test fun thermalSwitchTurnsOffHedging() {
        Thermal.set(0, false); assertFalse(Thermal.hot.value)
        assertEquals(3_000L, BrainRace.limits(BrainRace.Kind.CHAT).hedgeMs)
        Thermal.set(Thermal.MODERATE, false); assertTrue(Thermal.hot.value)
        assertEquals(0L, BrainRace.limits(BrainRace.Kind.CHAT).hedgeMs)
        assertEquals(8_000L, BrainRace.limits(BrainRace.Kind.CHAT).firstMs)   // deadlines stay
        Thermal.set(0, true); assertEquals(0L, BrainRace.limits(BrainRace.Kind.TASK).hedgeMs)
        Thermal.set(0, false); assertEquals(6_000L, BrainRace.limits(BrainRace.Kind.TASK).hedgeMs)
    }

    @Test fun phoneBrainNeverRestartsWhileIdle() {
        val b = src("agent/BackupBrain.kt")
        // the 10-min probe only looks at a running server (it used to start it: healthCheck())
        assertTrue(b.contains("if (proc?.isAlive == true) runCatching { val ok = healthy()"))
        assertFalse(b.contains("if (!healthy()) healthCheck() else"))
        // setup doesn't start the server, warm-up is skipped when hot, the process really ends
        assertTrue(b.contains("Thermal.now().warmPhoneBrain"))
        assertTrue(b.contains("p.destroyForcibly()"))
        assertTrue(b.contains("BackupHeat.mayTry(autoFails"))
        assertEquals(5 * 60_000L, BackupBrain.IDLE_MS)
        // a turn never starts it unless it's the only brain (signed out)
        assertTrue(b.contains("if (!cold) { prewarm(); throw LlmClient.LlmException(\"backup: warming up\", 503) }"))
    }

    @Test fun phoneControlIdlesOutsideATask() {
        val s = src("phone/BlazeAccessibilityService.kt")
        assertTrue(s.contains("if (!taskActive()) { if (treeWindow >= 0)"))
        assertTrue(s.contains("ScreenHeat.shotWait(lastShotAt, now0)"))
    }

    @Test fun animationsFreezeWhenHotOrIdle() {
        assertTrue(src("ui/Theme.kt").contains("if (paused || hot || !busy) return@LaunchedEffect"))
        assertTrue(src("ui/BlazeGlass.kt").contains("if (hot) return@LaunchedEffect"))
        assertTrue(src("ui/DuoGlow.kt").contains("if (!active || hot) return@LaunchedEffect"))
        assertTrue(src("ui/StreamOrb.kt").contains("if (!running || hot) return@LaunchedEffect"))
        assertTrue(src("ui/GlassKit.kt").contains("Thermal.now().blur"))
    }
}
