package com.blaze.assistant.agent.orch

import com.blaze.assistant.agent.LeakedMainLaunches
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** 0.47.0 orchestrator in virtual time: parallel, deps, exclusive lanes, steer, cancel, kill switch, retry, budgets. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    @Before fun drainLeaks() = LeakedMainLaunches.drain()

    private fun ok(r: Orchestrator.Run, text: String = "ok ${r.task.id}") = Orchestrator.Summary(r.task.id, r.task.specialist, Orchestrator.Status.DONE, text)

    @Test fun independentSubtasksRunInParallel() = runTest {
        val o = Orchestrator({ r -> delay(5_000); ok(r) }, { currentTime })
        val plan = listOf(Orchestrator.SubTask("a", "researcher", "x"), Orchestrator.SubTask("b", "coder", "y"), Orchestrator.SubTask("c", "memory", "z"))
        val out = o.run(plan)
        assertEquals(3, out.count { it.status == Orchestrator.Status.DONE })
        assertEquals("parallel: max not sum", 5_000L, currentTime)
    }

    @Test fun dependentsWaitAndSeeInputs() = runTest {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val o = Orchestrator({ r -> delay(1_000); seen += r.task.id + ":" + r.inputs.keys.sorted().joinToString(","); ok(r) }, { currentTime })
        o.run(listOf(Orchestrator.SubTask("find", "researcher", "x"), Orchestrator.SubTask("save", "files", "y", deps = setOf("find"))))
        assertEquals(listOf("find:", "save:find"), seen)
        assertEquals(2_000L, currentTime)
    }

    @Test fun failedDependencySkipsDependent() = runTest {
        val o = Orchestrator({ r -> if (r.task.id == "a") error("site down") else ok(r) }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "researcher", "x", budget = Orchestrator.Budget(3, 10_000, 0)), Orchestrator.SubTask("b", "files", "y", deps = setOf("a"))))
        assertEquals(Orchestrator.Status.FAILED, out[0].status)
        assertEquals(Orchestrator.Status.FAILED, out[1].status)
        assertTrue(out[1].text.startsWith("Skipped"))
    }

    @Test fun exclusivePhoneLaneSerialises() = runTest {
        val o = Orchestrator({ r -> delay(3_000); ok(r) }, { currentTime })
        o.run(listOf(Orchestrator.SubTask("p1", "phone", "x"), Orchestrator.SubTask("p2", "phone", "y"), Orchestrator.SubTask("w", "researcher", "z")))
        assertEquals("two phone parts take turns, research runs beside them", 6_000L, currentTime)
    }

    @Test fun steerReachesRunningWorker() = runTest {
        val got = CompletableDeferred<List<String>>()
        val o = Orchestrator({ r -> delay(2_000); got.complete(r.steering()); ok(r) }, { currentTime })
        launch { delay(1_000); assertTrue(o.steer("a", "also check Friday")) }
        o.run(listOf(Orchestrator.SubTask("a", "researcher", "x")))
        assertEquals(listOf("also check Friday"), got.await())
    }

    @Test fun cancelOneKeepsOthers() = runTest {
        val o = Orchestrator({ r -> delay(10_000); ok(r) }, { currentTime })
        launch { delay(1_000); o.cancel("a") }
        val out = o.run(listOf(Orchestrator.SubTask("a", "researcher", "x"), Orchestrator.SubTask("b", "coder", "y")))
        assertEquals(Orchestrator.Status.CANCELLED, out[0].status)
        assertEquals(Orchestrator.Status.DONE, out[1].status)
    }

    @Test fun killSwitchStopsEverything() = runTest {
        val o = Orchestrator({ r -> delay(60_000); ok(r) }, { currentTime })
        launch { delay(500); o.cancelAll() }
        val out = o.run(listOf(Orchestrator.SubTask("a", "researcher", "x"), Orchestrator.SubTask("b", "coder", "y"), Orchestrator.SubTask("c", "files", "z", deps = setOf("a"))))
        assertTrue(out.all { it.status == Orchestrator.Status.CANCELLED || it.status == Orchestrator.Status.FAILED })
        assertTrue(out.none { it.status == Orchestrator.Status.DONE })
        assertTrue("stopped fast", currentTime < 1_000L)
        assertTrue(o.killed)
    }

    @Test fun retriesAfterFailureWithNote() = runTest {
        var n = 0
        val notes = mutableListOf<String>()
        val o = Orchestrator({ r -> n++; notes += r.steering(); if (r.attempt == 1) error("button not found") else ok(r) }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "phone", "x")))
        assertEquals(Orchestrator.Status.DONE, out[0].status)
        assertEquals(2, out[0].attempts)
        assertEquals(2, n)
        assertTrue(notes.any { it.contains("button not found") })
    }

    @Test fun ownDeadlineTimesOutThenRetries() = runTest {
        val o = Orchestrator({ r -> if (r.attempt == 1) delay(100_000) else delay(1_000); ok(r) }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "memory", "x", budget = Orchestrator.Budget(4, 20_000, 1))))
        assertEquals(Orchestrator.Status.DONE, out[0].status)
        assertEquals(21_000L, currentTime)
    }

    @Test fun slowButWorkingIsNeverCancelled() = runTest {
        // 0.46.5 semantics: the wall time only starts a hedge; a part that keeps making progress is never cut
        val o = Orchestrator({ r -> repeat(10) { delay(5_000); r.step("Reading page ${it + 1}") }; ok(r) }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "memory", "x", budget = Orchestrator.Budget(4, 20_000, 0))))
        assertEquals(Orchestrator.Status.DONE, out[0].status)
        assertEquals(50_000L, currentTime)
    }

    @Test fun slowReliableBeatsFastFailingHedge() = runTest {
        // attempt 1: slow but reliable (progress every 4 s, done at 40 s); the hedge at 20 s fails fast at 22 s -> no error
        val o = Orchestrator({ r -> if (r.attempt == 1) { repeat(10) { delay(4_000); r.step("working") }; ok(r, "slow ok") } else { delay(2_000); error("busy 503") } }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "researcher", "x", budget = Orchestrator.Budget(6, 20_000, 1))))
        assertEquals(Orchestrator.Status.DONE, out[0].status)
        assertEquals("slow ok", out[0].text)
        assertEquals(40_000L, currentTime)
    }

    @Test fun hedgeWinsWhenFirstHangsSilently() = runTest {
        val o = Orchestrator({ r -> if (r.attempt == 1) delay(1_000_000) else delay(3_000); ok(r, "attempt ${r.attempt}") }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "researcher", "x", budget = Orchestrator.Budget(6, 20_000, 1))))
        assertEquals("attempt 2", out[0].text)
        assertEquals(23_000L, currentTime)
    }

    @Test fun exclusiveLaneNeverHedges() = runTest {
        var attempts = 0
        val o = Orchestrator({ r -> attempts++; repeat(6) { delay(10_000); r.step("tap") }; ok(r) }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "phone", "x", budget = Orchestrator.Budget(20, 20_000, 1))))
        assertEquals(Orchestrator.Status.DONE, out[0].status)
        assertEquals("one phone driver at a time", 1, attempts)
    }

    @Test fun timeoutWithoutRetriesIsReported() = runTest {
        val o = Orchestrator({ r -> delay(100_000); ok(r) }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "memory", "x", budget = Orchestrator.Budget(4, 20_000, 0))))
        assertEquals(Orchestrator.Status.TIMED_OUT, out[0].status)
    }

    @Test fun stepBudgetEndsAsPartial() = runTest {
        val o = Orchestrator({ r -> while (r.spend()) delay(10); throw Orchestrator.BudgetSpent("Used all ${r.budget.steps} steps; here is what I have.") }, { currentTime })
        val out = o.run(listOf(Orchestrator.SubTask("a", "researcher", "x")))
        assertEquals(Orchestrator.Status.PARTIAL, out[0].status)
        assertTrue(out[0].text.contains("6 steps"))
    }

    @Test fun eventsFeedThePill() = runTest {
        val steps = StatusLine.Steps()
        steps.begin("find flights to Lusaka and remind me")
        assertEquals("Searching flights", steps.line)
        val o = Orchestrator({ r -> r.step("Reading 3 pages"); delay(1_000); ok(r) }, { currentTime })
        o.run(listOf(Orchestrator.SubTask("a", "researcher", "x"), Orchestrator.SubTask("b", "scheduler", "y"))) { steps.on(it) }
        assertEquals(2, steps.list().size)
        assertTrue(steps.list().all { it.state == StatusLine.State.DONE })
    }
}
