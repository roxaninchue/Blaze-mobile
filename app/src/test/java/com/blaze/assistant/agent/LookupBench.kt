package com.blaze.assistant.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * 0.46.3 lookup benchmark: 10 lookup prompts on mocked network fixtures (search and page latencies, failing / slow /
 * sign-in pages), virtual time (kotlinx-coroutines-test), the same fixed [MODEL_MS] per model call in both engines.
 * OLD = the agent loop as it ran these: a model call picks web_search, then fetch_page one page per model call (in turn,
 * 20 s fetch timeout), then a model call answers; the answer shows only at the end. NEW = LookupLane: search, the top
 * pages in parallel with a 6 s timeout each, one model call, answer first; the background check is timed separately.
 * Not measured on a phone or with live sites.
 */
object LookupBench {
    const val MODEL_MS = 2_000L
    const val BROWSER_MS = 25_000L

    class Pg(val url: String, val ms: Long, val text: String, val ok: Boolean = true)
    class Fx(val id: String, val q: String, val fact: String, val searchMs: Long, val pages: List<Pg>)

    private fun page(i: Int, ms: Long, fact: String?, ok: Boolean = true, wall: Boolean = false) =
        Pg("https://site$i.example/p", ms, if (wall) "Sign in to continue reading this article. " + "x ".repeat(200)
            else "Some context about the topic. ".repeat(10) + (fact?.let { "The answer is $it. " } ?: "Unrelated details. "), ok)

    val fixtures = listOf(
        Fx("capital", "What is the capital of Zambia?", "Lusaka", 900, listOf(page(1, 700, "Lusaka"), page(2, 900, "Lusaka"), page(3, 1200, "Lusaka"), page(4, 800, "Lusaka"))),
        Fx("height", "How tall is Mount Kilimanjaro?", "5,895 m", 1100, listOf(page(1, 1500, "5,895 m"), page(2, 600, "5,895 m"), page(3, 2500, "5,895 m"), page(4, 700, "5,895 m"))),
        Fx("slow_page", "When was the Kariba Dam built?", "1959", 1000, listOf(page(1, 15_000, "1959"), page(2, 900, "1959"), page(3, 1100, "1959"), page(4, 900, "1959"))),
        Fx("one_fails", "Who wrote Things Fall Apart?", "Chinua Achebe", 800, listOf(page(1, 400, null, ok = false), page(2, 1000, "Chinua Achebe"), page(3, 1300, "Chinua Achebe"), page(4, 800, "Chinua Achebe"))),
        Fx("price", "How much is a Raspberry Pi 5?", "60 USD", 1200, listOf(page(1, 1800, "60 USD"), page(2, 1400, "60 USD"), page(3, 900, "60 USD"), page(4, 1000, "60 USD"))),
        Fx("update", "What is the population of Ndola?", "627,503", 1000, listOf(page(1, 900, "627,503"), page(2, 1000, "627,503"), page(3, 1100, "627,503"), page(4, 900, "627,503"))),
        Fx("timeouts", "What does ISO 8601 define?", "date and time formats", 900, listOf(page(1, 30_000, "date and time formats"), page(2, 30_000, "date and time formats"), page(3, 1000, "date and time formats"), page(4, 800, "date and time formats"))),
        Fx("fails_twice", "Which team won the 2012 Africa Cup of Nations?", "Zambia", 900, listOf(page(1, 500, null, ok = false), page(2, 500, null, ok = false), page(3, 500, null, ok = false), page(4, 500, null, ok = false), page(5, 500, null, ok = false), page(6, 500, null, ok = false))),
        Fx("login_wall", "What is in my library account's due list?", "n/a", 900, listOf(page(1, 800, null, wall = true), page(2, 800, null, wall = true), page(3, 800, null, wall = true), page(4, 800, null, wall = true), page(5, 800, null, wall = true), page(6, 800, null, wall = true))),
        Fx("speed", "What is the speed of light?", "299,792,458 m/s", 700, listOf(page(1, 600, "299,792,458 m/s"), page(2, 700, "299,792,458 m/s"), page(3, 800, "299,792,458 m/s"), page(4, 900, "299,792,458 m/s"))),
    )

    class Net(val fx: Fx) : LookupLane.Net {
        override suspend fun search(q: String): List<LookupLane.Hit> { delay(fx.searchMs); return fx.pages.mapIndexed { i, p -> LookupLane.Hit("Result ${i + 1}", p.url) } }
        override suspend fun fetch(url: String): LookupLane.Page {
            val p = fx.pages.first { it.url == url }
            delay(p.ms)
            return if (!p.ok) LookupLane.Page(url, url, "", false, "HTTP 500") else LookupLane.Page(url, "Page", p.text, true)
        }
    }

