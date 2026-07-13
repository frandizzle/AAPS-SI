package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Pure-math tests for [InsulinActivityCurve]. No Android, no mocks — validates the curve
 * invariants directly so the dosing core can trust the shape before integration.
 */
class InsulinActivityCurveTest {

    // Representative (peak, dia) pairs spanning the learner's clamped range, including the
    // a == 1 singular point of the raw oref0 form (peak/dia ~= 0.293 -> peak 88 / dia 300).
    private val cases = listOf(
        55.0 to 300.0,
        75.0 to 360.0,
        88.0 to 300.0,   // a == 1 in the un-simplified IOB form — must not NaN/Inf
        90.0 to 420.0,
        120.0 to 480.0,
        35.0 to 480.0,
        75.0 to 540.0,   // ~9h — community reference DIA (see BolusCurveAnalysis)
        120.0 to 600.0,  // new DIA_MAX_MINUTES ceiling
        35.0 to 600.0
    )

    @Test fun activity_integrates_to_one_over_dia() {
        for ((peak, dia) in cases) {
            // Trapezoid integration at 1-min resolution.
            var auc = 0.0
            var prev = InsulinActivityCurve.activityFraction(0.0, peak, dia)
            var t = 1.0
            while (t <= dia) {
                val cur = InsulinActivityCurve.activityFraction(t, peak, dia)
                auc += (prev + cur) / 2.0
                prev = cur
                t += 1.0
            }
            assertEquals(1.0, auc, 0.02, "AUC peak=$peak dia=$dia")
        }
    }

    @Test fun activity_peaks_at_peak() {
        for ((peak, dia) in cases) {
            var bestT = 0.0
            var bestV = -1.0
            var t = 1.0
            while (t < dia) {
                val v = InsulinActivityCurve.activityFraction(t, peak, dia)
                if (v > bestV) { bestV = v; bestT = t }
                t += 1.0
            }
            // Within 2 minutes of the requested peak.
            assertTrue(kotlin.math.abs(bestT - peak) <= 2.0, "argmax=$bestT peak=$peak dia=$dia")
        }
    }

    @Test fun activity_zero_outside_window() {
        for ((peak, dia) in cases) {
            assertEquals(0.0, InsulinActivityCurve.activityFraction(0.0, peak, dia), 1e-12)
            assertEquals(0.0, InsulinActivityCurve.activityFraction(-5.0, peak, dia), 1e-12)
            assertEquals(0.0, InsulinActivityCurve.activityFraction(dia, peak, dia), 1e-12)
            assertEquals(0.0, InsulinActivityCurve.activityFraction(dia + 30.0, peak, dia), 1e-12)
        }
    }

    @Test fun iob_boundaries_and_monotonicity() {
        for ((peak, dia) in cases) {
            assertEquals(1.0, InsulinActivityCurve.iobFraction(0.0, peak, dia), 1e-9, "iob(0) peak=$peak dia=$dia")
            assertEquals(0.0, InsulinActivityCurve.iobFraction(dia, peak, dia), 1e-6, "iob(dia) peak=$peak dia=$dia")
            var prev = 1.0
            var t = 1.0
            while (t <= dia) {
                val iob = InsulinActivityCurve.iobFraction(t, peak, dia)
                assertTrue(iob <= prev + 1e-9, "IOB increased at t=$t peak=$peak dia=$dia ($iob > $prev)")
                assertTrue(iob.isFinite(), "IOB not finite at t=$t peak=$peak dia=$dia")
                prev = iob
                t += 1.0
            }
        }
    }

    @Test fun activity_equals_negative_iob_derivative() {
        // activity(t) should equal -d(iob)/dt. Central difference vs the closed-form activity.
        for ((peak, dia) in cases) {
            var t = 10.0
            while (t < dia - 10.0) {
                val dIob = (InsulinActivityCurve.iobFraction(t + 1.0, peak, dia) -
                    InsulinActivityCurve.iobFraction(t - 1.0, peak, dia)) / 2.0
                val act = InsulinActivityCurve.activityFraction(t, peak, dia)
                assertEquals(-dIob, act, 1e-4, "activity vs -dIOB/dt at t=$t peak=$peak dia=$dia")
                t += 15.0
            }
        }
    }

    @Test fun effective_age_round_trips() {
        for ((peak, dia) in cases) {
            for (known in listOf(15.0, 45.0, 90.0)) {
                val act = InsulinActivityCurve.activityFraction(known, peak, dia) // unit bolus
                val iob = InsulinActivityCurve.iobFraction(known, peak, dia)
                val recovered = InsulinActivityCurve.effectiveAgeMinutes(act, iob, peak, dia)
                assertTrue(kotlin.math.abs(recovered - known) <= 2.0,
                           "round-trip known=$known recovered=$recovered peak=$peak dia=$dia")
            }
        }
    }

    @Test fun effective_age_degenerate_inputs() {
        assertEquals(0.0, InsulinActivityCurve.effectiveAgeMinutes(0.0, 2.0, 75.0, 360.0), 1e-9)
        assertEquals(0.0, InsulinActivityCurve.effectiveAgeMinutes(0.01, 0.0, 75.0, 360.0), 1e-9)
        assertFalse(InsulinActivityCurve.effectiveAgeMinutes(0.5, 1.0, 75.0, 360.0).isNaN())
    }
}