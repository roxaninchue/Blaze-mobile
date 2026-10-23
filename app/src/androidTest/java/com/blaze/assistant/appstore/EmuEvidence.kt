package com.blaze.assistant.appstore

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * app/emu-store: EMULATOR evidence for the Store installer. Every step is recorded as pass / fail / not-tested with a
 * detail line, into files/emu/results.json (pulled by .github/workflows/emu-store.yml with run-as). This is emulator
 * evidence only; it never stands in for the real-phone checks.
 */
object EmuEvidence {
    const val PASS = "pass"; const val FAIL = "fail"; const val NOT_TESTED = "not-tested"

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    val dir: File get() = File(ctx.filesDir, "emu").apply { mkdirs() }
    private val file get() = File(dir, "results.json")

    @Synchronized
    fun record(scenario: String, step: String, status: String, detail: String) {
        val root = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            .put("evidence", "EMULATOR")
            .put("note", "Android emulator (x86_64 google_apis) run in GitHub Actions. Not a real phone; real-phone checks stay open.")
            .put("api", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("device", Build.MODEL).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("results", JSONArray())
        root.getJSONArray("results").put(JSONObject().put("scenario", scenario).put("step", step).put("status", status)
            .put("detail", detail.take(600)).put("t", System.currentTimeMillis()))
        file.writeText(root.toString(2))
        android.util.Log.i("EMU-STORE", "[$scenario] $step = $status :: $detail")
    }

    fun shot(name: String) {
        try {
            val f = File(dir, "api${Build.VERSION.SDK_INT}-$name.png")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(f)
        } catch (e: Throwable) { android.util.Log.w("EMU-STORE", "screenshot $name failed", e) }
    }

    /** Runs a shell command as the shell user (UiAutomation) and returns its output. */
    fun sh(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun pmListed(pkg: String): Boolean = sh("pm list packages $pkg").lines().any { it.trim() == "package:$pkg" }
}
