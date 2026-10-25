package com.blaze.assistant.agent

/**
 * v0.45.27, pure (BackupNudgeTest): what the chat says when a request failed for good (Blaze Cloud busy or erroring and
 * every fallback, the gateway's Nous reserve included, failed too) on a phone that could run the on-device backup brain
 * ([BackupBrain]) but doesn't have it yet. Instead of the plain Blaze Cloud error, one normal Blaze message that says
 * the next step: try again later, or get on Wi-Fi for the one-time download. On Wi-Fi the download starts right away.
 *
 * Never for what the backup can't fix: a sign-in problem, the waiting list, today's free messages, a request too big
 * for any model, or no internet at all (the plain line about the connection is the useful one then).
 */
object BackupNudge {
    enum class Pick {
        /** Keep the normal error line. */
        NONE,
        /** Linux is there; not on Wi-Fi now: "get on Wi-Fi and I'll download …". */
        LATER,
        /** Alpine Linux isn't installed yet: it is needed first. */
        NEEDS_LINUX,
        /** On Wi-Fi (or mobile data allowed) with Linux installed: the download was started. */
        DOWNLOADING,
    }

    /** What the phone looks like right now (gathered by [BackupBrain.phone]). */
    data class Phone(
        /** 64-bit ARM phone where Blaze's Linux runs (OpenCode has no 32-bit build). */
        val supported: Boolean,
        /** Settings "backup_brain" (on by default). */
        val enabled: Boolean,
        val linuxInstalled: Boolean,
        val backupInstalled: Boolean,
        /** Any working internet (validated). */
        val online: Boolean,
        val unmetered: Boolean,
        /** Settings "backup_brain_mobile_data": the one-time download may use mobile data. */
        val mobileDataOk: Boolean = false,
    )

    /** Was this final failure Blaze Cloud being busy / erroring / unreachable (worth the nudge)? */
    fun cloudFailure(code: Int, message: String): Boolean {
        val t = message.trim()
        val low = t.lowercase()
        if (!BackupRoute.moveOn(code, t)) return false                      // sign-in, waiting list, today's allowance
        if (code == 413 || t == PlainError.CONTEXT || "too much for me" in low || "context length" in low || "too large" in low) return false
        if (t == Cloud.BUSY || t == Cloud.UNREACHABLE) return true
        return code == 0 || code == 400 || code == 404 || code == 408 || code == 409 || code == 429 || code in 500..599
    }

    fun pick(code: Int, message: String, p: Phone): Pick = when {
        !p.supported || !p.enabled || p.backupInstalled -> Pick.NONE
        !p.online -> Pick.NONE
        !cloudFailure(code, message) -> Pick.NONE
        !p.linuxInstalled -> Pick.NEEDS_LINUX
        p.unmetered || p.mobileDataOk -> Pick.DOWNLOADING
        else -> Pick.LATER
    }

    /** "78 MB" style size (decimal megabytes, rounded), as the changelog states it. */
    fun mb(bytes: Long): Long = ((bytes + 500_000L) / 1_000_000L).coerceAtLeast(1)

    const val OOPS = "Oops, I ran into an error."

    /** The chat line for [pick] (null = keep the normal error). */
    fun line(pick: Pick, bytes: Long): String? {
        val size = "${mb(bytes)} MB"
        return when (pick) {
            Pick.NONE -> null
            Pick.LATER -> "$OOPS You can try again when Blaze Cloud is available, or get on Wi-Fi and I'll download a $size feature to make sure this doesn't happen again."
            Pick.NEEDS_LINUX -> "$OOPS You can try again when Blaze Cloud is available, or install Alpine Linux in the Terminal and get on Wi-Fi, and I'll download a $size feature to make sure this doesn't happen again."
            Pick.DOWNLOADING -> "$OOPS You can try again when Blaze Cloud is available. Meanwhile I'm downloading a $size feature to make sure this doesn't happen again."
        }
    }

    /** Minimum time between two automatic download starts after a failed one (network flapping). */
    const val AUTO_RETRY_MS = 10 * 60_000L

    /** Should a Wi-Fi connection (or app start) start the one-time download now? */
    fun autoInstall(p: Phone, now: Long, lastTryAt: Long): Boolean =
        p.supported && p.enabled && p.linuxInstalled && !p.backupInstalled && p.online && (p.unmetered || p.mobileDataOk) &&
            (lastTryAt == 0L || now - lastTryAt >= AUTO_RETRY_MS)
}
