package com.blaze.assistant.movies

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.46.2 runs one function of a user-added provider module in a fresh QuickJS context (engine: dokar3/quickjs-kt, Apache-2.0).
 * The module sees only [SandboxPrelude]: HTTP through [SandboxHttp] (guarded, capped, no cookies or Blaze headers), Jsoup-backed
 * cheerio, a per-provider key/value store (64 KB) and its own settings. No file system, no Android APIs, no Blaze secrets,
 * tokens or accounts. Limits: memory cap, stack cap, [Limits.timeoutMs] per call, [Limits.maxRequests] requests per call.
 * QuickJS here has no interrupt hook, so a module stuck in a busy loop is abandoned on the engine lane (the call fails,
 * the source is marked Failing and [abandoned] counts it; [ProviderStore] turns a source off after repeated hangs).
 * The design follows the Vega app's provider sandbox idea (one isolated run per call, JSON in/out; Apache-2.0, commit 9ccc0c8).
 */
class ProviderSandbox(private val http: SandboxHttp = DefaultSandboxHttp, private val limits: Limits = Limits()) {
    data class Limits(val timeoutMs: Long = 25_000, val memoryBytes: Long = 48L * 1024 * 1024, val stackBytes: Long = 1024L * 1024,
                      val maxRequests: Int = 40, val maxResultChars: Int = 2_000_000, val kvBytes: Int = 64 * 1024)

    data class Result(val value: Any?, val error: String?, val logs: List<String> = emptyList(), val ms: Long = 0) { val ok get() = error == null }

    /** Per-provider state the host keeps between calls (settings typed by the user, the module's kvStore). */
    class ProviderEnv(val settings: Map<String, String> = emptyMap(), val kv: MutableMap<String, String> = LinkedHashMap())

