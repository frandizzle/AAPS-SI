package app.aaps.plugins.aps.smartInsulin.ice

import kotlin.math.exp
import kotlin.math.max

/**
 * Pure functions that map an [AnnouncedMeal] into expected ICE values over time.
 *
 * ## Model
 *
 * Total expected ICE at any moment is a sum of three contributions:
 *
 *   1. Primary carb absorption — a skewed bell curve peaking at [GiBucket.peakMinutes],
 *      tapering to zero by [GiBucket.totalDurationMinutes].
 *
 *   2. Late carb tail (only for higher [GiBucket.tailFraction]) — a slower second hump
 *      after the primary peak. Captures the bimodal "pizza effect" of fat-heavy meals.
 *
 *   3. Protein-conversion tail — typically 2–4h post-meal, ≈ 50% of protein grams
 *      eventually converts to glucose. Modeled as a low, broad bump centred around 3h.
 *
 * ## Scaling
 *
 * Each gram of carb produces a fixed total area-under-curve in mg/dL/h. Default
 * [CARB_GLYCEMIC_LOAD_MGDL_PER_G] is calibrated so 50g of medium-GI carbs over 4h
 * yields an integrated total roughly matching a typical postprandial rise (≈ 110 mg/dL
 * peak from baseline if uncovered). Tunable per user via the carbRatio setting in
 * future — for now use the constant.
 *
 * Protein conversion factor [PROTEIN_TO_GLUCOSE_FRACTION] = 0.5 is a common
 * estimate; varies 0.3–0.6 across individuals.
 *
 * Commitment % from the [AnnouncedMeal] scales the entire curve linearly — if the
 * user is only 50% sure of their inputs, every value is halved.
 *
 * ## Why a curve and not a constant
 *
 * The loop needs to know not just "is a meal coming" but the SHAPE of the impulse.
 * Pre-bolus timing, peak dosing pressure, and tail handling all need different
 * decisions at different points in the curve. By exposing the curve as a function,
 * downstream consumers (the loop, the UI) can sample it at any time of interest.
 */
object MealCurveBuilder {

    /**
     * Expected total glucose load delivered per gram of carbs, integrated across
     * the meal's full duration. mg/dL · hours. Calibrated for adult ~70kg user
     * with average carb ratio.
     */
    private const val CARB_GLYCEMIC_LOAD_MGDL_PER_G = 4.5

    /** Fraction of dietary protein that converts to glucose (slow, 2–4h post-meal). */
    private const val PROTEIN_TO_GLUCOSE_FRACTION = 0.5

    /**
     * Compute the expected ICE rate (mg/dL/h) for [meal] at the given absolute time.
     *
     * @param nowMs Current system clock time
     * @return Expected ICE in mg/dL/h. Zero if meal hasn't started or has finished.
     */
    fun expectedIceMgdlPerHourAt(meal: AnnouncedMeal, nowMs: Long): Double {
        val ageMinutes = (nowMs - meal.announceTimestampMs) / 60_000.0
        if (ageMinutes < 0.0 || ageMinutes > meal.giBucket.totalDurationMinutes.toDouble()) return 0.0

        val carbContribution    = carbRateAt(meal, ageMinutes)
        val proteinContribution = proteinRateAt(meal, ageMinutes)

        return (carbContribution + proteinContribution) * meal.commitmentFraction
    }

    /**
     * Sample the curve at regular intervals from now through the end of absorption.
     * Useful for UI plotting and offline analysis.
     *
     * @param nowMs Current time, used as the start of sampling
     * @param sampleIntervalMin Spacing between samples (default 5 min = matches loop cycle)
     * @return List of (ageMinutes, expectedIceMgdlPerH) pairs spanning the active window
     */
    fun sampleCurve(
        meal: AnnouncedMeal,
        nowMs: Long,
        sampleIntervalMin: Int = 5
    ): List<Pair<Int, Double>> {
        val ageNow = ((nowMs - meal.announceTimestampMs) / 60_000.0).coerceAtLeast(0.0)
        val out = mutableListOf<Pair<Int, Double>>()
        var t = ageNow.toInt()
        while (t <= meal.giBucket.totalDurationMinutes) {
            val sampleAt = meal.announceTimestampMs + t * 60_000L
            out.add(t to expectedIceMgdlPerHourAt(meal, sampleAt))
            t += sampleIntervalMin
        }
        return out
    }

    // ── Internals: contribution functions ─────────────────────────────────────

    /**
     * Carb absorption rate at age [ageMin]. Skewed-Gaussian primary peak plus
     * a smaller late hump scaled by tailFraction.
     */
    private fun carbRateAt(meal: AnnouncedMeal, ageMin: Double): Double {
        if (meal.carbsG <= 0.0) return 0.0

        val totalLoad = meal.carbsG * CARB_GLYCEMIC_LOAD_MGDL_PER_G   // mg/dL · h, area under curve
        val primaryFraction = 1.0 - meal.giBucket.tailFraction
        val tailFraction    = meal.giBucket.tailFraction

        // Primary peak: skewed Gaussian, width ≈ peakMinutes/2 (rises faster than it falls)
        val primaryPeak  = meal.giBucket.peakMinutes.toDouble()
        val primaryWidth = primaryPeak / 2.0
        val primaryRate  = gaussianRate(ageMin, primaryPeak, primaryWidth) * totalLoad * primaryFraction

        // Late hump: only for higher GI buckets. Centred at 2× primary peak.
        val tailRate = if (tailFraction > 0.0) {
            val tailCentre = primaryPeak * 2.0
            val tailWidth  = primaryPeak * 0.8
            gaussianRate(ageMin, tailCentre, tailWidth) * totalLoad * tailFraction
        } else 0.0

        return primaryRate + tailRate
    }

    /**
     * Protein conversion to glucose. Broad, low hump centred around 3h post-meal.
     * Total area = proteinG × conversion × CARB_GLYCEMIC_LOAD_MGDL_PER_G.
     */
    private fun proteinRateAt(meal: AnnouncedMeal, ageMin: Double): Double {
        if (meal.proteinG <= 0.0) return 0.0

        val glucoseFromProtein = meal.proteinG * PROTEIN_TO_GLUCOSE_FRACTION
        val proteinLoad        = glucoseFromProtein * CARB_GLYCEMIC_LOAD_MGDL_PER_G
        val centre             = 180.0   // 3h
        val width              = 60.0    // wide spread
        return gaussianRate(ageMin, centre, width) * proteinLoad
    }

    /**
     * Gaussian rate that integrates to 1.0 over time. Multiplied by the desired
     * total area (e.g., totalLoad) to produce a rate (mg/dL/h) at age [ageMin].
     *
     * Integral of Gaussian(t; μ, σ) dt over (-∞, ∞) = σ × √(2π). To make the
     * curve integrate to 1.0, we divide by that factor.
     */
    private fun gaussianRate(ageMin: Double, centreMin: Double, widthMin: Double): Double {
        if (widthMin <= 0.0) return 0.0
        val z = (ageMin - centreMin) / widthMin
        val rawGaussian = exp(-0.5 * z * z) / (widthMin * 2.5066282746310002)  // √(2π)
        // Convert per-minute rate to per-hour rate by multiplying by 60.
        return max(0.0, rawGaussian * 60.0)
    }
}