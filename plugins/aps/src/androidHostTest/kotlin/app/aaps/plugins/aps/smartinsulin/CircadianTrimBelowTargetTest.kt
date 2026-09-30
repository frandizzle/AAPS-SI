package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The FuelTrim "sustained high" branch, and the live-reading gate on it.
 *
 * aboveBand is decided on a trailing mean over up to 120 min. The overshoot guard covers a mean
 * that stays high while the live reading crashes a full dead band under target, but between target
 * and target - dead band there was nothing: BG down at 5.2 against a 5.5 target, flat, still read
 * as "sustained high" and had insulin added on the strength of a 5.9 mean behind it.
 *
 * Target here is 99 mg/dL (5.5 mmol) and TRIM_DEAD_BAND_MGDL is 5.4 (0.3 mmol), so the bands are:
 * mean must exceed 104.4 to be "high", and the old overshoot guard needed the reading under 93.6.
 *
 * The bg == target boundary itself is not covered here and cannot usefully be: holding BG at target
 * long enough to re-arm the trim clock (readyToReassess waits a full trim window) also washes the
 * trailing mean back down below the band, so the combination the gate decides — high mean, live
 * reading exactly at target — cannot be sustained for a test to observe.
 */
class CircadianTrimBelowTargetTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val DOW      = 3
    private val TARGET   = 99.0

    @BeforeEach
    fun setUp() { learner = CircadianLearner(logger, FakePreferences()) }

    private fun glucoseStatus(glucose: Double, delta: Double = 0.0): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(glucose)
            whenever(this.shortAvgDelta).thenReturn(delta)
            whenever(this.delta).thenReturn(delta)
            whenever(this.date).thenReturn(BASE_MS)
        }

    /** Clean fasting cycles at [bg]; returns the next timestamp. */
    private fun run(bg: Double, count: Int, startMs: Long, hour: Int = 7): Long {
        var t = startMs
        repeat(count) {
            learner.update(
                glucoseStatus  = glucoseStatus(bg),
                iobArray       = arrayOf(IobTotal(time = 0, iob = -0.5, activity = 0.0, basaliob = -0.5)),
                mealMode       = MealMode.FASTING,
                cobG           = 0.0,
                profileIsfMgdl = 50.0,
                targetMgdl     = TARGET,
                hour = hour, dow = DOW, minute = 30, nowMs = t
            )
            t += CYCLE_MS
        }
        return t
    }

    /**
     * Whether the trim actually ADDED insulin, measured on what it wrote rather than on the status
     * string: that string is only rewritten when a branch fires, so when the gate correctly stops
     * a trim it keeps reporting the previous episode. Basal up and ISF multiplier up both mean
     * more insulin (dosingISF = profileISF / mult).
     */
    private fun basal(hour: Int = 7) = learner.basalMultiplier(hour, DOW, minute = 30)
    private fun isf(hour: Int = 7)   = learner.isfMultiplier(hour, DOW, minute = 30)

    @Test
    fun `a high trailing mean does not add insulin once BG is back under target`() {
        // The reported case: an hour above target, then down to 5.28mmol — under the 5.5 target but
        // not a full dead band under it, so the old overshoot guard never engaged.
        var t = BASE_MS
        t = run(bg = 117.0, count = 20, startMs = t)   // 6.5mmol — builds a high mean
        val basBefore = basal(); val isfBefore = isf()
        run(bg = 95.0, count = 6, startMs = t)          // 5.28mmol — below target
        assertFalse(basal() > basBefore + 1e-9, "basal raised below target: $basBefore -> ${basal()}")
        assertFalse(isf() > isfBefore + 1e-9, "ISF strengthened below target: $isfBefore -> ${isf()}")
    }

    @Test
    fun `a genuinely sustained high still adds insulin`() {
        // The gate must not disarm the branch it is guarding — BG above target keeps trimming up.
        var t = BASE_MS
        t = run(bg = 117.0, count = 20, startMs = t)
        val basBefore = basal()
        run(bg = 117.0, count = 30, startMs = t)        // past readyToReassess, so a trim can fire
        assertTrue(basal() > basBefore + 1e-9, "sustained high should still trim up: $basBefore -> ${basal()}")
    }

    @Test
    fun `a full crash through target still reaches the cut branch`() {
        // Unchanged behaviour: below target by a full dead band is an overshoot crash, which cuts.
        var t = BASE_MS
        t = run(bg = 117.0, count = 20, startMs = t)
        val basBefore = basal()
        run(bg = 88.0, count = 6, startMs = t)          // 4.9mmol
        assertFalse(basal() > basBefore + 1e-9, "a crash must never add insulin: $basBefore -> ${basal()}")
    }
}
