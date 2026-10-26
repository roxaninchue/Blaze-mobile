package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleHomeTest {
    @Test fun greetingByHour() {
        assertEquals("Good morning, Sam", SimpleHome.greeting(8, "Sam Lee"))
        assertEquals("Good afternoon", SimpleHome.greeting(14))
        assertEquals("Good evening, Maya", SimpleHome.greeting(19, "Maya"))
        assertEquals("Still up, Maya?", SimpleHome.greeting(23, "Maya"))
        assertEquals("Still up?", SimpleHome.greeting(2))
    }

    @Test fun twoStartersThatFitTheHour() {
        listOf(3, 8, 14, 19, 23).forEach { assertEquals(2, SimpleHome.starters(it).size) }
        assertEquals("Plan my day", SimpleHome.starters(9)[0].label)
        assertEquals("Plan tomorrow", SimpleHome.starters(19)[0].label)
        assertEquals("Wind down", SimpleHome.starters(23)[0].label)
    }

    @Test fun replyChipsOnYesNoQuestions() {
        val c = SimpleHome.replyChips("Sam paid you back \$40 for dinner. Want to say thanks?").map { it.label }
        assertEquals(listOf("Yes", "No", "Later", "Do it for me"), c)
        assertEquals(listOf("Yes", "No", "Later"), SimpleHome.replyChips("All set. Are you happy with that?").map { it.label })
    }

    @Test fun noChipsForOpenQuestionsOrStatements() {
        assertTrue(SimpleHome.replyChips("What time works for you?").isEmpty())
        assertTrue(SimpleHome.replyChips("Done! It's in your calendar.").isEmpty())
        assertTrue(SimpleHome.replyChips("Coffee or tea?").isEmpty())
    }

    @Test fun lastQuestion() {
        assertEquals("Say thank you on WhatsApp?", SimpleHome.lastQuestion("Mutale sent you money. Say thank you on WhatsApp?"))
        assertEquals("", SimpleHome.lastQuestion("No question here."))
    }

    @Test fun talkToTags() {
        assertEquals("@halo hi there", SimpleHome.tagged("hi there", "halo"))
        // v0.45.28: "all" is no longer a pick; an old saved "all" counts as Blaze.
        assertEquals("plan a trip", SimpleHome.tagged("plan a trip", "all"))
        assertEquals("hi", SimpleHome.tagged("hi", "blaze"))
        assertEquals("/music", SimpleHome.tagged("/music", "halo"))
        assertEquals("hey @blaze do it", SimpleHome.tagged("hey @blaze do it", "halo"))
    }

    @Test fun haloXBlazeIsAutomatic() {
        assertEquals(listOf("blaze", "halo"), SimpleHome.TALK_TO)
        // Asked for both: @all whoever is picked.
        assertEquals("@all what do you both think?", SimpleHome.tagged("what do you both think?", "blaze"))
        assertEquals("@all Blaze and Halo, settle this", SimpleHome.tagged("Blaze and Halo, settle this", "halo"))
        // A creative multi-phase job goes untagged so the duo teams up on it (Duo TEAM), even with Halo picked.
        val story = "write a long story about a dragon"
        assertTrue(Duo.isTeamTask(story))
        assertEquals(story, SimpleHome.tagged(story, "halo"))
        assertEquals(Duo.Mode.TEAM, Duo.route(SimpleHome.tagged(story, "halo")).mode)
        // Everything else: Halo alone when Halo is picked, Blaze alone otherwise.
        assertEquals("@halo set an alarm for 6", SimpleHome.tagged("set an alarm for 6", "halo"))
        assertEquals(Duo.Mode.BLAZE, Duo.route(SimpleHome.tagged("set an alarm for 6", "all")).mode)
        assertFalse(Duo.wantsBoth("open twitter"))
        assertTrue(Duo.wantsBoth("Halo x Blaze, help me"))
    }

    @Test fun draftsAndSees() {
        assertTrue(SimpleHome.isDraft("Send a text to Mary?", "Allow"))
        assertTrue(SimpleHome.isDraft("Reply to Jack on WhatsApp?", "Allow"))
        assertTrue(!SimpleHome.isDraft("Delete 3 photos?", "Delete"))
        assertEquals("Sees: WhatsApp", SimpleHome.seesLine("WhatsApp"))
        assertEquals("Sees: Finance team on WhatsApp", SimpleHome.seesLine("WhatsApp", "Finance team"))
        assertEquals("", SimpleHome.seesLine(""))
    }
}
