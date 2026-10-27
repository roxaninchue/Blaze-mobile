package com.blaze.assistant.home

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 0.47 Home redesign (splash -> Home), the pure part: which cards the settled 2-column grid shows and in what order,
 * the Interests card fed by the tracked-topic news widgets, the topic -> icon lookup, the weather board's lines, the
 * To-do and Next up pickers and the Quick actions. No Android or Compose types, so all of it is unit tested on the JVM
 * (HomeGridTest). The Compose side is ui/HomeGridUi.kt + ui/HomeIntro.kt.
 */
object HomeGrid {
    enum class Card { WEATHER, INTERESTS, MUSIC, TODO, NEXT_UP, QUICK }

    /** One row of the grid: two half-width cards side by side, or one full-width card. */
    data class Row(val cards: List<Card>, val full: Boolean)

    /**
     * Weather | Interests, Music | To-do (To-do only when there are tasks), Next up (full width), Quick actions (full
     * width). Without tasks Music keeps its half-width cell on the left.
     */
    fun rows(hasTasks: Boolean): List<Row> = listOf(
        Row(listOf(Card.WEATHER, Card.INTERESTS), full = false),
        Row(if (hasTasks) listOf(Card.MUSIC, Card.TODO) else listOf(Card.MUSIC), full = false),
        Row(listOf(Card.NEXT_UP), full = true),
        Row(listOf(Card.QUICK), full = true),
    )

    /** The order the cards emerge in during the intro (the weather board comes from the splash cloud, not the centre). */
    fun order(hasTasks: Boolean): List<Card> = rows(hasTasks).flatMap { it.cards }
}

// ---------------------------------------------------------------- Interests ("Interests · Tracking")

object InterestsFeed {
    /** One tracked topic: its chip (label + icon key) and its latest headline with "Source · 3 h ago". */
    data class Topic(val widgetId: String, val label: String, val icon: String, val headline: String, val meta: String, val url: String)

