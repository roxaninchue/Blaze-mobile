package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.0 regression: "@halo making a huge blaze update!!…" got "Halo's got the modular screens … locked in … it'll be yours". */
class NewsPromiseTest {
    private val user = "@halo making a huge blaze update!! Modular screens and study coach, you'll hear about them when they're ready!"
    private val bad = "Halo's got the modular screens and study coach locked in\u2014just a few more sparks to fire and it'll be yours."

    @Test fun sharingNewsIsNotATask() {
        assertTrue(NewsPromise.sharingNews(user))
        assertTrue(NewsPromise.sharingNews("guess what, I'm building a new site this weekend"))
        assertFalse(NewsPromise.sharingNews("can you make me a study timer?"))
        assertFalse(NewsPromise.sharingNews("what's new in the update?"))
        assertFalse(NewsPromise.sharingNews("ok"))
    }

    @Test fun promisesAreDroppedWhenNothingRan() {
        val reply = "Ooh, that sounds huge! $bad"
        assertEquals("Ooh, that sounds huge!", NewsPromise.clean(reply, user, toolsRan = false))
        assertNull(NewsPromise.clean(reply, user, toolsRan = true))                          // a tool ran: other checks decide
        assertNull(NewsPromise.clean(reply, "build me a landing page", toolsRan = false))    // a real request
        assertNull(NewsPromise.clean("Can't wait to see it!", user, toolsRan = false))       // nothing to drop
        assertNull(NewsPromise.clean(bad, user, toolsRan = false))                           // all promise: caller keeps its text
        for (p in listOf("I'm on it!", "It's in the works.", "Coming your way soon.", "Almost ready.", "We've got it covered."))
            assertTrue(p, NewsPromise.promise.containsMatchIn(p))
    }

    @Test fun waitLineSkippedForHaloAndNews() {
        assertTrue(WaitNudge.skip(Duo.Who.HALO, "@halo plan my week"))
        assertTrue(WaitNudge.skip(Duo.Who.BLAZE, user))
        assertFalse(WaitNudge.skip(Duo.Who.BLAZE, "find me cheap flights to Lusaka"))
        assertNull(WaitNudge.clean(bad))
        assertEquals("Halo is on it and will be back with your plan shortly.", WaitNudge.clean("Halo is on it and will be back with your plan shortly."))
    }

    @Test fun haloSpeaksInFirstPerson() {
        assertTrue(Duo.soloOverride(Duo.Who.HALO).contains("first person"))
        assertTrue(Duo.soloOverride(Duo.Who.HALO).contains("NEWS IS NOT A TASK"))
    }
}
