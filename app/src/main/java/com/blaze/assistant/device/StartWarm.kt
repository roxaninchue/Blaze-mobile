package com.blaze.assistant.device

import android.content.Context

/**
 * 0.46.6 cold start (low-RAM release gate, run 2, main thread sampled with the R8 mapping): during BlazeApp.onCreate the main
 * thread spent 207 ms verifying Agent.Session's largest method (x4.z2.t0) and opened blaze.db itself; with the rest of
 * onCreate that made an ~820 ms stall before the first frame. Both are now done on one background thread started first
 * in onCreate, in parallel with the rest of start:
 *  - [classes] are loaded and initialised here (ART verifies a class when it is initialised). They have no static
 *    initialiser with side effects, so only the verification moves; if the main thread needs one first, it simply waits
 *    for the same class lock it would have taken anyway.
 *  - Mind.init is idempotent under its own lock (HomeBoard already calls it from a background thread), so the main
 *    thread's own Mind.init step later finds blaze.db open, or waits no longer than it would have opening it itself.
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
        warmClasses(classes)
        if (app != null) try { com.blaze.assistant.agent.Mind.init(app) } catch (e: Throwable) { CrashLog.quiet("StartWarm: Mind.init", e) }
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
