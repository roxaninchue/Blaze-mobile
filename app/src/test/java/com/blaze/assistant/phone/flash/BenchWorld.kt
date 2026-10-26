package com.blaze.assistant.phone.flash

/**
 * 0.46.3 benchmark fixtures: a deterministic simulated phone (virtual clock, accessibility tree with bounds, gestures,
 * screenshots described by a mock vision model). Shared by both engines in FlashBench, so they face the same screens.
 *
 * What it models (from the real service code): accessibility clicks climb to the clickable parent; a custom view can report
 * a click as done and do nothing (only a touch works); screens can load late (a spinner sends content events until then);
 * a canvas/game surface has no accessibility nodes at all (only a screenshot shows it); a map has a toolbar in the tree but
 * its chips only on the screenshot. Nothing here talks to a model or a device.
 */
class BNode(val cls: String, var text: String = "", var desc: String = "", var hint: String = "",
            var clickable: Boolean = false, var editable: Boolean = false, var checkable: Boolean = false, var checked: Boolean = false,
            var scrollable: Boolean = false, var enabled: Boolean = true, var visible: Boolean = true,
            var l: Int = 0, var t: Int = 0, var r: Int = 0, var b: Int = 0) {
    var key = 0L
    val children = ArrayList<BNode>()
    var parent: BNode? = null
    var onClick: (() -> Unit)? = null
    /** ACTION_CLICK reports success but does nothing (custom view); a touch still works. */
    var deadA11y = false
    var onScroll: ((Boolean) -> Boolean)? = null
    var field: String? = null
    fun add(c: BNode): BNode { c.parent = this; children.add(c); return c }
    fun all(): List<BNode> = listOf(this) + children.flatMap { it.all() }
    fun contains(x: Int, y: Int) = x in l until r && y in t until b
}

object BAccess : NodeAccess<BNode> {
    override fun key(n: BNode) = n.key
    override fun visible(n: BNode) = n.visible
    override fun text(n: BNode) = n.text
    override fun desc(n: BNode) = n.desc
    override fun hint(n: BNode) = n.hint
    override fun cls(n: BNode) = n.cls
    override fun clickable(n: BNode) = n.clickable
    override fun editable(n: BNode) = n.editable
    override fun scrollable(n: BNode) = n.scrollable
    override fun checkable(n: BNode) = n.checkable
    override fun checked(n: BNode) = n.checked
    override fun selected(n: BNode) = false
    override fun enabled(n: BNode) = n.enabled
    override fun childCount(n: BNode) = n.children.size
    override fun child(n: BNode, i: Int): BNode? = n.children.getOrNull(i)
    override fun bounds(n: BNode) = intArrayOf(n.l, n.t, n.r, n.b)
}

/** Something only the screenshot shows (canvas, map chips), with what a touch there does. */
data class VThing(val label: String, val l: Int, val t: Int, val r: Int, val b: Int, val onTouch: () -> Unit)

class World(start: String) {
    companion object { const val W = 1080; const val H = 2400 }
    var screen = start; private set
    private val stack = ArrayList<String>()
    val flags = HashMap<String, Boolean>()
    val text = HashMap<String, String>()
    val offset = HashMap<String, Int>()
    val notes = mutableListOf("Groceries", "Ideas", "Trip plan")
    var dialog: String? = null
    var toast: String = ""
    var focused: String? = null
    var clock = 0L; private set
    private var lastEvent = 0L
    private var pending: Pair<String, Long>? = null
    var visual: List<VThing> = emptyList(); private set

    fun f(k: String) = flags[k] == true
    /** Events counted (two can share a millisecond), like the service's eventSeq. */
    var seq = 0L; private set
    fun event() { lastEvent = clock; seq++ }
    fun go(s: String, delayMs: Long = 0) {
        stack.add(screen); dialog = null
        if (delayMs <= 0) { screen = s; event() } else { screen = "loading"; pending = s to clock + delayMs; event() }
    }
    fun back() {
        if (dialog != null) { dialog = null; event(); return }
        if (stack.isNotEmpty()) { screen = stack.removeAt(stack.size - 1); pending = null; event() }
    }
    fun advance(ms: Long) {
        clock += ms
        pending?.let { (s, at) -> if (clock >= at) { screen = s; lastEvent = at; seq++; pending = null } }
    }
    /** A spinner animates (content events) until the load finishes. */
    fun lastEventAt(): Long = if (pending != null) clock else lastEvent
    fun pkg(): String = Screens.pkg(screen)

