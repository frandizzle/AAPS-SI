package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.SmartInsulinLearner
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learns and persists insulin kinetics (drug properties) and carb absorption
 * (food properties) using EWMA updates.
 *
 * Strategy:
 * 1. Global Insulin Kinetics: Learned ONLY during FASTING corrections.
 * 2. Carb Absorption Profiles: Learned during MEAL modes per MealMode.
 */
@Singleton
class ProfileLearner @Inject constructor(
    private val aapsLogger:      AAPSLogger,
    private val preferences:     Preferences,
    private val profileFunction: ProfileFunction,
) : SmartInsulinLearner {

    private var insulinKinetics: LearnedInsulinKinetics = LearnedInsulinKinetics.default()
    private val carbProfiles:    MutableMap<MealMode, LearnedCarbAbsorption> = mutableMapOf()

    init {
        restore()
        aapsLogger.debug(LTag.APS, "ProfileLearner initialised: insulinKinetics=$insulinKinetics")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun getInsulinKinetics(): LearnedInsulinKinetics = insulinKinetics

    fun getCarbAbsorption(mode: MealMode): LearnedCarbAbsorption =
        carbProfiles[mode] ?: LearnedCarbAbsorption.defaultFor(mode)

    /**
     * Update global insulin kinetics from a pure fasting correction signal.
     */
    fun observeInsulinKinetics(
        observedPeakMins: Double,
        observedDiaMins:  Double,
        learningRate:     Double
    ) {
        val current = insulinKinetics
        val alpha = learningRate.coerceIn(0.01, 0.5)

        val newPeak = ewma(current.peakMinutes, observedPeakMins.coerceIn(LearnedInsulinKinetics.PEAK_MIN, LearnedInsulinKinetics.PEAK_MAX), alpha)
        val newDia  = ewma(current.diaMinutes,  observedDiaMins.coerceIn(LearnedInsulinKinetics.DIA_MIN,  LearnedInsulinKinetics.DIA_MAX),  alpha)

        insulinKinetics = current.copy(
            peakMinutes   = newPeak,
            diaMinutes    = newDia,
            sampleCount   = current.sampleCount + 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
        saveInsulinKinetics()

        aapsLogger.debug(
            LTag.APS, "ProfileLearner: global insulin kinetics updated " +
            "peak ${fmtChange(current.peakMinutes, newPeak)} dia ${fmtChange(current.diaMinutes, newDia)} n=${insulinKinetics.sampleCount}"
        )
    }

    /**
     * Update per-mode carb absorption duration.
     */
    fun observeCarbAbsorption(
        mode:              MealMode,
        observedPeakMins:  Double,
        observedTotalMins: Double,
        learningRate:      Double
    ) {
        val current = getCarbAbsorption(mode)
        val alpha = (learningRate * mode.learningWeight).coerceIn(0.01, 0.5)

        val clampedTotal = observedTotalMins.coerceAtLeast(observedPeakMins + 15.0)
            .coerceIn(LearnedCarbAbsorption.ABS_MIN, LearnedCarbAbsorption.ABS_MAX)
        val newAbs = ewma(current.absorptionMinutes, clampedTotal, alpha)
        val newPeak = ewma(current.peakMinutes,       observedPeakMins.coerceIn(30.0, 180.0), alpha)

        val updated = current.copy(
            absorptionMinutes = newAbs,
            peakMinutes       = newPeak,
            sampleCount       = current.sampleCount + 1,
            lastUpdatedMs     = System.currentTimeMillis()
        )
        carbProfiles[mode] = updated
        saveCarbProfile(updated)

        aapsLogger.debug(LTag.APS, "ProfileLearner: ${mode.label} carb absorption updated " +
            "duration ${fmtChange(current.absorptionMinutes, newAbs)} n=${updated.sampleCount}")
    }

    override fun resetProfiles() {
        val profile  = runBlocking { profileFunction.getProfile() }

        // 1. Seed Global Insulin Kinetics directly from the AAPS pump profile
        val diaMins  = profile?.iCfg?.dia?.times(60.0) ?: 360.0
        val peakMins = profile?.iCfg?.peak?.toDouble() ?: 75.0

        insulinKinetics = LearnedInsulinKinetics(
            peakMinutes   = peakMins,
            diaMinutes    = diaMins,
            sampleCount   = 0,
            lastUpdatedMs = System.currentTimeMillis()
        )
        saveInsulinKinetics()

        aapsLogger.debug(LTag.APS, "ProfileLearner: Seeded Insulin Kinetics with Peak=${peakMins}m DIA=${diaMins}m from profile")

        // 2. Reset Meal Modes to the 180m default (since profiles don't have food duration)
        carbProfiles.clear()
        MealMode.entries.forEach { mode ->
            if (mode != MealMode.FASTING) {
                resetCarbProfile(mode)
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun ewma(old: Double, observed: Double, alpha: Double): Double =
        ((1.0 - alpha) * old) + (alpha * observed)

    private fun restore() {
        try {
            val iJson = preferences.get(StringKey.ApsSmartInsulinProfileInsulin)
            if (iJson.isNotBlank()) insulinKinetics = LearnedInsulinKinetics.fromJson(JSONObject(iJson))

            MealMode.entries.forEach { mode ->
                if (mode == MealMode.FASTING) return@forEach
                val cJson = preferences.get(carbPrefKeyFor(mode))
                if (cJson.isNotBlank()) carbProfiles[mode] = LearnedCarbAbsorption.fromJson(JSONObject(cJson), mode)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ProfileLearner: restore failed", e)
        }
    }

    private fun saveInsulinKinetics() {
        preferences.put(StringKey.ApsSmartInsulinProfileInsulin, insulinKinetics.toJson().toString())
    }

    private fun saveCarbProfile(profile: LearnedCarbAbsorption) {
        preferences.put(carbPrefKeyFor(profile.mode), profile.toJson().toString())
    }

    private fun resetCarbProfile(mode: MealMode) {
        val default = LearnedCarbAbsorption.defaultFor(mode)
        carbProfiles[mode] = default
        saveCarbProfile(default)
    }

    private fun carbPrefKeyFor(mode: MealMode): StringKey = when (mode) {
        MealMode.LOW_CARB      -> StringKey.ApsSmartInsulinProfileLowCarb
        MealMode.BREAKFAST     -> StringKey.ApsSmartInsulinProfileBreakfast
        MealMode.LUNCH         -> StringKey.ApsSmartInsulinProfileLunch
        MealMode.DINNER        -> StringKey.ApsSmartInsulinProfileDinner
        MealMode.EXTENDED      -> StringKey.ApsSmartInsulinProfileExtended
        MealMode.UAM_BREAKFAST -> StringKey.ApsSmartInsulinProfileUamBreakfast
        MealMode.UAM_LUNCH     -> StringKey.ApsSmartInsulinProfileUamLunch
        MealMode.UAM_DINNER    -> StringKey.ApsSmartInsulinProfileUamDinner
        MealMode.UAM_SNACK     -> StringKey.ApsSmartInsulinProfileUamSnack
        MealMode.UAM_AFTERNOON -> StringKey.ApsSmartInsulinProfileUamAfternoon
        MealMode.UAM_PROTEIN_FAT -> StringKey.ApsSmartInsulinProfileUamProteinFat
        else -> StringKey.ApsSmartInsulinProfileFasting // should not happen for carb profiles
    }

    private fun fmtChange(old: Double, new: Double): String = "%.1f→%.1f".format(Locale.US, old, new)
}
