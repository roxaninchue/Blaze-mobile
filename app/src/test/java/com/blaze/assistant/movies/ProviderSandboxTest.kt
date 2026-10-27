package com.blaze.assistant.movies

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 0.46.2 the provider sandbox against a LOCAL mock provider (MockProviderServer on 127.0.0.1; no real site): posts via axios +
 * cheerio, search via fetch, meta + kvStore, episodes, streams (unsafe links dropped), catalog exports; and the walls: no fs /
 * globalThis, private-network redirects refused, request cap, memory cap, busy-loop timeout.
 */
class ProviderSandboxTest {
    private lateinit var srv: MockProviderServer
    private val root = listOf(File("src/test/resources/movies/mockrepo"), File("app/src/test/resources/movies/mockrepo")).first { it.isDirectory }
    private fun mod(id: String, f: String) = File(root, "dist/$id/$f.js").readText()

    @Before fun up() { srv = MockProviderServer(); UrlGuard.allowPrivateForTests = true }
    @After fun down() { srv.close(); UrlGuard.allowPrivateForTests = false }

    private fun env() = ProviderSandbox.ProviderEnv(mapOf("baseUrl" to srv.base))

    @Test fun postsSearchMetaEpisodesStreamsCatalog() = runBlocking {
        val sb = ProviderSandbox()
        val e = env()
        val posts = sb.invoke(mod("mock", "posts"), "getPosts", JSONObject().put("filter", "/latest").put("page", 1).put("providerValue", "mock"), e)
        assertTrue(posts.error, posts.ok)
        val p = MovieJson.posts(posts.value, "mock")
        assertEquals(listOf("The General", "Nosferatu"), p.map { it.title })
        assertEquals(srv.base + "/film/1", p[0].link)
        assertEquals(srv.base + "/img/film/1.jpg", p[0].image)

        val s = sb.invoke(mod("mock", "posts"), "getSearchPosts", JSONObject().put("searchQuery", "General").put("page", 1).put("providerValue", "mock"), e)
        assertEquals(listOf("The General"), MovieJson.posts(s.value, "mock").map { it.title })

        val meta = sb.invoke(mod("mock", "meta"), "getMeta", JSONObject().put("link", srv.base + "/film/1").put("provider", "mock"), e)
        val info = MovieJson.info(meta.value as JSONObject)!!
        assertEquals("The General", info.title); assertEquals("A train, a war, a chase.", info.synopsis)
        assertEquals(srv.base + "/film/1/play", info.groups.single().direct.single().link)
        assertEquals("\"The General\"", e.kv["last"])   // kvStore kept per provider, host side

        val eps = sb.invoke(mod("mock", "episodes"), "getEpisodes", JSONObject().put("url", srv.base + "/film/2/episodes"), e)
        assertEquals(2, MovieJson.episodes(eps.value).size)

        val st = sb.invoke(mod("mock", "stream"), "getStream", JSONObject().put("link", srv.base + "/film/1/play").put("type", "movie"), e)
        val streams = MovieJson.streams(st.value)
        assertEquals(listOf("Main", "Low", "The General"), streams.map { it.server })   // javascript: link dropped
        assertEquals("en", streams[0].subtitles.single().lang)
        assertEquals(srv.base + "/film/1/play", streams[0].headers["Referer"])
        assertFalse("module saw fs or globalThis", streams.any { it.quality == "leak" })

        val cat = sb.invoke(mod("mock", "catalog"), null, JSONObject(), e)
        val o = cat.value as JSONObject
        assertEquals(2, MovieJson.catalog(o.opt("catalog")).size); assertEquals("Comedy", MovieJson.catalog(o.opt("genres")).single().title)
    }

    @Test fun noFileSystemNoAndroidNoSecrets() = runBlocking {
        val code = """exports.getPosts = async function(){ return [typeof require('fs').readFileSync, typeof globalThis, typeof std, typeof os, typeof Java, typeof android, typeof process]; };"""
        val r = ProviderSandbox().invoke(code, "getPosts", JSONObject(), env())
        assertEquals(List(7) { "undefined" }, (r.value as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun privateNetworkAndRedirectsGuarded() = runBlocking {
        UrlGuard.allowPrivateForTests = false
        val code = """exports.getPosts = async function({providerContext}){ try { await providerContext.axios.get('http://192.168.1.1/'); return 'reached'; } catch (e) { return String(e.message); } };"""
        val r = ProviderSandbox().invoke(code, "getPosts", JSONObject(), env())
        assertTrue(r.value.toString(), r.value.toString().contains("Local-network"))
        assertTrue(UrlGuard.isPrivateHost("10.0.0.1")); assertTrue(UrlGuard.isPrivateHost("localhost")); assertFalse(UrlGuard.isPrivateHost("archive.org"))
        assertNotNull(UrlGuard.check("file:///etc/passwd")); assertNotNull(UrlGuard.check("https://u:p@host.com/")); assertNotNull(UrlGuard.check("content://x"))
    }

    @Test fun requestCapAndMemoryCapAndErrors() = runBlocking {
        val sb = ProviderSandbox(limits = ProviderSandbox.Limits(maxRequests = 3, memoryBytes = 16L * 1024 * 1024))
        val loop = """exports.getPosts = async function({providerContext}){ var n = 0; for (var i = 0; i < 10; i++) { try { await providerContext.axios.get('${'$'}{BASE}/latest'); n++; } catch (e) { return n + ':' + e.message; } } return n; };""".replace("\${BASE}", srv.base)
        val r = sb.invoke(loop, "getPosts", JSONObject(), env())
        assertTrue(r.value.toString(), r.value.toString().startsWith("3:Too many requests"))
        val mem = sb.invoke("""exports.getPosts = async function(){ var a = []; while (true) a.push('x'.repeat(100000) + a.length); };""", "getPosts", JSONObject(), env())
        assertFalse(mem.ok); assertTrue(mem.error, mem.error!!.contains("memory"))
        val missing = sb.invoke("exports.other = 1;", "getPosts", JSONObject(), env())
        assertTrue(missing.error, missing.error!!.contains("no getPosts"))
        val thrown = sb.invoke("exports.getPosts = async function(){ throw new Error('boom'); };", "getPosts", JSONObject(), env())
        assertEquals("boom", thrown.error)
    }

    @Test fun busyLoopTimesOut() = runBlocking {
        val sb = ProviderSandbox(limits = ProviderSandbox.Limits(timeoutMs = 800))
        val t0 = System.currentTimeMillis()
        val r = sb.invoke(mod("hang", "posts"), "getPosts", JSONObject(), env())
        assertFalse(r.ok); assertTrue(r.error, r.error!!.contains("longer than"))
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
        // 0.46.6: one engine at a time; the spinning one keeps its lane, so later tests get a fresh one (test-only reset)
        assertEquals(ProviderSandbox.BUSY, ProviderSandbox(limits = ProviderSandbox.Limits(timeoutMs = 300)).invoke("exports.getPosts = async function(){ return 1; };", "getPosts").error)
        ProviderSandbox.Gate.resetForTests()
    }
}
