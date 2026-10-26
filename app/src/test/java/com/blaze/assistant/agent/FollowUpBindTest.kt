package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.29 (stale "Yes"): a short yes answers only the newest assistant message. The device case: Blaze offered to
 * unsubscribe the user from a shop's emails; later Halo offered to add a song to their music; the user said "Yes" and
 * the unsubscribe was acted on and reported as done.
 */
class FollowUpBindTest {
    private val now = 1_000_000_000L
    private val unsubscribeOffer = FollowUpBind.Said("Blaze", "You get a lot of Shopify promo emails. Want me to unsubscribe you from them?", now - 6 * 60_000L)
    private val songOffer = FollowUpBind.Said("Halo", "That's \u201cMidnight City\u201d by M83. Want me to add it to your music?", now - 40_000L)

    @Test fun shortYesIsRecognised() {
        for (y in listOf("Yes", "yes!", "Yeah", "ok", "Okay.", "sure", "yes please", "Sure, go ahead", "do it", "go for it", "\ud83d\udc4d", "[m12] Yes", "ndiyo", "oui", "Sawa"))
            assertTrue(y, FollowUpBind.isShortYes(y))
        for (n in listOf("no", "yes but not on Shopify", "unsubscribe me from Shopify", "what song is this?", "", "continue", "yes, and email Sam the summary too"))
            assertFalse(n, FollowUpBind.isShortYes(n))
    }

    /** The regression: older offer A (unsubscribe) still "pending", newer offer B (song); "Yes" → B only, never A. */
    @Test fun yesBindsToTheNewestOfferNeverAnOlderOne() {
        // The newest assistant message is the song offer (the unsubscribe offer is older; only the newest is passed).
        val b = FollowUpBind.bind("[m31] Yes", songOffer, now)
        assertEquals(FollowUpBind.Kind.BOUND, b.kind)
        assertEquals(songOffer, b.offer)
        val block = FollowUpBind.block(b)
        assertTrue(block.contains("Midnight City") && block.contains("Halo"))
        assertFalse(block.contains("Shopify") || block.contains("unsubscribe you"))
        assertTrue(block.contains("NOT accepted"))
        // The goal pinned for the task is the song, not the older request / offer.
        val goal = GoalPin.of("Yes", listOf("unsubscribe me from those Shopify emails later maybe", "what's this song?"), b)
        assertTrue(goal.request, goal.request.contains("Midnight City"))
        assertFalse(goal.request, goal.request.contains("Shopify"))
        // Acting on the older offer needs the card, whatever Autopilot says.
        assertNotNull(FollowUpBind.toolGate(b, "browser_click", """{"text":"Unsubscribe"}"""))
        assertNotNull(FollowUpBind.toolGate(b, "gmail_send", """{"to":"unsubscribe@shopify.com"}"""))
        assertTrue(FollowUpBind.mustConfirm(b, "Click \u201cUnsubscribe\u201d on shopify.com? This looks like it pays, sends, posts or deletes something."))
        assertEquals(ConfirmGate.Decision.SHOW, ConfirmGate.decide(false, false, false, autopilot = true, tainted = false, unnamed = true))
        // Doing the song is fine.
        assertNull(FollowUpBind.toolGate(b, "spotify_play", """{"query":"Midnight City M83"}"""))
        assertNull(FollowUpBind.toolGate(b, "run_plan", """{"steps":[{"tap":"Add to playlist"}]}"""))
        assertFalse(FollowUpBind.mustConfirm(b, "Add \u201cMidnight City\u201d to Liked Songs?"))
    }

    @Test fun anOfferThatNamedTheActionMayDoIt() {
        val b = FollowUpBind.bind("yes", unsubscribeOffer.copy(at = now - 30_000L), now)
        assertEquals(FollowUpBind.Kind.BOUND, b.kind)
        assertNull(FollowUpBind.toolGate(b, "browser_click", """{"text":"Unsubscribe"}"""))
        assertFalse(FollowUpBind.mustConfirm(b, "Click \u201cUnsubscribe\u201d on shopify.com?"))
        // …but not a purchase it never mentioned.
        assertNotNull(FollowUpBind.toolGate(b, "browser_click", """{"text":"Buy now"}"""))
    }

