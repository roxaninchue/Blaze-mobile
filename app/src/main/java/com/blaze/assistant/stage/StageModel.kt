package com.blaze.assistant.stage

/**
 * 0.46.0 Blaze Stage: the chat screen is one playground. Everything on it is an element Blaze (stage_* tools) or the user
 * (drag, the element menu) can place, resize or hide: the chat stream, the top bar, the reply bar, one hero (the big area)
 * and small widgets docked in slots. Nothing is a fixed rule; [StageReducer] only keeps a few invariants:
 *  - at most one hero; showing a new hero turns the old one into a widget (same object, it morphs, nothing stops);
 *  - at most [StageReducer.MAX_WIDGETS] widgets (the oldest idle one leaves first, music / timers / video last);
 *  - the reply bar is never gone: hidden means a small floating input pill;
 *  - back always steps toward the normal chat (hero -> widget, then chrome and chat back), and Reset layout restores it at once.
 * Pure Kotlin (no Android), unit-tested in StageReducerTest.
 */
enum class StageKind(val id: String, val label: String, val fullView: String?) {
    TIMER("timer", "Timer", null), MUSIC("music", "Music", "music"), VIDEO("video", "Video", "video"),
    QUIZ("quiz", "Quiz", "learn"), FLASHCARDS("flashcards", "Flashcards", "learn"), PLAN("plan", "Plan", null), NOTE("note", "Note", null);
    companion object {
        fun of(s: String?): StageKind? {
            val k = s?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == k } ?: when (k) {
                "pomodoro", "countdown", "stopwatch", "focus", "study_timer" -> TIMER
                "player", "song", "songs", "mini_music" -> MUSIC
                "movie", "clip", "pip" -> VIDEO
                "cards", "deck", "learn" -> FLASHCARDS
                "today", "todo", "checklist", "agenda", "plan_card" -> PLAN
                "text", "card", "notes" -> NOTE
                else -> null
            }
        }
    }
}

enum class StageMode { HERO, WIDGET }

/** Where a widget docks. FLOAT = a free spot the user dragged it to ([StageItem.x]/[StageItem.y] as 0..1 of the stage). */
enum class StageSlot { TOP, SIDE, BOTTOM, FLOAT;
    companion object { fun of(s: String?): StageSlot? = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: when (s?.trim()?.lowercase()) {
        "top_strip", "strip", "header" -> TOP; "rail", "side_rail", "right", "left" -> SIDE; "bottom_strip", "dock", "above_reply" -> BOTTOM
        "free", "floating" -> FLOAT; else -> null } }
}

data class StageItem(
    val id: String,
    val kind: StageKind,
    val props: Map<String, String> = emptyMap(),
    val mode: StageMode = StageMode.WIDGET,
    val slot: StageSlot = StageSlot.TOP,
    /** Order within its slot (lower first) and age (for which widget leaves first). */
    val order: Long = 0,
    val x: Float = 0.7f, val y: Float = 0.2f,
    /** Hero scale 0.4..1 of the free area (the user can pinch / Blaze can resize). */
    val scale: Float = 1f,
    /** Immersive hero: top bar slides off and the reply bar becomes the pill while it is the hero. */
    val immersive: Boolean = false,
)

/** How the chat stream sits on the Stage. LAST = the newest [ChatLayout.count] messages docked; WIDGET = a small chat tile. */
enum class ChatMode { FULL, LAST, WIDGET, HIDDEN;
    companion object { fun of(s: String?): ChatMode? = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: when (s?.trim()?.lowercase()) {
        "all", "normal", "show" -> FULL; "last_n", "few", "collapsed", "docked" -> LAST; "tile", "mini" -> WIDGET; "hide", "none", "off" -> HIDDEN; else -> null } }
}

data class ChatLayout(val mode: ChatMode = ChatMode.FULL, val count: Int = 1, val slot: StageSlot = StageSlot.BOTTOM, val scale: Float = 1f)

enum class ReplyBar { FULL, PILL }

