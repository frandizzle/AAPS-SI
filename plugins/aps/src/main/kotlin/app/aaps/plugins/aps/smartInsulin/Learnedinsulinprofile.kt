package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import org.json.JSONObject

/**
 * Immutable snapshot of the learned insulin activity profile for one [MealMode].
 *
 * Hard limits (physiological bounds):
 *   Peak:  35 – 120 min
 *   DIA:  120 – 480 min
 *
 * A profile is considered "mature" after 5 observations.
 * Confidence saturates at 1.0 after ~30 observations.
 */
data class LearnedInsulinProfile(
    val mode:          MealMode,
    val peakMinutes:   Double,
    val diaMinutes:    Double,
    val confidence:    Double,
    val sampleCount:   Int,
    val lastUpdatedMs: Long
) {
    val isMature: Boolean get() = sampleCount >= 5

    val normalizedConfidence: Double
        get() = (sampleCount.toDouble() / 30.0).coerceAtMost(1.0)

    fun toJson(): JSONObject = JSONObject()
        .put("mode",          mode.name)
        .put("peakMinutes",   peakMinutes)
        .put("diaMinutes",    diaMinutes)
        .put("confidence",    confidence)
        .put("sampleCount",   sampleCount)
        .put("lastUpdatedMs", lastUpdatedMs)

    companion object {
        const val PEAK_MIN_MINUTES =  35.0
        const val PEAK_MAX_MINUTES = 120.0
        const val DIA_MIN_MINUTES  = 120.0
        const val DIA_MAX_MINUTES  = 480.0

        fun defaultFor(mode: MealMode): LearnedInsulinProfile = when (mode) {
            MealMode.FASTING   -> LearnedInsulinProfile(mode, peakMinutes = 65.0, diaMinutes = 240.0, confidence = 0.2, sampleCount = 0, lastUpdatedMs = 0L)
            MealMode.LOW_CARB  -> LearnedInsulinProfile(mode, peakMinutes = 70.0, diaMinutes = 250.0, confidence = 0.2, sampleCount = 0, lastUpdatedMs = 0L)
            MealMode.BREAKFAST -> LearnedInsulinProfile(mode, peakMinutes = 72.0, diaMinutes = 260.0, confidence = 0.2, sampleCount = 0, lastUpdatedMs = 0L)
            MealMode.LUNCH     -> LearnedInsulinProfile(mode, peakMinutes = 75.0, diaMinutes = 270.0, confidence = 0.2, sampleCount = 0, lastUpdatedMs = 0L)
            MealMode.DINNER    -> LearnedInsulinProfile(mode, peakMinutes = 78.0, diaMinutes = 280.0, confidence = 0.2, sampleCount = 0, lastUpdatedMs = 0L)
            MealMode.EXTENDED  -> LearnedInsulinProfile(mode, peakMinutes = 90.0, diaMinutes = 300.0, confidence = 0.2, sampleCount = 0, lastUpdatedMs = 0L)
        }

        fun fromJson(json: JSONObject, mode: MealMode): LearnedInsulinProfile =
            LearnedInsulinProfile(
                mode          = mode,
                peakMinutes   = json.getDouble("peakMinutes").coerceIn(PEAK_MIN_MINUTES, PEAK_MAX_MINUTES),
                diaMinutes    = json.getDouble("diaMinutes").coerceIn(DIA_MIN_MINUTES, DIA_MAX_MINUTES),
                confidence    = json.getDouble("confidence").coerceIn(0.0, 1.0),
                sampleCount   = json.getInt("sampleCount"),
                lastUpdatedMs = json.getLong("lastUpdatedMs")
            )
    }
}