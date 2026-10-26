package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.SecretVault


/**
 * Settings › Saved secrets: keys, tokens, logins, cards and codes from the Secure Form. Label, service, safe preview,
 * saved date and last use; rename, replace, delete. Revealing a value or deleting one asks for the fingerprint / face /
 * screen lock first. Cards are never revealed.
 */
@Composable
fun SavedSecretsPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val items = remember(tick) { try { SecretVault.list() } catch (_: Throwable) { emptyList() } }
    var mode by remember { mutableStateOf<Pair<String, String>?>(null) }   // id to "rename" | "replace"
    var draft by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf<Pair<String, String>?>(null) }   // id to value, for 30 s
    var note by remember { mutableStateOf("") }
    var replReveal by remember { mutableStateOf(false) }   // eye on the Replace field (not for cards)
    LaunchedEffect(revealed) { if (revealed != null) { kotlinx.coroutines.delay(30_000); revealed = null } }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.sub, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(com.blaze.assistant.agent.SecretDesk.COPY, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
    }
    Text("Keys, tokens, logins and cards you gave Blaze through the Secure Form. They're encrypted with this phone's Keystore and never go into the chat, memory or the AI.",
        modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
    if (note.isNotBlank()) Text(note, modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent))
    Spacer(Modifier.height(14.dp))
    if (items.isEmpty()) {
        Text("Nothing saved yet. When Blaze needs a key, password or code, it asks with the Secure Form and keeps it here.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        return
    }
    val now = System.currentTimeMillis()
    items.forEach { e ->
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).hairline(c.hair)) {
            Column(Modifier.fillMaxWidth().glassInset(c).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(e.label, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                Text(listOf(e.service.takeIf { it.isNotBlank() }?.let { com.blaze.assistant.agent.SecretUse.displayName(it) }, e.kind.title).filterNotNull().joinToString(" · "),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                val shownValue = revealed?.takeIf { it.first == e.id }?.second
                Text(shownValue ?: e.preview, modifier = Modifier.padding(top = 4.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
                Text("Saved ${SavedSecretsText.date(e.created)} · Last used ${SavedSecretsText.ago(e.lastUsed, now)}",
                    modifier = Modifier.padding(top = 2.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
            }
            val m = mode?.takeIf { it.first == e.id }?.second
            if (m != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(draft, { draft = it.replace("\n", "") }, singleLine = true,
                    visualTransformation = if (SecretField.masked(m == "replace", replReveal)) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                if (m == "replace" && e.kind != SecretVault.Kind.CARD) RevealEye(replReveal) { replReveal = !replReveal }
                ActionText("Save", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    if (draft.isNotBlank()) {
                        if (m == "rename") SecretVault.rename(e.id, draft)
                        else SecretVault.replace(e.id, mapOf(SecretVault.primary(e.kind) to draft))
                        note = if (m == "rename") "Renamed." else "Replaced. Blaze uses the new one from now on."
                    }
                    draft = ""; mode = null; tick++ }.padding(10.dp))
                ActionText("Cancel", size = 10.sp, modifier = Modifier.clickable { draft = ""; mode = null }.padding(10.dp))
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionText("Rename", color = c.accent, size = 10.sp, modifier = Modifier.clickable { draft = e.label; mode = e.id to "rename" }.padding(10.dp))
                if (e.kind != SecretVault.Kind.CODE) ActionText("Replace", color = c.accent, size = 10.sp, modifier = Modifier.clickable { draft = ""; replReveal = false; mode = e.id to "replace" }.padding(10.dp))
                if (SavedSecretsText.canReveal(e)) ActionText(if (revealed?.first == e.id) "Hide" else "Reveal", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    if (revealed?.first == e.id) revealed = null
                    else DeviceUnlock.ask(ctx, "Reveal ${e.label}") { ok, why -> if (ok) SecretVault.value(e.id, touch = false)?.let { revealed = e.id to it } else note = why }
                }.padding(10.dp))
                Box(Modifier.weight(1f))
                ActionText("Delete", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    DeviceUnlock.ask(ctx, "Delete ${e.label}", allowWithoutLock = true) { ok, why -> if (ok) { SecretVault.delete(e.id); note = "Deleted."; tick++ } else note = why }
                }.padding(10.dp))
            }
        }
    }
}

/** Fingerprint / face / screen lock before revealing or deleting a saved secret. */
object DeviceUnlock {
    private const val AUTH = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun ask(ctx: android.content.Context, title: String, allowWithoutLock: Boolean = false, done: (Boolean, String) -> Unit) {
        val act = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is androidx.fragment.app.FragmentActivity }
            as? androidx.fragment.app.FragmentActivity
        val can = try { androidx.biometric.BiometricManager.from(ctx).canAuthenticate(AUTH) } catch (_: Throwable) { -1 }
        if (act == null || can != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            if (allowWithoutLock) done(true, "") else done(false, "Set a screen lock on this phone to reveal saved secrets.")
            return
        }
        try {
            androidx.biometric.BiometricPrompt(act, androidx.core.content.ContextCompat.getMainExecutor(act), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) = done(true, "")
                override fun onAuthenticationError(code: Int, msg: CharSequence) = done(false, "Not unlocked.")
            }).authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Saved secrets").setAllowedAuthenticators(AUTH).build())
        } catch (_: Throwable) { done(false, "Couldn't ask for the screen lock.") }
    }
}
