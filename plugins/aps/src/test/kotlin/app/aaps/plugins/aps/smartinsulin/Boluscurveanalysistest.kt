package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.BolusCurveAnalysis.PeakResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BolusCurveAnalysis].
 *
 * Each test builds a synthetic CGM curve with known peak-velocity timing and asserts the
 * analyser recovers it within sub-cycle tolerance. Curves use real epoch-ms timestamps so
 * failures map directly to "minutes off from truth" without unit confusion.
 *
 * If your project is on JUnit 4 instead of JUnit 5, swap the imports for
 * `org.junit.Test` and `org.junit.Assert.*` — the assertions are equivalent.
 */
class BolusCurveAnalysisTest {

    private val cycleMs = 5 * 60_000L      // 5-min Dexcom G6 nominal cadence
    private val startMs = 1_700_000_000_000L  // arbitrary fixed epoch ms

    /** Build a uniformly-spaced curve: index i lands at startMs + i*cycleMs. */
    private fun uniformCurve(bgs: List<Double>): List<Pair<Long, Double>> =
        bgs.mapIndexed { i, bg -> (startMs + i * cycleMs) to bg }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Happy path: clean V-shape with symmetric flanks → vertex at segment midpoint
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `symmetric V-shape returns peak near steepest segment`() {
        // Velocities (mg/dL/min):
        //   seg 0: (195-200)/5 = -1.0
        //   seg 1: (185-195)/5 = -2.0
        //   seg 2: (170-185)/5 = -3.0   ← steepest (first hit)
        //   seg 3: (155-170)/5 = -3.0   ← tied — analyser picks the FIRST
        //   seg 4: (145-155)/5 = -2.0
        //   seg 5: (140-145)/5 = -1.0
        // Triple at min_index=2 is (-2, -3, -3). The vertex formula gives offset = +0.5
        // (clamped), pulling the peak to the right edge of segment 2 = idx 2 * 5min + 5min = 15 min.
        // This is mathematically correct — the asymmetry is real (-2 left, -3 right).
        val bgs = listOf(200.0, 195.0, 185.0, 170.0, 155.0, 145.0, 140.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)

        assertInstanceOf(PeakResult.Ok::class.java, result)
        val peakMins = (result as PeakResult.Ok).minutes
        // Peak should land within or just past segment 2 (10–17.5 min range)
        assertTrue(peakMins in 10.0..17.5, "peak=$peakMins not in [10, 17.5]")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Sharp asymmetric drop → vertex offset should land between the two steepest segments
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `asymmetric V locates peak with sub-cycle resolution`() {
        // Velocities:
        //   seg 0: -2.0
        //   seg 1: -3.0
        //   seg 2: -5.0   ← steepest
        //   seg 3: -2.0
        //   seg 4: -1.0
        // The asymmetric flanks should pull the vertex toward seg 1 (the steeper flank),
        // i.e. offset < 0 → peak < midpoint of seg 2.
        // Segment 2 midpoint = idx 2 * 5min + 2.5 = 12.5 min.
        val bgs = listOf(200.0, 190.0, 175.0, 150.0, 140.0, 135.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)

        assertInstanceOf(PeakResult.Ok::class.java, result)
        val peakMins = (result as PeakResult.Ok).minutes
        assertTrue(peakMins < 12.5, "asymmetric flank should pull peak before midpoint, got $peakMins")
        assertTrue(peakMins > 10.0, "peak should still be near steepest segment, got $peakMins")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Flatline → no negative-velocity signal → NoNegSlope
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `flatline returns NoNegSlope`() {
        // Truly flat — no segment drops more than 0.1 mg/dL over a 5-min cycle (well below
        // the -0.1 mg/dL/min floor). A 1-mg/dL dip in a 5-min interval would be -0.2 mg/dL/min,
        // which IS above the floor and would (correctly) be detected as a real drop.
        val bgs = listOf(110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertInstanceOf(PeakResult.NoNegSlope::class.java, result)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Monotonic rise (carbs winning a meal bolus) → no peak signal → NoNegSlope
    //  This is the critical meal-mode case — analyser MUST return null here so
    //  ProfileLearner doesn't get fed a fake peak.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `monotonic rise returns NoNegSlope`() {
        val bgs = listOf(120.0, 130.0, 145.0, 165.0, 180.0, 195.0, 205.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertInstanceOf(PeakResult.NoNegSlope::class.java, result)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Borderline negligible drop (under MIN_PEAK_VELOCITY threshold) → NoNegSlope
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `negligible drop below velocity floor returns NoNegSlope`() {
        // Drops of 0.25 mg/dL per 5min = -0.05 mg/dL/min — well below -0.1 floor
        val bgs = listOf(110.0, 109.75, 109.5, 109.25, 109.0, 108.75)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertInstanceOf(PeakResult.NoNegSlope::class.java, result)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Too few samples → TooFewPoints
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `fewer than 4 samples returns TooFewPoints`() {
        val bgs = listOf(180.0, 160.0, 140.0)  // 3 samples → only 2 velocities → can't fit
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertInstanceOf(PeakResult.TooFewPoints::class.java, result)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Boundary: steepest segment at right edge → midpoint fallback (Ok)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `steepest at right boundary returns segment midpoint`() {
        // Last segment is the steepest drop — no right-flank to fit a parabola.
        // Falls back to midpoint of the last segment.
        val bgs = listOf(180.0, 178.0, 175.0, 170.0, 150.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)

        assertInstanceOf(PeakResult.Ok::class.java, result)
        val peakMins = (result as PeakResult.Ok).minutes
        // Last segment midpoint: index 3 * 5min + 2.5min = 17.5 min
        assertEquals(17.5, peakMins, 0.1)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Boundary: steepest at left edge → midpoint fallback
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `steepest at left boundary returns segment midpoint`() {
        // First segment is the steepest — no left-flank for parabola.
        val bgs = listOf(180.0, 150.0, 145.0, 143.0, 142.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)

        assertInstanceOf(PeakResult.Ok::class.java, result)
        val peakMins = (result as PeakResult.Ok).minutes
        // First segment midpoint: 2.5 min
        assertEquals(2.5, peakMins, 0.1)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Mild jitter (within 10% deviation threshold) → still uses parabolic refinement
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `mild jitter still produces interpolated Ok result`() {
        // ±15s jitter (5% of 5min) — under MAX_SPACING_DEVIATION (10%) → parabola still applies.
        // Jitter shifts the segment midpoints slightly, which shifts where the steepest
        // velocity is computed — peak lands a bit later than the uniform-spacing equivalent.
        val curve = listOf(
            startMs                       to 200.0,
            (startMs + cycleMs - 15_000L) to 195.0,
            (startMs + 2 * cycleMs)       to 185.0,
            (startMs + 3 * cycleMs + 15_000L) to 170.0,
            (startMs + 4 * cycleMs)       to 155.0,
            (startMs + 5 * cycleMs - 15_000L) to 145.0,
            (startMs + 6 * cycleMs)       to 140.0
        )
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(curve, startMs)
        assertInstanceOf(PeakResult.Ok::class.java, result)
        val peakMins = (result as PeakResult.Ok).minutes
        assertTrue(peakMins in 10.0..18.0, "mild-jitter peak=$peakMins not in [10, 18]")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Large jitter (Dexcom missed reading: 10-min gap) → NonUniform fallback
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `missed reading triggers NonUniform fallback`() {
        // The segment around the steepest drop has a 10-min duration vs 5-min neighbours
        // → 67% deviation, well over MAX_SPACING_DEVIATION (10%) → fallback to midpoint.
        val curve = listOf(
            startMs                  to 200.0,
            (startMs + cycleMs)      to 190.0,
            (startMs + 2 * cycleMs)  to 180.0,
            (startMs + 4 * cycleMs)  to 145.0,  // ← gap: missed reading at idx 3
            (startMs + 5 * cycleMs)  to 138.0,
            (startMs + 6 * cycleMs)  to 135.0
        )
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(curve, startMs)
        assertInstanceOf(PeakResult.NonUniform::class.java, result)
        // Fallback should be midpoint of the wide segment: (10min + 20min) / 2 = 15min from start
        val fallback = (result as PeakResult.NonUniform).fallbackMinutes
        assertEquals(15.0, fallback, 0.5, "fallback should be midpoint of the gap segment")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Defensive: corrupt timestamps (zero or negative duration) are skipped, not crashed
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `corrupt overlapping timestamps are skipped without crashing`() {
        // The duplicate timestamp at index 2 produces a zero-duration segment that the
        // analyser must skip cleanly. Remaining segments still describe a clean drop.
        val curve = listOf(
            startMs                 to 200.0,
            (startMs + cycleMs)     to 190.0,
            (startMs + cycleMs)     to 188.0,  // ← duplicate timestamp (clock glitch)
            (startMs + 2 * cycleMs) to 175.0,
            (startMs + 3 * cycleMs) to 160.0,
            (startMs + 4 * cycleMs) to 152.0,
            (startMs + 5 * cycleMs) to 150.0
        )
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(curve, startMs)
        // Should produce some Ok or NonUniform — main thing is no crash, no NaN, no negative time
        assertTrue(
            result is PeakResult.Ok || result is PeakResult.NonUniform,
            "expected Ok or NonUniform after skipping corrupt segment, got $result"
        )
    }
}