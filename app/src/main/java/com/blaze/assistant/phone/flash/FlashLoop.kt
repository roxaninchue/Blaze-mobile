/*
 * The flash observe-act loop, the pro plan-and-verify escalation and the stagnation rule ("the same action on the same
 * target twice with no change: stop retrying") follow the design of google/artemis (Apache-2.0, Copyright 2026 Google LLC:
 * artemis/agents/flash, artemis/agents/flash/flash_runner.md section 4, artemis/utils/image_diff.py). Rewritten in Kotlin
 * for Blaze. See THIRD_PARTY_NOTICES.md.
 */
package com.blaze.assistant.phone.flash

/** What the engine saw: the app in front, the indexed elements, and (only when the tree is empty) a screenshot. */
data class Obs(
    val app: String,
    val pkg: String,
    val w: Int,
    val h: Int,
    val nodes: List<UiNode>,
    /** data:image/jpeg URL, only taken when the tree is empty (visual fallback). */
    val shot: String? = null,
    /** dHash of the screenshot, for "did it change" when there is no tree. */
    val shotHash: Long? = null,
    /** Set when Blaze must not read this screen (Bank mode); the text says why. */
    val refused: String? = null,
) {
    val sig: Long by lazy { FlashTree.signature(pkg, nodes, h) }
    val visual: Boolean get() = nodes.isEmpty()
    fun node(i: Int): UiNode? = nodes.firstOrNull { it.index == i }
    fun text(): String = FlashTree.format(app, pkg, nodes, w, h, shot != null)
}

/** What the device did with an action ([ok] = it was carried out, not that it had the intended effect). */
data class ActResult(val ok: Boolean, val msg: String)

interface FlashDevice {
    /** The screen now. [shot] = also take a screenshot (asked only when the tree is empty). */
    suspend fun observe(shot: Boolean): Obs
    suspend fun act(a: PhoneAct, on: Obs): ActResult
    /** Milliseconds (virtual in tests). */
    fun now(): Long
}

enum class FlashMode { FLASH, PRO }

data class FlashRequest(val goal: String, val mode: FlashMode, val screen: String, val history: List<String>, val note: String, val shot: String?, val step: Int)

/** The model. Returns its raw reply (one JSON action). */
interface FlashPolicy {
    suspend fun next(req: FlashRequest): String
}

data class FlashResult(
    val ok: Boolean,
    val answer: String,
    val steps: Int,
    val modelCalls: Int,
    val proCalls: Int,
    val log: List<String>,
    val error: String = "",
    val ms: Long = 0,
    val visualSteps: Int = 0,
)

/**
 * Loop guard for screen actions (the agent's own LoopGuard exempts taps and scrolls, because repeating them is how a
 * phone task moves on). Rules, per task:
 *  - the same action on the same screen that already changed nothing: escalate (pro mode); a second time: blocked;
 *  - the screen bouncing between the same two states for 6 observations: escalate; for 8: stop;
 *  - one screen seen [VISITS_MAX] times: stop.
 */
class FlashGuard {
    enum class Verdict { OK, ESCALATE, BLOCK, STOP }
    companion object { const val VISITS_MAX = 6 }

    private val noChange = HashMap<String, Int>()
    private val visits = HashMap<Long, Int>()
    private val sigs = ArrayList<Long>()

    fun seen(sig: Long) {
        visits[sig] = (visits[sig] ?: 0) + 1
        sigs.add(sig)
    }

    /** Before acting [key] on screen [sig]. */
    fun check(sig: Long, key: String): Verdict {
        if (key == "wait" || key == "done" || key == "fail") return Verdict.OK
        if ((visits[sig] ?: 0) >= VISITS_MAX) return Verdict.STOP
        val bounce = bouncing()
        if (bounce >= 8) return Verdict.STOP
        val n = noChange["$sig|$key"] ?: 0
        if (n >= 2) return Verdict.BLOCK
        if (n >= 1 || bounce >= 6) return Verdict.ESCALATE
        return Verdict.OK
    }

    fun record(sig: Long, key: String, changed: Boolean) {
        if (!changed) noChange["$sig|$key"] = (noChange["$sig|$key"] ?: 0) + 1
    }

