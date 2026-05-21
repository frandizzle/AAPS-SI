package app.aaps.plugins.aps.smartInsulin.ice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max

/**
 * Pure functions that map an [AnnouncedMeal] into expected ICE values over time.
 *
 * ## Model — three independent contribution curves
 *
 * Each meal contributes ICE through three biological mechanisms with different
 * temporal signatures. They are computed independently and summed.
 *
 * ### 1. Carbohydrate spike (GI-dependent Gaussian)
 *
 * Carbs absorb on a timeline driven by their glycemic index. Peak is reached
 * relatively early (30-120 min depending on GI) and the entire absorption is
 * usually done within 2-4h. **Fat content does not significantly delay carb
 * absorption** in most T1D users — this is a common misconception. Fat adds
 * its own later contribution (see #3) rather than slowing the carbs.
 *
 * Shape: Gaussian centered at [GiBucket.peakMinutes], width ≈ peak/2,
 * integrated area = carbsG × [CARB_GLYCEMIC_LOAD_MGDL_PER_G].
 *
 * ### 2. Protein → gluconeogenesis plateau
 *
 * About half of dietary protein is converted to glucose via hepatic
 * gluconeogenesis. Unlike carbs this is a **sustained, low-amplitude
 * plateau** rather than a discrete peak — onset around 45 min, full
 * plateau by 75 min, sustained for 3-5h depending on amount, then fades.
 *
 * Shape: cosine-smoothed plateau, total area = proteinG × [PROTEIN_TO_GLUCOSE_FRACTION] ×
 * [CARB_GLYCEMIC_LOAD_MGDL_PER_G].
 *
 * ### 3. Fat → insulin resistance plateau
 *
 * Dietary fat does not directly add glucose, but it creates **transient
 * insulin resistance** which from the loop's IOB-based prediction looks
 * indistinguishable from positive ICE (BG runs higher than the insulin
 * model predicts). The effect onsets in parallel with protein, lasts a
 * similar duration, and the amplitude scales with grams of fat.
 *
 * Shape: identical to protein plateau but lower amplitude per gram.
 *
 * ## Combined effect — the "wings & BBQ" example
 *
 * 25g HIGH-GI carbs + 80g protein + 60g fat (a fatty wing meal with sweet sauce):
 *
 * - 0-30 min : sharp carb peak from BBQ sauce sugar (high GI)
 * - 30-60 min: carbs decline, fat/protein not yet contributing → BG drifts toward target
 * - 60-90 min: fat & protein plateaus ramp up
 * - 90 min-4h: sustained plateau holds BG elevated
 * - 4-6h     : plateaus fade, ICE returns to zero
 *
 * Without this dual-plateau model, the loop would react ONLY to the brief
 * initial carb spike and then under-dose for the 4-6h plateau that follows.
 *
 * ## Commitment scaling
 *
 * The user's commitment % from [AnnouncedMeal] linearly scales the entire
 * combined output — if they're only 50% sure of their inputs, every value is
 * halved.
 */
object MealCurveBuilder {

    /**
     * Expected total glucose load delivered per gram of carbs, integrated across
     * the carb absorption window. mg/dL · hours. Calibrated for adult ~70kg user
     * with average carb ratio.
     */
    private const val CARB_GLYCEMIC_LOAD_MGDL_PER_G = 4.5

    /** Fraction of dietary protein that converts to glucose via gluconeogenesis. */
    private const val PROTEIN_TO_GLUCOSE_FRACTION = 0.5

    /**
     * Apparent ICE contribution per gram of fat, integrated across the fat
     * effect window. mg/dL · hours per gram.
     *
     * Fat doesn't add glucose directly — this captures the insulin-resistance
     * effect that makes the IOB-based prediction under-shoot BG. Calibrated so
     * a typical pizza-effect (60g fat, 5h plateau) produces ≈ 7 mg/dL/h average
     * elevation above baseline, which matches commonly-observed pizza patterns
     * in T1D loops.
     */
    private const val FAT_INSULIN_RESISTANCE_LOAD_PER_G = 0.6

    /** When fat/protein contributions begin (minutes from meal start). */
    private const val FAT_PROTEIN_ONSET_MIN = 45

    /** How long the fat/protein ramp-up takes (cosine-smoothed onset). */
    private const val FAT_PROTEIN_ONSET_DURATION_MIN = 30

    /** How long the fat/protein fade-down takes (cosine-smoothed end). */
    private const val FAT_PROTEIN_FADE_DURATION_MIN = 30

    /**
     * Compute the expected ICE rate (mg/dL/h) for [meal] at the given absolute time.
     *
     * Returns 0.0 outside the active window. Always finite.
     */
    fun expectedIceMgdlPerHourAt(meal: AnnouncedMeal, nowMs: Long): Double {
        val ageMinutes = (nowMs - meal.announceTimestampMs) / 60_000.0
        val totalDuration = meal.effectiveTotalDurationMin.toDouble()
        if (ageMinutes < 0.0 || ageMinutes > totalDuration) return 0.0

        val carbRate    = carbRateAt(meal, ageMinutes)
        val proteinRate = proteinRateAt(meal, ageMinutes)
        val fatRate     = fatRateAt(meal, ageMinutes)

        return (carbRate + proteinRate + fatRate) * meal.commitmentFraction
    }

