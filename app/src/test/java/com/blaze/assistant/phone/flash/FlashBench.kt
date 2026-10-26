package com.blaze.assistant.phone.flash

import com.blaze.assistant.phone.ScreenTree
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 0.46.3 benchmark: the OLD phone engine (Blaze's agent loop with read_screen / tap / type_text / scroll / press_button,
 * replayed with the real code's rules: read_screen's format and stable numbers, tapRaw's 700 ms click check and touch
 * fallback, screenAfter's 1200/200 ms settle and diff read, the switch want= check, no loop guard for taps) and the NEW
 * flash engine (FlashLoop with the real FlashTree / FlashParse / FlashVerify / FlashGuard) on the same fixture tasks.
 *
 * Both are driven by the SAME deterministic scripted policy (a mock model, no network): it follows each task's intent
 * script, reads only the text the engine gives it, retries a step that did nothing once, then takes the task's
 * alternative path, presses back after landing on the wrong screen, and makes the same scheduled "slips" (a neighbour's
 * index with the intended label) in both engines. Screenshots are read by a mock vision model that returns the fixture's
 * drawn elements. So the numbers measure the ENGINES (observation, targeting, fallbacks, verification, loop handling,
 * round trips and waits), not model intelligence. Time is virtual: device waits follow the real code's constants and every
 * model call costs the same fixed [MODEL_MS] in both engines (an assumption, stated in the report).
 */
object FlashBench {
    const val MODEL_MS = 2000L
    const val MAX_STEPS = 20

    // ---------------- tasks ----------------
    sealed class Intent {
        data class Tap(val label: String, val marker: String? = null, val slip: Boolean = false) : Intent()
        data class Toggle(val label: String, val want: Boolean) : Intent()
        data class Type(val field: String, val text: String, val submit: Boolean = false) : Intent()
        data class Key(val name: String, val marker: String? = null) : Intent()
        data class WaitFor(val label: String) : Intent()
        data class Answer(val find: String) : Intent()
        object Finish : Intent()
    }

    class Task(val id: String, val title: String, val start: String, val kind: String, val intents: List<Intent>,
               val alt: List<Intent>? = null, val setup: (World) -> Unit = {}, val success: (World, String) -> Boolean)

    val tasks: List<Task> = listOf(
        Task("wifi_on", "Turn on Wi-Fi", "settings", "toggle", listOf(Intent.Toggle("Wi-Fi", true), Intent.Finish)) { w, _ -> w.f("wifi") },
        Task("wifi_already_on", "Turn on Wi-Fi (already on)", "settings", "toggle", listOf(Intent.Toggle("Wi-Fi", true), Intent.Finish),
            setup = { it.flags["wifi"] = true }) { w, _ -> w.f("wifi") },
        Task("bt_off", "Turn off Bluetooth", "settings", "toggle", listOf(Intent.Toggle("Bluetooth", false), Intent.Finish),
            setup = { it.flags["bt"] = true }) { w, _ -> !w.f("bt") },
        Task("dark_theme", "Turn on Dark theme", "settings", "navigate+toggle",
            listOf(Intent.Tap("Display", "Dark theme", slip = true), Intent.Toggle("Dark theme", true), Intent.Finish)) { w, _ -> w.f("dark") },
        Task("adaptive_off", "Turn off Adaptive brightness", "settings", "navigate+toggle",
            listOf(Intent.Tap("Display", "Adaptive brightness"), Intent.Toggle("Adaptive brightness", false), Intent.Finish),
            setup = { it.flags["adaptive"] = true }) { w, _ -> !w.f("adaptive") },
        Task("font_large", "Set font size to Large", "settings", "navigate",
            listOf(Intent.Tap("Display", "Font size"), Intent.Tap("Font size", "Largest", slip = true), Intent.Tap("Large"), Intent.Finish)) { w, _ -> w.text["font"] == "Large" },
        Task("battery_saver", "Turn on Battery Saver (confirm dialog)", "settings", "dialog",
            listOf(Intent.Tap("Battery", "Battery Saver"), Intent.Tap("Battery Saver", "Turn on Battery Saver"), Intent.Tap("Turn on"), Intent.Finish)) { w, _ -> w.f("saver") },
        Task("search_bt", "Search settings for Bluetooth and turn it on", "settings", "search+type",
            listOf(Intent.Tap("Search settings", "Search settings"), Intent.Type("Search settings", "bluetooth"), Intent.Tap("Bluetooth", "Use Bluetooth"),
                Intent.Toggle("Use Bluetooth", true), Intent.Finish)) { w, _ -> w.f("bt") },
        Task("android_version", "What Android version is this?", "settings", "scroll+read",
            listOf(Intent.Tap("About phone", "Android version"), Intent.Answer("15"))) { _, a -> a.contains("15") },
        Task("note_create", "Create a note titled Milk", "notes", "form",
            listOf(Intent.Tap("New note", "Title"), Intent.Type("Title", "Milk"), Intent.Tap("Save", "Notes"), Intent.Finish)) { w, _ -> w.notes.firstOrNull() == "Milk" },
        Task("note_open", "Open the note Trip plan", "notes", "list",
            listOf(Intent.Tap("Trip plan", "Note: Trip plan", slip = true), Intent.Finish)) { w, _ -> w.screen == "notes_view:Trip plan" },
        Task("note_export", "Export all notes (Export button is broken)", "notes", "recovery",
            listOf(Intent.Tap("Export", "Exported"), Intent.Finish),
            alt = listOf(Intent.Tap("More options", "Export all"), Intent.Tap("Export all", "Exported"), Intent.Finish)) { w, _ -> w.toast.startsWith("Exported") },
        Task("play_dead_click", "Start playback (custom Play button ignores accessibility clicks)", "player", "dead-click",
            listOf(Intent.Tap("Play", "Playing"), Intent.Finish)) { w, _ -> w.f("playing") },
        Task("files_slow", "Open report.pdf in Downloads (slow load)", "files", "slow-load",
            listOf(Intent.Tap("Downloads", "report.pdf"), Intent.Tap("report.pdf", "Page 1 of 3"), Intent.Finish)) { w, _ -> w.screen == "viewer:report.pdf" },
        Task("onboarding", "Get through onboarding (Get started is on page 3)", "onboard", "pager",
            listOf(Intent.Tap("Get started", "all set"), Intent.Finish)) { w, _ -> w.screen == "welcome_home" },
        Task("game_start", "Start the game (canvas, no accessibility tree)", "game", "canvas",
            listOf(Intent.Tap("Start", "Score"), Intent.Finish)) { w, _ -> w.screen == "game_play" },
        Task("game_sound", "Turn off the game's sound (canvas)", "game", "canvas",
            listOf(Intent.Tap("Settings gear", "Sound"), Intent.Tap("Sound: On", "Sound: Off"), Intent.Finish)) { w, _ -> w.flags["sound"] == false },
        Task("map_directions", "Open Directions in the map (chip only on screen pixels)", "map", "partial-tree",
            listOf(Intent.Tap("Directions", "Choose destination"), Intent.Finish)) { w, _ -> w.screen == "directions" },
    )

    // ---------------- what the policy sees ----------------
    data class PEl(val index: Int, val role: String, val label: String, val checked: Boolean?, val clickable: Boolean)
    data class View(val els: List<PEl>, val visual: List<VThing>?, val unchanged: Boolean, val failed: Boolean, val notRun: Boolean, val described: String = "")

    sealed class Dec {
        data class TapEl(val index: Int, val label: String, val expect: String) : Dec()
        data class TapXY(val x: Int, val y: Int, val label: String, val expect: String) : Dec()
        data class ToggleEl(val index: Int, val want: Boolean, val label: String) : Dec()
        data class TypeEl(val index: Int, val text: String, val submit: Boolean) : Dec()
        data class Scroll(val dir: String) : Dec()
        data class Key(val name: String, val expect: String) : Dec()
        object Wait : Dec()
        object Look : Dec()
        data class TapText(val label: String) : Dec()
        data class Done(val answer: String) : Dec()
        data class Fail(val reason: String) : Dec()
    }

    private val lineRe = Regex("""^\[(\d+)] (\w+)(?: hint)?(?: "([^"]*)")?(?: \(([^)]*)\))?(.*)$""")

    fun parseLine(s: String): PEl? {
        val m = lineRe.find(s.trim()) ?: return null
        val rest = m.groupValues[5]
        val label = listOf(m.groupValues[3], m.groupValues[4]).filter { it.isNotBlank() && it != "no label" }.joinToString(" ")
        val checked = when { Regex("""\bon\b""").containsMatchIn(rest.substringBefore('[')) -> true; Regex("""\boff\b""").containsMatchIn(rest.substringBefore('[')) -> false; else -> null }
        val role = m.groupValues[2]
        return PEl(m.groupValues[1].toInt(), role, label, checked, role in setOf("btn", "item", "link", "field", "switch", "check", "radio") || rest.contains("tappable"))
    }

    /** An unlabelled switch line right after a label (the old format lists a row's switch as its own line). */
    fun withSwitchStates(els: List<PEl>): List<PEl> = els.mapIndexed { i, e ->
        if (e.checked != null || e.label.isEmpty()) e
        else els.drop(i + 1).take(3).firstOrNull { it.label.isEmpty() && it.checked != null && it.role in setOf("switch", "check") }?.let { e.copy(checked = it.checked) } ?: e
    }

    // ---------------- the scripted policy (same brain for both engines) ----------------
    class Policy(val task: Task, val oldTools: Boolean) {
        private var plan = task.intents
        private var ptr = 0
        private var pending: Intent? = null
        private var tries = 0
        private var scrolls = 0
        private var waits = 0
        private var usedAlt = false
        private var slipped = HashSet<Int>()
        private var looked = false
        private var afterBack = false

        private fun find(v: View, label: String, field: Boolean = false): PEl? {
            val l = label.lowercase()
            val pool = if (field) v.els else v.els.filter { it.role != "field" }
            return pool.firstOrNull { it.label.lowercase() == l } ?: pool.filter { it.label.lowercase().contains(l) }.minByOrNull { it.label.length }
        }
        private fun visible(v: View, s: String): Boolean = s.lowercase().let { t -> v.els.any { it.label.lowercase().contains(t) } || v.visual?.any { it.label.lowercase().contains(t) } == true || v.described.lowercase().contains(t) }
        private fun loading(v: View) = v.els.any { it.label.contains("Loading") }

        private fun achieved(p: Intent, v: View): Boolean = when (p) {
            is Intent.Tap -> p.marker?.let { visible(v, it) } ?: (!v.unchanged && !v.failed)
            is Intent.Toggle -> find(v, p.label)?.checked?.let { it == p.want } ?: (!v.unchanged && !v.failed)
            is Intent.Type -> v.els.any { it.label.lowercase().contains(p.text.lowercase()) }
            is Intent.Key -> p.marker?.let { visible(v, it) } ?: true
            else -> true
        }
        private fun navigating(p: Intent) = p is Intent.Tap && p.marker != null

        private fun switchAlt(): Boolean {
            val a = task.alt ?: return false
            if (usedAlt) return false
            usedAlt = true; plan = a; ptr = 0; tries = 0; return true
        }

        fun decide(v: View): Dec {
            if (afterBack) afterBack = false
            else pending?.let { p ->
                when {
                    v.notRun -> pending = null   // the engine didn't run it (target check, escalation): decide again
                    achieved(p, v) -> { pending = null; ptr++; tries = 0; waits = 0 }
                    loading(v) && waits < 4 -> { waits++; return Dec.Wait }
                    v.unchanged || v.failed -> { pending = null; tries++; if (tries >= 2 && !switchAlt()) return Dec.Fail("\u201c${label(p)}\u201d did nothing, twice.") }
                    p is Intent.Toggle && find(v, p.label)?.checked != null -> { pending = null; tries++; if (tries >= 2 && !switchAlt()) return Dec.Fail("\u201c${label(p)}\u201d didn't switch.") }
                    navigating(p) && find(v, label(p)) == null -> { pending = null; tries++; afterBack = true; return Dec.Key("back", label(p)) }
                    navigating(p) -> { pending = null; tries++ }
                    else -> { pending = null; ptr++; tries = 0 }
                }
            }
            while (ptr < plan.size) {
                when (val it = plan[ptr]) {
                    is Intent.Finish -> return Dec.Done("Done: ${task.title}.")
                    is Intent.Answer -> { val e = find(v, it.find); if (e != null) return Dec.Done("It shows ${e.label}."); return scrollOr(v, it.find) }
                    is Intent.WaitFor -> { if (visible(v, it.label)) { ptr++; continue }; if (waits++ < 4) return Dec.Wait; return Dec.Fail("${it.label} never appeared") }
                    is Intent.Key -> { pending = it; return Dec.Key(it.name, it.marker ?: "") }
                    is Intent.Toggle -> {
                        val e = find(v, it.label) ?: return scrollOr(v, it.label)
                        scrolls = 0
                        if (e.checked == it.want) { ptr++; continue }
                        pending = it
                        return if (oldTools) Dec.ToggleEl(e.index, it.want, it.label) else Dec.TapEl(e.index, it.label, it.label)
                    }
                    is Intent.Type -> {
                        val e = v.els.firstOrNull { e -> e.role == "field" && e.label.lowercase().contains(it.field.lowercase()) } ?: v.els.firstOrNull { e -> e.role == "field" }
                            ?: return Dec.Fail("no field ${it.field}")
                        pending = it
                        return Dec.TypeEl(e.index, it.text, it.submit)
                    }
                    is Intent.Tap -> {
                        val e = find(v, it.label)
                        if (e != null) {
                            scrolls = 0; pending = it
                            val ord = ptr + (if (usedAlt) 100 else 0)
                            if (it.slip && ord !in slipped) {
                                slipped.add(ord)
                                val pos = v.els.indexOf(e)
                                // the element listed just before the target (the same row in both formats), as a hurried model misreads it
                                val n = v.els.take(maxOf(0, pos)).lastOrNull { o -> o.label.isNotEmpty() && !o.label.contains(e.label) && !e.label.contains(o.label) && o.role != "list" && o.role != "field" }
                                if (n != null) return Dec.TapEl(n.index, it.label, it.marker ?: "")
                            }
                            return Dec.TapEl(e.index, it.label, it.marker ?: "")
                        }
                        v.visual?.firstOrNull { t -> t.label.lowercase().contains(it.label.lowercase()) }?.let { t ->
                            scrolls = 0; pending = it
                            return Dec.TapXY(FlashTree.norm((t.l + t.r) / 2, World.W), FlashTree.norm((t.t + t.b) / 2, World.H), it.label, it.marker ?: "")
                        }
                        if (oldTools && v.els.isEmpty()) {
                            if (!looked) { looked = true; return Dec.Look }
                            pending = it
                            return Dec.TapText(it.label)
                        }
                        return scrollOr(v, it.label)
                    }
                }
            }
            return Dec.Done("Done: ${task.title}.")
        }

        private fun scrollOr(v: View, label: String): Dec {
            if (v.unchanged && scrolls > 0) scrolls = 99   // the last scroll moved nothing
            if (scrolls < 4) { scrolls++; return Dec.Scroll("down") }
            return Dec.Fail("Couldn't find \u201c$label\u201d on this screen.")
        }
        private fun label(p: Intent) = when (p) { is Intent.Tap -> p.label; is Intent.Toggle -> p.label; is Intent.Type -> p.field; is Intent.Key -> p.name; else -> "step" }
    }

    // ---------------- results ----------------
    data class Run(val task: String, val engine: String, val saidDone: Boolean, val success: Boolean, val modelCalls: Int, val steps: Int,
                   val ms: Long, val waitMs: Long, val obsChars: Int, val error: String) {
        val secPerStep: Double get() = ms / 1000.0 / maxOf(1, steps)
    }

    // ---------------- NEW engine on the fixture ----------------
    class SimDevice(val w: World) : FlashDevice {
        private var map: Map<Int, BNode> = emptyMap()
        var waitMs = 0L
        override fun now() = w.clock
        override suspend fun observe(shot: Boolean): Obs {
            val root = w.render()
            val items = FlashTree.build(root, BAccess, World.W, World.H)
            map = items.associate { it.second.index to it.first }
            val nodes = items.map { it.second }
            if (!shot) return Obs(w.pkg(), w.pkg(), World.W, World.H, nodes)
            val h = w.visual.fold(w.screen.hashCode().toLong()) { a, t -> a * 31 + t.label.hashCode() }
            return Obs(w.pkg(), w.pkg(), World.W, World.H, nodes, "data:image/jpeg;base64,FIXTURE", h)
        }
        private fun wait(block: () -> Unit) { val t = w.clock; block(); waitMs += w.clock - t }
        override suspend fun act(a: PhoneAct, on: Obs): ActResult {
            val r = when (a) {
                is PhoneAct.Tap -> {
                    val n = map[a.index] ?: return ActResult(false, "No element [${a.index}].")
                    val before = w.seq
                    var res = ""
                    wait {
                        // flashTap: accessibility click (checked for 450 ms), else a touch at the centre
                        res = if (w.a11yClick(n) && w.changedSince(before, 450)) "Tapped." else { w.advance(60); w.touch((n.l + n.r) / 2, (n.t + n.b) / 2); "Tapped by touch." }
                    }
                    res
                }
                is PhoneAct.TapAt -> { w.advance(60); w.touch(FlashTree.px(a.x, World.W), FlashTree.px(a.y, World.H)); "Touched the screen there." }
                is PhoneAct.Type -> {
                    var n = a.index?.let { map[it] }
                    if (n != null && !n.editable) { w.a11yClick(n); wait { w.settle(700, 150) }; n = null }
                    val f = n ?: w.render().all().firstOrNull { it.editable && it.field == w.focused } ?: return ActResult(false, "No text field to type into.")
                    w.setText(f, a.text)
                    if (a.submit) { wait { w.settle(300, 100) }; w.enter() }
                    "Typed."
                }
                is PhoneAct.Scroll -> {
                    val n = a.index?.let { map[it] } ?: w.render().all().firstOrNull { it.scrollable && it.visible }
                    if (w.scroll(n, a.direction == "down" || a.direction == "right")) "Scrolled." else { w.advance(350); "Swiped." }
                }
                is PhoneAct.Key -> { if (a.name == "back") w.back() else if (a.name == "enter") w.enter(); "Pressed ${a.name}." }
                is PhoneAct.Wait -> { wait { w.advance(a.ms) }; "Waited." }
                else -> "Nothing to do."
            }
            if (a !is PhoneAct.Wait) wait { w.settle(1000, 150) }
            return ActResult(true, r)
        }
    }

    class NewPolicy(val w: World, val p: Policy) : FlashPolicy {
        var chars = 0
        private var lastLines = 0
        override suspend fun next(req: FlashRequest): String {
            w.advance(MODEL_MS)
            chars += FlashPrompt.user(req).length
            val last = if (req.history.size > 0 && (req.step > 1)) req.history.last() else ""
            val fresh = req.history.size != lastLines || req.note.isNotEmpty()
            lastLines = req.history.size
            val els = req.screen.lines().mapNotNull { parseLine(it) }
            val sig = last + " " + req.note
            val v = View(els, if (req.shot != null) w.visual else null,
                unchanged = fresh && Regex("(?i)nothing changed|nothing moved|still (on|off)").containsMatchIn(sig),
                failed = fresh && Regex("(?i)not done|blocked by loop guard|Not run|kept doing nothing").containsMatchIn(sig),
                notRun = fresh && Regex("(?i)no such element|target mismatch|→ pro mode|Target check failed|There is no element").containsMatchIn(sig))
            val o = JSONObject()
            when (val d = p.decide(v)) {
                is Dec.TapEl -> o.put("action", "tap").put("target", d.index).put("label", d.label).put("expect", d.expect)
                is Dec.TapXY -> o.put("action", "tap").put("target", org.json.JSONArray().put(d.x).put(d.y)).put("target_description", d.label).put("expect", d.expect)
                is Dec.ToggleEl -> o.put("action", "tap").put("target", d.index).put("label", d.label)
                is Dec.TypeEl -> o.put("action", "type").put("target", d.index).put("text", d.text).put("submit", d.submit)
                is Dec.Scroll -> o.put("action", "scroll").put("direction", d.dir)
                is Dec.Key -> o.put("action", d.name).put("expect", d.expect)
                is Dec.Wait -> o.put("action", "wait").put("seconds", 1)
                is Dec.Done -> o.put("action", "done").put("answer", d.answer)
                is Dec.Fail -> o.put("action", "fail").put("reason", d.reason)
                is Dec.Look, is Dec.TapText -> o.put("action", "fail").put("reason", "unsupported")
            }
            return o.toString()
        }
    }

    fun runNew(t: Task): Run {
        val w = World(t.start); t.setup(w)
        val dev = SimDevice(w)
        val pol = NewPolicy(w, Policy(t, oldTools = false))
        val r = runBlocking { FlashLoop(dev, pol, MAX_STEPS).run(t.title) }
        val acted = r.log.count { it.contains("] ") && it.contains(" → ") && !it.contains("no such element") && !it.contains("target mismatch") && !it.contains("blocked") }
        return Run(t.id, "new", r.ok, r.ok && t.success(w, r.answer), r.modelCalls, acted, w.clock, dev.waitMs, pol.chars / maxOf(1, r.modelCalls), r.error)
    }

    // ---------------- OLD engine on the fixture ----------------
    /** read_screen as BlazeAccessibilityService.readScreen does it (format, stable numbers, diffs, bare rows left out). */
    class OldScreen(val w: World) {
        private var keyToId = HashMap<String, Int>()
        private var lastLines = HashMap<Int, String>()
        private var lastPkg = ""
        private var lastAt = -1_000_000L
        val elements = ArrayList<BNode?>()
        private val bare = Regex("^\\[\\d+] item( disabled)?$")

        fun read(diff: Boolean): String {
            val root = w.render(); val pkg = w.pkg()
            val found = ArrayList<Pair<BNode, String>>()
            ScreenTree(BAccess).walkFull(root, found)
            val seen = HashMap<String, Int>()
            val keys = found.map { (n, _) ->
                val base = ScreenTree.role(n.cls, n.editable, n.clickable) + "|" + n.text + "|" + n.desc + "|"
                val k = seen.getOrDefault(base, 0); seen[base] = k + 1; "$base#$k"
            }
            val samePkg = pkg == lastPkg && w.clock - lastAt < 120_000
            val overlap = if (samePkg) keys.count { it in keyToId } else 0
            val keep = samePkg && found.isNotEmpty() && overlap * 10 >= found.size * 3
            val newMap = HashMap<String, Int>(); val used = HashSet<Int>()
            if (keep) keys.forEach { k -> keyToId[k]?.let { if (used.add(it)) newMap[k] = it } }
            var next = if (keep) (keyToId.values.maxOrNull() ?: -1) + 1 else 0
            keys.forEach { k -> if (k !in newMap) { while (next in used) next++; newMap[k] = next; used.add(next); next++ } }
            elements.clear()
            val lines = HashMap<Int, String>()
            found.forEachIndexed { i, (n, line) -> val id = newMap[keys[i]]!!; while (elements.size <= id) elements.add(null); elements[id] = n; lines[id] = "[$id] $line" }
            val prev = lastLines
            keyToId = newMap; lastLines = lines; lastPkg = pkg; lastAt = w.clock
            val ordered = found.indices.map { newMap[keys[it]]!! }
            val sb = StringBuilder()
            if (diff && keep && prev.isNotEmpty()) {
                val changed = ordered.filter { prev[it] != lines[it] }
                val gone = prev.keys.filter { it !in lines }
                if (changed.isEmpty() && gone.isEmpty()) return "Same screen in $pkg, nothing changed."
                if (changed.size + gone.size < found.size / 2 + 1) {
                    sb.append("Same screen in ").append(pkg).append(". Changed or new:\n")
                    changed.forEach { lines[it]?.takeIf { l -> !bare.matches(l.trim()) }?.let { l -> sb.append(l).append("\n") } }
                    if (gone.isNotEmpty()) sb.append("Gone: ").append(gone.take(20).joinToString(" ") { "[$it]" }).append("\n")
                    sb.append("Other ").append(found.size - changed.size).append(" elements unchanged, same numbers.")
                    return sb.toString()
                }
            }
            sb.append(pkg).append(" (").append(pkg).append("). Elements, use the [number]:\n")
            ordered.forEach { lines[it]?.takeIf { l -> !bare.matches(l.trim()) }?.let { l -> sb.append(l).append("\n") } }
            if (found.isEmpty()) sb.append("(nothing readable on screen)\n")
            val out = sb.toString()
            return if (out.length <= 6_000) out else out.take(6_000) + "\n…(more below; scroll to see it)"
        }
    }

    /** What the old model has in mind: the last full screen, patched by diffs. */
    class OldMind {
        private var lines = ArrayList<String>()
        fun update(result: String): View {
            val screen = result.substringAfter("Screen now:\n", result)
            val unchanged = screen.contains("nothing changed")
            val failed = Regex("^(Couldn't|No element|Error|Stopped)").containsMatchIn(result.trim()) || result.contains("No element matching")
            when {
                screen.contains("Elements, use the [number]:") -> lines = ArrayList(screen.substringAfter("Elements, use the [number]:\n").lines().filter { it.startsWith("[") })
                screen.contains("Changed or new:") -> {
                    val ch = screen.lines().filter { it.startsWith("[") }
                    ch.forEach { l -> val id = l.substringBefore(']'); val i = lines.indexOfFirst { it.substringBefore(']') == id }; if (i >= 0) lines[i] = l else lines.add(l) }
                    Regex("""Gone: (.*)""").find(screen)?.groupValues?.get(1)?.let { g -> val ids = Regex("""\[(\d+)]""").findAll(g).map { "[" + it.groupValues[1] }.toSet(); lines.removeAll { it.substringBefore(']') in ids } }
                }
            }
            val els = withSwitchStates(lines.mapNotNull { parseLine(it) })
            return View(els, null, unchanged, failed, false, if (result.startsWith("Screenshot:")) result else "")
        }
    }

    fun runOld(t: Task): Run {
        val w = World(t.start); t.setup(w)
        val scr = OldScreen(w); val mind = OldMind(); val pol = Policy(t, oldTools = true)
        var calls = 0; var steps = 0; var chars = 0; var waitMs = 0L
        fun model(input: String) { calls++; chars += input.length; w.advance(MODEL_MS) }
        fun waited(block: () -> Unit) { val t0 = w.clock; block(); waitMs += w.clock - t0 }
        fun screenAfter(r: String): String {
            if (r.startsWith("Error") || r.startsWith("No app") || r.startsWith("Not done yet")) return ""
            waited { w.settle(1200, 200) }
            return "\n\nScreen now:\n" + scr.read(true)
        }
        fun tapRaw(id: Int): String {
            val node = scr.elements.getOrNull(id) ?: return "No element [$id]. Call read_screen again to get fresh numbers."
            val before = w.seq
            var r = ""
            waited { r = if (w.a11yClick(node) && w.changedSince(before, 700)) "Tapped [$id]." else { w.advance(60); w.touch((node.l + node.r) / 2, (node.t + node.b) / 2); "Tapped [$id] by touch." } }
            return r
        }
        fun switchState(id: Int): Boolean? {
            val n = scr.elements.getOrNull(id) ?: return null
            if (n.checkable) return n.checked
            var row: BNode? = n; var hops = 0
            while (row != null && !row.clickable && hops < 4) { row = row.parent; hops++ }
            return row?.takeIf { it.clickable }?.all()?.firstOrNull { it.checkable }?.checked
        }
        // turn 1: the model reads the screen
        model(t.title)
        var result = scr.read(false)
        var error = ""; var done = false; var answer = ""
        while (steps < MAX_STEPS) {
            val v = mind.update(result)
            model(result)
            val d = pol.decide(v)
            when (d) {
                is Dec.Done -> { done = true; answer = d.answer; break }
                is Dec.Fail -> { error = d.reason; break }
                is Dec.Look -> { steps++; result = "Screenshot: " + w.visual.joinToString(", ") { it.label } + " (look_at_screen describes; tap takes an element number or text)" }
                is Dec.TapText -> { steps++
                    val found = scr.read(false).lines().mapNotNull { parseLine(it) }.firstOrNull { it.label.lowercase().contains(d.label.lowercase()) }
                    result = if (found == null) "No element matching \"${d.label}\" on this screen, even after scrolling through it. Read the screen for the real labels." else tapRaw(found.index).let { it + screenAfter(it) } }
                is Dec.TapEl -> { steps++; val r = tapRaw(d.index); result = r + screenAfter(r) }
                is Dec.ToggleEl -> { steps++
                    val now = switchState(d.index)
                    result = if (now == d.want) "Already ${if (d.want) "on" else "off"}: \u201c${d.label}\u201d. Not tapped." + screenAfter("Already")
                    else { val r = tapRaw(d.index); val after = switchState(d.index); (r + " " + if (after == d.want) "[checked: now ${if (d.want) "on" else "off"}]" else "[check failed]") + screenAfter(r) } }
                is Dec.TypeEl -> { steps++
                    val n = scr.elements.getOrNull(d.index)
                    val r = when {
                        n == null -> "No element [${d.index}]. Call read_screen again."
                        n.editable -> { w.setText(n, d.text); if (d.submit) { waited { w.settle(300, 100) }; w.enter(); "Typed into [${d.index}] and pressed Enter/Search." } else "Typed into [${d.index}]." }
                        else -> { tapRaw(d.index); waited { w.settle(700, 150) }; val f = w.render().all().firstOrNull { it.editable && it.field == w.focused }
                            if (f == null) "Couldn't type into [${d.index}]; it may not be a text field." else { w.setText(f, d.text); "Typed into [${d.index}]." } }
                    }
                    result = r + screenAfter(r) }
                is Dec.Scroll -> { steps++
                    val target = scr.elements.firstOrNull { it?.scrollable == true }
                    val r = if (target != null && w.scroll(target, true)) "Scrolled down." else { w.advance(350); "Swiped to scroll down." }
                    result = r + screenAfter(r) }
                is Dec.TapXY -> { steps++; result = "Error: tap takes an element number or text, not coordinates." }
                is Dec.Key -> { steps++; if (d.name == "back") w.back(); val r = "Pressed ${d.name}."; result = r + screenAfter(r) }
                is Dec.Wait -> { steps++; waited { w.advance(1000) }
                    // "wait" returns no screen: the model reads it on its next turn
                    model("Waited."); result = scr.read(true) }
            }
        }
        if (!done && error.isEmpty()) error = "Ran out of steps ($MAX_STEPS)."
        return Run(t.id, "old", done, done && t.success(w, answer), calls, steps, w.clock, waitMs, chars / maxOf(1, calls), error)
    }

    // ---------------- report ----------------
    fun median(xs: List<Double>): Double { if (xs.isEmpty()) return 0.0; val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    fun report(old: List<Run>, new: List<Run>): String = buildString {
        fun pct(rs: List<Run>) = 100.0 * rs.count { it.success } / maxOf(1, rs.size)
        fun f1(d: Double) = String.format(java.util.Locale.US, "%.1f", d)
        fun f2(d: Double) = String.format(java.util.Locale.US, "%.2f", d)
        val both = old.indices.filter { old[it].success && new[it].success }
        append("# Blaze phone control benchmark (0.46.3): old engine vs flash engine\n\n")
        append("${old.size} deterministic fixture tasks, scripted mock policy (same for both engines), mock vision for screenshots, virtual clock; ")
        append("every model call costs a fixed ${MODEL_MS} ms in both engines (assumption). Not measured on a phone or with a live model.\n\n")
        append("| metric | old | new |\n|---|---|---|\n")
        append("| success rate | ${f1(pct(old))}% (${old.count { it.success }}/${old.size}) | ${f1(pct(new))}% (${new.count { it.success }}/${new.size}) |\n")
        append("| said done but wrong | ${old.count { it.saidDone && !it.success }} | ${new.count { it.saidDone && !it.success }} |\n")
        append("| median s/step (all tasks) | ${f2(median(old.map { it.secPerStep }))} | ${f2(median(new.map { it.secPerStep }))} |\n")
        append("| median s/task (all tasks) | ${f1(median(old.map { it.ms / 1000.0 }))} | ${f1(median(new.map { it.ms / 1000.0 }))} |\n")
        append("| median s/task (${both.size} tasks both solved) | ${f1(median(both.map { old[it].ms / 1000.0 }))} | ${f1(median(both.map { new[it].ms / 1000.0 }))} |\n")
        append("| median s/step (both solved) | ${f2(median(both.map { old[it].secPerStep }))} | ${f2(median(both.map { new[it].secPerStep }))} |\n")
        append("| model calls / task (mean) | ${f2(old.map { it.modelCalls }.average())} | ${f2(new.map { it.modelCalls }.average())} |\n")
        append("| device wait s / task (mean) | ${f2(old.map { it.waitMs / 1000.0 }.average())} | ${f2(new.map { it.waitMs / 1000.0 }.average())} |\n")
        append("| prompt chars per model call (mean, screen+context) | ${old.map { it.obsChars }.average().toInt()} | ${new.map { it.obsChars }.average().toInt()} |\n\n")
        append("| task | old | new | old calls/steps/s | new calls/steps/s | old error | new error |\n|---|---|---|---|---|---|---|\n")
        old.indices.forEach { i ->
            val o = old[i]; val n = new[i]
            append("| ${o.task} | ${if (o.success) "ok" else "FAIL"} | ${if (n.success) "ok" else "FAIL"} | ${o.modelCalls}/${o.steps}/${f1(o.ms / 1000.0)} | ${n.modelCalls}/${n.steps}/${f1(n.ms / 1000.0)} | ${o.error.take(60).replace('|', '/')} | ${n.error.take(60).replace('|', '/')} |\n")
        }
    }

    fun runAll(): Pair<List<Run>, List<Run>> = tasks.map { runOld(it) } to tasks.map { runNew(it) }
}
