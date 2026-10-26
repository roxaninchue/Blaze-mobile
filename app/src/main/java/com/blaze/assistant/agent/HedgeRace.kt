package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.46.4 hotfix (report on 0.46.3, 26 Oct 2026 19:07–19:11 CAT: a short chat message sat at "Sent. Waiting for Blaze…"
 * for over 4 minutes, Halo silent too). One model call asked of several brains with hard deadlines and a hedge.
 *
 * Rules (all times per attempt, from when that brain was asked):
 *  - [Limits.firstMs]: no visible output (reply text or a tool call) in time → that brain is dropped and the next one asked
 *    at once. Hidden thinking is NOT visible output: on chat it never counts; on tasks it may stretch the wait to
 *    [Limits.thinkMs] (a reasoning model that streams thinking forever is cut there).
 *  - [Limits.hedgeMs]: while nobody has shown anything, the next brain is started in parallel; whichever shows output first
 *    wins and the others are cancelled (their text never reaches the screen: callbacks are gated by [Attempt.visible]).
 *  - [Limits.stallMs]: the winner went silent (no text, tool or thinking chunk) that long → dropped, next brain asked.
 *  - A call that does not stream ([streaming] false) has no progress to watch: it gets [Limits.wholeMs] to finish, no hedge.
 *  - An error (rate limit, busy, 5xx, network) moves on at once. [fatal] errors (sign-in, allowance) end the race once no
 *    other brain is still running.
 * No polling: the race sleeps until the next deadline or until a brain reports progress / finishes (0.46.4 heat audit:
 * no busy-wait timers). Losers are cancelled the moment a winner shows output (their sockets close: LlmClient.cancellable).
 * Pure coroutines with an injectable clock (HedgeRaceTest runs it in virtual time).
 */
object HedgeRace {
    data class Limits(val firstMs: Long, val hedgeMs: Long, val stallMs: Long, val thinkMs: Long, val wholeMs: Long)

    class TooSlow(val why: String) : Exception(why)

    /** One brain being asked. The call reports progress through [visible] / [think] (cheap, any thread). */
    class Attempt<S>(val step: S, val index: Int, val startedAt: Long, private val now: () -> Long, private val winner: AtomicReference<Attempt<S>?>,
                     private val wake: () -> Unit = {}) {
        @Volatile var visibleAt = 0L; private set
        @Volatile var thinkAt = 0L; private set
        @Volatile var lastAt = 0L; private set
        @Volatile internal var dropped = false
        /** Reply text or a tool call arrived. Returns true while this attempt is (or just became) the winner. */
        fun visible(): Boolean {
            val t = now(); val first = visibleAt == 0L; if (first) visibleAt = t; lastAt = t
            if (dropped) return false
            val won = winner.compareAndSet(null, this)
            if (won || first) wake()
            return won || winner.get() === this
        }
        /** A thinking chunk arrived (keeps a winner alive, never wins a race). */
        fun think() { val t = now(); if (thinkAt == 0L) { thinkAt = t; wake() }; lastAt = t }
        /** May this attempt's output reach the screen right now? */
        fun shows(): Boolean = !dropped && winner.get() === this
        internal var job: Deferred<Result<Any?>>? = null
    }

    class Outcome<S, T>(val value: T, val step: S, val index: Int, val firstVisibleMs: Long, val hedged: Boolean)

