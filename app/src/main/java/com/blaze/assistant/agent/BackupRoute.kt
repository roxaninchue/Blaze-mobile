package com.blaze.assistant.agent

/**
 * v0.45.26, pure (BackupRouteTest): the order Blaze asks for an answer in, and when it moves on.
 *
 *  1. Blaze Cloud (the gateway's normal routing) — always first.
 *  2. The on-device backup brain ([BackupBrain]: OpenCode's free models, strongest first) — only when Blaze Cloud
 *     failed with an error worth working around, or was too slow to start answering ([ttftMs]).
 *  3. The Nous reserve on the gateway (`X-Blaze-Reserve: only`, the owner's key, slower but consistent) — last.
 *
 * When the backup brain isn't ready (32-bit phone, Linux not installed, still downloading, server down) step 2 is
 * skipped and the gateway puts its reserve after its own lineup in the same call (no header = "auto"), so a phone
 * without the backup still ends on the reserve. The person never sees which step answered: the route chip says
 * "Blaze Cloud" for all three.
 */
object BackupRoute {
    enum class Step { CLOUD, BACKUP, RESERVE }

    /** The `X-Blaze-Reserve` header value for a gateway call at [step] ("" = none: the gateway's default, auto). */
    fun reserveHeader(step: Step, backupReady: Boolean): String = when {
        step == Step.RESERVE -> "only"
        step == Step.CLOUD && backupReady -> "skip"
        else -> ""
    }

    /** The steps for one request. */
    fun plan(backupReady: Boolean): List<Step> = if (backupReady) listOf(Step.CLOUD, Step.BACKUP, Step.RESERVE) else listOf(Step.CLOUD)

    /** Defaults for "too slow": no first chunk (text, thinking or a tool call) within this long. */
    const val DEFAULT_TTFT_MS = 15_000L
    const val DEFAULT_TTFT_NOSTREAM_MS = 45_000L
    const val MIN_TTFT_MS = 3_000L
    const val MAX_TTFT_MS = 120_000L

    /**
     * The first-chunk deadline for Blaze Cloud before the backup takes over. [setting] is the saved value in ms
     * (Settings "backup_ttft_ms"; null/blank = default; 0 = never switch for slowness, only for errors). A
     * non-streaming call's first chunk is its whole answer, so it gets the longer default (or 3× the setting).
     */
    fun ttftMs(streaming: Boolean, setting: Long?): Long {
        if (setting == 0L) return 0L
        val base = setting?.takeIf { it > 0 }?.coerceIn(MIN_TTFT_MS, MAX_TTFT_MS)
        return if (streaming) base ?: DEFAULT_TTFT_MS else (base?.let { (it * 3).coerceAtMost(MAX_TTFT_MS) } ?: DEFAULT_TTFT_NOSTREAM_MS)
    }

    /**
     * Should the next step run after Blaze Cloud (or the backup) failed with [code] / [message]? Not for what no other
     * brain can fix: a sign-in problem, the waiting list, today's allowance used up, a request the user cancelled.
     */
    fun moveOn(code: Int, message: String): Boolean {
        val t = message.lowercase()
        if (code == 401 || code == 403) return false
        if (Cloud.access(code, message) != null) return false
        if (t == Cloud.SIGNED_OUT.lowercase() || t == Cloud.EXPIRED.lowercase()) return false
        if (code == 429 && ("allowance" in t || "quota_exceeded" in t || "today's free messages" in t)) return false
        return true
    }

    /**
     * v0.45.28: what runs after Blaze Cloud's own call failed for good, in order. The Nous reserve on the gateway
     * (`X-Blaze-Reserve: only`) ALWAYS gets a turn for a failure another brain can fix (busy, 5xx, 429 that isn't the
     * allowance, too slow, no answer, a stream that died mid-reply), also when the backup brain isn't ready: before this,
     * a phone without the backup showed "Blaze Cloud is busy" without ever asking the reserve. [shapeError] (the request
     * itself was refused) and what [moveOn] rules out (sign-in, waiting list, allowance) get nothing.
     */
    fun fallbacks(backupReady: Boolean, code: Int, message: String, shapeError: Boolean): List<Step> = when {
        shapeError || !moveOn(code, message) -> emptyList()
        backupReady -> listOf(Step.BACKUP, Step.RESERVE)
        else -> listOf(Step.RESERVE)
    }

    /** A stream that broke after some words arrived (the fallback's reply replaces the partial one in the same bubble). */
    fun brokeMidReply(message: String): Boolean {
        val t = message.lowercase()
        return "stopped sending" in t || "mid-reply" in t || "stream ended" in t || "unexpected end of stream" in t ||
            "connection reset" in t || "software caused connection abort" in t || "stream was reset" in t
    }

    /** Has the attempt that started at [startedAt] shown a sign of life ([lastChunkAt] = latest chunk of any kind)? */
    fun alive(startedAt: Long, lastChunkAt: Long, gotFirst: Boolean, callStart: Long): Boolean =
        lastChunkAt >= startedAt || (gotFirst && callStart >= startedAt)

    /** Too slow: no sign of life [ttft] ms after [startedAt] (0 = never). */
    fun tooSlow(startedAt: Long, now: Long, alive: Boolean, ttft: Long): Boolean = ttft > 0 && !alive && now - startedAt >= ttft
}
