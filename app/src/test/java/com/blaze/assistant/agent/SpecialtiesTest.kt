package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/**
 * 0.45.23 Specialties client against a fake gateway, using the exact shapes of gateway 0.8.45 src/specialties.ts
 * (handleSpecialties) and the live 401 body.
 */
class SpecialtiesTest {

    private val id = "sp_0123456789abcdef0123"

    private val listBody = """{"object":"list","data":[
        {"id":"$id","name":"Booking","kind":"booking","version":3,"uses":5,"steps":4,"rules":2,"updated_at":"2026-10-23T17:02:11.000Z"},
        {"id":"sp_ffffffffffffffffffff","name":"Research","kind":"research","version":1,"uses":0,"steps":3,"rules":0,"updated_at":"2026-10-22T08:00:00.000Z"}],
        "general_rules":["Always reply in British English"]}"""

    private val detailBody = """{"object":"specialty","id":"$id","name":"Booking","kind":"booking","trigger":"book me a table for two",
        "steps":["Find the venue.","Check the times.","Confirm before paying."],"rules":["Always pick a window seat","Never book before 7pm"],
        "version":3,"uses":5,"created_at":"2026-10-20T10:00:00.000Z","updated_at":"2026-10-23T17:02:11.000Z"}"""

    private val unauthorized = """{"error":{"message":"Sign in to Blaze: send your Blaze account token as 'Authorization: Bearer bz_…'","type":"invalid_api_key","code":"invalid_api_key","request_id":"a4f2546bfa09efc6"}}"""
    private val notFound = """{"error":{"message":"No such specialty.","type":"not_found","code":"not_found"}}"""

    /** Answers by "METHOD path"; records every call; [offline] throws like a dead connection. */
    private class Fake(val routes: Map<String, Pair<Int, String>>, val offline: Boolean = false) : CloudRuns.Http {
        val calls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override fun send(method: String, path: String, body: String?, headers: Map<String, String>): CloudRuns.Response {
            calls += "$method $path"; this.headers += headers
            if (offline) throw IOException("Unable to resolve host")
            val (code, text) = routes["$method $path"] ?: (404 to """{"error":{"message":"Not found","type":"not_found","code":"not_found"}}""")
            return CloudRuns.Response(code, "application/json", BufferedReader(StringReader(text)))
        }
    }

    private fun client(f: Fake, signedIn: Boolean = true) = Specialties.Client(f) { signedIn }

    @Test fun parsesList() {
        val l = Specialties.parseList(JSONObject(listBody))
        assertEquals(2, l.items.size)
        val s = l.items[0]
        assertEquals(id, s.id); assertEquals("Booking", s.name); assertEquals("booking", s.kind)
        assertEquals(3, s.version); assertEquals(5, s.uses); assertEquals(4, s.steps); assertEquals(2, s.rules)
        assertEquals(listOf("Always reply in British English"), l.generalRules)
        assertFalse(l.isEmpty)
        assertEquals("Used 5 times \u00b7 2 rules \u00b7 updated 23 Oct 2026", Specialties.subLine(s))
        assertEquals("Not used yet \u00b7 updated 22 Oct 2026", Specialties.subLine(l.items[1]))
    }

    @Test fun parsesEmptyAndOddLists() {
        assertTrue(Specialties.parseList(JSONObject("""{"object":"list","data":[],"general_rules":[]}""")).isEmpty)
        assertTrue(Specialties.parseList(JSONObject("{}")).isEmpty)
        // A row without an id is dropped; full arrays (older drafts) count as their length.
        val l = Specialties.parseList(JSONObject("""{"data":[{"name":"x"},{"id":"$id","steps":["a","b"],"rules":["r"]}]}"""))
        assertEquals(1, l.items.size); assertEquals(2, l.items[0].steps); assertEquals(1, l.items[0].rules)
        assertEquals("Specialty", l.items[0].name)
    }