    suspend fun <S, T> run(
        steps: List<S>,
        limits: Limits,
        streaming: Boolean,
        call: suspend (Attempt<S>) -> T,
        onStatus: (String) -> Unit = {},
        onFail: (S, Throwable) -> Unit = { _, _ -> },
        fatal: (S, Throwable) -> Boolean = { _, _ -> false },
        onLose: (S, Long) -> Unit = { _, _ -> },
        allFailed: (last: Throwable?, fatal: Throwable?) -> Throwable = { l, f -> f ?: l ?: IllegalStateException("no brain answered") },
        now: () -> Long = { System.currentTimeMillis() },
        switching: String = "Switching to a faster brain",
    ): Outcome<S, T> {
        require(steps.isNotEmpty())
        // The brains run in their own scope: returning the winner never waits for a loser's socket to close, and a Stop
        // (cancelling the caller) cancels every brain in the finally below.
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        try {
            val start = now()
            val winner = AtomicReference<Attempt<S>?>(null)
            val all = ArrayList<Attempt<S>>()
            var next = 0
            var last: Throwable? = null
            var fatalErr: Throwable? = null
            var said = false
            var hedged = false
            val wake = Channel<Unit>(Channel.CONFLATED)

            fun live() = all.filter { !it.dropped }

            fun launch(why: String) {
                if (next >= steps.size || fatalErr != null) return
                val a = Attempt(steps[next], next, now(), now, winner) { wake.trySend(Unit) }
                next++
                @Suppress("UNCHECKED_CAST")
                a.job = scope.async(start = CoroutineStart.DEFAULT) {
                    try { Result.success(call(a) as Any?) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
                }.also { j -> j.invokeOnCompletion { wake.trySend(Unit) } }
                if (all.isNotEmpty() && !said) { said = true; onStatus(switching) }
                if (why == "hedge") hedged = true
                all += a
            }

            fun drop(a: Attempt<S>, e: Throwable?) {
                if (a.dropped) return
                a.dropped = true
                a.job?.cancel()
                winner.compareAndSet(a, null)
                if (e != null) {
                    onFail(a.step, e); last = e
                    if (fatal(a.step, e)) fatalErr = fatalErr ?: e
                }
            }

            launch("first")
            while (true) {
                // finished attempts
                for (a in live()) {
                    val j = a.job ?: continue
                    if (!j.isCompleted) continue
                    val r = try { j.await() } catch (e: CancellationException) { drop(a, null); continue }
                    if (r.isSuccess) {
                        val w = winner.get()
                        if (w == null || w === a) {
                            winner.set(a)
                            all.filter { it !== a }.forEach { if (!it.dropped) { it.dropped = true; it.job?.cancel(); onLose(it.step, now() - it.startedAt) } }
                            @Suppress("UNCHECKED_CAST")
                            val v = r.getOrNull() as T
                            val first = (if (a.visibleAt > 0) a.visibleAt else now()) - start
                            return Outcome(v, a.step, a.index, first, hedged)
                        }
                        drop(a, null)
                    } else drop(a, r.exceptionOrNull())
                }
                val t = now()
                // deadlines
                for (a in live()) {
                    val w = winner.get()
                    when {
                        w === a -> if (t - maxOf(a.lastAt, a.startedAt) > limits.stallMs) drop(a, TooSlow("stalled ${limits.stallMs / 1000}s"))
                        w != null -> { a.dropped = true; a.job?.cancel(); onLose(a.step, t - a.startedAt) }   // lost the race
                        !streaming -> if (t - a.startedAt > limits.wholeMs) drop(a, TooSlow("no answer in ${limits.wholeMs / 1000}s"))
                        else -> {
                            val cap = if (a.thinkAt > 0 && limits.thinkMs > limits.firstMs) limits.thinkMs else limits.firstMs
                            if (t - a.startedAt > cap) drop(a, TooSlow("no first token in ${cap / 1000}s"))
                            else if (a.thinkAt > 0 && t - a.lastAt > limits.stallMs) drop(a, TooSlow("thinking stalled"))
                        }
                    }
                }
                val running = live()
                if (running.isEmpty()) {
                    if (next >= steps.size || fatalErr != null) throw allFailed(last, fatalErr)
                    launch("failover"); continue
                }
                // hedge: nobody has shown anything and the newest brain has had its head start
                val canHedge = streaming && limits.hedgeMs > 0 && winner.get() == null && next < steps.size
                if (canHedge && t - running.maxOf { it.startedAt } >= limits.hedgeMs) { launch("hedge"); continue }
                // sleep until the next deadline (or a brain's progress / end wakes us)
                var due = Long.MAX_VALUE
                val w = winner.get()
                for (a in live()) due = minOf(due, when {
                    w === a -> maxOf(a.lastAt, a.startedAt) + limits.stallMs
                    w != null -> t
                    !streaming -> a.startedAt + limits.wholeMs
                    else -> minOf(a.startedAt + (if (a.thinkAt > 0 && limits.thinkMs > limits.firstMs) limits.thinkMs else limits.firstMs),
                        if (a.thinkAt > 0) a.lastAt + limits.stallMs else Long.MAX_VALUE)
                }.let { if (it == Long.MAX_VALUE || it == t) it else it + 1 })   // drops fire just past their deadline (">")
                if (canHedge) due = minOf(due, running.maxOf { it.startedAt } + limits.hedgeMs)   // the hedge fires at it (">=")
                val wait = (due - t).coerceIn(1L, 60_000L)
                withTimeoutOrNull(wait) { wake.receive() }
            }
        } finally { scope.cancel() }
    }
    /**
     * One brain that can't be raced (a Cloud run: the gateway keeps it): [block] is given up (null) when, once [armedAt]
     * says it started (0 = not yet, e.g. the message is still being sent), no progress came in [firstMs], or the last
     * progress ([lastAt], 0 = none yet) is older than [stallMs]. Keep-alive beats are not progress. [block] runs in its own
     * scope so giving up never waits for its socket.
     */
    suspend fun <T> watch(firstMs: Long, stallMs: Long, armedAt: () -> Long, lastAt: () -> Long,
                          now: () -> Long = { System.currentTimeMillis() }, tickMs: Long = 1_000L, block: suspend () -> T): T? {
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        try {
            val d = scope.async { block() }
            while (!d.isCompleted) {
                val a = armedAt()
                // not armed yet (still sending): look again after [tickMs]; armed: sleep to the deadline, then re-check
                // (progress moves the deadline, so this wakes about once per deadline, never on a fast tick)
                val wait = if (a <= 0L) tickMs else { val l = lastAt(); val t = now()
                    val due = if (l <= 0L) a + firstMs else maxOf(l, a) + stallMs
                    if (t > due) return null
                    due - t + 1 }
                withTimeoutOrNull(wait) { d.join() }
            }
            return d.await()
        } finally { scope.cancel() }
    }
}
