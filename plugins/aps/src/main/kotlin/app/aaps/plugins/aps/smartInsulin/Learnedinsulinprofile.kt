package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import org.json.JSONObject

/**
 * Represents a learned insulin activity profile for a specific [MealMode].
 *
 * Values converge over time from sensible priors toward the user's actual
 * physiology via EWMA updates in [ProfileLearner].
 *
 * @param mode          The [MealMode] this profile applies to
 * @param peakMinutes   Learned time-to-peak insulin activity (minutes)
 * @param diaMinutes    Learned full duration of insulin action (minutes)
 * @param sampleCount   Number of completed bolus observations that have
 *                      contributed to this profile
 * @param lastUpdatedMs Epoch ms of the last EWMA update
 */
data class LearnedInsulinProfile(
    val mode:           MealMode,
    val peakMinutes:    Double,
    val diaMinutes:     Double,
    val sampleCount:    Int,
    val lastUpdatedMs:  Long,
    val lastShiftPeak:  Double = 0.0,
    val lastShiftDia:   Double = 0.0,
    val lastObservedPeak: Double = 0.0,
    val lastObservedDia:  Double = 0.0,
    val lastWeight:     Double = 0.0
) {
    // Silently clamp to physiological bounds on construction — prevents corrupt
    // JSON or learner math errors from producing dangerous out-of-range values.
    val safePeakMinutes: Double get() = peakMinutes.coerceIn(PEAK_MIN_MINUTES, PEAK_MAX_MINUTES)
    val safeDiaMinutes:  Double get() = diaMinutes.coerceIn(DIA_MIN_MINUTES, DIA_MAX_MINUTES)

    // ── Serialisation ────────────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode",          mode.name)
        put("peakMinutes",   peakMinutes)
        put("diaMinutes",    diaMinutes)
        put("sampleCount",   sampleCount)
        put("lastUpdatedMs", lastUpdatedMs)
        put("lastShiftPeak", lastShiftPeak)
        put("lastShiftDia",  lastShiftDia)
        put("lastObservedPeak", lastObservedPeak)
        put("lastObservedDia",  lastObservedDia)
        put("lastWeight",    lastWeight)
    }

    // ── Derived helpers ──────────────────────────────────────────────────────

    /**
     * True once enough samples have accumulated that we trust this profile
     * more than the prior. Used by [ProfileLearner] to decide blend ratio.
     */
    val isMature: Boolean get() = sampleCount >= MIN_SAMPLES_FOR_MATURITY

    /**
     * Fraction of the way between prior and fully learned, derived purely from
     * [sampleCount]. Saturates at 1.0 after [FULL_CONFIDENCE_SAMPLES] observations.
     * Not stored — always recomputed so it can never desync from sample count.
     */
    val normalizedConfidence: Double
        get() = (sampleCount.toDouble() / FULL_CONFIDENCE_SAMPLES).coerceIn(0.0, 1.0)

    override fun toString(): String =
        "LearnedInsulinProfile(mode=${mode.label} peak=%.1f dia=%.1f conf=%.2f n=$sampleCount)"
            .format(peakMinutes, diaMinutes, normalizedConfidence)

    companion object {

        private const val MIN_SAMPLES_FOR_MATURITY  = 5
        private const val FULL_CONFIDENCE_SAMPLES   = 30

        // ── Hard bounds — physiologically reasonable limits ──────────────────
        const val PEAK_MIN_MINUTES = 35.0
        const val PEAK_MAX_MINUTES = 120.0
        const val DIA_MIN_MINUTES  = 120.0
        const val DIA_MAX_MINUTES  = 480.0

        // Fallback constants used only when profileFunction/activeInsulin are unavailable at seed time
        const val FALLBACK_PEAK_MINS = 75.0
        const val FALLBACK_DIA_MINS  = 300.0

        /**
         * Seed defaults from the actual profile DIA/peak rather than hardcoded values.
         * All modes start with the same peak/DIA — divergence happens through EWMA learning.
         */
        fun defaultFor(mode: MealMode, profilePeakMins: Double = FALLBACK_PEAK_MINS, profileDiaMins: Double = FALLBACK_DIA_MINS): LearnedInsulinProfile =
            LearnedInsulinProfile(mode, peakMinutes = profilePeakMins, diaMinutes = profileDiaMins,
                                  sampleCount = 0, lastUpdatedMs = 0L)

        /**
         * Deserialise from JSON stored in SharedPreferences.
         * Returns the default profile for [mode] if JSON is missing or malformed.
         */
        fun fromJson(json: JSONObject, mode: MealMode): LearnedInsulinProfile =
            LearnedInsulinProfile(
                mode          = mode,  // trust the caller — pref key already identifies the slot
                peakMinutes   = json.getDouble("peakMinutes"),
                diaMinutes    = json.getDouble("diaMinutes"),
                // "confidence" key intentionally ignored — now derived from sampleCount
                sampleCount   = json.getInt("sampleCount"),
                lastUpdatedMs = json.getLong("lastUpdatedMs"),
                lastShiftPeak = json.optDouble("lastShiftPeak", 0.0),
                lastShiftDia  = json.optDouble("lastShiftDia", 0.0),
                lastObservedPeak = json.optDouble("lastObservedPeak", 0.0),
                lastObservedDia  = json.optDouble("lastObservedDia", 0.0),
                lastWeight    = json.optDouble("lastWeight", 0.0)
            )
    }
}