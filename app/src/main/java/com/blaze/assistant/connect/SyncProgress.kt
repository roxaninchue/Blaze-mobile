package com.blaze.assistant.connect

/**
 * 0.45.24: how far a cloud sync round is, for the "Syncing with Blaze Cloud" notification ([SyncNote]).
 *
 * [SyncEngine] reports a [Step] after every pulled page and every pushed batch. Pulling has no item count up front, so
 * a full restore (cursor at 0) measures bytes against the cloud copy's size, and a catch-up round counts items with an
 * indeterminate bar. Pushing knows how many items it sends. Small background rounds (a message or two) stay silent;
 * a restore, a round that moves [SHOW_AT] items or more, and Sync now are shown. Pure (SyncProgressTest).
 */
object SyncProgress {
    enum class Phase { PULL, PUSH }

    data class Step(
        val phase: Phase,
        val done: Int,
        /** Items in this phase; 0 = not known yet. */
        val total: Int = 0,
        val bytesDone: Long = 0,
        /** Size of the cloud copy for a full restore; 0 = not known. */
        val bytesTotal: Long = 0,
        /** The whole account copy is coming down (first round on this phone, or a reset). */
        val full: Boolean = false,
        /** More pages are waiting on the server. */
        val more: Boolean = false,
    )

    const val SHOW_AT = 25

    /** True when this round is worth a notification. */
    fun shouldShow(s: Step, manual: Boolean): Boolean =
        manual || s.full || s.done >= SHOW_AT || s.total >= SHOW_AT || (s.phase == Phase.PULL && s.more)

    /** 0..100, or null when the bar is indeterminate. Capped at 99 until the round really ends. */
    fun percent(s: Step): Int? {
        val p = when {
            s.total > 0 -> s.done * 100L / s.total
            s.bytesTotal > 0 -> s.bytesDone * 100L / s.bytesTotal
            else -> return null
        }
        return p.coerceIn(0, 99).toInt()
    }

    /** "Downloading 120 of 300 · 40%", "Downloading · 2.1 MB of 6.0 MB · 35%", "Downloading · 84 items", "Uploading 3 of 10 · 30%". */
    fun text(s: Step): String {
        val verb = if (s.phase == Phase.PULL) "Downloading" else "Uploading"
        val pct = percent(s)
        return when {
            s.total > 0 -> "$verb ${s.done} of ${s.total} \u00b7 $pct%"
            s.bytesTotal > 0 -> "$verb \u00b7 ${size(s.bytesDone)} of ${size(s.bytesTotal)} \u00b7 $pct%"
            s.done > 0 -> "$verb \u00b7 ${s.done} item" + (if (s.done == 1) "" else "s")
            else -> "$verb\u2026"
        }
    }

    /** The short line when a shown round ends: "Synced", or the first sentence of the problem. */
    fun endLine(status: String): String {
        if (status.isBlank() || status == "Synced") return "Synced"
        val first = status.substringBefore(". ").trimEnd('.')
        return if (first.length <= 80) first else first.take(79).trimEnd() + "\u2026"
    }

    fun ok(status: String): Boolean = status == "Synced" || status.startsWith("Synced,")

    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
    }
}
