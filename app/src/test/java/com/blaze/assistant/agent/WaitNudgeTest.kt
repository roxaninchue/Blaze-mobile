package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.28: the free one's live "hang tight" line while its partner works, and side talk both ways. */
class WaitNudgeTest {
    @Test fun onlyAfterTenQuietSeconds() {
        assertFalse(WaitNudge.due(9_999, 0, 0, streaming = false))
        assertTrue(WaitNudge.due(10_000, 0, 0, streaming = false))
        assertFalse(WaitNudge.due(15_000, 0, 6_000, streaming = false))   // something new showed 9 s ago
        assertTrue(WaitNudge.due(16_000, 0, 6_000, streaming = false))
        assertFalse(WaitNudge.due(60_000, 0, 0, streaming = true))         // the reply is already streaming in
    }

    @Test fun promptCarriesTheContextNotAScript() {
        val p = WaitNudge.system(Duo.Who.BLAZE, Duo.Who.HALO, "Sam", "find me the latest AI news", 11)
        assertTrue(p.startsWith(Duo.BLAZE_SOUL))
        assertTrue(p.contains("Halo, your partner") && p.contains("find me the latest AI news") && p.contains("11 seconds"))
        assertTrue(p.contains("first name is Sam") && p.contains("1 or 2 sentences") && p.contains("vary your wording"))
        val q = WaitNudge.system(Duo.Who.HALO, Duo.Who.BLAZE, null, "book a table", 10)
        assertTrue(q.startsWith(Duo.HALO_SOUL.take(40)) && q.contains("Blaze, your partner") && q.contains("don't use one"))
    }

    @Test fun names() {
        assertEquals("Sam", WaitNudge.firstName("Sam Lee"))
        assertEquals("Mutale", WaitNudge.firstName("  Mutale "))
        assertNull(WaitNudge.firstName("")); assertNull(WaitNudge.firstName(null))
    }

    @Test fun cleanKeepsItShortAndPlain() {
        assertEquals("Halo's pulling the AI news together, Sam. Anything I can sort out for you meanwhile?",
            WaitNudge.clean("[Blaze] Halo's pulling the AI news together, Sam. Anything I can sort out for you meanwhile? [[done]]"))
        assertEquals("One. Two two two.", WaitNudge.clean("One. Two two two. Three three three."))
        assertNull(WaitNudge.clean(""))
        assertNull(WaitNudge.clean("ok"))
        assertNull(WaitNudge.clean("```json\n{}\n```"))
        assertNull(WaitNudge.clean("x".repeat(300)))
        assertNull(WaitNudge.clean("line one\nline two\nline three"))
    }

    @Test fun sideTalkWorksBothWays() {
        val blazeFree = Duo.sideSystem(null, "fetch AI news", "", Duo.Who.BLAZE)
        assertTrue(blazeFree.startsWith(Duo.BLAZE_SOUL))
        assertTrue(blazeFree.contains("Halo, your partner, is busy") && blazeFree.contains("[[relay]]"))
        val haloFree = Duo.sideSystem(null, "book a room", "")
        assertTrue(haloFree.contains("Blaze, your partner, is busy"))
    }
}
