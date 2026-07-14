package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import app.aaps.core.interfaces.smartInsulin.MealMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import app.aaps.core.interfaces.aps.GlucoseStatus

/**
 * Tests for the ISF<->basal cross-nudge added to [CircadianLearner]: when one quantity's signal
 * fires, the other gets a smaller (CROSS_NUDGE_FRACTION = 0.15) nudge in the same direction, as a
 * lower-confidence co-movement prior. Also verifies the mutual-exclusion gating that stops a
 * cross-nudge from double-writing on top of a same-cycle primary write to the same quantity.
 *
 * All scenarios start from a completely fresh [CircadianLearner] (mult=1.0, confidence=0 in every
 * bucket). On a fresh bucket, DayOfWeekCircadianState.updated() writes an IDENTICAL value to both
 * the day bucket and global (both start at the same default), so get() returns exactly
 * `1.0 + adjustment * alpha` with no day/global blending distortion — this lets the tests assert
 * exact values instead of loose bounds.
 */
class CircadianLearnerCrossNudgeTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() {
        learner = CircadianLearner(logger, FakePreferences())
    }

    private fun glucoseStatus(glucose: Double, shortAvgDelta: Double): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(glucose)
            whenever(this.shortAvgDelta).thenReturn(shortAvgDelta)
            whenever(this.delta).thenReturn(shortAvgDelta)
            whenever(this.date).thenReturn(BASE_MS)
        }

    private fun iobArray(iob: Double, activity: Double): Array<IobTotal> =
        arrayOf(IobTotal(time = 0, iob = iob, activity = activity, basaliob = 0.0))

    @Test
    fun `main ISF learner cross-nudges basal by exactly 15 percent of its own movement`() {
        // activity=0.02 -> expectedDelta = -0.02*50*5 = -5.0. actualDelta=-1.0 (insulin weaker
        // than expected) -> deviation=+4.0 -> normDeviation clamps to its +0.5 ceiling.
        // bg(140) >= target(100) so the below-target guard doesn't zero it out.
        learner.update(
            glucoseStatus    = glucoseStatus(glucose = 140.0, shortAvgDelta = -1.0),
            iobArray         = iobArray(iob = 0.0, activity = 0.02),
            mealMode         = MealMode.FASTING,
            cobG             = 0.0,
            profileIsfMgdl   = 50.0,
            targetMgdl       = 100.0,
            aggressiveness   = 1.0,   // neutral -> applyAggrNudge is a no-op this cycle
            hour = 0, dow = 0, nowMs = BASE_MS
        )

        val isfAfter   = learner.isfMultiplier(0, 0)
        val basalAfter = learner.basalMultiplier(0, 0)

        // Fresh state: alpha = ISF_ALPHA(0.04) * 1.5 = 0.06 (zero confidence hits the ceiling).
        // isfAfter = 1.0 + normDeviation(0.5) * alpha(0.06) = 1.03 exactly.
        assertEquals(1.03, isfAfter, 1e-9, "Main ISF learner should move isf by normDeviation*alpha exactly")
        assertTrue(basalAfter > 1.0, "Basal should be cross-nudged in the same (upward) direction, got $basalAfter")

        val isfMove   = isfAfter - 1.0
        val basalMove = basalAfter - 1.0
        assertEquals(0.15, basalMove / isfMove, 1e-6,
                     "Basal cross-nudge should be exactly CROSS_NUDGE_FRACTION (15%) of the ISF movement, " +
                         "got ratio=${basalMove / isfMove}")
    }

    @Test
    fun `Signal 0 cross-nudges ISF by exactly 15 percent of its own movement`() {
        // activity=0.0 (below MIN_ACTIVITY) -> main ISF learner's own gate skips it, so
        // isfLearningActive is false without needing anything extra. totalIob=0.0 satisfies
        // Signal 0's basalIob gate. shortAvgDelta=+3.0 with ~zero activity means the delta is
        // entirely "unexplained" -> attributed to basal. bg(140) >= target(100) so the
        // below-target guard doesn't block the upward direction.
        learner.update(
            glucoseStatus    = glucoseStatus(glucose = 140.0, shortAvgDelta = 3.0),
            iobArray         = iobArray(iob = 0.0, activity = 0.0),
            mealMode         = MealMode.FASTING,
            cobG             = 0.0,
            profileIsfMgdl   = 50.0,
            targetMgdl       = 100.0,
            aggressiveness   = 1.0,
            hour = 0, dow = 0, nowMs = BASE_MS
        )

        val isfAfter   = learner.isfMultiplier(0, 0)
        val basalAfter = learner.basalMultiplier(0, 0)

        // Fresh state: alpha = BASAL_ALPHA(0.06) * 1.5 = 0.09. normAdj = (3.0/5.0).coerceIn(-1,0.5)
        // clamps to +0.5. basalAfter = 1.0 + 0.5*0.09 = 1.045 exactly.
        assertEquals(1.045, basalAfter, 1e-9, "Signal 0 should move basal by normAdj*alpha exactly")
        assertTrue(isfAfter > 1.0, "ISF should be cross-nudged in the same (upward) direction, got $isfAfter")

        val basalMove = basalAfter - 1.0
        val isfMove   = isfAfter - 1.0
        assertEquals(0.15, isfMove / basalMove, 1e-6,
                     "ISF cross-nudge should be exactly CROSS_NUDGE_FRACTION (15%) of the basal movement, " +
                         "got ratio=${isfMove / basalMove}")
    }

    @Test
    fun `drift signal's cross-nudge to ISF is suppressed when the main ISF learner fires the same cycle`() {
        // Build a 12-sample drift window: bg rising 0.3 mg/dL per 5-min cycle (~3.6 mg/dL/hr) —
        // above the drift signal's own 2.0 mg/dL/hr noise floor, but deliberately kept below
        // predTrim's (Signal 3) 9.0 mg/dL dead band for its 60-min-ahead projection throughout
        // the whole window (worst case at the last preliminary sample: projected error ≈6.6),
        // so predTrim stays dormant and doesn't fire ahead of the drift signal and contaminate
        // the baseline. ~0 activity/totalIob and shortAvgDelta=0 keep Signal 0 from writing
        // (delta stays under its own noise gate) during these preliminary cycles. On the 12th
        // (trigger) cycle, ALSO raise activity so the main ISF learner fires in the SAME cycle —
        // if the gating works, isfState should end up reflecting ONLY the main learner's own
        // write, with no additional contribution from drift's (correctly suppressed) cross-nudge.
        var t = BASE_MS
        for (i in 0..10) {
            learner.update(
                glucoseStatus  = glucoseStatus(glucose = 100.0 + i * 0.3, shortAvgDelta = 0.0),
                iobArray       = iobArray(iob = 0.0, activity = 0.0),
                mealMode       = MealMode.FASTING,
                cobG           = 0.0,
                profileIsfMgdl = 50.0,
                targetMgdl     = 100.0,
                aggressiveness = 1.0,
                hour = 0, dow = 0, nowMs = t
            )
            t += CYCLE_MS
        }

        // Sanity: nothing should have touched isf/basal yet — drift needs a 12th sample to fire,
        // predTrim stays under its dead band the whole time (see comment above), and Signal 0
        // never writes (shortAvgDelta=0.0 is below its noise gate every cycle above).
        assertEquals(1.0, learner.isfMultiplier(0, 0), 1e-9, "ISF should be untouched before the trigger cycle")
        assertEquals(1.0, learner.basalMultiplier(0, 0), 1e-9, "Basal should be untouched before the trigger cycle")

        // Trigger cycle: 12th sample completes the drift window AND activity is high enough to
        // fire the main ISF learner in the same cycle.
        learner.update(
            glucoseStatus  = glucoseStatus(glucose = 103.3, shortAvgDelta = -1.0),
            iobArray       = iobArray(iob = 0.0, activity = 0.02),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = 100.0,
            aggressiveness = 1.0,
            hour = 0, dow = 0, nowMs = t
        )

        val isfAfter   = learner.isfMultiplier(0, 0)
        val basalAfter = learner.basalMultiplier(0, 0)

        // isfState entered this cycle untouched (confidence still 0), so the main learner's own
        // write is the exact same formula as the first test: 1.0 + 0.5*0.06 = 1.03. If drift's
        // cross-nudge had NOT been suppressed, this would be measurably higher (drift's own
        // adjustment is also upward here).
        assertEquals(1.03, isfAfter, 1e-9,
                     "ISF should reflect ONLY the main learner's own write — drift's cross-nudge must be " +
                         "suppressed while the main learner is active this cycle, got $isfAfter")

        // Drift's PRIMARY write to basal should still have fired normally — only the CROSS-NUDGE
        // (to the other quantity) is gated, not drift's own effect on its own quantity.
        assertTrue(basalAfter > 1.0, "Drift's own basal write should still fire even though its cross-nudge is gated, got $basalAfter")
    }
}
