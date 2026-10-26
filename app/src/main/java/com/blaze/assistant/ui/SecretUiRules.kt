package com.blaze.assistant.ui

import com.blaze.assistant.agent.SecretVault

/** 0.46.6 Secure form: what the card needs filled before Save (pure, SecretDeskTest). */
object SecretFormRules {
    /** A login needs only the password (some sites have no username); everything else needs every field. */
    fun ready(kind: SecretVault.Kind, fields: List<SecretVault.FieldSpec>, values: Map<String, String>): Boolean = when (kind) {
        SecretVault.Kind.LOGIN -> values["password"].orEmpty().isNotBlank()
        SecretVault.Kind.CARD -> com.blaze.assistant.agent.PasteGuard.luhn(values["number"].orEmpty().filter { it.isDigit() }) &&
            Regex("^\\s*(0[1-9]|1[0-2])\\s*/\\s*\\d{2}(\\d{2})?\\s*$").matches(values["exp"].orEmpty()) && Regex("^\\d{3,4}$").matches(values["cvc"].orEmpty().trim())
        else -> fields.all { values[it.name].orEmpty().isNotBlank() }
    }
    /** Card fields have no eye: what is typed there is never shown back. */
    fun canReveal(kind: SecretVault.Kind, f: SecretVault.FieldSpec) = f.masked && kind != SecretVault.Kind.CARD
}

/** 0.46.6 Saved secrets: pure helpers for the page (SecretVaultTest). */
object SavedSecretsText {
    fun ago(t: Long, now: Long): String {
        if (t <= 0L) return "never"
        val m = (now - t) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "${m} min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> "${m / (24 * 60)} d ago" }
    }
    fun date(t: Long): String = if (t <= 0) "" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))
    /** Cards and one-time codes are never shown back. */
    fun canReveal(e: SecretVault.Entry) = e.kind != SecretVault.Kind.CARD && e.kind != SecretVault.Kind.CODE
}
