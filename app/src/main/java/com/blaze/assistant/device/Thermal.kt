package com.blaze.assistant.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.46.4 (Rocky 19:37: "the phone runs really hot"). Thermal awareness: when Android says the phone is warm
 * (PowerManager thermal status MODERATE or worse, API 29+) or Battery Saver is on, Blaze does less:
 *  - no hedged second model request (one brain at a time, deadlines still apply),
 *  - animations (particles, edge dots, orb wobble, Halo glow) freeze to a still frame and glass blur is skipped,
 *  - the on-phone brain is not warmed up (a running one may still answer; nothing new is started).
 * [policy] is pure (ThermalTest); [hot] is the app-wide switch the UI and the brains read.
 */
object Thermal {
    /** PowerManager.THERMAL_STATUS_MODERATE (2). */
    const val MODERATE = 2

    data class Policy(val hedge: Boolean, val animate: Boolean, val blur: Boolean, val warmPhoneBrain: Boolean)

    val COOL = Policy(hedge = true, animate = true, blur = true, warmPhoneBrain = true)
    val HOT = Policy(hedge = false, animate = false, blur = false, warmPhoneBrain = false)

    fun policy(thermalStatus: Int, batterySaver: Boolean): Policy = if (thermalStatus >= MODERATE || batterySaver) HOT else COOL

    @Volatile var status = 0; private set
    @Volatile var saver = false; private set
    private val _hot = MutableStateFlow(false)
    /** True while the phone is warm or saving battery (UI: freeze animations, skip blur). */
    val hot: StateFlow<Boolean> = _hot
    fun now(): Policy = policy(status, saver)

    /** Tests and the listeners below. */
    fun set(thermalStatus: Int, batterySaver: Boolean) {
        status = thermalStatus; saver = batterySaver
        _hot.value = now() == HOT
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        fun refresh() = set(if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0, pm.isPowerSaveMode)
        try { refresh() } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= 29) try {
            pm.addThermalStatusListener(app.mainExecutor) { s -> set(s, pm.isPowerSaveMode) }
        } catch (e: Throwable) { CrashLog.quiet("thermal listener", e) }
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { try { refresh() } catch (_: Throwable) { } }
            }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        } catch (e: Throwable) { CrashLog.quiet("power save receiver", e) }
    }
}
