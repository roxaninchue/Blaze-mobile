package com.blaze.assistant.device

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local crash + slowness log the user can share in one tap. Nothing leaves the phone by itself. */
object CrashLog {
    private lateinit var file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private lateinit var dir: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "blaze_log.txt")
        dir = ctx.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { writeNow("CRASH on ${t.name}: ${android.util.Log.getStackTraceString(e).take(6000)}"); markPending() } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        // 0.46.5: a crash with no Java stack (native: the provider engine) leaves no CRASH line. On the next start, read
        // Android's exit record (API 30+) and the in-flight marker, write what happened, and offer "Send crash report".
        Thread({ try { checkLastExit(ctx) } catch (_: Throwable) { } }, "blaze-crash-check").start()
    }

    // ---------------- 0.46.5: redaction, in-flight markers, last-exit check, "Send crash report" ----------------

    /** Names, ids and hosts of sources the user added: never written to the log as such (see [redact]). */
    private val secrets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Remember a user-added source's name / id / host so every log line shows "a source" instead. Blank and very short values are ignored. */
    fun secret(vararg values: String?) { values.forEach { v -> v?.trim()?.takeIf { it.length >= 3 }?.let { secrets += it } } }

    /** Pure: [text] with each of [names] (case-insensitive, longest first) replaced by "a source". */
    fun redact(text: String, names: Collection<String>): String {
        var out = text
        names.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { n -> out = out.replace(n, "a source", ignoreCase = true) }
        return out
    }

    private val inflight = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val inflightN = java.util.concurrent.atomic.AtomicLong()

    /**
     * Mark a risky native call (a provider in the JS engine) as running: if the process dies inside it, the next start
     * says so in the log. Returns a token for [leave]. Never throws; the label is redacted.
     */
    fun enter(label: String): Long {
        val id = inflightN.incrementAndGet()
        try { inflight[id] = redact(label, secrets).take(160); saveInflight() } catch (_: Throwable) { }
        return id
    }
    fun leave(id: Long) { try { inflight.remove(id); saveInflight() } catch (_: Throwable) { } }
    @Synchronized private fun saveInflight() {
        if (!::dir.isInitialized) return
        val f = File(dir, "blaze_inflight.txt")
        if (inflight.isEmpty()) f.delete() else f.writeText(inflight.values.joinToString("\n"))
    }

    private fun markPending() { try { if (::dir.isInitialized) File(dir, "blaze_crash_pending").writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) { } }
    /** A crash was recorded since the user last chose: show "Send crash report" once. */
    fun pendingReport(): Boolean = try { ::dir.isInitialized && File(dir, "blaze_crash_pending").exists() } catch (_: Throwable) { false }
    fun dismissReport() { try { if (::dir.isInitialized) File(dir, "blaze_crash_pending").delete() } catch (_: Throwable) { } }
    @Volatile var pendingChanged: (() -> Unit)? = null

    /** Pure: the log line for a previous run's exit record, or null when it wasn't a crash / ANR. */
    fun exitLine(reason: Int, description: String?, trace: String?, during: String?, names: Collection<String>): String? {
        val kind = when (reason) { 4 -> "native crash"; 5 -> "crash"; 6 -> "ANR"; else -> return null }   // ApplicationExitInfo.REASON_CRASH_NATIVE / CRASH / ANR
        val sb = StringBuilder("CRASH (previous run, $kind)")
        if (!description.isNullOrBlank()) sb.append(": ").append(description.take(300))
        if (!during.isNullOrBlank()) sb.append(" | while running: ").append(during.take(300))
        if (!trace.isNullOrBlank()) sb.append("\n").append(trace.take(6000))
        return redact(sb.toString(), names)
    }

    private fun head(i: java.io.InputStream, max: Int): String {
        val buf = ByteArray(max); var n = 0
        while (n < max) { val r = i.read(buf, n, max - n); if (r <= 0) break; n += r }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun checkLastExit(ctx: Context) {
        val mark = File(ctx.filesDir, "blaze_inflight.txt")
        val during = if (mark.exists()) mark.readText().lines().filter { it.isNotBlank() }.joinToString("; ") else null
        mark.delete()
        val seenF = File(ctx.filesDir, "blaze_exit_seen")
        val seen = seenF.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        var wrote = false
        if (Build.VERSION.SDK_INT >= 30) {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val list = am?.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).orEmpty().filter { it.timestamp > seen }
            list.maxOfOrNull { it.timestamp }?.let { seenF.writeText(it.toString()) }
            list.sortedBy { it.timestamp }.forEach { x ->
                val trace = if (x.reason == 4 || x.reason == 6) try { x.traceInputStream?.use { head(it, 12_000) } } catch (_: Throwable) { null } else null
                exitLine(x.reason, x.description, trace?.replace("\u0000", "")?.trim(), during, secrets)?.let { writeNow(it); wrote = true }
            }
        }
        if (!wrote && during != null) { writeNow(redact("CRASH (previous run ended while running: $during)", secrets)); wrote = true }
        if (wrote) { markPending(); pendingChanged?.invoke() }
    }

    /**
     * Added to every background scope: a failure inside Blaze's own work is logged and shown as
     * an error in the chat instead of closing the app.
     */
    val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        write("CAUGHT: ${android.util.Log.getStackTraceString(e).take(4000)}")
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { com.blaze.assistant.agent.Agent.reportError(lineFor(e)) } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    /**
     * 0.46.3 (A5; report: "Something went wrong (t8: Blaze Cloud is busy…)": "t8" was the release build's shrunk name for
     * the model-call error class). A model error shows its own plain words; anything else a plain line. Never a class name.
     */
    fun lineFor(e: Throwable, kept: Boolean = false): String = when {
        e is com.blaze.assistant.agent.LlmClient.LlmException && !e.message.isNullOrBlank() -> e.message!!.trim()
        kept -> "Something went wrong, but Blaze kept running. It's in the crash report."
        else -> "Something went wrong. It's in the crash report; try again."
    }

    private val recent = ArrayDeque<Long>()

    /**
     * Keep the app alive through a stray error on the main thread (a tap handler, a UI update):
     * log it, show it in chat, and carry on. If errors pile up (5 in 10 s), let Android restart us.
     */
    fun shieldMainThread() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    val now = System.currentTimeMillis()
                    recent.addLast(now); while (recent.isNotEmpty() && now - recent.first() > 10_000) recent.removeFirst()
                    if (recent.size >= 5 || e is OutOfMemoryError) { writeNow("CAUGHT on main (giving up): ${android.util.Log.getStackTraceString(e).take(4000)}"); throw e }
                    write("CAUGHT on main: ${android.util.Log.getStackTraceString(e).take(4000)}")
                    try { com.blaze.assistant.agent.Agent.reportError(lineFor(e, kept = true)) } catch (_: Throwable) { }
                }
            }
        }
    }

    /**
     * 0.45.16 smoothness: log lines are written on one background thread, never on the caller's (the main thread wrote
     * and sometimes rewrote a 400 KB file on every caught error). A crash still writes at once ([writeNow]).
     */
    private val writer: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    fun write(line: String) {
        if (!::file.isInitialized) return
        val stamped = "${synchronized(fmt) { fmt.format(Date()) }}  $line\n"
        try { writer.execute { append(stamped) } } catch (_: Throwable) { append(stamped) }
    }

    /** Synchronous write, for the crash handler (the process is about to die). */
    fun writeNow(line: String) { if (::file.isInitialized) append("${synchronized(fmt) { fmt.format(Date()) }}  $line\n") }

    @Synchronized private fun append(stamped: String) {
        try {
            if (file.length() > 400_000) file.writeText(file.readText().takeLast(200_000))
            file.appendText(if (secrets.isEmpty()) stamped else redact(stamped, secrets))
        } catch (_: Exception) { }
    }

    /** Log steps that take long so we can see where time goes. */
    fun slow(what: String, ms: Long) { if (ms >= 8000) write("SLOW ${ms} ms: $what") }
    fun error(what: String, e: Throwable?) = write("ERROR $what: ${e?.javaClass?.simpleName}: ${e?.message}")

    private val quietAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * v0.45.0 (#29): for a catch that used to be empty. The failure is still not shown to the user, but it is written
     * to the crash log (once a minute per place), so "it just didn't do anything" can be traced. Cancellation is
     * normal control flow and is never logged. Never throws.
     */
    fun quiet(where: String, e: Throwable?) {
        if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) return
        try {
            val now = System.currentTimeMillis()
            val last = quietAt[where] ?: 0L
            if (now - last < 60_000) return
            quietAt[where] = now
            write("QUIET $where: ${e?.javaClass?.simpleName}: ${e?.message?.take(300)}")
        } catch (_: Throwable) { }
    }
    /** Pure: whether [quiet] would log [e] at all (unit-tested). */
    fun quietLogs(e: Throwable?): Boolean = !(e is kotlinx.coroutines.CancellationException || e is InterruptedException)

    fun read(): String = try { if (::file.isInitialized && file.exists()) file.readText() else "" } catch (_: Exception) { "" }
    fun crashes(): Int = read().lines().count { it.contains("CRASH") }
    fun anrs(): Int = read().lines().count { it.contains("  ANR ") }
    fun clear() { try { file.delete() } catch (_: Exception) { } }

    fun shareIntent(ctx: Context): Intent? {
        if (!::file.isInitialized || !file.exists()) return null
        val out = File(ctx.cacheDir, "blaze-report.txt")
        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        out.writeText("Blaze ${pi?.versionName} (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n\n" + read().takeLast(150_000))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Blaze report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
