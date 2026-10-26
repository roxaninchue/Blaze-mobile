package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19 chat fix 1 (user rules, phone screenshots 09:20-10:10): no placeholder ack, questions are answered not acked,
 * an ack is one specific line or nothing, a fast task gets only its result, no "ack" label, no "Done ✓" glued to an
 * answer or a "no", Halo's handoff line never shows. Strict 5/5: every scenario runs five times in a row.
 */
class ChatAck19Test {

    private fun fiveTimes(body: (Int) -> Unit) = repeat(5) { body(it) }

    private val placeholder = "Got it, I'm on it. I'll message you here when it's done."

    @Test fun neverThePlaceholder() = fiveTimes {
        assertTrue(Ack.isGeneric(placeholder))
    }

    @Test fun questionsAreAnsweredNotAcked() = fiveTimes {
        for (q in listOf("did you already book?", "what is Blaze phone doing", "is the alarm set?", "how's the booking going?",
            "Did you send it", "where are you on the booking?", "has it opened yet", "why did it stop?", "booking done?")) {
            assertTrue(q, Ack.isQuestion(q))
            assertFalse(q, Ack.wants(q))
        }
        // A request phrased politely as a question is still a task.
        assertTrue(Ack.wants("can you turn on dark mode?"))
        assertTrue(Ack.wants("Could you please turn off bluetooth?"))
    }

    @Test fun fastTasksGetOnlyTheirResult() = fiveTimes { r ->
        val start = 1_000_000L * (r + 1)
        assertEquals(2_000L, Ack.FAST_MS)
        assertEquals(Ack.FAST_MS, Ack.holdMs(start, start))
        assertEquals(1_500L, Ack.holdMs(start, start + 500))
        assertEquals(0L, Ack.holdMs(start, start + 2_000))
        assertEquals(0L, Ack.holdMs(start, start + 60_000))
        // The answer came first (a 300 ms toggle): the turn is never written on the ledger.
        val g = Ack.Gate(at = start)
        g.finish()
        assertFalse(g.track())
        assertFalse(g.tracked)
    }

    @Test fun noAckLabelAndNoStatusChipInTheActionRow() = fiveTimes {
        assertEquals("", ReplyChips.visible(Ack.META, false))
        assertEquals("", ReplyChips.visible(Ack.META, true))
        assertEquals("", ReplyChips.visible("\u26a0 Couldn't confirm: couldn't read the switch back", false))
        assertEquals("", ReplyChips.visible("\u2713 Phone status", false))
        assertEquals("", ReplyChips.visible("\u2717 Phone act didn't work \u00b7 \u2713 Phone open \u00b7 \u2713 Phone read \u00b7 +1 more", false))
        assertEquals("Memory updated", ReplyChips.visible("\u2713 Phone act \u00b7 Memory updated", false))
        assertEquals("Heads-up \u00b7 14:02", ReplyChips.visible("Heads-up \u00b7 14:02", false))
    }

    @Test fun halosHandoffLineIsNeverShown() = fiveTimes {
        for (h in listOf("Got it \u2014 passing that straight to Blaze.", "Passed to Blaze \u2014 let me know if you need anything else while that runs.",
            "Handing that over to Blaze.", "On it!", "Halo here, I'll handle it."))
            assertTrue(h, Ack.isGeneric(h))
        assertTrue(Duo.isRelay("[[relay]]"))
        assertTrue(Duo.sideSystem(null, "book a room", "").contains("reply with only the tag [[relay]]"))
        assertFalse(Duo.sideSystem(null, "book a room", "").contains("say in one short warm line that you've passed it to Blaze"))
        // A real answer from Halo is not a handoff.
        assertFalse(Ack.isGeneric("The room is \u00a3100 a night with check-in on 31 October."))
    }
}