    /** The mock model: answers with the fixture's fact, backed by the pages that state it. */
    class Brain(val fx: Fx, val calls: IntArray) : LookupLane.Brain {
        override suspend fun answer(q: String, pages: List<LookupLane.Page>): LookupLane.Answer {
            delay(MODEL_MS); calls[0]++
            val sup = pages.filter { it.text.contains(fx.fact) }.map { it.url }
            return LookupLane.Answer(if (sup.isEmpty()) "The pages don't say." else "It is ${fx.fact}.", sup)
        }
    }

    data class R(val id: String, val engine: String, val ok: Boolean, val ttfaMs: Long, val totalMs: Long, val calls: Int, val escalated: Boolean)

    fun runNew(fx: Fx): R {
        var r: R? = null
        runTest {
            val calls = intArrayOf(0)
            val net = Net(fx); val brain = Brain(fx, calls)
            var esc = false
            val st = LookupLane.first(fx.q, net, brain, LookupLane.Config(), { currentTime }) { if (it is LookupLane.Event.Escalate) esc = true }
            val ttfa: Long
            val ok: Boolean
            if (st == null) { delay(BROWSER_MS); ttfa = currentTime; ok = fx.id == "fails_twice" || fx.id == "login_wall" }   // the browser takes it
            else { ttfa = currentTime; ok = st.answer.text.contains(fx.fact) && st.answer.supporting.size >= 2 }
            if (st != null) LookupLane.verify(st, net, brain, LookupLane.Config())
            r = R(fx.id, "new", ok, ttfa, currentTime, calls[0], esc)
        }
        return r!!
    }

    /** The old loop: web_search, then fetch_page per model call (top pages in order), then the answer. */
    fun runOld(fx: Fx): R {
        var r: R? = null
        runTest {
            val net = Net(fx)
            var calls = 0
            suspend fun model() { delay(MODEL_MS); calls++ }
            model()                       // decides web_search
            val hits = net.search(fx.q)
            var good = 0; var tried = 0; var walls = 0
            for (h in hits) {
                if (good >= 2 || tried >= 6) break
                model()                   // decides fetch_page
                tried++
                val p = kotlinx.coroutines.withTimeoutOrNull(20_000) { net.fetch(h.url) }
                if (p != null && p.ok && p.text.contains("Sign in to continue")) walls++
                else if (p != null && p.ok && p.text.contains(fx.fact)) good++
            }
            val esc = good == 0
            if (esc) { model(); delay(BROWSER_MS) } else model()   // the answer (or: to the browser)
            r = R(fx.id, "old", if (esc) fx.id == "fails_twice" || fx.id == "login_wall" else good >= 1, currentTime, currentTime, calls, esc)
        }
        return r!!
    }

    fun median(xs: List<Long>): Double { val s = xs.sorted(); return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }

    fun report(old: List<R>, new: List<R>): String = buildString {
        fun s(ms: Double) = String.format(java.util.Locale.US, "%.1f s", ms / 1000)
        append("## Web lookups: old loop vs fast lane + answer first\n\n")
        append("${old.size} lookup prompts on mocked network fixtures (virtual time; ${MODEL_MS} ms per model call in both; a browser hand-off costs a fixed ${BROWSER_MS / 1000} s in both). Not measured on a phone or live sites.\n\n")
        append("| metric | old | new |\n|---|---|---|\n")
        append("| answered correctly / handed to the browser correctly | ${old.count { it.ok }}/${old.size} | ${new.count { it.ok }}/${new.size} |\n")
        append("| median time to first answer | ${s(median(old.map { it.ttfaMs }))} | ${s(median(new.map { it.ttfaMs }))} |\n")
        append("| median total time (new: incl. background check) | ${s(median(old.map { it.totalMs }))} | ${s(median(new.map { it.totalMs }))} |\n")
        append("| model calls (mean) | ${String.format(java.util.Locale.US, "%.1f", old.map { it.calls }.average())} | ${String.format(java.util.Locale.US, "%.1f", new.map { it.calls }.average())} |\n\n")
        append("| prompt | old first answer | new first answer | old total | new total | escalated (old/new) |\n|---|---|---|---|---|---|\n")
        old.indices.forEach { i -> append("| ${old[i].id} | ${s(old[i].ttfaMs.toDouble())} | ${s(new[i].ttfaMs.toDouble())} | ${s(old[i].totalMs.toDouble())} | ${s(new[i].totalMs.toDouble())} | ${old[i].escalated}/${new[i].escalated} |\n") }
    }

    fun runAll(): Pair<List<R>, List<R>> = fixtures.map { runOld(it) } to fixtures.map { runNew(it) }
}
