package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.SecretDesk
import com.blaze.assistant.agent.SecretVault


/** The live Secure Form card: whatever request_secret is waiting on, then a short "Saved securely" line. */
@Composable
fun SecretRequestCard() {
    val p by SecretDesk.pending.collectAsState()
    val saved by SecretDesk.saved.collectAsState()
    val req = p
    if (req != null) {
        SecretForm(req.kind, req.label, req.service, req.hint, req.fields, onSave = { SecretDesk.answer(it) }, onCancel = { SecretDesk.answer(null) })
        return
    }
    val s = saved ?: return
    LaunchedEffect(s.at) { kotlinx.coroutines.delay(4_000); SecretDesk.clearSaved() }
    SavedSecurelyLine(s.label, s.preview)
}

@Composable
private fun LockBadge() {
    val c = LocalBlaze.current
    Box(Modifier.size(28.dp).clip(BlazeShapes.pill).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SavedSecurelyLine(label: String, preview: String) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(BlazeShapes.large, GlassIntensity.High).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Saved securely", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
            Text("$label · $preview", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
    }
}

/**
 * The Secure Form card (masked fields, eye, paste, Save / Cancel). Typed values go from here into the vault; the chat
 * and the model never see them. Glass + theme colours, so it follows every theme.
 */
@Composable
fun SecretForm(kind: SecretVault.Kind, label: String, service: String, hint: String, fields: List<SecretVault.FieldSpec>,
               onSave: (Map<String, String>) -> Unit, onCancel: () -> Unit) {
    val c = LocalBlaze.current
    val clip = LocalClipboardManager.current
    val values = remember(fields) { mutableStateMapOf<String, String>() }
    val shown = remember(fields) { mutableStateMapOf<String, Boolean>() }
    val ready = SecretFormRules.ready(kind, fields, values)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(BlazeShapes.large, GlassIntensity.High).padding(16.dp)
        .semantics { contentDescription = "Secure form: $label" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LockBadge()
            Spacer(Modifier.width(10.dp))
            Label("Secure form" + if (service.isNotBlank()) " · ${com.blaze.assistant.agent.SecretUse.displayName(service).take(30)}" else "",
                color = c.accent, size = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp, color = c.ink))
        if (hint.isNotBlank()) Text(hint, modifier = Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
        Spacer(Modifier.height(10.dp))
        fields.forEachIndexed { i, f ->
            val masked = f.masked && shown[f.name] != true
            Label(f.label, size = 10.sp, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.pill).background(c.field, BlazeShapes.pill).hairline(c.fieldLine, BlazeShapes.pill).padding(start = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (values[f.name].isNullOrEmpty()) Text(placeholder(kind, f), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                    BasicTextField(values[f.name].orEmpty(), { values[f.name] = it.replace("\n", "") }, singleLine = true,
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (f.numeric) KeyboardType.NumberPassword else if (f.masked) KeyboardType.Password else KeyboardType.Email,
                            imeAction = if (i == fields.lastIndex) ImeAction.Done else ImeAction.Next),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                // Paste: straight from the clipboard into the field (never through the chat), then the clipboard is cleared.
                Box(Modifier.size(40.dp).clip(BlazeShapes.pill).clickable(onClickLabel = "Paste") {
                    clip.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { values[f.name] = it.replace("\n", ""); clip.setText(AnnotatedString("")) }
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = c.sub, modifier = Modifier.size(18.dp))
                }
                if (SecretFormRules.canReveal(kind, f)) RevealEye(shown[f.name] == true) { shown[f.name] = shown[f.name] != true }
                else Spacer(Modifier.width(6.dp))
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.sub, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(SecretDesk.COPY, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BlazeButton("Cancel", modifier = Modifier.weight(1f), kind = BtnKind.SECONDARY, height = 46.dp, onClick = onCancel)
            BlazeButton("Save securely", modifier = Modifier.weight(1f), enabled = ready, height = 46.dp) {
                onSave(fields.associate { it.name to values[it.name].orEmpty() }.filterValues { it.isNotBlank() })
            }
        }
    }
}

private fun placeholder(kind: SecretVault.Kind, f: SecretVault.FieldSpec) = when {
    kind == SecretVault.Kind.CARD && f.name == "number" -> "1234 5678 9012 3456"
    kind == SecretVault.Kind.CARD && f.name == "exp" -> "MM/YY"
    kind == SecretVault.Kind.CARD && f.name == "cvc" -> "123"
    kind == SecretVault.Kind.CODE -> "6-digit code"
    f.name == "username" -> "you@example.com"
    f.name == "password" -> "Password"
    else -> "Paste or type it here"
}

/** 0.46.6 paste safety: "This looks like a secret. Save it securely instead?" before the message goes. */
@Composable
fun SecretPasteCard() {
    val p by SecretDesk.paste.collectAsState()
    val h = p ?: return
    SecretPasteHold(h.labels, h.masked, onSave = { SecretDesk.saveHeld() }, onSend = { SecretDesk.sendHeldAnyway() })
}

@Composable
fun SecretPasteHold(labels: List<String>, masked: String, onSave: () -> Unit, onSend: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(BlazeShapes.large, GlassIntensity.High).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LockBadge()
            Spacer(Modifier.width(10.dp))
            Text("This looks like a secret", modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
        }
        Text("Save it securely instead? Blaze gets a stand-in, never the value." + if (labels.isNotEmpty()) " (${labels.joinToString(", ")})" else "",
            modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
        Text(masked.take(240), maxLines = 3, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(BlazeShapes.small).background(c.field).padding(10.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.sub, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(SecretDesk.COPY, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BlazeButton("Send anyway", modifier = Modifier.weight(1f), kind = BtnKind.SECONDARY, height = 46.dp, onClick = onSend)
            BlazeButton("Save securely", modifier = Modifier.weight(1f), height = 46.dp, onClick = onSave)
        }
    }
}
