package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 0.46.2 Movies routing on the Stage video card. */
class MovieRouteTest {
    private fun m(t: String) = InAppRoute.match(t)

    @Test fun moviePhrasesRoute() {
        assertEquals(InAppRoute.Route.Movie("play", "nosferatu"), m("play the movie Nosferatu").let { if (it is InAppRoute.Route.Movie) it.copy(query = it.query.lowercase()) else it })
        assertEquals("play", (m("watch Sintel") as InAppRoute.Route.Movie).action)
        assertEquals(InAppRoute.Route.Movie("search", "trains"), m("search movies about trains").let { if (it is InAppRoute.Route.Movie) it.copy(query = it.query.lowercase()) else it })
        assertEquals(InAppRoute.Route.Movie("continue"), m("continue watching"))
    }

    @Test fun otherThingsDontBecomeMovies() {
        for (t in listOf("watch out for the rain", "watch youtube", "play music", "what's playing")) {
            assertNotEquals(t, true, m(t) is InAppRoute.Route.Movie)
        }
    }
}
