package com.blaze.assistant.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 0.46.3 lookup fast lane + answer first (report: "Blaze takes forever when asked to find something online").
 *
 * A lookup question goes search → the top 2-3 pages fetched IN PARALLEL (each with its own timeout) → one call to the
 * fastest capable model → the answer, with its source pills, as soon as 2 or more solid sources agree. Then, off the turn,
 * a bounded background check reads a few more pages; if that changes the answer, the same message is updated in place with
 * a small "Updated" marker. Escalates to the full browser only when the facts sit behind a sign-in or an interaction, or
 * when fetches failed twice. At most [Config.maxSteps] steps. The live line says plainly what's happening, with counts
 * ("Reading 3 pages"), never a metaphor. Pure (LookupLaneTest); the network and the model are interfaces.
 */
object LookupLane {
    data class Hit(val title: String, val url: String, val snippet: String = "")
    data class Page(val url: String, val title: String, val text: String, val ok: Boolean, val error: String = "")
    /** [supporting] = urls of the pages that back the answer; [needsBrowser] = the model says the facts need interaction. */
    data class Answer(val text: String, val supporting: List<String>, val needsBrowser: Boolean = false)

    interface Net {
        suspend fun search(q: String): List<Hit>
        suspend fun fetch(url: String): Page
    }
    interface Brain { suspend fun answer(q: String, pages: List<Page>): Answer }

    class Config(val searchTimeoutMs: Long = 8_000, val fetchTimeoutMs: Long = 6_000, val firstPages: Int = 3, val verifyPages: Int = 3,
                 val verifyMs: Long = 40_000, val maxSteps: Int = 5, val agree: Int = 2, val minChars: Int = 200)

    sealed class Event {
        data class Progress(val line: String, val step: Int, val sources: Int) : Event()
        data class First(val answer: Answer, val pages: List<Page>, val ms: Long) : Event()
        data class Escalate(val reason: String, val ms: Long) : Event()
    }

    /** Where the first answer left off, for the background check. */
    class State(val q: String, val hits: List<Hit>, val used: Set<String>, val pages: List<Page>, val answer: Answer)

    data class Update(val answer: Answer, val pages: List<Page>, val changed: Boolean)

    // ---------------- routing ----------------
    private val ask = Regex("""(?i)^\s*(?:what|who|whom|whose|when|where|which|why|how (?:much|many|old|long|tall|big|far|fast|do|does|did|is|are|to)|is|are|was|were|does|did|do|can|find(?: out)?|look up|lookup|search(?: for)?|google|check|tell me|latest|current|price of|cost of|news)\b""")
    private val personal = Regex("""(?i)\b(?:my (?:phone|files?|photos?|messages?|texts?|calendar|alarms?|contacts?|notes?|music|songs?|battery|storage|settings|apps?|email|inbox)|remind|alarm|timer|text (?:him|her|them|mom|dad)|call |open |play |turn (?:on|off)|set (?:a|an|the)|send |screenshot|on (?:my|this) phone|you (?:remember|know about me))\b""")
    /** Things Blaze answers without the web (itself, the clock, the weather card, sums, a chat), so they never take the lane. */
    private val notWeb = Regex("""(?i)\b(?:you|your|yourself|blaze|halo|time is it|what time|today'?s date|what day is|weather|forecast|how are|joke|poem|story|translate|calculate|meaning of life)\b|^[\d\s+\-*/().x=?]+$|\bremember\b""")
    private val needsInteraction = Regex("""(?i)\b(?:book|buy|order|sign (?:me )?(?:in|up)|log ?in|fill (?:in|out)|apply|checkout|reserve|subscribe|my account|download)\b""")

    /** A question the fast lane should take (a fact to look up on the open web). */
    fun isLookup(text: String): Boolean {
        val t = text.trim()
        if (t.length < 6 || t.length > 240 || t.contains('\n')) return false
        if (personal.containsMatchIn(t) || needsInteraction.containsMatchIn(t) || notWeb.containsMatchIn(t)) return false
        if (t.startsWith("http", true) || t.startsWith("@")) return false
        return ask.containsMatchIn(t)
    }

