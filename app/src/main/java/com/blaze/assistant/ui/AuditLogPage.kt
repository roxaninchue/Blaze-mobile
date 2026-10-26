package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.orch.AuditLog
import com.blaze.assistant.agent.orch.AuditWords
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.47.0 Settings › What Blaze did (spec §2.7): every change Blaze made or was refused, newest first, with what decided
 * it ("You approved", "Done · can undo", "Blocked"), and Undo on rows that have a way back. "Show look-ups too" adds the
 * read-only rows. "Stop everything" is the kill switch for any running work. Local only.
 */
@Composable
fun AuditLogPage() {
    val c = LocalBlaze.current.mono
    var reads by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    val rows = remember(reads, tick) { try { Agent.audit()?.forScreen(reads).orEmpty().take(300) } catch (_: Throwable) { emptyList() } }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    Spacer(Modifier.height(8.dp))
    Text("Everything Blaze changed, sent or was stopped from doing, on this phone only.", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (reads) "Hide look-ups" else "Show look-ups too", modifier = Modifier.clickable(role = Role.Button) { reads = !reads }.padding(vertical = 8.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.accent))
        Spacer(Modifier.width(18.dp))
        Text("Stop everything", modifier = Modifier.clickable(role = Role.Button) { try { Agent.killAll() } catch (_: Throwable) { }; note = "Stopped. Nothing more will run."; tick++ }.padding(vertical = 8.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.accent))
    }
    if (note.isNotBlank()) Text(note, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
    Spacer(Modifier.height(6.dp))
    if (rows.isEmpty()) Text("Nothing yet.", modifier = Modifier.padding(vertical = 14.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        rows.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(r.action, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 18.sp, color = c.ink))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${fmt.format(Date(r.at))} \u00b7 ${AuditWords.level(r.level)} \u00b7 ${AuditWords.outcome(r.outcome)}" + if (r.who.isNotBlank() && r.who != "Blaze") " \u00b7 ${r.who}" else "",
                        modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    if (r.undoId.isNotBlank() && r.outcome == AuditLog.Outcome.CHECKPOINT)
                        Text("Undo", modifier = Modifier.clickable(role = Role.Button) { note = try { Agent.undo()?.undo(r.undoId) ?: "" } catch (e: Throwable) { "Couldn't undo." }; tick++ }.padding(6.dp),
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.accent))
                }
            }
        }
    }
}
