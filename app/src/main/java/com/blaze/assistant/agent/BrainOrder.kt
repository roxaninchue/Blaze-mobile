package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * 0.46.3 brain order (owner decision 26 Oct 2026, 18:00; replaces "Blaze Cloud first, on-device backup second"):
 *  1. the on-device OpenCode brain (free models, through the bundled opencode server) when it is ready;
 *  2. Nous (the gateway's Nous reserve, free `:free` models only);
 *  3. Blaze Cloud (the gateway's own lineup) last.
 * Until OpenCode is ready (first-run setup still running, or switched off) the order starts at Nous, silently.
 * Coding jobs (code, repos, terminal, Exam Coach coding questions, REA) ask OpenCode for the strong free coders first;
 * requests carrying Google data (Gmail, Calendar, Drive, Contacts) only go to OpenCode's zero-retention models.
 * Never NVIDIA (Nemotron), Jev or System One models anywhere ([BackupModels.excluded]). Pure (BrainOrderTest).
 */
object BrainOrder {
    /** The strong free coders, in order (zero-retention, 200K context, ~32K output; free for a limited time, OpenCode only). */
    val CODERS = listOf("deepseek-v4.1-flash-free", "glm-5.3-flash-free")
    /** OpenCode free models known to keep no data (the only ones a Google-data request may use). */
    val ZERO_RETENTION = setOf("deepseek-v4.1-flash-free", "glm-5.3-flash-free")
    /** First-token cap for an OpenCode model before the next one (GLM free has shown 7 to 196 s): then fail over. */
    const val FIRST_TOKEN_MS = 20_000L
    /** Health probe of the on-device brain: at start and this often. */
    const val PROBE_EVERY_MS = 10 * 60_000L

    fun plan(backupReady: Boolean, signedIn: Boolean = true): List<BackupRoute.Step> = buildList {
        if (backupReady) add(BackupRoute.Step.BACKUP)
        if (signedIn) { add(BackupRoute.Step.RESERVE); add(BackupRoute.Step.CLOUD) }
    }

    /** Display names for Settings › Brains. */
    fun label(s: BackupRoute.Step): String = when (s) {
        BackupRoute.Step.BACKUP -> "On-phone brain"
        BackupRoute.Step.RESERVE -> "Nous reserve"
        BackupRoute.Step.CLOUD -> "Blaze Cloud"
    }

    private val codeWords = Regex("""(?i)\b(code|coding|bug|debug|function|class|method|compile|compiler|stack ?trace|traceback|exception|refactor|repo|repository|git|github|gitlab|commit|pull request|merge request|terminal|shell|bash|script|python|kotlin|java|javascript|typescript|rust|golang|c\+\+|sql|regex|api|json|yaml|dockerfile|npm|pip|gradle|apk|decompile|reverse engineer|rea|exam coach)\b|```""")
    private val codeTools = Regex("""^(terminal|run_command|shell|git|github_|gitlab_|code_|repo_|linux_|exam_coach|rea)""")

    private fun lastUser(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") return (m.opt("content") as? String) ?: m.optJSONArray("content")?.toString().orEmpty()
        }
        return ""
    }

    /** A coding job: the request talks code / repos / terminal, or the turn already used code tools. */
    fun coding(messages: JSONArray): Boolean {
        if (codeWords.containsMatchIn(lastUser(messages).take(4_000))) return true
        for (i in 0 until messages.length()) {
            val calls = messages.optJSONObject(i)?.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) if (codeTools.containsMatchIn(calls.optJSONObject(j)?.optJSONObject("function")?.optString("name").orEmpty())) return true
        }
        return false
    }

    private val googleTools = Regex("""^(gmail|gcal|gdrive|drive_|gcontacts|google_|gdocs|gsheets)""")

    /** The request carries Google user data (a Google tool ran in this conversation). */
    fun googleData(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool" && googleTools.containsMatchIn(m.optString("name"))) return true
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) if (googleTools.containsMatchIn(calls.optJSONObject(j)?.optJSONObject("function")?.optString("name").orEmpty())) return true
        }
        return false
    }

    /**
     * The OpenCode models for this job, from the server's ordered free list [ordered]: coders first for a coding job (asked
     * for even when the live list hasn't shown them yet), then the rest; only zero-retention models with Google data.
     */
    fun models(ordered: List<String>, coding: Boolean, googleData: Boolean): List<String> {
        val clean = ordered.filter { !BackupModels.excluded(it) }
        val base = if (coding) (CODERS + clean.filter { it !in CODERS }) else clean
        return (if (googleData) base.filter { it in ZERO_RETENTION }.ifEmpty { CODERS } else base).distinct()
    }
}

