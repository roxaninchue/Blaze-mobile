package com.blaze.assistant.agent.orch

/**
 * 0.47.0 one Blaze (spec §1.3, §7): the one-time migration from the Blaze / Halo split. Pure over a small key/value view
 * of the prefs ([Prefs]) so it is tested without Android (HaloMergeTest). Idempotent (flag [FLAG]); nothing is deleted.
 *  - the talk-arc pick ("halo" / "all") becomes Blaze;
 *  - Halo Studio's voice notes (language mix, boundaries) are kept as Blaze voice notes;
 *  - the personality: only a setting that was NEVER written (no "persona" key at all) is the untouched default and
 *    becomes Butler. A written value ("blaze" included) can't be told apart from a deliberate pick (older builds stored
 *    no "picked" flag, and sync restores the key), so it is kept and [BUTLER_CARD] is set: the chat shows one card,
 *    "New: Butler personality. Try it?". Nothing is silently overridden;
 *  - duo_team (Halo x Blaze team phases) is off: multi-part jobs go to the orchestrator.
 */
object HaloMerge {
    const val FLAG = "halo_merged_470"
    /** Pending one-time "New: Butler personality. Try it?" card (set by [migrate], cleared by [ButlerIntro]). */
    const val BUTLER_CARD = "butler_card_470"

    interface Prefs {
        fun str(k: String): String?
        fun put(k: String, v: String)
        fun bool(k: String): Boolean?
        fun putBool(k: String, v: Boolean)
    }

    data class Result(val ran: Boolean, val changed: List<String>)

    fun migrate(p: Prefs): Result {
        if (p.bool(FLAG) == true) return Result(false, emptyList())
        val changed = ArrayList<String>()
        for (k in listOf("talk_to", "duo_pick", "arc_pick", "talk_pick")) {
            val v = p.str(k) ?: continue
            if (v != "blaze") { p.put(k, "blaze"); changed += k }
        }
        val notes = listOf("halo_language", "halo_boundaries", "halo_style").mapNotNull { k -> p.str(k)?.takeIf { it.isNotBlank() }?.let { "$k: $it" } }
        if (notes.isNotEmpty() && p.str("blaze_voice_notes").isNullOrBlank()) { p.put("blaze_voice_notes", notes.joinToString("\n").take(1200)); changed += "blaze_voice_notes" }
        val persona = p.str("persona")
        if (persona == null) { p.put("persona", Butler.ID); changed += "persona" }      // truly untouched: never written
        else if (persona != Butler.ID) { p.putBool(BUTLER_CARD, true); changed += BUTLER_CARD }   // keep theirs, offer once
        if (p.bool("duo_team") != false) { p.putBool("duo_team", false); changed += "duo_team" }
        p.putBool(FLAG, true)
        return Result(true, changed)
    }

    /** Who a stored message is shown as: always Blaze now (old Halo replies lose the coloured name). */
    fun shownSpeaker(stored: String?): String = ""

    /** History tags "[Halo] …" in old transcripts read as Blaze's own lines. */
    fun untagHistory(line: String): String = line.replace(Regex("""^\s*\[(?:Halo|Blaze)]\s*"""), "")
}

/**
 * 0.47.0 the one-time Butler card for people who kept their own personality (see [HaloMerge.migrate]). Pure over the
 * same [HaloMerge.Prefs] view. "Try it" switches to Butler (an explicit pick); "No thanks" keeps theirs; either way the
 * card never comes back.
 */
object ButlerIntro {
    const val TEXT = "New: Butler personality. Try it?"
    const val TRY = "Try it"
    const val NO = "No thanks"

    fun shouldShow(p: HaloMerge.Prefs): Boolean = p.bool(HaloMerge.BUTLER_CARD) == true && p.str("persona") != Butler.ID

    /** The user's answer: true = try Butler. Returns the persona now in use. */
    fun answer(p: HaloMerge.Prefs, tryIt: Boolean): String? {
        p.putBool(HaloMerge.BUTLER_CARD, false)
        if (tryIt) { p.put("persona", Butler.ID); p.putBool("persona_picked", true) }
        return p.str("persona")
    }
}