    private val wall = Regex("""(?i)sign in to (?:continue|view|see)|log ?in to (?:continue|view|see)|please (?:enable|turn on) javascript|verify you are (?:a )?human|are you a robot|captcha|access denied|subscribe to (?:continue|read)|create an account to""")

    /** A page whose facts sit behind a sign-in, a bot check or a script wall. */
    fun walled(p: Page): Boolean = p.ok && wall.containsMatchIn(p.text.take(4_000))
    fun solid(p: Page, cfg: Config): Boolean = p.ok && !walled(p) && p.text.length >= cfg.minChars

    // ---------------- progress lines (plain, with counts; no metaphors) ----------------
    fun searching() = "Searching the web"
    fun reading(n: Int, more: Boolean = false) = "Reading $n ${if (more) "more " else ""}page${if (n == 1) "" else "s"}"
    fun writing(n: Int) = "Writing the answer from $n source${if (n == 1) "" else "s"}"
    fun checking(n: Int) = "Checking $n more source${if (n == 1) "" else "s"}"

    /** The live line as shown: "Reading 3 pages · step 2 · 3 sources". */
    fun line(p: Event.Progress): String = p.line + " · step ${p.step}" + if (p.sources > 0) " · ${p.sources} source${if (p.sources == 1) "" else "s"}" else ""

