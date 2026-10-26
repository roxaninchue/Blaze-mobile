package com.blaze.assistant.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.4 hotfix benchmark: time to the first visible token on mocked brains, 0.46.3 vs 0.46.4.
 *
 * "Before" is the 0.46.3 timeout model, written out from the 0.46.3 code (no race existed to run): a short main-chat turn
 * went as a Cloud run when the on-phone brain wasn't ready (cap 75 s, CloudRuns/TurnDeadline.CLOUD_STEP_MS), then the
 * brains one after another inside the 150 s step budget (TurnDeadline.STEP_MS): the on-phone brain first whenever it was
 * installed (start under the setup lock, then 20 s first chunk per model with thinking counted as a chunk, 100 s total,
 * 4 models), the gateway (Nous, then Blaze Cloud) with no first-token cap at all (120 s read timeout reset by every
 * keep-alive, 3 tries, backoff 1 s / 3 s, rate limits retried).
 * "After" runs the real [HedgeRace] in virtual time with the 0.46.4 limits and the [BrainRace.order] lists (asserted in
 * BrainRaceTest). Virtual time, deterministic.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrainBenchTest {
    /** Missing-Main launch failures from earlier test classes surface at the first runTest here (CI run 40374778854); drop only those ([LeakedMainLaunches]). */
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()
    enum class B { PHONE, NOUS, CLOUD }

    /** first = ms to the first word (null = never); think = streams hidden thinking until then; err = 429 after that many ms. */
    data class Brain(val first: Long? = null, val think: Boolean = false, val err: Long? = null)
    data class Fx(val name: String, val task: Boolean, val phoneReady: Boolean, val phoneWarm: Boolean, val setupLockMs: Long,
                  val runQueued: Boolean, val brains: Map<B, Brain>)

    private val chat = HedgeRace.Limits(8_000, 3_000, 30_000, 8_000, 30_000)
    private val taskL = HedgeRace.Limits(20_000, 6_000, 30_000, 45_000, 60_000)

    val fixtures = listOf(
        Fx("healthy (phone warm 4 s, Nous 2.5 s, Cloud 1.5 s)", false, true, true, 0, false,
            mapOf(B.PHONE to Brain(4_000), B.NOUS to Brain(2_500), B.CLOUD to Brain(1_500))),
        Fx("Rocky 19:07: setup running, run queued, Nous silent, Cloud 2 s", false, false, false, 0, true,
            mapOf(B.NOUS to Brain(null), B.CLOUD to Brain(2_000))),
        Fx("on-phone brain hangs (no output), Nous 3 s", false, true, true, 0, false,
            mapOf(B.PHONE to Brain(null), B.NOUS to Brain(3_000), B.CLOUD to Brain(2_000))),
        Fx("on-phone brain hangs, task turn", true, true, true, 0, false,
            mapOf(B.PHONE to Brain(null), B.NOUS to Brain(3_000), B.CLOUD to Brain(2_000))),
        Fx("setup in progress (start lock held 20 s), Nous 2.5 s", false, true, false, 20_000, false,
            mapOf(B.PHONE to Brain(4_000), B.NOUS to Brain(2_500), B.CLOUD to Brain(2_000))),
        Fx("slow first token: phone reasoning 15 s, task", true, true, true, 0, false,
            mapOf(B.PHONE to Brain(15_000, think = true), B.NOUS to Brain(2_500), B.CLOUD to Brain(2_000))),
        Fx("rate limit: phone + Nous 429, Cloud 2 s", false, true, true, 0, false,
            mapOf(B.PHONE to Brain(err = 500), B.NOUS to Brain(err = 300), B.CLOUD to Brain(2_000))),
        Fx("reasoning forever on the phone, chat", false, true, true, 0, false,
            mapOf(B.PHONE to Brain(null, think = true), B.NOUS to Brain(2_500), B.CLOUD to Brain(2_000))),
    )

    /** 0.46.3 timeout model; null = no first word inside the 150 s step (the turn then retried/errored up to its 5 min end). */
    fun before(fx: Fx): Long? {
        var t = 0L
        val stepEnd: Long
        if (!fx.phoneReady) {
            // Cloud run (Blaze Cloud lineup on the gateway), up to 75 s, then direct with what is left of 150 s
            if (!fx.runQueued) return fx.brains[B.CLOUD]?.first
            t += 75_000
        }
        stepEnd = 150_000
        val plan = (if (fx.phoneReady) listOf(B.PHONE) else emptyList()) + listOf(B.NOUS, B.CLOUD)
        for (b in plan) {
            val br = fx.brains[b] ?: continue
            if (b == B.PHONE) {
                t += fx.setupLockMs + (if (fx.phoneWarm || fx.setupLockMs > 0) 0 else 5_000)
                if (br.err != null) { t += br.err; continue }   // free limit: thrown at once
                val f = br.first
                if (f != null && (f <= 20_000 || br.think)) return (t + f).takeIf { it < stepEnd }   // thinking counts as a chunk
                t += if (br.think) 100_000 else 80_000   // thinking: until TOTAL_MS; silent: 4 models × 20 s
                continue
            }
            if (br.err != null) { t += br.err + 1_000 + br.err + 3_000 + br.err; continue }   // 3 tries, 429 retried
            val f = br.first ?: return null   // keep-alives reset the read timeout: waits to the step end
            return (t + f).takeIf { it < stepEnd }
        }
        return null
    }

    private suspend fun TestScope.play(br: Brain, a: HedgeRace.Attempt<B>): String {
        val t0 = testScheduler.currentTime
        if (br.err != null) { delay(br.err); throw Exception("429 Free usage limit reached") }
        val f = br.first
        while (f == null || testScheduler.currentTime - t0 < f) { delay(if (f == null) 1_000 else minOf(1_000L, f - (testScheduler.currentTime - t0))); if (br.think) a.think() }
        a.visible(); delay(300); return "${a.step} answer"
    }

    /** 0.46.4: the BrainRace order (warm phone only; chat = fastest-first by default guesses Nous 3.0, Cloud 3.2, phone 6.0 s). */
    fun order(fx: Fx): List<B> {
        val l = (if (fx.phoneWarm) listOf(B.PHONE) else emptyList()) + listOf(B.NOUS, B.CLOUD)
        if (fx.task) return l
        val guess = mapOf(B.NOUS to 3_000L, B.CLOUD to 3_200L, B.PHONE to 6_000L)
        return l.sortedBy { guess.getValue(it) }
    }

    @Test fun firstTokenBeforeAndAfter() = runTest {
        val rows = StringBuilder("| fixture | kind | 0.46.3 first token | 0.46.4 first token | 0.46.4 brain |\n|---|---|---|---|---|\n")
        val afters = ArrayList<Long>(); val befores = ArrayList<Long?>()
        for (fx in fixtures) {
            val o = HedgeRace.run(order(fx), if (fx.task) taskL else chat, true, call = { a -> play(fx.brains.getValue(a.step), a) }, now = { testScheduler.currentTime })
            val b = before(fx)
            afters += o.firstVisibleMs; befores += b
            rows.append("| ${fx.name} | ${if (fx.task) "task" else "chat"} | ${b?.let { "%.1f s".format(it / 1000.0) } ?: ">150 s (no reply; turn ran to its limit)"} | ${"%.1f s".format(o.firstVisibleMs / 1000.0)} | ${o.step}${if (o.hedged) " (hedged)" else ""} |\n")
            // chat: a first word within ~5 s whenever a brain answers; tasks within the 6 s hedge + the next brain's first word
            if (!fx.task) assertTrue("${fx.name}: ${o.firstVisibleMs}", o.firstVisibleMs <= 5_500)
            else assertTrue("${fx.name}: ${o.firstVisibleMs}", o.firstVisibleMs <= 9_500)
        }
        val med = { l: List<Long> -> l.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 } }
        val beforeMed = med(befores.map { it ?: 150_000L })
        rows.append("\nMedian first token: 0.46.3 ${"%.1f".format(beforeMed / 1000.0)} s (no-reply counted as 150 s) vs 0.46.4 ${"%.1f".format(med(afters) / 1000.0)} s; worst 0.46.4 ${"%.1f".format(afters.max() / 1000.0)} s.\n")
        val out = rows.toString()
        println(out)
        try { File("build").mkdirs(); File("build/brain-bench.md").writeText(out) } catch (_: Exception) { }
    }
}
