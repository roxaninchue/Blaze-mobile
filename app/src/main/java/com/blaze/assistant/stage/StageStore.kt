package com.blaze.assistant.stage

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.0 the Stage on the phone: the state (saved, so it survives the app closing), the stage_* tools, and the timers.
 * Timers are real: an exact alarm at the phase's end (fires with the app closed), a lock-screen countdown notification
 * (Android's own chronometer, so nothing in Blaze ticks while the screen is off), and "time's up" / "break" alerts.
 */
object StageStore {
    private const val KEY = "stage_state_v1"; private const val TKEY = "stage_timers_v1"
    private val _state = MutableStateFlow(StageState())
    val state: StateFlow<StageState> = _state
    private val _timers = MutableStateFlow<Map<String, TimerState>>(emptyMap())
    val timers: StateFlow<Map<String, TimerState>> = _timers
    @Volatile private var loaded = false
    @Volatile var app: Context? = null

    /** The Stage on/off switch (Settings › Look & feel); off = Blaze never puts cards on the chat. */
    val enabled: Boolean get() = try { Settings.bool("stage_on", true) } catch (_: Throwable) { true }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (loaded) return
        loaded = true
        try {
            _state.value = StageTools.decode(Settings.str(KEY, ""))
            _timers.value = decodeTimers(Settings.str(TKEY, ""))
            // Timers that ended while the app was closed catch up (exact end times, no drift).
            val now = System.currentTimeMillis()
            _timers.value = _timers.value.mapValues { StageTimer.advance(it.value, now).first }
            // Cards whose timer is gone are dropped; every running timer is re-armed (alarms don't survive a reboot).
            _state.value = _state.value.copy(items = _state.value.items.filter { it.kind != StageKind.TIMER || _timers.value.containsKey(it.id) })
            _timers.value.values.filter { it.running }.forEach { arm(ctx, it) }
        } catch (_: Throwable) { }
    }

    private fun save() { try { Settings.setStr(KEY, StageTools.encode(_state.value)); Settings.setStr(TKEY, encodeTimers(_timers.value)) } catch (_: Throwable) { } }

    fun encodeTimers(m: Map<String, TimerState>): String = JSONArray(m.values.map { it.toJson() }).toString()
    fun decodeTimers(s: String?): Map<String, TimerState> = try { JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { TimerState.from(a.getJSONObject(it)) }.associateBy { it.id } } } catch (_: Exception) { emptyMap() }

    /** Apply one action (UI or tool). Timers follow their cards: closing a timer card cancels its alarm. */
    @Synchronized fun dispatch(a: StageAction): StageResult {
        val before = _state.value
        val r = StageReducer.reduce(before, a)
        if (!r.handled) return r
        _state.value = r.state
        val gone = before.items.map { it.id }.toSet() - r.state.items.map { it.id }.toSet()
        if (gone.isNotEmpty()) { gone.forEach { id -> _timers.value[id]?.let { t -> app?.let { cancel(it, t) } } }; _timers.value = _timers.value - gone }
        save()
        return r
    }

    fun back(): Boolean = dispatch(StageAction.Back).handled

    // ---------------- timers ----------------

    fun timer(id: String): TimerState? = _timers.value[id]

    fun timerOp(ctx: Context, id: String, op: String, minutes: Double = 5.0) {
        val t = _timers.value[id] ?: return
        val now = System.currentTimeMillis()
        val n = when (op) {
            "start", "resume" -> StageTimer.start(if (t.done) StageTimer.reset(t) else t, now)
            "pause" -> StageTimer.pause(t, now)
            "reset" -> StageTimer.reset(t)
            "add" -> StageTimer.add(t, (minutes * 60_000).toLong(), now)
            else -> t
        }
        putTimer(ctx, n)
    }

    private fun putTimer(ctx: Context, t: TimerState) {
        _timers.value = _timers.value + (t.id to t); save()
        if (t.running) arm(ctx, t) else { cancelAlarm(ctx, t); if (!t.started || t.done) nm(ctx).cancel(nid(t.id)) else ongoing(ctx, t) }
    }

    /** The alarm fired (or the app opened late): roll phases forward, alert, re-arm the next phase. */
    fun onAlarm(ctx: Context, id: String) {
        init(ctx)
        val t = _timers.value[id] ?: return
        val (n, ended) = StageTimer.advance(t, System.currentTimeMillis())
        if (ended == 0) { if (n.running) arm(ctx, n); return }
        _timers.value = _timers.value + (id to n); save()
        alert(ctx, n)
        if (n.running) arm(ctx, n) else nm(ctx).cancel(nid(id))
    }

    private const val CH = "stage_timer"; private const val CH_ALERT = "stage_timer_done"
    const val ACT_ALARM = "com.blaze.assistant.stage.ALARM"; const val ACT_PAUSE = "com.blaze.assistant.stage.PAUSE"; const val ACT_STOP = "com.blaze.assistant.stage.STOP"
    private fun nid(id: String) = 0x5A00 + (id.hashCode() and 0xFF)
    private fun pi(ctx: Context, act: String, id: String) = PendingIntent.getBroadcast(ctx, (act + id).hashCode(),
        Intent(ctx, StageTimerReceiver::class.java).setAction(act).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun nm(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java).also {
        if (Build.VERSION.SDK_INT >= 26) {
            it.createNotificationChannel(NotificationChannel(CH, "Stage timers", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            it.createNotificationChannel(NotificationChannel(CH_ALERT, "Timer alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun open(ctx: Context): PendingIntent? = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
        PendingIntent.getActivity(ctx, 0x5A, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    private fun arm(ctx: Context, t: TimerState) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, ACT_ALARM, t.id)
        try { if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, p)
              else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, p) } catch (_: Exception) { try { am.set(AlarmManager.RTC_WAKEUP, t.endAt, p) } catch (_: Exception) { } }
        ongoing(ctx, t)
    }

    private fun cancelAlarm(ctx: Context, t: TimerState) { try { ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, ACT_ALARM, t.id)) } catch (_: Exception) { } }
    private fun cancel(ctx: Context, t: TimerState) { cancelAlarm(ctx, t); try { nm(ctx).cancel(nid(t.id)) } catch (_: Throwable) { } }

    /** The lock-screen countdown (the system draws the ticking chronometer; Blaze does nothing per second). */
    @SuppressLint("MissingPermission")
    private fun ongoing(ctx: Context, t: TimerState) {
        try {
            val b = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_blaze).setOngoing(t.running).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setContentTitle(t.label + if (t.phases.size > 1) " \u00b7 " + t.phaseName else "")
                .setContentIntent(open(ctx))
            if (t.running) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.endAt).setShowWhen(true).setContentText("Running")
                .addAction(0, "Pause", pi(ctx, ACT_PAUSE, t.id))
            else b.setContentText("Paused \u00b7 " + StageTimer.clock(t.left) + " left").addAction(0, "Resume", pi(ctx, ACT_PAUSE, t.id))
            b.addAction(0, "Stop", pi(ctx, ACT_STOP, t.id))
            nm(ctx).notify(nid(t.id), b.build())
        } catch (_: Throwable) { }
    }

    @SuppressLint("MissingPermission")
    private fun alert(ctx: Context, t: TimerState) {
        val text = if (t.done) "Time's up." else "${t.phaseName} \u00b7 ${StageTimer.words(t.phases[t.phase])}"
        try {
            nm(ctx).notify(nid(t.id) + 0x100, NotificationCompat.Builder(ctx, CH_ALERT).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle(t.label).setContentText(text).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL).setContentIntent(open(ctx)).build())
        } catch (_: Throwable) { }
    }

    // ---------------- the stage_* tools ----------------

    suspend fun run(ctx: Context, name: String, args: JSONObject): String {
        init(ctx)
        if (!enabled) return "The Stage is off in Settings › Look & feel; answer in chat (or open the view the user asked for)."
        val (action, err) = StageTools.parse(name, args)
        if (action == null) return err
        return withContext(Dispatchers.Main) {
            val r = dispatch(action)
            if (!r.handled) return@withContext r.note
            val id = r.id
            val item = id?.let { r.state.byId(it) }
            var extra = ""
            if (item != null && action is StageAction.Show) extra = onShown(ctx, item, args)
            if (item != null && action is StageAction.Update) {
                val c = args.optString("control").trim().lowercase()
                if (item.kind == StageKind.TIMER && c.isNotEmpty()) { timerOp(ctx, item.id, c, StageTimer.parseMinutes(args.opt("minutes")?.toString()) ?: 5.0); extra = " Timer: $c." }
                else if (item.kind == StageKind.TIMER && args.has("minutes")) { replaceTimer(ctx, item); extra = " Timer set to ${item.props["minutes"]} min." }
                if (item.kind == StageKind.MUSIC && args.optString("query").isNotBlank()) extra = " " + com.blaze.assistant.mediaapps.StageMusic.play(ctx, args.optString("query"))
            }
            (r.note + extra).trim() + ""
        }
    }

    private fun replaceTimer(ctx: Context, item: StageItem) {
        _timers.value[item.id]?.let { cancel(ctx, it) }
        val p = item.props
        val t = StageTimer.create(item.id, p["title"].orEmpty().ifBlank { if (p["pomodoro"] == "true") "Pomodoro" else "Timer" },
            p["minutes"]?.toDoubleOrNull() ?: 25.0, p["pomodoro"] == "true", p["break_minutes"]?.toDoubleOrNull() ?: 5.0, p["cycles"]?.toIntOrNull() ?: 4)
        putTimer(ctx, if (p["autostart"] == "true") StageTimer.start(t, System.currentTimeMillis()) else t)
    }

    /** Side effects of a card arriving: a timer is created (started only on autostart), music starts in place. */
    private suspend fun onShown(ctx: Context, item: StageItem, args: JSONObject): String = when (item.kind) {
        StageKind.TIMER -> {
            if (_timers.value[item.id] == null || args.has("minutes")) replaceTimer(ctx, item)
            val t = _timers.value[item.id]
            if (t?.running == true) " Running: ends in ${StageTimer.words(StageTimer.remaining(t, System.currentTimeMillis()))}."
            else " Ready with a Start button (${StageTimer.words(t?.left ?: 0L)}); it's not running until the user taps Start."
        }
        StageKind.MUSIC -> " " + com.blaze.assistant.mediaapps.StageMusic.play(ctx, args.optString("query"))
        StageKind.VIDEO -> if (item.props["url"].isNullOrBlank()) " No video given: the card offers Blaze Video." else ""
        StageKind.FLASHCARDS -> com.blaze.assistant.agent.Flashcards.queue(item.props["deck"].orEmpty()).size.let { n ->
            if (n == 0) " That deck has nothing due (or doesn't exist): add cards with flashcards action=add." else " $n card${if (n == 1) "" else "s"} to go." }
        else -> ""
    }
}

class StageTimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        StageStore.init(ctx)
        when (intent.action) {
            StageStore.ACT_ALARM -> StageStore.onAlarm(ctx, id)
            StageStore.ACT_PAUSE -> StageStore.timerOp(ctx, id, if (StageStore.timer(id)?.running == true) "pause" else "start")
            StageStore.ACT_STOP -> StageStore.dispatch(StageAction.Close(id))
        }
    }
}