    @Test fun parsesDetailAndGeneral() {
        val d = Specialties.parseDetail(JSONObject(detailBody))
        assertEquals("book me a table for two", d.trigger)
        assertEquals(3, d.steps.size); assertEquals("Confirm before paying.", d.steps[2])
        assertEquals(listOf("Always pick a window seat", "Never book before 7pm"), d.rules)
        assertFalse(d.isGeneral)
        val g = Specialties.parseDetail(JSONObject("""{"id":"general","name":"General rules","rules":["Be brief"]}"""))
        assertTrue(g.isGeneral); assertTrue(g.steps.isEmpty()); assertEquals(listOf("Be brief"), g.rules)
    }

    @Test fun listOk() {
        val f = Fake(mapOf("GET /specialties" to (200 to listBody)))
        val r = client(f).list()
        assertTrue(r is Specialties.Result.Ok)
        assertEquals(2, (r as Specialties.Result.Ok).value.items.size)
        assertEquals(listOf("GET /specialties"), f.calls)
        // Never asks for the owner-debug fields (specialty names in run events).
        assertFalse(f.headers.any { h -> h.keys.any { it.equals("X-Blaze-Debug", true) } })
    }

    @Test fun getAndDeletePaths() {
        val f = Fake(mapOf(
            "GET /specialties/$id" to (200 to detailBody),
            "DELETE /specialties/$id" to (200 to """{"id":"$id","deleted":true}"""),
            "DELETE /specialties/$id/rules/1" to (200 to """{"id":"$id","deleted_rule":1,"version":4}"""),
            "DELETE /specialties/general/rules/0" to (200 to """{"id":"general","deleted_rule":0}"""),
            "DELETE /specialties/general" to (200 to """{"id":"general","deleted":true}"""),
        ))
        val c = client(f)
        assertEquals("Booking", (c.get(id) as Specialties.Result.Ok).value.name)
        assertTrue(c.deleteRule(id, 1) is Specialties.Result.Ok)
        assertTrue(c.delete(id) is Specialties.Result.Ok)
        assertTrue(c.deleteRule("general", 0) is Specialties.Result.Ok)
        assertTrue(c.delete("general") is Specialties.Result.Ok)
        assertEquals(listOf("GET /specialties/$id", "DELETE /specialties/$id/rules/1", "DELETE /specialties/$id",
            "DELETE /specialties/general/rules/0", "DELETE /specialties/general"), f.calls)
    }

    @Test fun unauthorizedIsSignedOut() {
        val f = Fake(mapOf("GET /specialties" to (401 to unauthorized), "GET /specialties/$id" to (401 to unauthorized),
            "DELETE /specialties/$id" to (401 to unauthorized)))
        val c = client(f)
        assertEquals(Specialties.Result.SignedOut, c.list())
        assertEquals(Specialties.Result.SignedOut, c.get(id))
        assertEquals(Specialties.Result.SignedOut, c.delete(id))
    }

