package com.blaze.assistant.movies

import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.mediaapps.BrowseState
import com.blaze.assistant.mediaapps.VideoHome
import com.blaze.assistant.mediaapps.VideoKeys
import com.blaze.assistant.ui.HomeImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.5 (Rocky 21:04: 0.46.4 closed suddenly while browsing Video with user-added sources, no stack trace): duplicate ids
 * (Compose's duplicate-key crash), malformed items, providers that throw or hang, huge posters, the redacted crash log and the
 * next-launch "Send crash report", and the old Movies tab being gone.
 */
class VideoCrashHardeningTest {
    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()

    @Test fun duplicateIdsNeverReachALazyList() {
        val a = MoviePost("Film A", "/a", "", "p:x"); val a2 = a.copy(title = "Film A again"); val sameLinkOtherSource = a.copy(source = "p:y")
        val clean = BrowseState.clean(listOf(a, a2, sameLinkOtherSource, a))
        assertEquals(listOf(a, sameLinkOtherSource), clean)
        assertEquals(clean.size, clean.map { VideoKeys.post(it) }.toSet().size)
        // every keyed list goes through VideoKeys
        assertEquals(listOf("k1", "k2"), VideoKeys.unique(listOf("k1", "k2", "k1")) { it })
        assertEquals(2, VideoKeys.posts(listOf(a, a, sameLinkOtherSource)).size)
        // a source listed twice gives one row
        assertEquals(listOf("archive", "p:x"), VideoHome.order(listOf("p:x", "archive", "p:x", "archive")))
        // hero: never the same item twice
        val hero = VideoHome.hero(emptyList(), mapOf("p:x" to listOf(a.copy(image = "https://i/a.jpg"), a.copy(image = "https://i/a.jpg"))))
        assertEquals(1, hero.size)
        val shell = src("mediaapps/VideoShell.kt"); val ui = src("mediaapps/MoviesUi.kt"); val act = src("mediaapps/VideoActivity.kt")
        assertTrue(shell.contains("items(VideoKeys.posts(posts), key = { \"p-\" + VideoKeys.post(it) })"))
        assertTrue(shell.contains("items(VideoKeys.posts(watch), key = { \"wl-\" + VideoKeys.post(it) })"))
        assertTrue(shell.contains("items(VideoKeys.unique(hero) { heroKey(it) }, key = { heroKey(it) })"))
        assertTrue(ui.contains("items(VideoKeys.unique(eps) { \"ep-\" + it.link.link }"))
        assertTrue(act.contains("items(VideoKeys.unique(shown) { it.key }, key = { \"v|\" + it.key })"))
    }

    @Test fun malformedItemsAreDroppedOrTrimmed() {
        assertNull(BrowseState.sane(MoviePost("", "/x")))
        assertNull(BrowseState.sane(MoviePost("Title", "   ")))
        assertNull(BrowseState.sane(null))
        val odd = BrowseState.sane(MoviePost("  A\n\n  film\t ", "/x", "data:image/png;base64," + "A".repeat(5000), "p:x", "T".repeat(90)))!!
        assertEquals("A film", odd.title); assertEquals("", odd.image); assertEquals(40, odd.tag.length)
        assertEquals("", BrowseState.sane(MoviePost("A", "/x", "https://h/" + "a".repeat(3000)))!!.image)
        assertEquals(200, BrowseState.clean((1..500).map { MoviePost("T$it", "/l$it", "", "p:x") }).size)
        assertTrue(BrowseState.clean(null).isEmpty())
        // the provider JSON parser: nulls, numbers, nested junk, missing fields
        val junk = JSONArray().put(JSONObject.NULL).put(5).put("str").put(JSONArray()).put(JSONObject().put("title", JSONObject.NULL).put("link", "/a"))
            .put(JSONObject().put("title", 42).put("link", 7)).put(JSONObject().put("title", "Ok").put("link", "/ok").put("image", JSONObject()))
        val posts = MovieJson.posts(junk, "p:x")
        assertEquals(listOf("42", "Ok"), posts.map { it.title })
        assertTrue(MovieJson.posts("not an array", "p:x").isEmpty()); assertTrue(MovieJson.posts(null, "p:x").isEmpty())
        assertNull(MovieJson.info(JSONObject().put("linkList", "nope")))
        assertNotNull(MovieJson.info(JSONObject().put("title", "T").put("linkList", JSONArray().put(5).put(JSONObject.NULL)).put("tags", "x")))
        assertTrue(MovieJson.streams(JSONArray().put(JSONObject().put("link", "javascript:alert(1)")).put(JSONObject.NULL)).isEmpty())
    }

    private fun provider(value: String, code: String, limits: ProviderSandbox.Limits = ProviderSandbox.Limits()) =
        JsProviderSource(ProviderRepo.Entry(value, "My Secret Source", "1.0"), { _ -> code }, ProviderSandbox.ProviderEnv(), ProviderSandbox(limits = limits))

    @Test fun providerThatThrowsOrHangsFailsThatSourceOnly() = runBlocking {
        val throws = provider("thrower", """exports.getSearchPosts = async function(){ throw new TypeError('cannot read x of undefined'); };""")
        // hangs: waits on a timer far past its time limit (a busy loop would spin a CPU for the rest of the test run)
        val hangs = provider("hanger", """exports.getSearchPosts = async function(){ await new Promise(function(r){ setTimeout(r, 10000); }); return []; };""", ProviderSandbox.Limits(timeoutMs = 800))
        val malformed = provider("junk", """exports.getSearchPosts = async function(){ return [null, 1, {title: 'Dup', link: '/d'}, {title: 'Dup', link: '/d'}, {link: '/no-title'}]; };""")
        val err = object : MovieSource {   // an Error (not an Exception) from a source must not escape either
            override val id = "p:err"; override val name = "Err"; override val official = false
            override suspend fun rows() = throw StackOverflowError()
            override suspend fun search(query: String): List<MoviePost> = throw OutOfMemoryError("engine")
            override suspend fun info(post: MoviePost) = throw StackOverflowError()
            override suspend fun streams(link: MovieLink) = emptyList<MovieStream>()
        }
        val stuck = object : MovieSource {
            override val id = "p:stuck"; override val name = "Stuck"; override val official = false
            override suspend fun rows() = emptyList<Pair<String, List<MoviePost>>>()
            override suspend fun search(query: String): List<MoviePost> { delay(10 * 60_000); return emptyList() }
            override suspend fun info(post: MoviePost) = throw IllegalStateException()
            override suspend fun streams(link: MovieLink) = emptyList<MovieStream>()
        }
        val t0 = System.currentTimeMillis()
        val r = MovieHub.search(listOf(throws, hangs, malformed, err, stuck), "x", parallel = 5, timeoutMs = 3_000)
        assertTrue("bounded: ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 8_000)
        val by = r.results.associateBy { it.source.id }
        assertNotNull(by.getValue("p:thrower").error); assertNotNull(by.getValue("p:hanger").error); assertNull(by.getValue("p:junk").error)
        assertEquals(listOf("Dup"), r.posts.map { it.title })
        assertEquals(setOf("p:thrower", "p:hanger", "p:err", "p:stuck"), r.failed.map { it.source.id }.toSet())
        // the hanging provider fails on its own sandbox timeout (no exception escapes)
        val h = runCatching { hangs.search("x") }
        assertTrue(h.isFailure); assertTrue(h.exceptionOrNull()!!.message!!.contains("longer than"))
        // the crash log never names the source
        assertEquals("a source failed in a source", CrashLog.redact("My Secret Source failed in mockprov", listOf("My Secret Source", "mockprov")))
    }

    @Test fun memoryHungryProviderGetsAPlainErrorNotACrash() = runBlocking {
        val sb = ProviderSandbox(limits = ProviderSandbox.Limits(memoryBytes = 16L * 1024 * 1024))
        val r = sb.invoke("""exports.getPosts = async function(){ var a = []; for (;;) a.push(['x'.repeat(50000), a.length].join('-')); };""", "getPosts", JSONObject())
        assertFalse(r.ok); assertTrue(r.error, r.error!!.contains("memory"))
        // the same engine instance type still works afterwards
        val ok = sb.invoke("""exports.getPosts = async function(){ return [1, 2]; };""", "getPosts", JSONObject())
        assertTrue(ok.error, ok.ok)
        assertTrue(src("movies/SandboxPrelude.kt").contains("function memChk(){ if (__mem() > 0)"))
    }

    @Test fun hugeOrOddPostersAreDownsampledOrSkipped() {
        assertEquals(1, HomeImages.safeSample(300, 450))
        assertEquals(4, HomeImages.safeSample(2000, 3000))
        assertTrue(HomeImages.safeSample(20_000, 30_000).let { s -> (20_000 / s) * (30_000 / s) <= 1_500_000 && 30_000 / s <= 1080 })
        assertEquals(-1, HomeImages.safeSample(0, 100)); assertEquals(-1, HomeImages.safeSample(400, 40_000)); assertEquals(-1, HomeImages.safeSample(-1, -1))
        val hi = src("ui/HomeImages.kt")
        assertTrue(hi.contains("catch (_: OutOfMemoryError) { null }")); assertTrue(hi.contains("if (total > MAX_BYTES) { big = true; break }"))
    }

    @Test fun crashLogRedactsAndNextLaunchOffersTheReport() {
        val line = CrashLog.exitLine(4, "crash in JS_DefineProperty", "backtrace: libquickjs.so My Secret Source", "provider call getPosts", listOf("My Secret Source"))!!
        assertTrue(line.startsWith("CRASH (previous run, native crash)")); assertFalse(line.contains("My Secret Source")); assertTrue(line.contains("provider call getPosts"))
        assertNull(CrashLog.exitLine(10, "user stopped", null, null, emptyList()))   // not a crash: nothing written
        assertTrue(CrashLog.exitLine(6, "ANR", null, null, emptyList())!!.contains("ANR"))
        val main = src("MainActivity.kt"); val log = src("device/CrashLog.kt")
        assertTrue(main.contains("confirm = \"Send crash report\"")); assertTrue(main.contains("dismiss = \"Not now\""))
        assertTrue(main.contains("CrashLog.shareIntent(this@MainActivity)"))   // local file, the user picks where it goes
        assertTrue(log.contains("getHistoricalProcessExitReasons")); assertTrue(log.contains("file.appendText(if (secrets.isEmpty()) stamped else redact(stamped, secrets))"))
        assertTrue(src("movies/ProviderSandbox.kt").contains("CrashLog.enter(\"provider call"))
    }

    @Test fun oldMoviesTabIsNotReachable() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val uses = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> f.readLines().filter { l -> "MoviesTab(" in l && "fun MoviesTab(" !in l }.map { f.name + ": " + it.trim() } }.toList()
        assertTrue("MoviesTab is still called: $uses", uses.isEmpty())
    }
}
