package com.blaze.assistant.agent

/**
 * 0.46.3 (reports on 0.46.2: "Blaze Cloud is busy right now. Tap Retry", then "Something went wrong (t8: …)" mid-task).
 * The rule: while any brain is left, nobody sees a busy or provider error. One model call of the agent loop, at ANY turn
 * (the first or the eighth), goes through the brains in [BrainOrder.plan] order (on-device OpenCode, Nous reserve,
 * Blaze Cloud), each with the same messages and tools, so the task goes on with its context. Only when every one failed
 * is an error thrown (shown with Retry). [onSwitch] gets one plain status line when a later brain took over a call.
 * A sign-in / allowance / malformed-request refusal from Blaze Cloud is final ([fixable]). Pure (FailoverTest).
 */
object Failover {
    const val SWITCHED = "Switched to backup"

    class Outcome<T>(val value: T, val via: BackupRoute.Step, val retried: Boolean)

    /** Can another try or brain fix this failure? (Not sign-in, the waiting list, today's allowance, a cancel.) */
    fun fixable(e: Throwable): Boolean {
        if (e is kotlinx.coroutines.CancellationException) return false
        val le = e as? LlmClient.LlmException ?: return e is java.io.IOException
        return BackupRoute.moveOn(le.code, le.message.orEmpty())
    }

    suspend fun <T> run(
        steps: List<BackupRoute.Step>,
        call: suspend (BackupRoute.Step) -> T,
        shapeError: (Throwable) -> Boolean = { false },
        onSwitch: (String) -> Unit = {},
        onFail: (BackupRoute.Step, Throwable) -> Unit = { _, _ -> },
        onOk: (BackupRoute.Step) -> Unit = {},
    ): Outcome<T> {
        if (steps.isEmpty()) throw LlmClient.LlmException(Cloud.SIGNED_OUT, 401)
        var cloudErr: Throwable? = null
        var last: Throwable? = null
        steps.forEachIndexed { i, step ->
            try {
                val v = call(step)
                if (i > 0) onSwitch(SWITCHED)
                onOk(step)
                return Outcome(v, step, i > 0)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onFail(step, e)
                last = e
                if (step == BackupRoute.Step.CLOUD) {
                    cloudErr = e
                    // what no other brain fixes (sign-in, allowance, a refused request shape) is final
                    if (!fixable(e) || shapeError(e)) throw e
                }
            }
        }
        val err = cloudErr ?: last!!
        throw if (err is LlmClient.TooSlow || err !is LlmClient.LlmException) LlmClient.LlmException(Cloud.BUSY, 503) else err
    }

    /** The 0.46.3-early shape (cloud first): kept for callers that pass explicit lambdas; same order rule as [BrainOrder]. */
    suspend fun <T> run(cloud: suspend () -> T, backupReady: Boolean, backup: suspend () -> T, reserve: suspend () -> T,
                        shapeError: (Throwable) -> Boolean = { false }, onSwitch: (String) -> Unit = {},
                        onFail: (BackupRoute.Step, Throwable) -> Unit = { _, _ -> }): Outcome<T> =
        run(BrainOrder.plan(backupReady), { s -> when (s) { BackupRoute.Step.BACKUP -> backup(); BackupRoute.Step.RESERVE -> reserve(); BackupRoute.Step.CLOUD -> cloud() } },
            shapeError, onSwitch, onFail)
}
