package com.blaze.assistant.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.3 lookup fast lane: routing, escalation, answer first then update in place, the progress line, per-fetch timeouts. */
class LookupLaneTest {
    private fun fx(id: String) = LookupBench.fixtures.first { it.id == id }

    @Test fun routesLookupsAndKeepsEverythingElse() {
        listOf("What is the capital of Zambia?", "who wrote Things Fall Apart", "How much is a Raspberry Pi 5?", "find the opening hours of the national museum",
            "latest news on the Kafue bridge").forEach { assertTrue(it, LookupLane.isLookup(it)) }
        listOf("open WhatsApp", "set an alarm for 7", "what time is it", "what's the weather tomorrow", "who are you", "book a table for two tonight",
            "what's on my calendar", "remind me to call mom", "2+2", "hi").forEach { assertFalse(it, LookupLane.isLookup(it)) }
    }

    @Test fun answersFirstFromTwoAgreeingPagesFetchedInParallel() = runTest {
        val f = fx("capital"); val calls = intArrayOf(0)
        val ev = ArrayList<LookupLane.Event>()
        val st = LookupLane.first(f.q, LookupBench.Net(f), LookupBench.Brain(f, calls), LookupLane.Config(), { currentTime }) { ev.add(it) }
        assertNotNull(st)
        val first = ev.filterIsInstance<LookupLane.Event.First>().single()
        assertTrue(first.answer.supporting.size >= 2)
        assertEquals(1, calls[0])
        // search 0.9 s + the slowest of 3 parallel pages 1.2 s + one model call 2 s (sequential reads would be 0.9+0.7+0.9+1.2+2)
        assertEquals(900L + 1200L + LookupBench.MODEL_MS, first.ms)
        assertEquals(0, ev.count { it is LookupLane.Event.Escalate })
    }

    @Test fun perFetchTimeoutKeepsSlowPagesFromHoldingTheAnswer() = runTest {
        val f = fx("timeouts")
        val st = LookupLane.first(f.q, LookupBench.Net(f), LookupBench.Brain(f, intArrayOf(0)), LookupLane.Config(), { currentTime }) { }
        assertNotNull(st)
        assertTrue("answered in ${currentTime} ms", currentTime < 20_000)
    }

    @Test fun escalatesWhenFetchesFailTwiceOrPagesNeedSignIn() = runTest {
        for ((id, why) in listOf("fails_twice" to "wouldn't load", "login_wall" to "sign-in")) {
            val f = fx(id); val ev = ArrayList<LookupLane.Event>()
            val st = LookupLane.first(f.q, LookupBench.Net(f), LookupBench.Brain(f, intArrayOf(0)), LookupLane.Config(), { currentTime }) { ev.add(it) }
            assertNull(st)
            val e = ev.filterIsInstance<LookupLane.Event.Escalate>().single()
            assertTrue(e.reason, e.reason.contains(why))
            assertTrue("capped steps", ev.filterIsInstance<LookupLane.Event.Progress>().size <= LookupLane.Config().maxSteps)
        }
    }

    @Test fun backgroundCheckUpdatesOnlyWhenTheAnswerChanges() = runTest {
        val f = fx("update")
        var n = 0
        val brain = object : LookupLane.Brain {
            override suspend fun answer(q: String, pages: List<LookupLane.Page>): LookupLane.Answer {
                delay(10); n++
                return if (n == 1) LookupLane.Answer("About 455,194 people (2010 census).", pages.map { it.url })
                else LookupLane.Answer("About 627,503 people (2022 census).", pages.map { it.url })
            }
        }
        val st = LookupLane.first(f.q, LookupBench.Net(f), brain, LookupLane.Config(), { currentTime }) { }!!
        val u = LookupLane.verify(st, LookupBench.Net(f), brain, LookupLane.Config())!!
        assertTrue(u.changed)
        assertTrue(u.answer.text.contains("627,503"))
        // same facts in other words: no update
        assertFalse(LookupLane.differs("It is Lusaka.", "It is Lusaka, the capital."))
        assertTrue(LookupLane.differs("Built in 1959.", "Built in 1960."))
    }

    @Test fun verifyIsBounded() = runTest {
        val f = fx("capital")
        val slow = object : LookupLane.Net by LookupBench.Net(f) {
            override suspend fun fetch(url: String): LookupLane.Page { delay(100_000); return LookupLane.Page(url, "", "", false) }
        }
        val st = LookupLane.State(f.q, f.pages.map { LookupLane.Hit("r", it.url) }, setOf(f.pages[0].url), emptyList(), LookupLane.Answer("x", emptyList()))
        val t0 = currentTime
        LookupLane.verify(st, slow, LookupBench.Brain(f, intArrayOf(0)), LookupLane.Config(fetchTimeoutMs = 60_000, verifyMs = 40_000))
        assertTrue(currentTime - t0 <= 40_000)
    }

    @Test fun progressLineIsPlainWithCounts() {
        val lines = listOf(LookupLane.searching(), LookupLane.reading(3), LookupLane.reading(1, true), LookupLane.writing(2), LookupLane.checking(3))
        assertEquals(listOf("Searching the web", "Reading 3 pages", "Reading 1 more page", "Writing the answer from 2 sources", "Checking 3 more sources"), lines)
        assertEquals("Reading 3 pages · step 2 · 2 sources", LookupLane.line(LookupLane.Event.Progress(LookupLane.reading(3), 2, 2)))
        val metaphor = Regex("(?i)dig|hunt|brew|cook|weav|magic|journey|dive|scour|sift|treasure|spark|forge|summon|\\u2026")
        lines.forEach { assertFalse(it, metaphor.containsMatchIn(it)) }
    }

    @Test fun parsesTheModelReply() {
        val pages = listOf(LookupLane.Page("https://a.example", "A", "t", true), LookupLane.Page("https://b.example", "B", "t", true))
        val a = LookupLane.parse("""{"answer":"Lusaka.","sources":[2],"needs_browser":false}""", pages)
        assertEquals("Lusaka.", a.text); assertEquals(listOf("https://b.example"), a.supporting)
        assertTrue(LookupLane.parse("""{"answer":"","sources":[],"needs_browser":true}""", pages).needsBrowser)
        assertEquals(2, LookupLane.hitsOf("1. A\n   https://a.example\n   s\n2. B\n   https://b.example\n   s\n").size)
    }
}
