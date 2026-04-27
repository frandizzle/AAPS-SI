package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal

/**
 * Test builders for APS data objects.
 * GlucoseStatus is an interface — we use anonymous objects.
 * All BG values in mg/dL (internal AAPS units).
 */
object TestBuilders {

    fun glucoseStatus(
        glucose:       Double = 100.0,
        delta:         Double = 0.0,
        shortAvgDelta: Double = 0.0,
        longAvgDelta:  Double = 0.0,
        date:          Long   = System.currentTimeMillis(),
        noise:         Double = 0.0
    ): GlucoseStatus = object : GlucoseStatus {
        override val glucose:       Double = glucose
        override val noise:         Double = noise
        override val delta:         Double = delta
        override val shortAvgDelta: Double = shortAvgDelta
        override val longAvgDelta:  Double = longAvgDelta
        override val date:          Long   = date
    }

    /**
     * Build a single-element IobTotal array.
     * IobTotal is typically a data/open class — we construct it then set fields.
     * If your IobTotal is final/sealed, replace with a mock.
     */
    fun iobArray(
        iob:           Double = 0.0,
        activity:      Double = 0.0,
        basaliob:      Double = 0.0,
        lastBolusTime: Long   = 0L
    ): Array<IobTotal> {
        val entry = IobTotal(time = System.currentTimeMillis())
        entry.iob          = iob
        entry.activity     = activity
        entry.basaliob     = basaliob
        entry.lastBolusTime = lastBolusTime
        return arrayOf(entry)
    }

    fun flatBg(mgdl: Double, timestampMs: Long = System.currentTimeMillis()) =
        glucoseStatus(glucose = mgdl, date = timestampMs)

    fun fallingBg(mgdl: Double, deltaMgdl: Double, timestampMs: Long = System.currentTimeMillis()) =
        glucoseStatus(glucose = mgdl, delta = deltaMgdl, shortAvgDelta = deltaMgdl, date = timestampMs)

    fun risingBg(mgdl: Double, deltaMgdl: Double, timestampMs: Long = System.currentTimeMillis()) =
        glucoseStatus(glucose = mgdl, delta = deltaMgdl, shortAvgDelta = deltaMgdl, date = timestampMs)
}