data class StageState(
    val items: List<StageItem> = emptyList(),
    val chat: ChatLayout = ChatLayout(),
    val topBar: Boolean = true,
    val reply: ReplyBar = ReplyBar.FULL,
    /** Chat / chrome as the user (or a layout) set them while a hero is up; restored when the next hero comes. */
    val heroChat: ChatLayout = ChatLayout(ChatMode.LAST, 1),
    val seq: Long = 0,
) {
    val hero: StageItem? get() = items.firstOrNull { it.mode == StageMode.HERO }
    val widgets: List<StageItem> get() = items.filter { it.mode == StageMode.WIDGET }.sortedBy { it.order }
    fun widgetsIn(slot: StageSlot) = widgets.filter { it.slot == slot }
    fun byId(id: String) = items.firstOrNull { it.id == id }
    /** True when the Stage looks exactly like the plain chat (nothing to restore). */
    val isNormal: Boolean get() = items.none { it.mode == StageMode.HERO } && chat.mode == ChatMode.FULL && topBar && reply == ReplyBar.FULL
    val isEmpty: Boolean get() = items.isEmpty() && isNormal
}

sealed class StageAction {
    data class Show(val kind: StageKind, val props: Map<String, String> = emptyMap(), val mode: StageMode = StageMode.HERO,
                    val slot: StageSlot? = null, val id: String? = null) : StageAction()
    data class Update(val id: String, val props: Map<String, String>) : StageAction()
    data class Move(val id: String, val slot: StageSlot, val x: Float? = null, val y: Float? = null) : StageAction()
    data class Close(val id: String) : StageAction()
    /** Tap a widget: it becomes the hero (the old hero shrinks to a widget). */
    data class Expand(val id: String) : StageAction()
    /** The hero shrinks back into a widget. */
    data class Shrink(val id: String) : StageAction()
    data class Resize(val id: String, val scale: Float) : StageAction()
    data class SetChat(val mode: ChatMode, val count: Int? = null, val slot: StageSlot? = null, val scale: Float? = null) : StageAction()
    data class SetChrome(val topBar: Boolean? = null, val reply: ReplyBar? = null) : StageAction()
    data class Layout(val preset: String) : StageAction()
    object Back : StageAction()
    object Reset : StageAction()
    object CloseAll : StageAction()
}

/** What a reducer step did, for tool results ("Timer is on the Stage; Music moved to a widget"). */
data class StageResult(val state: StageState, val id: String? = null, val note: String = "", val handled: Boolean = true, val evicted: List<StageItem> = emptyList())

object StageReducer {
    const val MAX_WIDGETS = 4
    val PRESETS = listOf("normal", "focus", "split", "chat", "widgets_top", "widgets_side", "widgets_bottom", "minimal")

    /** Kinds that only ever have one item (a second music / video show updates the one there). */
    private val single = setOf(StageKind.MUSIC, StageKind.VIDEO)
    /** Kinds that keep running on their own; they leave the widget slots last. */
    private val running = setOf(StageKind.MUSIC, StageKind.TIMER, StageKind.VIDEO)

    fun defaultSlot(kind: StageKind): StageSlot = when (kind) {
        StageKind.MUSIC, StageKind.TIMER -> StageSlot.TOP
        StageKind.VIDEO -> StageSlot.FLOAT
        StageKind.QUIZ, StageKind.FLASHCARDS -> StageSlot.SIDE
        StageKind.PLAN, StageKind.NOTE -> StageSlot.BOTTOM
    }

    private fun newId(kind: StageKind, seq: Long) = kind.id + seq

