package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.SmartInsulinLearner
import app.aaps.core.keys.StringKey
import java.util.Locale
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Learns and persists per-[MealMode] insulin activity profiles using
 * Exponential Weighted Moving Average (EWMA) updates.
 */
@SingleIn(AppScope::class)
class ProfileLearner @Inject constructor(
    private val aapsLogger:      AAPSLogger,
    private val sp:              SP,
    private val profileFunction: ProfileFunction,
    private val activePlugin:    ActivePlugin
) : SmartInsulinLearner {

    private val profiles: MutableMap<MealMode, LearnedInsulinProfile> = mutableMapOf()

    private companion object {
        private const val PEAK_DIA_MIN_GAP_MINUTES = 15.0
        private val PEAK_LEARNING_MODES = setOf(MealMode.FASTING, MealMode.LOW_CARB)
    }

    init {
        MealMode.entries.forEach { mode ->
            profiles[mode] = loadProfile(mode)
        }
    }

    fun getProfile(mode: MealMode): LearnedInsulinProfile =
        profiles[mode] ?: profileSeededDefault(mode)

    private fun profileSeededDefault(mode: MealMode): LearnedInsulinProfile {
        val profile  = profileFunction.getProfile()
        val diaMins  = profile?.dia?.times(60.0) ?: LearnedInsulinProfile.FALLBACK_DIA_MINS
        // Peak comes from whichever Insulin plugin is actually configured in Config Builder
        // (Rapid-Acting=75, Ultra-rapid/Fiasp=55, Lyumjev=45, or the user-set value for
        // Free-Peak Oref) — already in minutes, no conversion needed. Previously hardcoded to
        // 55.0 (Fiasp's value) regardless of what insulin was selected, so switching insulin
        // types and resetting profile learning always silently reseeded Fiasp's peak.
        val peakMins = activePlugin.activeInsulin.peak.toDouble()
        return LearnedInsulinProfile.defaultFor(mode, peakMins, diaMins)
    }

    fun observeBolusCurve(
        mode:             MealMode,
        observedPeakMins: Double?,
        observedDiaMins:  Double,
        learningRate:     Double
    ) {
        val peakLearningEnabled = mode in PEAK_LEARNING_MODES
        if (peakLearningEnabled && observedPeakMins != null) {
            if (observedPeakMins >= observedDiaMins - PEAK_DIA_MIN_GAP_MINUTES) {
                return
            }
        }

        val current = getProfile(mode)

        val baseAlpha       = (learningRate * mode.learningWeight).coerceIn(0.01, 0.5)
        val confAttenuation = 1.0 - (current.normalizedConfidence * 0.5)
        val alpha           = baseAlpha * confAttenuation

        val newPeak: Double
        val peakUpdated: Boolean
        if (peakLearningEnabled && observedPeakMins != null) {
            val clampedPeak = observedPeakMins.coerceIn(
                LearnedInsulinProfile.PEAK_MIN_MINUTES,
                LearnedInsulinProfile.PEAK_MAX_MINUTES
            )
            newPeak     = ewma(current.peakMinutes, clampedPeak, alpha)
            peakUpdated = true
        } else {
            newPeak     = current.peakMinutes
            peakUpdated = false
        }

        val clampedDia = observedDiaMins.coerceIn(
            LearnedInsulinProfile.DIA_MIN_MINUTES,
            LearnedInsulinProfile.DIA_MAX_MINUTES
        )
        val newDia: Double
        val diaUpdated: Boolean
        if (mode.diaLearningEnabled) {
            newDia     = ewma(current.diaMinutes, clampedDia, alpha)
            diaUpdated = true
        } else {
            newDia     = current.diaMinutes
            diaUpdated = false
        }

        if (!peakUpdated && !diaUpdated) return

        val newSampleCount = current.sampleCount + 1
        val updated = current.copy(
            peakMinutes   = newPeak,
            diaMinutes    = newDia,
            sampleCount   = newSampleCount,
            lastUpdatedMs = System.currentTimeMillis()
        )

        profiles[mode] = updated
        saveProfile(updated)
    }

    fun resetProfile(mode: MealMode) {
        val default = profileSeededDefault(mode)
        profiles[mode] = default
        saveProfile(default)
    }

    fun resetAll() {
        MealMode.entries.forEach { resetProfile(it) }
    }

    private fun ewma(old: Double, observed: Double, alpha: Double): Double =
        (1.0 - alpha) * old + alpha * observed

    private fun prefKeyFor(mode: MealMode): StringKey =
        when (mode) {
            MealMode.FASTING       -> StringNonKey.ApsSmartInsulinProfileFasting
            MealMode.LOW_CARB      -> StringNonKey.ApsSmartInsulinProfileLowCarb
            MealMode.BREAKFAST     -> StringNonKey.ApsSmartInsulinProfileBreakfast
            MealMode.LUNCH         -> StringNonKey.ApsSmartInsulinProfileLunch
            MealMode.DINNER        -> StringNonKey.ApsSmartInsulinProfileDinner
            MealMode.EXTENDED      -> StringNonKey.ApsSmartInsulinProfileExtended
            MealMode.UAM_BREAKFAST -> StringNonKey.ApsSmartInsulinProfileUamBreakfast
            MealMode.UAM_LUNCH     -> StringNonKey.ApsSmartInsulinProfileUamLunch
            MealMode.UAM_DINNER    -> StringNonKey.ApsSmartInsulinProfileUamDinner
            MealMode.UAM_SNACK     -> StringNonKey.ApsSmartInsulinProfileUamSnack
            MealMode.UAM_AFTERNOON    -> StringNonKey.ApsSmartInsulinProfileUamAfternoon
            MealMode.UAM_PROTEIN_FAT  -> StringNonKey.ApsSmartInsulinProfileUamProteinFat
        }

    private fun loadProfile(mode: MealMode): LearnedInsulinProfile {
        return try {
            val json = sp.getString(prefKeyFor(mode).key, prefKeyFor(mode).defaultValue)
            if (json.isNullOrBlank()) return safeSeededDefault(mode)
            LearnedInsulinProfile.fromJson(JSONObject(json), mode)
        } catch (_: Exception) {
            safeSeededDefault(mode)
        }
    }

    private fun safeSeededDefault(mode: MealMode): LearnedInsulinProfile {
        return try {
            profileSeededDefault(mode)
        } catch (_: Exception) {
            // Last-resort fallback if reading the active insulin plugin itself throws —
            // use the same generic fallback constant as everywhere else, not a Fiasp-specific value.
            LearnedInsulinProfile.defaultFor(
                mode,
                LearnedInsulinProfile.FALLBACK_PEAK_MINS,
                LearnedInsulinProfile.FALLBACK_DIA_MINS
            )
        }
    }

    override fun resetProfiles() {
        val profile  = profileFunction.getProfile()
        val diaMins  = profile?.dia?.times(60.0) ?: LearnedInsulinProfile.FALLBACK_DIA_MINS
        // Same fix as profileSeededDefault() — read the actual configured insulin's peak
        // instead of hardcoding Fiasp's 55.
        val peakMins = activePlugin.activeInsulin.peak.toDouble()
        MealMode.entries.forEach { mode ->
            val seeded = LearnedInsulinProfile.defaultFor(mode, peakMins, diaMins)
            profiles[mode] = seeded
            saveProfile(seeded)
        }
    }

    private fun saveProfile(profile: LearnedInsulinProfile) {
        try {
            sp.edit { putString(prefKeyFor(profile.mode).key, profile.toJson().toString()) }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ProfileLearner: failed to save ${profile.mode}: ${e.message}")
        }
    }
}