    @Test fun noSessionMakesNoCall() {
        val f = Fake(mapOf("GET /specialties" to (200 to listBody)))
        assertEquals(Specialties.Result.SignedOut, client(f, signedIn = false).list())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun notFound() {
        val f = Fake(mapOf("GET /specialties/$id" to (404 to notFound), "DELETE /specialties/$id" to (404 to notFound),
            "DELETE /specialties/$id/rules/9" to (404 to """{"error":{"message":"No such rule.","type":"not_found","code":"not_found"}}""")))
        val c = client(f)
        assertEquals(Specialties.Result.NotFound, c.get(id))
        assertEquals(Specialties.Result.NotFound, c.delete(id))
        assertEquals(Specialties.Result.NotFound, c.deleteRule(id, 9))
        // Malformed ids and negative rules never reach the network (the gateway would 404 them anyway).
        assertEquals(Specialties.Result.NotFound, c.get("../runs"))
        assertEquals(Specialties.Result.NotFound, c.delete("sp_XYZ"))
        assertEquals(Specialties.Result.NotFound, c.deleteRule(id, -1))
        assertEquals(3, f.calls.size)
    }

    @Test fun listOnOlderGatewayIsUnavailable() {
        val f = Fake(emptyMap())
        assertEquals(Specialties.Result.Unavailable, client(f).list())
        assertEquals(Specialties.Result.Unavailable, client(Fake(mapOf("GET /specialties" to (405 to "")))).list())
    }

    @Test fun offline() {
        val c = client(Fake(emptyMap(), offline = true))
        assertEquals(Specialties.Result.Offline, c.list())
        assertEquals(Specialties.Result.Offline, c.get(id))
        assertEquals(Specialties.Result.Offline, c.delete(id))
        assertEquals(Specialties.Result.Offline, c.deleteRule(id, 0))
    }

    @Test fun serverErrorsAndBadBodies() {
        val c = client(Fake(mapOf(
            "GET /specialties" to (503 to """{"error":{"message":"x","type":"server_error","code":"not_configured"}}"""),
            "GET /specialties/$id" to (200 to "<html>oops</html>"),
        )))
        assertEquals(Specialties.Result.Failed(503, "not_configured"), c.list())
        assertEquals(Specialties.Result.Failed(200, "bad_response"), c.get(id))
        assertEquals("http_500", Specialties.errorCode(500, "not json"))
    }

    @Test fun plainLinesNeverEmptyForFailures() {
        listOf(Specialties.Result.SignedOut, Specialties.Result.NotFound, Specialties.Result.Unavailable, Specialties.Result.Offline,
            Specialties.Result.Failed(500, "x"), Specialties.Result.Failed(429, "rate_limited"), Specialties.Result.Failed(400, "bad")).forEach {
            val t = Specialties.plain(it)
            assertTrue(t, t.isNotBlank() && !t.contains("specialist", ignoreCase = true))
        }
        assertEquals("", Specialties.plain(Specialties.Result.Ok(Unit)))
    }

    @Test fun dates() {
        assertEquals("23 Oct 2026", Specialties.shortDate("2026-10-23T17:02:11.000Z"))
        assertEquals("1 Jan 2027", Specialties.shortDate("2027-01-01"))
        assertEquals("", Specialties.shortDate(""))
        assertEquals("", Specialties.shortDate("2026-13-01"))
    }

    @Test fun ids() {
        assertTrue(Specialties.validId(id)); assertTrue(Specialties.validId("general"))
        assertFalse(Specialties.validId("sp_0123")); assertFalse(Specialties.validId("SP_0123456789ABCDEF0123")); assertFalse(Specialties.validId(""))
    }

    @Test fun settingsPageIsReachable() {
        assertTrue(com.blaze.assistant.ui.settingsPages.any { it.id == "specialties" })
        assertEquals("specialties", settingsPageFor("specialties"))
        assertEquals("specialties", settingsPageFor("specialty"))
        // The Agents page keeps its old id.
        assertEquals("specialists", settingsPageFor("specialists"))
    }

    @Test fun pillsStayGeneral() {
        // Gateway 0.8.45 run events: specialty_loaded / specialty_updated + step{kind:"specialty"}; private _name never shown.
        val m = StepPill.Machine(showAfterMs = 0)
        m.begin(0)
        m.onEvent(JSONObject().put("type", "specialty_loaded").put("text", "Using a specialty").put("_name", "Booking"))
        assertEquals("Using a specialty", m.visible(1))
        m.onEvent(JSONObject().put("type", "step").put("kind", "specialty").put("text", "Updating a specialty"))
        assertEquals("Updating a specialty", m.visible(2))
        m.onEvent(JSONObject().put("type", "step").put("kind", "specialty").put("text", "Using Booking specialty"))
        assertEquals("Using a specialty", m.visible(3))
    }
}
