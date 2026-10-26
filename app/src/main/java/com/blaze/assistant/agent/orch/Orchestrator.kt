package com.blaze.assistant.agent.orch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 0.47.0 orchestrator (work/out/orchestrator-spec.md §1.2): the main Blaze brain hands pieces of one task to specialist
 * sub-agents it fully controls. Each runs in its own context with its own scoped tools ([SpecialistKit]), a step budget
 * and its own deadline; independent ones run in parallel, dependents wait, exclusive lanes (the phone screen, the browser)
 * take turns. Results come back as [Summary]s (never transcripts). The orchestrator can [steer] a running one (the note is
 * read before its next step), [cancel] one or all (kill switch), and failures / timeouts are retried up to the budget.
 *
 * Pure coroutines with an injectable [Worker] and clock (OrchestratorTest runs it in virtual time). The Agent supplies a
 * Worker that runs the specialist's tool loop through the usual brain race (on-phone when warm → Nous → Blaze Cloud).
 */
class Orchestrator(private val worker: Worker, private val now: () -> Long = { System.currentTimeMillis() }) {

    data class Budget(val steps: Int, val wallMs: Long, val retries: Int = 1)

    data class SubTask(val id: String, val specialist: String, val brief: String, val deps: Set<String> = emptySet(),
                       val budget: Budget? = null)

    enum class Status { DONE, PARTIAL, FAILED, TIMED_OUT, CANCELLED }

    /** What a specialist hands back: a short text, the proofs it gathered and the actions it took (plain lines). */
    data class Summary(val id: String, val specialist: String, val status: Status, val text: String,
                       val proofs: List<String> = emptyList(), val actions: List<String> = emptyList(),
                       val attempts: Int = 1, val ms: Long = 0L) {
        val ok get() = status == Status.DONE || status == Status.PARTIAL
    }

    sealed class Event(val id: String) {
        class Started(id: String, val specialist: String, val attempt: Int) : Event(id)
        class Step(id: String, val specialist: String, val line: String) : Event(id)
        class Retrying(id: String, val specialist: String, val why: String) : Event(id)
        class Finished(id: String, val summary: Summary) : Event(id)
    }

    /** What one specialist run can do: report a step line, read steering notes, check the step budget. */
    interface Run {
        val task: SubTask
        val attempt: Int
        val budget: Budget
        /** A plain progress line (feeds the status pill). */
        fun step(line: String)
        /** Steering notes that arrived since the last call (empty when none). */
        fun steering(): List<String>
        /** Counts one model/tool step; false once the step budget is spent (the worker must wrap up). */
        fun spend(): Boolean
        val stepsUsed: Int
        /** Results of the dependencies (by subtask id), for the brief. */
        val inputs: Map<String, Summary>
    }

    fun interface Worker { suspend fun run(r: Run): Summary }

    class BudgetSpent(msg: String) : Exception(msg)

    private class Live(val task: SubTask, val budget: Budget) {
        val notes = ConcurrentLinkedQueue<String>()
        @Volatile var job: Job? = null
        @Volatile var cancelled = false
    }

    private val live = ConcurrentHashMap<String, Live>()
    private val lanes = ConcurrentHashMap<String, Mutex>()
    @Volatile private var scope: CoroutineScope? = null
    @Volatile var killed = false; private set

    /** The ids still running (or waiting for a dependency / a lane). */
    fun running(): Set<String> = live.keys.toSet()

    /** Adds a note to a running subtask (or to all running when [id] is null). False if nothing took it. */
    fun steer(id: String?, note: String): Boolean {
        val targets = if (id == null) live.values.toList() else listOfNotNull(live[id])
        targets.forEach { it.notes.add(note) }
        return targets.isNotEmpty()
    }

    fun cancel(id: String): Boolean { val l = live[id] ?: return false; l.cancelled = true; l.job?.cancel(); return true }

