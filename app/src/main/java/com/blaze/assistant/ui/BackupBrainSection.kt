package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.blaze.assistant.agent.BackupBrain
import com.blaze.assistant.agent.BackupNudge
import com.blaze.assistant.agent.BackupStatus
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v0.45.28 Settings › Brain › Backup brain: what state the on-device backup is in, Download now (mobile data after a
 * confirm), Install Linux when Alpine is missing, the on/off and mobile-data switches and the wait before it takes over.
 * Same settings as chat's set_setting ("backup_brain", "backup_brain_mobile_data", "backup_wait").
 */
@Composable
internal fun BackupBrainSection() {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }   // the status follows a download / Wi-Fi change
    var on by remember { mutableStateOf(Settings.bool("backup_brain", true)) }
    var mobile by remember { mutableStateOf(Settings.bool("backup_brain_mobile_data", false)) }
    var wait by remember { mutableIntStateOf(BackupStatus.waitSeconds(Settings.str("backup_ttft_ms"))) }
    var note by remember { mutableStateOf("") }
    var askMobile by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val state = remember(tick, on, mobile, note) { try { BackupBrain.status(now) } catch (_: Throwable) { BackupStatus.State.NOT_SUPPORTED } }
    val backAt = BackupBrain.restingUntil().takeIf { it > now }?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }.orEmpty()
    val mb = BackupNudge.mb(BackupBrain.DOWNLOAD_BYTES)

    fun download() {
        note = "Starting the download…"
        BackupBrain.startDownload(force = true) { r -> note = r; tick++ }
        tick++
    }

    // 0.46.3 Settings › Brains: the order every request follows and how each brain is doing right now
    V18Section("Brains")
    V18Note(com.blaze.assistant.agent.BrainOrder.plan(true).mapIndexed { i, st ->
        val ledger = com.blaze.assistant.agent.BrainLedger.app
        val health = when {
            st == com.blaze.assistant.agent.BackupRoute.Step.BACKUP && !BackupBrain.ready() -> "not ready yet, requests start at Nous"
            st == com.blaze.assistant.agent.BackupRoute.Step.BACKUP && BackupBrain.healthOk == false -> "not answering its health check"
            else -> ledger.health(st)
        }
        val n = ledger.rows[st]?.calls ?: 0
        "${i + 1}. ${com.blaze.assistant.agent.BrainOrder.label(st)}: $health" + (if (n > 0) " · $n answers" else "")
    }.joinToString("\n") + "\nCoding jobs use the strongest free coders on the phone first. Never NVIDIA or System One models.")
    V18Section("Backup brain")
    V18Note("Answers on the phone when Blaze Cloud is busy or down, before Blaze Cloud's own reserve. Status: " +
        BackupStatus.line(state, BackupBrain.VERSION, mb, backAt) +
        BackupStatus.healthLine(BackupBrain.healthOk, BackupBrain.healthAt.takeIf { it > 0 }?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }.orEmpty())
            .let { if (it.isEmpty()) "" else " $it" })
    if (state == BackupStatus.State.INSTALLED) V18Button("Check now") { scope.launch { note = if (BackupBrain.healthCheck()) "The backup brain answered." else "The backup brain didn't answer."; tick++ } }
    if (BackupStatus.canDownload(state)) V18Button("Download now (~$mb MB)") {
        if (BackupStatus.confirmMobileData(BackupBrain.phone())) askMobile = true else download()
    }
    if (BackupStatus.needsLinux(state)) V18Button("Install Linux (Alpine)") {
        note = "Installing Alpine Linux…"
        scope.launch { val r = try { com.blaze.assistant.terminal.Linux.install() } catch (e: Exception) { e.message ?: "Couldn't install Linux." }; note = r; tick++ }
    }
    ToggleRow("Backup brain", "Use the on-device backup when Blaze Cloud struggles", on) {
        on = !on; Settings.setBool("backup_brain", on); tick++
    }
    ToggleRow("Use mobile data for download", "Let the one-time download (about $mb MB) start without Wi-Fi", mobile) {
        mobile = !mobile; Settings.setBool("backup_brain_mobile_data", mobile); tick++
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Wait before switching", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
            Text("How long Blaze Cloud gets to start answering", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            fun set(sec: Int) { wait = sec; Settings.setStr("backup_ttft_ms", (sec * 1000L).toString()) }
            Text("\u2212", modifier = Modifier.clickable { set(BackupStatus.stepWait(wait, false)) }.padding(8.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.ink))
            Text(BackupStatus.waitLabel(wait), modifier = Modifier.width(96.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            Text("+", modifier = Modifier.clickable { set(BackupStatus.stepWait(wait, true)) }.padding(8.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.ink))
        }
    }
    if (note.isNotBlank()) V18Note(note, top = 6)
    if (askMobile) BlazeDialog(
        title = "Download on mobile data?",
        body = "You're not on Wi-Fi. The backup brain is about $mb MB, downloaded once.",
        confirm = "Download", onConfirm = { askMobile = false; download() },
        dismiss = "Wait for Wi-Fi", onDismiss = { askMobile = false })
}