    /** Catalogue labels and sub-topics, lower-cased -> their display spelling ("programming" -> "Programming"). */
    private val known: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        Interests.catalogue.forEach { c -> m[c.label.lowercase(Locale.ROOT)] = c.label; c.subs.forEach { s -> m[s.lowercase(Locale.ROOT)] = s } }
        m
    }

    /** "programming" -> "Programming" (catalogue spelling), "formula one" -> "Formula One", "NBA" stays. */
    fun label(query: String): String {
        val q = query.trim()
        known[q.lowercase(Locale.ROOT)]?.let { return it }
        return q.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { w ->
            if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    /** A tracked topic is a visible news widget with a search query (what [WidgetIntent.news] makes). Feeds and "Top stories" are not topics. */
    fun isTopic(w: Widget): Boolean = w.type == WidgetTypes.NEWS && !w.hidden && w.opt("feed").isBlank() && w.opt("query").isNotBlank()

    /** The tracked topics in board order, each with its newest headline (`lines.first()`), at most [max]. */
    fun topics(widgets: List<Widget>, data: Map<String, WidgetData>, max: Int = 3): List<Topic> {
        val seen = HashSet<String>()
        return widgets.filter(::isTopic).sortedBy { it.order }.mapNotNull { w ->
            val label = label(w.opt("query"))
            if (!seen.add(label.lowercase(Locale.ROOT))) return@mapNotNull null
            val first = data[w.id]?.lines?.firstOrNull()
            Topic(w.id, label, TopicIcons.of(label), first?.title.orEmpty(), first?.sub.orEmpty(), first?.url.orEmpty())
        }.take(max)
    }

    /** Just the labels, for Quick actions ("Catch up on Programming, Startups and PlayStation news"). */
    fun labels(widgets: List<Widget>, max: Int = 3): List<String> =
        widgets.filter(::isTopic).sortedBy { it.order }.map { label(it.opt("query")) }.distinctBy { it.lowercase(Locale.ROOT) }.take(max)
}

/**
 * Topic -> icon key for the chip. The UI maps a key to a Material rounded icon (ui/HomeGridUi.kt `topicIcon`), so the
 * rules stay testable here. Unknown topics get "news".
 */
object TopicIcons {
    const val DEFAULT = "news"

    private val exact = mapOf(
        "programming" to "code", "coding" to "code", "developer" to "code", "github" to "code", "kotlin" to "code", "android" to "android",
        "startups" to "trend", "startup" to "trend", "business" to "business", "stocks" to "chart", "stock market" to "chart",
        "personal finance" to "wallet", "finance" to "wallet", "usd to eur" to "currency",
        "bitcoin" to "bitcoin", "ethereum" to "bitcoin", "crypto" to "bitcoin",
        "ai" to "ai", "artificial intelligence" to "ai",
        "phones" to "phone", "smartphones" to "phone", "gadgets" to "devices", "tech" to "memory", "technology" to "memory",
        "playstation" to "gamepad", "xbox" to "gamepad", "nintendo" to "gamepad", "pc gaming" to "gamepad", "esports" to "gamepad",
        "mobile games" to "gamepad", "gaming" to "gamepad",
        "nba" to "basketball", "lakers" to "basketball", "basketball" to "basketball",
        "nfl" to "football", "american football" to "football",
        "formula 1" to "f1", "f1" to "f1", "motorsport" to "f1",
        "cricket" to "cricket", "tennis" to "tennis", "sports" to "soccer",
        "premier league" to "soccer", "champions league" to "soccer", "arsenal" to "soccer", "man city" to "soccer", "liverpool" to "soccer",
        "real madrid" to "soccer", "barcelona" to "soccer", "chelsea" to "soccer", "football" to "soccer", "football training" to "soccer",
        "music" to "music", "hip hop" to "music", "afrobeats" to "music", "pop" to "music", "k-pop" to "music", "gospel" to "music", "amapiano" to "music",
        "news" to "news", "world" to "world", "local" to "place", "science" to "science", "health" to "health",
        "study" to "school", "exams" to "school", "deadlines" to "school", "maths" to "school", "math" to "school",
        "creators" to "video", "mkbhd" to "video", "mrbeast" to "video", "veritasium" to "video", "mark rober" to "video", "youtube" to "video",
        "fitness" to "fitness", "running" to "run", "gym" to "fitness", "yoga" to "fitness",
        "movies & tv" to "movie", "movies" to "movie", "marvel" to "movie", "anime" to "movie", "netflix" to "movie", "tv" to "movie",
        "weather" to "weather", "climate" to "weather", "travel" to "travel", "food" to "food",
    )

    /** Word hints for topics outside the catalogue ("Arsenal transfer news" -> soccer). */
    private val hints = listOf(
        Regex("\\b(code|coding|program|software|dev|kotlin|python|java|rust)\\b") to "code",
        Regex("\\b(startup|founder|venture|vc|funding)\\b") to "trend",
        Regex("\\b(game|gaming|console|playstation|xbox|nintendo|steam)\\b") to "gamepad",
        Regex("\\b(ai|llm|gpt|machine learning)\\b") to "ai",
        Regex("\\b(bitcoin|crypto|ethereum|btc|eth)\\b") to "bitcoin",
        Regex("\\b(stock|stocks|market|shares|nasdaq|economy)\\b") to "chart",
        Regex("\\b(football|soccer|league|fc|united|city|arsenal|chelsea|liverpool|madrid|barcelona|transfer)\\b") to "soccer",
        Regex("\\b(nba|basketball)\\b") to "basketball",
        Regex("\\b(music|album|song|rap|hip hop|afrobeats)\\b") to "music",
        Regex("\\b(movie|film|series|tv|netflix|anime|marvel)\\b") to "movie",
        Regex("\\b(phone|iphone|samsung|pixel)\\b") to "phone",
        Regex("\\b(science|space|nasa|physics)\\b") to "science",
        Regex("\\b(health|medical|medicine)\\b") to "health",
    )

    fun of(topic: String): String {
        val t = topic.trim().lowercase(Locale.ROOT)
        exact[t]?.let { return it }
        val core = t.removeSuffix(" news").trim()
        exact[core]?.let { return it }
        return hints.firstOrNull { it.first.containsMatchIn(core) }?.second ?: DEFAULT
    }
}

// ---------------------------------------------------------------- weather board

enum class WeatherKind { CLEAR, PARTLY_CLOUDY, OVERCAST, RAIN, SNOW, THUNDER, FOG }

object WeatherBoard {
    /** The board: "Ndola, ZM", "Broken clouds", "24°C", sunset "18:04" (24 h, the place's local time; blank when unknown). */
    data class Board(val place: String, val condition: String, val temp: String, val sunset: String, val kind: WeatherKind, val night: Boolean, val loaded: Boolean)

    /** Plain condition words (Open-Meteo labels, gateway conditions, OpenWeather descriptions) -> the splash / icon variant. */
    fun kind(condition: String): WeatherKind {
        val c = condition.lowercase(Locale.ROOT)
        return when {
            Regex("thunder|storm|lightning").containsMatchIn(c) -> WeatherKind.THUNDER
            Regex("snow|sleet|ice pellets|blizzard|flurr").containsMatchIn(c) -> WeatherKind.SNOW
            Regex("rain|drizzle|shower").containsMatchIn(c) -> WeatherKind.RAIN
            Regex("fog|mist|haze|smoke|dust").containsMatchIn(c) -> WeatherKind.FOG
            Regex("overcast|cloudy skies|mostly cloudy|^cloudy$").containsMatchIn(c) -> WeatherKind.OVERCAST
            Regex("partly|broken|scattered|few clouds|mainly clear|mostly sunny|cloud").containsMatchIn(c) -> WeatherKind.PARTLY_CLOUDY
            Regex("clear|sunny|fair").containsMatchIn(c) -> WeatherKind.CLEAR
            else -> WeatherKind.PARTLY_CLOUDY
        }
    }

    /** Night when the local time is past today's sunset ("HH:mm") or before 06:00; without a sunset, 19:00-06:00. */
    fun night(hour: Int, minute: Int, sunset: String): Boolean {
        val now = hour * 60 + minute
        if (now < 6 * 60) return true
        val ss = hhmm(sunset) ?: return now >= 19 * 60
        return now >= ss
    }

    private fun hhmm(s: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
        return if (h in 0..23 && mm in 0..59) h * 60 + mm else null
    }

    /** "18:04" -> "6:04 PM" on a 12-hour phone, "18:04" on a 24-hour one. Blank stays blank. */
    fun clock(hhmm: String, h24: Boolean): String {
        val m = hhmm(hhmm) ?: return ""
        val h = m / 60; val mm = m % 60
        if (h24) return "%02d:%02d".format(Locale.ROOT, h, mm)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(Locale.ROOT, h12, mm, if (h < 12) "AM" else "PM")
    }

    /** The first visible weather widget's last content, or a placeholder board (variant from the clock only). */
    fun from(widgets: List<Widget>, data: Map<String, WidgetData>, hour: Int, minute: Int): Board {
        val w = widgets.filter { it.type == WidgetTypes.WEATHER && !it.hidden }.minByOrNull { it.order }
        val d = w?.let { data[it.id] }
        if (d == null || d.value.isBlank()) return Board(w?.opt("place")?.ifBlank { w.opt("city") }.orEmpty().ifBlank { "Weather" },
            "", "", "", WeatherKind.PARTLY_CLOUDY, night(hour, minute, ""), loaded = false)
        val cond = d.change.substringBefore(" · ").trim()
        return Board(d.headline.ifBlank { "Weather" }, cond, d.value, d.sunset, kind(cond), night(hour, minute, d.sunset), loaded = true)
    }
}

// ---------------------------------------------------------------- To-do

object TodoFeed {
    data class Task(val title: String, val done: Boolean)

    private val listName = Regex("(?i)\\b(to-?do|todos|tasks?|checklist)\\b")
    private val doneMark = Regex("^\\s*(\\[x]|✓|✔|☑)\\s*", RegexOption.IGNORE_CASE)
    private val openMark = Regex("^\\s*(\\[ ]|☐|-)\\s*")

    /** A list widget Blaze made ("To-do", "Tasks", "Checklist": a custom widget with items). */
    fun isTodo(w: Widget): Boolean = w.type == WidgetTypes.CUSTOM && !w.hidden && (listName.containsMatchIn(w.title) || listName.containsMatchIn(w.category))

    /** Tasks from every to-do list widget, in board order. A line marked "[x]" / "✓" or noted "done" is done. */
    fun tasks(widgets: List<Widget>, data: Map<String, WidgetData>, max: Int = 3): List<Task> =
        widgets.filter(::isTodo).sortedBy { it.order }.flatMap { w -> data[w.id]?.lines.orEmpty() }.mapNotNull { l ->
            val done = doneMark.containsMatchIn(l.title) || l.note.equals("done", true) || l.sub.equals("done", true)
            val t = l.title.replace(doneMark, "").replace(openMark, "").trim()
            if (t.isEmpty()) null else Task(t, done)
        }.take(max)

    /** All tasks (for the count in the corner). */
    fun count(widgets: List<Widget>, data: Map<String, WidgetData>): Int = tasks(widgets, data, Int.MAX_VALUE).size
}

// ---------------------------------------------------------------- Next up

object NextUp {
    enum class Kind { REMINDER, CALENDAR }
    data class Item(val kind: Kind, val title: String, val at: Long)

    /** The earliest upcoming reminder or calendar event (ties go to the reminder). Null when there's nothing ahead. */
    fun pick(reminders: List<Pair<String, Long>>, events: List<Pair<String, Long>>, now: Long): Item? {
        val r = reminders.filter { it.second > now }.minByOrNull { it.second }?.let { Item(Kind.REMINDER, it.first, it.second) }
        val e = events.filter { it.second > now }.minByOrNull { it.second }?.let { Item(Kind.CALENDAR, it.first, it.second) }
        return listOfNotNull(r, e).minWithOrNull(compareBy<Item> { it.at }.thenBy { it.kind.ordinal })
    }

    /** "9:00 AM" today, "Tomorrow 9:00 AM", "Fri 9:00 AM" this week, "3 Nov 9:00 AM" later (24 h: "09:00"). */
    fun whenLabel(at: Long, now: Long, h24: Boolean, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val a = Calendar.getInstance(tz).apply { timeInMillis = at }
        val n = Calendar.getInstance(tz).apply { timeInMillis = now }
        val time = WeatherBoard.clock("%02d:%02d".format(Locale.ROOT, a.get(Calendar.HOUR_OF_DAY), a.get(Calendar.MINUTE)), h24)
        val days = dayIndex(a) - dayIndex(n)
        return when {
            days <= 0 -> time
            days == 1L -> "Tomorrow $time"
            days < 7 -> java.text.SimpleDateFormat("EEE", locale).apply { timeZone = tz }.format(java.util.Date(at)) + " " + time
            else -> java.text.SimpleDateFormat("d MMM", locale).apply { timeZone = tz }.format(java.util.Date(at)) + " " + time
        }
    }

    private fun dayIndex(c: Calendar): Long = (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L
}

// ---------------------------------------------------------------- Quick actions

object QuickActions {
    /** A chip ("Plan") followed by the rest of the sentence ("my day: what's on and what not to forget"); [prompt] goes to chat. */
    data class Action(val verb: String, val rest: String, val prompt: String)

    /** Short tails for the time-of-day starters (SimpleHome.starters), so a chip reads as one sentence. */
    private val tails = mapOf(
        "Plan my day" to "my day: what's on and what not to forget",
        "What did I miss?" to "on what I missed: messages and missed calls",
        "Plan tomorrow" to "tomorrow: what's on and what to get ready",
        "Catch up" to "on messages and anything I still need to answer",
        "Wind down" to "for the night: check my alarm, keep things quiet",
    )
    private val verbs = mapOf("What did I miss?" to "Catch up", "Wind down" to "Wind down")

    /** One starter chip ([label], [prompt]) as an action: "Plan my day" -> "Plan" + "my day: …". */
    fun fromStarter(label: String, prompt: String): Action {
        val verb = verbs[label] ?: label.substringBefore(' ')
        val rest = tails[label] ?: label.substringAfter(' ', "").trim()
        return Action(verb, rest, prompt)
    }

    /** "A", "A and B", "A, B and C". */
    fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /**
     * Up to [max] actions from real app data: the first time-of-day starter (SimpleHome.starters), a catch-up on the
     * tracked topics, and a routine setup suggestion when no morning routine exists yet. Without tracked topics the
     * second starter fills the gap.
     * [starters] are (label, prompt) pairs; [routineHours] the hours of the enabled daily routines.
     */
    fun build(starters: List<Pair<String, String>>, topics: List<String>, routineHours: List<Int>, max: Int = 3): List<Action> {
        val out = ArrayList<Action>()
        starters.firstOrNull()?.let { (l, p) -> out += fromStarter(l, p) }
        if (topics.isNotEmpty()) {
            val names = joinAnd(topics.take(3))
            out += Action("Catch up", "on $names news", "Catch me up on the latest $names news.")
        } else starters.getOrNull(1)?.let { (l, p) -> out += fromStarter(l, p).let { a -> if (out.any { it.verb == a.verb }) a.copy(verb = l) else a } }
        if (routineHours.none { it in 5..10 }) out += Action("Set up", "a morning routine that briefs me at 7",
            "Set up a morning routine: every day at 7:00, brief me on the weather, my day and the news I follow.")
        return out.distinctBy { it.verb + "|" + it.rest }.take(max)
    }
}
