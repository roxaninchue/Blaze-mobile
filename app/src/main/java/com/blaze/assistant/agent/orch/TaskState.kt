package com.blaze.assistant.agent.orch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.47.0 one real task's pinned state (spec §2.1, §2.5): the goal, its acceptance checklist, the plan's subtasks and
 * where each one is. Written atomically on every transition so an app restart or crash can resume: READ / REVERSIBLE work
 * picks up from its last summary; a subtask that was in the middle of an OUTWARD action is never replayed — the user gets
 * one honest line and decides. Pure JSON + file I/O in a given directory (TaskStateTest).
 */
data class TaskState(
    val id: String,
    val chat: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val checks: List<Acceptance.Check> = emptyList(),
    val subtasks: List<Sub> = emptyList(),
    val status: Status = Status.RUNNING,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    enum class Status { RUNNING, DONE, PARTIAL, FAILED, STOPPED }
    enum class SubStatus { WAITING, RUNNING, OUTWARD_PENDING, DONE, FAILED, CANCELLED }

    data class Sub(val id: String, val specialist: String, val brief: String, val deps: List<String> = emptyList(),
                   val status: SubStatus = SubStatus.WAITING, val summary: String = "", val pendingAction: String = "")

    fun with(id: String, f: (Sub) -> Sub) = copy(subtasks = subtasks.map { if (it.id == id) f(it) else it })

    fun toJson(): JSONObject = JSONObject().put("id", id).put("chat", chat).put("goal", goal).put("constraints", JSONArray(constraints))
        .put("checks", JSONArray().apply { checks.forEach { put(it.toJson()) } })
        .put("subtasks", JSONArray().apply { subtasks.forEach { s -> put(JSONObject().put("id", s.id).put("specialist", s.specialist).put("brief", s.brief)
            .put("deps", JSONArray(s.deps)).put("status", s.status.name).put("summary", s.summary).put("pending", s.pendingAction)) } })
        .put("status", status.name).put("started", startedAt).put("updated", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): TaskState = TaskState(
            o.getString("id"), o.optString("chat"), o.optString("goal"),
            o.optJSONArray("constraints")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optJSONArray("checks")?.let { a -> (0 until a.length()).map { Acceptance.Check.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            o.optJSONArray("subtasks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
                Sub(s.getString("id"), s.optString("specialist"), s.optString("brief"),
                    s.optJSONArray("deps")?.let { d -> (0 until d.length()).map { d.getString(it) } } ?: emptyList(),
                    runCatching { SubStatus.valueOf(s.optString("status")) }.getOrDefault(SubStatus.WAITING), s.optString("summary"), s.optString("pending"))
            } } ?: emptyList(),
            runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.RUNNING), o.optLong("started"), o.optLong("updated"))
    }
}

/** Persisted task states, one file per task (tasks/<id>.json), written via a temp file + rename. */
class TaskStore(private val dir: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    init { dir.mkdirs() }
    private fun f(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    @Synchronized fun save(s: TaskState): TaskState {
        val t = s.copy(updatedAt = now())
        val tmp = File(dir, f(s.id).name + ".tmp"); tmp.writeText(t.toJson().toString()); if (!tmp.renameTo(f(s.id))) { f(s.id).writeText(t.toJson().toString()); tmp.delete() }
        return t
    }
    @Synchronized fun load(id: String): TaskState? = try { TaskState.fromJson(JSONObject(f(id).readText())) } catch (_: Exception) { null }
    @Synchronized fun all(): List<TaskState> = (dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).mapNotNull { try { TaskState.fromJson(JSONObject(it.readText())) } catch (_: Exception) { null } }
    @Synchronized fun remove(id: String) { f(id).delete() }
    /** Unfinished tasks (after a restart). */
    fun pending(): List<TaskState> = all().filter { it.status == TaskState.Status.RUNNING }.sortedBy { it.startedAt }
}

/**
 * Resume rules after a restart (pure): which subtasks run again, and the one line for an outward action that was
 * waiting / in flight (never replayed without the user).
 */
object TaskResume {
    data class Plan(val rerun: List<TaskState.Sub>, val keep: List<TaskState.Sub>, val askLines: List<String>)

    fun plan(s: TaskState): Plan {
        val rerun = ArrayList<TaskState.Sub>(); val keep = ArrayList<TaskState.Sub>(); val ask = ArrayList<String>()
        for (sub in s.subtasks) when (sub.status) {
            TaskState.SubStatus.DONE, TaskState.SubStatus.FAILED, TaskState.SubStatus.CANCELLED -> keep += sub
            TaskState.SubStatus.OUTWARD_PENDING -> { keep += sub; ask += askLine(sub.pendingAction.ifBlank { sub.brief }) }
            TaskState.SubStatus.WAITING, TaskState.SubStatus.RUNNING -> rerun += sub
        }
        return Plan(rerun, keep, ask)
    }

    fun askLine(action: String): String = "Blaze closed before I could finish this: ${action.trim().take(120)}. I haven't done it. Want me to go ahead?"

    /** Resume only fresh tasks (older ones get the honest "stopped" line instead). */
    const val MAX_AGE_MS = 30 * 60_000L

    /** The hidden instruction that resumes a task after a restart: what's done is never repeated; outward asks again. */
    fun prompt(s: TaskState, done: List<String>): String = buildString {
        append("[Blaze closed while working on this and has just restarted. Resume the task where it stopped.]\nGoal: ").append(s.goal.take(600))
        if (s.constraints.isNotEmpty()) append("\nConstraints: ").append(s.constraints.joinToString("; ").take(400))
        if (done.isNotEmpty()) { append("\nAlready done (check, never repeat): "); done.take(12).forEach { append("\n- ").append(it.take(160)) } }
        val open = s.checks.filterNot { it.met }
        if (open.isNotEmpty()) append("\nStill to finish and prove: ").append(open.joinToString("; ") { it.what })
        append("\nAnything that sends, pays, deletes, posts or installs must ask the user again. Read the current state first.")
    }

    /** The line shown once when a task is picked up again. */
    fun resumedLine(goal: String, n: Int): String =
        "Picking up where I left off on \u201c${goal.trim().take(60)}\u201d" + if (n > 0) " ($n part${if (n == 1) "" else "s"} to finish)." else "."
}
