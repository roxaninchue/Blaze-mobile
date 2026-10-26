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
 * for over 4 minutes, Halo silent too). One model call asked of several brains with deadlines and a hedge.
 *
 * 0.46.5 fix (Rocky, 26 Oct 21:00, on 0.46.4: a task turn ended in "Blaze Cloud is busy right now" after ~3 min while the
 * on-phone brain and Nous were working, only slowly): in 0.46.4 the first-token deadline DROPPED (cancelled) a slow brain,
 * so the slow-but-working brains were killed one by one and the turn fell through to Blaze Cloud, which was failing.
 * Now a deadline never cancels a brain that may still answer:
 *  - [Limits.hedgeMs] / [Limits.firstMs]: nobody has shown output yet → the NEXT brain is started in parallel (the earlier
 *    ones keep running). Every started brain races until one shows real output (reply text or a tool call); only then
 *    are the others cancelled (their text never reaches the screen: callbacks are gated by [Attempt.visible]).
 *  - A brain is dropped only on a hard error, or on [Limits.stallMs] without progress AFTER it started streaming (a text,
 *    tool or thinking chunk). Hidden thinking counts as alive, never as an answer.
 *  - [Limits.wholeMs]: the outer cap for a brain that shows nothing at all (and [Limits.thinkMs] while it is still sending
 *    thinking), so a turn can't wait forever. Being cut by our own deadline is [TooSlow], not a brain failure.
 *  - An error (busy, 5xx, CPU-killed, rate limit, network) moves on at once to the brains not yet started; [fatal] errors
 *    (sign-in, allowance, refused shape) are remembered but never stop the brains that don't depend on them.
 *  - Every brain failed (hard errors) and none was fatal: [retryPick] chooses one (the most reliable) and it is asked once
 *    more with [retryMs] as its cap, before [allFailed] builds the one calm error.
 *  - A call that does not stream ([streaming] false) has no progress to watch: the first finished answer wins; parallel
 *    starts happen only at [Limits.firstMs] (no early hedge).
 * No polling: the race sleeps until the next deadline or until a brain reports progress / finishes (0.46.4 heat audit).
 * Pure coroutines with an injectable clock (HedgeRaceTest runs it in virtual time).
 */
object HedgeRace {
    data class Limits(val firstMs: Long, val hedgeMs: Long, val stallMs: Long, val thinkMs: Long, val wholeMs: Long)

    class TooSlow(val why: String) : Exception(why)

    /** One brain being asked. The call reports progress through [visible] / [think] (cheap, any thread). */
    class Attempt<S>(val step: S, val index: Int, val startedAt: Long, private val now: () -> Long, private val winner: AtomicReference<Attempt<S>?>,
                     private val wake: () -> Unit = {}, val capMs: Long = 0L, val retry: Boolean = false) {
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
        /** A thinking chunk (or any other sign of life) arrived: keeps the attempt alive, never wins a race. */
        fun think() { val t = now(); if (thinkAt == 0L) { thinkAt = t; wake() }; lastAt = t }
        /** May this attempt's output reach the screen right now? */
        fun shows(): Boolean = !dropped && winner.get() === this
        /** Started streaming (any chunk). */
        val streamed: Boolean get() = lastAt > 0L
        internal var job: Deferred<Result<Any?>>? = null
    }

    class Outcome<S, T>(val value: T, val step: S, val index: Int, val firstVisibleMs: Long, val hedged: Boolean, val retried: Boolean = false)

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
        retryPick: (List<S>) -> S? = { null },
        retryMs: Long = 0L,
        onCancelLoser: (S) -> Unit = {},
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
            var lastHard: Throwable? = null
            var fatalErr: Throwable? = null
            var said = false
            var hedged = false
            var retried = false
            val wake = Channel<Unit>(Channel.CONFLATED)

            fun live() = all.filter { !it.dropped }
            fun cap(a: Attempt<S>) = if (a.capMs > 0) a.capMs else limits.wholeMs
            /** When this attempt (showing nothing yet) is cut by our own outer cap. */
            fun outerDue(a: Attempt<S>): Long {
                val c = cap(a)
                return if (a.thinkAt > 0 && streaming) a.startedAt + maxOf(c, limits.thinkMs) else a.startedAt + c
            }

