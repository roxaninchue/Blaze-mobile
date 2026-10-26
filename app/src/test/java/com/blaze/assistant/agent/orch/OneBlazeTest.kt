package com.blaze.assistant.agent.orch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.47.0 merged chat during work, pill words, Halo migration, Butler personality and mood reading. */
class OneBlazeTest {
    // ------------------------------------------------------------ LightLane
    @Test fun routesStopChangeSteerSide() {
        listOf("stop", "Stop!", "cancel that", "never mind", "stop everything").forEach { assertEquals(it, LightLane.Kind.STOP, LightLane.route(it)) }
        listOf("forget that, book Friday instead", "scrap that and find hotels in Livingstone", "new task: clean my downloads", "change of plan, call Joe").forEach {
            assertEquals(it, LightLane.Kind.CHANGE, LightLane.route(it)) }
        listOf("also check Ethiopian Airlines", "make it 7 pm", "only direct flights", "don't include weekends", "@halo add a summary").forEach {
            assertEquals(it, LightLane.Kind.STEER, LightLane.route(it)) }
        listOf("how's it going?", "what's the capital of Peru?", "lol you're fast", "any update").forEach { assertEquals(it, LightLane.Kind.SIDE, LightLane.route(it)) }
        assertEquals(LightLane.Kind.STEER, LightLane.route("send it to Joe too", toolShaped = true))
        assertEquals("a question stays a side question even if tool shaped", LightLane.Kind.SIDE, LightLane.route("what time is it in Tokyo?", toolShaped = true))
    }

    @Test fun changeExtractsNewInstruction() {
        assertEquals("book Friday instead", LightLane.newInstruction("forget that, book Friday instead"))
        assertEquals("find hotels in Livingstone", LightLane.newInstruction("scrap that and find hotels in Livingstone"))
        assertTrue(LightLane.changed("book Friday").contains("earlier task is stopped"))
    }

    @Test fun sideStatusIsReadOnlyAndBlaze() {
        val s = LightLane.status("compare flights", "Reading 3 pages", listOf("Looking it up"))
        assertTrue(s.contains("You (Blaze)")); assertTrue(s.contains("Don't start actions")); assertFalse(s.contains("Halo"))
    }

    // ------------------------------------------------------------ StatusLine
    @Test fun pillWordsAreNeutral() {
        val banned = Regex("(?i)hark|model|brain|specialist|agent|tool|provider|gateway|thinking|json|nous|opencode")
        val tools = listOf("web_search", "fetch_page", "browser_open", "open_app", "device_setting", "read_screen", "tap", "send_sms", "gmail_send", "set_alarm",
            "create_routine", "calendar_events", "file_write", "run_shell", "recall_memory", "generate_image", "something_new")
        tools.forEach { t -> val l = StatusLine.forTool(t, JSONObject().put("query", "flights to Lusaka").put("app", "com.whatsapp"))
            assertFalse("$t → $l", banned.containsMatchIn(l)); assertTrue(l.length <= 60); assertFalse(l.endsWith(".")) }
        assertEquals("Reading 3 pages", StatusLine.forTool("fetch_page", pages = 3))
        assertEquals("Opening Whatsapp", StatusLine.forTool("open_app", JSONObject().put("app", "com.whatsapp")))
        assertNull(StatusLine.clean("Asking the model"))
        assertNull(StatusLine.clean("Using Hark specialist"))
        assertNull(StatusLine.clean("call web_search"))
    }

    @Test fun firstLineIsLocalAndFast() {
        val t0 = System.nanoTime()
        val l = StatusLine.first("open settings and turn on wifi")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("Opening Settings", l)
        assertTrue("first line in ${ms} ms", ms < StatusLine.FIRST_LINE_BUDGET_MS)
        assertEquals("Searching flights", StatusLine.first("find me flights to Cape Town"))
        assertEquals("Opening Spotify", StatusLine.first("open spotify"))
        assertEquals("Working on it", StatusLine.first("hmm"))
    }

    // ------------------------------------------------------------ HaloMerge
    private class MapPrefs(val m: MutableMap<String, Any> = mutableMapOf()) : HaloMerge.Prefs {
        override fun str(k: String) = m[k] as? String
        override fun put(k: String, v: String) { m[k] = v }
        override fun bool(k: String) = m[k] as? Boolean
        override fun putBool(k: String, v: Boolean) { m[k] = v }
    }

    @Test fun migrationIsIdempotentAndKeepsPicks() {
        // untouched: the personality key was never written -> Butler, no card
        val p = MapPrefs(mutableMapOf("talk_to" to "halo", "halo_language" to "mix Bemba"))
        val r = HaloMerge.migrate(p)
        assertTrue(r.ran)
        assertEquals("blaze", p.m["talk_to"]); assertEquals(Butler.ID, p.m["persona"]); assertEquals(false, p.m["duo_team"])
        assertFalse(ButlerIntro.shouldShow(p))
        assertTrue((p.m["blaze_voice_notes"] as String).contains("mix Bemba"))
        assertFalse(HaloMerge.migrate(p).ran)
        // written "blaze" (could be a deliberate pick; older builds stored no flag) -> kept, one-time card
        val written = MapPrefs(mutableMapOf("persona" to "blaze"))
        HaloMerge.migrate(written); assertEquals("a written personality is never overridden", "blaze", written.m["persona"])
        assertTrue(ButlerIntro.shouldShow(written))
        val picked = MapPrefs(mutableMapOf("persona" to "blaze", "persona_picked" to true))
        HaloMerge.migrate(picked); assertEquals("an explicit pick stays", "blaze", picked.m["persona"])
        val coach = MapPrefs(mutableMapOf("persona" to "coach")); HaloMerge.migrate(coach); assertEquals("coach", coach.m["persona"])
        assertTrue(ButlerIntro.shouldShow(coach))
        // a second run never re-raises the card or touches the pick
        ButlerIntro.answer(coach, tryIt = false); HaloMerge.migrate(coach)
        assertFalse(ButlerIntro.shouldShow(coach)); assertEquals("coach", coach.m["persona"])
        assertEquals("Sure thing", HaloMerge.untagHistory("[Halo] Sure thing"))
    }

