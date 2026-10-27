package com.blaze.assistant.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 0.47 Home redesign: the grid plan, Interests from the news widgets, topic icons, weather board, To-do, Next up, Quick actions. */
class HomeGridTest {
    private fun news(id: String, q: String, order: Int, hidden: Boolean = false): Widget {
        val s = WidgetIntent.news(q)
        return Widget(id, s.type, s.title, s.category, s.config.toString(), order = order, hidden = hidden)
    }

    private fun lines(vararg l: WidgetLine) = WidgetData(headline = "x", lines = l.toList(), fetched = 1L)

    // ---------------- grid ----------------

    @Test fun todoHiddenWithoutTasks() {
        val rows = HomeGrid.rows(hasTasks = false)
        assertFalse(rows.flatMap { it.cards }.contains(HomeGrid.Card.TODO))
        assertEquals(listOf(HomeGrid.Card.MUSIC), rows[1].cards)
        assertFalse(rows[1].full)
        assertEquals(listOf(HomeGrid.Card.INTERESTS, HomeGrid.Card.MUSIC, HomeGrid.Card.NEXT_UP, HomeGrid.Card.QUICK), HomeGrid.order(false).drop(1))
    }

    @Test fun gridWithTasks() {
        val rows = HomeGrid.rows(hasTasks = true)
        assertEquals(4, rows.size)
        assertEquals(listOf(HomeGrid.Card.WEATHER, HomeGrid.Card.INTERESTS), rows[0].cards)
        assertEquals(listOf(HomeGrid.Card.MUSIC, HomeGrid.Card.TODO), rows[1].cards)
        assertEquals(listOf(HomeGrid.Card.NEXT_UP), rows[2].cards); assertTrue(rows[2].full)
        assertEquals(listOf(HomeGrid.Card.QUICK), rows[3].cards); assertTrue(rows[3].full)
    }

    @Test fun todoCardFollowsRealTasks() {
        val todo = Widget("t", WidgetTypes.CUSTOM, "To-do", "Today", JSONObject().toString())
        val none = TodoFeed.tasks(listOf(todo), emptyMap())
        assertTrue(none.isEmpty())
        val d = mapOf("t" to lines(WidgetLine("[x] Buy groceries"), WidgetLine("Gym at 6 PM"), WidgetLine("Read 20 pages", note = ""), WidgetLine("Call mum")))
        val tasks = TodoFeed.tasks(listOf(todo), d)
        assertEquals(listOf("Buy groceries", "Gym at 6 PM", "Read 20 pages"), tasks.map { it.title })
        assertEquals(listOf(true, false, false), tasks.map { it.done })
        assertEquals(4, TodoFeed.count(listOf(todo), d))
        // a plain custom widget is not a to-do list
        assertFalse(TodoFeed.isTodo(Widget("c", WidgetTypes.CUSTOM, "Packing", "Notes")))
        assertFalse(TodoFeed.isTodo(todo.copy(hidden = true)))
    }

    // ---------------- Interests ----------------

    @Test fun interestsMapFromNewsWidgets() {
        val ws = listOf(
            news("n2", "startups", 2), news("n1", "programming", 1), news("n3", "PlayStation", 3),
            Widget("top", WidgetTypes.NEWS, "Top stories", "News", JSONObject().put("query", "").toString(), order = 0),
            Widget("feed", WidgetTypes.NEWS, "Feed", "News", JSONObject().put("feed", "https://x/rss").toString(), order = 4),
            Widget("w", WidgetTypes.WEATHER, "Weather", "Weather", order = 5),
        )
        val d = mapOf(
            "n1" to lines(WidgetLine("Researchers have discovered a new way", "Facebook · 3 h ago", "https://a"), WidgetLine("older", "X · 5 h ago")),
            "n2" to lines(WidgetLine("This startup's self-driving exc", "TechCrunch · 4 h ago", "https://b")),
            "n3" to lines(WidgetLine("PlayStation Handheld May Happen", "IGN · 5 h ago", "https://c")),
        )
        val t = InterestsFeed.topics(ws, d)
        assertEquals(listOf("Programming", "Startups", "PlayStation"), t.map { it.label })
        assertEquals(listOf("code", "trend", "gamepad"), t.map { it.icon })
        assertEquals("Researchers have discovered a new way", t[0].headline)   // lines.first()
        assertEquals("Facebook · 3 h ago", t[0].meta)
        assertEquals("https://a", t[0].url)
        assertEquals(listOf("n1", "n2", "n3"), t.map { it.widgetId })
    }

    @Test fun interestsWithoutContentYetAndLimits() {
        val ws = listOf(news("a", "AI", 0), news("b", "ai", 1), news("c", "Formula 1", 2), news("d", "Arsenal transfer", 3), news("e", "Cricket", 4, hidden = true))
        val t = InterestsFeed.topics(ws, emptyMap())
        assertEquals(listOf("AI", "Formula 1", "Arsenal Transfer"), t.map { it.label })   // duplicates and hidden dropped, max 3
        assertTrue(t.all { it.headline.isEmpty() && it.meta.isEmpty() })
        assertEquals(listOf("ai", "f1", "soccer"), t.map { it.icon })
        assertEquals(listOf("AI", "Formula 1"), InterestsFeed.labels(ws, 2))
    }

