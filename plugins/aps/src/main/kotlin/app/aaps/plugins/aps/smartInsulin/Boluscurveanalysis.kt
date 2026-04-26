package app.aaps.plugins.aps.smartInsulin

import kotlin.math.abs

/**
 * Pure curve-analysis utilities for [BolusCurveTracker].
 *
 * No state, no Android dependencies — this object exists so curve math can be unit-tested
 * with synthetic data, independent of the tracker's state machine and persistence layer.
 *
 * The primary function, [calculateInterpolatedPeakMinutes], finds the time of *peak insulin
 * action* by locating the maximum negative BG velocity (steepest drop) and refining it via
 * parabolic vertex interpolation over the velocity curve. This is physiologically distinct
 * from BG nadir time:
 *
 *   - **Peak action**  : when insulin is pulling BG down fastest (max negative dBG/dt)
 *   - **BG nadir**     : when BG stops falling (insulin action ≈ basal + counter-regulation)
 *
 * Nadir always lags peak action by a meaningful interval (~20–40 min for rapid analogues).
 * Feeding nadir time into the AAPS insulin curve's `peak` parameter systematically biases
 * the modeled peak late, which is what this utility is designed to correct.
 */
object BolusCurveAnalysis {

    /**
     * Result of a peak-detection attempt. Sealed so callers can log diagnostic reasons
     * for failure rather than just "null".
     */
    sealed class PeakResult {
        /** Successful interpolation. [minutes] is offset from track start. */
        data class Ok(val minutes: Double) : PeakResult()

        /** Fewer than the minimum required samples in [BolusCurveAnalysis.MIN_SAMPLES_FOR_PEAK]. */
        data class TooFewPoints(val count: Int) : PeakResult()

        /** Curve never showed a meaningfully negative velocity — insulin "lost" to carbs/basal. */
        object NoNegSlope : PeakResult()

        /**
         * Spacing between BG samples was too non-uniform around the steepest segment to
         * trust the parabolic vertex math. Fell back to the segment midpoint as a coarse
         * estimate. [fallbackMinutes] is offset from track start; quality is "rough".
         */
        data class NonUniform(val fallbackMinutes: Double) : PeakResult()
    }

    /** Need ≥4 BG samples to produce ≥3 velocity segments — minimum for parabolic vertex fit. */
    const val MIN_SAMPLES_FOR_PEAK = 4

    /**
     * Floor below which a "drop" doesn't represent meaningful insulin action. -0.1 mg/dL/min
     * is roughly -0.5 mg/dL per 5-min cycle — well below sensor noise. Real FASTING peaks
     * typically hit -1.0 to -3.0 mg/dL/min. Anything shallower than this floor we treat as
     * "BG basically flat" and decline to declare a peak.
     */
    const val MIN_PEAK_VELOCITY_MGDL_PER_MIN = -0.1

    /**
     * Maximum allowed deviation between the three intervals around the steepest segment
     * before we abandon parabolic refinement. The parabolic vertex formula assumes uniform
     * spacing; with Dexcom G6 jitter typically ±30s on a 5-min cadence, ~10% deviation
     * (~30s on 5min) is the natural threshold. Beyond that we fall back to the segment
     * midpoint rather than produce a confidently-wrong sub-cycle estimate.
     */
    const val MAX_SPACING_DEVIATION = 0.10