/** 0.46.3: usage and health per brain, recorded on the phone (the daily effort meter will read it). Pure core. */
class BrainLedger(private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Row(var calls: Int = 0, var fails: Int = 0, var ms: Long = 0, var lastOk: Long = 0, var lastFail: Long = 0, var lastError: String = "", var ttft: Long = 0,
                   var rel: Double = -1.0)
    val rows = linkedMapOf<BackupRoute.Step, Row>()
    @Synchronized fun ok(s: BackupRoute.Step, ms: Long) { val r = rows.getOrPut(s) { Row() }; r.calls++; r.ms += ms; r.lastOk = now(); r.rel = blend(r.rel, 1.0) }
    /** A real failure (error from the brain). 0.46.5: a brain cut by OUR deadline is not a failure: don't call this for it. */
    @Synchronized fun fail(s: BackupRoute.Step, why: String) { val r = rows.getOrPut(s) { Row() }; r.fails++; r.lastFail = now(); r.lastError = why.take(120); r.rel = blend(r.rel, 0.0) }
    /** 0.46.5: success rate, smoothed (new = 0.7 × old + 0.3 × this outcome; first outcome counts from 0.75); null = never measured. */
    @Synchronized fun reliability(s: BackupRoute.Step): Double? = rows[s]?.rel?.takeIf { it >= 0.0 }
    private fun blend(old: Double, x: Double) = if (old < 0) 0.75 * 0.7 + x * 0.3 else old * 0.7 + x * 0.3
    /** 0.46.4: first-token time (ms), smoothed (new = 0.6 × old + 0.4 × this call); the chat router puts the fastest first. */
    @Synchronized fun ttft(s: BackupRoute.Step, ms: Long) { val r = rows.getOrPut(s) { Row() }; r.ttft = if (r.ttft <= 0) ms.coerceAtLeast(1) else ((r.ttft * 6 + ms.coerceAtLeast(1) * 4) / 10) }
    @Synchronized fun ttft(s: BackupRoute.Step): Long? = rows[s]?.ttft?.takeIf { it > 0 }
    /** "answering", "failing", or "not used yet", from the newest outcome. */
    @Synchronized fun health(s: BackupRoute.Step): String { val r = rows[s] ?: return "not used yet"; return if (r.lastOk >= r.lastFail) "answering" else "failing" }
    @Synchronized fun toJson(): String = org.json.JSONObject().also { o -> rows.forEach { (k, r) -> o.put(k.name, org.json.JSONObject().put("calls", r.calls).put("fails", r.fails).put("ms", r.ms)
        .put("lastOk", r.lastOk).put("lastFail", r.lastFail).put("err", r.lastError).put("ttft", r.ttft).put("rel", r.rel)) } }.toString()
    @Synchronized fun load(json: String) { try { val o = org.json.JSONObject(json); o.keys().forEach { k -> val r = o.getJSONObject(k)
        rows[BackupRoute.Step.valueOf(k)] = Row(r.optInt("calls"), r.optInt("fails"), r.optLong("ms"), r.optLong("lastOk"), r.optLong("lastFail"), r.optString("err"), r.optLong("ttft"), r.optDouble("rel", -1.0)) } } catch (_: Exception) { } }

    companion object {
        /** The app's ledger (saved in Settings "brain_usage_v1"). */
        val app: BrainLedger by lazy { BrainLedger().also { l -> try { l.load(Settings.str("brain_usage_v1", "")) } catch (_: Throwable) { } } }
        fun save() { try { Settings.setStr("brain_usage_v1", app.toJson()) } catch (_: Throwable) { } }
    }
}
