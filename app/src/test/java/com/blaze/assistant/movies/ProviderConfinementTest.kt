package com.blaze.assistant.movies

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.46.6 (Rocky's 0.46.5 crash report, a low-RAM phone: native crash "while running: provider call getPosts x5; catalog x2").
 * Root cause: the caller's timeout / cancel shut down the engine's executor while it still awaited a fetch, so kotlinx resumed
 * the engine on a Dispatchers.IO thread and quickjs-kt used (and closed) the runtime from there, several runtimes at once.
 * These tests drive that exact pattern (many concurrent calls, callers giving up mid-fetch) and check every host call and every
 * close ran on the engine's own lane, that no more than the allowed engines run at once, that a title the user opens is not
 * stuck behind browsing, and that a normal details page is no longer refused by the memory guard.
 */
class ProviderConfinementTest {
    /** In-memory site: [delayMs] per request, counts how many requests are in flight at once (= engines working at once). */
    private class SlowHttp(val delayMs: Long, val body: (String) -> String) : SandboxHttp {
        val inFlight = AtomicInteger(0); val maxInFlight = AtomicInteger(0); val count = AtomicInteger(0)
        override fun request(r: SandboxHttp.Req): SandboxHttp.Res {
            val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }; count.incrementAndGet()
            try { Thread.sleep(delayMs); return SandboxHttp.Res(200, "OK", r.url, mapOf("content-type" to "text/html"), body(r.url)) }
            finally { inFlight.decrementAndGet() }
        }
    }

    private fun page(n: Int) = "<html><body><div class=list>" + (1..n).joinToString("") { i ->
        "<div class=\"film\"><a href=\"/film/$i\">Film number $i</a><img src=\"/img/$i.jpg\"><span class=year>19${i % 100}</span></div>" } + "</div></body></html>"

    private val posts200 = """
        exports.getPosts = async function({ filter, providerContext }) {
          var r = await providerContext.axios.get('https://films.example/' + (filter || 'latest'));
          var ${'$'} = providerContext.cheerio.load(r.data); var out = [];
          ${'$'}('.film').each(function(i, el){ out.push({ title: ${'$'}(el).find('a').text().trim(), link: ${'$'}(el).find('a').attr('href'), image: ${'$'}(el).find('img').attr('src') }); });
          return out;
        };
        exports.catalog = [{ title: 'Latest', filter: 'latest' }, { title: 'Popular', filter: 'popular' }];
    """.trimIndent()

    // Each test starts and ends with no engine alive and nobody waiting (an engine still closing would skew the counters).
    @Before fun up() = runBlocking { settle(); ProviderSandbox.Gate.maxSeen.set(0) }
    @After fun down() = runBlocking { settle() }

    private suspend fun settle(maxMs: Long = 15_000) { val t = System.currentTimeMillis()
        while ((ProviderSandbox.Gate.active.get() > 0 || ProviderSandbox.Gate.waiting > 0) && System.currentTimeMillis() - t < maxMs) delay(20) }

    @Test fun callersGivingUpMidFetchNeverRunTwoEnginesOrLeaveTheLane() = runBlocking {
        val off0 = ProviderSandbox.Gate.offLane.get()
        val http = SlowHttp(300) { page(200) }
        val sb = ProviderSandbox(http)
        // 4 rounds of 7 calls at once (the report: getPosts x5 + catalog x2), callers giving up after 100-250 ms, mid-fetch.
        repeat(4) { round ->
            (1..7).map { i -> async {
                withTimeoutOrNull(100L + 50 * (i % 4)) { sb.invoke(posts200, if (i % 3 == 0) null else "getPosts", JSONObject().put("filter", "r$round-$i")) }
            } }.awaitAll()
        }
        settle()
        assertEquals("host calls / closes off the engine lane, or two engines alive", off0, ProviderSandbox.Gate.offLane.get())
        assertEquals("one QuickJS engine alive at a time (quickjs-kt's JNI caches are process-wide)", 1, ProviderSandbox.Gate.maxSeen.get())
        assertTrue("one engine fetching at a time, saw ${http.maxInFlight.get()}", http.maxInFlight.get() <= 1)
        assertEquals(0, ProviderSandbox.Gate.waiting)
        // and everything still works afterwards: the engine was always given back
        val r = (1..7).map { i -> async { sb.invoke(posts200, "getPosts", JSONObject().put("filter", "after$i")) } }.awaitAll()
        r.forEach { assertTrue(it.error, it.ok); assertEquals(200, (it.value as JSONArray).length()) }
        settle()
        assertEquals(off0, ProviderSandbox.Gate.offLane.get()); assertEquals(1, ProviderSandbox.Gate.maxSeen.get())
    }

    @Test fun anAbandonedEngineWindsDownFast() = runBlocking {
        // 40 fetches of 300 ms each would keep the engine 12 s after its caller left; its host calls now fail at once.
        val http = SlowHttp(300) { "<html></html>" }
        val greedy = """exports.getPosts = async function({ providerContext }) { var n = 0;
            for (var i = 0; i < 40; i++) { try { await providerContext.axios.get('https://films.example/p' + i); n++; } catch (e) { await new Promise(function(r){ setTimeout(r, 5000); }); } } return n; };"""
        val r = withTimeoutOrNull(200) { ProviderSandbox(http).invoke(greedy, "getPosts") }
        assertEquals(null, r)
        val t0 = System.currentTimeMillis(); settle(5_000)
        assertEquals(0, ProviderSandbox.Gate.active.get())
        assertTrue("wound down in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
        assertTrue("only the fetch in flight finished, saw ${http.count.get()}", http.count.get() <= 2)
    }

    @Test fun browse200ItemsWithConcurrentLoadsAllLand() = runBlocking {
        val http = SlowHttp(40) { page(200) }
        val src = JsProviderSource(ProviderRepo.Entry("stress", "Stress", "1.0"), { _ -> posts200 }, ProviderSandbox.ProviderEnv(), ProviderSandbox(http))
        val rows = (1..3).map { async { src.rows() } }.awaitAll()
        rows.forEach { rs -> assertEquals(2, rs.size); rs.forEach { (_, ps) -> assertEquals(200, ps.size) } }
        assertEquals(1, ProviderSandbox.Gate.maxSeen.get())
    }

    @Test fun aTitleTheUserOpensGoesAheadOfBrowsing() = runBlocking {
        val sb = ProviderSandbox(SlowHttp(1_500) { page(5) })
        val ends = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val browsing = (1..4).map { i -> launch { sb.invoke(posts200, "getPosts", JSONObject().put("filter", "b$i")); ends.add("browse$i") } }
        delay(300)   // browse 1 runs, 2-4 wait in line
        val fast = ProviderSandbox(SlowHttp(10) { "<html><h1>The General</h1><p class=plot>A train, a war, a chase.</p></html>" })
        val meta = """exports.getMeta = async function({ link, providerContext }) { var r = await providerContext.axios.get(link); var ${'$'} = providerContext.cheerio.load(r.data);
            return { title: ${'$'}('h1').text(), synopsis: ${'$'}('.plot').text(), linkList: [] }; };"""
        val t0 = System.currentTimeMillis()
        val r = fast.invoke(meta, "getMeta", JSONObject().put("link", "https://films.example/film/1"), interactive = true)
        val ms = System.currentTimeMillis() - t0; ends.add("details")
        assertTrue(r.error, r.ok); assertEquals("The General", (r.value as JSONObject).getString("title"))
        assertTrue("details waited $ms ms", ms < 3_000)
        browsing.forEach { it.join() }
        assertEquals("details right after the call in progress: $ends", listOf("browse1", "details"), ends.take(2))
    }

    @Test fun aSourceStuckInABusyLoopGivesAPlainErrorNotAHang() = runBlocking {
        val sb = ProviderSandbox(SlowHttp(10) { "" }, ProviderSandbox.Limits(timeoutMs = 400))
        val first = sb.invoke("exports.getPosts = function(){ while (true) {} };", "getPosts")
        assertTrue(first.error, first.error!!.contains("longer than"))
        val t0 = System.currentTimeMillis()
        val second = sb.invoke("exports.getPosts = async function(){ return [1]; };", "getPosts")
        assertFalse(second.ok); assertEquals(ProviderSandbox.BUSY, second.error)
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
        ProviderSandbox.Gate.stuckSince = System.currentTimeMillis() - 30_000
        assertEquals(ProviderSandbox.STUCK, sb.invoke("exports.getPosts = async function(){ return [1]; };", "getPosts").error)
        ProviderSandbox.Gate.resetForTests()   // the spinning engine keeps its old lane (no host calls, never closes)
        assertTrue(sb.invoke("exports.getPosts = async function(){ return [1]; };", "getPosts").ok)
    }

    @Test fun aNormalDetailsPageIsNotRefusedByTheMemoryGuard() = runBlocking {
        // 0.46.5 counted every allocation for the whole call against 60 % of 48 MB, so a source that fetched and parsed a few real
        // pages was refused ("Couldn't open"). The count now restarts from the engine's live size at every await.
        val big = page(4_000)   // ~0.6 MB of HTML per page
        val http = SlowHttp(5) { big }
        val details = """
            exports.getMeta = async function({ link, providerContext }) {
              var all = [];
              for (var p = 0; p < 6; p++) {
                var r = await providerContext.axios.get(link + '?page=' + p);
                var ${'$'} = providerContext.cheerio.load(r.data);
                var rows = [];
                ${'$'}('.film').each(function(i, el){ rows.push([${'$'}(el).find('a').text(), ${'$'}(el).find('.year').text(), ${'$'}(el).find('img').attr('src')].join('|')); });
                all = all.concat(rows.slice(0, 50));
                var copy = JSON.parse(JSON.stringify({ html: r.data.slice(0, 400000), rows: rows }));
              }
              return { title: 'Big page', synopsis: 'ok ' + all.length, linkList: [] };
            };"""
        val r = ProviderSandbox(http).invoke(details, "getMeta", JSONObject().put("link", "https://films.example/film/1"), interactive = true)
        assertTrue(r.error, r.ok); assertEquals("ok 300", (r.value as JSONObject).getString("synopsis"))
        // a runaway loop is still stopped with a plain error
        val runaway = ProviderSandbox(http, ProviderSandbox.Limits(memoryBytes = 16L * 1024 * 1024))
            .invoke("""exports.getMeta = async function(){ var a = []; for (;;) a.push('x'.repeat(100000) + a.length); };""", "getMeta", interactive = true)
        assertFalse(runaway.ok); assertTrue(runaway.error, runaway.error!!.contains("memory"))
    }

    @Test fun oneEngineThreadWithRoomAndNoShutdown() {
        assertTrue(ProviderSandbox.Gate.lowRamFor(lowRamDevice = true, memoryClassMb = 256))
        assertTrue(ProviderSandbox.Gate.lowRamFor(lowRamDevice = false, memoryClassMb = 128))
        assertFalse(ProviderSandbox.Gate.lowRamFor(lowRamDevice = false, memoryClassMb = 256))
        val src = listOf(File("src/main/java/com/blaze/assistant/movies/ProviderSandbox.kt"), File("app/src/main/java/com/blaze/assistant/movies/ProviderSandbox.kt")).first { it.exists() }.readText()
        assertFalse("never shut an engine's executor down", src.contains("exec.shutdown()"))
        assertTrue(src.contains("4L * 1024 * 1024"))
        assertTrue(src.contains("while (pending.get() > 0) delay(20)"))
    }
}
