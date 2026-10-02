package app.aaps.plugins.aps.smartInsulin

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Catches the top of a rise that a smoothing filter has not caught yet.
 *
 * A smoothing filter such as UKF estimates a trend, and that trend carries momentum: when the raw
 * CGM rise stops, the smoothed delta keeps saying "still rising fast" for a reading or two. Seen on a
 * G7: raw steps +1.7, +0.9, +0.2 mmol while UKF still said +1.2 then +0.8, and the loop kept giving
 * SMBs into the peak. A G6 was smoothed by the Dexcom app before AAPS saw it, so this lag was not
 * there.
 *
 * The rule looks at the raw 5-minute steps:
 *  - One reading where the rise slows sharply (the step drops by [SHARP_SLOWDOWN_MGDL] or more):
 *    use halfway between the raw step and the smoothed delta. One reading can be noise, so it only
 *    goes half way, and never below a flat line.
 *  - The slowdown is still there on a later reading, and the rise has not picked back up: use the
 *    raw step.
 *  - The rise picks back up: no change, the smoothed delta is used again.
 *
 * The result is never above the smoothed delta, so this can only ever take insulin away. It does
 * nothing when the smoothed delta is not rising, when the readings are not 5 minutes apart, or when
 * there are too few of them.
 */
object RiseTurnGuard {

    enum class Level { NONE, HALF, RAW }

    data class Result(
        /** The delta to use, mg/dL per 5 min. Equal to the smoothed delta when [level] is NONE. */
        val deltaMgdl: Double,
        val level: Level,
        /** The newest raw step, mg/dL per 5 min. NaN when the raw readings could not be used. */
        val rawStepMgdl: Double
    )

    /** A step this much smaller than the one before it is a sharp slowdown (0.3 mmol). */
    const val SHARP_SLOWDOWN_MGDL = 5.4
    /** A step this much bigger than the slowed one means the rise has picked back up (0.1 mmol). */
    const val RESUME_MARGIN_MGDL = 1.8
    /** How many steps back a slowdown is still remembered. */
    const val MEMORY_STEPS = 3
    private const val MIN_GAP_MS = 4 * 60_000L
    private const val MAX_GAP_MS = 6 * 60_000L

    /**
     * @param smoothedDeltaMgdl the delta the loop would otherwise use (mg/dL per 5 min)
     * @param rawMgdl raw readings, newest first (calibrated if a calibration is active)
     * @param timesMs their timestamps, newest first
     */
    fun apply(smoothedDeltaMgdl: Double, rawMgdl: List<Double>, timesMs: List<Long>): Result {
        val none = { step: Double -> Result(smoothedDeltaMgdl, Level.NONE, step) }
        if (smoothedDeltaMgdl <= 0.0) return none(Double.NaN)

        // Steps between readings that are 5 minutes apart, newest first. Stops at the first gap, so
        // a missed reading never makes two steps out of one.
        val n = min(rawMgdl.size, timesMs.size)
        val steps = ArrayList<Double>()
        for (i in 0 until min(n - 1, MEMORY_STEPS + 1)) {
            val gap = timesMs[i] - timesMs[i + 1]
            if (gap !in MIN_GAP_MS..MAX_GAP_MS || rawMgdl[i] < 39.0 || rawMgdl[i + 1] < 39.0) break
            steps.add(rawMgdl[i] - rawMgdl[i + 1])
        }
        if (steps.size < 2) return none(steps.firstOrNull() ?: Double.NaN)
        val newest = steps[0]

        // Sharp slowdowns in memory, newest first, as long as nothing after them has climbed back
        // up. Only a slowdown on the newest step alone is a single reading; one further back has
        // already been confirmed by the readings since.
        val slowdowns = ArrayList<Int>()
        for (k in 0 until min(MEMORY_STEPS, steps.size - 1)) {
            val resumed = (0 until k).any { j -> steps[j] > steps[k] + RESUME_MARGIN_MGDL }
            if (resumed) break
            if (steps[k + 1] - steps[k] >= SHARP_SLOWDOWN_MGDL) slowdowns.add(k)
        }
        if (slowdowns.isEmpty()) return none(newest)

        val (level, wanted) =
            if (slowdowns.any { it > 0 }) Level.RAW to newest
            else Level.HALF to (max(newest, 0.0) + smoothedDeltaMgdl) / 2.0
        val delta = min(smoothedDeltaMgdl, wanted)
        return if (abs(delta - smoothedDeltaMgdl) < 0.05) none(newest)
        else Result(delta, level, newest)
    }
}