    /**
     * Sample the curve at regular intervals from now through the end of absorption.
     * Useful for UI plotting and offline analysis.
     */
    fun sampleCurve(
        meal: AnnouncedMeal,
        nowMs: Long,
        sampleIntervalMin: Int = 5
    ): List<Pair<Int, Double>> {
        val ageNow = ((nowMs - meal.announceTimestampMs) / 60_000.0).coerceAtLeast(0.0)
        val out = mutableListOf<Pair<Int, Double>>()
        var t = ageNow.toInt()
        while (t <= meal.effectiveTotalDurationMin) {
            val sampleAt = meal.announceTimestampMs + t * 60_000L
            out.add(t to expectedIceMgdlPerHourAt(meal, sampleAt))
            t += sampleIntervalMin
        }
        return out
    }

    // ── Internals: per-component contribution functions ─────────────────────

    /** Carb absorption — Gaussian centred at the GI bucket's peak time. */
    private fun carbRateAt(meal: AnnouncedMeal, ageMin: Double): Double {
        if (meal.carbsG <= 0.0) return 0.0
        val totalLoad = meal.carbsG * CARB_GLYCEMIC_LOAD_MGDL_PER_G
        val peak      = meal.giBucket.peakMinutes.toDouble()
        val width     = peak / 2.0
        return gaussianRate(ageMin, peak, width) * totalLoad
    }

    /** Protein → gluconeogenesis plateau. */
    private fun proteinRateAt(meal: AnnouncedMeal, ageMin: Double): Double {
        if (meal.proteinG <= 0.0) return 0.0
        val totalLoad = meal.proteinG * PROTEIN_TO_GLUCOSE_FRACTION * CARB_GLYCEMIC_LOAD_MGDL_PER_G
        return plateauRateAt(totalLoad, ageMin, meal.fatProteinDurationMin)
    }

    /** Fat → insulin-resistance plateau (looks like positive ICE to the loop). */
    private fun fatRateAt(meal: AnnouncedMeal, ageMin: Double): Double {
        if (meal.fatG <= 0.0) return 0.0
        val totalLoad = meal.fatG * FAT_INSULIN_RESISTANCE_LOAD_PER_G
        return plateauRateAt(totalLoad, ageMin, meal.fatProteinDurationMin)
    }

    /**
     * Cosine-smoothed plateau: zero before onset, smooth ramp up over
     * [FAT_PROTEIN_ONSET_DURATION_MIN], hold at full height, smooth ramp down
     * over [FAT_PROTEIN_FADE_DURATION_MIN], then zero.
     *
     * Plateau height is computed so that the integrated area under the curve
     * equals [totalLoad] (in mg/dL · hours).
     */
    private fun plateauRateAt(totalLoad: Double, ageMin: Double, totalDurationMin: Int): Double {
        if (totalLoad <= 0.0 || totalDurationMin <= FAT_PROTEIN_ONSET_MIN) return 0.0

        val onsetStart  = FAT_PROTEIN_ONSET_MIN.toDouble()
        val onsetEnd    = onsetStart + FAT_PROTEIN_ONSET_DURATION_MIN
        val fadeEnd     = totalDurationMin.toDouble()
        val fadeStart   = fadeEnd - FAT_PROTEIN_FADE_DURATION_MIN

        // Guard for very short total durations (shouldn't happen with normal inputs)
        if (fadeStart <= onsetEnd) return 0.0

        // Effective area in minutes = plateau_width + half of each ramp
        // (cosine ramp integrates to exactly half its peak × ramp duration)
        val plateauWidthMin = fadeStart - onsetEnd
        val effectiveAreaMin = plateauWidthMin +
            FAT_PROTEIN_ONSET_DURATION_MIN / 2.0 +
            FAT_PROTEIN_FADE_DURATION_MIN / 2.0
        val effectiveAreaHours = effectiveAreaMin / 60.0
        val plateauHeight = totalLoad / effectiveAreaHours  // mg/dL/h

        return when {
            ageMin < onsetStart -> 0.0
            ageMin < onsetEnd   -> {
                // Cosine ramp from 0 to plateauHeight
                val frac = (ageMin - onsetStart) / FAT_PROTEIN_ONSET_DURATION_MIN
                plateauHeight * (1.0 - cos(PI * frac)) / 2.0
            }
            ageMin < fadeStart  -> plateauHeight
            ageMin < fadeEnd    -> {
                // Cosine ramp from plateauHeight back to 0
                val frac = (ageMin - fadeStart) / FAT_PROTEIN_FADE_DURATION_MIN
                plateauHeight * (1.0 + cos(PI * frac)) / 2.0
            }
            else -> 0.0
        }
    }

    /**
     * Gaussian rate that integrates to 1.0 over time. Multiplied by the desired
     * total area to produce a rate (mg/dL/h) at age [ageMin].
     *
     * Integral of Gaussian(t; μ, σ) dt over (-∞, ∞) = σ × √(2π). To make the
     * curve integrate to 1.0, we divide by that factor. Multiplied by 60 to
     * convert per-minute rate to per-hour rate.
     */
    private fun gaussianRate(ageMin: Double, centreMin: Double, widthMin: Double): Double {
        if (widthMin <= 0.0) return 0.0
        val z = (ageMin - centreMin) / widthMin
        val rawGaussian = exp(-0.5 * z * z) / (widthMin * 2.5066282746310002)  // √(2π)
        return max(0.0, rawGaussian * 60.0)
    }
}