    // ------------------------------------------------------------ Butler + mood
    @Test fun neverSirOrMadam() {
        assertEquals("Certainly.", Butler.scrub("Certainly, sir."))
        assertEquals("Very good, Rocky.", Butler.scrub("Very good, sir.", "Rocky"))
        assertEquals("Your 9:00 is moved.", Butler.scrub("Your 9:00 is moved, madam."))
        assertFalse(Butler.hasHonorific(Butler.scrub("Of course ma'am, right away sir")))
        assertTrue(Butler.PROMPT.contains("NEVER call them sir"))
        assertFalse(Regex("(?i)jarvis|marvel|stark").containsMatchIn(Butler.PROMPT + Butler.NAME + Butler.BLURB))
        assertEquals("the word 'sirloin' is fine", "Sirloin is on the menu.", Butler.scrub("Sirloin is on the menu."))
    }

    @Test fun nameIsUsed() {
        val rule = Butler.voiceRule("Rocky", Mood.Tone.NEUTRAL)
        assertTrue(rule.contains("The user's name is Rocky"))
        assertTrue(rule.contains("never sir or madam"))
        assertEquals("Rocky", Butler.nickFrom("call me Rocky please"))
        assertEquals("Rox", Butler.nickFrom("you can call me Rox"))
        assertNull(Butler.nickFrom("call me sir"))
        assertTrue(Butler.shouldAskNick(asked = false, haveNick = false, mood = Mood.Tone.NEUTRAL, busy = false))
        assertFalse("asked once", Butler.shouldAskNick(asked = true, haveNick = false, mood = Mood.Tone.NEUTRAL, busy = false))
        assertFalse("not when sad", Butler.shouldAskNick(false, false, Mood.Tone.SAD, false))
    }

    @Test fun witSuppressedWhenUpset() {
        assertEquals(Mood.Tone.SAD, Mood.read("my grandma passed away this morning"))
        assertEquals(Mood.Tone.FRUSTRATED, Mood.read("this is STILL not working, I told you twice"))
        assertEquals(Mood.Tone.FRUSTRATED, Mood.read("WHY IS THE ALARM NOT RINGING"))
        assertEquals(Mood.Tone.RUSHED, Mood.read("quick, what's the bus time, I'm running late"))
        assertEquals(Mood.Tone.JOKING, Mood.read("haha you're getting cheeky"))
        assertEquals(Mood.Tone.TERSE, Mood.read("alarm 6"))
        assertFalse(Mood.witAllowed(Mood.Tone.SAD)); assertFalse(Mood.witAllowed(Mood.Tone.FRUSTRATED)); assertTrue(Mood.witAllowed(Mood.Tone.JOKING))
        assertTrue(Butler.voiceRule("Rocky", Mood.Tone.SAD).contains("no humour at all"))
        val joke = "I've moved your 1:00. Whoever titled it 'busy' deserves a medal for descriptive precision."
        assertEquals("I've moved your 1:00.", Mood.dewit(joke, Mood.Tone.FRUSTRATED))
        assertEquals(joke, Mood.dewit(joke, Mood.Tone.NEUTRAL))
    }

    @Test fun butlerCardTryItSwitchesOnceAndNeverReturns() {
        val p = MapPrefs(mutableMapOf("persona" to "calm"))
        HaloMerge.migrate(p)
        assertTrue(ButlerIntro.shouldShow(p))
        assertEquals("New: Butler personality. Try it?", ButlerIntro.TEXT)
        assertEquals(Butler.ID, ButlerIntro.answer(p, tryIt = true))
        assertEquals(true, p.m["persona_picked"])
        assertFalse(ButlerIntro.shouldShow(p))
        // already on Butler (e.g. picked on another device): no card
        val b = MapPrefs(mutableMapOf("persona" to Butler.ID)); HaloMerge.migrate(b); assertFalse(ButlerIntro.shouldShow(b))
    }

    @Test fun personalityPersistsInSpecialistReplies() {
        // the same voice rule + scrub is applied to every specialist's final reply (OrchWorker.finalReply)
        val reply = OrchVoice.finalReply("Flights found, sir. Cheapest is K2,100.", butler = true, name = "Rocky", tone = Mood.Tone.NEUTRAL)
        assertFalse(Butler.hasHonorific(reply))
        assertTrue(OrchVoice.system("researcher", butler = true, name = "Rocky", tone = Mood.Tone.SAD).contains("no humour at all"))
        assertTrue(OrchVoice.system("coder", butler = true, name = "Rocky", tone = Mood.Tone.NEUTRAL).contains("VOICE (Butler)"))
        assertFalse(OrchVoice.system("coder", butler = false, name = "Rocky", tone = Mood.Tone.NEUTRAL).contains("VOICE (Butler)"))
    }
}