    /** How many of the latest observations alternate between exactly two screens (A B A B…). */
    fun bouncing(): Int {
        if (sigs.size < 4) return 0
        val a = sigs[sigs.size - 1]; val b = sigs[sigs.size - 2]
        if (a == b) return 0
        var n = 0
        for (i in sigs.indices.reversed()) { if (sigs[i] == (if ((sigs.size - 1 - i) % 2 == 0) a else b)) n++ else break }
        return n
    }
}

/** After each action: did the screen change, and does it show what the model expected? Honest notes either way. */
object FlashVerify {
    data class V(val changed: Boolean, val expected: Boolean?, val ok: Boolean, val note: String)

    private val stop = setOf("the", "and", "with", "that", "this", "screen", "page", "shows", "show", "should", "will", "open", "opens", "opened",
        "appear", "appears", "visible", "now", "into", "from", "after", "list", "button", "field", "text", "there", "then", "have", "been")

    fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.distinct()

    fun check(before: Obs, after: Obs, a: PhoneAct, res: ActResult): V {
        if (!res.ok) return V(false, null, false, "Not done: ${res.msg}")
        val changed = if (before.visual && after.visual && before.shotHash != null && after.shotHash != null)
            FlashHash.distance(before.shotHash, after.shotHash) >= FlashHash.CHANGED_BITS || before.pkg != after.pkg
        else before.sig != after.sig
        if (a is PhoneAct.Wait) return V(changed, null, true, if (changed) "Waited; the screen moved on." else "Waited; nothing changed.")
        // typing: the field must hold the text now
        if (a is PhoneAct.Type) {
            val want = a.text.trim().lowercase()
            val has = after.nodes.any { it.editable && it.label.lowercase().contains(want) } || (a.submit && changed)
            return if (has) V(true, true, true, "Typed; the field shows it.") else V(changed, false, false, "Typed, but no field on screen shows \u201c${a.text.take(40)}\u201d.")
        }
        // a switch: its state must have flipped
        if (a is PhoneAct.Tap) {
            val b0 = before.node(a.index)
            if (b0?.checked != null) {
                val a0 = after.nodes.firstOrNull { it.role == b0.role && kotlin.math.abs(it.cy - b0.cy) < 40 && it.label.substringBefore(" \u00b7 ") == b0.label.substringBefore(" \u00b7 ") }
                if (a0?.checked != null && a0.checked != b0.checked) return V(true, true, true, "\u201c${b0.label.substringBefore(" \u00b7 ").take(40)}\u201d is now ${if (a0.checked) "on" else "off"}.")
                if (a0?.checked != null && !changed) return V(false, false, false, "Tapped \u201c${b0.label.take(40)}\u201d but it is still ${if (a0.checked) "on" else "off"}.")
            }
        }
        if (!changed) return V(false, null, false, when (a) {
            is PhoneAct.Scroll -> "Scrolled ${a.direction}, but nothing moved (the end of the list, or nothing scrolls here)."
            else -> "Nothing changed on the screen after: ${a.describe()}."
        })
        val ws = words(a.expect)
        if (ws.isEmpty()) return V(true, null, true, "The screen changed.")
        val hay = (after.app + " " + after.pkg + " " + after.nodes.joinToString(" ") { it.label }).lowercase()
        val was = (before.app + " " + before.pkg + " " + before.nodes.joinToString(" ") { it.label }).lowercase()
        // words that were already on screen before the step prove nothing: only newly shown words count
        val fresh = ws.filter { !was.contains(it) }
        if (fresh.isEmpty()) return V(true, null, true, "The screen changed (what was expected was already showing, so it can't be confirmed).")
        val hit = fresh.count { hay.contains(it) }
        return if (hit > 0) V(true, true, true, "The screen changed as expected.")
        else if (after.visual) V(true, null, true, "The screen changed (no text to check it against).")
        else V(true, false, false, "The screen changed, but I don't see what was expected (\u201c${a.expect.take(60)}\u201d).")
    }
}

