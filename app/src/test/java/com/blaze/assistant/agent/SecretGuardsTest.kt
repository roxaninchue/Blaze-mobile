package com.blaze.assistant.agent

import com.blaze.assistant.agent.SecretTestKit.CF
import com.blaze.assistant.agent.SecretTestKit.GL
import com.blaze.assistant.agent.SecretTestKit.SK
import com.blaze.assistant.agent.SecretTestKit.GHP
import com.blaze.assistant.agent.SecretTestKit.AKIA
import com.blaze.assistant.agent.SecretTestKit.JWT
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.6 Secure form: the post-generation guard (chat asks -> Secure Form) and paste detection (true and false positives). */
class SecretGuardsTest {
    private val known = setOf(SecretRules.TOOL, "web_search")

    /** The 0.46.3 reply from the bug report (Cloudflare + GitLab tokens, "one message each"). */
    private val seen = "Yes — send them over, one message each. I'll keep them on the phone and never repeat them back in chat.\n\n" +
        "Two things that save us a round trip:\n- Scope them tight. For Cloudflare, tick only what we need (Workers/Pages, and DNS if we're touching domains). " +
        "For GitLab, api plus write_repository on the projects we'll push to — nothing account-wide.\n- Tell me the account/zone each one belongs to."

    @Test fun reportedReplyBecomesTwoSecureForms() {
        val user = "Can I give you my Cloudflare and GitLab API tokens so you can deploy?"
        assertTrue(SecretAsk.asks(seen, user))
        val calls = SecretAsk.decide(seen, user, false, known)
        assertNotNull(calls); calls!!
        assertEquals(2, calls.length())
        val a0 = JSONObject(calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
        val a1 = JSONObject(calls.getJSONObject(1).getJSONObject("function").getString("arguments"))
        assertEquals(SecretRules.TOOL, calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("cloudflare", a0.getString("service")); assertEquals("gitlab", a1.getString("service"))
        assertEquals("api_key", a0.getString("kind"))
    }

    @Test fun guardRewritesAsks() {
        for (r in listOf(
            "Please paste your OpenAI API key here and I'll set it up.",
            "Send me your GitHub token and I'll push the branch.",
            "What's your password for the router?",
            "Type the 6-digit verification code you got by SMS here.",
            "To pay, give me your card number, expiry and CVC.",
            "Drop the token in the chat when you have it.",
        )) assertTrue(r, SecretAsk.asks(r))
        val card = SecretAsk.wants("To pay, give me your card number, expiry and CVC.")
        assertEquals(SecretVault.Kind.CARD, card.single().kind)
        assertEquals(SecretVault.Kind.CODE, SecretAsk.wants("Type the 6-digit verification code you got by SMS here.").single().kind)
    }

    @Test fun guardLeavesOtherRepliesAlone() {
        for (r in listOf(
            "I'll open a secure form for your Cloudflare token.",
            "Never share your password with anyone, including me.",
            "Send them over and I'll make the collage.",            // photos, nothing secret in the turn
            "Your token was saved securely; deploying now.",
            "You can revoke the token in the GitLab dashboard any time.",
            "I sent the email with the report.",
            "Reset your password from the sign-in page, then tell me when it's done.",
        )) assertFalse(r, SecretAsk.asks(r, "make a collage from these photos"))
        assertNull(SecretAsk.decide(seen, "tokens", guarded = true, known = known))
        assertNull(SecretAsk.decide(seen, "tokens", guarded = false, known = setOf("web_search")))
    }

    // ---------- paste detection ----------
    private fun hit(t: String) = PasteGuard.detect(t)

    @Test fun pasteTruePositives() {
        val cases = mapOf(
            GL to "gitlab",
            SK to "openai",
            GHP to "github",
            AKIA to "aws",
            JWT to "",
        )
        for ((v, svc) in cases) {
            val h = hit("here you go: $v thanks")
            assertEquals(v, 1, h.size); assertEquals(v, v, h[0].value("here you go: $v thanks")); assertEquals(v, svc, h[0].service)
        }
        val cf = hit("my cloudflare token is $CF")
        assertEquals(1, cf.size); assertEquals("cloudflare", PasteGuard.serviceFor(cf[0], "my cloudflare token is $CF"))
        assertEquals(CF, cf[0].value("my cloudflare token is $CF"))
        val bare = hit(CF)
        assertEquals(1, bare.size)
        assertEquals(SecretVault.Kind.CARD, hit("card 4242 4242 4242 4242 exp 12/29").single().kind)
        val pw = hit("my password is Tr0ub4dor&3")
        assertEquals(SecretVault.Kind.LOGIN, pw.single().kind); assertEquals("Tr0ub4dor&3", pw.single().value("my password is Tr0ub4dor&3"))
    }

    @Test fun pasteFalsePositives() {
        for (t in listOf(
            "commit eb5bfae769129e71151cc8914df70c17ac71285c is merged",
            "sha256 8c925f54faaec875bcf606ed7f7fb7a7fe15a2e12cf8c45d1a9961cc66d2ce40",
            "deploy 5c27f65c-deee-483b-816e-e503f4822f9b is live",
            "see https://gitlab.com/blazeinchue/BlazeMobile/-/merge_requests/10 please",
            "call me on +260 971 234 567 or 0971234567",
            "the password reset didn't work, my password is wrong apparently",
            "my task-force-alpha-bravo-charlie-delta-echo-foxtrot plan",
            "card 4242 4242 4242 4241 is a test number",           // fails Luhn
            "Remember to buy bread, milk and 12 eggs tomorrow at 10:30.",
            "Here is the handle {{secret:cloudflare_token}} you gave me",
            "/storage/emulated/0/Download/Blaze/blaze-0.46.3-release-unsigned-aligned.apk",
        )) assertTrue(t + " -> " + hit(t), hit(t).isEmpty())
    }

    @Test fun redactPutsHandlesInPlace() {
        val t = "deploy with $GL please"
        val h = hit(t)
        assertEquals("deploy with {{secret:gitlab_token}} please", PasteGuard.redact(t, h, listOf("{{secret:gitlab_token}}")))
    }

    @Test fun luhnAndEntropy() {
        assertTrue(PasteGuard.luhn("4242424242424242")); assertFalse(PasteGuard.luhn("4242424242424241")); assertFalse(PasteGuard.luhn("1234"))
        assertTrue(PasteGuard.entropy(CF) > 4.0); assertTrue(PasteGuard.entropy("aaaaaaaa") < 0.1)
    }

    @Test fun promptRuleForbidsChatAsks() {
        assertTrue(SecretRules.PROMPT.contains("request_secret"))
        assertTrue(SecretRules.PROMPT.contains("never ask the user to type"))
    }
}