    /** The settle wait the service does: until quiet for [quietMs], at most [maxMs] (virtual time). */
    fun settle(maxMs: Long, quietMs: Long) {
        val start = clock
        advance(60)
        while (clock - lastEventAt() < quietMs && clock - start < maxMs) advance(40)
    }
    /** changedSince(before, ms) of the service: polls for an event. */
    fun changedSince(beforeSeq: Long, ms: Long): Boolean {
        val end = clock + ms
        while (clock < end) { if (seq > beforeSeq || pending != null) return true; advance(40) }
        return seq > beforeSeq
    }

    fun render(): BNode {
        val ui = Ui(this)
        Screens.build(this, ui)
        visual = ui.visual
        var k = 1L
        ui.root.all().forEach { it.key = k++ }
        return ui.root
    }

    // ---------- input ----------
    /** Accessibility click: climbs to the clickable parent like tapRaw. True = performAction returned true. */
    fun a11yClick(n: BNode): Boolean {
        var x: BNode? = n
        while (x != null && !x.clickable) x = x.parent
        val t = x ?: return false
        if (!t.enabled) return false
        if (t.deadA11y) return true
        t.onClick?.invoke(); return true
    }
    /** A finger touch at (x, y) px: a visual thing there, else the smallest clickable node there. */
    fun touch(x: Int, y: Int) {
        val root = render()
        visual.firstOrNull { x in it.l until it.r && y in it.t until it.b }?.let { it.onTouch(); return }
        root.all().filter { it.visible && it.clickable && it.enabled && it.contains(x, y) }.minByOrNull { (it.r - it.l).toLong() * (it.b - it.t) }?.onClick?.invoke()
    }
    fun setText(n: BNode, s: String): Boolean { val id = n.field ?: return false; text[id] = s; focused = id; event(); Screens.onText(this, id); return true }
    fun enter(): Boolean { val id = focused ?: return false; Screens.onEnter(this, id); return true }
    fun scroll(n: BNode?, forward: Boolean): Boolean {
        var x = n
        while (x != null && x.onScroll == null) x = x.parent
        return x?.onScroll?.invoke(forward) ?: false
    }
}

