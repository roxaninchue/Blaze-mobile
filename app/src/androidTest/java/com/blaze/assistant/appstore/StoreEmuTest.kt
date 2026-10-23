package com.blaze.assistant.appstore

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.blaze.assistant.appstore.EmuEvidence.FAIL
import com.blaze.assistant.appstore.EmuEvidence.NOT_TESTED
import com.blaze.assistant.appstore.EmuEvidence.PASS
import com.blaze.assistant.appstore.EmuEvidence.record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Collections
import java.util.regex.Pattern

/**
 * app/emu-store: EMULATOR checks of the Store install path (InstallPipeline + AppStore's real Env + StoreInstaller's
 * PackageInstaller session + Android's own confirm dialog, tapped with UiAutomator).
 *
 * Fixture scenarios replace only resolve() and download() (a local fixture APK from the test APK's assets instead of a
 * GitHub release, so no network flakiness); SHA-256, APK inspection / signer extraction, the installed-signer check, the
 * VirusTotal call, the PackageInstaller session, the receiver and the package manager check are the app's real code.
 * The network scenario runs the app's real Env end to end (GitHub API + HTTPS download) for one pinned catalog app.
 * Anything that can't be driven here is recorded as not-tested, never as pass.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class StoreEmuTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = inst.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(inst)
    private lateinit var fx: JSONObject
    private var host: ActivityScenario<ComponentActivity>? = null

    private val installerPkg = Pattern.compile(".*packageinstaller.*")
    private val installBtn = Pattern.compile("(?i)^\\s*(install|update)\\s*$")
    private val nextBtn = Pattern.compile("(?i)^\\s*next\\s*$")
    private val doneBtn = Pattern.compile("(?i)^\\s*done\\s*$")

    @Before fun setUp() {
        AppStore.init(target)
        fx = JSONObject(inst.context.assets.open("emu/fixtures.json").use { it.readBytes().toString(Charsets.UTF_8) })
        // Blaze in the foreground, as when the user taps Get in the Store (the confirm screen is started from Blaze).
        host = ActivityScenario.launch(ComponentActivity::class.java)
        device.waitForIdle()
    }

    @After fun tearDown() { try { host?.close() } catch (_: Throwable) { } }

    // ------------------------------------------------------------------------------------------------ helpers

    private class Run(val result: PipelineResult, val steps: List<Pair<StoreStep, String>>, val env: Spy,
                      val dialogSeen: Boolean, val dialogClicked: Boolean, val permUi: String, val confirms: List<String>)

    /** Wraps an Env and remembers what the pipeline did with it. */
    private open class Spy(val real: InstallPipeline.Env) : InstallPipeline.Env by real {
        var inspected: ApkInfo? = null; var inspectCalled = false
        var shaSeen: String? = null
        var installCalled = false
        var vt: VtVerdict? = null
        var downloaded: File? = null
        val deleted: MutableList<File> = Collections.synchronizedList(mutableListOf())
        var resolved: ResolvedAsset? = null
        var resolveError: String? = null
        override fun resolve(app: StoreApp): ResolvedAsset = try { real.resolve(app).also { resolved = it } } catch (e: Exception) { resolveError = e.message; throw e }
        override fun download(app: StoreApp, asset: ResolvedAsset, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File =
            real.download(app, asset, progress, cancelled).also { downloaded = it }
        override fun sha256(file: File): String = real.sha256(file).also { shaSeen = it }
        override fun inspect(file: File): ApkInfo? { inspectCalled = true; return real.inspect(file).also { inspected = it } }
        override fun scan(sha256: String): VtVerdict = real.scan(sha256).also { vt = it }
        override suspend fun install(apk: File, info: ApkInfo, onUserAction: () -> Unit): InstallOutcome { installCalled = true; return real.install(apk, info, onUserAction) }
        override fun delete(file: File) { deleted += file; real.delete(file) }
    }

    /** Fixture: resolve() and download() come from the test APK's assets; the rest is the app's real Env. */
    private inner class FixtureEnv(real: InstallPipeline.Env, val asset: String, val tag: String) : Spy(real) {
        override fun resolve(app: StoreApp): ResolvedAsset {
            val size = inst.context.assets.open("emu/$asset").use { i -> var n = 0L; val b = ByteArray(8192); while (true) { val k = i.read(b); if (k < 0) break; n += k }; n }
            return ResolvedAsset(tag, asset, "https://github.com/blaze-emu/fixtures/releases/download/$tag/$asset", size, null).also { resolved = it }
        }
        override fun download(app: StoreApp, asset: ResolvedAsset, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
            val dir = File(target.cacheDir, "store-dl").apply { mkdirs() }
            val out = File(dir, "${app.id}-${System.nanoTime()}.apk")
            inst.context.assets.open("emu/${this.asset}").use { i -> out.outputStream().use { i.copyTo(it) } }
            progress(out.length(), out.length())
            return out.also { downloaded = it }
        }
    }

    private fun fixtureApp(id: String, pkg: String, pins: Set<String>, releases: Map<String, KnownRelease>) = StoreApp(
        id, "EMU fixture $id", pkg, "Local test APK for the emulator Store checks", "", "", "tools", "", "MIT", emptyList(),
        StoreSourceRef("github", "blaze-emu/fixtures"), AssetRule(Regex(".*")), pins, releases, 0)

    private fun sha(name: String) = fx.getJSONObject("sha").getString(name)

    private fun find(sel: androidx.test.uiautomator.BySelector): UiObject2? = try { device.findObject(sel) } catch (_: Throwable) { null }

    /** Settings › Install unknown apps › Blaze: tap "Allow from this source", then come back. */
    private fun grantPermissionUi(scenario: String): String {
        val allow = Pattern.compile("(?i).*allow from this source.*|.*allow app installs.*")
        val row = try { device.wait(Until.findObject(By.text(allow)), 20_000) } catch (_: Throwable) { null }
        EmuEvidence.shot("$scenario-permission-settings")
        if (row != null) {
            try { row.click() } catch (_: Throwable) { }
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 8_000 && !StoreInstaller.canInstall(target)) Thread.sleep(250)
        }
        val viaUi = StoreInstaller.canInstall(target)
        if (!viaUi) EmuEvidence.sh("appops set ${target.packageName} REQUEST_INSTALL_PACKAGES allow")
        device.pressBack()
        return if (viaUi) "ui" else if (StoreInstaller.canInstall(target)) "appops-fallback" else "failed"
    }

    private fun drive(app: StoreApp, env: Spy, update: Boolean, scenario: String, timeoutMs: Long = 240_000): Run {
        val steps = Collections.synchronizedList(mutableListOf<Pair<StoreStep, String>>())
        val confirms = Collections.synchronizedList(mutableListOf<String>())
        val job = CoroutineScope(Dispatchers.IO).async {
            InstallPipeline(env).run(app, update, report = { s, m, _, _ -> synchronized(steps) { if (steps.lastOrNull()?.first != s) steps += s to m } })
        }
        var dialogSeen = false; var dialogClicked = false; var permUi = "not-asked"; var shotDialog = false
        val t0 = System.currentTimeMillis()
        while (!job.isCompleted && System.currentTimeMillis() - t0 < timeoutMs) {
            val seen = synchronized(steps) { steps.map { it.first } }
            if (StoreStep.PERMISSION in seen && permUi == "not-asked") permUi = grantPermissionUi(scenario)
            val next = find(By.pkg(installerPkg).text(nextBtn))
            if (next != null) { dialogSeen = true; if (!shotDialog) { EmuEvidence.shot("$scenario-installer-dialog"); shotDialog = true }; try { next.click() } catch (_: Throwable) { }; Thread.sleep(600); continue }
            val btn = find(By.pkg(installerPkg).text(installBtn))
            if (btn != null && !dialogClicked) {
                dialogSeen = true
                if (!shotDialog) { EmuEvidence.shot("$scenario-installer-dialog"); shotDialog = true }
                try { btn.click(); dialogClicked = true } catch (_: Throwable) { }
            }
            find(By.pkg(installerPkg).text(doneBtn))?.let { try { it.click() } catch (_: Throwable) { } }
            Thread.sleep(500)
        }
        val result = if (job.isCompleted) runBlocking { job.await() } else {
            job.cancel(); PipelineResult(StoreStep.FAILED, "EMU harness timeout after ${timeoutMs / 1000}s; last step ${steps.lastOrNull()?.first}")
        }
        EmuEvidence.shot("$scenario-end")
        return Run(result, steps.toList(), env, dialogSeen, dialogClicked, permUi, confirms)
    }

    private val yes: suspend (String, String) -> Boolean = { _, _ -> true }

    private fun Run.saw(s: StoreStep) = steps.any { it.first == s }
    private fun Run.trace() = steps.joinToString(" > ") { it.first.name } + " | final=${result.step} ${result.message.take(200)}"

    private fun recordCommon(sc: String, r: Run) {
        record(sc, "trace", if (r.result.step == StoreStep.FAILED && r.result.message.startsWith("EMU harness")) FAIL else PASS, r.trace())
        val ins = r.env.inspected
        record(sc, "apk_inspect_signers", if (ins != null && ins.signers.isNotEmpty()) PASS else if (r.env.inspectCalled) FAIL else NOT_TESTED,
            if (ins == null) "inspect=${if (r.env.inspectCalled) "null" else "not called"}" else "pkg=${ins.pkg} vc=${ins.versionCode} signers=${ins.signers}")
    }

    private fun blockedCheck(sc: String, r: Run, expectWord: Regex, pkg: String, stepName: String) {
        recordCommon(sc, r)
        val leftover = r.env.downloaded?.exists() == true
        val ok = r.result.step == StoreStep.BLOCKED && expectWord.containsMatchIn(r.result.message) && !r.env.installCalled && !leftover
        record(sc, stepName, if (ok) PASS else FAIL,
            "final=${r.result.step} installCalled=${r.env.installCalled} fileLeft=$leftover msg=${r.result.message.take(220)}")
        val listed = EmuEvidence.pmListed(pkg)
        record(sc, "pm_list_absent", if (!listed) PASS else FAIL, "pm list packages $pkg -> ${if (listed) "present" else "absent"}")
        record(sc, "install_dialog", if (!r.dialogSeen) PASS else FAIL, if (r.dialogSeen) "Android's installer was opened for a refused file" else "Android's installer was never opened (correct)")
        assertTrue("$sc: ${r.trace()}", ok && !listed && !r.dialogSeen)
    }

    // ------------------------------------------------------------------------------------------------ scenarios

    /** Good fixture: confirm → permission (Settings UI) → checksum → key A pin → VT → Android dialog → pm. */
    @Test fun t1_fixtureGoodInstall() {
        val sc = "fixture-good"
        val pkg = "com.blaze.emufixture.one"
        val before = StoreInstaller.canInstall(target)
        record(sc, "precondition_install_permission_off", if (!before) PASS else NOT_TESTED,
            "canRequestPackageInstalls() at start = $before (workflow sets appops deny before the run)")
        val app = fixtureApp("emu-one", pkg, setOf(fx.getString("certA")), mapOf("v1" to KnownRelease(sha256 = sha("one_v1_A.apk"))))
        val env = FixtureEnv(AppStore.env(yes), "one_v1_A.apk", "v1")
        val r = drive(app, env, update = false, scenario = sc)
        recordCommon(sc, r)
        record(sc, "confirm_card", if (r.saw(StoreStep.CONFIRM)) PASS else FAIL,
            "pipeline asked Env.confirmInstall before download (answered yes by the harness; the chat ConfirmGate card itself is not rendered here)")
        record(sc, "permission", when {
            before -> NOT_TESTED
            r.permUi == "ui" -> PASS
            r.permUi == "appops-fallback" -> NOT_TESTED
            else -> FAIL
        }, "PERMISSION step seen=${r.saw(StoreStep.PERMISSION)}; grant path=${r.permUi} (ui = toggled 'Allow from this source' in Settings with UiAutomator)")
        record(sc, "resolve", NOT_TESTED, "fixture: resolve() stubbed to a local asset (real GitHub resolve is in the network scenario)")
        record(sc, "download", NOT_TESTED, "fixture: local copy from test assets (real HTTPS download is in the network scenario)")
        val pastVerify = r.saw(StoreStep.SCANNING) || r.saw(StoreStep.INSTALLING)
        record(sc, "checksum", if (pastVerify && r.env.shaSeen == sha("one_v1_A.apk")) PASS else FAIL, "sha256=${r.env.shaSeen} expected=${sha("one_v1_A.apk")}")
        record(sc, "signer_pin", if (pastVerify && r.env.inspected?.signers?.contains(fx.getString("certA")) == true) PASS else FAIL,
            "pinned certA=${fx.getString("certA")} apk=${r.env.inspected?.signers}")
        record(sc, "virustotal", NOT_TESTED, "no VirusTotal key on the emulator (verdict=${r.env.vt?.line()}); risk prompt answered yes by the harness")
        record(sc, "install_dialog", if (r.dialogSeen && r.dialogClicked) PASS else FAIL, "Android confirm dialog seen=${r.dialogSeen} tapped=${r.dialogClicked}")
        record(sc, "session_status_and_pm_verified", if (r.result.ok) PASS else FAIL, "PipelineResult ok=${r.result.ok} step=${r.result.step}")
        val listed = EmuEvidence.pmListed(pkg)
        record(sc, "pm_list_installed", if (listed) PASS else FAIL, "pm list packages $pkg -> ${if (listed) "present" else "absent"}")
        record(sc, "temp_file_deleted", if (r.env.downloaded?.exists() == false) PASS else FAIL, "download file left after install = ${r.env.downloaded?.exists()}")
        assertTrue(r.trace(), r.result.ok && listed)
    }

    /** Bad checksum: the catalog's SHA-256 is for the clean file; the download is a tampered copy. */
    @Test fun t2_tamperedChecksumRefused() {
        val sc = "fixture-bad-checksum"
        if (!StoreInstaller.canInstall(target)) EmuEvidence.sh("appops set ${target.packageName} REQUEST_INSTALL_PACKAGES allow")
        val app = fixtureApp("emu-two", "com.blaze.emufixture.two", setOf(fx.getString("certA")), mapOf("v1" to KnownRelease(sha256 = sha("two_v1_A.apk"))))
        val r = drive(app, FixtureEnv(AppStore.env(yes), "two_v1_A_tampered.apk", "v1"), false, sc, 90_000)
        blockedCheck(sc, r, Regex("SHA-256 doesn't match"), "com.blaze.emufixture.two", "checksum_refused")
    }

    /** Wrong signer: catalog pins key A; the APK is signed with key B (no checksum published, so the key is what stops it). */
    @Test fun t3_wrongSignerRefused() {
        val sc = "fixture-wrong-signer"
        if (!StoreInstaller.canInstall(target)) EmuEvidence.sh("appops set ${target.packageName} REQUEST_INSTALL_PACKAGES allow")
        val app = fixtureApp("emu-two", "com.blaze.emufixture.two", setOf(fx.getString("certA")), emptyMap())
        val r = drive(app, FixtureEnv(AppStore.env(yes), "two_v1_B.apk", "v1"), false, sc, 90_000)
        blockedCheck(sc, r, Regex("not signed with the developer's known key"), "com.blaze.emufixture.two", "signer_refused")
    }

    /** Tampered file and no published checksum: the APK parser / signer check must still stop it before Android's installer. */
    @Test fun t4_tamperedNoChecksumRefused() {
        val sc = "fixture-tampered-no-checksum"
        if (!StoreInstaller.canInstall(target)) EmuEvidence.sh("appops set ${target.packageName} REQUEST_INSTALL_PACKAGES allow")
        val app = fixtureApp("emu-two", "com.blaze.emufixture.two", setOf(fx.getString("certA")), emptyMap())
        val r = drive(app, FixtureEnv(AppStore.env(yes), "two_v1_A_tampered.apk", "v1"), false, sc, 90_000)
        blockedCheck(sc, r, Regex("Blocked"), "com.blaze.emufixture.two", "tampered_refused")
    }

    /** Update signed by another key than the installed app (no catalog pin): the installed-signer check must refuse it. */
    @Test fun t5_updateDifferentSignerRefused() {
        val sc = "fixture-update-other-key"
        val pkg = "com.blaze.emufixture.one"
        if (!EmuEvidence.pmListed(pkg)) { record(sc, "installed_signer_refused", NOT_TESTED, "precondition: $pkg not installed (t1 did not install it)"); return }
        if (!StoreInstaller.canInstall(target)) EmuEvidence.sh("appops set ${target.packageName} REQUEST_INSTALL_PACKAGES allow")
        val app = fixtureApp("emu-one", pkg, emptySet(), emptyMap())
        val r = drive(app, FixtureEnv(AppStore.env(yes), "one_v2_B.apk", "v2"), true, sc, 90_000)
        recordCommon(sc, r)
        val ok = r.result.step == StoreStep.BLOCKED && r.result.message.contains("different key") && !r.env.installCalled
        record(sc, "installed_signer_refused", if (ok) PASS else FAIL, "final=${r.result.step} installCalled=${r.env.installCalled} msg=${r.result.message.take(220)}")
        val vc = EmuEvidence.sh("dumpsys package $pkg").lines().firstOrNull { it.trim().startsWith("versionCode=") }?.trim().orEmpty()
        record(sc, "installed_version_unchanged", if (vc.startsWith("versionCode=1 ")) PASS else FAIL, vc)
        assertTrue(r.trace(), ok)
    }

    /** One real network install with the app's own Env: GitHub API resolve → HTTPS download → checksum → pinned key → dialog. */
    @Test fun t6_networkInstall() {
        val sc = "network-aegis"
        if (!StoreInstaller.canInstall(target)) EmuEvidence.sh("appops set ${target.packageName} REQUEST_INSTALL_PACKAGES allow")
        val app = AppStore.catalog.value.byId("aegis-authenticator")
        if (app == null) { record(sc, "catalog_entry", FAIL, "aegis-authenticator not in the loaded catalog (${AppStore.catalog.value.apps.size} apps)"); assertTrue(false); return }
        record(sc, "catalog_entry", PASS, "pkg=${app.pkg} pins=${app.signers} knownReleases=${app.releases.keys}")
        val env = Spy(AppStore.env(yes))
        val r = drive(app, env, update = false, scenario = sc, timeoutMs = 300_000)
        recordCommon(sc, r)
        val rateLimited = env.resolveError?.contains("403") == true || env.resolveError?.contains("429") == true
        record(sc, "resolve", when { env.resolved != null -> PASS; rateLimited -> NOT_TESTED; else -> FAIL },
            env.resolved?.let { "tag=${it.tag} asset=${it.name} hostSha256=${it.hostSha256 ?: "none"} url=${it.url}" } ?: "error=${env.resolveError}${if (rateLimited) " (GitHub API rate limit from the runner's IP: environment, not the app)" else ""}")
        if (env.resolved == null) { assertTrue(r.trace(), rateLimited); return }
        record(sc, "download", if (env.downloaded != null) PASS else FAIL, "https download ${if (env.downloaded != null) "completed" else "failed"}; ${r.result.message.take(160)}")
        val expected = app.knownSha(env.resolved!!.tag, env.resolved!!.name) ?: env.resolved!!.hostSha256
        val pastVerify = r.saw(StoreStep.SCANNING) || r.saw(StoreStep.INSTALLING)
        record(sc, "checksum", when { expected == null -> NOT_TESTED; pastVerify && env.shaSeen == expected -> PASS; else -> FAIL },
            "expected=${expected ?: "none (no catalog hash for this tag and no GitHub digest)"} actual=${env.shaSeen} source=${if (app.knownSha(env.resolved!!.tag, env.resolved!!.name) != null) "catalog" else if (env.resolved!!.hostSha256 != null) "github-digest" else "none"}")
        record(sc, "signer_pin", if (pastVerify && env.inspected?.signers?.any { it in app.signers } == true) PASS else FAIL, "pins=${app.signers} apk=${env.inspected?.signers}")
        record(sc, "virustotal", NOT_TESTED, "no VirusTotal key (verdict=${env.vt?.line()})")
        record(sc, "install_dialog", if (r.dialogSeen && r.dialogClicked) PASS else FAIL, "seen=${r.dialogSeen} tapped=${r.dialogClicked}")
        record(sc, "session_status_and_pm_verified", if (r.result.ok) PASS else FAIL, "ok=${r.result.ok} step=${r.result.step} msg=${r.result.message.take(200)}")
        val listed = EmuEvidence.pmListed(app.pkg)
        record(sc, "pm_list_installed", if (listed) PASS else FAIL, "pm list packages ${app.pkg} -> ${if (listed) "present" else "absent"}")
        assertTrue(r.trace(), r.result.ok && listed)
    }
}
