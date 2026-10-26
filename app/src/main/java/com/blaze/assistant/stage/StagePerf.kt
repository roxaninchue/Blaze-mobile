package com.blaze.assistant.stage

/**
 * 0.46.0 Stage motion tiers, so the Stage feels smooth on every phone (incl. 2-3 GB RAM ones). Only transform + alpha ever
 * animate (graphicsLayer), never blur, shadows or layout per frame.
 *  - LOW: low-RAM device, <= 3 GB, or the user's "remove animations": cross-fades only (short), no morph, static gradients.
 *  - MID: 3-6 GB or a 60 Hz screen: short tweens + light springs.
 *  - HIGH: > 6 GB and a fast screen: springs and the hero <-> widget morph (scale from the widget's spot).
 * Pure; unit-tested in StagePerfTest. [StageDevice] reads the real values on the phone.
 */
enum class PerfTier { LOW, MID, HIGH }

data class MotionSpec(val enterMs: Int, val exitMs: Int, val morph: Boolean, val springs: Boolean, val gradients: Boolean, val particles: Boolean)

object StagePerf {
    fun tier(lowRamDevice: Boolean, totalRamMb: Long, refreshHz: Float, animatorScale: Float, cores: Int = 8): PerfTier = when {
        animatorScale.isNaN() || animatorScale <= 0f -> PerfTier.LOW
        lowRamDevice || (totalRamMb in 1..3_200) || cores in 1..4 -> PerfTier.LOW
        totalRamMb in 1..6_200 || refreshHz < 75f -> PerfTier.MID
        else -> PerfTier.HIGH
    }

    fun motion(t: PerfTier, animatorScale: Float = 1f): MotionSpec {
        val k = if (animatorScale.isNaN() || animatorScale <= 0f) 0f else animatorScale.coerceAtMost(2f)
        return when (t) {
            PerfTier.LOW -> MotionSpec((120 * k).toInt(), (90 * k).toInt(), morph = false, springs = false, gradients = false, particles = false)
            PerfTier.MID -> MotionSpec((220 * k).toInt(), (160 * k).toInt(), morph = true, springs = false, gradients = true, particles = false)
            PerfTier.HIGH -> MotionSpec((300 * k).toInt(), (200 * k).toInt(), morph = true, springs = true, gradients = true, particles = true)
        }
    }
}
