package com.blaze.assistant.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 0.46.6 brain routing fix (handoff 27 Oct: "on-phone brain 'Free usage exceeded' rests the whole brain, Nous gets skipped").
 *
 * Before: one "Free usage exceeded" from ONE on-phone model rested every on-phone model and the whole on-phone brain
 * (`freeUntil`, so [BackupBrain.ready] went false for up to 30 min) and the call ended there, without asking the other
 * on-phone models.
 *
 * Now, per model:
 *  - a free-usage refusal rests only the model that said it: until its reset when the reply gave one (capped at
 *    [MAX_MS], the 0.46.3 cap), otherwise for a backoff ([BASE_MS], doubling on each repeat, capped at [MAX_MS]);
 *  - the call falls through to the next on-phone model that is not resting ([run]);
 *  - only when every on-phone model is resting does the brain as a whole count as resting ([Book.allRestingUntil]), and
 *    [AllResting] (a plain 429, never fatal) moves the brain race on to Nous, then the Blaze cloud gateway.
 * Pure (BrainRestTest): no Android, injectable clock.
 */
object BrainRest {
    /** First backoff for a model that ran out of free usage without saying when it resets. */
    const val BASE_MS = 2 * 60_000L
    /** Never rest a model longer than this (a stale or far-off reset must not park it all evening). */
    const val MAX_MS = BackupModels.MAX_REST_MS

    /** How long one model rests: until [reset] (> [now]) when known, else a backoff by [strikes] (1 = first time). */
    fun until(reset: Long, now: Long, strikes: Int): Long =
        if (reset > now) minOf(reset, now + MAX_MS)
        else now + minOf(MAX_MS, BASE_MS shl (strikes - 1).coerceIn(0, 10))

    /** Every on-phone model that is resting: thrown when none is left, so the race asks Nous next (429, never fatal). */
    class AllResting(msg: String, val until: Long) : LlmClient.LlmException(msg, 429)

    /** The rests of the on-phone models (thread-safe; one per process in [BackupBrain]). */
    class Book {
        private val rests = ConcurrentHashMap<String, Long>()
        private val strikes = ConcurrentHashMap<String, Int>()

        /** [id] said "free usage exceeded": rest it alone. Returns until when. */
        fun rest(id: String, reset: Long, now: Long): Long {
            val n = (strikes[id] ?: 0) + 1
            strikes[id] = n
            return until(reset, now, n).also { rests[id] = it }
        }
        /** [id] answered: its backoff starts over. */
        fun ok(id: String) { strikes.remove(id); rests.remove(id) }
        fun resting(id: String, now: Long): Boolean = (rests[id] ?: 0L) > now
        fun restingUntil(id: String): Long = rests[id] ?: 0L
        /** The models of [ids] not resting at [now], order kept. */
        fun available(ids: List<String>, now: Long): List<String> = ids.filter { !resting(it, now) }
        /** 0 while any of [ids] can answer; otherwise when the first of them is back. */
        fun allRestingUntil(ids: Collection<String>, now: Long): Long {
            if (ids.isEmpty()) return 0L
            var first = Long.MAX_VALUE
            for (id in ids) { val u = rests[id] ?: 0L; if (u <= now) return 0L; first = minOf(first, u) }
            return first
        }
        fun clear() { rests.clear(); strikes.clear() }
    }

    /**
     * One on-phone call over its models, strongest first: models resting in [book] are skipped; a free-usage refusal
     * ([freeReset] gives the reset, 0 = unknown, null = not a free-usage refusal) rests that model and the next one is
     * asked; any other error goes to [onError] and the next one is asked. An [empty] answer counts as an error.
     * Returns the first real answer, or throws [AllResting] when every model is resting, else the last error.
     * At most [max] models are asked and none is started after [budgetMs]. Cancellation passes through.
     */
    suspend fun <T> run(
        ids: List<String>,
        book: Book,
        call: suspend (String) -> T,
        freeReset: (Throwable) -> Long?,
        empty: (T) -> Boolean = { false },
        onError: (String, Throwable) -> Unit = { _, _ -> },
        onRest: (String, Long) -> Unit = { _, _ -> },
        max: Int = Int.MAX_VALUE,
        budgetMs: Long = Long.MAX_VALUE,
        now: () -> Long = { System.currentTimeMillis() },
    ): T {
        val t0 = now()
        val todo = book.available(ids, t0).take(max)
        var last: Throwable? = null
        for (id in todo) {
            if (now() - t0 > budgetMs) break
            try {
                val v = call(id)
                if (!empty(v)) { book.ok(id); return v }
                val e = LlmClient.LlmException("backup: empty reply", 0)
                onError(id, e); last = e
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                val reset = freeReset(e)
                if (reset != null) { onRest(id, book.rest(id, reset, now())); last = e; continue }
                onError(id, e); last = e
            }
        }
        val t = now()
        val back = book.allRestingUntil(ids, t)
        if (back > 0L) throw AllResting("on-phone brain: every model is out of free usage for now", back)
        throw (last as? LlmClient.LlmException) ?: LlmClient.LlmException(if (last != null) "backup: ${last.message}" else "backup: no answer", 503)
    }
}
