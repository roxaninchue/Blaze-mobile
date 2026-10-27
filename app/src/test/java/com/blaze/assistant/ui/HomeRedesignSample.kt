package com.blaze.assistant.ui

import com.blaze.assistant.home.InterestsFeed
import com.blaze.assistant.home.NextUp
import com.blaze.assistant.home.QuickActions
import com.blaze.assistant.home.TodoFeed
import com.blaze.assistant.home.WeatherBoard
import com.blaze.assistant.home.WeatherKind
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetData
import com.blaze.assistant.home.WidgetIntent
import com.blaze.assistant.home.WidgetLine

/** Sample Home data shaped like the approved preview, built through the real mappers (news widgets -> Interests, etc.). */
object HomeRedesignSample {
    const val NOW = 1_793_080_800_000L   // a fixed morning; labels below don't depend on the test machine's zone

    fun newsWidgets(): Pair<List<Widget>, Map<String, WidgetData>> {
        val qs = listOf("Programming", "Startups", "PlayStation")
        val ws = qs.mapIndexed { i, q -> WidgetIntent.news(q).let { s -> Widget("n$i", s.type, s.title, s.category, s.config.toString(), order = i + 1) } }
        val d = mapOf(
            "n0" to WidgetData(headline = "Programming news", fetched = 1L, lines = listOf(WidgetLine("Researchers have discovered a faster way to compile", "Facebook \u00b7 3 h ago", "https://example.com/a"))),
            "n1" to WidgetData(headline = "Startups news", fetched = 1L, lines = listOf(WidgetLine("This startup's self-driving excavator digs alone", "TechCrunch \u00b7 4 h ago", "https://example.com/b"))),
            "n2" to WidgetData(headline = "PlayStation news", fetched = 1L, lines = listOf(WidgetLine("PlayStation Handheld May Happen After All", "IGN \u00b7 5 h ago", "https://example.com/c"))),
        )
        return ws to d
    }

    /** Two more tracked interests than the grid shows (the focused Interests card lists all of them). */
    fun extraTopics(): List<InterestsFeed.Topic> = listOf(
        InterestsFeed.Topic("n3", "Formula One", "f1", "Ferrari brings a new floor to Austin", "Autosport \u00b7 1 h ago", "https://example.com/d"),
        InterestsFeed.Topic("n4", "Football", "soccer", "Zambia names its squad for the qualifiers", "BBC Sport \u00b7 2 h ago", "https://example.com/e"))

    fun state(withTasks: Boolean = true, night: Boolean = false): HomeGridState {
        val (ws, d) = newsWidgets()
        val tasks = if (withTasks) listOf(TodoFeed.Task("Buy groceries", true), TodoFeed.Task("Gym at 6 PM", false), TodoFeed.Task("Read 20 pages", false)) else emptyList()
        return HomeGridState(
            board = WeatherBoard.Board("Ndola, ZM", "Broken Clouds", "24\u00b0C", "18:04", WeatherKind.PARTLY_CLOUDY, night, loaded = true),
            topics = InterestsFeed.topics(ws, d),
            music = HomeMusicNow("Track name", "Artist", 62_000L, 228_000L, playing = true),
            tasks = tasks, taskCount = tasks.size,
            next = NextUp.Item(NextUp.Kind.REMINDER, "Reminder", NOW + 3_600_000L),
            actions = QuickActions.build(com.blaze.assistant.agent.SimpleHome.starters(9).map { it.label to it.prompt }, InterestsFeed.labels(ws), emptyList()),
            h24 = false, now = NOW)
    }
}
