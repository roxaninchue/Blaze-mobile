package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.24: quick replies never mirror the user's message, and near-identical chips collapse to one. */
class QuickRepliesTest {
    private val ask = "plan a trip to Livingstone this weekend"

    @Test fun echoOfTheAskIsDropped() {
        assertTrue(QuickReplies.echoes("Plan a trip to Livingstone", ask))
        assertTrue(QuickReplies.echoes("Livingstone trip", ask))
        assertTrue(QuickReplies.echoes("Plan my Livingstone trips this weekend", ask))
    }

    @Test fun realNextStepsStay() {
        assertFalse(QuickReplies.echoes("Price a lodge", ask))
        assertFalse(QuickReplies.echoes("Yes, book it", ask))
        assertFalse(QuickReplies.echoes("Saturday morning", ask))
        assertEquals(listOf("Price a lodge", "Yes, book it"),
            QuickReplies.filter(listOf("Plan a trip to Livingstone", "Price a lodge", "Yes, book it"), ask))
    }

    @Test fun twinsCollapse() {
        assertEquals(listOf("Book the lodge"), QuickReplies.filter(listOf("Book the lodge", "book the lodge!", "Book lodges"), "what's there to do"))
    }

    @Test fun allEchoesMeansNoChips() {
        assertTrue(QuickReplies.filter(listOf("Plan a trip to Livingstone", "Livingstone trip"), ask).isEmpty())
    }

    @Test fun appChipsAlwaysStay() {
        assertEquals(listOf(NudgePolicy.TRY_AGAIN), QuickReplies.filter(listOf(NudgePolicy.TRY_AGAIN), "try again"))
    }

    @Test fun jaccardBasics() {
        assertEquals(1.0, QuickReplies.jaccard(setOf("a"), setOf("a")), 0.0)
        assertEquals(0.5, QuickReplies.jaccard(setOf("a", "b"), setOf("a")), 1e-9)
        assertEquals(setOf("lodge", "book"), QuickReplies.tokens("Book the lodges!"))
    }
}
