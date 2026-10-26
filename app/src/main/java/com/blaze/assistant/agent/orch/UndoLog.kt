package com.blaze.assistant.agent.orch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.47.0 checkpoint / undo log (spec §2.4). Before a REVERSIBLE action runs, how to put it back is written down:
 *  - FILE: the file's bytes (≤ [MAX_FILE] bytes) copied under undo/ (or "absent": undo deletes the file it created);
 *  - SETTING: the setting's key and previous value (restored through [Hooks.setSetting]);
 *  - ROUTINE / EVENT / MEMORY / DRAFT: an id the matching hook removes or restores.
 * Outward actions are never in here (a sent message can't be unsent; the audit log shows it as sent).
 * Persisted as JSON (last [MAX] entries). Pure apart from [File] I/O under the given directory (UndoLogTest uses a temp dir).
 */
class UndoLog(private val dir: File, private val hooks: Hooks = Hooks(), private val now: () -> Long = { System.currentTimeMillis() }) {
    companion object { const val MAX = 200; const val MAX_FILE = 2L * 1024 * 1024 }

    enum class Kind { FILE, SETTING, ROUTINE, EVENT, MEMORY, DRAFT }

    data class Entry(val id: String, val at: Long, val kind: Kind, val label: String, val target: String, val before: String,
                     val task: String = "", val undone: Boolean = false)

    /** What restoring a non-file entry does (set by the app; tests pass fakes). Each returns a plain line. */
    class Hooks(
        val setSetting: (key: String, value: String) -> String = { k, _ -> "Restored $k." },
        val removeRoutine: (id: String) -> String = { "Removed." },
        val removeEvent: (id: String) -> String = { "Removed." },
        val removeMemory: (id: String) -> String = { "Removed." },
        val discardDraft: (id: String) -> String = { "Discarded." },
    )

    private val file = File(dir, "undo_log.json")
    private val blobs = File(dir, "undo").apply { mkdirs() }
    private var seq = 0

    @Synchronized fun all(): List<Entry> = try {
        val a = JSONArray(if (file.exists()) file.readText() else "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.getString("id"), it.optLong("at"), Kind.valueOf(it.optString("kind", "FILE")), it.optString("label"), it.optString("target"),
                it.optString("before"), it.optString("task"), it.optBoolean("undone"))
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized private fun save(list: List<Entry>) {
        val keep = list.takeLast(MAX)
        list.dropLast(keep.size.coerceAtMost(list.size)).let { gone -> gone.filter { it.kind == Kind.FILE }.forEach { File(blobs, it.id).delete() } }
        val a = JSONArray(); keep.forEach { e -> a.put(JSONObject().put("id", e.id).put("at", e.at).put("kind", e.kind.name).put("label", e.label)
            .put("target", e.target).put("before", e.before).put("task", e.task).put("undone", e.undone)) }
        val tmp = File(dir, "undo_log.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(file)
    }

    private fun newId() = "u${now()}_${seq++}"

    /** Snapshot of a file before it is written / edited. Too big → null (the gate then asks first). */
    @Synchronized fun checkpointFile(path: String, label: String, task: String = ""): Entry? {
        val f = File(path)
        val id = newId()
        val before = when {
            !f.exists() -> "absent"
            f.isDirectory -> return null
            f.length() > MAX_FILE -> return null
            else -> { f.copyTo(File(blobs, id), overwrite = true); "copy" }
        }
        val e = Entry(id, now(), Kind.FILE, label, path, before, task)
        save(all() + e); return e
    }

    @Synchronized fun checkpoint(kind: Kind, label: String, target: String, before: String, task: String = ""): Entry {
        require(kind != Kind.FILE) { "use checkpointFile" }
        val e = Entry(newId(), now(), kind, label, target, before, task)
        save(all() + e); return e
    }

    /** Restores one entry (once). Returns a plain line for the chat. */
    @Synchronized fun undo(id: String): String {
        val list = all()
        val e = list.firstOrNull { it.id == id } ?: return "Nothing to undo."
        if (e.undone) return "Already undone."
        val line = try {
            when (e.kind) {
                Kind.FILE -> {
                    val f = File(e.target)
                    if (e.before == "absent") { f.delete(); "Removed ${f.name} (it didn't exist before)." }
                    else { File(blobs, e.id).copyTo(f, overwrite = true); "Restored ${f.name}." }
                }
                Kind.SETTING -> hooks.setSetting(e.target, e.before)
                Kind.ROUTINE -> hooks.removeRoutine(e.target)
                Kind.EVENT -> hooks.removeEvent(e.target)
                Kind.MEMORY -> hooks.removeMemory(e.target)
                Kind.DRAFT -> hooks.discardDraft(e.target)
            }
        } catch (ex: Exception) { return "Couldn't undo: ${ex.message.orEmpty().take(80)}" }
        save(list.map { if (it.id == id) it.copy(undone = true) else it })
        return line
    }

    /** Undoes everything a task changed, newest first. */
    @Synchronized fun undoTask(task: String): List<String> = all().filter { it.task == task && !it.undone }.reversed().map { undo(it.id) }
}
