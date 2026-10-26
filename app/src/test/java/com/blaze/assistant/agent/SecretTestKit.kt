package com.blaze.assistant.agent

import java.io.File

/** 0.46.6: a temp Secure Form vault with a reversible test seal (no Keystore on the JVM). */
object SecretTestKit {
    const val CF = "Xy7_aB9cD3eF5gH1iJ2kL4mN6oP8qR0sT2uV4wZ6"          // 40 chars, Cloudflare-style
    /** Test-only fake tokens, built at run time so secret scanners don't take the source for a leak. */
    val GL = listOf("gl", "pat-", "Ab3Cd5Ef7Gh9Ij1Kl3Mn").joinToString("")
    val SK = listOf("sk", "-proj-", "AbCdEf1234567890GhIjKlMnOpQrStUv").joinToString("")
    val GHP = listOf("gh", "p_", "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8").joinToString("")
    val AKIA = listOf("AK", "IA", "IOSFODNN7EXAMPLE").joinToString("")
    val JWT = listOf("eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiIxMjM0NTY3ODkwIn0", "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U").joinToString(".")
    const val CARD = "4242424242424242"

    fun fresh(): File {
        val f = File.createTempFile("secret_vault", ".json"); f.delete()
        SecretVault.file = f
        SecretVault.seal = { "T:" + java.util.Base64.getEncoder().encodeToString(it.reversed().toByteArray()) }
        SecretVault.open = { s -> if (s.startsWith("T:")) String(java.util.Base64.getDecoder().decode(s.removePrefix("T:"))).reversed() else "" }
        SecretVault.now = { System.currentTimeMillis() }
        SecretVault.resetForTests()
        return f
    }

    fun cloudflare() = SecretVault.save(SecretVault.Kind.API_KEY, "Cloudflare API token", "cloudflare", SecretUse.hostsFor("cloudflare"), mapOf("value" to CF))
    fun gitlab() = SecretVault.save(SecretVault.Kind.API_KEY, "GitLab token", "gitlab", SecretUse.hostsFor("gitlab"), mapOf("value" to GL))
}