    suspend fun fetchAll(net: Net, hits: List<Hit>, cfg: Config): List<Page> = coroutineScope {
        hits.map { h -> async { withTimeoutOrNull(cfg.fetchTimeoutMs) { try { net.fetch(h.url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Page(h.url, h.title, "", false, e.message.orEmpty()) } }
            ?: Page(h.url, h.title, "", false, "timed out after ${cfg.fetchTimeoutMs / 1000} s") } }.awaitAll()
    }

    /**
     * The first answer. Emits progress, then exactly one [Event.First] or [Event.Escalate]; returns the state for
     * [verify] (null when it escalated).
     */
    suspend fun first(q: String, net: Net, brain: Brain, cfg: Config, now: () -> Long, emit: suspend (Event) -> Unit): State? {
        val t0 = now()
        var step = 1
        emit(Event.Progress(searching(), step, 0))
        val hits = (withTimeoutOrNull(cfg.searchTimeoutMs) { try { net.search(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } } ?: emptyList())
            .distinctBy { ReplySources.norm(it.url) }
        if (hits.isEmpty()) { emit(Event.Escalate("search failed", now() - t0)); return null }
        val used = LinkedHashSet<String>()
        val pages = ArrayList<Page>()
        var failedRounds = 0
        var walls = 0
        var next = 0
        while (pages.count { solid(it, cfg) } < cfg.agree && next < hits.size && step < cfg.maxSteps - 1 && failedRounds < 2) {
            val batch = hits.drop(next).take(if (pages.isEmpty()) cfg.firstPages else cfg.agree - pages.count { solid(it, cfg) } + 1)
            next += batch.size
            step++
            emit(Event.Progress(reading(batch.size, more = pages.isNotEmpty()), step, pages.count { solid(it, cfg) }))
            val got = fetchAll(net, batch, cfg)
            got.forEach { used.add(it.url) }
            walls += got.count { walled(it) }
            if (got.none { solid(it, cfg) }) failedRounds++
            pages += got.filter { solid(it, cfg) }
        }
        if (pages.isEmpty()) {
            emit(Event.Escalate(if (walls > 0) "the pages need a sign-in or a click" else "the pages wouldn't load (twice)", now() - t0))
            return null
        }
        step++
        emit(Event.Progress(writing(pages.size), step, pages.size))
        var a = brain.answer(q, pages)
        // fewer than [Config.agree] pages back it: one more small batch before answering (within the step cap)
        if (!a.needsBrowser && a.supporting.size < cfg.agree && next < hits.size && step < cfg.maxSteps) {
            val batch = hits.drop(next).take(cfg.agree)
            next += batch.size; step++
            emit(Event.Progress(reading(batch.size, more = true), step, a.supporting.size))
            val got = fetchAll(net, batch, cfg).filter { solid(it, cfg) }
            got.forEach { used.add(it.url) }
            if (got.isNotEmpty()) { pages += got; a = brain.answer(q, pages) }
        }
        if (a.needsBrowser) { emit(Event.Escalate("the facts need an interaction on the page", now() - t0)); return null }
        emit(Event.First(a, pages, now() - t0))
        return State(q, hits, used, pages, a)
    }

    /**
     * The background check after the answer (bounded by [Config.verifyMs]): a few more pages, one more model call; [Update]
     * says whether the answer changed in substance. Null = nothing more to read, or out of time.
     */
    suspend fun verify(s: State, net: Net, brain: Brain, cfg: Config, emit: suspend (Event) -> Unit = {}): Update? = withTimeoutOrNull(cfg.verifyMs) {
        val more = s.hits.filter { it.url !in s.used }.take(cfg.verifyPages)
        if (more.isEmpty()) return@withTimeoutOrNull null
        emit(Event.Progress(checking(more.size), 0, s.pages.size))
        val got = fetchAll(net, more, cfg).filter { solid(it, cfg) }
        if (got.isEmpty()) return@withTimeoutOrNull Update(s.answer, s.pages, false)
        val all = s.pages + got
        val a2 = brain.answer(s.q, all)
        Update(a2, all, differs(s.answer.text, a2.text))
    }

    private val num = Regex("""\d[\d,.:/]*""")
    /** A change of substance: different numbers / dates, or a mostly different first sentence. */
    fun differs(a: String, b: String): Boolean {
        val na = num.findAll(a).map { it.value.trimEnd('.', ',') }.toSet(); val nb = num.findAll(b).map { it.value.trimEnd('.', ',') }.toSet()
        if (na != nb) return true
        fun words(s: String) = s.substringBefore(". ").lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        // what the shorter answer says is mostly missing from the other one
        return wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size) < 0.5
    }

    const val GO_DEEPER = "Go deeper"
    const val UPDATED = "Updated"

    /** The model's instructions: answer only from the pages, name which back it, say when a page needs interaction. */
    const val SYSTEM = "Answer the question only from the numbered pages below. Reply with one JSON object: " +
        "{\"answer\":\"2-5 plain sentences, the direct answer first\",\"sources\":[page numbers that state it],\"needs_browser\":false}. " +
        "Set needs_browser true only if the facts are behind a sign-in, a form or a click. If the pages disagree, say so in the answer. No links in the text."

    fun prompt(q: String, pages: List<Page>): String = buildString {
        append("Question: ").append(q).append("\n\n")
        pages.forEachIndexed { i, p -> append("Page ").append(i + 1).append(": ").append(p.title).append(" (").append(p.url).append(")\n").append(p.text.take(3_500)).append("\n\n") }
    }

    /** Reads the model's JSON reply into an [Answer] (lenient; plain text = the answer backed by every page). */
    fun parse(raw: String, pages: List<Page>): Answer {
        val o = com.blaze.assistant.phone.flash.FlashParse.json(raw)
        if (o == null) return Answer(raw.trim(), pages.map { it.url })
        val idx = o.optJSONArray("sources")
        val sup = if (idx == null) pages.map { it.url } else (0 until idx.length()).mapNotNull { pages.getOrNull(idx.optInt(it) - 1)?.url }
        return Answer(o.optString("answer").trim(), sup.distinct(), o.optBoolean("needs_browser"))
    }

    /** Search results text (Web.search: "1. title\n   url\n   snippet") into hits. */
    fun hitsOf(result: String): List<Hit> {
        val out = ArrayList<Hit>()
        val lines = result.lines()
        var i = 0
        while (i < lines.size) {
            val m = Regex("""^\s*\d+\.\s+(.*)$""").find(lines[i])
            val url = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (m != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                out.add(Hit(m.groupValues[1].trim(), url, lines.getOrNull(i + 2)?.trim().orEmpty())); i += 3
            } else i++
        }
        return out
    }
}
