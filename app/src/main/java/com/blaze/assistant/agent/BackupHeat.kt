package com.blaze.assistant.agent

/**
 * 0.46.4 heat audit: the on-phone brain's automatic setup never loops. After a failed try the next automatic one waits
 * 10 min, then 20, 40, 80… (at most 6 h), and after [MAX_FAILS] failures only Settings › Download now tries again (until
 * the app restarts). Pure (HeatTest).
 */
object BackupHeat {
    const val BASE_MS = 10 * 60_000L
    const val MAX_MS = 6 * 3600_000L
    const val MAX_FAILS = 3

    fun waitMs(fails: Int): Long = if (fails <= 0) 0L else minOf(MAX_MS, BASE_MS shl minOf(fails - 1, 10))

    fun mayTry(fails: Int, lastTryAt: Long, now: Long): Boolean =
        fails < MAX_FAILS && (lastTryAt == 0L || now - lastTryAt >= waitMs(fails))
}
