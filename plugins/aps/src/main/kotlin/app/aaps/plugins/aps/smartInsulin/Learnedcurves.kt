package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import org.json.JSONObject

/**
 * Represents the global physiological response to insulin.
 * Learned only during FASTING corrections to ensure a pure drug signal.
 */
data class LearnedInsulinKinetics(
    val peakMinutes:    Double,
    val diaMinutes:     Double,
    val sampleCount:    Int,
    val lastUpdatedMs:  Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("peakMinutes",   peakMinutes)
        put("diaMinutes",    diaMinutes)
        put("sampleCount",   sampleCount)
        put("lastUpdatedMs", lastUpdatedMs)
    }

    companion object {
        const val PEAK_MIN = 35.0
        const val PEAK_MAX = 100.0
        const val DIA_MIN  = 180.0
        const val DIA_MAX  = 480.0

        fun fromJson(json: JSONObject): LearnedInsulinKinetics =
            LearnedInsulinKinetics(
                peakMinutes   = json.optDouble("peakMinutes",   75.0),
                diaMinutes    = json.optDouble("diaMinutes",    360.0),
                sampleCount   = json.optInt("sampleCount",      0),
                lastUpdatedMs = json.optLong("lastUpdatedMs",   0L)
            )

        fun default() = LearnedInsulinKinetics(75.0, 360.0, 0, 0L)
    }
}

/**
 * Represents how quickly a specific type of meal is absorbed.
 * Learned during MEAL modes by observing the BG rise and return to baseline.
 */
data class LearnedCarbAbsorption(
    val mode:              MealMode,
    val absorptionMinutes: Double, // Total time until carbs are gone (DIA for carbs)
    val peakMinutes:       Double, // Time to highest BG impact
    val sampleCount:       Int,
    val lastUpdatedMs:     Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode",              mode.name)
        put("absorptionMinutes", absorptionMinutes)
        put("peakMinutes",       peakMinutes)
        put("sampleCount",       sampleCount)
        put("lastUpdatedMs",     lastUpdatedMs)
    }

    companion object {
        const val ABS_MIN = 60.0
        const val ABS_MAX = 600.0

        fun fromJson(json: JSONObject, mode: MealMode): LearnedCarbAbsorption =
            LearnedCarbAbsorption(
                mode              = mode,
                absorptionMinutes = json.optDouble("absorptionMinutes", 300.0),
                peakMinutes       = json.optDouble("peakMinutes",       90.0),
                sampleCount       = json.optInt("sampleCount",          0),
                lastUpdatedMs     = json.optLong("lastUpdatedMs",       0L)
            )

        fun defaultFor(mode: MealMode) = LearnedCarbAbsorption(mode, 300.0, 90.0, 0, 0L)    }
}