/**
 * The flash engine: observe → one model call → act → verify, every step. Pro mode (plan, target check, expected outcome)
 * only after a step failed or the loop guard warned; back to flash after a step that worked. Errors are reported as they
 * are: nothing is called done unless the model says so after seeing the screen, and every failure names its reason.
 */
class FlashLoop(
    private val device: FlashDevice,
    private val policy: FlashPolicy,
    private val maxSteps: Int = 20,
    private val maxFails: Int = 4,
) {
    suspend fun run(goal: String): FlashResult {
        val t0 = device.now()
        val log = ArrayList<String>()
        val guard = FlashGuard()
        var mode = FlashMode.FLASH
        var calls = 0; var pro = 0; var fails = 0; var modelErrors = 0; var visualSteps = 0
        var note = ""
        var obs = look()
        fun result(ok: Boolean, answer: String, steps: Int, error: String = "") =
            FlashResult(ok, answer, steps, calls, pro, log, error, device.now() - t0, visualSteps)
        obs.refused?.let { return result(false, "", 0, it) }
        guard.seen(obs.sig)
        for (step in 1..maxSteps) {
            // pro mode after a failed step: also look at the pixels (a target can be drawn without being in the tree)
            if (mode == FlashMode.PRO && fails > 0 && obs.shot == null) obs = device.observe(true).let { if (it.refused != null) obs else it }
            if (obs.visual || obs.shot != null) visualSteps++
            val raw = try {
                policy.next(FlashRequest(goal, mode, obs.text(), log.takeLast(8), note, obs.shot, step)).also { calls++; if (mode == FlashMode.PRO) pro++; modelErrors = 0 }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                calls++; modelErrors++
                log.add("step $step: model error ${e.message.orEmpty().take(80)}")
                if (modelErrors >= 2) return result(false, "", step, "The model didn't answer (twice): ${e.message.orEmpty().take(120)}")
                continue
            }
            val act = FlashParse.parse(raw)
            if (act == null) {
                fails++; note = "Your last reply wasn't one valid action JSON. Reply with exactly one JSON object."
                log.add("step $step [${mode.name.lowercase()}]: unreadable reply")
                mode = FlashMode.PRO
                if (fails >= maxFails) return result(false, "", step, "Stopped: the model's replies weren't usable actions ($fails in a row).")
                continue
            }
            when (act) {
                is PhoneAct.Done -> { log.add("step $step: done"); return result(true, act.answer, step) }
                is PhoneAct.Fail -> { log.add("step $step: gave up"); return result(false, "", step, act.reason) }
                else -> { }
            }
            // the target must be on this screen
            val missing = when (act) {
                is PhoneAct.Tap -> obs.node(act.index) == null
                is PhoneAct.Type -> act.index != null && obs.node(act.index) == null
                is PhoneAct.Scroll -> act.index != null && obs.node(act.index) == null
                else -> false
            }
            if (missing) {
                fails++; mode = FlashMode.PRO
                note = "There is no element with that index on this screen. Use an index from the current list, or [x,y]."
                log.add("step $step: ${act.describe()} → no such element")
                if (fails >= maxFails) return result(false, "", step, "Stopped after $fails failed steps: ${note}")
                continue
            }
            // pro mode: the named target must be the element at that index (Artemis checks targets before acting)
            if (mode == FlashMode.PRO && act is PhoneAct.Tap && act.label.isNotBlank()) {
                val lab = obs.node(act.index)?.label.orEmpty()
                if (!labelMatches(act.label, lab)) {
                    fails++
                    note = "Target check failed: [${act.index}] is \u201c${lab.take(50)}\u201d, not \u201c${act.label.take(50)}\u201d. Nothing was tapped."
                    log.add("step $step: ${act.describe()} → target mismatch")
                    if (fails >= maxFails) return result(false, "", step, "Stopped after $fails failed steps: $note")
                    continue
                }
            }
            when (guard.check(obs.sig, act.key())) {
                FlashGuard.Verdict.STOP -> { log.add("step $step: loop guard stop"); return result(false, "", step, "Stopped: Blaze was going round in circles on this screen (loop guard). Last tried: ${act.describe()}.") }
                FlashGuard.Verdict.BLOCK -> {
                    fails++; mode = FlashMode.PRO
                    note = "Not run: ${act.describe()} already changed nothing twice on this screen. Try a different way (another element, scroll, back, or [x,y])."
                    log.add("step $step: ${act.describe()} → blocked by loop guard")
                    if (fails >= maxFails) return result(false, "", step, "Stopped after $fails failed steps: ${act.describe()} kept doing nothing.")
                    continue
                }
                FlashGuard.Verdict.ESCALATE -> if (mode == FlashMode.FLASH) {
                    mode = FlashMode.PRO
                    note = "Careful: this exact action already did nothing here. Plan first, then choose."
                    log.add("step $step: loop guard → pro mode")
                    continue
                }
                FlashGuard.Verdict.OK -> { }
            }
            val res = try { device.act(act, obs) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ActResult(false, e.message ?: "error") }
            val after = look()
            after.refused?.let { log.add("step $step: refused screen"); return result(false, "", step, it) }
            val v = FlashVerify.check(obs, after, act, res)
            guard.record(obs.sig, act.key(), v.changed)
            guard.seen(after.sig)
            log.add("step $step [${mode.name.lowercase()}]: ${act.describe()} → ${v.note}")
            if (v.ok) { fails = 0; note = ""; mode = FlashMode.FLASH }
            else {
                fails++; note = v.note; mode = FlashMode.PRO
                if (fails >= maxFails) return result(false, "", step, "Stopped after $fails failed steps in a row. Last: ${v.note}")
            }
            obs = after
        }
        return result(false, "", maxSteps, "Ran out of steps ($maxSteps) before the task was done.")
    }

    /** The screen, with a screenshot only when the tree is empty. */
    private suspend fun look(): Obs {
        val o = device.observe(false)
        return if (o.refused == null && o.nodes.isEmpty()) device.observe(true) else o
    }

    companion object {
        fun labelMatches(want: String, have: String): Boolean {
            val w = want.trim().lowercase(); val h = have.trim().lowercase()
            if (w.isEmpty()) return true
            if (h.contains(w) || (h.isNotEmpty() && w.contains(h))) return true
            val ws = FlashVerify.words(w)
            return ws.isNotEmpty() && ws.count { h.contains(it) } * 2 >= ws.size
        }
    }
}

