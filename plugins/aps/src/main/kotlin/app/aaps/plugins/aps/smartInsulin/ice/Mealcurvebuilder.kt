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
     * Default expected glucose load per gram of carbs (mg/dL · hours), used when
     * no per-user value is supplied. Equivalent to a 70kg adult with ISF=36 mg/dL/U
     * (≈ 2.0 mmol/U) and CR=8 g/U → ISF/CR = 4.5 mg/dL per gram.
     *
     * **Per-user calibration**: the loop SHOULD derive this dynamically from the
     * current profile as `ISF_mgdl / CR_grams_per_unit` and pass it into
     * [expectedIceMgdlPerHourAt]. That makes ICE responsiveness automatically
     * track the user's profile — tighten CR → ICE doses more aggressively, loosen
     * CR → ICE backs off. The constant below is only a safe fallback for callers
     * (like unit tests) that don't have a profile in scope.
     */
    const val DEFAULT_CARB_LOAD_PER_G_MGDL = 4.5

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
     * Per-component breakdown of the meal's instantaneous ICE rate (mg/dL/h).
     * All three values already include the commitment fraction scaling, so
     * `sum == expectedIceMgdlPerHourAt(...)` for any given inputs.
     */
    data class ComponentRates(
        val carbMgdlH: Double,
        val proteinMgdlH: Double,
        val fatMgdlH: Double
    ) {
        val totalMgdlH: Double get() = carbMgdlH + proteinMgdlH + fatMgdlH
    }

    /**
     * Returns the meal's current ICE rate broken into carb / protein / fat
     * contributions. Useful for diagnostic display when the total rate alone
     * doesn't make the source obvious — e.g. when carbs are nearly absorbed
     * but the protein/fat plateau is still at full strength, the total can
     * look surprisingly high relative to remaining COB. Use this to see
     * which component is responsible.
     *
     * Returns all-zero outside the active window.
     */
    fun componentRatesAt(
        meal: AnnouncedMeal,
        nowMs: Long,
        carbLoadPerG: Double = DEFAULT_CARB_LOAD_PER_G_MGDL
    ): ComponentRates {
        val ageMinutes = (nowMs - meal.announceTimestampMs) / 60_000.0
        val totalDuration = meal.effectiveTotalDurationMin.toDouble()
        if (ageMinutes < 0.0 || ageMinutes > totalDuration)
            return ComponentRates(0.0, 0.0, 0.0)

        val safeLoad = carbLoadPerG.coerceAtLeast(0.5)
        val c = meal.commitmentFraction
        return ComponentRates(
            carbMgdlH    = carbRateAt(meal, ageMinutes, safeLoad) * c,
            proteinMgdlH = proteinRateAt(meal, ageMinutes, safeLoad) * c,
            fatMgdlH     = fatRateAt(meal, ageMinutes, safeLoad) * c
        )
    }

    /**
     * Compute the expected ICE rate (mg/dL/h) for [meal] at the given absolute time.
     *
     * @param carbLoadPerG glucose impact per gram of carbs (mg/dL · h per g). Pass
     *   the user's `ISF_mgdl / CR_grams_per_unit` from the current profile to make
     *   ICE responsiveness track their profile. Defaults to a population-average
     *   value when omitted (e.g. from unit tests).
     *
     * Returns 0.0 outside the active window. Always finite.
     */
    fun expectedIceMgdlPerHourAt(
        meal: AnnouncedMeal,
        nowMs: Long,
        carbLoadPerG: Double = DEFAULT_CARB_LOAD_PER_G_MGDL
    ): Double {
        val ageMinutes = (nowMs - meal.announceTimestampMs) / 60_000.0
        val totalDuration = meal.effectiveTotalDurationMin.toDouble()
        if (ageMinutes < 0.0 || ageMinutes > totalDuration) return 0.0

        val safeLoad    = carbLoadPerG.coerceAtLeast(0.5)  // guard against zero/negative CR
        val carbRate    = carbRateAt(meal, ageMinutes, safeLoad)
        val proteinRate = proteinRateAt(meal, ageMinutes, safeLoad)
        val fatRate     = fatRateAt(meal, ageMinutes, safeLoad)

        return (carbRate + proteinRate + fatRate) * meal.commitmentFraction
    }

    /**
     * Sample the curve at regular intervals from now through the end of absorption.
     * Useful for UI plotting and offline analysis.
     */
    fun sampleCurve(
        meal: AnnouncedMeal,
        nowMs: Long,
        sampleIntervalMin: Int = 5,
        carbLoadPerG: Double = DEFAULT_CARB_LOAD_PER_G_MGDL
    ): List<Pair<Int, Double>> {
        val ageNow = ((nowMs - meal.announceTimestampMs) / 60_000.0).coerceAtLeast(0.0)
        val out = mutableListOf<Pair<Int, Double>>()
        var t = ageNow.toInt()
        while (t <= meal.effectiveTotalDurationMin) {
            val sampleAt = meal.announceTimestampMs + t * 60_000L
            out.add(t to expectedIceMgdlPerHourAt(meal, sampleAt, carbLoadPerG))
            t += sampleIntervalMin
        }
        return out
    }

    /**
     * Fraction of carbs absorbed by [ageMin], in 0.0–1.0. Uses the Gaussian CDF
     * truncated to the carb absorption window — so 0 returned when the curve
     * hasn't ramped up yet, 1.0 once past the window.
     *
     * Matches the rate function [carbRateAt] (same Gaussian centred at
     * [GiBucket.peakMinutes], width = peak/2). Used by UI "remaining COB"
     * displays where calling [sampleCurve] just to integrate would be overkill.
     */
    fun carbAbsorbedFraction(meal: AnnouncedMeal, ageMin: Double): Double {
        if (meal.carbsG <= 0.0) return 0.0
        if (ageMin <= 0.0) return 0.0
        val totalDur = meal.giBucket.totalDurationMinutes.toDouble()
        if (ageMin >= totalDur) return 1.0
        val peak  = meal.giBucket.peakMinutes.toDouble()
        val width = peak / 2.0
        // CDF at age, normalised by CDF range across the truncated window
        val cdfAtAge   = normalCdf((ageMin   - peak) / width)
        val cdfAtStart = normalCdf((0.0      - peak) / width)
        val cdfAtEnd   = normalCdf((totalDur - peak) / width)
        val denom = cdfAtEnd - cdfAtStart
        if (denom < 1e-9) return 0.0
        return ((cdfAtAge - cdfAtStart) / denom).coerceIn(0.0, 1.0)
    }

    /**
     * Fraction of protein-or-fat absorbed by [ageMin], in 0.0–1.0. Returns 0.0
     * before onset (45 min), 1.0 past the window, and analytical cumulative
     * integral of the cosine-smoothed plateau in between.
     *
     * Used for both protein and fat — they share the same plateau shape and
     * window length. Caller scales by the respective gram counts.
     */
    fun plateauAbsorbedFraction(ageMin: Double, totalDurationMin: Int): Double {
        if (totalDurationMin <= FAT_PROTEIN_ONSET_MIN) return 0.0
        if (ageMin <= FAT_PROTEIN_ONSET_MIN) return 0.0
        if (ageMin >= totalDurationMin.toDouble()) return 1.0

        val onsetStart  = FAT_PROTEIN_ONSET_MIN.toDouble()
        val onsetEnd    = onsetStart + FAT_PROTEIN_ONSET_DURATION_MIN
        val fadeEnd     = totalDurationMin.toDouble()
        val fadeStart   = fadeEnd - FAT_PROTEIN_FADE_DURATION_MIN
        if (fadeStart <= onsetEnd) return 0.0

        // Total "area" in plateau-height-equivalent minutes. The cosine ramps
        // each integrate to half their duration × peak height — so each
        // contributes (duration/2) units of area.
        val plateauWidth   = fadeStart - onsetEnd
        val totalArea      = plateauWidth +
            FAT_PROTEIN_ONSET_DURATION_MIN / 2.0 +
            FAT_PROTEIN_FADE_DURATION_MIN / 2.0

        // Compute cumulative area swept from t=0 up to ageMin.
        val absorbedArea = when {
            ageMin < onsetEnd -> {
                // Inside ramp-up. Cumulative ∫₀ˢ (1−cos(πu/D))/2 du
                //   = s/2 − D/(2π) · sin(πs/D)
                // where s = age − onsetStart, D = onset duration.
                val s = ageMin - onsetStart
                val d = FAT_PROTEIN_ONSET_DURATION_MIN.toDouble()
                s / 2.0 - d / (2.0 * Math.PI) * Math.sin(Math.PI * s / d)
            }
            ageMin < fadeStart -> {
                // Past ramp-up, in plateau. Ramp contributed D/2 = half-duration.
                val rampUpArea = FAT_PROTEIN_ONSET_DURATION_MIN / 2.0
                rampUpArea + (ageMin - onsetEnd)
            }
            else -> {
                // In fade region. Add ramp + full plateau + cumulative fade.
                // Cumulative fade ∫₀ˢ (1+cos(πu/D))/2 du
                //   = s/2 + D/(2π) · sin(πs/D)
                val rampUpArea = FAT_PROTEIN_ONSET_DURATION_MIN / 2.0
                val s = ageMin - fadeStart
                val d = FAT_PROTEIN_FADE_DURATION_MIN.toDouble()
                val fadeArea = s / 2.0 + d / (2.0 * Math.PI) * Math.sin(Math.PI * s / d)
                rampUpArea + plateauWidth + fadeArea
            }
        }

        return (absorbedArea / totalArea).coerceIn(0.0, 1.0)
    }

    /**
     * Standard normal CDF — Abramowitz & Stegun approximation, accurate to ~7.5e-8.
     * Used to compute cumulative carb absorption via the Gaussian rate function.
     */
    private fun normalCdf(z: Double): Double {
        val absZ = Math.abs(z)
        val t = 1.0 / (1.0 + 0.2316419 * absZ)
        val d = 0.3989422804014327 * Math.exp(-0.5 * z * z)
        val p = d * t * (0.31938153 + t * (-0.356563782 +
            t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (z >= 0.0) 1.0 - p else p
    }

    // ── Internals: per-component contribution functions ─────────────────────

    /** Carb absorption — Gaussian centred at the GI bucket's peak time. */
    private fun carbRateAt(meal: AnnouncedMeal, ageMin: Double, carbLoadPerG: Double): Double {
        if (meal.carbsG <= 0.0) return 0.0
        // ice-step38: respect the GI bucket's onset delay. Gastric emptying
        // takes a few minutes — before the onset, no carbs are absorbing
        // (rate = 0). After the onset, the Gaussian starts ramping up from
        // its left tail relative to the post-onset clock. This stops the
        // loop from dosing on phantom absorption in the first few minutes
        // after meal announce.
        val effectiveAge = ageMin - meal.giBucket.onsetMinutes
        if (effectiveAge < 0.0) return 0.0
        val totalLoad = meal.carbsG * carbLoadPerG
        val peak      = meal.giBucket.peakMinutes.toDouble()
        val width     = peak / 2.0
        return gaussianRate(effectiveAge, peak, width) * totalLoad
    }

    /** Protein → gluconeogenesis plateau. */
    private fun proteinRateAt(meal: AnnouncedMeal, ageMin: Double, carbLoadPerG: Double): Double {
        if (meal.proteinG <= 0.0) return 0.0
        val totalLoad = meal.proteinG * PROTEIN_TO_GLUCOSE_FRACTION * carbLoadPerG
        return plateauRateAt(totalLoad, ageMin, meal.fatProteinDurationMin)
    }

    /** Fat → insulin-resistance plateau (looks like positive ICE to the loop).
     *
     * Fat load is scaled proportionally to carbLoadPerG, preserving the
     * empirical carbs:fat ratio of 4.5:0.6 ≈ 7.5 mg/dL per gram. So a user
     * with tighter CR (more insulin-sensitive) gets stronger carb response
     * AND stronger fat response in proportion. */
    private fun fatRateAt(meal: AnnouncedMeal, ageMin: Double, carbLoadPerG: Double): Double {
        if (meal.fatG <= 0.0) return 0.0
        // Fat load tracks carb load via the calibrated ratio.
        val fatLoadPerG = carbLoadPerG * (FAT_INSULIN_RESISTANCE_LOAD_PER_G / DEFAULT_CARB_LOAD_PER_G_MGDL)
        val totalLoad = meal.fatG * fatLoadPerG
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