package com.blaze.assistant.terminal

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * 0.48.0 RC4: the `blaze` CLI inside the phone's Linux is signed in to the same Blaze account as the app, so the
 * terminal never shows "Sign in to Blaze to start" while the app is signed in.
 *
 * How (no token of the app is ever copied):
 *  - The app asks blaze-auth for a device code ([com.blaze.assistant.connect.BlazeAccount.mintCliSession]), approves it
 *    itself with its own session, and redeems it: a NEW session named "Blaze CLI (this phone)". Signing out of the CLI
 *    (`blaze logout`) therefore never signs the app out, and the app can end the CLI's session on its own.
 *  - That session goes where the CLI keeps its sign-in when there is no OS keychain (headless Linux, as in PRoot):
 *    `~/.blaze/secrets.json` ({"blaze-account:session": token}, 0600, other keys kept) plus the cached user in
 *    `~/.blaze/desktop/account.json` (0600), in /root of every installed distro. Folders are 0700.
 *  - Refreshed when the app's account changes (another user), written when Linux / a distro is installed or the
 *    Terminal opens, removed (and the CLI session ended) when the app signs out.
 * Tokens are never logged; errors are logged without their message.
 *
 * Pure part: [CliAuthLogic] (CliAuthLogicTest).
 */
object CliAuthLogic {
    /** The CLI's session key in its private-file keychain fallback (blaze-cli account.go `sessionKey`). */
    const val SESSION_KEY = "blaze-account:session"
    const val CLIENT = "Blaze CLI (this phone)"

    fun secretsFile(rootfs: File) = File(rootfs, "root/.blaze/secrets.json")
    fun accountFile(rootfs: File) = File(rootfs, "root/.blaze/desktop/account.json")

    /** The CLI's session in a secrets.json text ("" when none / unreadable). */
    fun sessionIn(secrets: String?): String = try {
        if (secrets.isNullOrBlank()) "" else JSONObject(secrets).optString(SESSION_KEY).trim()
    } catch (_: Exception) { "" }

    /** The user id in an account.json text ("" when none). */
    fun userIn(account: String?): String = try {
        if (account.isNullOrBlank()) "" else JSONObject(account).optString("id").trim()
    } catch (_: Exception) { "" }

    /** secrets.json with the CLI session set to [token] (or removed when null), every other key kept. */
    fun withSession(secrets: String?, token: String?): String {
        val o = try { if (secrets.isNullOrBlank()) JSONObject() else JSONObject(secrets) } catch (_: Exception) { JSONObject() }
        if (token == null) o.remove(SESSION_KEY) else o.put(SESSION_KEY, token)
        return o.toString()
    }

    enum class Need { NOTHING, WRITE, CLEAR }

    /**
     * What a distro's CLI needs: the app signed in as [appUser] (null = signed out); the CLI holds [cliSession] for
     * [cliUser]. A CLI signed in to the same account (by us or by the user) is left alone.
     */
    fun need(appUser: String?, cliSession: String, cliUser: String): Need = when {
        appUser.isNullOrBlank() -> if (cliSession.isNotEmpty() && cliUser.isNotEmpty()) Need.CLEAR else Need.NOTHING
        cliSession.isNotEmpty() && cliUser == appUser -> Need.NOTHING
        else -> Need.WRITE
    }
}

object CliAuth {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val lock = kotlinx.coroutines.sync.Mutex()
    /** The CLI user ids whose session this app wrote (a session the user signed in to themselves is never ended by us). */
    private const val MINE = "cli_auth_written"

    /** Fire-and-forget: brings every installed distro's CLI in line with the app's sign-in. Never blocks the caller. */
    fun sync() { scope.launch { runCatching { syncNow() }.onFailure { com.blaze.assistant.device.CrashLog.quiet("CliAuth: sync", null) } } }

    private fun rootfses(): List<File> = runCatching {
        Distros.installedIds().map { if (it == "alpine") Linux.rootfs() else Linux.rootfs(it) }
    }.getOrDefault(emptyList())

    suspend fun syncNow(): Unit = lock.withLock {
        val acct = com.blaze.assistant.connect.BlazeAccount
        val user = acct.user()?.takeIf { acct.sessionToken().isNotBlank() }
        var minted: Pair<String, com.blaze.assistant.connect.BlazeAccount.User>? = null
        for (rf in rootfses()) {
            val sf = CliAuthLogic.secretsFile(rf); val af = CliAuthLogic.accountFile(rf)
            val secrets = runCatching { sf.takeIf { it.isFile }?.readText() }.getOrNull()
            val account = runCatching { af.takeIf { it.isFile }?.readText() }.getOrNull()
            val cliSession = CliAuthLogic.sessionIn(secrets); val cliUser = CliAuthLogic.userIn(account)
            when (CliAuthLogic.need(user?.id, cliSession, cliUser)) {
                CliAuthLogic.Need.NOTHING -> {}
                CliAuthLogic.Need.CLEAR -> {
                    // Only a session we wrote: the user's own `blaze login` (another account, no app) stays theirs.
                    if (cliUser in mine()) { runCatching { acct.endSession(cliSession) }; write(sf, CliAuthLogic.withSession(secrets, null)); af.delete() }
                }
                CliAuthLogic.Need.WRITE -> {
                    if (cliSession.isNotEmpty() && cliUser in mine()) runCatching { acct.endSession(cliSession) }
                    val m = minted ?: acct.mintCliSession(CliAuthLogic.CLIENT)?.also { minted = it } ?: return@withLock
                    write(sf, CliAuthLogic.withSession(secrets, m.first))
                    write(af, m.second.json().toString(2))
                    remember(m.second.id)
                }
            }
        }
    }

    /** Signing out of the app: the CLI sessions we wrote end too (call before the app's own session is cleared or after; it needs none). */
    fun signedOut() = sync()

    private fun mine(): Set<String> = runCatching { com.blaze.assistant.agent.Settings.str(MINE).split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    private fun remember(id: String) { runCatching { com.blaze.assistant.agent.Settings.setStr(MINE, (mine() + id).joinToString(",")) } }

    /** Writes [text] to [f] readable by its owner only (0600), its folders 0700; tmp + rename. */
    private fun write(f: File, text: String) {
        val dir = f.parentFile ?: return
        dir.mkdirs()
        var d: File? = dir
        while (d != null && d.name != "root") { runCatching { android.system.Os.chmod(d.path, 448) }; d = d.parentFile }   // 0700 up to /root
        val tmp = File(dir, "." + f.name + ".tmp")
        tmp.writeText(text)
        runCatching { android.system.Os.chmod(tmp.path, 384) }   // 0600
        if (!tmp.renameTo(f)) { f.writeText(text); runCatching { android.system.Os.chmod(f.path, 384) }; tmp.delete() }
    }
}
