package com.blaze.assistant.composer

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role

/**
 * 0.47 chat over Home (pure; ChatOverlayLogicTest). Which messages the overlay shows, when Blaze's typing dots show,
 * how fast a fresh reply types in, and the centred timestamp.
 */
object ChatOverlayLogic {
    /** Messages shown when the chat is opened without sending (a little context). */
    const val CONTEXT = 4

    /** The overlay's start: the id before the last [CONTEXT] user / Blaze messages (0 = from the start). */
    fun since(all: List<ChatMessage>): Long {
        val talk = all.filter { it.role == Role.USER || it.role == Role.BLAZE }
        return if (talk.size <= CONTEXT) 0L else talk[talk.size - CONTEXT - 1].id
    }

    /** User and Blaze lines (and errors) after [sinceId]; tool steps stay in the full chat. */
    fun visible(all: List<ChatMessage>, sinceId: Long): List<ChatMessage> =
        all.filter { it.id > sinceId && (it.role == Role.USER || it.role == Role.BLAZE || it.role == Role.ERROR) && (it.text.isNotBlank() || it.files.isNotEmpty() || it.media.isNotEmpty()) }

    /** Typing dots: Blaze is working and hasn't started its answer to the newest user line. */
    fun typing(shown: List<ChatMessage>, busy: Boolean): Boolean = busy && shown.lastOrNull()?.role == Role.USER

    /** Characters a fresh reply reveals per ~16 ms frame: steady for short replies, never slower than ~1.5 s overall. */
    fun step(remaining: Int): Int = maxOf(2, remaining / 90)

    /** "Today 09:41" / "Mon 09:41" / "12 Oct 09:41" for the centred timestamp. */
    fun stamp(at: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (at <= 0L) return ""
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = at }
        val n = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
        val hm = String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        val sameYear = c.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
        val dayDiff = n.get(java.util.Calendar.DAY_OF_YEAR) - c.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today $hm"
            sameYear && dayDiff == 1 -> "Yesterday $hm"
            sameYear && dayDiff in 2..6 -> java.text.SimpleDateFormat("EEE", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(at)) + " " + hm
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(at)) + " " + hm
        }
    }

    /** The user's avatar letter (their name, else "Y" for You). */
    fun initial(name: String?): String = name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "Y"
}
