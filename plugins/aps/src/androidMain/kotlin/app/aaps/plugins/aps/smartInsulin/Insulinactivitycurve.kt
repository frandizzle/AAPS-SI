package app.aaps.plugins.aps.smartInsulin

import kotlin.math.exp

/**
 * Parameterised insulin activity / IOB curve (oref0 "exponential" model).
 *
 * Pure, stateless, no Android dependencies, so the math can be unit-tested with synthetic
 * inputs independent of the dosing core (same rationale as [BolusCurveAnalysis]).
 *
 * peak (time-to-peak activity) and DIA (duration of insulin action) are INDEPENDENT shape
 * parameters. That is the entire point of this class: a time-axis rescale of a pre-computed
 * activity array can stretch DIA but cannot relocate the peak, so learned peak/DIA never
 * reached the BG prediction. This curve takes both numbers directly into the shape.
 *
 * [activityFraction] is normalised so its integral over [0, dia] == 1.0 per unit of insulin,
 * and its maximum occurs exactly at t == peak (by construction of tau).
 *
 * Reference: oref0 lib/iob/calculate.js (exponential model). The IOB form below is the
 * algebraically-simplified version of that expression (see [iobFraction]).
 */
object InsulinActivityCurve {

    // peak must sit below dia/2 or the tau denominator (1 - 2*peak/dia) degenerates. The
    // learner's own clamps (peak <= 120, dia >= 300 -> ratio <= 0.40) already preclude this;
    // the guard only protects against a future clamp change.
    private const val MAX_PEAK_DIA_RATIO = 0.45

    private data class Params(val tau: Double, val a: Double, val s: Double, val dia: Double)

    private fun params(peakMinutes: Double, diaMinutes: Double): Params {
        val dia  = diaMinutes.coerceAtLeast(1.0)
        val peak = peakMinutes.coerceIn(1.0, dia * MAX_PEAK_DIA_RATIO)
        val tau  = peak * (1.0 - peak / dia) / (1.0 - 2.0 * peak / dia)
        val a    = 2.0 * tau / dia
        val s    = 1.0 / (1.0 - a + (1.0 + a) * exp(-dia / tau))
        return Params(tau, a, s, dia)
    }

    /** Fraction of a unit bolus acting per minute at age [tMinutes]. Units: 1/min. 0 outside [0, dia). */
    fun activityFraction(tMinutes: Double, peakMinutes: Double, diaMinutes: Double): Double {
        val p = params(peakMinutes, diaMinutes)
        if (tMinutes <= 0.0 || tMinutes >= p.dia) return 0.0
        return (p.s / (p.tau * p.tau)) * tMinutes * (1.0 - tMinutes / p.dia) * exp(-tMinutes / p.tau)
    }

    /**
     * Fraction of a unit bolus still on board at age [tMinutes]. 1.0 at t<=0, ->0 at t>=dia,
     * monotonically non-increasing in between.
     *
     * The raw oref0 IOB expression divides by (1 - a), which is exactly zero when a == 1
     * (peak/dia ~= 0.293 — inside this learner's range, e.g. peak 88 / dia 300). Distributing the
     * outer (1 - a) into the bracket cancels that denominator algebraically, so the form below is
     * singularity-free across every peak/DIA the learner can produce. (Verified: this form gives
     * iobFraction(dia) == 0 exactly.)
     */
    fun iobFraction(tMinutes: Double, peakMinutes: Double, diaMinutes: Double): Double {
        val p = params(peakMinutes, diaMinutes)
        if (tMinutes <= 0.0) return 1.0
        if (tMinutes >= p.dia) return 0.0
        val t  = tMinutes
        val oneMinusA = 1.0 - p.a
        val iob = 1.0 - p.s * (
            exp(-t / p.tau) * (t * t / (p.tau * p.dia) - oneMinusA * t / p.tau - oneMinusA) + oneMinusA
            )
        return iob.coerceIn(0.0, 1.0)
    }

    /**
     * Estimate the single effective age (minutes since delivery) of the insulin currently on
     * board, by matching the observed activity/IOB ratio to this curve.
     *
     * The real IOB is a mixture of boluses at different ages; this collapses that mixture to one
     * representative age so the forward projection can be reshaped by the learned curve WITHOUT
     * needing per-bolus history. It is exact at t==0 (just-delivered insulin reads age 0) and
     * avoids the "treat all IOB as fresh" error, which would re-peak insulin that is already
     * decaying.
     *
     * ratio(t) = activityFraction(t) / iobFraction(t) rises from 0, so the FIRST age whose ratio
     * reaches the observed ratio is the physically-correct (still-has-a-future) phase. Scans in
     * 1-minute steps for robustness — no derivative / root-solver fragility, and the first-crossing
     * rule is correct even if the ratio is non-monotonic deep in the tail.
     */
    fun effectiveAgeMinutes(
        activityNowUPerMin: Double,
        iobNowU:            Double,
        peakMinutes:        Double,
        diaMinutes:         Double
    ): Double {
        if (iobNowU <= 1e-6 || activityNowUPerMin <= 0.0) return 0.0
        val observedRatio = activityNowUPerMin / iobNowU
        val p = params(peakMinutes, diaMinutes)
        var t = 1.0
        while (t < p.dia) {
            val iobF = iobFraction(t, peakMinutes, diaMinutes)
            if (iobF <= 1e-6) break
            val ratio = activityFraction(t, peakMinutes, diaMinutes) / iobF
            if (ratio >= observedRatio) return t
            t += 1.0
        }
        // Observed ratio exceeds anything on the curve -> insulin essentially spent.
        return (p.dia - 1.0).coerceAtLeast(0.0)
    }
}