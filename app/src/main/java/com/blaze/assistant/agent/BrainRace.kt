package com.blaze.assistant.agent

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0.46.4 hotfix router (report on 0.46.3, 26 Oct 19:07–19:11 CAT: "Sent. Waiting for Blaze…" for 4+ minutes, Halo silent).
 *
 * Root cause in 0.46.3: the "~20 s first-token cap" only existed inside the on-phone brain's own stream loop (and counted
 * hidden thinking as a first token). The gateway paths had none: [LlmClient]'s watchFirstChunk was never called, a gateway
 * call waited on a 120 s read timeout with 3 tries and backoff, a Cloud run step waited up to 75 s on a run the gateway
 * kept "queued" (the status never changed from "Sent. Waiting for Blaze…"), and the brains were asked one after another.
 * The on-phone brain was used whenever it was installed, even cold: each call first took the setup Mutex in
 * BackupBrain.start() (shared with the background setup / health check) and waited up to 20 s for the server, so the turn
 * and Halo's side call queued behind each other and behind setup. 75 s + 150 s step budget + the idle watchdog's retry
 * filled the 5-minute turn limit.
 *
 * Now every model call goes through [HedgeRace] with deadlines by [Kind], brains in [order]:
 *  - CHAT (no tools, or a short plain turn, and every Halo side reply): the fastest brain first by measured first-token
 *    time ([BrainLedger.ttft]; Nous reserve, then Blaze Cloud's fast lineup until there is data), hedged after 3 s,
 *    first visible token within 8 s or the next brain, thinking never counts and is not shown.
 *  - TASK / CODING: the 0.46.3 order (on-phone brain → Nous → Blaze Cloud), first token 20 s (thinking may stretch it to
 *    45 s), hedged after 6 s.
 *  - Every kind: 30 s without any chunk once answering → next brain, with the same messages.
 * The on-phone brain is only in the list when it is warm ([BackupBrain.warm]: installed, server running, last health check
 * good, not resting); a cold one is started in the background and the call starts at Nous / Blaze Cloud at once.
 * Pure (BrainRaceTest).
 */
object BrainRace {
    enum class Kind { CHAT, TASK, CODING }

    /** The one status line when another brain is asked (plain, factual). */
    const val SWITCHING = "Switching to a faster brain"

    // 0.46.5: firstMs / hedgeMs only start the next brain in parallel (never cancel one); wholeMs is the outer cap for a
    // brain that shows nothing at all, thinkMs while it is still sending thinking (HedgeRace).
    val CHAT_LIMITS = HedgeRace.Limits(firstMs = 8_000L, hedgeMs = 3_000L, stallMs = 30_000L, thinkMs = 60_000L, wholeMs = 60_000L)
    val TASK_LIMITS = HedgeRace.Limits(firstMs = 20_000L, hedgeMs = 6_000L, stallMs = 30_000L, thinkMs = 120_000L, wholeMs = 90_000L)

    /** 0.46.5: every brain hard-failed → the most reliable one is asked once more with the long-task cap. */
    const val RETRY_MS = 90_000L

    /** 0.46.5: the one calm line when every brain (and the retry) failed. Never "Blaze Cloud is busy" unless Cloud was the only path. */
    const val TOO_LONG = "That took too long. Tap Retry."

    /** [hot] (phone warm or Battery Saver, [com.blaze.assistant.device.Thermal]): no hedged second request, deadlines stay. */
    fun limits(k: Kind, hot: Boolean = !com.blaze.assistant.device.Thermal.now().hedge): HedgeRace.Limits =
        (if (k == Kind.CHAT) CHAT_LIMITS else TASK_LIMITS).let { if (hot) it.copy(hedgeMs = 0L) else it }

    /** A short turn: a plain message of up to this many characters is chat-fast even when tools ride along. */
    const val SHORT_CHARS = 280

    /**
     * [forced] = the caller's own kind (Halo's side lane is always CHAT). Coding jobs are CODING; a call without tools, the
     * chat lane, or a short non-heavy message is CHAT; the rest TASK.
     */
    fun kind(lite: Boolean, heavy: Boolean, toolCount: Int, coding: Boolean, userChars: Int, forced: Kind? = null): Kind = when {
        forced != null -> forced
        coding -> Kind.CODING
        lite || toolCount == 0 -> Kind.CHAT
        !heavy && userChars in 1..SHORT_CHARS -> Kind.CHAT
        else -> Kind.TASK
    }

    /** First-token guesses (ms) before the phone has measured a brain. */
    val DEFAULT_TTFT = mapOf(BackupRoute.Step.RESERVE to 3_000L, BackupRoute.Step.CLOUD to 3_200L, BackupRoute.Step.BACKUP to 6_000L)

    /**
     * The brains for one call, in order. [warm] = the on-phone brain can answer right now (never "will be ready").
     * [ttft] = the measured first-token time per brain (null = not measured yet).
     */
    fun order(kind: Kind, warm: Boolean, signedIn: Boolean, ttft: (BackupRoute.Step) -> Long? = { null },
              reliability: (BackupRoute.Step) -> Double? = { null }): List<BackupRoute.Step> {
        val list = buildList {
            if (warm) add(BackupRoute.Step.BACKUP)
            if (signedIn) { add(BackupRoute.Step.RESERVE); add(BackupRoute.Step.CLOUD) }
        }
        // 0.46.5: success rate first: a slow but reliable brain is never put behind a fast one that keeps failing.
        // Tasks keep the policy order inside a reliability band (stable sort); chat is fastest first inside a band.
        if (kind != Kind.CHAT) return list.sortedByDescending { band(reliability(it)) }
        return list.sortedWith(compareByDescending<BackupRoute.Step> { band(reliability(it)) }.thenBy { ttft(it) ?: DEFAULT_TTFT[it] ?: 10_000L })
    }

    /** Reliability band: 2 = answers (>= 80 % or not measured yet), 1 = shaky (>= 50 %), 0 = mostly failing. */
    fun band(rel: Double?): Int = when { rel == null || rel >= 0.8 -> 2; rel >= 0.5 -> 1; else -> 0 }

    /** The brain to ask once more when all failed: the most reliable (unmeasured = 0.5), ties keep [steps]' order. */
    fun retryPick(steps: List<BackupRoute.Step>, reliability: (BackupRoute.Step) -> Double?): BackupRoute.Step? =
        steps.withIndex().maxWithOrNull(compareBy<IndexedValue<BackupRoute.Step>> { reliability(it.value) ?: 0.5 }.thenByDescending { it.index })?.value

    /**
     * 0.46.5: the error after every brain (and the retry) failed. A fatal error (sign-in, allowance, refused shape) keeps its
     * own line; Cloud's error only when Cloud was the only brain; otherwise the calm [TOO_LONG] (code 504, so no screen
     * reads it as "Blaze Cloud is busy").
     */
    fun finalError(steps: List<BackupRoute.Step>, fatal: Throwable?, cloudErr: Throwable?, last: Throwable?): Throwable {
        (fatal as? LlmClient.LlmException)?.let { return it }
        if (steps == listOf(BackupRoute.Step.CLOUD)) (cloudErr as? LlmClient.LlmException)?.takeIf { it !is LlmClient.TooSlow }?.let { return it }
        return LlmClient.LlmException(TOO_LONG, 504)
    }

    /** How long the last user message is (characters), for [kind]. */
    fun userChars(messages: JSONArray): Int {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            return ((m.opt("content") as? String) ?: m.optJSONArray("content")?.toString().orEmpty()).trim().length
        }
        return 0
    }

    /**
     * A Cloud run (kept on the gateway, slower to start) only for long work: routines, a resumed turn, or a long message.
     * Short turns go direct, through the race.
     */
    fun cloudRunFor(lite: Boolean, longWork: Boolean): Boolean = !lite && longWork

    /** Halo's side reply: if no word arrived by then, Halo says where Blaze is (from the app's own state, no model). */
    const val HALO_HOLD_MS = 2_000L
    fun haloHolding(task: String, now: String): String = buildString {
        append("Blaze is still on ")
        append(if (task.isBlank()) "your request" else "\u201c${task.trim().take(60)}\u201d")
        val n = now.trim().trimEnd('.', '\u2026')
        if (n.isNotBlank() && !n.equals(CloudRuns.QUEUED.trimEnd('\u2026'), true)) append(". Right now: ").append(n.take(80))
        append('.')
    }

    /**
     * Halo's side reply, independent of Blaze's turn: [call] streams the model's words through its show function; if none
     * came within [holdMs], [holding] (if not blank) is shown at once. When the model fails after the holding line was shown,
     * the holding line is Halo's reply (never an error while Blaze is simply busy). Cancellation passes through.
     */
    suspend fun haloReply(holdMs: Long, holding: () -> String, show: (String) -> Unit, call: suspend (show: (String) -> Unit) -> String): String = coroutineScope {
        val spoke = AtomicBoolean(false)
        var held = ""
        val timer = launch { delay(holdMs); if (!spoke.get()) { val h = holding(); if (h.isNotBlank()) { held = h; show(h) } } }
        try { call { t -> spoke.set(true); show(t) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { timer.cancel(); if (held.isNotEmpty()) held else throw e }
        finally { timer.cancel() }
    }
}