    /** Kill switch: every running and waiting subtask stops; nothing new starts in this run. */
    fun cancelAll() { killed = true; live.values.forEach { it.cancelled = true; it.job?.cancel() }; scope?.cancel() }

    /**
     * Runs [plan] to the end and returns every subtask's summary (in plan order). Subtasks whose dependency did not
     * succeed are reported FAILED without running ("needs X, which failed"). Cancellation of the caller cancels all.
     */
    suspend fun run(plan: List<SubTask>, onEvent: (Event) -> Unit = {}): List<Summary> {
        require(plan.map { it.id }.toSet().size == plan.size) { "duplicate subtask ids" }
        val byId = plan.associateBy { it.id }
        plan.forEach { t -> t.deps.forEach { d -> require(d in byId) { "unknown dependency $d of ${t.id}" } } }
        val done = ConcurrentHashMap<String, CompletableDeferred<Summary>>()
        plan.forEach { done[it.id] = CompletableDeferred() }
        val sc = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        scope = sc
        try {
            for (t in plan) {
                val budget = t.budget ?: SpecialistKit.byId(t.specialist)?.budget ?: SpecialistKit.DEFAULT_BUDGET
                val l = Live(t, budget)
                live[t.id] = l
                l.job = sc.launch {
                    val out = try {
                        val inputs = t.deps.associateWith { done.getValue(it).await() }
                        val bad = inputs.values.firstOrNull { !it.ok }
                        if (bad != null) Summary(t.id, t.specialist, Status.FAILED, "Skipped: needs ${bad.specialist} (${bad.id}), which did not finish.")
                        else {
                            val lane = SpecialistKit.byId(t.specialist)?.takeIf { it.exclusive }?.let { lanes.getOrPut(it.lane) { Mutex() } }
                            if (lane != null) lane.withLock { attempts(l, inputs, onEvent) } else attempts(l, inputs, onEvent)
                        }
                    } catch (e: CancellationException) {
                        Summary(t.id, t.specialist, Status.CANCELLED, if (killed) "Stopped." else "Cancelled.")
                    }
                    live.remove(t.id)
                    if (done.getValue(t.id).complete(out)) onEvent(Event.Finished(t.id, out))
                }.also { j -> j.invokeOnCompletion {
                    // cancelled before it even started (kill switch right after launch): still report it, never hang
                    live.remove(t.id)
                    val c = Summary(t.id, t.specialist, Status.CANCELLED, if (killed) "Stopped." else "Cancelled.")
                    if (done.getValue(t.id).complete(c)) onEvent(Event.Finished(t.id, c))
                } }
            }
            return plan.map { done.getValue(it.id).await() }
        } finally {
            live.clear()
            scope = null
            sc.cancel()   // its SupervisorJob is a child of the caller's job: never leave it open (runTest / the turn would wait on it)
        }
    }

