package com.blaze.assistant.agent.orch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.47.0 "What Blaze did" (spec §2.7): an append-only, local list of every action Blaze took or was refused, with the
 * risk class, what decided it, the proof and whether it can be undone. Args are scrubbed of secrets before they get here
 * (the caller passes plain lines; [scrub] also removes anything that looks like a handle value, a card or a long token).
 * Capped at [MAX] rows (oldest dropped). Pure apart from file I/O in a given directory (AuditLogTest).
 */
class AuditLog(private val dir: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MAX = 1000
        private val card = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val token = Regex("""\b(?:glpat-|ghp_|github_pat_|sk-|xox[bp]-|AIza)[A-Za-z0-9_\-]{8,}|\b[A-Za-z0-9_\-]{32,}\b""")
        private val handle = Regex("""\{\{secret:[^}]+\}\}""")
        fun scrub(s: String): String = handle.replace(token.replace(card.replace(s, "[card]"), "[token]"), "[saved secret]")
    }

    enum class Outcome { RAN, CHECKPOINT, CONFIRMED, DECLINED, BLOCKED, FAILED }

    data class Row(val at: Long, val task: String, val who: String, val action: String, val level: Risk.Level, val outcome: Outcome,
                   val proof: Acceptance.Proof = Acceptance.Proof.NONE, val undoId: String = "")

    private val file = File(dir.apply { mkdirs() }, "audit_log.jsonl")
    private var adds = 0

    @Synchronized fun add(r: Row) {
        val o = JSONObject().put("at", r.at.takeIf { it > 0 } ?: now()).put("task", r.task.take(80)).put("who", r.who).put("action", scrub(r.action).take(240))
            .put("level", r.level.name).put("outcome", r.outcome.name).put("proof", r.proof.name).put("undo", r.undoId)
        file.appendText(o.toString() + "\n")
        if (++adds % 100 == 0) { val lines = file.readLines(); if (lines.size > MAX + 100) file.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n")) }
    }

    @Synchronized fun rows(): List<Row> = if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }.mapNotNull { l ->
        try { val o = JSONObject(l)
            Row(o.optLong("at"), o.optString("task"), o.optString("who"), o.optString("action"),
                runCatching { Risk.Level.valueOf(o.optString("level")) }.getOrDefault(Risk.Level.READ),
                runCatching { Outcome.valueOf(o.optString("outcome")) }.getOrDefault(Outcome.RAN),
                runCatching { Acceptance.Proof.valueOf(o.optString("proof")) }.getOrDefault(Acceptance.Proof.NONE), o.optString("undo"))
        } catch (_: Exception) { null }
    }.takeLast(MAX)

    @Synchronized fun clear() { file.delete() }

    /** Newest first, READ rows folded away unless [withReads] (the screen's default shows changes and refusals). */
    fun forScreen(withReads: Boolean = false): List<Row> = rows().filter { withReads || it.level != Risk.Level.READ || it.outcome == Outcome.BLOCKED }.reversed()

    fun toJson(): String = JSONArray().apply { rows().forEach { put(JSONObject().put("action", it.action).put("outcome", it.outcome.name)) } }.toString()
}

/** Plain words for the audit screen and the pill's expanded list. */
object AuditWords {
    fun outcome(o: AuditLog.Outcome): String = when (o) {
        AuditLog.Outcome.RAN -> "Done"
        AuditLog.Outcome.CHECKPOINT -> "Done \u00b7 can undo"
        AuditLog.Outcome.CONFIRMED -> "You approved"
        AuditLog.Outcome.DECLINED -> "You declined"
        AuditLog.Outcome.BLOCKED -> "Blocked"
        AuditLog.Outcome.FAILED -> "Didn't work"
    }
    fun level(l: Risk.Level): String = when (l) { Risk.Level.READ -> "Looked"; Risk.Level.REVERSIBLE -> "Changed"; Risk.Level.OUTWARD -> "Sent / irreversible" }
}