    @Test fun aStaleOrMissingOfferMakesTheModelAsk() {
        val stale = FollowUpBind.bind("Yes", unsubscribeOffer.copy(at = now - 11 * 60_000L), now)
        assertEquals(FollowUpBind.Kind.STALE, stale.kind)
        assertTrue(FollowUpBind.block(stale).contains("Ask the user"))
        assertTrue(FollowUpBind.block(stale).contains("Do NOT act on any earlier offer"))
        assertNotNull(FollowUpBind.toolGate(stale, "browser_click", """{"text":"Unsubscribe"}"""))
        assertEquals("Yes", GoalPin.of("Yes", listOf("unsubscribe me from Shopify"), stale).request)
        val none = FollowUpBind.bind("ok", null, now)
        assertEquals(FollowUpBind.Kind.STALE, none.kind)
        assertNull(none.offer)
        // Not a short yes: nothing changes.
        val full = FollowUpBind.bind("unsubscribe me from Shopify", songOffer, now)
        assertEquals(FollowUpBind.Kind.NONE, full.kind)
        assertEquals("", FollowUpBind.block(full))
        assertNull(FollowUpBind.toolGate(full, "browser_click", """{"text":"Unsubscribe"}"""))
        assertFalse(FollowUpBind.mustConfirm(null, "Send email?"))
    }

    @Test fun ruleIsInThePrompt() {
        assertTrue(FollowUpBind.RULE.contains("newest message"))
        assertTrue(FollowUpBind.RULE.contains("unsubscribe") && FollowUpBind.RULE.contains("buy"))
    }

    /** GoalPin read none of the main chat's messages: each starts with its "[m7] " id and was skipped as a Blaze note. */
    @Test fun userTextsReadTaggedMessages() {
        val h = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "[m3] what's this song?"))
            .put(JSONObject().put("role", "assistant").put("content", "Midnight City."))
            .put(JSONObject().put("role", "user").put("content", "[m5 \u00b7 sent while you were working] and who sings it"))
            .put(JSONObject().put("role", "user").put("content", "[Blaze check] not a user request"))
        assertEquals(listOf("what's this song?", "and who sings it"), GoalPin.userTexts(h))
    }

    @Test fun bareYesClaimsAreChecked() {
        // "You're unsubscribed" after a bare yes with nothing run is unbacked.
        assertEquals("unsubscribe", ActionProof.unbacked("Done! You're unsubscribed from Shopify's emails.", emptyList(), "Yes")?.id)
        assertEquals("purchase", ActionProof.unbacked("I've ordered it for you.", emptyList(), "ok")?.id)
        assertEquals("done", ActionProof.unbacked("Done.", emptyList(), "yes")?.id)
        assertNull(ActionProof.unbacked("Done.", listOf("spotify_play"), "yes"))
        assertEquals("open", ActionProof.unbacked("I opened Gmail for you.", emptyList(), "sure")?.id)
        // A verified tool backs it.
        assertNull(ActionProof.unbacked("You're unsubscribed from Shopify's emails.", listOf("browser_click"), "yes"))
        // ClaimCheck: a bare "Done." with only failed results is unbacked; a booking claim is checked without Blaze Phone.
        val failed = listOf(ClaimCheck.Done("spotify_play", ToolVerify.Status.FAILED, "error"))
        assertTrue(ClaimCheck.review("Done.", failed, "yes") is ClaimCheck.Outcome.Unbacked)
        assertTrue(ClaimCheck.review("I've booked it.", emptyList(), "yes") !is ClaimCheck.Outcome.Ok)
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("Done.", listOf(ClaimCheck.Done("spotify_play", ToolVerify.Status.VERIFIED)), "yes"))
    }
}
