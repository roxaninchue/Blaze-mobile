package com.blaze.assistant.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.48.0 RC4: the terminal's `blaze` CLI follows the app's sign-in (files the CLI reads with no OS keychain). */
class CliAuthLogicTest {
    @Test fun whereTheCliKeepsItsSignIn() {
        val rf = File("/x/linux/alpine")
        assertEquals("/x/linux/alpine/root/.blaze/secrets.json", CliAuthLogic.secretsFile(rf).path)
        assertEquals("/x/linux/alpine/root/.blaze/desktop/account.json", CliAuthLogic.accountFile(rf).path)
        assertEquals("blaze-account:session", CliAuthLogic.SESSION_KEY)
    }

    @Test fun secretsKeepOtherKeys() {
        val before = JSONObject().put("conn:github", "gh-token").toString()
        val w = CliAuthLogic.withSession(before, "sess-1")
        assertEquals("sess-1", CliAuthLogic.sessionIn(w))
        assertEquals("gh-token", JSONObject(w).getString("conn:github"))
        val cleared = CliAuthLogic.withSession(w, null)
        assertEquals("", CliAuthLogic.sessionIn(cleared))
        assertEquals("gh-token", JSONObject(cleared).getString("conn:github"))
        assertEquals("", CliAuthLogic.sessionIn("not json"))
        assertEquals("s", CliAuthLogic.sessionIn(CliAuthLogic.withSession("garbage", "s")))
        assertEquals("u1", CliAuthLogic.userIn(JSONObject().put("id", "u1").put("email", "a@b").toString()))
    }

    @Test fun whatEachDistroNeeds() {
        // signed in, CLI signed out -> write; CLI on another account -> write; same account -> nothing
        assertEquals(CliAuthLogic.Need.WRITE, CliAuthLogic.need("u1", "", ""))
        assertEquals(CliAuthLogic.Need.WRITE, CliAuthLogic.need("u1", "tok", "u2"))
        assertEquals(CliAuthLogic.Need.NOTHING, CliAuthLogic.need("u1", "tok", "u1"))
        // app signed out: clear a signed-in CLI, nothing when it's already out
        assertEquals(CliAuthLogic.Need.CLEAR, CliAuthLogic.need(null, "tok", "u1"))
        assertEquals(CliAuthLogic.Need.NOTHING, CliAuthLogic.need(null, "", ""))
        assertFalse(CliAuthLogic.CLIENT.contains("Hark"))
        assertTrue(CliAuthLogic.CLIENT.startsWith("Blaze CLI"))
    }
}