    companion object {
        val abandoned = AtomicInteger(0)
        /** The engine stayed busy for the whole wait. */
        const val BUSY = "Sources are busy right now. Try again in a moment."
        /** A source got stuck in a loop that never ends (the engine can't be stopped from outside); a restart frees it. */
        const val STUCK = "A source got stuck. Close and reopen Blaze to reset your sources."
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * 0.46.6 (Rocky's 0.46.5 crash report, a low-RAM phone on Android 12: the process died natively "while running: provider
     * call getPosts x5; catalog x2"). Two causes, both from running several QuickJS engines at once:
     *
     * 1. quickjs-kt 1.0.0-alpha13 keeps the JavaVM pointer and its JNI class / method caches in process-wide C statics
     *    (jni_globals.c, jni_globals_generated.c) and CLEARS them whenever any engine is closed (releaseGlobals ->
     *    clear_java_vm_cache + clear_jni_refs_cache). With two engines alive, one finishing deletes the global class refs the
     *    other is using and nulls the VM ("Cannot get jni env because the vm is not cached"): SIGSEGV in jobject_to_js_value.
     *    The library is only safe with ONE engine alive in the process at a time.
     * 2. The caller's timeout / cancel (Video browse gave up at 20 s, before the sandbox's own 25 s) shut its executor down while
     *    the engine still awaited a fetch; kotlinx then resumed the engine on a Dispatchers.IO thread (rejected dispatch) and
     *    quickjs-kt ran and closed that runtime from a foreign thread.
     *
     * Now there is ONE engine thread (the [Lane]) and ONE engine alive at a time, process-wide: each call's engine is created,
     * run, drained and closed on that thread, and the next call's engine is created only after the previous one is closed.
     * The lane is never shut down, so a continuation always comes back to it; a caller that gives up only stops waiting (it
     * never cancels or touches the engine), and the abandoned engine's host calls fail fast so it winds down quickly.
     * Calls wait in line; what the user opened (details, episodes, streams) goes ahead of background browsing.
     */
    object Gate {
        /** Engines alive right now / the most seen at once (tests: must never pass 1). */
        val active = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        /** Host calls or closes that ran on any thread but the engine lane. Must stay 0 (tests, and logged once if not). */
        val offLane = AtomicInteger(0)
        /** Low-RAM phone (smaller picture cache; set once at start). */
        @Volatile var lowRam = false; private set
        fun lowRamFor(lowRamDevice: Boolean, memoryClassMb: Int): Boolean = lowRamDevice || memoryClassMb in 1..192
        fun configure(ctx: android.content.Context) {
            try {
                val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                lowRam = lowRamFor(am.isLowRamDevice, am.memoryClass)
                com.blaze.assistant.ui.HomeImages.configure(lowRam)
            } catch (_: Throwable) { }
        }

        @Volatile internal var lane = Lane(); private set
        /** When the engine in progress was given up on and still hadn't ended (0 = not stuck). */
        @Volatile internal var stuckSince = 0L
        private val lock = Any()
        private var busy = false
        private val first = ArrayDeque<kotlinx.coroutines.CancellableContinuation<Unit>>()   // the user opened something
        private val rest = ArrayDeque<kotlinx.coroutines.CancellableContinuation<Unit>>()    // browsing / search / catalog

        /** True when the engine was handed over within [waitMs]. Cancellable; the engine is always given back by [release]. */
        internal suspend fun acquire(interactive: Boolean, waitMs: Long): Boolean = withTimeoutOrNull(waitMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
                val now = synchronized(lock) { if (!busy) { busy = true; true } else { (if (interactive) first else rest).addLast(c); false } }
                if (now) c.resume(Unit) { release() }
                else c.invokeOnCancellation { synchronized(lock) { first.remove(c); rest.remove(c) } }
            }
            true
        } ?: false

        /** The engine is closed: hand the lane to the next in line (the user's first). */
        internal fun release() {
            stuckSince = 0L
            val next = synchronized(lock) { (first.removeFirstOrNull() ?: rest.removeFirstOrNull()).also { if (it == null) busy = false } }
            next?.resume(Unit) { release() }   // cancelled before it could run: pass it on
        }
        /**
         * Tests only: a module in a pure busy loop (no host calls) never ends and keeps the lane; later tests get a fresh lane.
         * Safe only because such an engine makes no JNI calls and never closes (see the class note).
         */
        internal fun resetForTests() { synchronized(lock) { lane = Lane(); busy = false; first.clear(); rest.clear(); stuckSince = 0L }; active.set(0) }
        /** Calls waiting in line (tests). */
        val waiting: Int get() = synchronized(lock) { first.size + rest.size }

        internal fun enter() {
            val n = active.incrementAndGet()
            maxSeen.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            if (n > 1) noteOffLane("second engine alive ($n)")
        }
        internal fun leave() { active.decrementAndGet() }
        @Volatile private var offLaneLogged = false
        internal fun noteOffLane(where: String) {
            offLane.incrementAndGet()
            if (!offLaneLogged) { offLaneLogged = true; try { com.blaze.assistant.device.CrashLog.write("provider engine misuse: $where") } catch (_: Throwable) { } }
        }
    }

