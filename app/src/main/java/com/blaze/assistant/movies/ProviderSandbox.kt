package com.blaze.assistant.movies

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * QuickJS here has no interrupt hook, so a module stuck in a busy loop is abandoned on its own daemon thread (the call fails,
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
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Load [moduleCode] and call its export [exportName] with [args] (+ signal, providerContext), or with no export resolve every
     * export (the catalog module: catalog / genres arrays or functions). Never throws: failures come back in [Result.error].
     */
    suspend fun invoke(moduleCode: String, exportName: String?, args: JSONObject = JSONObject(), env: ProviderEnv = ProviderEnv()): Result {
        val t0 = System.currentTimeMillis()
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-provider-js").apply { isDaemon = true } }
        val dispatcher = exec.asCoroutineDispatcher()
        val done = CompletableDeferred<Result>()
        scope.launch(dispatcher) {
            var q: QuickJs? = null
            try {
                q = QuickJs.create(dispatcher)
                q.memoryLimit = limits.memoryBytes
                q.maxStackSize = limits.stackBytes
                // 0.46.5: QuickJS's own out-of-memory paths can crash the whole app natively (SIGSEGV in JS_DefineProperty,
                // CI run 40381571120; the likely "sudden crash while browsing Video" on 0.46.4). The prelude checks this soft
                // cap (half the hard one) from the allocating builtins and throws a normal JS error long before the hard cap.
                val qq = q
                val soft = limits.memoryBytes / 2
                q.function("__mem") { _ -> if (runCatching { qq.memoryUsage.mallocSize }.getOrDefault(0L) > soft) 1 else 0 }
                val dom = Dom()
                val requests = AtomicInteger(0)
                var out: String? = null; var fail: String? = null
                q.function("__done") { a -> out = a.getOrNull(0)?.toString() ?: "null"; null }
                q.function("__fail") { a -> fail = (a.getOrNull(0)?.toString() ?: "error").removePrefix("Error: ").take(500); null }
                q.asyncFunction("__h_sleep") { a -> delay(((a.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceIn(0L, 10_000L)); null }
                q.asyncFunction("__h_fetch") { a ->
                    if (requests.incrementAndGet() > limits.maxRequests) JSONObject().put("error", "Too many requests (over ${limits.maxRequests})").toString()
                    else try {
                        val req = DefaultSandboxHttp.reqFrom(JSONObject(a.getOrNull(0)?.toString() ?: "{}"))
                        DefaultSandboxHttp.resJson(withContext(Dispatchers.IO) { http.request(req) })
                    } catch (e: Exception) { JSONObject().put("error", (e.message ?: e.javaClass.simpleName).take(300)).toString() }
                }
                q.asyncFunction("__h_baseurl") { _ -> env.settings["baseUrl"].orEmpty().trim().trimEnd('/') }
                q.asyncFunction("__h_kv") { a -> kv(env, a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2)?.toString().orEmpty()) }
                q.function("__u_parse") { a -> parseUrl(a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1)?.toString().orEmpty()) }
                q.function("__c_load") { a -> dom.load(a.getOrNull(0)?.toString().orEmpty()) }
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
                runCatching { q?.close() }
            }
        }
        // 0.46.5: the caller's own timeout / cancel (Video browse) must never leave the engine thread's executor open, and
        // never cancel the job from here (see below); the in-flight marker tells the next start if the process died in here.
        val mark = com.blaze.assistant.device.CrashLog.enter("provider call ${exportName ?: "catalog"}")
        val r = try { withTimeoutOrNull(limits.timeoutMs) { done.await() } } catch (e: Throwable) {
            scope.launch { runCatching { done.await() }; com.blaze.assistant.device.CrashLog.leave(mark) }
            exec.shutdown(); throw e
        }
        if (r != null) { exec.shutdown(); com.blaze.assistant.device.CrashLog.leave(mark); return r }
        scope.launch { runCatching { done.await() }; com.blaze.assistant.device.CrashLog.leave(mark) }
        // Timed out: the call is abandoned on its own daemon thread and closes its engine there when (if) it ends. Never cancel
        // the job from here: quickjs-kt's cancellation touches the native runtime from this thread while evaluate() is still
        // running on the JS thread, which crashed the process (SIGSEGV in JS_DefineProperty, CI run 40341276397).
        val settled = withTimeoutOrNull(500) { done.await() }
        if (settled == null) abandoned.incrementAndGet()
        exec.shutdown()
        return Result(null, "The source took longer than ${limits.timeoutMs / 1000} s", emptyList(), System.currentTimeMillis() - t0)
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
