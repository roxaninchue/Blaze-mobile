package com.blaze.assistant.browser

import com.blaze.assistant.agent.LongChat
import com.blaze.assistant.agent.LoopGuard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.29 browser tasks: what the model reads of a page. The fixture is a real [BrowserScripts.READ] result captured in
 * headless Chromium (a shop page, 52 elements, 40 products), so these check the reader end to end on the Kotlin side.
 */
class BrowserReadTest {
    private val fixture: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResource("browser/read-fixture.json")!!.readText())
    }
    private val read get() = fixture.getJSONObject("read")

    @Test fun readFitsTheBudgetAndKeepsWhatIsOnScreen() {
        val out = BrowserRead.format(read)
        assertTrue("${out.length}", out.length <= BrowserRead.BUDGET)
        assertTrue(out.startsWith("Page: Shop \u2014 https://shop.example/all"))
        assertTrue(out.contains("Scroll: top"))
        assertTrue(out.contains("More below"))
        // The products on screen, with prices, and the fields with their real labels (not name attributes).
        assertTrue(out.contains("Price \u00a310.99"))
        assertTrue(out.contains("[5] field \"Search the shop\""))
        assertTrue(out.contains("[6] field:number \"Quantity\""))
        assertTrue(out.contains("[8] select \"Country\""))
        assertTrue(out.contains("options:"))
        // Elements below the screen are marked, and text below follows.
        assertTrue(out.contains("\u2193 ["))
        assertTrue(out.contains("Below:"))
    }

    @Test fun onScreenElementsComeBeforeOffScreen() {
        val out = BrowserRead.format(read)
        val firstBelow = out.indexOf("\u2193 [")
        val lastOn = out.lastIndexOf("\n[")
        assertTrue(firstBelow > 0 && lastOn in 0 until firstBelow)
    }

    @Test fun neverCutBehindTheModelsBack() {
        // Browser results stay whole in chat history (LongChat used to keep only the first 40 and last 20 lines).
        listOf("browser_read", "browser_open", "browser_click", "browser_find", "phone_read").forEach { assertTrue(it, LongChat.keeps(it)) }
        assertFalse(LongChat.keeps("web_search"))
    }

    @Test fun compactSummaryAfterAnAction() {
        val out = BrowserRead.format(read, BrowserRead.AFTER, compact = true)
        assertTrue(out.length <= BrowserRead.AFTER)
        assertFalse(out.contains("Below:"))
        assertTrue(out.contains("On screen:"))
    }

    @Test fun hugePageStillFits() {
        val r = JSONObject(read.toString())
        r.put("on", "word ".repeat(5000)).put("below", "more ".repeat(5000))
        val items = JSONArray(); for (i in 0 until 90) items.put(JSONObject().put("id", i).put("kind", "link").put("label", "Link number $i with a long label").put("w", if (i < 40) 0 else 1))
        r.put("items", items)
        val out = BrowserRead.format(r)
        assertTrue(out.length <= BrowserRead.BUDGET)
        assertTrue(out.contains("more elements"))
        assertTrue(out.contains("[0] link"))
    }

    @Test fun items() {
        assertEquals("[3] checkbox \"Remember me\" (checked)", BrowserRead.item(JSONObject().put("id", 3).put("kind", "checkbox").put("label", "Remember me").put("checked", true)))
        assertEquals("\u2193 [9] field \"Email\" = \"a@b.co\"", BrowserRead.item(JSONObject().put("id", 9).put("kind", "field").put("label", "Email").put("value", "a@b.co").put("w", 1)))
        assertEquals("[1] button \"Pay\" (disabled)", BrowserRead.item(JSONObject().put("id", 1).put("kind", "button").put("label", "Pay").put("disabled", true)))
    }

    @Test fun scrollLine() {
        assertEquals("Scroll: the whole page fits on screen.", BrowserRead.scrollLine(0, 700, 780))
        assertTrue(BrowserRead.scrollLine(0, 5435, 780).startsWith("Scroll: top"))
        assertTrue(BrowserRead.scrollLine(4655, 5435, 780).startsWith("Scroll: bottom"))
        assertFalse(BrowserRead.scrollLine(4655, 5435, 780).contains("More below"))
        assertTrue(BrowserRead.scrollLine(2000, 5435, 780).contains("% down"))
    }

    @Test fun clipAtWordEnds() {
        assertEquals("short", BrowserRead.clip("short", 10))
        val c = BrowserRead.clip("alpha beta gamma delta epsilon", 20)
        assertTrue(c.length <= 20); assertTrue(c.endsWith("\u2026")); assertFalse(c.contains("gam\u2026"))
    }

    @Test fun findAnswer() {
        val out = BrowserRead.found("Product 33", fixture.getJSONObject("find"))
        assertTrue(out, out.startsWith("Found \u201cProduct 33\u201d"))
        assertTrue(out.contains("\u2192 ["))
        assertTrue(BrowserRead.found("zzz", JSONObject().put("hits", JSONArray())).contains("isn't on this page"))
    }

    @Test fun honestProblems() {
        assertNull(BrowserRead.problem(fixture.getJSONObject("covered"), "click"))
        assertNotNull(BrowserRead.problem(null, "click"))
        assertTrue(BrowserRead.problem(JSONObject().put("r", "missing"), "click")!!.contains("no such element"))
        val cov = BrowserRead.problem(JSONObject().put("r", "covered").put("l", "Search").put("by", "Accept cookies").put("byId", 12), "click")!!
        assertTrue(cov.contains("covered by \"Accept cookies\" [12]"))
        val sel = BrowserRead.problem(JSONObject().put("r", "select").put("l", "Country").put("opts", JSONArray(listOf("Pick", "Zambia"))), "type")!!
        assertTrue(sel.contains("browser_select") && sel.contains("Zambia"))
        assertTrue(BrowserRead.problem(JSONObject().put("r", "mismatch").put("l", "Quantity").put("got", "").put("type", "number"), "type")!!.contains("didn't keep it"))
        assertTrue(BrowserRead.problem(JSONObject().put("r", "badkey"), "press")!!.contains("Enter"))
    }

    @Test fun scriptsDoTheRightThings() {
        // Stable ids from a WeakMap, stale attributes cleared, real pointer input, verified typing, secrets hidden.
        assertTrue(BrowserScripts.READ.contains("WeakMap"))
        assertTrue(BrowserScripts.READ.contains("removeAttribute('data-bz')"))
        assertFalse(BrowserScripts.READ.contains("\$COMMON"))
        val click = BrowserScripts.click(4, null)
        assertTrue(click.contains("pointerdown") && click.contains("elementFromPoint") && click.contains("'covered'"))
        assertTrue(click.trimEnd().endsWith("(4, \"\")"))
        assertTrue(BrowserScripts.type(null, "Email", "a\"b", false, false).contains("'mismatch'"))
        assertTrue(BrowserScripts.type(null, "Email", "a\"b", false, false).contains("\"a\\\"b\""))   // quoted, not injected
        assertTrue(BrowserScripts.READ.contains("(filled, hidden)"))
        assertTrue(BrowserScripts.select(null, "Country", "Zambia").contains("HTMLSelectElement"))
        assertTrue(BrowserScripts.key("Enter").contains("requestSubmit"))
    }

    @Test fun toolsAreRegistered() {
        val defs = BrowserTools.definitions().map { it.getJSONObject("function").getString("name") }.toSet()
        listOf("browser_select", "browser_key", "browser_wait", "browser_find").forEach { assertTrue(it, it in defs && it in BrowserTools.names) }
        assertEquals(defs, BrowserTools.names)
    }

    @Test fun browserTasksCarryBrowserTools() {
        fun t(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n))
        val all = JSONArray(listOf("set_alarm", "browser_open", "web_search", "run_shell", "browser_click", "ask_user", "memory_search").map(::t))
        assertFalse(BrowserFocus.active(listOf("web_search")))
        assertFalse(BrowserFocus.active(listOf("browser_vpn")))
        assertTrue(BrowserFocus.active(listOf("web_search", "browser_open")))
        assertFalse(BrowserFocus.active(listOf("browser_open", "find_tool")))   // asked for another tool: full list again
        val sub = BrowserFocus.subset(all)
        val names = (0 until sub.length()).map { sub.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("browser_open", "web_search", "browser_click", "ask_user", "memory_search"), names)
    }

    @Test fun loopGuardLetsPagingThrough() {
        listOf("browser_read", "browser_scroll", "browser_find", "browser_wait").forEach { assertTrue(it, it in LoopGuard.EXEMPT) }
        assertTrue("browser_click" in LoopGuard.PAGE_ACTS)
    }
}
