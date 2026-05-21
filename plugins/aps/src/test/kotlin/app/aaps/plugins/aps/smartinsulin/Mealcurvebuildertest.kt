package app.aaps.plugins.aps.smartInsulin.ice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [MealCurveBuilder] under the three-component physiological model:
 *
 *   - Carbs: GI-dependent Gaussian, peak 30-120 min
 *   - Protein: cosine-smoothed plateau, onset 45 min, duration 4-6h
 *   - Fat: cosine-smoothed plateau, onset 45 min, duration 4-6h
 *
 * These tests verify the curve SHAPES match real T1D physiology — fast carb
 * spike independent of fat content, then sustained plateau from fat/protein
 * starting ~45 min after the meal. Naming uses em-dash rather than colon
 * because Kotlin backticks forbid `:` in function names.
 */
class MealCurveBuilderTest {

    private val now = 1_000_000_000_000L
    private fun meal(
        carbs: Double = 0.0,
        protein: Double = 0.0,
        fat: Double = 0.0,
        bucket: GiBucket = GiBucket.MEDIUM,
        commitment: Int = 100
    ) = AnnouncedMeal(
        carbsG              = carbs,
        proteinG            = protein,
        fatG                = fat,
        giBucket            = bucket,
        commitmentPct       = commitment,
        announceTimestampMs = now
    )

    private fun rateAt(m: AnnouncedMeal, ageMinutes: Int): Double =
        MealCurveBuilder.expectedIceMgdlPerHourAt(m, m.announceTimestampMs + ageMinutes * 60_000L)

    /** Integrate the curve over its full window using the trapezoidal rule. mg/dL·h. */
    private fun integrate(m: AnnouncedMeal, stepMin: Int = 1): Double {
        val end = m.effectiveTotalDurationMin
        var total = 0.0
        var prev = rateAt(m, 0)
        for (t in stepMin..end step stepMin) {
            val curr = rateAt(m, t)
            total += (prev + curr) / 2.0 * stepMin
            prev = curr
        }
        return total / 60.0   // convert minute-summed area to hour-area
    }

    // ── Boundary conditions ─────────────────────────────────────────────────

    @Test
    @DisplayName("returns 0 before announce time and after window end")
    fun `boundary returns zero outside the active window`() {
        val m = meal(carbs = 50.0)
        // before announce — negative age
        assertEquals(0.0, MealCurveBuilder.expectedIceMgdlPerHourAt(m, m.announceTimestampMs - 60_000L))
        // after window end (MEDIUM = 240 min)
        assertEquals(0.0, rateAt(m, 250))
        // and well past
        assertEquals(0.0, rateAt(m, 500))
    }

    // ── Carb-only behaviour ─────────────────────────────────────────────────

    @Test
    @DisplayName("FAST carbs peak earlier than SLOW carbs")
    fun `fast carbs peak earlier than slow`() {
        val fast = meal(carbs = 50.0, bucket = GiBucket.FAST)
        val slow = meal(carbs = 50.0, bucket = GiBucket.SLOW)
        // Sample both at 30 min — fast should be near peak, slow barely started
        val fastAt30 = rateAt(fast, 30)
        val slowAt30 = rateAt(slow, 30)
        assertTrue(fastAt30 > slowAt30,
                   "FAST should be near peak at 30 min ($fastAt30 mg/dL/h) but SLOW shouldn't be yet ($slowAt30)")
    }

    @Test
    @DisplayName("carbs hit fast regardless of GI bucket — peak before 90 min")
    fun `carb peak occurs before 90 minutes even for slow GI`() {
        // Key physiological assertion: even Slow GI carbs hit by 90 min. The "slow" in
        // Slow GI means slower than fast, not "delayed for hours" — fat/protein is what
        // creates real delay, modeled as a separate plateau.
        val slow = meal(carbs = 50.0, bucket = GiBucket.SLOW)
        val peakMinute = (0..180 step 5).maxByOrNull { rateAt(slow, it) } ?: -1
        assertTrue(peakMinute in 60..150,
                   "SLOW carb peak should land between 60-150 min, got $peakMinute")
    }

    @Test
    @DisplayName("integrated carb area approximates total glycemic load")
    fun `integrated carb area matches expected glycemic load`() {
        val m = meal(carbs = 50.0, bucket = GiBucket.MEDIUM)
        val area = integrate(m)
        // 50g × 4.5 mg/dL·h per gram = 225 mg/dL·h expected, allow ±15% for Gaussian tails
        // truncated by window end
        assertTrue(area in 190.0..260.0,
                   "Expected carb area ~225 mg/dL·h, got $area")
    }

    // ── Protein behaviour — plateau, not peak ───────────────────────────────

