package com.blaze.assistant.composer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.47 chat composer: the model family the user picked on the composer's model chip (or in Spotlight).
 * Auto (default) / Spark (fast, < 6 s) / Flare (balanced, 6–12 s) / Inferno (smartest, > 12 s).
 *
 * The choice never changes the brain ORDER (on-phone brain → Nous → Blaze cloud gateway; never Jev, System One or
 * NVIDIA models). Routing only READS it: [wire] is sent to the gateway as the `blaze_family` hint (every blaze_* field is
 * stripped by the gateway before a provider sees it), Auto sends nothing. Persisted in Settings ("composer_model").
 */
object ModelChoice {
    enum class Family(val id: String, val label: String, val sub: String) {
        AUTO("auto", "Auto", "Picks for you"),
        SPARK("spark", "Spark", "Fast"),
        FLARE("flare", "Flare", "Balanced"),
        INFERNO("inferno", "Inferno", "Smartest");

        /** "Spark (Fast)", the picker row. */
        val pickerLabel: String get() = if (this == AUTO) label else "$label ($sub)"
        /** "Blaze Inferno", the Spotlight / self-map name. */
        val blazeName: String get() = "Blaze $label"
    }

    const val PREF = "composer_model"

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Family> = _current

    fun of(id: String?): Family = Family.values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: Family.AUTO

    private fun load(): Family = of(runCatching { com.blaze.assistant.agent.Settings.str(PREF, "auto") }.getOrDefault("auto"))

    fun set(f: Family) {
        _current.value = f
        runCatching { com.blaze.assistant.agent.Settings.setStr(PREF, f.id) }
    }

    /** The gateway hint for [f]: null for Auto (the gateway picks as before). */
    fun wire(f: Family = _current.value): String? = if (f == Family.AUTO) null else f.id

    /** Chip text: "Auto", "Spark", … */
    fun chip(f: Family = _current.value): String = f.label
}
