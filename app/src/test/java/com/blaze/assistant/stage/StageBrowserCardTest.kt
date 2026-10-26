package com.blaze.assistant.stage

import com.blaze.assistant.agent.ClaimGuard
import com.blaze.assistant.agent.InAppRoute
import com.blaze.assistant.agent.ViewRouter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.46.1 the browser card. Report: "pull up browser card" opened the full-screen browser and Blaze said "Browser's open, tap Exit"
 * with no card. Now browser requests go to stage_show(kind=browser) on the Stage; the full view only on "full screen" / "full view";
 * and an "open" claim with no card behind it is caught.
 */
class StageBrowserCardTest {
    @Test fun browserIsAStageKind() {
        assertEquals(StageKind.BROWSER, StageKind.of("browser"))
        assertEquals(StageKind.BROWSER, StageKind.of("web"))
        assertEquals("browser", StageKind.BROWSER.fullView)
        assertEquals(StageSlot.TOP, StageReducer.defaultSlot(StageKind.BROWSER))
        val (a, err) = StageTools.parse(StageTools.SHOW, JSONObject().put("kind", "browser").put("url", "example.com"))
        assertEquals("", err)
        val show = a as StageAction.Show
        assertEquals(StageKind.BROWSER, show.kind); assertEquals(StageMode.HERO, show.mode); assertEquals("example.com", show.props["url"])
        assertTrue(StageTools.RULE.contains("kind=browser"))
    }

    @Test fun cardStateOneBrowserHeroToWidgetAndBack() {
        val r1 = StageReducer.reduce(StageState(), StageAction.Show(StageKind.BROWSER))
        val id = r1.id!!
        assertEquals(StageKind.BROWSER, r1.state.hero?.kind)
        // A second "show the browser" updates the same card (one shared WebView), never a second one.
        val r2 = StageReducer.reduce(r1.state, StageAction.Show(StageKind.BROWSER, mapOf("url" to "news.com")))
        assertEquals(id, r2.id); assertEquals(1, r2.state.items.count { it.kind == StageKind.BROWSER })
        // Collapsed = a widget (the card then draws no WebView); tap = big again; it survives the saved layout.
        val small = StageReducer.reduce(r2.state, StageAction.Shrink(id)).state
        assertEquals(StageMode.WIDGET, small.byId(id)!!.mode)
        assertEquals(StageMode.HERO, StageReducer.reduce(small, StageAction.Expand(id)).state.byId(id)!!.mode)
        val back = StageTools.decode(StageTools.encode(small))
        assertEquals(StageKind.BROWSER, back.byId(id)!!.kind); assertEquals("news.com", back.byId(id)!!.props["url"])
        // Another hero turns the browser into a widget (it keeps its page).
        val t = StageReducer.reduce(r2.state, StageAction.Show(StageKind.TIMER, mapOf("minutes" to "5"))).state
        assertEquals(StageMode.WIDGET, t.byId(id)!!.mode)
    }

    @Test fun routing_browserCardIsTheStageNotTheFullView() {
        for (t in listOf("pull up browser card", "Pull up the browser card", "open the browser", "open browser", "show me the browser",
                "bring up the browser", "browser card", "Blaze, open the browser please"))
            assertEquals(t, InAppRoute.Route.Stage("browser"), InAppRoute.match(t))
        for (t in listOf("open the browser full screen", "open browser in full view", "full screen browser"))
            assertEquals(t, InAppRoute.Route.View("browser"), InAppRoute.match(t))
        // Not a bare "open the browser": the model takes it (with the Stage hint for card words).
        assertNull(InAppRoute.match("open the browser and search for flights to Lusaka"))
        assertEquals("stage", ViewRouter.route("can you pull up the browser card and look up the weather")?.id)
    }

    @Test fun openClaimWithNoCardIsCaught() {
        val reply = "Browser's open \u2014 tap Exit when you're done."
        assertEquals(setOf("browser"), ClaimGuard.cardClaims(reply).keys)
        assertEquals("browser", ClaimGuard.unbackedCard(reply, emptyList(), emptySet()))
        // Backed: stage_show browser this turn, or the card is already on the Stage.
        assertNull(ClaimGuard.unbackedCard(reply, listOf(ClaimGuard.Call("stage_show", JSONObject().put("kind", "browser"))), emptySet()))
        assertNull(ClaimGuard.unbackedCard(reply, emptyList(), setOf("browser")))
        // A stage_show of another kind doesn't back it.
        assertEquals("browser", ClaimGuard.unbackedCard(reply, listOf(ClaimGuard.Call("stage_show", JSONObject().put("kind", "timer"))), emptySet()))
        for (r in listOf("The browser card is up.", "Opened the browser.", "Here's the browser.", "The browser is on your screen now."))
            assertEquals(r, "browser", ClaimGuard.unbackedCard(r, emptyList(), emptySet()))
        for (r in listOf("I couldn't open the browser card.", "The browser isn't open yet.", "Want me to open the browser?"))
            assertNull(r, ClaimGuard.unbackedCard(r, emptyList(), emptySet()))
        assertTrue(ClaimGuard.cardNudge("browser").contains("stage_show kind=browser"))
        assertTrue(ClaimGuard.cardHonest(reply, "browser").startsWith("The browser card isn't on the screen"))
    }
}