    /**
     * Find time of maximum negative BG velocity using quadratic vertex interpolation
     * over the velocity curve.
     *
     * @param curve       List of (timestampMs, smoothedBgMgdl) samples in chronological order
     * @param trackStartMs Epoch ms of bolus delivery (curve[0] need not equal this exactly)
     * @return [PeakResult] indicating success or specific failure mode
     */
    fun calculateInterpolatedPeakMinutes(
        curve: List<Pair<Long, Double>>,
        trackStartMs: Long
    ): PeakResult {
        if (curve.size < MIN_SAMPLES_FOR_PEAK) return PeakResult.TooFewPoints(curve.size)

        // ── 1. Compute per-segment velocities (mg/dL per minute) and segment midpoints ──
        // We use real timestamps rather than assuming uniform 5-min cadence — Dexcom G6
        // routinely jitters ±30s and occasionally drops a reading entirely.
        val velocities = mutableListOf<Double>()
        val midpointsMs = mutableListOf<Long>()
        val segmentDurationsMs = mutableListOf<Long>()

        for (i in 0 until curve.size - 1) {
            val (t1, bg1) = curve[i]
            val (t2, bg2) = curve[i + 1]
            val deltaMs = t2 - t1
            // Defensive: skip segments with non-positive duration (corrupt timestamps,
            // duplicate readings, clock-rollback). Keeping them would produce NaN or sign-
            // flipped velocities that poison the min-search below.
            if (deltaMs <= 0L) continue

            val deltaMinutes = deltaMs.toDouble() / 60_000.0
            velocities.add((bg2 - bg1) / deltaMinutes)
            midpointsMs.add(t1 + deltaMs / 2)
            segmentDurationsMs.add(deltaMs)
        }

        if (velocities.size < 3) return PeakResult.TooFewPoints(curve.size)

        // ── 2. Locate steepest negative-velocity segment ────────────────────────────────
        var minVelocity = Double.MAX_VALUE
        var minIndex = -1
        for (i in velocities.indices) {
            if (velocities[i] < minVelocity) {
                minVelocity = velocities[i]
                minIndex = i
            }
        }

        if (minVelocity > MIN_PEAK_VELOCITY_MGDL_PER_MIN || minIndex == -1) {
            return PeakResult.NoNegSlope
        }

        // ── 3. Boundary case — steepest segment at edge means no left/right neighbour ──
        // Without two flanking points we can't fit a parabola; return the segment midpoint
        // as a coarse estimate. (This is rare in practice — by the time nadir is confirmed,
        // the steepest drop is well inside the curve.)
        if (minIndex == 0 || minIndex == velocities.size - 1) {
            val peakMins = (midpointsMs[minIndex] - trackStartMs).toDouble() / 60_000.0
            return PeakResult.Ok(peakMins)
        }

        // ── 4. Spacing uniformity check before parabolic refinement ────────────────────
        // The vertex formula 0.5 * (y₋₁ − y₊₁) / (y₋₁ − 2y₀ + y₊₁) is derived assuming
        // uniform spacing of the velocity samples. With non-uniform spacing the formula
        // doesn't hold rigorously and using "average duration" to map back to time is
        // wrong-flavoured at the precision we care about (single-minute resolution).
        // If spacing is off by more than MAX_SPACING_DEVIATION, fall back to the midpoint.
        val durLeft   = segmentDurationsMs[minIndex - 1].toDouble()
        val durMid    = segmentDurationsMs[minIndex].toDouble()
        val durRight  = segmentDurationsMs[minIndex + 1].toDouble()
        val avgDur    = (durLeft + durMid + durRight) / 3.0
        val maxDev    = maxOf(
            abs(durLeft  - avgDur) / avgDur,
            abs(durMid   - avgDur) / avgDur,
            abs(durRight - avgDur) / avgDur
        )
        if (maxDev > MAX_SPACING_DEVIATION) {
            val fallbackMins = (midpointsMs[minIndex] - trackStartMs).toDouble() / 60_000.0
            return PeakResult.NonUniform(fallbackMins)
        }

        // ── 5. Parabolic vertex interpolation ──────────────────────────────────────────
        val yMinus1 = velocities[minIndex - 1]
        val y0      = velocities[minIndex]
        val yPlus1  = velocities[minIndex + 1]

        val denominator = yMinus1 - 2.0 * y0 + yPlus1
        // Linear (collinear) velocities → no parabolic curvature → no sub-cycle refinement
        // possible. Default offset to 0 (midpoint of steepest segment).
        val offsetFraction = if (abs(denominator) < 1e-9) {
            0.0
        } else {
            0.5 * (yMinus1 - yPlus1) / denominator
        }
        // Mathematically the offset should be in [-0.5, 0.5]; clamp defensively against
        // pathological inputs (near-zero denominator amplifying floating-point noise).
        val clampedOffset = offsetFraction.coerceIn(-0.5, 0.5)

        // Map fractional offset back to real time using the AVERAGE of the three durations.
        // We've already verified spacing is uniform-enough above, so avgDur is a reliable
        // unit-of-time for the fractional offset.
        val peakTimeMs = midpointsMs[minIndex] + (clampedOffset * avgDur).toLong()
        val peakMins   = (peakTimeMs - trackStartMs).toDouble() / 60_000.0

        return PeakResult.Ok(peakMins)
    }
}