    fun reduce(s: StageState, a: StageAction): StageResult = when (a) {
        is StageAction.Show -> show(s, a)
        is StageAction.Update -> s.byId(a.id)?.let { it0 ->
            val merged = (it0.props + a.props).filterValues { it.isNotEmpty() }
            StageResult(s.copy(items = s.items.map { if (it.id == a.id) it.copy(props = merged) else it }), a.id, "Updated ${it0.kind.label}.")
        } ?: StageResult(s, null, "Nothing called ${a.id} on the Stage.", handled = false)
        is StageAction.Move -> s.byId(a.id)?.let { it0 ->
            // Moving the hero to a slot makes it a widget there.
            val moved = it0.copy(slot = a.slot, mode = StageMode.WIDGET, x = a.x ?: it0.x, y = a.y ?: it0.y, order = s.seq + 1)
            val st = s.copy(items = s.items.map { if (it.id == a.id) moved else it }, seq = s.seq + 1)
            StageResult(if (it0.mode == StageMode.HERO) leaveHero(st) else st, a.id, "${it0.kind.label} moved to ${a.slot.name.lowercase()}.")
        } ?: StageResult(s, null, "Nothing called ${a.id} on the Stage.", handled = false)
        is StageAction.Close -> s.byId(a.id)?.let { it0 ->
            val st = s.copy(items = s.items.filter { it.id != a.id })
            StageResult(if (it0.mode == StageMode.HERO) leaveHero(st) else st, a.id, "${it0.kind.label} closed.")
        } ?: StageResult(s, null, "Nothing called ${a.id} on the Stage.", handled = false)
        is StageAction.Expand -> s.byId(a.id)?.let { show(s, StageAction.Show(it.kind, it.props, StageMode.HERO, it.slot, it.id)) }
            ?: StageResult(s, null, "Nothing called ${a.id} on the Stage.", handled = false)
        is StageAction.Shrink -> s.byId(a.id)?.takeIf { it.mode == StageMode.HERO }?.let { h ->
            StageResult(leaveHero(s.copy(items = s.items.map { if (it.id == h.id) it.copy(mode = StageMode.WIDGET, order = s.seq + 1) else it }, seq = s.seq + 1)), h.id, "${h.kind.label} is a widget now.")
        } ?: StageResult(s, a.id, "", handled = false)
        is StageAction.Resize -> s.byId(a.id)?.let { _ ->
            StageResult(s.copy(items = s.items.map { if (it.id == a.id) it.copy(scale = a.scale.coerceIn(0.4f, 1f)) else it }), a.id, "Resized.")
        } ?: StageResult(s, null, "Nothing called ${a.id} on the Stage.", handled = false)
        is StageAction.SetChat -> {
            val c = s.chat.copy(mode = a.mode, count = (a.count ?: s.chat.count).coerceIn(1, 20), slot = a.slot ?: s.chat.slot, scale = (a.scale ?: s.chat.scale).coerceIn(0.4f, 1f))
            StageResult(s.copy(chat = c, heroChat = if (s.hero != null) c else s.heroChat), null, "Chat: ${c.mode.name.lowercase()}" + (if (c.mode == ChatMode.LAST) " (${c.count})" else "") + ".")
        }
        is StageAction.SetChrome -> StageResult(s.copy(topBar = a.topBar ?: s.topBar, reply = a.reply ?: s.reply), null,
            "Top bar ${if (a.topBar ?: s.topBar) "shown" else "hidden"}; reply bar ${if ((a.reply ?: s.reply) == ReplyBar.FULL) "full" else "pill"}.")
        is StageAction.Layout -> layout(s, a.preset)
        StageAction.Back -> back(s)
        StageAction.Reset -> StageResult(reset(s), null, "Normal layout.")
        StageAction.CloseAll -> StageResult(StageState(seq = s.seq), null, "Stage cleared.")
    }

    private fun show(s: StageState, a: StageAction.Show): StageResult {
        val seq = s.seq + 1
        val existing = a.id?.let { s.byId(it) } ?: if (a.kind in single) s.items.firstOrNull { it.kind == a.kind } else null
        val id = existing?.id ?: newId(a.kind, seq)
        val slot = a.slot?.takeIf { it != StageSlot.FLOAT || a.mode == StageMode.WIDGET } ?: existing?.slot ?: defaultSlot(a.kind)
        val immersive = a.props["immersive"]?.let { it == "true" } ?: (existing?.immersive ?: false)
        val item = (existing ?: StageItem(id, a.kind)).copy(props = (existing?.props.orEmpty() + a.props - "immersive").filterValues { it.isNotEmpty() },
            mode = a.mode, slot = slot, order = seq, immersive = immersive)
        var items = s.items.filter { it.id != id }
        var note = if (existing == null) "${a.kind.label} is on the Stage" else "${a.kind.label} updated"
        var st = s
        if (a.mode == StageMode.HERO) {
            // The old hero shrinks into a widget (keeps running).
            items = items.map { if (it.mode == StageMode.HERO) { note += "; ${it.kind.label} moved to a widget"; it.copy(mode = StageMode.WIDGET, order = seq) } else it }
            // A hero arriving on the plain chat brings the hero layout (chat docked, chrome per immersive); a hero replacing a hero keeps
            // whatever layout is up now (the user or Blaze may have arranged it).
            if (s.hero == null) st = st.copy(chat = s.heroChat)
            if (immersive) st = st.copy(topBar = false, reply = ReplyBar.PILL)
            note += " (big)"
        }
        items = items + item
        val (kept, evicted) = cap(items)
        if (evicted.isNotEmpty()) note += "; closed " + evicted.joinToString(", ") { it.kind.label } + " (max $MAX_WIDGETS widgets)"
        return StageResult(st.copy(items = kept, seq = seq), id, "$note. id=$id", evicted = evicted)
    }

