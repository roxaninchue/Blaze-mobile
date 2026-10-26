package com.blaze.assistant.agent

/**
 * v0.45.28, pure (BackupStatusTest): the one status line Settings › On-phone brain shows, and which buttons go with it.
 */
object BackupStatus {
    enum class State { NOT_SUPPORTED, INSTALLED, RESTING, OFF, DOWNLOADING, NEEDS_LINUX, WAITING_WIFI, NOT_DOWNLOADED }

    fun state(p: BackupNudge.Phone, downloading: Boolean, freeUntil: Long, now: Long): State = when {
        !p.supported -> State.NOT_SUPPORTED
        p.backupInstalled -> if (freeUntil > now) State.RESTING else State.INSTALLED
        downloading -> State.DOWNLOADING
        !p.enabled -> State.OFF
        !p.linuxInstalled -> State.NEEDS_LINUX
        !p.unmetered && !p.mobileDataOk -> State.WAITING_WIFI
        else -> State.NOT_DOWNLOADED
    }

    /** [backAt] = the reset time already formatted for the phone ("14:05"). */
    fun line(s: State, version: String, mb: Long, backAt: String = ""): String = when (s) {
        State.NOT_SUPPORTED -> "Not supported on this phone (it needs a 64-bit phone)."
        State.INSTALLED -> "On-phone brain ready."
        State.RESTING -> "On-phone brain ready. Free allowance used" + (if (backAt.isNotBlank()) ", back at $backAt." else ", back later.")
        State.OFF -> "Off. Blaze answers with its online brains."
        State.DOWNLOADING -> "Getting your on-phone brain ready (one-time)…"
        State.NEEDS_LINUX -> "Getting ready: a small one-time setup comes first."
        State.WAITING_WIFI -> "Not set up yet: it finishes by itself on Wi-Fi (about $mb MB, one time)."
        State.NOT_DOWNLOADED -> "Not set up yet (about $mb MB, one time)."
    }

    /** 0.46.3: the startup health check line ("" = not checked / not installed). [at] = the time already formatted. */
    fun healthLine(ok: Boolean?, at: String): String = when (ok) {
        null -> ""
        true -> "Health check: answering" + (if (at.isNotBlank()) " ($at)." else ".")
        false -> "Health check: not answering" + (if (at.isNotBlank()) " ($at)" else "") + ". Blaze answers with its online brains meanwhile."
    }

    /** "Download now" is offered for these. */
    fun canDownload(s: State): Boolean = s == State.WAITING_WIFI || s == State.NOT_DOWNLOADED || s == State.OFF

    /** "Install Linux (Alpine)" is offered for this. */
    fun needsLinux(s: State): Boolean = s == State.NEEDS_LINUX

    /** Downloading now would use mobile data: ask first. */
    fun confirmMobileData(p: BackupNudge.Phone): Boolean = !p.unmetered

    /** The wait setting shown in seconds (Settings "backup_ttft_ms"; blank = default 15 s; 0 = errors only). */
    fun waitSeconds(settingMs: String): Int {
        val ms = settingMs.trim().toLongOrNull() ?: return (BackupRoute.DEFAULT_TTFT_MS / 1000).toInt()
        return if (ms <= 0L) 0 else (BackupRoute.ttftMs(true, ms) / 1000).toInt()
    }

    /** −/+ on the wait: 5 s steps, 0 (errors only) .. 120 s; from 0, + goes to the 3 s minimum. */
    fun stepWait(sec: Int, up: Boolean): Int = when {
        up && sec == 0 -> (BackupRoute.MIN_TTFT_MS / 1000).toInt()
        up -> ((sec / 5 + 1) * 5).coerceAtMost((BackupRoute.MAX_TTFT_MS / 1000).toInt())
        sec <= (BackupRoute.MIN_TTFT_MS / 1000).toInt() -> 0
        else -> (((sec + 4) / 5 - 1) * 5).coerceAtLeast((BackupRoute.MIN_TTFT_MS / 1000).toInt())
    }

    fun waitLabel(sec: Int): String = if (sec == 0) "Only on errors" else "$sec s"
}
