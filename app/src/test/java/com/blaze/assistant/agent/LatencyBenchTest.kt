package com.blaze.assistant.agent

import com.blaze.assistant.agent.orch.StatusLine
import com.blaze.assistant.agent.orch.Typeahead
import com.blaze.assistant.agent.orch.WorkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 0.47.0 latency bench (spec §4, §6.2): tap→tick, tap→first pill line, tap→first token, on fixtures.
 *
 * What is MEASURED here (real code):
 *  - tap→tick (new, short text): wall clock of [Delivery.optimistic] + [Delivery.Tracker.optimistic] (in-memory store),
 *    median / p95 over [N] runs on this JVM. The tick is then drawn on the next frame (16.7 ms at 60 Hz, stated, not measured).
 *  - tap→tick for a Cloud-run send (0.46.4 and 0.47.0 long messages): the real [Delivery.deliver] loop against a fake
 *    gateway in virtual time, per network fixture (RTT / errors are the fixture's, the loop + backoff are the code's).
 *  - tap→first pill (new): wall clock of [Typeahead.take] (cold: classified on Send; warm: classified while typing) +
 *    [WorkStatus.begin], median / p95 over [N] runs.
 *  - tap→first token: the real [HedgeRace] in virtual time on the [BrainBenchTest] brain fixtures (unchanged by 0.47.0).
 * What is HAND-MODELLED (labelled so in the report): 0.46.4's first pill (it had no local line; its pill came with the first
 * step event, so it is modelled as no earlier than the first token), and 0.46.4's local-turn tick (raised at turn pick-up on
 * the device; not reproducible on the JVM, so it is reported as "device only", never as a number).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LatencyBenchTest {
    @Before fun drainLeaks() = LeakedMainLaunches.drain()

    private val N = 2_000
    private fun stats(ns: List<Long>): Pair<Double, Double> { val s = ns.sorted(); return s[s.size / 2] / 1e6 to s[(s.size * 95) / 100] / 1e6 }
    private fun memTracker(): Delivery.Tracker { val kv = HashMap<String, String>(); return Delivery.Tracker({ kv[it] }, { k, v -> kv[k] = v }) }

    private val messages = listOf("what's up??", "set an alarm for 6:30", "find me flights to Cape Town on Friday", "open spotify",
        "text Mum I'm on my way", "summarise the article about solar", "what's on my calendar tomorrow?", "remind me to buy milk at 5")

    /** tap→tick, new path: measured. */
    private fun tickNew(): Pair<Double, Double> {
        val t = memTracker()
        repeat(200) { i -> if (Delivery.optimistic(messages[i % messages.size], 0)) t.optimistic("w$i") }   // warm-up (JIT)
        val ns = (0 until N).map { i ->
            val gid = "g$i"; val text = messages[i % messages.size]
            val t0 = System.nanoTime()
            if (Delivery.optimistic(text, 0)) t.optimistic(gid)
            val dt = System.nanoTime() - t0
            assertEquals(DeliveryState.SENT, t.state(gid)); dt
        }
        return stats(ns)
    }

    /** tap→first pill line, new path: measured (cold = classified on Send, warm = classified while typing). */
    private fun pillNew(warm: Boolean): Pair<Double, Double> {
        val ta = Typeahead()
        repeat(200) { i -> ta.classify(messages[i % messages.size]); WorkStatus.begin("bench", messages[i % messages.size]) }
        val ns = (0 until N).map { i ->
            val text = messages[i % messages.size] + if (warm) "" else " #$i"   // cold: a text never classified before
            if (warm) ta.classify(text)
            val t0 = System.nanoTime()
            val pre = ta.take(text)
            if (!pre.smallTalk) WorkStatus.begin("bench", text)
            val dt = System.nanoTime() - t0
            assertTrue(WorkStatus.current.value?.line?.isNotBlank() == true); dt
        }
        WorkStatus.end("bench")
        return stats(ns)
    }

    data class Net(val name: String, val answers: List<Pair<Long, Any>>)   // (ms until the answer, HTTP code or IOException)

    private val nets = listOf(
        Net("good 4G (RTT 250 ms)", listOf(250L to 200)),
        Net("slow 3G (RTT 1.2 s)", listOf(1_200L to 200)),
        Net("one drop, then ok (timeout 3 s + backoff 1 s + 400 ms)", listOf(3_000L to IOException("timeout"), 400L to 200)),
        Net("gateway 408 once (300 ms) then ok", listOf(300L to 408, 300L to 200)),
    )

    /** tap→tick on a Cloud-run send: the real deliver loop in virtual time. */
    private suspend fun TestScope.tickCloud(n: Net): Long {
        val t0 = testScheduler.currentTime
        var k = 0
        var at = -1L
        Delivery.deliver(post = { val (ms, r) = n.answers[minOf(k++, n.answers.size - 1)]; delay(ms); if (r is IOException) throw r; r as Int },
            codeOf = { it }, retryAfterOf = { null }, close = { }, wait = { delay(it) }, now = { testScheduler.currentTime },
            onEvent = { if (it == Delivery.Ev.POSTED) at = testScheduler.currentTime - t0 })
        return at
    }

    private suspend fun TestScope.play(br: BrainBenchTest.Brain, a: HedgeRace.Attempt<BrainBenchTest.B>): String {
        val t0 = testScheduler.currentTime
        if (br.err != null) { delay(br.err); throw Exception("429 Free usage limit reached") }
        val f = br.first
        while (f == null || testScheduler.currentTime - t0 < f) { delay(if (f == null) 1_000 else minOf(1_000L, f - (testScheduler.currentTime - t0))); if (br.think) a.think() }
        a.visible(); delay(300); return "${a.step} answer"
    }

    @Test fun optimisticTickIsHonest() {
        val t = memTracker()
        assertTrue(Delivery.optimistic("hi", 0)); assertTrue(!Delivery.optimistic("hi", 1)); assertTrue(!Delivery.optimistic("x".repeat(281), 0))
        t.optimistic("a"); assertEquals(DeliveryState.SENT, t.state("a"))
        val cid = t.cidForSend("a"); assertEquals("same first send keeps its id", Delivery.cidFor("a"), cid); assertEquals(DeliveryState.SENT, t.state("a"))
        t.apply(listOf("a"), Delivery.Ev.REJECTED); assertEquals("a refusal after an optimistic tick says Not sent", DeliveryState.NOT_SENT, t.state("a"))
        t.optimistic("b"); t.apply(listOf("b"), Delivery.Ev.LOCAL_PICKUP); t.apply(listOf("b"), Delivery.Ev.STOPPED)
        assertEquals("confirmed by pick-up: a later Stop doesn't undo it", DeliveryState.SENT, t.state("b"))
        t.optimistic("c"); t.apply(listOf("c"), Delivery.Ev.READ); assertEquals(DeliveryState.READ, t.state("c"))
    }

    @Test fun bench() = runTest {
        val (tickMed, tickP95) = tickNew()
        val (coldMed, coldP95) = pillNew(false)
        val (warmMed, warmP95) = pillNew(true)
        val cloud = nets.map { it to tickCloud(it) }
        val bb = BrainBenchTest()
        val tokens = bb.fixtures.map { fx ->
            val o = HedgeRace.run(bb.order(fx), if (fx.task) HedgeRace.Limits(20_000, 6_000, 30_000, 45_000, 60_000) else HedgeRace.Limits(8_000, 3_000, 30_000, 8_000, 30_000), true,
                call = { a -> play(fx.brains.getValue(a.step), a) }, now = { testScheduler.currentTime })
            fx to o.firstVisibleMs
        }
        val md = buildString {
            append("# Blaze 0.47.0 latency bench (fixtures)\n\n")
            append("## tap → tick\n| path | result | how |\n|---|---|---|\n")
            append("| 0.47.0 short text (≤ ${Delivery.OPTIMISTIC_MAX_CHARS} chars, no attachment) | ${"%.3f".format(tickMed)} ms median, ${"%.3f".format(tickP95)} ms p95 (+ next frame, 16.7 ms at 60 Hz) | MEASURED: Delivery.optimistic + Tracker.optimistic, $N runs, this JVM |\n")
            cloud.forEach { (n, ms) -> append("| 0.46.4 Cloud-run send / 0.47.0 long message or attachment: ${n.name} | ${"%.2f".format(ms / 1000.0)} s | MEASURED in virtual time: real Delivery.deliver loop, fixture network |\n") }
            append("| 0.46.4 short local turn | device only | NOT MEASURED: ticked at turn pick-up on the phone (after turn setup); no JVM number is claimed |\n")
            append("\n## tap → first pill line\n| path | result | how |\n|---|---|---|\n")
            append("| 0.47.0 classified on Send (cold) | ${"%.3f".format(coldMed)} ms median, ${"%.3f".format(coldP95)} ms p95 | MEASURED: Typeahead.take + WorkStatus.begin, $N runs (spec gate ≤ 300 ms) |\n")
            append("| 0.47.0 classified while typing (warm) | ${"%.3f".format(warmMed)} ms median, ${"%.3f".format(warmP95)} ms p95 | MEASURED, same |\n")
            append("| 0.46.4 | ≥ first token (see below) | HAND-MODELLED: no local first line existed; the pill came with the first step event |\n")
            append("\n## tap → first token (unchanged by 0.47.0; real HedgeRace, virtual time, BrainBenchTest fixtures)\n| fixture | kind | first token |\n|---|---|---|\n")
            tokens.forEach { (fx, ms) -> append("| ${fx.name} | ${if (fx.task) "task" else "chat"} | ${"%.1f".format(ms / 1000.0)} s |\n") }
            val s = tokens.map { it.second }.sorted()
            append("\nMedian first token ${"%.1f".format(s[(s.size - 1) / 2] / 1000.0)} s, worst ${"%.1f".format(s.last() / 1000.0)} s (MEASURED in virtual time on fixtures; not a phone measurement).\n")
        }
        println(md)
        try { File("build").mkdirs(); File("build/latency-bench.md").writeText(md) } catch (_: Exception) { }
        assertTrue("first pill line within 300 ms (p95, cold): $coldP95 ms", coldP95 < 300.0)
        assertTrue("tick on the tap within one frame (p95): $tickP95 ms", tickP95 < 16.0)
    }
}
