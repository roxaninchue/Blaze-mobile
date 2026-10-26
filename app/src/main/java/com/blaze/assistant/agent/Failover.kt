package com.blaze.assistant.agent

/**
 * 0.46.3 (report on 0.46.2: "Blaze Cloud is busy right now. Tap Retry", then "Something went wrong (t8: Blaze Cloud is
 * busy…)" mid-task, and the backup brain never answered). The rule: while any brain is left, nobody sees a busy or
 * provider error. One model call of the agent loop, at ANY turn (the first or the eighth):
 *  1. Blaze Cloud (its own quiet retries, Cloud.drive), [retryOnce] = one more plain try for a busy / 5xx / timeout /
 *     network failure;
 *  2. then the steps [BackupRoute.fallbacks] gives (the on-device backup brain when ready, then the gateway's Nous
 *     reserve), each answering with the same messages and tools, so the task goes on with its context;
 *  3. only when every one failed, the first error is thrown (shown with Retry).
 * [onSwitch] gets one plain status line when another brain took over ("Switched to backup"). Pure (FailoverTest).
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
        cloud: suspend () -> T,
        backupReady: Boolean,
        backup: suspend () -> T,
        reserve: suspend () -> T,
        retryOnce: Boolean = false,
        shapeError: (Throwable) -> Boolean = { false },
        onSwitch: (String) -> Unit = {},
        onFail: (BackupRoute.Step, Throwable) -> Unit = { _, _ -> },
    ): Outcome<T> {
        val first: Throwable
        try { return Outcome(cloud(), BackupRoute.Step.CLOUD, false) } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            first = e
        }
        if (!fixable(first) || shapeError(first)) throw first
        if (retryOnce && first !is LlmClient.TooSlow) try { return Outcome(cloud(), BackupRoute.Step.CLOUD, true) } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onFail(BackupRoute.Step.CLOUD, e)
        }
        val le = first as? LlmClient.LlmException
        val steps = BackupRoute.fallbacks(backupReady, le?.code ?: 503, first.message.orEmpty(), false)
        for (step in steps) {
            try {
                val v = when (step) { BackupRoute.Step.BACKUP -> backup(); BackupRoute.Step.RESERVE -> reserve(); else -> continue }
                onSwitch(SWITCHED)
                return Outcome(v, step, retryOnce)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onFail(step, e)
            }
        }
        throw if (first is LlmClient.TooSlow) LlmClient.LlmException(Cloud.BUSY, 503) else first
    }
}
