package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-math tests for [BolusCurveAnalysis.solveDiaFromObservedFraction] — the magnitude-based
 * DIA estimator that replaced trusting raw elapsed-time-to-nadir as DIA (see that function's doc
 * for the rationale: nadir timing alone systematically undershoots true DIA).
 */
class BolusCurveAnalysisDiaSolveTest {

    @Test fun `more of the dose used by a given time implies a shorter DIA`() {
        // Same elapsed time and peak, well past peak so the curves have genuinely diverged by
        // dia — a higher observed fraction-used should never solve to a LONGER dia.
        val diaLowUsage = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 200.0, peakMinutes = 75.0, fractionUsed = 0.3
        )
        val diaHighUsage = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 200.0, peakMinutes = 75.0, fractionUsed = 0.7
        )
        assertTrue(diaHighUsage <= diaLowUsage,
                   "Higher fraction-used should imply DIA <= lower fraction-used's DIA, got high=$diaHighUsage low=$diaLowUsage")
    }

    @Test fun `solved DIA round-trips a known DIA's fraction-used`() {
        // Derive fractionUsed FROM a known DIA via the same curve model, then solve backward —
        // guarantees the target is achievable within the search range (unlike guessing an
        // arbitrary fraction), mirroring InsulinActivityCurveTest's effective_age_round_trips.
        val elapsed = 180.0
        val peak    = 65.0
        val trueDia = 420.0
        val fractionUsed = 1.0 - InsulinActivityCurve.iobFraction(elapsed, peak, trueDia)

        val solvedDia = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = elapsed, peakMinutes = peak, fractionUsed = fractionUsed
        )

        assertTrue(kotlin.math.abs(solvedDia - trueDia) <= 10.0,
                   "Solving back from a known DIA's fraction-used should recover it within grid " +
                       "resolution, wanted=$trueDia got=$solvedDia")
    }

    @Test fun `result always stays within the search bounds`() {
        // Degenerate extremes — near-zero and near-total fraction used — should clamp to the
        // search range rather than extrapolate outside it or blow up.
        val diaNearZeroUsed = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 45.0, peakMinutes = 75.0, fractionUsed = 0.0
        )
        val diaNearFullUsed = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 45.0, peakMinutes = 75.0, fractionUsed = 1.0
        )
        assertTrue(diaNearZeroUsed in LearnedInsulinProfile.DIA_MIN_MINUTES..LearnedInsulinProfile.DIA_MAX_MINUTES)
        assertTrue(diaNearFullUsed in LearnedInsulinProfile.DIA_MIN_MINUTES..LearnedInsulinProfile.DIA_MAX_MINUTES)
    }

    @Test fun `lower fraction used well after peak solves to a longer DIA than a higher fraction used`() {
        // Only 15% of the dose consumed by 250 min post-delivery (175 min past a 75-min peak) is
        // only consistent with a longer DIA than if 60% had been consumed by the same time — a
        // short DIA would already show much more consumed by this elapsed time. This is the core
        // case the estimator exists to fix versus trusting raw elapsed-to-nadir time directly.
        val diaLowUsage = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 250.0, peakMinutes = 75.0, fractionUsed = 0.15
        )
        val diaHighUsage = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 250.0, peakMinutes = 75.0, fractionUsed = 0.60
        )
        assertTrue(diaLowUsage >= diaHighUsage,
                   "Lower fraction-used should solve to DIA >= higher fraction-used's, got low=$diaLowUsage high=$diaHighUsage")
    }

    @Test fun `custom search bounds are respected`() {
        val dia = BolusCurveAnalysis.solveDiaFromObservedFraction(
            elapsedMinutes = 200.0, peakMinutes = 55.0, fractionUsed = 0.5,
            diaMin = 360.0, diaMax = 420.0
        )
        assertTrue(dia in 360.0..420.0, "Solve should respect custom bounds, got $dia")
    }
}
