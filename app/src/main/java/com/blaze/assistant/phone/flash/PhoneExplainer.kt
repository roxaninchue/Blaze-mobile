package com.blaze.assistant.phone.flash

/**
 * 0.46.3: the one-time "Let Blaze use your phone" explainer. Phones have no ADB here, so on-phone control runs only
 * through Blaze's accessibility service, which the user switches on once in Android Settings. The first time a phone task
 * needs it (or the user taps the banner), the explainer says what it does and what it never does, with one button
 * straight to Blaze's own switch. It is shown once; after that the plain "Phone control is off · Turn on" banner is enough.
 * The rule is pure ([shouldShow]); the seen flag lives in Settings (PhoneExplainerTest).
 */
object PhoneExplainer {
    const val SEEN_KEY = "a11y_explainer_seen"

    const val TITLE = "Let Blaze use your phone"
    val POINTS = listOf(
        "Blaze reads what's on screen and taps, types and scrolls for you, only when you ask it to do something.",
        "It uses Android's accessibility switch. No cable, computer or ADB needed.",
        "Banking apps on your bank list are never read or touched, and Blaze asks before anything risky.",
        "You can switch it off any time in Settings \u2192 Accessibility.",
    )
    const val STEPS = "Tap Open Settings, choose Blaze, and switch it on."
    const val OPEN = "Open Settings"
    const val LATER = "Not now"

    /** Show it when phone control is off and it hasn't been shown before. */
    fun shouldShow(seen: Boolean, connected: Boolean): Boolean = !seen && !connected

    /** Asked to show (a phone task needed control). The UI observes this. */
    val requested = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Called when control is needed: raises the explainer once, never again after it was seen. Returns true if raised. */
    fun request(seen: Boolean, connected: Boolean): Boolean {
        if (!shouldShow(seen, connected)) return false
        requested.value = true
        return true
    }

    /** The UI showed it: never again. */
    fun markShown(save: (String, Boolean) -> Unit) {
        save(SEEN_KEY, true)
        requested.value = false
    }
}
