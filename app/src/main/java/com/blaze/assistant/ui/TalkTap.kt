package com.blaze.assistant.ui

/**
 * v0.45.28, pure (TalkTapTest): what a press on the big mic does. Not listening: start; a long hold sends when you let
 * go, a quick tap keeps listening until you stop talking. Already listening: a tap (or a press of any length) stops
 * listening and sends what was heard, so you never have to wait for the phone to notice you stopped.
 */
object TalkTap {
    /** A hold longer than this sends on release. */
    const val HOLD_MS = 600L

    enum class Act { START, STOP }

    /** On touch down. */
    fun onPress(listening: Boolean): Act = if (listening) Act.STOP else Act.START

    /** On release: stop listening and send? [act] is what the press did, [heldMs] how long the finger stayed down. */
    fun stopOnRelease(act: Act, heldMs: Long, released: Boolean): Boolean = when (act) {
        Act.STOP -> true
        Act.START -> released && heldMs > HOLD_MS
    }

    /** The line under the mic. */
    fun label(busy: Boolean, listening: Boolean): String = when {
        busy -> "Tap to stop"
        listening -> "Listening\u2026 tap or let go to send"
        else -> "Hold to talk"
    }
}
