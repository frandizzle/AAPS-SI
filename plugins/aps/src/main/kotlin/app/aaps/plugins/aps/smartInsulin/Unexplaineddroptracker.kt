package app.aaps.plugins.aps.smartInsulin

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling measure of how much BG has dropped BEYOND what insulin activity can account for.
 *
 * Exists to stop the episode-outcome learners from blaming ISF for lows that insulin didn't
 * cause. The two cases have distinguishable signatures:
 *
 *   - ISF genuinely too strong → BG falls roughly AS MUCH as `activity × ISF` predicts. The
 *     model was right, there was simply too much insulin. Unexplained drop ≈ 0.
 *   - Exercise (or missed/overestimated food) → BG falls MUCH FASTER than insulin can explain.
 *     Strongly negative unexplained drop.
 *
 * Deliberately independent of [ActivityMonitor]: step/HR detection needs a watch or phone on
 * the body, and the walk-with-IOB case most often happens exactly when neither is. This works
 * from the BG and IOB trace alone, and as a bonus catches non-exercise causes of unexplained
 * drops too — all of which are equally wrong to attribute to ISF.
 *
 * The metric is noise-robust by construction: summing per-cycle deltas telescopes to
 * (BG_now − BG_windowStart), so CGM noise is bounded by two readings rather than accumulating
 * across the window.
 */
@Singleton
class UnexplainedDropTracker @Inject constructor() {

    private val window = ArrayDeque<Sample>()

    private data class Sample(val timeMs: Long, val unexplainedMgdl: Double)

    companion object {
        private const val WINDOW_MS = 45 * 60_000L
        /** Unexplained drop (mg/dL, negative) over the window past which a low is treated as
         *  not-an-ISF-signal. -27 mg/dL ≈ -1.5 mmol — well beyond CGM noise, comfortably
         *  inside what a brisk walk produces. */
        private const val SUSPECT_THRESHOLD_MGDL = -27.0
    }

    /** Call once per loop cycle, before the learners run. */
    fun onCycle(deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, nowMs: Long) {
        if (isfMgdl <= 0.0) return
        val expectedDelta = -activityPerMin * isfMgdl * 5.0
        window.addLast(Sample(nowMs, deltaMgdl - expectedDelta))
        while (window.isNotEmpty() && nowMs - window.first().timeMs > WINDOW_MS) window.removeFirst()
    }

    /** Net unexplained BG movement over the window (mg/dL). Negative = falling faster than
     *  insulin accounts for. */
    val unexplainedDropMgdl: Double get() = window.sumOf { it.unexplainedMgdl }

    /** True when the recent drop is too large to blame on insulin — a low right now is more
     *  likely exercise (or missing food) than an ISF that's set too strong. */
    val exerciseSuspected: Boolean get() = unexplainedDropMgdl <= SUSPECT_THRESHOLD_MGDL

    fun reset() = window.clear()
}
