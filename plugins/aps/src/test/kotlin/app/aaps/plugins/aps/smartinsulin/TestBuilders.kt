package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal

/**
 * Test builders for APS data objects.
 * All values in mg/dL (internal AAPS units).
 */
object TestBuilders {

    /**
     * Build a [GlucoseStatus] with just the fields the learner uses.
     * @param glucose    Current BG in mg/dL
     * @param delta      Raw delta (mg/dL per 5-min cycle)
     * @param shortAvgDelta Smoothed short average delta
     * @param longAvgDelta  Long average delta
     * @param date       CGM timestamp in epoch ms
     */
    fun glucoseStatus(
        glucose:        Double  = 100.0,   // 5.5 mmol
        delta:          Double  = 0.0,
        shortAvgDelta:  Double  = 0.0,
        longAvgDelta:   Double  = 0.0,
        date:           Long    = System.currentTimeMillis(),
        noise:          Double  = 0.0
    ) = GlucoseStatus(
        glucose       = glucose,
        noise         = noise,
        delta         = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta  = longAvgDelta,
        date          = date,
        deltaIsfMgdl  = 0.0,
        dura8hAvg     = 0.0,
        dura8h        = 0.0
    )

    /**
     * Build an [IobTotal] array (single element) with just the fields the learner uses.
     * @param iob      Total IOB in units
     * @param activity IOB activity (positive = insulin actively pulling BG down)
     * @param basaliob Basal IOB component
     */
    fun iobArray(
        iob:        Double = 0.0,
        activity:   Double = 0.0,
        basaliob:   Double = 0.0,
        lastBolusTime: Long = 0L
    ): Array<IobTotal> = arrayOf(
        IobTotal(time = System.currentTimeMillis()).apply {
            this.iob          = iob
            this.activity     = activity
            this.basaliob     = basaliob
            this.lastBolusTime = lastBolusTime
        }
    )

    /** Convenience: a BG that's flat at [mgdl] with zero deltas. */
    fun flatBg(mgdl: Double, timestampMs: Long = System.currentTimeMillis()) =
        glucoseStatus(glucose = mgdl, date = timestampMs)

    /** Convenience: a BG dropping at [deltaMgdl] per 5-min cycle. */
    fun fallingBg(mgdl: Double, deltaMgdl: Double, timestampMs: Long = System.currentTimeMillis()) =
        glucoseStatus(glucose = mgdl, delta = deltaMgdl, shortAvgDelta = deltaMgdl, date = timestampMs)

    /** Convenience: a BG rising at [deltaMgdl] per 5-min cycle. */
    fun risingBg(mgdl: Double, deltaMgdl: Double, timestampMs: Long = System.currentTimeMillis()) =
        glucoseStatus(glucose = mgdl, delta = deltaMgdl, shortAvgDelta = deltaMgdl, date = timestampMs)
}