    @Test fun everyCatalogueTopicHasAnIcon() {
        Interests.catalogue.forEach { c ->
            (listOf(c.label) + c.subs).forEach { s -> assertTrue("no icon for $s", TopicIcons.of(s) != TopicIcons.DEFAULT || s in setOf("News", "Local", "World")) }
        }
        assertEquals("news", TopicIcons.of("Something else entirely"))
        assertEquals("code", TopicIcons.of("Kotlin news"))
    }

    // ---------------- weather board ----------------

    @Test fun weatherKinds() {
        assertEquals(WeatherKind.PARTLY_CLOUDY, WeatherBoard.kind("Broken Clouds"))
        assertEquals(WeatherKind.PARTLY_CLOUDY, WeatherBoard.kind("Partly cloudy"))
        assertEquals(WeatherKind.OVERCAST, WeatherBoard.kind("Overcast"))
        assertEquals(WeatherKind.RAIN, WeatherBoard.kind("Light drizzle"))
        assertEquals(WeatherKind.SNOW, WeatherBoard.kind("Snow showers"))
        assertEquals(WeatherKind.THUNDER, WeatherBoard.kind("Thunderstorm with hail"))
        assertEquals(WeatherKind.FOG, WeatherBoard.kind("Fog"))
        assertEquals(WeatherKind.CLEAR, WeatherBoard.kind("Clear"))
    }

    @Test fun boardFromWeatherWidget() {
        val w = Widget("w", WidgetTypes.WEATHER, "Weather", "Weather")
        val d = WidgetData(headline = "Ndola, ZM", value = "24°C", change = "Broken clouds · feels 25° · wind 9 km/h", sunset = "18:04", fetched = 1L)
        val b = WeatherBoard.from(listOf(w), mapOf("w" to d), 10, 0)
        assertEquals("Ndola, ZM", b.place); assertEquals("Broken clouds", b.condition); assertEquals("24°C", b.temp)
        assertEquals(WeatherKind.PARTLY_CLOUDY, b.kind); assertFalse(b.night); assertTrue(b.loaded)
        assertTrue(WeatherBoard.from(listOf(w), mapOf("w" to d), 18, 30).night)
        assertEquals("6:04 PM", WeatherBoard.clock("18:04", h24 = false))
        assertEquals("18:04", WeatherBoard.clock("18:04", h24 = true))
        assertEquals("12:05 AM", WeatherBoard.clock("00:05", h24 = false))
        assertFalse(WeatherBoard.from(emptyList(), emptyMap(), 10, 0).loaded)
        assertTrue(WeatherBoard.night(22, 0, ""))
    }

    @Test fun sunsetSurvivesTheCache() {
        val d = WidgetData(headline = "Ndola", value = "24°C", sunset = "18:04")
        assertEquals("18:04", WidgetData.parse(d.toJson(), 1L, "").sunset)
        assertEquals("", WidgetData.parse(WidgetData(headline = "x").toJson(), 1L, "").sunset)
        val daily = JSONObject().put("sunset", org.json.JSONArray(listOf("2026-10-27T18:04", "2026-10-28T18:05")))
        assertEquals("18:04", OpenMeteo.sunset(daily))
        assertTrue(OpenMeteo.forecastUrl(-12.95, 28.6, false).contains("sunset"))
    }

    // ---------------- Next up ----------------

    @Test fun nextUpPicksTheEarliest() {
        val now = 1_000_000L
        assertNull(NextUp.pick(listOf("Old" to now - 1), emptyList(), now))
        val p = NextUp.pick(listOf("Reminder" to now + 5_000, "Later" to now + 9_000), listOf("Standup" to now + 7_000), now)!!
        assertEquals(NextUp.Kind.REMINDER, p.kind); assertEquals("Reminder", p.title)
        assertEquals(NextUp.Kind.CALENDAR, NextUp.pick(listOf("R" to now + 9_000), listOf("Standup" to now + 7_000), now)!!.kind)
        val tz = TimeZone.getTimeZone("Africa/Lusaka")
        val base = java.util.Calendar.getInstance(tz).apply { set(2026, 9, 27, 8, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals("9:00 AM", NextUp.whenLabel(base + 3_600_000L, base, false, tz, java.util.Locale.US))
        assertEquals("Tomorrow 9:00 AM", NextUp.whenLabel(base + 25 * 3_600_000L, base, false, tz, java.util.Locale.US))
    }

    // ---------------- Quick actions ----------------

    @Test fun quickActionsFromRealData() {
        val starters = com.blaze.assistant.agent.SimpleHome.starters(9).map { it.label to it.prompt }
        val qa = QuickActions.build(starters, listOf("Programming", "Startups", "PlayStation"), routineHours = emptyList())
        assertEquals(listOf("Plan", "Catch up", "Set up"), qa.map { it.verb })
        assertEquals("my day: what's on and what not to forget", qa[0].rest)
        assertEquals("on Programming, Startups and PlayStation news", qa[1].rest)
        assertEquals("a morning routine that briefs me at 7", qa[2].rest)
        assertEquals(starters[0].second, qa[0].prompt)
        // a morning routine already exists: no setup suggestion; no topics: the second starter
        val qb = QuickActions.build(starters, emptyList(), routineHours = listOf(7))
        assertEquals(2, qb.size)
        assertEquals("Catch up", qb[1].verb)
        assertEquals("A and B", QuickActions.joinAnd(listOf("A", "B")))
    }
}
