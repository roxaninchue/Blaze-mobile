package com.blaze.assistant.agent

import com.blaze.assistant.agent.SecretTestKit.CARD
import com.blaze.assistant.agent.SecretTestKit.CF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 0.46.6 Secure form: the vault itself (sealed at rest, previews, rename / replace / delete, codes in memory only, cards never shown). */
class SecretVaultTest {
    private lateinit var file: java.io.File

    @Before fun setUp() { file = SecretTestKit.fresh() }

    @Test fun savesSealedAndGivesHandleAndPreview() {
        val e = SecretTestKit.cloudflare()
        assertEquals("cloudflare_token", e.id)
        assertEquals("{{secret:cloudflare_token}}", SecretVault.handle(e.id))
        assertEquals("…" + CF.takeLast(4), e.preview)
        assertFalse("value at rest in plain text", file.readText().contains(CF))
        assertEquals(CF, SecretVault.value(e.id))
        assertTrue(SecretVault.list().single().lastUsed > 0)
    }

    @Test fun loginHasFieldHandlesAndMaskedPreview() {
        val e = SecretVault.save(SecretVault.Kind.LOGIN, "GitLab login", "gitlab", SecretUse.hostsFor("gitlab"), mapOf("username" to "lucky@example.com", "password" to "hunter2-Long!"))
        assertEquals(listOf("{{secret:gitlab_login}}", "{{secret:gitlab_login.username}}", "{{secret:gitlab_login.password}}"), SecretVault.handlesOf(e))
        assertFalse(e.preview.contains("hunter2")); assertTrue(e.preview.startsWith("lu•••@example.com"))
        assertEquals("hunter2-Long!", SecretVault.value(e.id))
        assertEquals("lucky@example.com", SecretVault.value(e.id, "username"))
    }

    @Test fun cardIsNeverShownBack() {
        val e = SecretVault.save(SecretVault.Kind.CARD, "Visa", "shop.example.com", listOf("shop.example.com"), mapOf("number" to CARD, "exp" to "12/29", "cvc" to "123"))
        assertEquals("•••• 4242", e.preview)
        assertFalse(SecretVault.list().toString().contains(CARD))
        assertFalse(SecretVault.list().toString().contains("12/29"))
        assertFalse(com.blaze.assistant.ui.SavedSecretsText.canReveal(e))
        assertFalse(file.readText().contains(CARD))
        assertFalse(com.blaze.assistant.ui.SecretFormRules.canReveal(SecretVault.Kind.CARD, SecretVault.fieldsFor(SecretVault.Kind.CARD).first()))
    }

    @Test fun oneTimeCodeStaysInMemoryAndExpires() {
        var t = 1_000_000L; SecretVault.now = { t }
        val e = SecretVault.save(SecretVault.Kind.CODE, "GitHub code", "github", SecretUse.hostsFor("github"), mapOf("code" to "482913"))
        assertFalse(file.exists() && file.readText().contains("482913"))
        assertEquals("482913", SecretVault.value(e.id))
        t += SecretVault.CODE_TTL_MS + 1
        assertNull(SecretVault.value(e.id)); assertNull(SecretVault.get(e.id))
    }

    @Test fun renameReplaceDelete() {
        val e = SecretTestKit.cloudflare()
        assertTrue(SecretVault.rename(e.id, "CF deploy token"))
        assertEquals("CF deploy token", SecretVault.get(e.id)!!.label)
        SecretVault.replace(e.id, mapOf("value" to "Zz9_yY8xX7wW6vV5uU4tT3sS2rR1qQ0pP9oO8nN7"))
        assertEquals("Zz9_yY8xX7wW6vV5uU4tT3sS2rR1qQ0pP9oO8nN7", SecretVault.value(e.id))
        assertEquals("CF deploy token", SecretVault.get(e.id)!!.label)
        assertTrue(SecretVault.delete(e.id)); assertNull(SecretVault.get(e.id)); assertFalse(SecretVault.delete(e.id))
    }

    @Test fun replaceKeepsTheOtherLoginField() {
        val e = SecretVault.save(SecretVault.Kind.LOGIN, "Site login", "example.com", listOf("example.com"), mapOf("username" to "me@example.com", "password" to "old-pass-123"))
        SecretVault.replace(e.id, mapOf("password" to "new-pass-456"))
        assertEquals("me@example.com", SecretVault.value(e.id, "username")); assertEquals("new-pass-456", SecretVault.value(e.id))
    }

    @Test fun secondSecretForSameServiceGetsItsOwnId() {
        val a = SecretTestKit.cloudflare(); val b = SecretTestKit.cloudflare()
        assertEquals("cloudflare_token_2", b.id); assertTrue(a.id != b.id)
        assertNotNull(SecretVault.findFor("Cloudflare", SecretVault.Kind.API_KEY))
    }

    @Test fun kindsParse() {
        assertEquals(SecretVault.Kind.API_KEY, SecretVault.Kind.parse("token"))
        assertEquals(SecretVault.Kind.API_KEY, SecretVault.Kind.parse("api_key"))
        assertEquals(SecretVault.Kind.LOGIN, SecretVault.Kind.parse("password"))
        assertEquals(SecretVault.Kind.CARD, SecretVault.Kind.parse("card"))
        assertEquals(SecretVault.Kind.CODE, SecretVault.Kind.parse("one_time_code"))
        assertEquals(SecretVault.Kind.GENERIC, SecretVault.Kind.parse("generic"))
    }

    @Test fun agoText() {
        assertEquals("never", com.blaze.assistant.ui.SavedSecretsText.ago(0, 5))
        assertEquals("2 h ago", com.blaze.assistant.ui.SavedSecretsText.ago(0 + 1, 1 + 2 * 3_600_000L))
    }
}
