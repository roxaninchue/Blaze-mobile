package com.blaze.assistant.agent

import android.content.Context
import android.os.Build
import android.system.Os
import com.blaze.assistant.terminal.Fetch
import com.blaze.assistant.terminal.Linux
import com.blaze.assistant.terminal.LinuxReadyRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.45.26: Blaze's on-device backup brain. A minimal open-source OpenCode server (anomalyco/opencode, linux-arm64 musl
 * build, pinned + SHA-256 checked) runs headless inside Blaze's Alpine Linux as `opencode serve` on 127.0.0.1, behind
 * HTTP basic auth with a random per-install password (OPENCODE_SERVER_PASSWORD). It answers only when Blaze Cloud
 * failed or was too slow ([BackupRoute]); its replies stream into the normal chat as Blaze ([BackupBridge]), and the
 * person never sees OpenCode, a terminal or a job for it.
 *
 * Lifecycle: installed in the background (Wi-Fi only by default: ~78 MB download, ~218 MB on the phone) once Alpine is
 * there; started lazily (the first sign Blaze Cloud is struggling warms it, see [prewarm]) and stopped again after
 * [IDLE_MS] without use, so it costs no memory while Blaze Cloud is healthy. 64-bit phones only (OpenCode publishes no
 * 32-bit ARM build); elsewhere [ready] is false and the gateway's own reserve is the fallback.
 */