    /**
     * One subtask's attempts, with the 0.46.5 race semantics (never drop slow-but-working):
     *  - the budget's wall time only STARTS a hedge (a second attempt beside the first, when retries remain and the lane
     *    isn't exclusive); it never cancels an attempt that is still making progress;
     *  - an attempt is dropped only when it fails, or when it has shown no progress (a step or a spent step) for [STALL_MS]
     *    after its wall time;
     *  - the first attempt to SUCCEED wins and the others are cancelled then; a failure while another attempt is alive is no
     *    error (the live one carries on);
     *  - with nothing alive and retries left, a fresh attempt starts with the failure as a note.
     */
    private suspend fun attempts(l: Live, inputs: Map<String, Summary>, onEvent: (Event) -> Unit): Summary = coroutineScope {
        val t = l.task
        val t0 = now()
        val exclusive = SpecialistKit.byId(t.specialist)?.exclusive == true
        val maxAttempts = 1 + l.budget.retries.coerceAtLeast(0)
        val wake = Channel<Unit>(Channel.CONFLATED)
        class Att(val n: Int, val startedAt: Long) { @Volatile var lastAt = startedAt; var job: Deferred<Summary>? = null; var dropped = false }
        val atts = ArrayList<Att>()
        var started = 0
        var why = ""
        var last: Summary? = null

        fun launchAttempt() {
            if (l.cancelled || killed) throw CancellationException("cancelled")
            started++
            val n = started
            if (n > 1) { onEvent(Event.Retrying(t.id, t.specialist, why.ifBlank { "taking long" })); if (why.isNotBlank()) l.notes.add("The previous try did not work: $why. Try a different way.") }
            onEvent(Event.Started(t.id, t.specialist, n))
            val a = Att(n, now())
            var used = 0
            val run = object : Run {
                override val task = t
                override val attempt = n
                override val budget = l.budget
                override val inputs = inputs
                override val stepsUsed get() = used
                override fun step(line: String) { a.lastAt = now(); onEvent(Event.Step(t.id, t.specialist, line)) }
                override fun steering(): List<String> = generateSequence { l.notes.poll() }.toList()
                override fun spend(): Boolean { a.lastAt = now(); used++; return used <= l.budget.steps }
            }
            a.job = async {
                try { worker.run(run) }
                catch (e: CancellationException) { throw e }
                catch (e: BudgetSpent) { Summary(t.id, t.specialist, Status.PARTIAL, e.message ?: "Used all its steps.") }
                catch (e: Exception) { Summary(t.id, t.specialist, Status.FAILED, "Failed: ${e.message.orEmpty().take(160)}") }
            }.also { it.invokeOnCompletion { wake.trySend(Unit) } }
            atts += a
        }

        fun live() = atts.filter { !it.dropped }
        launchAttempt()
        while (true) {
            for (a in live()) {
                val j = a.job ?: continue
                if (!j.isCompleted) continue
                val r = try { j.await() } catch (e: CancellationException) { a.dropped = true; continue }
                a.dropped = true
                if (r.ok) {
                    atts.filter { !it.dropped }.forEach { it.dropped = true; it.job?.cancel() }
                    return@coroutineScope r.copy(attempts = a.n, ms = now() - t0)
                }
                last = r.copy(attempts = a.n, ms = now() - t0); why = r.text.take(120)
            }
            val tNow = now()
            // stall: past its wall time AND silent for STALL_MS -> dropped (the only way a live attempt is cut)
            for (a in live()) if (tNow - a.startedAt > l.budget.wallMs && tNow - a.lastAt > STALL_MS) {
                a.dropped = true; a.job?.cancel()
                last = Summary(t.id, t.specialist, Status.TIMED_OUT, "Ran out of time after ${(tNow - t0) / 1000} s.", attempts = a.n, ms = tNow - t0)
                why = "it stalled"
            }
            val running = live()
            if (running.isEmpty()) {
                if (started < maxAttempts && !l.cancelled && !killed) { launchAttempt(); continue }
                return@coroutineScope last ?: Summary(t.id, t.specialist, Status.FAILED, "Didn't finish.", attempts = started, ms = tNow - t0)
            }
            // hedge: the newest attempt passed its wall time without finishing -> one more beside it (never on an exclusive lane)
            val newest = running.maxOf { it.startedAt }
            val canHedge = !exclusive && started < maxAttempts
            if (canHedge && tNow - newest >= l.budget.wallMs) { launchAttempt(); continue }
            var due = Long.MAX_VALUE
            if (canHedge) due = minOf(due, newest + l.budget.wallMs)
            for (a in running) due = minOf(due, maxOf(a.startedAt + l.budget.wallMs, a.lastAt + STALL_MS) + 1)
            withTimeoutOrNull((due - tNow).coerceIn(1L, 60_000L)) { wake.receive() }
        }
        @Suppress("UNREACHABLE_CODE") last!!
    }

    companion object {
        /** A live attempt past its wall time is dropped only after this long with no progress at all. */
        const val STALL_MS = 30_000L
    }
}
