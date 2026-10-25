package com.blaze.assistant.connect

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R

/**
 * 0.45.24: one ongoing "Syncing with Blaze Cloud" notification for a sync round worth showing ([SyncProgress.shouldShow]):
 * a determinate bar when the total is known, indeterminate otherwise, then "Synced" for a few seconds, or a short
 * error. Quiet channel, no sound. Without the notification permission it does nothing (never throws).
 */
class SyncNote(private val ctx: Context?, private val manual: Boolean) {
    companion object {
        const val CHANNEL = "blaze_sync"
        private const val ID = 4610
        private const val DONE_MS = 4_000L
        private const val ERROR_MS = 10_000L
        private const val EVERY_MS = 500L
    }

    @Volatile private var shown = false
    @Volatile private var last = 0L

    private fun allowed(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nm(c: Context): NotificationManager? = try {
        c.getSystemService(NotificationManager::class.java)?.also { m ->
            if (Build.VERSION.SDK_INT >= 26 && m.getNotificationChannel(CHANNEL) == null)
                m.createNotificationChannel(NotificationChannel(CHANNEL, "Cloud sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "How far a sync with Blaze Cloud is"
                    setShowBadge(false)
                })
        }
    } catch (_: Throwable) { null }

    private fun builder(c: Context) = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
        .setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(PendingIntent.getActivity(c, ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    /** Sync now: the bar shows straight away. */
    fun start() { if (manual) post(null, "Starting\u2026", force = true) }

    /** From [SyncEngine] after each page / batch. */
    fun step(s: SyncProgress.Step) {
        if (!shown && !SyncProgress.shouldShow(s, manual)) return
        post(SyncProgress.percent(s), SyncProgress.text(s), force = !shown)
    }

    private fun post(pct: Int?, text: String, force: Boolean) {
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        if (!force && now - last < EVERY_MS) return
        last = now
        try {
            if (!allowed(c)) return
            val n = builder(c).setContentTitle("Syncing with Blaze Cloud").setContentText(text).setOngoing(true)
                .setProgress(100, pct ?: 0, pct == null).build()
            nm(c)?.notify(ID, n)
            shown = true
        } catch (_: Throwable) { }
    }

    /** The round ended with [status] (CloudSync's line). Only a round that was shown says so. */
    fun end(status: String) {
        val c = ctx ?: return
        if (!shown) return
        try {
            val m = nm(c) ?: return
            if (!allowed(c)) { m.cancel(ID); return }
            val ok = SyncProgress.ok(status)
            val wait = if (ok) DONE_MS else ERROR_MS
            val b = builder(c).setContentTitle(if (ok) "Synced" else "Sync stopped").setOngoing(false).setAutoCancel(true)
                .setProgress(0, 0, false)
            if (!ok || status != "Synced") b.setContentText(SyncProgress.endLine(status))
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(wait)
            m.notify(ID, b.build())
            if (Build.VERSION.SDK_INT < 26) Handler(Looper.getMainLooper()).postDelayed({ try { m.cancel(ID) } catch (_: Throwable) { } }, wait)
        } catch (_: Throwable) { }
    }
}
