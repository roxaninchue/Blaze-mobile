package com.blaze.assistant.device

import android.content.Context

/**
 * 0.46.6 cold start (low-RAM release gate, run 2, main thread sampled with the R8 mapping): during BlazeApp.onCreate the main
 * thread spent 207-309 ms verifying Agent.Session's largest method and opened blaze.db itself; with the rest of onCreate
 * that made an ~820 ms stall before the first frame. Lazy start moves both off the main thread for good:
 *  - blaze.db is opened by [com.blaze.assistant.agent.Mind.openLater] (its own background thread, started here first);
 *    nothing on the main thread waits for it (Memory attaches when it is open, an agent turn suspends on Mind.await).
 *  - [classes] are loaded and initialised here (ART verifies a class when it is initialised). They have no static
 *    initialiser with side effects, so only the verification moves. Agent no longer builds a Session in its static
 *    initialiser and Agent.init loads the main chat on a background thread, so the main thread does not touch Session
 *    during onCreate at all.
 * Nothing the app does depends on this thread finishing; a failure is logged and ignored.
 */
object StartWarm {
    @Volatile var thread: Thread? = null
        private set

    /** Classes whose verification is expensive and that start-up needs soon (names survive R8: taken from the class). */
    val classes: List<String> get() = listOf(com.blaze.assistant.agent.Agent.Session::class.java.name)

    fun begin(ctx: Context) {
        if (thread != null) return
        val app = ctx.applicationContext ?: ctx
        thread = Thread({ run(app) }, "blaze-start-warm").apply { isDaemon = true; start() }
    }

    fun run(app: Context?) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (app != null) try { com.blaze.assistant.agent.Mind.openLater(app) } catch (e: Throwable) { CrashLog.quiet("StartWarm: Mind.openLater", e) }
        warmClasses(classes)
        try { android.util.Log.i("BlazeStart", "warm (off main) ${android.os.SystemClock.elapsedRealtime() - t0} ms") } catch (_: Throwable) { Unit }   // no logcat (JVM tests)
    }

    /** Loads and initialises each class (verification included); returns how many were ready. Never throws. */
    fun warmClasses(names: List<String>, loader: ClassLoader? = StartWarm::class.java.classLoader): Int = names.count { n ->
        try { Class.forName(n, true, loader); true } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            try { CrashLog.quiet("StartWarm: $n", e) } catch (_: Throwable) { Unit }   // warm-up never fails start
            false
        }
    }
}