            fun start(step: S, index: Int, why: String, capMs: Long = 0L) {
                val a = Attempt(step, index, now(), now, winner, { wake.trySend(Unit) }, capMs, why == "retry")
                @Suppress("UNCHECKED_CAST")
                a.job = scope.async(start = CoroutineStart.DEFAULT) {
                    try { Result.success(call(a) as Any?) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
                }.also { j -> j.invokeOnCompletion { wake.trySend(Unit) } }
                if (all.isNotEmpty() && !said) { said = true; onStatus(switching) }
                if (why == "hedge") hedged = true
                all += a
            }
            fun launch(why: String) { if (next >= steps.size) return; start(steps[next], next, why); next++ }

            fun cancelLoser(a: Attempt<S>, t: Long) { if (!a.dropped) { a.dropped = true; a.job?.cancel(); onCancelLoser(a.step); onLose(a.step, t - a.startedAt) } }

            fun drop(a: Attempt<S>, e: Throwable?) {
                if (a.dropped) return
                a.dropped = true
                a.job?.cancel()
                winner.compareAndSet(a, null)
                if (e != null) {
                    onFail(a.step, e); last = e
                    if (e !is TooSlow) lastHard = e
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
                            val t = now()
                            all.filter { it !== a }.forEach { cancelLoser(it, t) }
                            @Suppress("UNCHECKED_CAST")
                            val v = r.getOrNull() as T
                            val first = (if (a.visibleAt > 0) a.visibleAt else t) - start
                            return Outcome(v, a.step, a.index, first, hedged, a.retry)
                        }
                        drop(a, null)
                    } else drop(a, r.exceptionOrNull())
                }
                val t = now()
                // deadlines: only the winner can stall out; the rest race on until someone shows output (or their outer cap)
                val w0 = winner.get()
                for (a in live()) {
                    when {
                        w0 === a -> if (t - maxOf(a.lastAt, a.startedAt) > limits.stallMs) drop(a, TooSlow("stalled ${limits.stallMs / 1000}s"))
                        w0 != null -> cancelLoser(a, t)   // lost the race: someone showed real output
                        a.streamed && a.thinkAt > 0 && t - a.lastAt > limits.stallMs -> drop(a, TooSlow("thinking stalled ${limits.stallMs / 1000}s"))
                        t > outerDue(a) -> drop(a, TooSlow("no answer in ${(outerDue(a) - a.startedAt) / 1000}s"))
                    }
                }
                val running = live()
                if (running.isEmpty()) {
                    if (next < steps.size) { launch("failover"); continue }
                    // every brain failed: the most reliable one gets one more go with the long cap (never after a fatal error)
                    if (!retried && fatalErr == null && retryMs > 0) {
                        retried = true
                        val pick = retryPick(steps)
                        if (pick != null) { start(pick, steps.indexOf(pick).coerceAtLeast(0), "retry", retryMs); continue }
                    }
                    throw allFailed(lastHard ?: last, fatalErr)
                }
                // parallel start: nobody has shown anything and the newest brain has had its head start (hedge) or hit its
                // first-token deadline. Earlier brains keep running.
                val nobody = winner.get() == null && next < steps.size
                val newest = running.maxOf { it.startedAt }
                val headStart = if (streaming && limits.hedgeMs > 0) minOf(limits.hedgeMs, limits.firstMs) else limits.firstMs
                if (nobody && t - newest >= headStart) { launch(if (streaming && limits.hedgeMs > 0 && headStart == limits.hedgeMs) "hedge" else "deadline"); continue }
                // sleep until the next deadline (or a brain's progress / end wakes us)
                var due = Long.MAX_VALUE
                val w = winner.get()
                for (a in live()) due = minOf(due, when {
                    w === a -> maxOf(a.lastAt, a.startedAt) + limits.stallMs
                    w != null -> t
                    else -> minOf(outerDue(a), if (a.streamed && a.thinkAt > 0) a.lastAt + limits.stallMs else Long.MAX_VALUE)
                }.let { if (it == Long.MAX_VALUE || it == t) it else it + 1 })   // drops fire just past their deadline (">")
                if (nobody) due = minOf(due, newest + headStart)   // the parallel start fires at it (">=")
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
