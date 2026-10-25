package com.blaze.assistant.variant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.kiln.KilnPreviewActivity
import com.blaze.assistant.ui.Label
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type

/**
 * kilnPreview build only (private "Kiln preview" test build, never a release). Tapping the version in Settings > About
 * 7 times reveals the "Kiln preview" switch; it is off by default. Turning it on opens the Kiln screen
 * (KilnPreviewActivity). Release, debug and kilnTest use the no-op copy in src/noPreview.
 */
object VariantHooks {
    const val VERSION_TAPS = true

    private const val PREFS = "kiln_preview"
    private val counter = TapCounter(needed = 7)
    private val revealed = mutableStateOf<Boolean?>(null)
    private val on = mutableStateOf<Boolean?>(null)
    private var toast: Toast? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isRevealed(ctx: Context): Boolean = revealed.value ?: prefs(ctx).getBoolean("revealed", false).also { revealed.value = it }
    fun isOn(ctx: Context): Boolean = on.value ?: prefs(ctx).getBoolean("on", false).also { on.value = it }
    fun setOn(ctx: Context, value: Boolean) { prefs(ctx).edit().putBoolean("on", value).apply(); on.value = value }

    fun versionTapped(context: Context) {
        val left = counter.tap(android.os.SystemClock.elapsedRealtime())
        val msg = when {
            isRevealed(context) && left == 0 -> "Kiln preview is already shown below."
            left == 0 -> {
                prefs(context).edit().putBoolean("revealed", true).apply(); revealed.value = true
                "Kiln preview revealed."
            }
            left <= 4 -> "$left more tap" + (if (left == 1) "" else "s") + " to show Kiln preview."
            else -> null
        } ?: return
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    fun openKiln(ctx: Context) {
        val i = Intent(ctx, KilnPreviewActivity::class.java)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    @Composable
    fun AboutExtras() {
        val ctx = LocalContext.current
        // Read through the state so the row appears the moment the 7th tap lands.
        if (!(revealed.value ?: isRevealed(ctx))) return
        val isOnNow = on.value ?: isOn(ctx)
        val c = LocalBlaze.current.mono
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kiln preview", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                Label("Private test build. Runs one pinned F-Droid app inside Blaze.", color = c.weak, size = 10.sp)
            }
            Switch(checked = isOnNow, onCheckedChange = { v -> setOn(ctx, v); if (v) openKiln(ctx) })
        }
        if (isOnNow) {
            Text("Open Kiln \u2192", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                modifier = Modifier.padding(top = 10.dp).clickable { openKiln(ctx) })
        }
    }

    /** Kiln guest processes (":g0") run the guest app, not Blaze: skip Blaze's own start-up there. */
    fun skipAppInit(app: Application): Boolean = processName(app)?.substringAfter(':', "")?.startsWith("g") == true

    private fun processName(app: Application): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else java.io.File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    } catch (t: Throwable) {
        android.util.Log.w("BlazeVariant", "process name: ${t.javaClass.simpleName}"); null
    }
}
