package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.46.4 (Rocky 19:41: "what's up??" ran the web lookup lane). Small talk never searches, never gets pills or Go deeper. */
class SmallTalkTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()

    val chit = listOf("hey", "hi", "hello!", "what's up??", "whats up", "wassup", "sup", "yo", "how are you", "how are you doing?", "how r u",
        "how's it going", "lol", "lmao", "haha", "thanks", "thank you so much!", "thx", "ok", "okay", "cool", "nice", "good morning",
        "gm", "good night", "bye", "see you later", "\uD83D\uDE02", "\uD83D\uDC4D", "\u2764\uFE0F", "hey blaze", "hey \uD83D\uDC4B", "you there?", "i'm bored",
        "not much", "love you", "you're the best", "hmm", "what's new?", "morning!")
    val lookup = listOf("What is the capital of Zambia?", "what's the price of bitcoin today", "who won the champions league final in 2025",
        "latest news on the Kafue bridge", "how much is a Raspberry Pi 5?", "when is the next full moon", "who is the president of Kenya",
        "what is the population of Ndola")

    @Test fun chitChatIsChat() { chit.forEach { assertEquals(it, SmallTalk.Kind.CHAT, SmallTalk.classify(it)) } }

    @Test fun chitChatNeverTakesTheLookupLane() {
        // the lookup lane is the only source of web pills and the Go deeper chip on a reply; small talk never enters it
        chit.forEach { assertFalse(it, LookupLane.isLookup(it)) }
        assertTrue(chit.size >= 40)
    }

    @Test fun realLookupsStillLookUp() {
        lookup.forEach { assertTrue(it, LookupLane.isLookup(it)); assertFalse(it, SmallTalk.chitChat(it)) }
        assertEquals(SmallTalk.Kind.LOOKUP, SmallTalk.classify("what's the price of bitcoin today"))
    }

    @Test fun unsureGoesToTheModelNotTheFastLane() {
        // longer conversational messages: not rule-sure, so the lane isn't forced; the normal turn's model decides
        assertEquals(SmallTalk.Kind.UNSURE, SmallTalk.classify("I have a cloudflare token I want to give you and a gitlab token as well"))
    }

    @Test fun bareAckAfterAQuestionStaysWithTheTask() {
        assertTrue(SmallTalk.ack("yes")); assertTrue(SmallTalk.ack("ok!")); assertFalse(SmallTalk.ack("ok thanks for the tip about the bridge"))
    }

    @Test fun neverDescribesThePage() {
        val bad = "The page lists \u201cwhat's up?\u201d among the idioms for greeting someone."
        assertTrue(SmallTalk.describesPage(bad, "what's up??"))
        assertFalse(SmallTalk.describesPage(bad, "what does this page say about idioms?"))
        assertEquals("Lusaka is the capital.", SmallTalk.stripPageLead("The page says that lusaka is the capital."))
        assertFalse(SmallTalk.describesPage("Lusaka is the capital of Zambia.", "capital of zambia?"))
        assertTrue(LookupLane.SYSTEM.contains("never describe the pages"))
    }

    @Test fun wiring() {
        fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/agent/$p"), File("app/src/main/java/com/blaze/assistant/agent/$p")).first { it.exists() }.readText()
        assertTrue(src("LookupLane.kt").contains("if (SmallTalk.ruleChat(t)) return false"))
        assertTrue(src("ChatLane.kt").contains("if (SmallTalk.chitChat(t) && !TurnContext.scheduleFollowUp(t, lastAssistant)"))
        assertTrue(src("ChatLane.kt").contains("if (SmallTalk.chitChat(userText)) append(SmallTalk.RULE)"))
        assertTrue(src("LookupRoutes.kt").contains("SmallTalk.describesPage(ans.text, q)"))
    }
}
