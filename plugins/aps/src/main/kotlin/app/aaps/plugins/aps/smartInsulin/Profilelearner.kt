package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learns and persists per-[MealMode] insulin activity profiles using EWMA updates.
 * Called by [BolusCurveTracker] after each completed bolus observation.
 */
@Singleton
class ProfileLearner @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    private val profiles: MutableMap<MealMode, LearnedInsulinProfile> = mutableMapOf()

    init {
        MealMode.entries.forEach { mode -> profiles[mode] = loadProfile(mode) }
        aapsLogger.debug(LTag.APS, "ProfileLearner initialised: ${profiles.values.joinToString { it.toString() }}")
    }

    fun getProfile(mode: MealMode): LearnedInsulinProfile =
        profiles[mode] ?: LearnedInsulinProfile.defaultFor(mode)

    fun observeBolusCurve(
        mode:             MealMode,
        observedPeakMins: Double,
        observedDiaMins:  Double,
        learningRate:     Double
    ) {
        val clampedPeak = observedPeakMins.coerceIn(
            LearnedInsulinProfile.PEAK_MIN_MINUTES, LearnedInsulinProfile.PEAK_MAX_MINUTES)
        val clampedDia = observedDiaMins.coerceIn(
            LearnedInsulinProfile.DIA_MIN_MINUTES, LearnedInsulinProfile.DIA_MAX_MINUTES)

        if (clampedPeak >= clampedDia) {
            aapsLogger.debug(LTag.APS, "ProfileLearner: rejecting peak=$clampedPeak >= dia=$clampedDia for $mode")
            return
        }

        val current = getProfile(mode)
        val alpha   = (learningRate * mode.learningWeight).coerceIn(0.01, 0.5)
        val newPeak = ewma(current.peakMinutes, clampedPeak, alpha)
        val newDia  = if (mode.diaLearningEnabled) ewma(current.diaMinutes, clampedDia, alpha)
        else current.diaMinutes
        val newSampleCount = current.sampleCount + 1
        val newConfidence  = current.normalizedConfidence.coerceAtLeast(alpha).coerceAtMost(1.0)

        val updated = current.copy(
            peakMinutes   = newPeak,
            diaMinutes    = newDia,
            confidence    = newConfidence,
            sampleCount   = newSampleCount,
            lastUpdatedMs = System.currentTimeMillis()
        )
        profiles[mode] = updated
        saveProfile(updated)

        aapsLogger.debug(LTag.APS,
                         "ProfileLearner updated ${mode.label}: " +
                             "peak ${fmtChange(current.peakMinutes, newPeak)} " +
                             "dia ${fmtChange(current.diaMinutes, newDia)} " +
                             "α=${"%.3f".format(alpha)} n=$newSampleCount")
    }

    fun resetProfile(mode: MealMode) {
        val default = LearnedInsulinProfile.defaultFor(mode)
        profiles[mode] = default
        saveProfile(default)
        aapsLogger.debug(LTag.APS, "ProfileLearner: reset $mode to defaults")
    }

    fun resetAll() { MealMode.entries.forEach { resetProfile(it) } }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun ewma(old: Double, observed: Double, alpha: Double): Double =
        (1.0 - alpha) * old + alpha * observed

    private fun prefKeyFor(mode: MealMode): StringKey = when (mode) {
        MealMode.FASTING   -> StringKey.ApsSmartInsulinProfileFasting
        MealMode.LOW_CARB  -> StringKey.ApsSmartInsulinProfileLowCarb
        MealMode.BREAKFAST -> StringKey.ApsSmartInsulinProfileBreakfast
        MealMode.LUNCH     -> StringKey.ApsSmartInsulinProfileLunch
        MealMode.DINNER    -> StringKey.ApsSmartInsulinProfileDinner
        MealMode.EXTENDED  -> StringKey.ApsSmartInsulinProfileExtended
    }

    private fun loadProfile(mode: MealMode): LearnedInsulinProfile = try {
        val json = preferences.get(prefKeyFor(mode))
        if (json.isBlank()) LearnedInsulinProfile.defaultFor(mode)
        else LearnedInsulinProfile.fromJson(JSONObject(json), mode)
    } catch (_: Exception) {
        LearnedInsulinProfile.defaultFor(mode)
    }

    private fun saveProfile(profile: LearnedInsulinProfile) {
        try {
            preferences.put(prefKeyFor(profile.mode), profile.toJson().toString())
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ProfileLearner: failed to save ${profile.mode}: ${e.message}")
        }
    }

    private fun fmtChange(old: Double, new: Double): String = "%.1f→%.1f".format(old, new)
}