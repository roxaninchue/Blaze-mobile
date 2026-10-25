package com.blaze.kiln.runtime

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.blaze.kiln.diag.HarnessStep
import java.io.File

// Guest process (:gN): after the launch swap, proves the guest's own activity really runs and reaches the foreground.
// Polls this process's ActivityThread for the guest activity instance and records, once:
//   GUEST_ACTIVITY ok   when an instance of the guest class is resumed and its window is shown,
//   GUEST_STORAGE       whether its getFilesDir() resolves under Kiln's per-guest data dir.
// Never reads guest content (no views' text, no files), only class names, pid, focus and paths.

internal object ForegroundProbe {
    private const val STEP_MS = 500L
    private const val MAX_TRIES = 40 // 20 s

    fun watch(pkg: String, cls: String, expectedDataDir: File) {
        val main = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        main.postDelayed(object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                val a = try { find(cls) } catch (t: Throwable) { null }
                val resumed = a != null && isResumed(a)
                val shown = a?.window?.decorView?.isShown == true
                if (a != null && resumed && shown) {
                    KilnDiag.step(HarnessStep.GUEST_ACTIVITY, true,
                        "${cls.substringAfterLast('.')} resumed and shown after ${System.currentTimeMillis() - started} ms; " +
                            "package ${a.packageName}; pid ${Process.myPid()}; task ${a.taskId}; window focus ${a.hasWindowFocus()}; " +
                            "class loader ${a.javaClass.classLoader?.javaClass?.simpleName}")
                    val files = try { a.filesDir?.path } catch (t: Throwable) { "error ${t.javaClass.simpleName}" }
                    val want = File(expectedDataDir, "files").path
                    val ok = files == want
                    KilnDiag.step(HarnessStep.GUEST_STORAGE, ok, if (ok) files.orEmpty() else "expected $want, got $files")
                    return
                }
                if (tries >= MAX_TRIES) {
                    KilnDiag.step(HarnessStep.GUEST_ACTIVITY, false,
                        "${cls.substringAfterLast('.')} not in the foreground after ${tries * STEP_MS} ms " +
                            "(instance ${if (a == null) "missing" else "found"}, resumed $resumed, shown $shown)")
                    return
                }
                main.postDelayed(this, STEP_MS)
            }
        }, STEP_MS)
    }

    /** The guest activity instance in this process: ActivityThread.mActivities -> ActivityClientRecord.activity. */
    private fun find(cls: String): Activity? {
        val at = ActivityThreadRef.current() ?: return null
        val map = HiddenApi.get(HiddenApi.field(at.javaClass, "mActivities"), at) as? Map<*, *> ?: return null
        for (rec in map.values.toList()) {
            if (rec == null) continue
            val act = HiddenApi.get(HiddenApi.field(rec.javaClass, "activity"), rec) as? Activity ?: continue
            if (act.javaClass.name == cls) return act
        }
        return null
    }

    /** Activity.mResumed (set by performResume); falls back to the record's paused flag. */
    private fun isResumed(a: Activity): Boolean =
        (HiddenApi.get(HiddenApi.field(Activity::class.java, "mResumed"), a) as? Boolean) ?: !a.isFinishing
}
