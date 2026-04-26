package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.BolusCurveAnalysis.PeakResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BolusCurveAnalysisTest {

    private val cycleMs = 5 * 60_000L
    private val startMs = 1_700_000_000_000L

    private fun uniformCurve(bgs: List<Double>): List<Pair<Long, Double>> =
        bgs.mapIndexed { i, bg -> (startMs + i * cycleMs) to bg }

    @Test
    fun symmetricVShapeReturnsPeakNearSteepestSegment() {
        val bgs = listOf(200.0, 195.0, 185.0, 170.0, 155.0, 145.0, 140.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.Ok)
        val peakMins = (result as PeakResult.Ok).minutes
        assertTrue(peakMins in 10.0..17.5)
    }

    @Test
    fun asymmetricVLocatesPeakWithSubCycleResolution() {
        val bgs = listOf(200.0, 190.0, 175.0, 150.0, 140.0, 135.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.Ok)
        val peakMins = (result as PeakResult.Ok).minutes
        assertTrue(peakMins < 12.5)
        assertTrue(peakMins > 10.0)
    }

    @Test
    fun flatlineReturnsNoNegSlope() {
        val bgs = listOf(110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.NoNegSlope)
    }

    @Test
    fun monotonicRiseReturnsNoNegSlope() {
        val bgs = listOf(120.0, 130.0, 145.0, 165.0, 180.0, 195.0, 205.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.NoNegSlope)
    }

    @Test
    fun negligibleDropBelowVelocityFloorReturnsNoNegSlope() {
        // 0.25 mg/dL per 5min = -0.05 mg/dL/min, below -0.1 floor
        val bgs = listOf(110.0, 109.75, 109.5, 109.25, 109.0, 108.75)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.NoNegSlope)
    }

    @Test
    fun fewerThan4SamplesReturnsTooFewPoints() {
        val bgs = listOf(180.0, 160.0, 140.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.TooFewPoints)
    }

    @Test
    fun steepestAtRightBoundaryReturnsSegmentMidpoint() {
        // Last segment steepest, boundary fallback: index 3 * 5min + 2.5min = 17.5 min
        val bgs = listOf(180.0, 178.0, 175.0, 170.0, 150.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.Ok)
        assertEquals(17.5, (result as PeakResult.Ok).minutes, 0.1)
    }

    @Test
    fun steepestAtLeftBoundaryReturnsSegmentMidpoint() {
        // First segment steepest, boundary fallback: midpoint = 2.5 min
        val bgs = listOf(180.0, 150.0, 145.0, 143.0, 142.0)
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(uniformCurve(bgs), startMs)
        assertTrue(result is PeakResult.Ok)
        assertEquals(2.5, (result as PeakResult.Ok).minutes, 0.1)
    }

    @Test
    fun mildJitterStillProducesInterpolatedOkResult() {
        // +-15s jitter (5% of 5min), under MAX_SPACING_DEVIATION threshold
        val curve = listOf(
            startMs                            to 200.0,
            (startMs + cycleMs - 15_000L)      to 195.0,
            (startMs + 2 * cycleMs)            to 185.0,
            (startMs + 3 * cycleMs + 15_000L)  to 170.0,
            (startMs + 4 * cycleMs)            to 155.0,
            (startMs + 5 * cycleMs - 15_000L)  to 145.0,
            (startMs + 6 * cycleMs)            to 140.0
        )
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(curve, startMs)
        assertTrue(result is PeakResult.Ok)
        assertTrue((result as PeakResult.Ok).minutes in 10.0..18.0)
    }

    @Test
    fun missedReadingTriggersNonUniformFallback() {
        // 10-min gap produces 67% spacing deviation, fallback midpoint = 15 min
        val curve = listOf(
            startMs                 to 200.0,
            (startMs + cycleMs)     to 190.0,
            (startMs + 2 * cycleMs) to 180.0,
            (startMs + 4 * cycleMs) to 145.0,
            (startMs + 5 * cycleMs) to 138.0,
            (startMs + 6 * cycleMs) to 135.0
        )
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(curve, startMs)
        assertTrue(result is PeakResult.NonUniform)
        assertEquals(15.0, (result as PeakResult.NonUniform).fallbackMinutes, 0.5)
    }

    @Test
    fun corruptOverlappingTimestampsAreSkippedWithoutCrashing() {
        val curve = listOf(
            startMs                 to 200.0,
            (startMs + cycleMs)     to 190.0,
            (startMs + cycleMs)     to 188.0,  // duplicate timestamp
            (startMs + 2 * cycleMs) to 175.0,
            (startMs + 3 * cycleMs) to 160.0,
            (startMs + 4 * cycleMs) to 152.0,
            (startMs + 5 * cycleMs) to 150.0
        )
        val result = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(curve, startMs)
        assertTrue(result is PeakResult.Ok || result is PeakResult.NonUniform)
    }
}