/** Screen layout helper: a top bar, then stacked rows; a list scrolls by its offset. */
class Ui(val w: World) {
    val root = BNode("android.widget.FrameLayout", l = 0, t = 0, r = World.W, b = World.H)
    val visual = ArrayList<VThing>()
    var y = 0
    fun bar(title: String, back: Boolean = false, vararg icons: Pair<String, () -> Unit>) {
        val bar = root.add(BNode("android.view.ViewGroup", l = 0, t = 0, r = World.W, b = 220))
        if (back) bar.add(BNode("android.widget.ImageButton", desc = "Navigate up", clickable = true, l = 0, t = 60, r = 140, b = 200)).onClick = { w.back() }
        bar.add(BNode("android.widget.TextView", text = title, l = 160, t = 80, r = 700, b = 180))
        icons.forEachIndexed { i, (d, act) -> bar.add(BNode("android.widget.ImageButton", desc = d, clickable = true, l = World.W - 150 * (i + 1), t = 60, r = World.W - 150 * i, b = 200)).onClick = act }
        y = 220
    }
    /** A scrollable list; rows outside the visible part aren't visible to accessibility. */
    fun list(id: String, rowH: Int, rows: List<(BNode, Int, Int) -> Unit>, horizontal: Boolean = false): BNode {
        val top = y
        val list = root.add(BNode(if (horizontal) "androidx.viewpager.widget.ViewPager" else "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = top, r = World.W, b = World.H))
        val span = if (horizontal) World.W else World.H - top
        val total = rows.size * (if (horizontal) World.W else rowH)
        val off = w.offset[id] ?: 0
        list.onScroll = { fwd ->
            val step = if (horizontal) World.W else (span * 0.8).toInt()
            val nv = ((w.offset[id] ?: 0) + if (fwd) step else -step).coerceIn(0, maxOf(0, total - span))
            if (nv != (w.offset[id] ?: 0)) { w.offset[id] = nv; w.event(); true } else false
        }
        rows.forEachIndexed { i, mk ->
            val frame = if (horizontal) BNode("android.widget.FrameLayout", l = i * World.W - off, t = top, r = (i + 1) * World.W - off, b = World.H)
                else BNode("android.widget.FrameLayout", l = 0, t = top + i * rowH - off, r = World.W, b = top + (i + 1) * rowH - off)
            frame.visible = frame.r > 0 && frame.l < World.W && frame.b > top && frame.t < World.H
            list.add(frame)
            mk(frame, frame.t, frame.b)
        }
        return list
    }
    /** A settings row: a clickable layout with a title, a summary and maybe a (non-clickable) switch. */
    fun row(parent: BNode, t: Int, b: Int, title: String, summary: String = "", switch: Boolean? = null, onClick: () -> Unit) {
        val row = parent.add(BNode("android.widget.LinearLayout", clickable = true, l = 0, t = t, r = World.W, b = b, visible = parent.visible))
        row.onClick = onClick
        row.add(BNode("android.widget.ImageView", l = 40, t = t + 50, r = 120, b = t + 130, visible = parent.visible))
        val texts = row.add(BNode("android.widget.RelativeLayout", l = 160, t = t + 20, r = 860, b = b - 20, visible = parent.visible))
        texts.add(BNode("android.widget.TextView", text = title, l = 160, t = t + 30, r = 860, b = t + 100, visible = parent.visible))
        if (summary.isNotEmpty()) texts.add(BNode("android.widget.TextView", text = summary, l = 160, t = t + 100, r = 860, b = t + 160, visible = parent.visible))
        if (switch != null) row.add(BNode("android.widget.Switch", checkable = true, checked = switch, l = 900, t = t + 60, r = 1040, b = t + 140, visible = parent.visible))
    }
    fun button(label: String, t: Int, desc: String = "", l: Int = 100, r: Int = 980, h: Int = 160, cls: String = "android.widget.Button", onClick: () -> Unit): BNode =
        root.add(BNode(cls, text = label, desc = desc, clickable = true, l = l, t = t, r = r, b = t + h)).also { it.onClick = onClick }
    fun label(s: String, t: Int, h: Int = 100) { root.add(BNode("android.widget.TextView", text = s, l = 60, t = t, r = 1020, b = t + h)) }
    fun field(id: String, hint: String, t: Int) {
        root.add(BNode("android.widget.EditText", text = w.text[id].orEmpty(), hint = hint, clickable = true, editable = true, l = 60, t = t, r = 1020, b = t + 160)).also {
            it.field = id; it.onClick = { w.focused = id; w.event() } }
    }
    fun dialog(title: String, vararg buttons: Pair<String, () -> Unit>) {
        val d = root.add(BNode("android.widget.FrameLayout", l = 80, t = 900, r = 1000, b = 1500))
        d.add(BNode("android.widget.TextView", text = title, l = 120, t = 950, r = 960, b = 1050))
        buttons.forEachIndexed { i, (lab, act) -> d.add(BNode("android.widget.Button", text = lab, clickable = true, l = 120 + i * 440, t = 1300, r = 520 + i * 440, b = 1440)).onClick = act }
    }
    fun canvas() { root.add(BNode("android.view.SurfaceView", l = 0, t = 0, r = World.W, b = World.H)) }
    fun see(label: String, l: Int, t: Int, r: Int, b: Int, onTouch: () -> Unit) { visual.add(VThing(label, l, t, r, b, onTouch)) }
}

object Screens {
    fun pkg(s: String): String = when {
        s.startsWith("settings") || s == "display" || s == "battery" || s == "search" || s.startsWith("page:") || s == "font" -> "com.android.settings"
        s.startsWith("notes") || s == "edit" -> "com.example.notes"
        s.startsWith("player") -> "com.example.player"
        s.startsWith("game") -> "com.example.game"
        s.startsWith("map") || s == "directions" -> "com.example.maps"
        s == "files" || s == "downloads" || s == "loading" || s.startsWith("viewer") -> "com.example.files"
        s.startsWith("onboard") || s == "welcome_home" -> "com.example.onboard"
        else -> "com.example.other"
    }

    private val settingsRows = listOf("Wi-Fi", "Bluetooth", "Display", "Battery", "Sound", "Notifications", "Apps", "Storage", "Privacy",
        "Location", "Security", "Accounts", "Accessibility", "Digital wellbeing", "Google", "System", "About phone")

    fun build(w: World, ui: Ui) {
        when (val s = w.screen) {
            "settings" -> {
                ui.bar("Settings", false, "Search settings" to { w.go("search") })
                ui.list("settings", 200, settingsRows.map { name -> { p: BNode, t: Int, b: Int ->
                    when (name) {
                        "Wi-Fi" -> ui.row(p, t, b, name, if (w.f("wifi")) "Connected" else "Off", w.f("wifi")) { w.flags["wifi"] = !w.f("wifi"); w.event() }
                        "Bluetooth" -> ui.row(p, t, b, name, if (w.f("bt")) "On" else "Off", w.f("bt")) { w.flags["bt"] = !w.f("bt"); w.event() }
                        "Display" -> ui.row(p, t, b, name, "Brightness and text") { w.go("display") }
                        "Battery" -> ui.row(p, t, b, name, "82%") { w.go("battery") }
                        else -> ui.row(p, t, b, name) { w.go("page:$name") }
                    }
                } })
            }
            "display" -> {
                ui.bar("Display", true)
                ui.list("display", 200, listOf<(BNode, Int, Int) -> Unit>(
                    { p, t, b -> ui.row(p, t, b, "Brightness level", "60%") { } },
                    { p, t, b -> ui.row(p, t, b, "Adaptive brightness", "", w.f("adaptive")) { w.flags["adaptive"] = !w.f("adaptive"); w.event() } },
                    { p, t, b -> ui.row(p, t, b, "Dark theme", "", w.f("dark")) { w.flags["dark"] = !w.f("dark"); w.event() } },
                    { p, t, b -> ui.row(p, t, b, "Font size", w.text["font"] ?: "Default") { w.go("font") } },
                    { p, t, b -> ui.row(p, t, b, "Screen timeout", "30 seconds") { } },
                ))
            }
            "font" -> {
                ui.bar("Font size", true)
                listOf("Small", "Default", "Large", "Largest").forEachIndexed { i, sz ->
                    ui.root.add(BNode("android.widget.RadioButton", text = sz, clickable = true, checkable = true, checked = (w.text["font"] ?: "Default") == sz,
                        l = 60, t = 300 + i * 180, r = 1020, b = 460 + i * 180)).onClick = { w.text["font"] = sz; w.event() }
                }
            }
            "battery" -> {
                ui.bar("Battery", true)
                ui.list("battery", 200, listOf<(BNode, Int, Int) -> Unit>(
                    { p, t, b -> ui.row(p, t, b, "Battery usage") { } },
                    { p, t, b -> ui.row(p, t, b, "Battery Saver", if (w.f("saver")) "On" else "Off", w.f("saver")) {
                        if (w.f("saver")) { w.flags["saver"] = false; w.event() } else { w.dialog = "saver"; w.event() } } },
                    { p, t, b -> ui.row(p, t, b, "Battery percentage", "", true) { } },
                ))
                if (w.dialog == "saver") ui.dialog("Turn on Battery Saver?", "Cancel" to { w.dialog = null; w.event() }, "Turn on" to { w.flags["saver"] = true; w.dialog = null; w.event() })
            }
            "search" -> {
                ui.bar("Search", true)
                ui.field("q", "Search settings", 240)
                val q = w.text["q"].orEmpty().trim().lowercase()
                if (q.isNotEmpty()) settingsRows.filter { it.lowercase().contains(q) }.forEachIndexed { i, name ->
                    ui.button(name, 460 + i * 180, cls = "android.widget.TextView") { if (name == "Bluetooth") w.go("page:Bluetooth") else w.go("page:$name") }
                }
            }
            "page:Bluetooth" -> {
                ui.bar("Bluetooth", true)
                ui.root.add(BNode("android.widget.Switch", text = "Use Bluetooth", clickable = true, checkable = true, checked = w.f("bt"), l = 40, t = 260, r = 1040, b = 420)).onClick = { w.flags["bt"] = !w.f("bt"); w.event() }
            }
            "page:About phone" -> {
                ui.bar("About phone", true)
                ui.list("about", 200, listOf<(BNode, Int, Int) -> Unit>(
                    { p, t, b -> ui.row(p, t, b, "Device name", "Pixel 8") { } },
                    { p, t, b -> ui.row(p, t, b, "Phone number", "Unknown") { } },
                    { p, t, b -> ui.row(p, t, b, "Android version", "15") { } },
                    { p, t, b -> ui.row(p, t, b, "Build number", "AP4A.250105") { } },
                ))
            }
            "notes" -> {
                ui.bar("Notes", false, "More options" to { w.dialog = "menu"; w.event() })
                ui.button("Export", 230, l = 700, r = 1040, h = 120) { /* broken: does nothing */ }
                ui.y = 380
                ui.list("notes", 220, w.notes.map { n -> { p: BNode, t: Int, b: Int ->
                    p.add(BNode("android.widget.TextView", text = n, clickable = true, l = 40, t = t + 20, r = 1040, b = b - 20, visible = p.visible)).onClick = { w.go("notes_view:$n") }
                } })
                ui.button("", 2150, desc = "New note", l = 860, r = 1040, h = 180, cls = "android.widget.ImageButton") { w.go("edit") }
                if (w.dialog == "menu") ui.dialog("Menu", "Export all" to { w.toast = "Exported ${w.notes.size} notes"; w.dialog = null; w.go("notes_exported") }, "Settings" to { w.dialog = null; w.event() })
                if (w.toast.isNotEmpty() && w.screen == "notes") ui.label(w.toast, 2000)
            }
            "notes_exported" -> { ui.bar("Notes", true); ui.label(w.toast, 400) }
            "edit" -> {
                ui.bar("New note", true)
                ui.field("title", "Title", 260)
                ui.field("body", "Note", 460)
                ui.button("Save", 2100) { val t = w.text["title"].orEmpty().trim(); if (t.isNotEmpty()) { w.notes.add(0, t); w.text.remove("title"); w.back() } }
            }
            "player" -> {
                ui.bar("Player", false)
                ui.label("Song A by The Band", 400)
                ui.label(if (w.f("playing")) "Playing" else "Paused", 520)
                ui.button("", 1800, desc = if (w.f("playing")) "Pause" else "Play", l = 420, r = 660, h = 240, cls = "android.widget.ImageButton") { w.flags["playing"] = !w.f("playing"); w.event() }
                    .deadA11y = true
            }
            "files" -> {
                ui.bar("Files", false)
                ui.list("files", 200, listOf("Downloads", "Documents", "Pictures").map { n -> { p: BNode, t: Int, b: Int ->
                    ui.row(p, t, b, n) { if (n == "Downloads") w.go("downloads", 1500) else w.go("page:$n") } } })
            }
            "loading" -> { ui.bar("Files", true); ui.root.add(BNode("android.widget.ProgressBar", desc = "Loading", l = 440, t = 1100, r = 640, b = 1300)) }
            "downloads" -> {
                ui.bar("Downloads", true)
                ui.list("dl", 200, listOf("report.pdf", "photo.jpg", "song.mp3").map { n -> { p: BNode, t: Int, b: Int -> ui.row(p, t, b, n, "1.2 MB") { w.go("viewer:$n") } } })
            }
            "onboard" -> {
                ui.list("pager", 0, (1..3).map { i -> { p: BNode, t: Int, b: Int ->
                    p.add(BNode("android.widget.TextView", text = "Welcome $i of 3", l = p.l + 100, t = 600, r = p.l + 980, b = 720, visible = p.visible))
                    if (i == 3) p.add(BNode("android.widget.Button", text = "Get started", clickable = true, l = p.l + 200, t = 2000, r = p.l + 880, b = 2160, visible = p.visible)).onClick = { w.go("welcome_home") }
                } }, horizontal = true)
            }
            "welcome_home" -> { ui.bar("Home", false); ui.label("You're all set", 400) }
            "game" -> {
                ui.canvas()
                ui.see("Start", 340, 1500, 740, 1700) { w.go("game_play") }
                ui.see("Settings gear", 900, 80, 1040, 220) { w.go("game_settings") }
                ui.see("Sky Hopper", 200, 500, 880, 700) { }
            }
            "game_play" -> { ui.canvas(); ui.see("Score 0", 40, 80, 400, 200) { }; ui.see("Pause", 900, 80, 1040, 220) { } }
            "game_settings" -> {
                ui.canvas()
                ui.see("Sound: " + (if (w.flags["sound"] != false) "On" else "Off"), 200, 800, 880, 960) { w.flags["sound"] = w.flags["sound"] == false; w.event() }
                ui.see("Back", 40, 80, 200, 220) { w.back() }
            }
            "map" -> {
                ui.root.add(BNode("android.widget.EditText", hint = "Search here", clickable = true, editable = true, l = 40, t = 80, r = 1040, b = 220)).field = "mapq"
                ui.root.add(BNode("android.view.View", l = 0, t = 240, r = World.W, b = World.H))   // the map surface: no accessibility
                ui.see("Directions", 60, 260, 380, 360) { w.go("directions") }
                ui.see("Restaurants", 400, 260, 760, 360) { }
            }
            "directions" -> { ui.bar("Directions", true); ui.field("from", "Your location", 260); ui.field("to", "Choose destination", 460) }
            else -> when {
                s.startsWith("page:") -> { ui.bar(s.removePrefix("page:"), true); ui.label("Nothing to change here", 400) }
                s.startsWith("notes_view:") -> { ui.bar(s.removePrefix("notes_view:"), true); ui.label("Note: " + s.removePrefix("notes_view:"), 400) }
                s.startsWith("viewer:") -> { ui.bar(s.removePrefix("viewer:"), true); ui.label("Page 1 of 3", 400) }
                else -> ui.label("?", 400)
            }
        }
    }

    fun onText(w: World, id: String) { }
    fun onEnter(w: World, id: String) { if (id == "q") w.event() }
}