    /** Over [MAX_WIDGETS] widgets: the oldest idle (not music / timer / video) leaves first, then the oldest of the rest. */
    fun cap(items: List<StageItem>): Pair<List<StageItem>, List<StageItem>> {
        val w = items.filter { it.mode == StageMode.WIDGET }
        if (w.size <= MAX_WIDGETS) return items to emptyList()
        val order = w.sortedWith(compareBy<StageItem>({ it.kind in running }, { it.order }))
        val out = order.take(w.size - MAX_WIDGETS).map { it.id }.toSet()
        return items.filter { it.id !in out } to items.filter { it.id in out }
    }

    /** The hero left: remember the hero-time chat layout, bring the plain chat and chrome back. */
    private fun leaveHero(s: StageState): StageState =
        if (s.hero != null) s else s.copy(heroChat = s.chat.takeIf { it.mode != ChatMode.FULL } ?: s.heroChat, chat = ChatLayout(), topBar = true, reply = ReplyBar.FULL)

    fun reset(s: StageState): StageState = s.copy(
        items = s.items.map { it.copy(mode = StageMode.WIDGET, slot = if (it.slot == StageSlot.FLOAT && it.kind != StageKind.VIDEO) defaultSlot(it.kind) else it.slot, scale = 1f) },
        chat = ChatLayout(), topBar = true, reply = ReplyBar.FULL, heroChat = ChatLayout(ChatMode.LAST, 1))

    /** Back: hero -> widget (chat and chrome return); else chrome / chat back to normal; else not handled (the app's own back). */
    fun back(s: StageState): StageResult {
        s.hero?.let { h -> return reduce(s, StageAction.Shrink(h.id)).let { it.copy(note = "${h.kind.label} is a widget now.") } }
        if (!s.isNormal) return StageResult(s.copy(chat = ChatLayout(), topBar = true, reply = ReplyBar.FULL), null, "Normal layout.")
        return StageResult(s, null, "", handled = false)
    }

    fun layout(s: StageState, preset: String): StageResult {
        val p = preset.trim().lowercase().replace(' ', '_').replace('-', '_')
        val st = when (p) {
            "normal", "reset", "default" -> reset(s)
            "focus", "immersive" -> s.copy(chat = s.chat.copy(mode = ChatMode.HIDDEN), topBar = false, reply = ReplyBar.PILL)
            "split" -> s.copy(chat = s.chat.copy(mode = ChatMode.LAST, count = 3), topBar = true, reply = ReplyBar.FULL)
            "chat" -> s.copy(items = s.items.map { it.copy(mode = StageMode.WIDGET) }, chat = ChatLayout(), topBar = true, reply = ReplyBar.FULL)
            "minimal" -> s.copy(chat = s.chat.copy(mode = ChatMode.WIDGET), topBar = false, reply = ReplyBar.PILL)
            "widgets_top" -> s.copy(items = s.items.map { if (it.mode == StageMode.WIDGET) it.copy(slot = StageSlot.TOP) else it })
            "widgets_side" -> s.copy(items = s.items.map { if (it.mode == StageMode.WIDGET) it.copy(slot = StageSlot.SIDE) else it })
            "widgets_bottom" -> s.copy(items = s.items.map { if (it.mode == StageMode.WIDGET) it.copy(slot = StageSlot.BOTTOM) else it })
            else -> return StageResult(s, null, "Unknown layout \u201c$preset\u201d. Layouts: " + PRESETS.joinToString(", "), handled = false)
        }
        val fixed = if (st.hero != null && st.chat.mode != ChatMode.FULL) st.copy(heroChat = st.chat) else st
        return StageResult(fixed, null, "Layout: $p.")
    }

    /** Drop position (0..1 of the stage) -> slot, for the user dragging a widget. */
    fun slotForDrop(x: Float, y: Float): StageSlot = when {
        y < 0.22f -> StageSlot.TOP
        y > 0.72f -> StageSlot.BOTTOM
        x > 0.78f -> StageSlot.SIDE
        else -> StageSlot.FLOAT
    }

    /** Drag on the docked chat's handle: up shows more messages, down fewer; past the bottom it becomes the chat tile. */
    fun chatCountForDrag(count: Int, dyDp: Float, rowDp: Float = 56f): ChatLayout {
        val n = count - Math.round(dyDp / rowDp)
        return when {
            n <= 0 -> ChatLayout(ChatMode.WIDGET, 1)
            n >= 12 -> ChatLayout(ChatMode.FULL, 12)
            else -> ChatLayout(ChatMode.LAST, n)
        }
    }
}
