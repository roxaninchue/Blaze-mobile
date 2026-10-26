package com.blaze.assistant.phone

/** 0.46.4 heat audit: phone-control work only during a phone task, screenshots rate-capped. Pure (HeatTest). */
object ScreenHeat {
    /** After the last screen read / capture / action, the service keeps its tree cache live this long. */
    const val TASK_IDLE_MS = 60_000L
    /** At most one screenshot per this long (Android itself refuses faster than ~3 a second). */
    const val SHOT_GAP_MS = 700L

    fun shotWait(lastShotAt: Long, now: Long): Long = if (lastShotAt <= 0L) 0L else (SHOT_GAP_MS - (now - lastShotAt)).coerceAtLeast(0L)
}
