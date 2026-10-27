package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lazy start (low-RAM release gate: BlazeApp.onCreate was 673-806 ms; Agent.preload 342 ms = verification of the boot
 * Session built in Agent.<clinit>, Mind.init 211 ms = opening blaze.db on the main thread). The rules are pure
 * (LazyStart) and the wiring is checked in the sources, since onCreate itself needs a device.
 */
class LazyStartTest {
    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()
    private fun onCreate() = src("BlazeApp.kt").substringAfter("override fun onCreate()").substringBefore("override fun onTrimMemory")

    @Test fun mainChatOnlyReplacesNothingOrThePlaceholder() {
        assertTrue(LazyStart.showMain(null))
        assertTrue(LazyStart.showMain(Agent.BOOT))
        assertFalse("a chat opened meanwhile (notification, link) is never replaced", LazyStart.showMain("c123"))
        assertFalse(LazyStart.showMain(Mind.MAIN))
    }

    @Test fun onlyBackgroundThreadsWaitForTheStore() {
        assertTrue(LazyStart.mayWaitForMind(onMainThread = false, opening = true))
        assertFalse("the main thread never waits for blaze.db", LazyStart.mayWaitForMind(onMainThread = true, opening = true))
        assertFalse(LazyStart.mayWaitForMind(onMainThread = false, opening = false))
        assertEquals(200L, LazyStart.ON_CREATE_BUDGET_MS)
        assertTrue(LazyStart.MIND_WAIT_MS in 1_000L..30_000L)
    }

    @Test fun agentBuildsNoSessionInItsStaticInitialiser() {
        val a = src("agent/Agent.kt")
        assertFalse("boot Session in Agent.<clinit> is back", Regex("""var cur: Session = Session\(""").containsMatchIn(a))
        assertTrue(a.contains("private var cur: Session\n        get() = curRef ?: bootSession()"))
        assertTrue(a.contains("synchronized(bootLock) { return curRef ?: Session(BOOT).also { curRef = it } }"))
        // Session's own setters compare with the open chat without building the placeholder
        assertFalse(Regex("""this === cur\b(?!Ref)""").containsMatchIn(a))
    }

    @Test fun agentInitLoadsTheMainChatOffTheMainThread() {
        val a = src("agent/Agent.kt")
        val init = a.substringAfter("    fun init(ctx: Context) {").substringBefore("\n    }\n")
        assertFalse("Agent.init must not build the main chat on the main thread", init.contains("show(mainSession())"))
        assertTrue(init.contains("mainReady = io.launch {") && init.contains("withContext(Dispatchers.Main)") && init.contains("LazyStart.showMain(curRef?.id)"))
        // one main chat only: parsed under one lock, and load(MAIN) goes through it
        assertTrue(a.contains("private fun mainSession(): Session = synchronized(mainLock) {"))
        assertTrue(a.contains("if (id == Mind.MAIN) return mainSession()"))
        // an agent turn right after start suspends until blaze.db is open (never blocks)
        assertTrue(a.substringAfter("suspend fun runLoop() {").take(400).contains("Mind.await()"))
    }

    @Test fun onCreateNeverOpensTheStoreOnTheMainThread() {
        val b = onCreate()
        assertFalse("blaze.db is opened on the main thread again", b.contains("Mind.init(this)"))
        assertTrue(b.contains("Mind.openLater(this)"))
        assertTrue(b.contains("Memory.init(this, attachLater = true)"))
        assertTrue(b.indexOf("StartWarm.begin(this)") < b.indexOf("StartGuard.step(\"Settings.init\")"))
        assertTrue(b.contains("StartLater.armFallback()"))
        // work the first frame doesn't need waits for it
        for (k in listOf("proactive.Triggers.init", "proactive.Proactive.start", "ui.Overlay.watchTasks", "update.Updater.checkInBackground", "agent.BackupBrain.init"))
            assertTrue("$k should start after the first frame", b.contains("StartLater.add(\"$k\")"))
        assertTrue(b.contains("LazyStart.ON_CREATE_BUDGET_MS"))
    }

    @Test fun firstFrameIsReportedAndHomeCardsWaitForIt() {
        val m = src("MainActivity.kt")
        assertTrue(m.contains("watchFirstFrame()") && m.contains("StartLater.firstFrameDrawn()") && m.contains("addOnDrawListener"))
        assertTrue(src("ui/HomeBoardUi.kt").contains("if (firstFrameShown()) HomeBoardView("))
        val ff = src("ui/FirstFrame.kt")
        assertTrue(ff.contains("fun AfterFirstFrame(") && ff.contains("StartLater.firstFrame.collectAsState()"))
    }
}