/** The prompts. Short on purpose: flash steps are one small model call each. */
object FlashPrompt {
    val SYSTEM_FLASH = """
You control an Android phone for the user, one step at a time. Each turn you get the goal, the screen as a numbered list
of elements ([index] role "label" state [left,top][right,bottom] on a 0-1000 grid) and your recent steps.
Reply with exactly ONE JSON object and nothing else:
{"action":"tap","target":3,"label":"Wi-Fi","expect":"Wi-Fi settings"}
Actions: tap / long_press (target = index, or [x,y] on the 0-1000 grid only when the element isn't listed),
type (target = index of the field, text, submit true/false), scroll (direction up/down/left/right, optional target),
back, home, open_app (app), wait (seconds), done (answer = what you did or found), fail (reason).
Rules: use an index from the CURRENT list only. Always give "expect": a few words that will be on screen after the step.
A switch shows on/off: if it is already as wanted, reply done. Never call done before the screen shows the goal is met.
If a step did nothing, don't repeat it: try another element, scroll, back, or coordinates. Never type passwords or pay.
""".trim()

    val SYSTEM_PRO = SYSTEM_FLASH + """

PRO MODE (the last step failed or repeated): first think in one or two short sentences in "plan" (what went wrong,
the next milestone), then give the action. "label" must be the exact label of the element you target; it is checked
before anything is tapped. "expect" is required.
{"plan":"...","action":"tap","target":7,"label":"Display","expect":"Dark theme"}
""".trimEnd()

    fun user(r: FlashRequest): String = buildString {
        append("Goal: ").append(r.goal).append("\n\n")
        if (r.history.isNotEmpty()) { append("Recent steps:\n"); r.history.forEach { append("- ").append(it).append('\n') }; append('\n') }
        if (r.note.isNotBlank()) append("Note: ").append(r.note).append("\n\n")
        append("Screen now (step ").append(r.step).append("):\n").append(r.screen)
    }
}
