package com.blaze.assistant.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.46.0 Stage motion tiers: cheap on 2-3 GB phones and with animations off; springs + morph only on strong phones. */
class StagePerfTest {
    @Test fun tiers() {
        assertEquals(PerfTier.LOW, StagePerf.tier(true, 8_000, 120f, 1f))
        assertEquals(PerfTier.LOW, StagePerf.tier(false, 2_048, 60f, 1f))
        assertEquals(PerfTier.LOW, StagePerf.tier(false, 3_000, 90f, 1f))
        assertEquals(PerfTier.LOW, StagePerf.tier(false, 12_000, 120f, 0f))   // "remove animations"
        assertEquals(PerfTier.LOW, StagePerf.tier(false, 8_000, 120f, 1f, cores = 4))
        assertEquals(PerfTier.MID, StagePerf.tier(false, 4_096, 120f, 1f))
        assertEquals(PerfTier.MID, StagePerf.tier(false, 8_000, 60f, 1f))
        assertEquals(PerfTier.HIGH, StagePerf.tier(false, 8_000, 120f, 1f))
    }

    @Test fun motionSpecs() {
        val low = StagePerf.motion(PerfTier.LOW)
        assertFalse(low.morph); assertFalse(low.springs); assertFalse(low.particles); assertFalse(low.gradients)
        assertTrue(low.enterMs <= 150)
        val high = StagePerf.motion(PerfTier.HIGH)
        assertTrue(high.morph && high.springs)
        assertTrue(high.enterMs <= 320)   // short, never a slow show
        assertEquals(0, StagePerf.motion(PerfTier.HIGH, 0f).enterMs)
    }
}