object BackupBrain {
    const val VERSION = "1.19.4"
    /** GitHub release asset (musl, for Alpine). SHA-256 and size from the release's own digest, re-checked 26 Oct 2026. */
    const val URL = "https://github.com/anomalyco/opencode/releases/download/v$VERSION/opencode-linux-arm64-musl.tar.gz"
    const val SHA256 = "aa32a91f62cfddfa4615ba4deffc2b13696e77cbee83551824e3f16e6770b37e"
    const val DOWNLOAD_BYTES = 78_269_653L
    /** Inside Alpine (no leading slash for File(rootfs, …)). */
    const val DIR = "opt/blaze-backup"
    /** Public lists of OpenCode's hosted models (no key needed), tried in order. */
    val PUBLIC_LISTS = listOf("https://opencode.ai/zen/v1/models", "https://opencode.ai/inference/v1/models")
    const val IDLE_MS = 5 * 60_000L   // 0.46.4 heat audit: 15 → 5 min (a running Bun server under proot costs heat)
    const val START_WAIT_MS = 20_000L   // 0.46.3 (A4): start + first model fit in about 45 s
    // 0.46.3: ~20 s first-token cap per model. 0.46.5: 45 s: the brain race (HedgeRace) already starts the next brain in
    // parallel at its deadline, so a slow but working on-phone model (25 s to its first word) is no longer cut here.
    const val FIRST_CHUNK_MS = 45_000L
    const val GAP_MS = 30_000L
    const val TOTAL_MS = 100_000L
    const val MAX_MODELS = 4
    private const val MODELS_TTL_MS = 6 * 3600_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var proc: Process? = null
    @Volatile private var port = 0
    @Volatile private var lastUse = 0L
    @Volatile private var models: List<BackupModels.Model> = emptyList()
    @Volatile private var modelsAt = 0L
    private val cooling = ConcurrentHashMap<String, Long>()
    /** 0.46.6: until when EVERY on-phone model is out of free usage (0 = at least one can answer). */
    @Volatile private var freeUntil = 0L
    @Volatile private var fetching = false
    private val log = StringBuilder()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // Install quietly once Alpine exists (and refresh the everyday tools of every installed Linux).
        // 0.46.3: ready by default: a quiet background setup (Alpine first when it's missing, on Wi-Fi), then a health check.
        scope.launch { delay(8_000); runCatching { autoSetup() } }
        // health probe every ~10 min while installed (keeps the status in Settings › Brains live)
        // 0.46.4 heat audit: the probe only looks at a server that is already running. It used to call healthCheck(), which
        // STARTS the server, every 10 min, so the Bun server under proot never stayed stopped (idle stop after 15 min,
        // restarted by the probe 10 min later): a phone kept warm all day by a brain nobody was using.
        scope.launch { while (true) { delay(BrainOrder.PROBE_EVERY_MS); if (proc?.isAlive == true) runCatching { val ok = healthy(); healthOk = ok; healthAt = System.currentTimeMillis(); if (!ok) stopNow() } } }
        LinuxReadyRunner.ensureAllLater()
        // v0.45.27: and as soon as the phone gets onto Wi-Fi (an unmetered, working connection), not only at start.
        watchWifi()
    }

    // ---------------- v0.45.27: the one-time download, automatic on Wi-Fi ----------------

    /** What [BackupNudge] needs to know about the phone right now. */
    fun phone(): BackupNudge.Phone = try {
        val sup = supported()
        BackupNudge.Phone(
            supported = sup, enabled = enabled, linuxInstalled = sup && Linux.installed(), backupInstalled = installed(),
            online = LinuxReadyRunner.online(), unmetered = LinuxReadyRunner.unmetered(),
            mobileDataOk = Settings.bool("backup_brain_mobile_data", false))
    } catch (_: Throwable) { BackupNudge.Phone(false, false, false, false, false, false) }

    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastAutoTry = 0L
    private val wifiCheck = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Starts the one-time download in the background (once at a time); true when it was started or is running.
     * v0.45.28: [force] = Settings › Backup brain › Download now (mobile data allowed after the person confirmed); [done]
     * gets the result line.
     */
    fun startDownload(force: Boolean = false, done: ((String) -> Unit)? = null): Boolean {
        if (!installing.compareAndSet(false, true)) return true
        lastAutoTry = System.currentTimeMillis()
        scope.launch {
            try { val r = runCatching { install(force) }.getOrElse { it.message.orEmpty() }; note("download: $r")
                autoFails = if (r == "Installed." || r == "Already installed.") 0 else if (force) autoFails else autoFails + 1; done?.invoke(r) }
            finally { installing.set(false) }
        }
        return true
    }

    /** v0.45.28: the download is running right now (from any path). */
    fun downloading(): Boolean = installing.get() || fetching

    /** v0.45.28: until when the phone's free allowance is used up (0 = it isn't). */
    fun restingUntil(): Long = freeUntil

    /** v0.45.28 (Settings): the status as [BackupStatus] sees it. */
    fun status(now: Long = System.currentTimeMillis()): BackupStatus.State = BackupStatus.state(phone(), downloading(), freeUntil, now)

    /** Registers a callback for unmetered networks while the backup brain still has to be downloaded. */
    private fun watchWifi() {
        try {
            if (!supported() || installed()) return
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED).build()
            cm.registerNetworkCallback(req, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    // Fires again on every signal change: one check at a time, off the callback thread.
                    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    if (installing.get() || !wifiCheck.compareAndSet(false, true)) return
                    val cb = this
                    scope.launch {
                        try {
                            delay(5_000)   // let the phone make the Wi-Fi its default network first (install checks it)
                            if (installed()) { runCatching { cm.unregisterNetworkCallback(cb) }; return@launch }
                            if (BackupNudge.autoInstall(phone(), System.currentTimeMillis(), lastAutoTry) && BackupHeat.mayTry(autoFails, lastAutoTry, System.currentTimeMillis())) startDownload()
                        } finally { wifiCheck.set(false) }
                    }
                }
            })
        } catch (e: Throwable) { note("wifi watch failed: ${e.message}") }
    }

    /**
     * v0.45.27: the chat line for a request that failed for good ([BackupNudge]), or null for the normal error.
     * On Wi-Fi with Linux installed the download is started here.
     */
    fun nudgeFor(code: Int, message: String): String? = try {
        val pick = BackupNudge.pick(code, message, phone())
        if (pick == BackupNudge.Pick.DOWNLOADING) startDownload()
        BackupNudge.line(pick, DOWNLOAD_BYTES)
    } catch (_: Throwable) { null }

    /** Settings: on by default; "backup_brain_mobile_data" lets the one-time download use mobile data. */
    private val enabled get() = Settings.bool("backup_brain", true)

    fun supported(): Boolean = ::app.isInitialized && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && Linux.supported()
    private fun dir() = File(Linux.rootfs(), DIR)
    private fun bin() = File(dir(), "opencode")
    fun installed(): Boolean = supported() && Linux.installed() && bin().exists() && File(dir(), ".version").let { it.exists() && it.readText().trim() == VERSION }

    /** Can the backup step run for a request (it may still have to start)? */
    fun ready(): Boolean = enabled && installed() && System.currentTimeMillis() >= freeUntil

    /**
     * 0.46.4: can it answer right now, without waiting? Installed and on, not resting, not downloading, the server process
     * running with its last health check good, and its last call didn't fail in the past [SICK_MS]. Never blocks (no lock,
     * no network): a turn only asks a warm brain; a cold one is started in the background ([prewarm]).
     */
    fun warm(now: Long = System.currentTimeMillis()): Boolean = ready() && !downloading() && proc?.isAlive == true && port > 0 &&
        healthOk == true && !lock.isLocked && BrainLedger.app.rows[BackupRoute.Step.BACKUP].let { r -> r == null || r.lastOk >= r.lastFail || now - r.lastFail > SICK_MS }
    const val SICK_MS = 2 * 60_000L

    @Volatile private var lastError = ""

    /** 0.46.3 (A4): one line on whether the on-device brain could run (crash report, final failures). */
    fun diag(): String = try {
        "backup: supported=${supported()} linux=${Linux.installed()} installed=${installed()} enabled=$enabled ready=${ready()} " +
            "resting=${if (freeUntil > System.currentTimeMillis()) java.util.Date(freeUntil).toString() else "no"} health=${healthOk ?: "unchecked"} last=\"${lastError.take(120)}\""
    } catch (e: Throwable) { "backup: diag failed ${e.message}" }

    /** 0.46.3: the one-time question before the setup uses mobile data (the UI shows it; the answer is saved). */
    val askMobile = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun answerMobile(allow: Boolean) {
        Settings.setBool("backup_mobile_asked", true)
        if (allow) Settings.setBool("backup_brain_mobile_data", true)
        askMobile.value = false
        if (allow) scope.launch { runCatching { autoSetup() } }
    }

    /** 0.46.3: the last startup health check (null = not checked yet) and when, for Settings › Backup brain. */
    @Volatile var healthOk: Boolean? = null; private set
    @Volatile var healthAt: Long = 0L; private set

    /** 0.46.3: Linux (Alpine) when missing and the phone is on Wi-Fi (or mobile data is allowed), then the backup, then a health check. */
    suspend fun autoSetup() {
        if (!enabled || !supported()) return
        val dataOk = LinuxReadyRunner.unmetered() || Settings.bool("backup_brain_mobile_data", false)
        // primary brain: on mobile data, ask once (until then, and on a 32-bit phone, Blaze starts at Nous)
        if (!dataOk && LinuxReadyRunner.online() && !installed() && !Settings.bool("backup_mobile_asked", false)) askMobile.value = true
        if (!Linux.installed() && LinuxReadyRunner.online() && dataOk) runCatching { Linux.install() }.onFailure { note("auto linux: ${it.message}") }
        if (Linux.installed() && !installed()) {
            // 0.46.4: never a retry loop: a failed setup backs off (10 min, 20, 40… up to 6 h) and stops after 3 tries a day
            if (!BackupHeat.mayTry(autoFails, lastAutoTry, System.currentTimeMillis())) { note("auto install: backing off"); return }
            lastAutoTry = System.currentTimeMillis()
            val r = runCatching { install(dataOk) }.getOrElse { it.message.orEmpty() }
            note("auto install: $r")
            autoFails = if (r == "Installed." || r == "Already installed.") 0 else autoFails + 1
        }
        // 0.46.4: setup no longer starts the server (it starts when a turn finds it cold, and stops again when idle)
    }
    @Volatile private var autoFails = 0

    /** Starts the backup server and asks its health endpoint. */
    suspend fun healthCheck(): Boolean {
        if (!installed()) { healthOk = null; return false }
        val ok = runCatching { start() && healthy() }.getOrDefault(false)
        healthOk = ok; healthAt = System.currentTimeMillis()
        note("health: " + if (ok) "ok" else "not answering")
        return ok
    }

    // ---------------- install ----------------

    /** Downloads, checks and unpacks the pinned build into Alpine, plus the two libraries it needs. */
    suspend fun install(force: Boolean): String = withContext(Dispatchers.IO) {
        if (!supported()) return@withContext "This phone can't run the on-phone brain (it needs a 64-bit phone)."
        if (!enabled && !force) return@withContext "The on-phone brain is off."
        if (!Linux.installed()) return@withContext "Linux isn't installed yet."
        if (installed()) return@withContext "Already installed."
        lock.withLock {
            if (installed()) return@withLock "Already installed."
            if (!LinuxReadyRunner.online()) return@withLock "Offline."
            if (!force && !LinuxReadyRunner.unmetered() && !Settings.bool("backup_brain_mobile_data", false)) return@withLock "Waiting for Wi-Fi."
            val tgz = File(Linux.base(), ".dl-backup-$VERSION.tar.gz")
            fetching = true
            try {
                Fetch.download(listOf(URL), tgz, SHA256)
                dir().deleteRecursively(); dir().mkdirs()
                Linux.untarStream(java.util.zip.GZIPInputStream(java.io.BufferedInputStream(tgz.inputStream(), 256 * 1024)), dir(), 0) { }
                Os.chmod(bin().path, 493)
                tgz.delete()
                // The everyday-tools step first (it holds apk's lock while it runs), then the two libraries
                // Bun's musl build links: libstdc++ (and libgcc_s).
                runCatching { LinuxReadyRunner.ensure("alpine") }
                // Bun's musl build links libstdc++ (and libgcc_s).
                val (ok, out) = LinuxReadyRunner.runHidden("alpine", "set -e\n${com.blaze.assistant.terminal.LinuxPm.APK_TIMEOUT_PROBE}\napk \$T add libstdc++ libgcc\necho BK_OK", 600)
                if (!ok || !out.contains("BK_OK")) return@withLock "Couldn't finish setting up the on-phone brain: ${out.takeLast(200)}"
                File(dir(), ".version").writeText(VERSION)
                "Installed."
            } catch (e: Exception) {
                note("install failed: ${e.message}")
                "Install failed: ${Fetch.reason(e)}"
            } finally { fetching = false }
        }
    }

    // ---------------- the server ----------------

    /** Random per install, kept in its own private prefs file (never in Settings, which can sync to the account). */
    private fun password(): String {
        val p = app.getSharedPreferences("backup_brain", Context.MODE_PRIVATE)
        p.getString("pw", null)?.takeIf { it.length >= 24 }?.let { return it }
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..32).map { abc[r.nextInt(abc.length)] }.joinToString("").also { p.edit().putString("pw", it).apply() }
    }

    private fun base() = "http://127.0.0.1:$port"
    private fun auth() = "Basic " + android.util.Base64.encodeToString("opencode:${password()}".toByteArray(), android.util.Base64.NO_WRAP)

    private fun open(path: String, method: String = "GET", readMs: Int = 10_000): HttpURLConnection =
        (URL(base() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 3_000; readTimeout = readMs
            setRequestProperty("Authorization", auth()); setRequestProperty("Accept", "application/json")
        }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, readMs: Int = 10_000): Pair<Int, String> {
        val c = open(path, method, readMs)
        try {
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    fun healthy(): Boolean = port > 0 && proc?.isAlive == true && try { request("/global/health", readMs = 2_000).let { (c, t) -> c == 200 && t.contains("true") } } catch (_: Exception) { false }

    /** Starts the server if it isn't running; true once it answers. */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext false
        lock.withLock {
            if (healthy()) return@withLock true
            stopNow()
            port = ServerSocket(0).use { it.localPort }   // proot shares the app's network: a free local port
            val cfg = File(dir(), "opencode.json")
            cfg.writeText(BackupBridge.config(models.firstOrNull()?.id).toString(2))
            File(dir(), ".pw").apply { writeText(password()); setReadable(true, true) }
            File(Linux.rootfs(), "root/.blaze-backup").mkdirs()   // an empty working folder: no project files, no AGENTS.md
            val script = "cd /root/.blaze-backup && export OPENCODE_CONFIG=/$DIR/opencode.json OPENCODE_SERVER_PASSWORD=\"\$(cat /$DIR/.pw)\" " +
                "OPENCODE_DISABLE_AUTOUPDATE=1 OPENCODE_DISABLE_LSP_DOWNLOAD=1 OPENCODE_DISABLE_DEFAULT_PLUGINS=1 OPENCODE_DISABLE_CLAUDE_CODE=1 " +
                "XDG_DATA_HOME=/$DIR/data XDG_CACHE_HOME=/$DIR/cache XDG_STATE_HOME=/$DIR/state && " +
                "exec /$DIR/opencode serve --hostname 127.0.0.1 --port $port"
            try {
                com.blaze.assistant.terminal.Terminal.setup()
                val pb = ProcessBuilder(Linux.command(script, distro = "alpine")).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                pb.environment().putAll(com.blaze.assistant.terminal.Terminal.env()); pb.environment().putAll(Linux.env())
                val p = pb.start(); proc = p
                Thread { runCatching { p.inputStream.bufferedReader().forEachLine { note(it) } } }.apply { isDaemon = true; name = "blaze-backup-log" }.start()
            } catch (e: Exception) { note("start failed: ${e.message}"); return@withLock false }
            val until = System.currentTimeMillis() + START_WAIT_MS
            while (System.currentTimeMillis() < until) {
                if (proc?.isAlive != true) { note("server exited"); return@withLock false }
                if (healthy()) { lastUse = System.currentTimeMillis(); healthOk = true; healthAt = lastUse; watchIdle(); return@withLock true }
                delay(300)
            }
            note("server didn't answer in ${START_WAIT_MS / 1000}s"); stopNow(); false
        }
    }

    /** Blaze Cloud is struggling (slow first chunk or a failure): start the backup now so a switch is smooth. */
    // 0.46.4: the check runs off the caller's thread (healthy() is a local HTTP call); a turn never waits for it
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    fun prewarm() { if (!ready() || downloading() || !com.blaze.assistant.device.Thermal.now().warmPhoneBrain || !warming.compareAndSet(false, true)) return
        scope.launch { try { runCatching { if (!healthy()) start(); refreshModels(false) } } finally { warming.set(false) } } }

    /** 0.46.4: proot and the server under it really end (destroy, then forcibly after 2 s if it is still alive). */
    private fun stopNow() {
        val p = proc; proc = null; port = 0
        if (p != null) try { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Throwable) { }
    }

    fun stop() { stopNow() }

    @Volatile private var idleWatch = false
    private fun watchIdle() {
        if (idleWatch) return
        idleWatch = true
        scope.launch {
            try { while (proc?.isAlive == true) { delay(60_000); if (System.currentTimeMillis() - lastUse > IDLE_MS) { stopNow(); break } } }
            finally { idleWatch = false }
        }
    }

    private fun note(line: String) { synchronized(log) { if (log.length > 8_000) log.delete(0, 4_000); log.append(line.take(400)).append('\n') } }
    /** The last lines of the server's output (diagnostics only). */
    fun lastLog(): String = synchronized(log) { log.toString().takeLast(2_000) }

    // ---------------- models ----------------

    private fun publicList(): List<BackupModels.Model>? {
        for (u in PUBLIC_LISTS) try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            if (c.responseCode == 200) return BackupModels.fromPublic(JSONObject(c.inputStream.bufferedReader().readText()))
        } catch (_: Exception) { }
        return null
    }

    /** The free, allowed models, strongest first: the server's data joined with the live public list. */
    suspend fun refreshModels(force: Boolean): List<BackupModels.Model> = withContext(Dispatchers.IO) {
        if (!force && models.isNotEmpty() && System.currentTimeMillis() - modelsAt < MODELS_TTL_MS) return@withContext models
        val server = try { request("/provider").let { (c, t) -> if (c == 200) BackupModels.fromServer(JSONObject(t)) else emptyList() } }
            catch (_: Exception) { emptyList() }
        val list = BackupModels.usable(BackupModels.join(server, publicList()))
        if (list.isNotEmpty()) { models = list; modelsAt = System.currentTimeMillis() }
        models
    }

    // ---------------- one model call ----------------

    /**
     * One Blaze model call answered by the backup brain: free models strongest first, the next one on an error, a rate
     * limit, an empty reply or a stall. Returns the OpenAI-shaped assistant message (tool calls included), or throws
     * [LlmClient.LlmException] when no model answered.
     */
    suspend fun complete(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?, onThink: ((String) -> Unit)?,
                         coding: Boolean = false, googleData: Boolean = false, chat: Boolean = false, cold: Boolean = false): JSONObject {
        if (!BackupBridge.canServe(messages)) throw LlmClient.LlmException("backup: pictures aren't supported", 415)
        // 0.46.4: never wait on setup or start in a turn (the start lock is shared with setup and the health check). Only the
        // signed-out phone, whose only brain this is, may start it here ([cold]).
        if (!warm()) {
            if (!cold) { prewarm(); throw LlmClient.LlmException("backup: warming up", 503) }
            if (!start()) throw LlmClient.LlmException("backup: not running", 503)
        }
        lastUse = System.currentTimeMillis()
        // 0.46.4: chat asks the non-reasoning free models first (a reasoning model's hidden thinking was the long wait)
        val ordered = BackupModels.order(refreshModels(false), cooling, System.currentTimeMillis()).let { l -> if (chat && !coding) l.sortedBy { it.reasoning == true } else l }
        // 0.46.3 (BrainOrder): coders first for a coding job; zero-retention models only with Google data
        val list = BrainOrder.models(ordered.map { it.id }, coding, googleData).map { id -> ordered.firstOrNull { it.id == id } ?: BackupModels.Model(id, free = true) }
        if (list.isEmpty()) throw LlmClient.LlmException("backup: no free models", 503)
        // 0.46.6 (BrainRest): a "Free usage exceeded" rests only the model that said it (until its reset, or a backoff) and
        // the next on-phone model is asked; only when every model rests does the brain rest, and the race moves on to Nous.
        try {
            return BrainRest.run(list.map { it.id }, rests,
                call = { id -> withContext(Dispatchers.IO) { one(id, messages, tools, onText, onThink) }.also { lastUse = System.currentTimeMillis() } },
                freeReset = { e -> (e as? FreeLimit)?.until },
                empty = { EmptyReply.isEmpty(it) },
                onError = { id, e ->
                    val code = (e as? LlmClient.LlmException)?.code ?: 0
                    lastError = "$id: ${e.message.orEmpty().take(120)}"
                    cooling[id] = System.currentTimeMillis() + BackupModels.coolMs(code, e.message.orEmpty())
                    CallState.current().errors.add("backup $id: $code ${e.message.orEmpty().take(120)}")
                },
                onRest = { id, until ->
                    lastError = "$id: out of free usage"
                    CallState.current().errors.add("backup $id: resting until " + java.util.Date(until))
                    try { com.blaze.assistant.device.CrashLog.write("On-phone model $id resting until " + java.util.Date(until)) } catch (_: Throwable) { }
                },
                max = MAX_MODELS, budgetMs = TOTAL_MS)
        } finally {
            // the brain as a whole rests only while every usable model rests (Settings › Brains shows "resting" then)
            freeUntil = rests.allRestingUntil(models.map { it.id }.ifEmpty { list.map { it.id } }, System.currentTimeMillis())
        }
    }

    /** 0.46.6: per-model free-usage rests (BrainRest). */
    private val rests = BrainRest.Book()

    private suspend fun one(model: String, messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?, onThink: ((String) -> Unit)?): JSONObject {
        val (sc, st) = request("/session", "POST", JSONObject().put("title", "blaze"))
        if (sc !in 200..299) throw LlmClient.LlmException("backup: session $sc", sc)
        val session = JSONObject(st).optString("id").ifBlank { throw LlmClient.LlmException("backup: no session id", 0) }
        try {
            val body = BackupBridge.messageBody(messages, tools, model)
            // Always through the event stream (also without onText): OpenCode retries rate limits by itself and a plain
            // POST /message would sit through that; the stream shows the retry at once and Blaze moves on.
            val text = stream(session, body, onText, onThink)
            return BackupBridge.toOpenAI(text, tools)
        } finally {
            scope.launch { runCatching { request("/session/$session/abort", "POST") }; runCatching { request("/session/$session", "DELETE") } }
        }
    }

    /** Thrown when OpenCode says a model's free usage is used up: that model rests ([BrainRest]), the next one is asked. */
    class FreeLimit(msg: String, val until: Long) : LlmClient.LlmException(msg, 429)

    /** Streams one reply: subscribe to GET /event, send with prompt_async, follow this session's parts until idle. */
    private suspend fun stream(session: String, body: JSONObject, onText: ((String) -> Unit)?, onThink: ((String) -> Unit)?): String {
        val ev = open("/event", "GET", FIRST_CHUNK_MS.toInt()).apply { setRequestProperty("Accept", "text/event-stream") }
        return LlmClient.cancellable(ev) {
            val reader = ev.inputStream.bufferedReader()
            val (pc, pt) = request("/session/$session/prompt_async", "POST", body)
            if (pc !in 200..299) throw LlmClient.LlmException("backup: prompt $pc ${pt.take(200)}", pc)
            val assistant = HashSet<String>()
            val parts = LinkedHashMap<String, String>()   // text part id → its text so far (a reply can have several)
            var text = ""; var gotAny = false
            val start = System.currentTimeMillis()
            var lastAt = start
            val data = StringBuilder()
            while (true) {
                // The server's heartbeats keep the socket busy, so the waits are checked here, not only by the read timeout.
                val now = System.currentTimeMillis()
                if (now - start > TOTAL_MS) throw LlmClient.LlmException("backup: too long", 0)
                if (!gotAny && now - start > FIRST_CHUNK_MS) throw LlmClient.LlmException("backup: no first chunk", 0)
                if (gotAny && now - lastAt > GAP_MS) throw LlmClient.LlmException("backup: stalled", 0)
                val line = try { reader.readLine() } catch (e: java.net.SocketTimeoutException) {
                    throw LlmClient.LlmException(if (gotAny) "backup: stalled" else "backup: no first chunk", 0)
                } ?: break
                if (line.startsWith("data:")) { data.append(line.removePrefix("data:").trim()); continue }
                if (line.isNotEmpty() || data.isEmpty()) continue
                val o = try { JSONObject(data.toString()) } catch (_: Exception) { null }
                data.setLength(0)
                o ?: continue
                when (val e = BackupBridge.event(o, session, assistant)) {
                    is BackupBridge.Ev.Text -> { CallState.current().progress?.invoke(true); parts[e.part] = e.text; text = parts.values.joinToString(""); lastAt = System.currentTimeMillis(); if (!gotAny) { gotAny = true; ev.readTimeout = GAP_MS.toInt() }; LlmClient.lastChunkAt = System.currentTimeMillis(); onText?.invoke(BackupBridge.cleanText(text)) }
                    is BackupBridge.Ev.Think -> { CallState.current().progress?.invoke(false); lastAt = System.currentTimeMillis(); if (!gotAny) { gotAny = true; ev.readTimeout = GAP_MS.toInt() }; LlmClient.lastChunkAt = System.currentTimeMillis(); onThink?.invoke(e.text) }
                    is BackupBridge.Ev.Error -> throw LlmClient.LlmException(e.failure.message, e.failure.status)
                    is BackupBridge.Ev.Retry -> {
                        // 0.46.6: no reset given = 0, so BrainRest rests this one model for a backoff (not an hour for all)
                        if (BackupBridge.freeLimit(e.reason, e.message)) throw FreeLimit(e.message, if (e.next > System.currentTimeMillis()) e.next else 0L)
                        throw LlmClient.LlmException(e.message.ifBlank { "backup: retrying" }, 429)
                    }
                    BackupBridge.Ev.Idle -> return@cancellable text
                    BackupBridge.Ev.Other -> {}
                }
            }
            text
        }
    }
}
