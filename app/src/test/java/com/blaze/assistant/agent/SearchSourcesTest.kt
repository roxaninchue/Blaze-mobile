package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.3: source pills come only from this turn and only from pages the answer used; the search guard stops copies and old subjects. */
class SearchSourcesTest {
    private val taxSearch = "1. Taxonline\n   https://portal.tax.example/taxonline\n   File returns online\n2. Tax portal\n   https://portal.tax.example/\n   Revenue authority\n"
    private val videoSearch = "1. Provider format docs\n   https://docs.video.example/providers\n   How to add a provider repo\n2. Manifest guide\n   https://guide.example/manifest\n   manifest.json fields\n"

    @Test fun staleSourcesFromAnEarlierTurnNeverShow() {
        val c = ReplySources.Collector()
        c.addSearch(taxSearch)              // an earlier turn that ended with an error: no reply took them
        c.begin()                           // the next turn starts
        c.addSearch(videoSearch)
        val f = c.take("Add the provider repo: paste the manifest URL in Add source, then pick the providers.")
        assertTrue(f.sources.none { it.url.contains("tax.example") })
        assertTrue(f.sources.isNotEmpty())
    }

    @Test fun irrelevantSourcesAreFiltered() {
        val all = ReplySources.Found(ReplySources.fromSearch(taxSearch) + ReplySources.fromSearch(videoSearch))
        val f = ReplySources.relevant(all, "To add a video source, open Providers, tap Add source and paste the repo's manifest link.")
        assertEquals(listOf("https://docs.video.example/providers", "https://guide.example/manifest"), f.sources.map { it.url })
        // a cited domain always stays
        assertEquals(2, ReplySources.relevant(all, "See portal.tax.example for the form.").sources.count { it.url.contains("tax") })   // both pages of the cited domain
        assertTrue(ReplySources.relevant(ReplySources.Found(), "x").isEmpty)
    }

    @Test fun duplicateQueryGuard() {
        val g = SearchGuard()
        val goal = "how do I add a video provider source"
        assertNull(g.check("video provider source repo format", goal))
        assertNull(g.check("format of video provider source repo", goal))
        assertNotNull(g.check("video provider repo source format", goal))   // a third near-copy
        assertNull(g.check("manifest json provider fields", goal))
        g.begin()
        assertNull(g.check("video provider source repo format", goal))      // a new turn starts fresh
    }

    @Test fun oldSubjectsBleedingIntoSearchesAreStopped() {
        val g = SearchGuard()
        val goal = "how do I add a video provider source"
        val older = "ZRA taxonline login help. remind me about the ZRA return"
        val n = g.check("ZRA taxonline portal", goal, older)
        assertNotNull(n); assertTrue(n!!.contains("isn't about what the user asked"))
        // following a lead from this turn's own results is fine
        g.saw("The repo is listed on the Vega format page with its manifest")
        assertNull(g.check("vega manifest example", goal, older))
        // other words / another language than the request still run (nothing from older subjects)
        assertNull(g.check("ajouter une source vidéo", goal, older))
    }
}
