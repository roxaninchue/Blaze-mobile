package com.blaze.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Specialties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Advanced › Specialties (0.45.23, gateway 0.8.45 `/v1/specialties`): the account's specialties — the steps
 * that worked and the rules the person gave for one kind of task. List, open one (steps + rules), delete a rule or the
 * whole specialty (each asks first). Blaze makes and refines them on its own; this page never creates or edits one.
 * States: loading, empty, signed out, offline, older gateway, error. [client] is replaceable for screenshots / tests.
 */
@Composable
fun SpecialtiesPage(onSignIn: () -> Unit = {}, client: Specialties.Client = remember { Specialties.Client() }) {
    val c = LocalBlaze.current.mono
    var tick by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<Specialties.Result<Specialties.Listing>?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        listing = null
        listing = withContext(Dispatchers.IO) { client.list() }
    }

    val id = openId
    if (id != null) {
        BackHandler { openId = null }
        SpecialtyDetail(id, client, onBack = { openId = null }, onSignIn = onSignIn, onDeleted = { msg -> note = msg; openId = null; tick++ })
        return
    }

    Spacer(Modifier.height(10.dp))
    Text("Specialties are how Blaze gets better at tasks you repeat. The second time you ask for the same kind of task, " +
        "Blaze keeps the steps that worked and the rules you gave it, and uses them only when a new request clearly matches. " +
        "They live on your Blaze account, so all your devices share them.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
    Spacer(Modifier.height(14.dp))
    if (note.isNotEmpty()) { Label(note, size = 10.sp, color = c.accent, modifier = Modifier.padding(bottom = 10.dp)) }

    when (val r = listing) {
        null -> Label("Loading your specialties\u2026", size = 11.sp)
        is Specialties.Result.Ok -> {
            val l = r.value
            if (l.isEmpty) {
                Text("No specialties yet. Ask Blaze for the same kind of task twice \u2014 a booking, some research, a piece of writing \u2014 and what worked shows up here.",
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
            } else {
                Column(Modifier.fillMaxWidth().hairline(c.hair)) {
                    l.items.forEachIndexed { i, s ->
                        KitRow(s.name, Specialties.subLine(s), first = i == 0, onClick = { note = ""; openId = s.id })
                    }
                    if (l.generalRules.isNotEmpty()) {
                        val n = l.generalRules.size
                        KitRow("General rules", if (n == 1) "1 rule for every kind of task" else "$n rules for every kind of task",
                            first = l.items.isEmpty(), onClick = { note = ""; openId = Specialties.GENERAL })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ActionText("Refresh", color = c.accent, size = 10.sp, modifier = Modifier.clickable { note = ""; tick++ }.padding(vertical = 6.dp))
        }
        else -> Problem(r, onRetry = { note = ""; tick++ }, onSignIn = onSignIn)
    }
}

/** One specialty (or the general rules): what it's for, its steps, the person's rules, delete. */
@Composable
private fun SpecialtyDetail(id: String, client: Specialties.Client, onBack: () -> Unit, onSignIn: () -> Unit, onDeleted: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var res by remember { mutableStateOf<Specialties.Result<Specialties.Detail>?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmRule by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(id, tick) {
        res = null
        res = withContext(Dispatchers.IO) { client.get(id) }
    }

    Spacer(Modifier.height(6.dp))
    ActionText("\u2039 All specialties", color = c.accent, size = 10.sp, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp))
    Spacer(Modifier.height(4.dp))

    when (val r = res) {
        null -> Label("Loading\u2026", size = 11.sp)
        is Specialties.Result.Ok -> {
            val d = r.value
            Text(d.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = c.ink))
            if (!d.isGeneral) {
                val meta = listOfNotNull(
                    when (d.uses) { 0 -> "Not used yet"; 1 -> "Used once"; else -> "Used ${d.uses} times" },
                    Specialties.shortDate(d.createdAt).ifEmpty { null }?.let { "made $it" },
                    Specialties.shortDate(d.updatedAt).ifEmpty { null }?.takeIf { d.updatedAt != d.createdAt }?.let { "updated $it" },
                ).joinToString(" \u00b7 ")
                Label(meta, size = 10.sp, modifier = Modifier.padding(top = 4.dp))
                if (d.trigger.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Label("Started from", size = 10.sp)
                    Text("\u201c${d.trigger}\u201d", modifier = Modifier.padding(top = 2.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
                }
                if (d.steps.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Label("Steps that work", size = 10.sp)
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp).hairline(c.hair).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.steps.forEachIndexed { i, s ->
                            Row {
                                Text("${i + 1}.", modifier = Modifier.width(22.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                                Text(s, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.ink))
                            }
                        }
                    }
                }
            } else {
                Label("Rules you gave Blaze that hold for every kind of task.", size = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(16.dp))
            Label("Your rules", size = 10.sp)
            if (d.rules.isEmpty()) {
                Text("None yet. When you tell Blaze \u201calways\u2026\u201d, \u201cnever\u2026\u201d or correct it, the rule is kept here.",
                    modifier = Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).hairline(c.hair)) {
                    d.rules.forEachIndexed { i, rule ->
                        Row(Modifier.fillMaxWidth().let { if (i == 0) it else it.hairlineTop(c.hair) }.padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(rule, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.ink))
                            ActionText("\u00d7", size = 12.sp, modifier = Modifier.clickable(enabled = !busy, onClickLabel = "Delete rule") { confirmRule = i }.padding(12.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            val canDelete = !d.isGeneral || d.rules.isNotEmpty()
            if (canDelete) KitButton(if (d.isGeneral) "Delete all general rules" else "Delete specialty", Btn.LINE, enabled = !busy) { confirmAll = true }
            if (note.isNotEmpty()) Label(note, size = 10.sp, color = c.accent, modifier = Modifier.padding(top = 8.dp))

            if (confirmAll) BlazeDialog(
                title = if (d.isGeneral) "Delete all general rules?" else "Delete this specialty?",
                body = if (d.isGeneral) "Blaze stops following these rules on every device. This can't be undone."
                    else "Blaze forgets these steps and your rules for this kind of task on every device. If you ask for it again, it starts fresh. This can't be undone.",
                confirm = "Delete", warm = true, dismiss = "Cancel", onDismiss = { confirmAll = false },
                onConfirm = {
                    confirmAll = false; busy = true; note = ""
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { client.delete(d.id) }
                        busy = false
                        when (out) {
                            is Specialties.Result.Ok, Specialties.Result.NotFound -> onDeleted(if (d.isGeneral) "General rules deleted" else "Specialty deleted")
                            else -> { note = Specialties.plain(out) }
                        }
                    }
                })

            confirmRule?.let { n -> BlazeDialog(
                title = "Delete this rule?",
                body = "\u201c${d.rules.getOrNull(n).orEmpty()}\u201d\n\nBlaze stops following it on every device.",
                confirm = "Delete", warm = true, dismiss = "Cancel", onDismiss = { confirmRule = null },
                onConfirm = {
                    confirmRule = null; busy = true; note = ""
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { client.deleteRule(d.id, n) }
                        busy = false
                        when (out) {
                            is Specialties.Result.Ok -> { note = "Rule deleted"; tick++ }
                            Specialties.Result.NotFound -> { note = "That rule was already gone"; tick++ }
                            else -> { note = Specialties.plain(out) }
                        }
                    }
                }) }
        }
        else -> Problem(r, onRetry = { tick++ }, onSignIn = onSignIn, onBack = onBack)
    }
}

/** Signed out / offline / older gateway / error: one plain line and the one useful button. */
@Composable
private fun Problem(r: Specialties.Result<*>, onRetry: () -> Unit, onSignIn: () -> Unit, onBack: (() -> Unit)? = null) {
    val c = LocalBlaze.current.mono
    Text(Specialties.plain(r), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
    Spacer(Modifier.height(12.dp))
    when (r) {
        Specialties.Result.SignedOut -> KitButton("Sign in", Btn.ACCENT, onClick = onSignIn)
        Specialties.Result.Unavailable -> Unit
        Specialties.Result.NotFound -> if (onBack != null) KitButton("Back to all specialties", Btn.LINE, onClick = onBack) else KitButton("Try again", Btn.LINE, onClick = onRetry)
        else -> KitButton("Try again", Btn.LINE, onClick = onRetry)
    }
}
