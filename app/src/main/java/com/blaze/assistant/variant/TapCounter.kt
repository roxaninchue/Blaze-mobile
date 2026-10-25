package com.blaze.assistant.variant

/**
 * Counts quick taps on one target ("tap the version 7 times"). A gap longer than [maxGapMs] starts over.
 * [tap] returns how many taps are still needed; 0 means the count was just reached (then it starts over).
 */
class TapCounter(private val needed: Int = 7, private val maxGapMs: Long = 2_000L) {
    private var count = 0
    private var last = Long.MIN_VALUE

    fun tap(nowMs: Long): Int {
        count = if (last != Long.MIN_VALUE && nowMs - last in 0..maxGapMs) count + 1 else 1
        last = nowMs
        if (count >= needed) { count = 0; last = Long.MIN_VALUE; return 0 }
        return needed - count
    }
}
