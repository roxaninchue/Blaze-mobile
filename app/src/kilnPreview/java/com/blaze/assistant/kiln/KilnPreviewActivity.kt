package com.blaze.assistant.kiln

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.blaze.assistant.BuildConfig
import com.blaze.assistant.variant.VariantHooks
import com.blaze.kiln.diag.HarnessReport
import com.blaze.kiln.diag.HarnessStep
import com.blaze.kiln.diag.StepResult
import com.blaze.kiln.runtime.BlazeKiln
import com.blaze.kiln.runtime.KilnDiag
import java.io.File

/**
 * Kiln preview screen (kilnPreview build only; private test build, never a release). Opened from the hidden
 * "Kiln preview" switch in Settings > About. One button runs verify -> install -> launch of the pinned F-Droid guest
 * (Shopping List Calculator 2.3, GUEST_APPS.md) inside Kiln and shows every KilnDiag step on screen, with Copy log.
 *
 * The activity is exported only behind android.permission.DUMP, so apps cannot start it; adb (shell) can, for the
 * emulator CI check (tools/kiln/emulator-check.sh):
 *   am start -n com.blaze.assistant/com.blaze.assistant.kiln.KilnPreviewActivity --ez kiln.enable true --ez kiln.autorun true
 */
class KilnPreviewActivity : Activity() {
    private lateinit var out: TextView
    private val kiln by lazy { BlazeKiln.get(this) }
    private var sessionStart = 0L
    private var local = ArrayList<StepResult>()
    private var pkg: String? = null
    private var autorunDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_ENABLE, false) == true) VariantHooks.setOn(this, true)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 72, 40, 40) }
        col.addView(TextView(this).apply { text = "Kiln preview"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        if (!VariantHooks.isOn(this)) {
            col.addView(TextView(this).apply { text = "Kiln preview is off. Turn it on in Settings > About."; textSize = 15f })
            setContentView(col); return
        }
        KilnDiag.init(this)
        sessionStart = savedInstanceState?.getLong("session") ?: 0L
        pkg = savedInstanceState?.getString("pkg")
        col.addView(TextView(this).apply {
            text = "Private test build ${BuildConfig.VERSION_NAME}. Kiln runs the app below inside Blaze: it is not installed on " +
                "the phone. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "${Build.SUPPORTED_ABIS.firstOrNull()}"
            textSize = 12f
        })
        fun button(label: String, onClick: () -> Unit) = col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } })
        button("Install & open Shopping List Calculator") { installAndLaunch(bundledGuest()) }
        button("Open it again (fresh start)") { launchAgain() }
        button("Copy log") { copy() }
        button("Refresh steps") { render() }
        button("Stop the app") { val n = kiln.stopGuestProcesses(); note("Stopped $n guest process(es).") }
        button("Remove it from Kiln") { clearAll() }
        out = TextView(this).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, 24, 0, 0) }
        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
        render()
        // Autorun once per launch intent. A configuration change can recreate the activity before the posted run starts
        // (seen on the API 34 emulator: "relaunch" right after onCreate), so "done" is saved only once it really ran.
        autorunDone = savedInstanceState?.getBoolean("autorunDone") ?: false
        if (!autorunDone && intent?.getBooleanExtra(EXTRA_AUTORUN, false) == true) {
            KilnDiag.info("PREVIEW", "autorun: clear Kiln, install + launch $BUNDLED_GUEST")
            out.post {
                if (isFinishing || isDestroyed || autorunDone) return@post
                autorunDone = true
                kiln.stopGuestProcesses()
                kiln.installed().forEach { kiln.uninstall(it.packageName) }
                installAndLaunch(bundledGuest())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::out.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("session", sessionStart)
        outState.putBoolean("autorunDone", autorunDone)
        pkg?.let { outState.putString("pkg", it) }
    }

    private fun bundledGuest(): File {
        val f = File(cacheDir, BUNDLED_GUEST)
        assets.open("kiln/guests/$BUNDLED_GUEST").use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun installAndLaunch(apk: File) {
        sessionStart = System.currentTimeMillis(); local = ArrayList()
        kiln.stopGuestProcesses()
        // installWithReport runs READ_APK -> VERIFY (pinned signer + exact APK hash + v2/v3 integrity) -> INSTALL.
        val outcome = try { kiln.installWithReport(apk) } catch (t: Throwable) {
            local += StepResult(HarnessStep.READ_APK, false, "${t.javaClass.simpleName}: ${t.message}"); render(); return
        }
        local.addAll(outcome.steps)
        val rec = outcome.record
        if (rec == null || outcome.failedStep != null) { render(); return }
        pkg = rec.packageName
        launch(rec.packageName, fresh = false)
    }

    private fun launchAgain() {
        val p = pkg ?: kiln.installed().firstOrNull()?.packageName
        if (p == null) { note("Nothing in Kiln yet: tap Install & open first."); return }
        pkg = p
        sessionStart = System.currentTimeMillis(); local = ArrayList()
        kiln.stopGuestProcesses()
        local += StepResult(HarnessStep.INSTALL, true, "already in Kiln: $p")
        launch(p, fresh = true)
    }

    private fun launch(p: String, fresh: Boolean) {
        val intent = try { if (fresh) kiln.relaunchIntent(p) else kiln.launchIntent(p) } catch (t: Throwable) {
            record(HarnessStep.PREPARE_LAUNCH, false, "${t.javaClass.simpleName}: ${t.message}"); render(); return
        }
        if (intent == null) { record(HarnessStep.PREPARE_LAUNCH, false, "no launcher activity or no free guest process"); render(); return }
        record(HarnessStep.PREPARE_LAUNCH, true, intent.component?.className?.substringAfterLast('.') ?: "")
        try {
            startActivity(intent)
            record(HarnessStep.START_SLOT, true, "startActivity accepted")
        } catch (t: Throwable) {
            record(HarnessStep.START_SLOT, false, "${t.javaClass.simpleName}: ${t.message}")
        }
        render()
    }

    private fun record(step: HarnessStep, ok: Boolean, detail: String) {
        local += StepResult(step, ok, detail)
        KilnDiag.step(step, ok, detail)
    }

    private fun render() {
        if (sessionStart == 0L) {
            out.text = "Tap \"Install & open Shopping List Calculator\". Kiln checks the app's pinned signer and file hash, " +
                "copies it into Blaze, and opens it inside Blaze. Every step shows here; come back to this screen to see the " +
                "steps the app reported while it ran.\nIn Kiln now: " + kiln.installed().joinToString { it.packageName }.ifEmpty { "nothing" }
            return
        }
        val report = HarnessReport.build(sessionStart, ArrayList(local), KilnDiag.read(this))
        out.text = report.lines().joinToString("\n")
    }

    private fun copy() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = "Blaze Kiln preview ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} " +
            "API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" + out.text
        cm?.setPrimaryClip(ClipData.newPlainText("Kiln preview log", text))
        note("Log copied.")
    }

    private fun clearAll() {
        kiln.stopGuestProcesses()
        kiln.installed().forEach { kiln.uninstall(it.packageName) }
        KilnDiag.clear(this)
        pkg = null; sessionStart = 0L; local = ArrayList()
        render()
    }

    private fun note(s: String) { out.text = s + "\n\n" + out.text }

    companion object {
        const val EXTRA_AUTORUN = "kiln.autorun"
        const val EXTRA_ENABLE = "kiln.enable"
        /** Pinned F-Droid guest bundled by app/build.gradle.kts (copyKilnGuest); pinned in TrustedGuests. */
        const val BUNDLED_GUEST = "io.github.buildsbyben.shoppinglistcalc_21.apk"
    }
}