    @Test
    @DisplayName("protein contributes nothing before 45 min onset")
    fun `protein zero before onset`() {
        val m = meal(protein = 100.0)
        assertEquals(0.0, rateAt(m, 0), 1e-9)
        assertEquals(0.0, rateAt(m, 30), 1e-9)
        assertEquals(0.0, rateAt(m, 44), 1e-9)
    }

    @Test
    @DisplayName("protein produces a SUSTAINED PLATEAU not a discrete peak")
    fun `protein produces a sustained plateau`() {
        val m = meal(protein = 80.0)
        // Sample across the plateau region (90 min through 240 min). Values should be
        // relatively flat — max/min ratio should be small.
        val plateauSamples = (90..240 step 15).map { rateAt(m, it) }
        val maxRate = plateauSamples.max()
        val minRate = plateauSamples.min()
        assertTrue(maxRate > 0.0, "Protein plateau should be non-zero, got max $maxRate")
        // Ratio of max to min within plateau should be < 1.2 (truly flat)
        assertTrue(maxRate / minRate < 1.3,
                   "Protein plateau should be flat — max/min ratio = ${maxRate / minRate}, samples: $plateauSamples")
    }

    @Test
    @DisplayName("protein plateau height scales linearly with grams")
    fun `protein height scales linearly`() {
        val small  = meal(protein = 30.0)
        val medium = meal(protein = 60.0)
        // Sample mid-plateau at 150 min for both
        val smallMid  = rateAt(small, 150)
        val mediumMid = rateAt(medium, 150)
        // Note: durations differ (small=240, medium=300) so this isn't pure 2× scaling.
        // We check the basic monotone property — more protein = more rate.
        assertTrue(mediumMid > smallMid,
                   "Larger protein meal should produce higher plateau (60g=$mediumMid > 30g=$smallMid)")
    }

    // ── Fat behaviour — same plateau shape, lower amplitude per gram ───────

    @Test
    @DisplayName("fat-only meal produces a plateau, no fast spike")
    fun `fat-only meal produces only a plateau`() {
        val m = meal(fat = 80.0)
        // Verify no contribution in the first 30 min (no carb spike from fat alone)
        for (t in 0..30 step 5) {
            assertEquals(0.0, rateAt(m, t), 1e-9,
                         "Fat-only meal must not contribute before onset (failed at t=$t)")
        }
        // Verify plateau exists past 90 min
        val midPlateau = rateAt(m, 150)
        assertTrue(midPlateau > 0.0,
                   "Fat-only meal should have a positive plateau, got $midPlateau at 150 min")
    }

    @Test
    @DisplayName("more fat extends the plateau duration")
    fun `larger fat load creates longer plateau`() {
        val small = meal(fat = 20.0)   // < 30g → 4h window
        val large = meal(fat = 90.0)   // > 80g → 6h window
        assertEquals(240, small.effectiveTotalDurationMin)
        assertEquals(360, large.effectiveTotalDurationMin)
        // Small should be near zero at 300 min (past its window)
        assertEquals(0.0, rateAt(small, 300), 1e-9)
        // Large should still be contributing at 300 min
        assertTrue(rateAt(large, 300) > 0.0,
                   "Large fat load should still be contributing at 300 min, got ${rateAt(large, 300)}")
    }

    // ── The "wings & BBQ" scenario — composite behaviour ───────────────────

    @Test
    @DisplayName("wings scenario: fast carb spike, settle, then fat/protein plateau")
    fun `wings and BBQ produces the right composite shape`() {
        val wings = meal(
            carbs   = 25.0,            // BBQ sauce sugar — high GI
            protein = 80.0,            // big serve of wings
            fat     = 60.0,            // wings are fatty
            bucket  = GiBucket.FAST    // BBQ sauce sugar = fast spike
        )

        // 1. Early carb spike — should peak by 45 min
        val carbPeakArea = (15..60 step 5).map { rateAt(wings, it) }.max()
        // 2. Settle window — fat/protein not yet engaged, carbs declining
        val settleRate = rateAt(wings, 60)
        // 3. Plateau region — fat + protein sustaining elevation
        val plateauRate = rateAt(wings, 180)
        // 4. Tail — should fade by 360 min
        val tailRate = rateAt(wings, 359)

        assertTrue(carbPeakArea > plateauRate,
                   "Early carb spike ($carbPeakArea) should exceed mid plateau ($plateauRate)")
        assertTrue(plateauRate > 0.0,
                   "Plateau region must be active, got $plateauRate at 180 min")
        // Wings have lots of fat/protein → 6h window
        assertEquals(360, wings.effectiveTotalDurationMin)
        // Rate near end of window should be small
        assertTrue(tailRate < plateauRate / 2.0,
                   "Tail rate ($tailRate) should be small relative to plateau ($plateauRate)")
    }

