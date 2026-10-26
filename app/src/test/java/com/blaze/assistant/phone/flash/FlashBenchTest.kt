package com.blaze.assistant.phone.flash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the old-vs-new phone benchmark (deterministic) and writes build/flash-bench.md (printed too, for the CI log). */
class FlashBenchTest {
    @Test fun benchmarkRunsAndIsDeterministic() {
        val (o1, n1) = FlashBench.runAll()
        val (o2, n2) = FlashBench.runAll()
        assertEquals(o1, o2); assertEquals(n1, n2)
        assertTrue(FlashBench.tasks.size in 15..20)
        assertEquals(0, n1.count { it.saidDone && !it.success })   // the new engine never claims a task it didn't do
        val md = FlashBench.report(o1, n1)
        println(md)
        val dir = listOf(File("build"), File("app/build")).firstOrNull { it.isDirectory } ?: File("build").also { it.mkdirs() }
        File(dir, "flash-bench.md").writeText(md)
    }
}
