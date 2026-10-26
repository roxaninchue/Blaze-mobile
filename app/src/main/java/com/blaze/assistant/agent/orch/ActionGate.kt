package com.blaze.assistant.agent.orch

import org.json.JSONObject

/**
 * 0.47.0 the central gate in front of every tool call (spec §2.2–2.3). One pure decision per call from the [Risk]
 * verdict and this task's [Guard] state:
 *  - KILLED: the kill switch is on (Stop) — nothing runs;
 *  - BLOCK_DUPLICATE: the same outward action (tool + normalised args) already ran in this task → never twice;
 *  - BLOCK_RATE: more than [MAX_CALLS_PER_MIN] changing (non-read) calls in a minute, or more than [MAX_SAME_OUTWARD] outward calls of
 *    one kind in a task (a runaway loop);
 *  - PREVIEW: a bulk change over [BULK_PREVIEW] items → a dry-run list first, then the confirm card with the count;
 *  - CONFIRM: OUTWARD (or over the blast radius) → the native confirm card with exactly what will happen;
 *  - CHECKPOINT: REVERSIBLE → runs, with a way back written to [UndoLog] first;
 *  - RUN: READ (or OUTWARD the user's own words pre-approved).
 * The decision never depends on the model's description of the action. GateTest / ActionGateTest.
 */
object ActionGate {
    const val BULK_PREVIEW = 5
    const val MAX_BLAST = 20
    const val MAX_TASK_BLAST = 50
    const val MAX_CALLS_PER_MIN = 30
    const val MAX_SAME_OUTWARD = 3

    enum class Decision { RUN, CHECKPOINT, CONFIRM, PREVIEW, BLOCK_DUPLICATE, BLOCK_RATE, KILLED }

    data class Gate(val decision: Decision, val verdict: Risk.Verdict, val title: String, val detail: String, val key: String)

    /** One task's guard state (per running task; the orchestrator's specialists share their task's guard). */
    class Guard(private val now: () -> Long = { System.currentTimeMillis() }) {
        @Volatile var killed = false
        private val done = HashSet<String>()
        private val calls = ArrayDeque<Long>()
        private val perKind = HashMap<Risk.Kind, Int>()
        var touched = 0; private set

        @Synchronized internal fun tick(): Int { val t = now(); calls.addLast(t); while (calls.isNotEmpty() && t - calls.first() > 60_000L) calls.removeFirst(); return calls.size }
        @Synchronized fun ran(key: String) = key in done
        @Synchronized fun kindCount(k: Risk.Kind) = perKind[k] ?: 0
        /** Records a call that actually ran (approved or free). */
        @Synchronized fun record(g: Gate) {
            if (g.verdict.level == Risk.Level.OUTWARD) { done += g.key; perKind[g.verdict.kind] = kindCount(g.verdict.kind) + 1 }
            if (g.verdict.level != Risk.Level.READ) touched += g.verdict.count
        }
        @Synchronized fun reset() { done.clear(); calls.clear(); perKind.clear(); touched = 0; killed = false }
    }

    /** Idempotency key: tool + arguments with volatile keys (call ids, nonces, timestamps) removed and whitespace / case normalised. */
    fun key(tool: String, args: JSONObject): String {
        val o = JSONObject(args.toString())
        listOf("call_id", "request_id", "nonce", "ts", "timestamp").forEach { o.remove(it) }
        val keys = o.keys().asSequence().sorted().toList()
        return tool + "|" + keys.joinToString("|") { k -> k + "=" + o.opt(k).toString().lowercase().replace(Regex("\\s+"), " ").trim() }
    }

    /**
     * [userWords] = the user's own messages in this task (for pre-approval). [tainted] = this turn read outside content.
     * [approvedBulk] = the user already approved this exact bulk preview (second call after the preview).
     */
    fun decide(tool: String, args: JSONObject, g: Guard, userWords: String, tainted: Boolean, approvedBulk: Boolean = false): Gate {
        val v = Risk.classify(tool, args)
        val k = key(tool, args)
        val title = v.what.ifBlank { tool.replace('_', ' ').replaceFirstChar { it.uppercase() } }
        fun gate(d: Decision, detail: String = "") = Gate(d, v, title, detail, k)
        if (g.killed) return gate(Decision.KILLED, "Stopped by you. Nothing more runs for this task.")
        if (v.level == Risk.Level.OUTWARD && g.ran(k)) return gate(Decision.BLOCK_DUPLICATE, "Already done once in this task; not repeating it.")
        if (v.level != Risk.Level.READ && g.tick() > MAX_CALLS_PER_MIN) return gate(Decision.BLOCK_RATE, "Too many actions in a minute; paused to avoid a loop.")
        if (v.level == Risk.Level.OUTWARD && g.kindCount(v.kind) >= MAX_SAME_OUTWARD) return gate(Decision.BLOCK_RATE, "That would be the ${MAX_SAME_OUTWARD + 1}th ${v.kind.verb.lowercase()} in this task; asking first.").let { it.copy(decision = Decision.CONFIRM) }
        val n = v.count
        if (v.level != Risk.Level.READ && n > BULK_PREVIEW && !approvedBulk) return gate(Decision.PREVIEW, "$n items. Showing the list first; nothing changes until you confirm.")
        if (v.level != Risk.Level.READ && (n > MAX_BLAST || g.touched + n > MAX_TASK_BLAST)) return gate(Decision.CONFIRM, "This touches $n items (${g.touched + n} in this task).")
        return when (v.level) {
            Risk.Level.READ -> gate(Decision.RUN)
            Risk.Level.REVERSIBLE -> gate(Decision.CHECKPOINT)
            Risk.Level.OUTWARD -> if (Risk.preApproved(v, userWords, tainted)) gate(Decision.RUN, "You asked for this.") else gate(Decision.CONFIRM, v.what)
        }
    }

    /** The dry-run preview for a bulk change: the first items and the total, plain. */
    fun preview(v: Risk.Verdict, args: JSONObject): String {
        val list = listOf("paths", "ids", "items", "recipients", "files").firstNotNullOfOrNull { args.optJSONArray(it) }
        val items = if (list == null) emptyList() else (0 until list.length()).map { list.opt(it).toString() }
        return buildString {
            append("Preview (nothing changed yet): ").append(v.kind.verb.ifBlank { "Change" }).append(' ').append(v.count).append(" items")
            items.take(10).forEach { append("\n\u2022 ").append(it.take(80)) }
            if (items.size > 10) append("\n\u2026 and ").append(items.size - 10).append(" more")
        }
    }
}
