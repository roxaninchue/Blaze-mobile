package com.blaze.assistant.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * v0.45.26: runs [LinuxReady] for real. Right after a Linux is installed (Linux.install / Distros.install), and in the
 * background at start for Linuxes installed before this version. Quiet: no Terminal job, no notification, nothing in
 * the agent's command log; the Terminal reads [status] if they want a line.
 */
object LinuxReadyRunner {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    /** "" or a short line ("Getting Python, git and friends ready…"). */
    val status = MutableStateFlow("")

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun prefs() = app.getSharedPreferences("linux_ready", Context.MODE_PRIVATE)
    private fun cpu() = TermLogic.cpu(Build.SUPPORTED_ABIS.firstOrNull())

    /** Background check of every installed Linux (start-up). Online only; never blocks. */
    fun ensureAllLater() {
        if (!::app.isInitialized) return
        scope.launch {
            kotlinx.coroutines.delay(45_000)   // after the start-up rush
            for (id in runCatching { Distros.installedIds() }.getOrDefault(emptySet())) runCatching { ensure(id) }
        }
    }

    /**
     * Makes [distroId] ready (packages + the Blaze CLI). Returns a short line, or null when there was nothing to do.
     * [force] = right after an install (ignore the retry wait).
     */
    suspend fun ensure(distroId: String, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!::app.isInitialized || !Distros.installed(distroId)) return@withContext null
        lock.withLock {
            val rootfs = if (distroId == "alpine") Linux.rootfs() else Linux.rootfs(distroId)
            val pm = LinuxReady.pmOf(distroId)
            val marker = File(rootfs, LinuxReady.MARKER)
            val lastKey = "last_try_$distroId"
            var line: String? = null
            if (LinuxReady.due(runCatching { marker.readText() }.getOrNull(), pm, prefs().getLong(lastKey, 0), System.currentTimeMillis(), force)) {
                if (!online()) {
                    line = "Offline: the Linux tools will be set up when you're back online."
                } else {
                    status.value = "Getting Python, git and friends ready\u2026"
                    val (ok, out) = runHidden(distroId, LinuxReady.script(distroId), LinuxReady.TIMEOUT_SEC)
                    if (ok && LinuxReady.ok(out)) {
                        marker.writeText(LinuxReady.stamp(pm)); prefs().edit().remove(lastKey).apply()
                        line = "Python, pip, git, curl, wget and nano are ready."
                    } else {
                        prefs().edit().putLong(lastKey, System.currentTimeMillis()).apply()
                        runCatching { com.blaze.assistant.device.CrashLog.write("LinuxReady $distroId failed: " + out.takeLast(600).replace('\n', ' ')) }
                        line = "Couldn't finish setting up the Linux tools; Blaze will try again later."
                    }
                }
            }
            runCatching { installCli(rootfs) }
            status.value = ""
            line
        }
    }

    /** True when the phone has a working internet connection right now. */
    fun online(): Boolean = try {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) { false }

    /** Wi-Fi / unmetered right now (big downloads wait for it). */
    fun unmetered(): Boolean = try {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (_: Throwable) { false }

    /**
     * Puts the Blaze CLI at /usr/local/bin/blaze: the linux-arm64 build from blazeinchue.pages.dev, checked against
     * the published SHA256SUMS (a mismatch installs nothing). 32-bit phones: no build is published, so it's skipped.
     */
    private fun installCli(rootfs: File) {
        CliAuth.sync()   // RC4: the CLI is signed in to the app's account (also where no CLI build is published yet)
        val asset = LinuxReady.cliAsset(cpu()) ?: return
        if (!online()) return
        val sums = httpText("${LinuxReady.CLI_BASE}/SHA256SUMS") ?: return
        val sha = LinuxReady.sumFor(sums, asset) ?: return
        val marker = File(rootfs, LinuxReady.CLI_MARKER)
        val dest = File(rootfs, LinuxReady.CLI_PATH)
        if (dest.exists() && LinuxReady.cliCurrent(runCatching { marker.readText() }.getOrNull(), sha)) return
        val tmp = File(Linux.base(), ".dl-$asset")
        Fetch.download(listOf("${LinuxReady.CLI_BASE}/$asset"), tmp, sha)
        dest.parentFile?.mkdirs()
        tmp.copyTo(dest, overwrite = true); tmp.delete()
        Os.chmod(dest.path, 493)   // 0755
        marker.writeText(sha)
    }

    private fun httpText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        if (c.responseCode == 200) c.inputStream.bufferedReader().readText().take(64_000) else null
    } catch (_: Exception) { null }

    /**
     * Runs [script] inside [distroId] with no stdin and no trace in the Terminal or job list. Returns (exit 0?, output).
     * Shared with the backup brain's own set-up.
     */
    fun runHidden(distroId: String, script: String, timeoutSec: Int): Pair<Boolean, String> = try {
        Terminal.setup()
        val pb = ProcessBuilder(Linux.command(script, distro = distroId)).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.environment().putAll(Terminal.env()); pb.environment().putAll(Linux.env())
        val p = pb.start()
        val out = StringBuilder()
        val t = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { if (out.length > 200_000) out.delete(0, 100_000); out.append(it).append('\n') } } } }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) { p.destroyForcibly(); false to synchronized(out) { out.toString() } + "\n[timed out]" }
        else { t.join(2000); (p.exitValue() == 0) to synchronized(out) { out.toString() } }
    } catch (e: Exception) { false to (e.message ?: "couldn't start") }
}
