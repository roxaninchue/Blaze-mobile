package com.blaze.assistant.stage

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.0 the Stage tools Blaze calls: stage_show / stage_update / stage_move / stage_close / stage_layout. This file is the pure
 * half (definitions, parsing tool args into [StageAction]s, the prompt rule, the state codec); StageStore runs them on the phone.
 * Unit-tested in StageToolsTest.
 */
object StageTools {
    const val SHOW = "stage_show"; const val UPDATE = "stage_update"; const val MOVE = "stage_move"; const val CLOSE = "stage_close"; const val LAYOUT = "stage_layout"
    val NAMES = setOf(SHOW, UPDATE, MOVE, CLOSE, LAYOUT)

    /**
     * The prompt rule (stable part). Short on purpose: Stage first, views only when asked or when the Stage can't host it,
     * plain Q&A stays chat, and a study / focus time is a timer card with Start (not a reminder sentence).
     */
    const val RULE = "\nSTAGE: the chat screen is a Stage you can arrange in place (never a jump to another screen). Use stage_show only when a live thing truly helps: " +
        "a timer or pomodoro (\"study 30 min\" -> stage_show kind=timer minutes=30 title=Study: a timer card with Start, not a reminder or a sentence), music playing, a video, " +
        "a quiz or flashcards to go through, a plan / checklist card, the browser (\"pull up the browser\" / \"browser card\" -> stage_show kind=browser url=optional; " +
        "the browser_ tools then drive the page shown in that card; open_view view=browser only when they ask for full screen / full view). Plain questions, chat and explanations stay normal chat replies. " +
        "Stage first: do it on the Stage before opening a /views screen; open_view only when the user asks for that view or the Stage can't host it " +
        "(every Stage card has its own \"Open in full view\"). A new hero turns the old one into a small widget; both keep running. " +
        "Rearrange with stage_move (any element: an item id, chat, top_bar, reply_bar) and stage_layout (normal = restore). After stage_show reply in one short line."

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun en(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    private fun contentProps(): JSONObject = JSONObject()
        .put("title", str("short title (e.g. Study, Lo-fi, Biology quiz)"))
        .put("minutes", JSONObject().put("type", "number").put("description", "timer: length in minutes (pomodoro: focus length)"))
        .put("pomodoro", JSONObject().put("type", "boolean")).put("break_minutes", JSONObject().put("type", "number")).put("cycles", JSONObject().put("type", "integer"))
        .put("autostart", JSONObject().put("type", "boolean").put("description", "timer: start now (only when the user said start / go); default shows Start"))
        .put("query", str("music: what to play (song, artist, mix name, or empty to resume / Blaze DJ)"))
        .put("url", str("video: a video file path or direct link (YouTube opens in Blaze Video); browser: the page or search words to open (empty = the current tab)"))
        .put("deck", str("flashcards: Learn deck name (add cards first with flashcards action=add)"))
        .put("questions", JSONObject().put("type", "array").put("description", "quiz: [{q, options:[..], answer: index or text, hint (optional)}]").put("items", JSONObject().put("type", "object")))
        .put("items", JSONObject().put("type", "array").put("description", "plan: checklist lines").put("items", JSONObject().put("type", "string")))
        .put("text", str("note: the text"))
        .put("immersive", JSONObject().put("type", "boolean").put("description", "hero fills the screen (top bar off, reply bar as a pill)"))

    fun definitions(): List<JSONObject> = listOf(
        fn(SHOW, "Put a live card on the chat Stage, in place: a big hero (default) or a small widget. kinds: timer (pomodoro), music, video, quiz, flashcards, plan, note, browser. Returns the id.",
            contentProps().put("kind", en(*StageKind.entries.map { it.id }.toTypedArray())).put("mode", en("hero", "widget")).put("slot", en("top", "side", "bottom", "float")), listOf("kind")),
        fn(UPDATE, "Change a Stage card: new content, or timer control (start | pause | reset | add with minutes).",
            contentProps().put("id", str("the card id from stage_show")).put("control", en("start", "pause", "reset", "add")), listOf("id")),
        fn(MOVE, "Move, resize or hide any Stage element. target = a card id, or chat / top_bar / reply_bar. Cards: slot top|side|bottom|float|hero (hero = make it big). " +
            "chat: mode full|last|widget|hidden (+count for last). top_bar: visible. reply_bar: mode full|pill (never gone).",
            JSONObject().put("target", str("card id, chat, top_bar or reply_bar")).put("slot", en("top", "side", "bottom", "float", "hero"))
                .put("mode", str("chat: full|last|widget|hidden; reply_bar: full|pill")).put("count", JSONObject().put("type", "integer"))
                .put("visible", JSONObject().put("type", "boolean")).put("scale", JSONObject().put("type", "number").put("description", "0.4-1")), listOf("target")),
        fn(CLOSE, "Close a Stage card (id), or all.", JSONObject().put("id", str("card id or all")), listOf("id")),
        fn(LAYOUT, "Arrange the whole Stage: " + StageReducer.PRESETS.joinToString(" | ") + " (normal = the plain chat back).",
            JSONObject().put("preset", en(*StageReducer.PRESETS.toTypedArray())), listOf("preset")),
    )

    fun step(name: String, a: JSONObject): String = when (name) {
        SHOW -> "Setting up " + (StageKind.of(a.optString("kind"))?.label?.lowercase() ?: "the Stage")
        UPDATE -> "Updating the Stage"; MOVE -> "Arranging the Stage"; CLOSE -> "Closing"; else -> "Arranging the Stage"
    }

    /** Tool args -> props (flat strings; arrays as JSON). */
    fun propsFrom(a: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in listOf("title", "query", "url", "deck", "text", "sound")) a.optString(k).trim().takeIf { it.isNotEmpty() }?.let { out[k] = it.take(4000) }
        if (a.has("minutes")) StageTimer.parseMinutes(a.opt("minutes")?.toString())?.let { out["minutes"] = trimNum(it) }
        if (a.has("break_minutes")) StageTimer.parseMinutes(a.opt("break_minutes")?.toString())?.let { out["break_minutes"] = trimNum(it) }
        if (a.has("cycles")) out["cycles"] = a.optInt("cycles", 4).coerceIn(1, 8).toString()
        if (a.optBoolean("pomodoro")) out["pomodoro"] = "true"
        if (a.optBoolean("autostart")) out["autostart"] = "true"
        if (a.has("immersive")) out["immersive"] = a.optBoolean("immersive").toString()
        a.optJSONArray("questions")?.let { q -> quiz(q).takeIf { it.isNotEmpty() }?.let { out["questions"] = JSONArray(it.map { x -> x.toJson() }).toString() } }
        a.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.opt(it)?.let { x -> if (x is JSONObject) x.optString("text", x.optString("title")) else x.toString() }.orEmpty().trim() }
            .filter { it.isNotEmpty() }.take(30).takeIf { it.isNotEmpty() }?.let { out["items"] = JSONArray(it).toString() } }
        return out
    }

    private fun trimNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')

    data class Question(val q: String, val options: List<String>, val answer: Int, val hint: String = "") {
        fun toJson(): JSONObject = JSONObject().put("q", q).put("options", JSONArray(options)).put("answer", answer).also { if (hint.isNotBlank()) it.put("hint", hint) }
    }

    /** Quiz questions in the shapes models write: {q|question, options|choices, answer: index | letter | text}. Bad ones dropped. */
    fun quiz(arr: JSONArray): List<Question> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val q = o.optString("q").ifBlank { o.optString("question") }.trim()
        val opts = (o.optJSONArray("options") ?: o.optJSONArray("choices"))?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }?.filter { it.isNotEmpty() }.orEmpty()
        if (q.isEmpty() || opts.size < 2) return@mapNotNull null
        val ans = o.opt("answer") ?: o.opt("correct")
        val idx = when (ans) {
            is Number -> ans.toInt().let { if (it in opts.indices) it else if (it == opts.size) it - 1 else -1 }
            is String -> ans.trim().let { s ->
                s.toIntOrNull()?.takeIf { it in opts.indices }
                    ?: (if (s.length == 1 && s[0].lowercaseChar() in 'a'..'h') s[0].lowercaseChar() - 'a' else null)?.takeIf { it in opts.indices }
                    ?: opts.indexOfFirst { it.equals(s, true) }
            }
            else -> -1
        }
        if (idx !in opts.indices) null else Question(q.take(400), opts.take(6).map { it.take(200) }, idx, o.optString("hint").trim().take(200))
    }.take(30)

    fun questions(json: String?): List<Question> = try { quiz(JSONArray(json ?: "[]")) } catch (_: Exception) { emptyList() }
    fun items(json: String?): List<String> = try { JSONArray(json ?: "[]").let { a -> (0 until a.length()).map { a.optString(it) } } } catch (_: Exception) { emptyList() }

    /** stage_show / stage_update / stage_move / stage_close / stage_layout args -> the reducer action (null + why when invalid). */
    fun parse(name: String, a: JSONObject): Pair<StageAction?, String> = when (name) {
        SHOW -> {
            val kind = StageKind.of(a.optString("kind"))
            when {
                kind == null -> null to "Unknown kind \u201c${a.optString("kind")}\u201d. Kinds: " + StageKind.entries.joinToString(", ") { it.id }
                else -> {
                    val p = propsFrom(a).toMutableMap()
                    if (StageKind.of(a.optString("kind")) == StageKind.TIMER && a.optString("kind").trim().lowercase() == "pomodoro") p["pomodoro"] = "true"
                    val err = when (kind) {
                        StageKind.TIMER -> if (p["minutes"] == null && p["pomodoro"] == null) "A timer needs minutes." else null
                        StageKind.QUIZ -> if (p["questions"] == null) "A quiz needs questions [{q, options, answer}] (at least 2 options each)." else null
                        StageKind.FLASHCARDS -> if (p["deck"] == null) "Flashcards need a deck (make one with flashcards action=add)." else null
                        StageKind.PLAN -> if (p["items"] == null && p["text"] == null) "A plan needs items." else null
                        StageKind.NOTE -> if (p["text"] == null) "A note needs text." else null
                        else -> null
                    }
                    if (err != null) null to err
                    else StageAction.Show(kind, p, if (a.optString("mode").equals("widget", true)) StageMode.WIDGET else StageMode.HERO, StageSlot.of(a.optString("slot"))) to ""
                }
            }
        }
        UPDATE -> a.optString("id").trim().let { id -> if (id.isEmpty()) null to "Give the card id." else StageAction.Update(id, propsFrom(a)) to "" }
        MOVE -> move(a)
        CLOSE -> a.optString("id").trim().let { id -> when { id.isEmpty() -> null to "Give the card id (or all)."; id.equals("all", true) -> StageAction.CloseAll to ""; else -> StageAction.Close(id) to "" } }
        LAYOUT -> a.optString("preset").trim().let { p -> if (p.isEmpty()) null to "Give a preset: " + StageReducer.PRESETS.joinToString(", ") else StageAction.Layout(p) to "" }
        else -> null to "Unknown Stage tool."
    }

    private fun move(a: JSONObject): Pair<StageAction?, String> {
        val t = a.optString("target").trim().lowercase().replace(' ', '_').replace('-', '_')
        return when (t) {
            "" -> null to "Give a target: a card id, chat, top_bar or reply_bar."
            "chat", "chat_stream", "messages" -> {
                val mode = ChatMode.of(a.optString("mode")) ?: if (a.has("visible")) (if (a.optBoolean("visible")) ChatMode.FULL else ChatMode.HIDDEN) else if (a.has("count")) ChatMode.LAST else null
                if (mode == null) null to "chat mode: full | last | widget | hidden"
                else StageAction.SetChat(mode, if (a.has("count")) a.optInt("count") else null, StageSlot.of(a.optString("slot")),
                    if (a.has("scale")) a.optDouble("scale").toFloat() else null) to ""
            }
            "top_bar", "topbar", "header", "chrome" -> StageAction.SetChrome(topBar = if (a.has("visible")) a.optBoolean("visible") else !a.optString("mode").equals("hidden", true)) to ""
            "reply_bar", "reply", "composer", "input" -> {
                val m = a.optString("mode").lowercase()
                val pill = m == "pill" || m == "hidden" || m == "mini" || (a.has("visible") && !a.optBoolean("visible"))
                StageAction.SetChrome(reply = if (pill) ReplyBar.PILL else ReplyBar.FULL) to ""
            }
            else -> {
                val id = a.optString("target").trim()
                val slotS = a.optString("slot").trim().lowercase()
                when {
                    slotS == "hero" || slotS == "big" -> StageAction.Expand(id) to ""
                    StageSlot.of(slotS) != null -> StageAction.Move(id, StageSlot.of(slotS)!!) to ""
                    a.has("scale") -> StageAction.Resize(id, a.optDouble("scale").toFloat()) to ""
                    a.has("visible") && !a.optBoolean("visible") -> StageAction.Close(id) to ""
                    else -> null to "Give slot (top | side | bottom | float | hero) or scale."
                }
            }
        }
    }

    // ---------------- codec (the Stage survives the app closing) ----------------

    /** Saved-layout schema version (bump when the shape changes; an unknown version restores the default layout). */
    const val SCHEMA = 1

    fun encode(s: StageState, aspect: Float = 0f): String = JSONObject().put("v", SCHEMA).put("aspect", aspect.toDouble())
        .put("items", JSONArray(s.items.map { i -> JSONObject().put("id", i.id).put("kind", i.kind.id).put("props", JSONObject(i.props as Map<*, *>))
            .put("mode", i.mode.name).put("slot", i.slot.name).put("order", i.order).put("x", i.x.toDouble()).put("y", i.y.toDouble()).put("scale", i.scale.toDouble()).put("imm", i.immersive).put("z", i.z) }))
        .put("chat", chatJson(s.chat)).put("heroChat", chatJson(s.heroChat)).put("top", s.topBar).put("reply", s.reply.name).put("seq", s.seq).toString()

    private fun chatJson(c: ChatLayout) = JSONObject().put("mode", c.mode.name).put("count", c.count).put("slot", c.slot.name).put("scale", c.scale.toDouble())
    private fun chatFrom(o: JSONObject?, d: ChatLayout) = if (o == null) d else ChatLayout(ChatMode.of(o.optString("mode")) ?: d.mode, o.optInt("count", d.count).coerceIn(1, 20),
        StageSlot.of(o.optString("slot")) ?: d.slot, o.optDouble("scale", d.scale.toDouble()).toFloat().let { if (it.isNaN()) 1f else it }.coerceIn(0.4f, 1f))

    /**
     * The saved layout, made safe for this screen: an unknown schema or broken JSON is the default layout; positions and sizes
     * are clamped on screen; when the screen turned (portrait <-> landscape) or changed shape a lot since it was saved
     * ([aspectNow] = width / height, 0 = unknown), widgets dropped at free spots go back to their docks.
     */
    fun decode(json: String?, aspectNow: Float = 0f): StageState = try {
        val o = JSONObject(json ?: "")
        if (o.optInt("v", SCHEMA) != SCHEMA) throw IllegalStateException("schema")
        val saved = o.optDouble("aspect", 0.0).toFloat()
        val turned = saved > 0f && aspectNow > 0f && ((saved < 1f) != (aspectNow < 1f) || maxOf(saved, aspectNow) / minOf(saved, aspectNow) > 1.5f)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k)?.let { i ->
            val kind = StageKind.of(i.optString("kind")) ?: return@let null
            val pr = i.optJSONObject("props"); val props = LinkedHashMap<String, String>()
            pr?.keys()?.forEach { key -> props[key] = pr.optString(key) }
            val id = i.optString("id").takeIf { it.isNotBlank() } ?: return@let null
            var slot = StageSlot.of(i.optString("slot")) ?: StageReducer.defaultSlot(kind)
            if (turned && slot == StageSlot.FLOAT && kind != StageKind.VIDEO) slot = StageReducer.defaultSlot(kind)
            fun f(k: String, d: Double, lo: Float, hi: Float) = i.optDouble(k, d).toFloat().let { if (it.isNaN()) d.toFloat() else it }.coerceIn(lo, hi)
            StageItem(id, kind, props, if (i.optString("mode") == "HERO") StageMode.HERO else StageMode.WIDGET, slot,
                i.optLong("order"), f("x", 0.7, 0f, 0.85f), f("y", 0.2, 0f, 0.9f), f("scale", 1.0, 0.4f, 1f), i.optBoolean("imm"), i.optLong("z"))
        } }.distinctBy { it.id }.let { l -> val keep = StageReducer.cap(l.filter { it.mode == StageMode.WIDGET }).first.map { it.id }.toSet()
            l.filter { it.mode == StageMode.HERO || it.id in keep } }
        // Never two heroes, and the reply bar is never gone (PILL at worst).
        val firstHero = items.firstOrNull { it.mode == StageMode.HERO }?.id
        StageState(items.map { if (it.mode == StageMode.HERO && it.id != firstHero) it.copy(mode = StageMode.WIDGET) else it },
            chatFrom(o.optJSONObject("chat"), ChatLayout()), o.optBoolean("top", true), if (o.optString("reply") == "PILL") ReplyBar.PILL else ReplyBar.FULL,
            chatFrom(o.optJSONObject("heroChat"), ChatLayout(ChatMode.LAST, 1)), o.optLong("seq"))
    } catch (_: Exception) { StageState() }
}
