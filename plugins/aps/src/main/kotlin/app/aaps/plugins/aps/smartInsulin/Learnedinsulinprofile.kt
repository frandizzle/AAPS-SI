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
 * @param confidence    0.0–1.0 — how much real data has shaped this profile.
 *                      Starts low, approaches 1.0 as sample count grows.
 * @param sampleCount   Number of completed bolus observations that have
 *                      contributed to this profile
 * @param lastUpdatedMs Epoch ms of the last EWMA update
 */
data class LearnedInsulinProfile(
    val mode:           MealMode,
    val peakMinutes:    Double,
    val diaMinutes:     Double,
    val confidence:     Double,
    val sampleCount:    Int,
    val lastUpdatedMs:  Long
) {

    // ── Serialisation ────────────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode",          mode.name)
        put("peakMinutes",   peakMinutes)
        put("diaMinutes",    diaMinutes)
        put("confidence",    confidence)
        put("sampleCount",   sampleCount)
        put("lastUpdatedMs", lastUpdatedMs)
    }

    // ── Derived helpers ──────────────────────────────────────────────────────

    /**
     * True once enough samples have accumulated that we trust this profile
     * more than the prior. Used by [ProfileLearner] to decide blend ratio.
     */
    val isMature: Boolean get() = sampleCount >= MIN_SAMPLES_FOR_MATURITY

    /**
     * Fraction of the way between prior and fully learned.
     * Saturates at 1.0 after [FULL_CONFIDENCE_SAMPLES] observations.
     */
    val normalizedConfidence: Double
        get() = (sampleCount.toDouble() / FULL_CONFIDENCE_SAMPLES).coerceIn(0.0, 1.0)

    override fun toString(): String =
        "LearnedInsulinProfile(mode=${mode.label} peak=%.1f dia=%.1f conf=%.2f n=$sampleCount)"
            .format(peakMinutes, diaMinutes, confidence)

    companion object {

        private const val MIN_SAMPLES_FOR_MATURITY  = 5
        private const val FULL_CONFIDENCE_SAMPLES   = 30

        // ── Hard bounds — physiologically reasonable limits ──────────────────
        const val PEAK_MIN_MINUTES = 35.0
        const val PEAK_MAX_MINUTES = 120.0
        const val DIA_MIN_MINUTES  = 120.0
        const val DIA_MAX_MINUTES  = 480.0

        /**
         * Physiologically sensible starting priors per mode.
         * These will converge toward the user's real values over ~30 boluses.
         *
         * FASTING   — cleanest signal, tighter prior
         * LOW_CARB  — slightly longer DIA due to lower glucose disposal rate
         * MEAL      — peak slightly later due to competing carb absorption
         * EXTENDED  — longest DIA, most uncertainty
         */
        const val FALLBACK_PEAK_MINS = 75.0   // used if no profile available at seed time
        const val FALLBACK_DIA_MINS  = 300.0  // used if no profile available at seed time

        /**
         * Seed defaults from the actual profile DIA/peak rather than hardcoded values.
         * Meal modes get a small upward offset on DIA since carb absorption extends apparent action.
         */
        fun defaultFor(mode: MealMode, profilePeakMins: Double = FALLBACK_PEAK_MINS, profileDiaMins: Double = FALLBACK_DIA_MINS): LearnedInsulinProfile {
            val (peak, dia) = when (mode) {
                MealMode.FASTING   -> Pair(profilePeakMins,        profileDiaMins)
                MealMode.LOW_CARB  -> Pair(profilePeakMins,        profileDiaMins + 30.0)
                MealMode.BREAKFAST -> Pair(profilePeakMins + 5.0,  profileDiaMins + 60.0)
                MealMode.LUNCH     -> Pair(profilePeakMins + 5.0,  profileDiaMins + 60.0)
                MealMode.DINNER    -> Pair(profilePeakMins + 10.0, profileDiaMins + 90.0)
                MealMode.EXTENDED  -> Pair(profilePeakMins + 15.0, profileDiaMins + 120.0)
            }
            val confidence = when (mode) {
                MealMode.FASTING -> 0.3
                else             -> 0.2
            }
            return LearnedInsulinProfile(mode, peakMinutes = peak, diaMinutes = dia,
                                         confidence = confidence, sampleCount = 0, lastUpdatedMs = 0L)
        }

        /**
         * Deserialise from JSON stored in SharedPreferences.
         * Returns the default profile for [mode] if JSON is missing or malformed.
         */
        fun fromJson(json: JSONObject, mode: MealMode): LearnedInsulinProfile =
            try {
                LearnedInsulinProfile(
                    mode          = MealMode.valueOf(json.getString("mode")),
                    peakMinutes   = json.getDouble("peakMinutes"),
                    diaMinutes    = json.getDouble("diaMinutes"),
                    confidence    = json.getDouble("confidence"),
                    sampleCount   = json.getInt("sampleCount"),
                    lastUpdatedMs = json.getLong("lastUpdatedMs")
                )
            } catch (_: Exception) {
                defaultFor(mode)
            }
    }
}