    /**
     * The one engine thread, never shut down (an idle daemon thread costs nothing). Its stack is 4 MB: QuickJS may use its own
     * 1 MB stack cap on top of the JNI and coroutine frames, and Android's default 1 MB thread stack could overflow natively
     * before QuickJS's own check fires.
     */
    internal class Lane {
        @Volatile private var thread: Thread? = null
        val dispatcher by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(null, r, "blaze-provider-js", 4L * 1024 * 1024).apply { isDaemon = true; thread = this } }.asCoroutineDispatcher()
        }
        fun owns(): Boolean = Thread.currentThread() === thread
    }

    /**
     * Load [moduleCode] and call its export [exportName] with [args] (+ signal, providerContext), or with no export resolve every
     * export (the catalog module: catalog / genres arrays or functions). Never throws (except the caller's own cancellation):
     * failures come back in [Result.error]. [interactive] = the user opened something (details, episodes, streams).
     */
    suspend fun invoke(moduleCode: String, exportName: String?, args: JSONObject = JSONObject(), env: ProviderEnv = ProviderEnv(), interactive: Boolean = false): Result {
        val t0 = System.currentTimeMillis()
        // Waiting in line: what the user opened waits at most for the call in progress; browsing waits behind other sources too.
        if (!Gate.acquire(interactive, if (interactive) limits.timeoutMs + 5_000 else limits.timeoutMs * 3))
            return Result(null, if (Gate.stuckSince > 0 && System.currentTimeMillis() - Gate.stuckSince > 20_000) STUCK else BUSY, emptyList(), System.currentTimeMillis() - t0)
        val lane = Gate.lane
        val done = CompletableDeferred<Result>()
        val gaveUp = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            scope.launch(lane.dispatcher) { runEngine(lane, moduleCode, exportName, args, env, done, gaveUp) }
        } catch (e: Throwable) { Gate.release(); return Result(null, friendly(e), emptyList(), System.currentTimeMillis() - t0) }
        // The in-flight marker tells the next start if the process died in here.
        val mark = com.blaze.assistant.device.CrashLog.enter("provider call ${exportName ?: "catalog"}")
        val r = try { withTimeoutOrNull(limits.timeoutMs) { done.await() } } catch (e: Throwable) {
            // The caller gave up (cancelled): only stop waiting. The engine winds down and closes on its own lane.
            gaveUp.set(true)
            scope.launch { runCatching { done.await() }; com.blaze.assistant.device.CrashLog.leave(mark) }
            throw e
        }
        if (r != null) { com.blaze.assistant.device.CrashLog.leave(mark); return r }
        gaveUp.set(true)
        scope.launch { runCatching { done.await() }; com.blaze.assistant.device.CrashLog.leave(mark) }
        // Timed out: the call is abandoned on its lane, which closes the engine there when (if) it ends. Never cancel the job
        // from here: quickjs-kt's cancellation touches the native runtime from this thread (SIGSEGV in JS_DefineProperty, CI run
        // 40341276397). Its fetches and timers now fail at once so it ends soon; a pure busy loop keeps the lane until the app
        // restarts (calls then get [BUSY]) and [ProviderStore] turns that source off.
        val settled = withTimeoutOrNull(500) { done.await() }
        if (settled == null) { abandoned.incrementAndGet(); if (!done.isCompleted) Gate.stuckSince = System.currentTimeMillis() }
        return Result(null, "The source took longer than ${maxOf(1L, limits.timeoutMs / 1000)} s", emptyList(), System.currentTimeMillis() - t0)
    }

    /** Runs on [lane] only, from engine create to engine close. */
    private suspend fun runEngine(lane: Lane, moduleCode: String, exportName: String?, args: JSONObject, env: ProviderEnv, done: CompletableDeferred<Result>,
                                  gaveUp: java.util.concurrent.atomic.AtomicBoolean) {
        val t0 = System.currentTimeMillis()
        var q: QuickJs? = null
        var entered = false
        // Async host calls still out (a fetch on the IO pool, a timer). The engine is freed only after every one came back to
        // this lane and quickjs-kt settled its promise: freeing it earlier left a callback into a released runtime.
        val pending = AtomicInteger(0)
        fun here(where: String) { if (!lane.owns()) Gate.noteOffLane(where) }
        suspend fun <T> host(where: String, body: suspend () -> T): T {
            pending.incrementAndGet()
            try { here(where); return body() } finally { pending.decrementAndGet() }
        }
        try {
            Gate.enter(); entered = true
            q = QuickJs.create(lane.dispatcher)
            q.memoryLimit = limits.memoryBytes
            q.maxStackSize = limits.stackBytes
            // 0.46.5: QuickJS's own out-of-memory paths can crash the whole app natively (SIGSEGV in JS_DefineProperty,
            // CI run 40381571120). The prelude counts what the big allocating builtins produce and throws a normal JS error
            // at 60 % of the hard cap, long before the engine reaches it.
            q.function("__membudget") { _ -> (limits.memoryBytes * 6 / 10).toDouble() }
            // 0.46.6: the guard's count is reset to the engine's real live size at every await (fetch, timer): an async host
            // function body runs on this lane while no JS runs, so the engine can be asked there (unlike from a sync host call,
            // which blocked in 0.46.5). Counting only between awaits, a normal page never adds up to the cap (0.46.5's
            // cumulative count failed details calls of sources that parse real pages: "Couldn't open"); a runaway loop still stops.
            val engine: QuickJs = q
            q.asyncFunction("__h_live") { _ -> host("live") { try { engine.gc(); engine.memoryUsage.mallocSize.toDouble() } catch (_: Throwable) { -1.0 } } }
            val dom = Dom()
            val requests = AtomicInteger(0)
            var out: String? = null; var fail: String? = null
            q.function("__done") { a -> here("done"); out = a.getOrNull(0)?.toString() ?: "null"; null }
            q.function("__fail") { a -> here("fail"); fail = (a.getOrNull(0)?.toString() ?: "error").removePrefix("Error: ").take(500); null }
            // A timer ends early once the caller gave up, so an abandoned engine frees the lane within ~100 ms.
            q.asyncFunction("__h_sleep") { a -> host("sleep") {
                var left = ((a.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceIn(0L, 10_000L)
                while (left > 0 && !gaveUp.get()) { val step = minOf(left, 100L); delay(step); left -= step }
                null
            } }
            q.asyncFunction("__h_fetch") { a -> host("fetch") {
                if (gaveUp.get()) JSONObject().put("error", "Cancelled").toString()
                else if (requests.incrementAndGet() > limits.maxRequests) JSONObject().put("error", "Too many requests (over ${limits.maxRequests})").toString()
                else try {
                    val req = DefaultSandboxHttp.reqFrom(JSONObject(a.getOrNull(0)?.toString() ?: "{}"))
                    DefaultSandboxHttp.resJson(withContext(Dispatchers.IO) { http.request(req) })
                } catch (e: Exception) { JSONObject().put("error", (e.message ?: e.javaClass.simpleName).take(300)).toString() }
            } }
            q.asyncFunction("__h_baseurl") { _ -> host("baseurl") { env.settings["baseUrl"].orEmpty().trim().trimEnd('/') } }
            q.asyncFunction("__h_kv") { a -> host("kv") { kv(env, a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2)?.toString().orEmpty()) } }
            q.function("__u_parse") { a -> parseUrl(a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1)?.toString().orEmpty()) }
            q.function("__c_load") { a -> here("cheerio"); dom.load(a.getOrNull(0)?.toString().orEmpty()) }
            q.function("__c_select") { a -> dom.select(a.getOrNull(0)?.toString(), a.getOrNull(1)?.toString().orEmpty()) }
            q.function("__c_text") { a -> dom.text(a.getOrNull(0)?.toString()) }
            q.function("__c_html") { a -> dom.html((a.getOrNull(0) as? Number)?.toInt() ?: -1, a.getOrNull(1) == true) }
            q.function("__c_attr") { a -> dom.attr((a.getOrNull(0) as? Number)?.toInt() ?: -1, a.getOrNull(1)?.toString().orEmpty()) }
            q.function("__c_attrs") { a -> dom.attrs((a.getOrNull(0) as? Number)?.toInt() ?: -1) }
            q.function("__c_tag") { a -> dom.tag((a.getOrNull(0) as? Number)?.toInt() ?: -1) }
            q.function("__c_rel") { a -> dom.rel(a.getOrNull(0)?.toString(), a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2)?.toString().orEmpty()) }
            q.function("__c_filter") { a -> dom.filter(a.getOrNull(0)?.toString(), a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2) == true) }
            q.evaluate<Any?>(SandboxPrelude.JS, "prelude.js", false)
            q.evaluate<Any?>(wrapper(moduleCode, exportName, args), "provider.js", false)
            val logs = runCatching { q.evaluate<String>("JSON.stringify(globalThis.__logs || [])", "logs.js", false) }.getOrNull()
                ?.let { s -> JSONArray(s).let { j -> (0 until j.length()).map { j.optString(it) } } }.orEmpty()
            val ms = System.currentTimeMillis() - t0
            done.complete(when {
                fail != null -> Result(null, fail, logs, ms)
                out == null -> Result(null, "The source didn't return anything", logs, ms)
                out!!.length > limits.maxResultChars -> Result(null, "The source returned too much data", logs, ms)
                else -> Result(JSONTokener(out).nextValue(), null, logs, ms)
            })
        } catch (e: Throwable) {
            done.complete(Result(null, friendly(e), emptyList(), System.currentTimeMillis() - t0))
        } finally {
            withContext(NonCancellable) {
                // Bounded: fetches have their own connect/read timeouts and timers are capped at 10 s.
                while (pending.get() > 0) delay(20)
                repeat(4) { yield() }   // let quickjs-kt resolve / reject the last promises on this lane
                here("close")
                runCatching { q?.close() }
            }
            if (entered) Gate.leave()
            Gate.release()
        }
    }

    private fun friendly(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull().orEmpty().removePrefix("Error: ").take(300)
        // 0.46.5: when the heap is exhausted QuickJS can't even build the message and throws a bare InternalError (CI runs 40367470857, 40368792061).
        if (Regex("""^InternalError:?\s*(?:<NO_MESSAGE>)?\s*$""").matches(m.trim())) return "The source used too much memory or stack"
        return if (m.contains("out of memory", true)) "The source used too much memory" else if (m.contains("stack overflow", true)) "The source overflowed its stack" else m
    }

    private fun wrapper(code: String, export: String?, args: JSONObject): String {
        val call = if (export == null) """
            var ex = mod.exports, res = {};
            for (var k in ex) { var v = ex[k]; if (typeof v === 'function') { try { res[k] = await v(Object.assign({}, A, { signal: __signal, providerContext: __ctx })); } catch (e) { res[k] = null; } } else res[k] = v; }
            return res;""" else """
            var fn = mod.exports[${JSONObject.quote(export)}];
            if (typeof fn !== 'function') throw new Error('The source has no ${export.replace("'", "")}');
            return await fn(Object.assign({}, A, { signal: __signal, providerContext: __ctx }));"""
        return """
(function(){
  var mod = { exports: {} };
  var A = ${args};
  (function(exports, module, require, console, fetch, setTimeout, clearTimeout, setInterval, clearInterval, providerGlobal, globalThis, self, window, XMLHttpRequest, WebSocket, importScripts) {
${code}
  }).call(undefined, mod.exports, mod, __require, console, fetch, setTimeout, clearTimeout, setInterval, clearInterval, {}, undefined, undefined, undefined, undefined, undefined, undefined, undefined);
  (async function(){ $call })().then(function(v){ __done(JSON.stringify(v === undefined ? null : v)); }, function(e){ __fail(String((e && e.message) || e)); });
})();
"""
    }

    private fun kv(env: ProviderEnv, op: String, key: String, value: String): String = synchronized(env.kv) {
        when (op) {
            "get" -> env.kv[key].orEmpty()
            "set" -> {
                val size = env.kv.entries.sumOf { it.key.length + it.value.length } - (env.kv[key]?.length ?: 0) + key.length + value.length
                if (size <= limits.kvBytes && key.length <= 200) env.kv[key] = value; ""
            }
            "delete" -> (env.kv.remove(key) != null).toString()
            "keys" -> JSONArray(env.kv.keys.toList()).toString()
            "clear" -> { env.kv.clear(); "" }
            else -> ""
        }
    }

    private fun parseUrl(u: String, base: String): String = try {
        val uri = (if (base.isNotBlank()) URI(base.trim()).resolve(u.trim()) else URI(u.trim()))
        if (uri.scheme == null || uri.host == null) throw IllegalArgumentException()
        val port = if (uri.port > 0) uri.port.toString() else ""
        JSONObject().put("protocol", uri.scheme + ":").put("hostname", uri.host).put("port", port).put("host", uri.host + if (port.isEmpty()) "" else ":$port")
            .put("origin", uri.scheme + "://" + uri.host + if (port.isEmpty()) "" else ":$port")
            .put("pathname", uri.rawPath.ifEmpty { "/" }).put("search", uri.rawQuery?.let { "?$it" }.orEmpty()).put("hash", uri.rawFragment?.let { "#$it" }.orEmpty()).toString()
    } catch (_: Exception) { "{\"error\":true}" }

    /** The Jsoup side of the cheerio shim: node ids live only for this call. */
    private class Dom {
        private val nodes = ArrayList<Node>()
        private val ids = IdentityHashMap<Node, Int>()
        fun id(n: Node): Int = ids.getOrPut(n) { nodes.add(n); nodes.size - 1 }
        fun node(i: Int): Node? = nodes.getOrNull(i)
        fun el(i: Int): Element? = node(i) as? Element
        private fun list(j: String?): List<Int> = try { JSONArray(j ?: "[]").let { a -> (0 until a.length()).map { a.getInt(it) } } } catch (_: Exception) { emptyList() }
        private fun out(ns: Collection<Node>): String = JSONArray(ns.distinct().take(5000).map { id(it) }).toString()
        fun load(html: String): Int { if (nodes.size > 200_000) throw IllegalStateException("Too many pages parsed"); return id(Jsoup.parse(html.take(8_000_000))) }
        fun select(j: String?, sel: String): String = try {
            out(list(j).mapNotNull { el(it) }.flatMap { e -> e.select(sel).filter { it !== e } })
        } catch (_: Exception) { "[]" }
        fun text(j: String?): String = list(j).mapNotNull { node(it) }.joinToString("") { n -> when (n) { is Element -> n.wholeText(); is TextNode -> n.wholeText; else -> "" } }
        fun html(i: Int, outer: Boolean): String? = node(i)?.let { n -> if (outer) n.outerHtml() else (n as? Element)?.html() }
        fun attr(i: Int, name: String): String? = el(i)?.let { if (it.hasAttr(name)) it.attr(name) else null }
        fun attrs(i: Int): String = JSONObject().also { o -> el(i)?.attributes()?.forEach { a -> o.put(a.key, a.value) } }.toString()
        fun tag(i: Int): String = el(i)?.tagName()?.lowercase().orEmpty()
        fun filter(j: String?, sel: String, not: Boolean): String = try {
            out(list(j).mapNotNull { el(it) }.filter { it.`is`(sel) != not })
        } catch (_: Exception) { "[]" }
        fun rel(j: String?, kind: String, sel: String): String = try {
            val els = list(j).mapNotNull { el(it) }
            val r: List<Node> = when (kind) {
                "children" -> els.flatMap { it.children() }
                "contents" -> els.flatMap { it.childNodes() }
                "parent" -> els.mapNotNull { it.parent() }
                "parents" -> els.flatMap { it.parents() }
                "next" -> els.mapNotNull { it.nextElementSibling() }
                "prev" -> els.mapNotNull { it.previousElementSibling() }
                "nextAll" -> els.flatMap { it.nextElementSiblings() }
                "prevAll" -> els.flatMap { it.previousElementSiblings() }
                "siblings" -> els.flatMap { it.siblingElements() }
                "closest" -> els.mapNotNull { e -> if (sel.isBlank()) null else e.closest(sel) }
                else -> emptyList()
            }
            out(if (sel.isBlank() || kind == "closest") r else r.filter { it is Element && it.`is`(sel) })
        } catch (_: Exception) { "[]" }
    }
}
