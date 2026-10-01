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

    // Declared ABOVE init{}: Kotlin initialises properties in declaration order, and restore()
    // seeds profiles from these. Declared below it, they were still 0.0 when restore() ran, so
    // every profile seeded at startup got a DIA and peak of zero.
    // The configured insulin's DIA and peak. In 4.0 both live on the effective profile's insulin
    // configuration and reading the profile is suspend, while this class is read from synchronous
    // paths (the SI tab among them). So the loop hands them over each cycle and seeding reads the
    // last values seen. Until the first cycle, the fallbacks below — the same ones a missing
    // profile always fell back to.
    @Volatile private var insulinDiaMins  = LearnedInsulinProfile.FALLBACK_DIA_MINS
    @Volatile private var insulinPeakMins = 55.0

    fun updateInsulinDefaults(diaMins: Double, peakMins: Double) {
        val changed = (diaMins > 0.0 && diaMins != insulinDiaMins) || (peakMins > 0.0 && peakMins != insulinPeakMins)
        if (diaMins > 0.0)  insulinDiaMins  = diaMins
        if (peakMins > 0.0) insulinPeakMins = peakMins
        if (!changed) return
        // Profiles are seeded at construction, before the first loop cycle has reported the
        // configured insulin, so they start from the fallbacks above. Any profile that has never
        // learned anything is still just a seed: re-seed it from the real insulin now, as 3.4 did
        // by reading the insulin plugin directly. Learned profiles (sampleCount > 0) are the user's
        // data and are never touched.
        MealMode.entries.forEach { mode ->
            if ((profiles[mode]?.sampleCount ?: 0) == 0)
                profiles[mode] = LearnedInsulinProfile.defaultFor(mode, insulinPeakMins, insulinDiaMins)
        }
    }

    init {
        MealMode.entries.forEach { mode ->
            profiles[mode] = loadProfile(mode)
        }
    }

    fun getProfile(mode: MealMode): LearnedInsulinProfile =
        profiles[mode] ?: profileSeededDefault(mode)

    private fun profileSeededDefault(mode: MealMode): LearnedInsulinProfile {
        // Seeded from the insulin actually configured, as read by the last loop cycle — see
        // [updateInsulinDefaults]. Previously hardcoded to 55.0 (Fiasp's value) regardless of
        // insulin, so switching insulin types and resetting silently reseeded Fiasp's peak.
        val diaMins  = insulinDiaMins
        val peakMins = insulinPeakMins
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
        onChange?.invoke(
            "${mode.label}: " + listOfNotNull(
                if (peakUpdated) "peak ${current.peakMinutes.toInt()}→${newPeak.toInt()} min" else null,
                if (diaUpdated) "DIA ${current.diaMinutes.toInt()}→${newDia.toInt()} min" else null
            ).joinToString(", ") + " (n=$newSampleCount)"
        )
    }

    /** Called with a description of each learned peak/DIA change; the plugin points it at [LearningJournal]. */
    var onChange: ((String) -> Unit)? = null

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

    private fun prefKeyFor(mode: MealMode): StringNonKey =
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
        val diaMins  = insulinDiaMins
        val peakMins = insulinPeakMins
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