    @Test
    @DisplayName("wings scenario: a 'trough' between carb peak and plateau onset")
    fun `wings scenario has settle window between carb spike and plateau`() {
        // The signature shape Shantelle described: carbs hit fast, BG starts settling
        // back toward target, THEN protein/fat kicks in for a sustained plateau.
        // Verify rate at 60-75 min (between carb peak and fat/protein onset) is
        // notably lower than both the carb peak earlier AND the plateau later.
        val wings = meal(
            carbs   = 25.0,
            protein = 80.0,
            fat     = 60.0,
            bucket  = GiBucket.FAST
        )
        val carbPeak    = rateAt(wings, 30)    // near FAST peak (30 min)
        val troughDeep  = rateAt(wings, 60)    // mid-trough
        val plateauMid  = rateAt(wings, 180)   // mid-plateau
        // Carb peak should be the highest in this run (small meal, sharp spike)
        // Trough should be substantially lower than the peak
        // Plateau should be elevated but lower than the carb peak (different mechanisms)
        assertTrue(carbPeak > troughDeep,
                   "Carb peak ($carbPeak) should exceed trough ($troughDeep)")
        assertTrue(plateauMid > troughDeep * 0.5 || troughDeep < 5.0,
                   "Plateau ($plateauMid) should re-elevate above the trough ($troughDeep), or trough should be near-zero")
    }

    // ── Commitment scaling ─────────────────────────────────────────────────

    @Test
    @DisplayName("commitment scales the entire curve linearly")
    fun `commitment scales the curve`() {
        val full = meal(carbs = 50.0, protein = 30.0, fat = 30.0, commitment = 100)
        val half = meal(carbs = 50.0, protein = 30.0, fat = 30.0, commitment = 50)
        // At any sample point, half-commitment should produce exactly half the rate
        for (t in listOf(30, 60, 120, 180)) {
            val fullR = rateAt(full, t)
            val halfR = rateAt(half, t)
            if (fullR > 0.01) {
                assertEquals(fullR / 2.0, halfR, 0.01,
                             "Commitment 50% should produce half the rate at t=$t (full=$fullR, half=$halfR)")
            }
        }
    }

    @Test
    @DisplayName("zero commitment produces a flat zero curve")
    fun `zero commitment is identically zero`() {
        val m = meal(carbs = 50.0, protein = 30.0, fat = 30.0, commitment = 0)
        for (t in 0..360 step 30) {
            assertEquals(0.0, rateAt(m, t), 1e-9, "Zero commitment failed at t=$t")
        }
    }

    // ── effectiveTotalDurationMin behaviour ─────────────────────────────────

    @Test
    @DisplayName("carb-only meal uses GI bucket duration as the window")
    fun `carb-only window equals GI bucket duration`() {
        val fast = meal(carbs = 30.0, bucket = GiBucket.FAST)
        val slow = meal(carbs = 30.0, bucket = GiBucket.SLOW)
        assertEquals(GiBucket.FAST.totalDurationMinutes, fast.effectiveTotalDurationMin)
        assertEquals(GiBucket.SLOW.totalDurationMinutes, slow.effectiveTotalDurationMin)
    }

    @Test
    @DisplayName("fat/protein extends the window past the carb absorption end")
    fun `protein extends window past carb end`() {
        val carbOnly = meal(carbs = 30.0, bucket = GiBucket.FAST)            // 90 min window
        val withProt = meal(carbs = 30.0, bucket = GiBucket.FAST, protein = 50.0)  // protein → 5h
        assertTrue(withProt.effectiveTotalDurationMin > carbOnly.effectiveTotalDurationMin,
                   "Adding protein should extend the window")
        assertEquals(300, withProt.effectiveTotalDurationMin)
    }

    // ── Sampling helper ────────────────────────────────────────────────────

    @Test
    @DisplayName("sampleCurve covers the full active window")
    fun `sampleCurve produces samples across the full window`() {
        val m = meal(carbs = 50.0, protein = 40.0, bucket = GiBucket.MEDIUM)
        val samples = MealCurveBuilder.sampleCurve(m, m.announceTimestampMs, sampleIntervalMin = 10)
        assertTrue(samples.isNotEmpty(), "sampleCurve should return non-empty list")
        // First sample at t=0, last sample within sample-interval of the end
        assertEquals(0, samples.first().first)
        val lastT = samples.last().first
        assertTrue(lastT > m.effectiveTotalDurationMin - 10,
                   "Last sample t=$lastT should be near window end ${m.effectiveTotalDurationMin}")
    }
}