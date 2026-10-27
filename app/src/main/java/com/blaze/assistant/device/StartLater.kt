package com.blaze.assistant.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lazy start: work that start-up needs soon but the first frame does not. BlazeApp.onCreate queues it with [add]; it runs
 * on the main thread right after Blaze's first frame is drawn ([firstFrameDrawn], called by MainActivity), one job per
 * main-thread message so frames can be drawn in between, each timed like a start step (StartGuard). When Blaze starts
 * without a screen (a notification, an alarm, a service) the queue runs [FALLBACK_MS] after onCreate instead.
 *
 * [firstFrame] / [onFirstFrame] are the generic hook for UI that is heavy to compose (Home cards and the like): show
 * something light in the first frame and the rest from the second (ui/FirstFrame.kt: AfterFirstFrame).
 */
object StartLater {
    /** When the queue runs if no first frame was drawn (process started for a service, receiver or alarm). */
    const val FALLBACK_MS = 2_500L

    class Job(val name: String, val run: () -> Unit)

    private val lock = Any()
    private val queue = ArrayDeque<Job>()
    private val frameHooks = ArrayDeque<() -> Unit>()
    @Volatile var released = false
        private set

    private val _firstFrame = MutableStateFlow(false)
    /** True once Blaze's first frame was drawn (or the fallback fired). */
    val firstFrame: StateFlow<Boolean> = _firstFrame

    /** How a job reaches the main thread (one message each). Tests replace it with a direct call. */
    @Volatile var post: (Runnable) -> Unit = { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) }
    /** How one job is run and timed. Tests replace it. */
    @Volatile var runStep: (String, () -> Unit) -> Unit = { name, block -> StartGuard.step(name) { block() } }

    /** Queues [run] for after the first frame; runs it (posted) at once when the first frame was already drawn. */
    fun add(name: String, run: () -> Unit) {
        val now = synchronized(lock) { if (released) true else { queue.addLast(Job(name, run)); false } }
        if (now) post(Runnable { runStep(name, run) })
    }

    /** Runs [block] on the main thread after the first frame (at once, posted, when it was already drawn). */
    fun onFirstFrame(block: () -> Unit) {
        val now = synchronized(lock) { if (released) true else { frameHooks.addLast(block); false } }
        if (now) post(Runnable { safe("first-frame hook", block) })
    }

    /** MainActivity: the first frame is on screen. Idempotent. */
    fun firstFrameDrawn() { release("first frame") }

    /** BlazeApp: run the queue anyway after [delayMs] when no screen draws a first frame. */
    fun armFallback(delayMs: Long = FALLBACK_MS) {
        try { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release("fallback") }, delayMs) }
        catch (e: Throwable) { CrashLog.quiet("StartLater.armFallback", e) }
    }

    /** Releases the queue once: hooks first (UI), then each job in the order it was added. Returns false when already released. */
    fun release(why: String): Boolean {
        val (jobs, hooks) = synchronized(lock) {
            if (released) return false
            released = true
            val j = queue.toList(); queue.clear(); val h = frameHooks.toList(); frameHooks.clear(); j to h
        }
        _firstFrame.value = true
        try { android.util.Log.i("BlazeStart", "later: ${jobs.size} jobs after $why") } catch (_: Throwable) { Unit }   // no logcat in JVM tests
        for (h in hooks) post(Runnable { safe("first-frame hook", h) })
        for (j in jobs) post(Runnable { runStep(j.name, j.run) })
        return true
    }

    /** Names still waiting (diagnosis and tests). */
    fun pending(): List<String> = synchronized(lock) { queue.map { it.name } }

    private fun safe(where: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { CrashLog.quiet("StartLater: $where", e) }
    }

    /** For tests: back to "nothing released". */
    fun resetForTests() { synchronized(lock) { released = false; queue.clear(); frameHooks.clear() }; _firstFrame.value = false }
}
