package app.aaps.plugins.aps.smartInsulin.ice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the expected-ICE curve builder. These cover:
 *
 * - Shape: rate is zero outside [0, totalDuration], peaks near expected time
 * - Integration: total area under curve approximates the meal's expected glucose load
 * - Commitment scaling: linear in commitmentPct
 * - GI bucket differences: SLOW peaks later and lasts longer than FAST
 * - Protein contributes a late hump
 * - Edge cases: zero carbs, negative ages, validation errors
 */
class MealCurveBuilderTest {

    /** Helper: build a meal and sample its curve at the named age. */
    private fun rateAt(meal: AnnouncedMeal, ageMin: Int): Double =
        MealCurveBuilder.expectedIceMgdlPerHourAt(meal, meal.announceTimestampMs + ageMin * 60_000L)

    // ── Shape & timing ──────────────────────────────────────────────────────

    @Test fun `curve is zero before meal start`() {
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 1_000_000L
        )
        val before = MealCurveBuilder.expectedIceMgdlPerHourAt(meal, 999_000L)
        assertEquals(0.0, before, 0.0001)
    }

    @Test fun `curve is zero after total duration ends`() {
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        val afterEnd = rateAt(meal, GiBucket.MEDIUM.totalDurationMinutes + 1)
        assertEquals(0.0, afterEnd, 0.0001)
    }

    @Test fun `rate near peak is greater than rate at start`() {
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        val rateAtStart = rateAt(meal, 5)
        val rateAtPeak  = rateAt(meal, GiBucket.MEDIUM.peakMinutes)
        assertTrue(rateAtPeak > rateAtStart * 2,
                   "peak rate ($rateAtPeak) should be much larger than start rate ($rateAtStart)")
    }

    // ── Integration: total area ≈ expected glucose load ──────────────────────

    @Test fun `integrated area approximates total carb glycemic load`() {
        // For 50g carbs at medium GI, integrated area should be ≈ 50 × 4.5 = 225 mg/dL·h
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        // Numerical integration: trapezoidal rule, 1-min sampling
        var total = 0.0
        for (t in 0..GiBucket.MEDIUM.totalDurationMinutes) {
            total += rateAt(meal, t)
        }
        // Sum-of-rates × dt = total area; rates are per hour, dt = 1/60 hour
        val areaMgdlH = total / 60.0
        // Expected: 50 × 4.5 = 225 mg/dL·h. Within 10% is acceptable (Gaussian tails get clipped).
        assertEquals(225.0, areaMgdlH, 25.0,
                     "integrated area should be ≈ 225 mg/dL·h for 50g medium-GI carbs, got $areaMgdlH")
    }

    // ── Commitment scales linearly ──────────────────────────────────────────

    @Test fun `half commitment halves all rates`() {
        val full = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        val half = full.copy(commitmentPct = 50)

        for (t in listOf(15, 30, 60, 120, 180)) {
            val fullRate = rateAt(full, t)
            val halfRate = rateAt(half, t)
            if (fullRate > 0.01) {
                assertEquals(0.5, halfRate / fullRate, 0.01,
                             "at t=${t}min, half commitment should produce 0.5× rate")
            }
        }
    }

    @Test fun `zero commitment produces zero rates everywhere`() {
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 0,
            announceTimestampMs = 0L
        )
        for (t in 0..240 step 5) {
            assertEquals(0.0, rateAt(meal, t), 0.0001, "rate at t=${t} should be 0")
        }
    }

    // ── GI buckets differ ──────────────────────────────────────────────────

    @Test fun `FAST peaks earlier than SLOW`() {
        val fast = AnnouncedMeal(carbsG = 50.0, giBucket = GiBucket.FAST, commitmentPct = 100, announceTimestampMs = 0L)
        val slow = AnnouncedMeal(carbsG = 50.0, giBucket = GiBucket.SLOW, commitmentPct = 100, announceTimestampMs = 0L)

        // At 30 min FAST should have already peaked; SLOW should still be ramping up
        val fastAt30 = rateAt(fast, 30)
        val slowAt30 = rateAt(slow, 30)
        assertTrue(fastAt30 > slowAt30,
                   "at 30 min FAST should exceed SLOW (fast=$fastAt30 slow=$slowAt30)")
    }

    @Test fun `SLOW has a tail contribution that FAST does not`() {
        // FAST has tailFraction=0, SLOW has tailFraction=0.35.
        // Compare rates at 4h post-start: SLOW should still be producing rate, FAST zero.
        val fast = AnnouncedMeal(carbsG = 50.0, giBucket = GiBucket.FAST, commitmentPct = 100, announceTimestampMs = 0L)
        val slow = AnnouncedMeal(carbsG = 50.0, giBucket = GiBucket.SLOW, commitmentPct = 100, announceTimestampMs = 0L)

        val fastLate = rateAt(fast, 180)  // past FAST end (120 min)
        val slowLate = rateAt(slow, 180)
        assertEquals(0.0, fastLate, 0.001)
        assertTrue(slowLate > 0.0, "SLOW should still produce rate at 180 min; got $slowLate")
    }

    // ── Protein contributes a late tail ─────────────────────────────────────

    @Test fun `protein produces a late hump around 3h`() {
        val carbsOnly = AnnouncedMeal(
            carbsG = 30.0, proteinG = 0.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        val withProtein = carbsOnly.copy(proteinG = 40.0)

        // At 3h post-meal, carb rate is tailing off but protein rate should be near its peak
        val rateAt3hCarbsOnly = rateAt(carbsOnly, 180)
        val rateAt3hWithProtein = rateAt(withProtein, 180)
        assertTrue(rateAt3hWithProtein > rateAt3hCarbsOnly + 0.5,
                   "protein should boost 3h rate noticeably: carbs-only=$rateAt3hCarbsOnly with-protein=$rateAt3hWithProtein")
    }

    @Test fun `protein only meal still produces a nonzero curve`() {
        val proteinOnly = AnnouncedMeal(
            carbsG = 0.0, proteinG = 30.0, giBucket = GiBucket.SLOW, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        val rateAt3h = rateAt(proteinOnly, 180)
        assertTrue(rateAt3h > 0.0, "protein-only meal should produce non-zero rate at 3h; got $rateAt3h")
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test fun `zero carbs and zero protein gives zero rate everywhere`() {
        val nothing = AnnouncedMeal(
            carbsG = 0.0, proteinG = 0.0, fatG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        // Fat alone doesn't directly contribute in this model (it shifts GI bucket selection)
        for (t in listOf(15, 60, 120, 180, 240)) {
            assertEquals(0.0, rateAt(nothing, t), 0.001)
        }
    }

    @Test fun `negative carbs rejected by AnnouncedMeal`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnnouncedMeal(carbsG = -10.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100, announceTimestampMs = 0L)
        }
    }

    @Test fun `commitment out of range rejected by AnnouncedMeal`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnnouncedMeal(carbsG = 10.0, giBucket = GiBucket.MEDIUM, commitmentPct = 150, announceTimestampMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnnouncedMeal(carbsG = 10.0, giBucket = GiBucket.MEDIUM, commitmentPct = -5, announceTimestampMs = 0L)
        }
    }

    // ── sampleCurve helper ─────────────────────────────────────────────────

    @Test fun `sampleCurve returns reasonable number of samples`() {
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        val samples = MealCurveBuilder.sampleCurve(meal, nowMs = 0L, sampleIntervalMin = 5)
        // 240 min / 5 min = 48 samples (approximately, depending on inclusive end)
        assertTrue(samples.size in 45..52, "expected ~48 samples, got ${samples.size}")
        // First sample at age 0, last sample at or near totalDuration
        assertEquals(0, samples.first().first)
        assertTrue(samples.last().first >= GiBucket.MEDIUM.totalDurationMinutes - 5)
    }

    @Test fun `sampleCurve from middle of meal skips the past samples`() {
        val meal = AnnouncedMeal(
            carbsG = 50.0, giBucket = GiBucket.MEDIUM, commitmentPct = 100,
            announceTimestampMs = 0L
        )
        // Sample as if we're 60 min into the meal
        val samples = MealCurveBuilder.sampleCurve(meal, nowMs = 60 * 60_000L, sampleIntervalMin = 5)
        assertTrue(samples.first().first >= 60,
                   "first sample after 60-min start should be ≥ 60, got ${samples.first().first}")
    }
}