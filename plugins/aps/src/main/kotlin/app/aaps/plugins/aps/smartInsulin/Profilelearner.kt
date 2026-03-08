package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.SmartInsulinLearner
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import java.util.Locale
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learns and persists per-[MealMode] insulin activity profiles using
 * Exponential Weighted Moving Average (EWMA) updates.
 *
 * ## How learning works
 *
 * After each bolus, [BolusCurveTracker] calls [observeBolusCurve] with:
 * - The observed time-to-peak BG drop (minutes)
 * - The observed duration until BG returned to pre-bolus level (minutes)
 * - The [MealMode] active during that bolus
 *
 * The EWMA update blends the new observation into the existing profile:
 *
 *   new_value = (1 - α) * old_value + α * observed_value
 *
 * where α = learningRate * [MealMode.learningWeight]
 *
 * This means FASTING corrections update the profile faster than MEAL boluses,
 * since FASTING has a cleaner signal (no carb absorption competing with insulin).
 *
 * ## DIA learning gate
 *
 * DIA learning is suppressed for [MealMode.EXTENDED] since the carb tail
 * distorts the apparent insulin duration. Peak learning still occurs.
 *
 * ## Persistence
 *
 * Profiles are serialised as JSON and stored in SharedPreferences under
 * per-mode string keys. On app restart, profiles are restored automatically.
 */
@Singleton
class ProfileLearner @Inject constructor(
    private val aapsLogger:      AAPSLogger,
    private val preferences:     Preferences,
    private val profileFunction: ProfileFunction,
    private val activePlugin:    ActivePlugin
) : SmartInsulinLearner {

    // ── In-memory cache of learned profiles ──────────────────────────────────
    private val profiles: MutableMap<MealMode, LearnedInsulinProfile> = mutableMapOf()

    init {
        // Load persisted profiles for all modes on construction
        MealMode.entries.forEach { mode ->
            profiles[mode] = loadProfile(mode)
        }
        aapsLogger.debug(LTag.APS, "ProfileLearner initialised: ${profiles.values.joinToString { it.toString() }}")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the current learned profile for [mode].
     * Falls back to [LearnedInsulinProfile.defaultFor] if nothing persisted yet.
     */
    fun getProfile(mode: MealMode): LearnedInsulinProfile =
        profiles[mode] ?: profileSeededDefault(mode)

    /** Seed default from actual profile DIA and peak so first-run values are meaningful. */
    private fun profileSeededDefault(mode: MealMode): LearnedInsulinProfile {
        val profile  = profileFunction.getProfile()
        val diaMins  = profile?.dia?.times(60.0) ?: LearnedInsulinProfile.FALLBACK_DIA_MINS
        val peakMins = activePlugin.activeInsulin.peak.toDouble()
        return LearnedInsulinProfile.defaultFor(mode, peakMins, diaMins)
    }

    /**
     * Update the learned profile for [mode] from a completed bolus observation.
     *
     * Called by [BolusCurveTracker] once it has fitted a peak and DIA estimate
     * from the post-bolus CGM curve.
     *
     * @param mode              The [MealMode] active during this bolus
     * @param observedPeakMins  Time from bolus to maximum insulin effect (minutes)
     * @param observedDiaMins   Time from bolus until BG returned to baseline (minutes)
     * @param learningRate      User-configured base learning rate (0.05–0.5)
     */
    fun observeBolusCurve(
        mode:             MealMode,
        observedPeakMins: Double,
        observedDiaMins:  Double,
        learningRate:     Double
    ) {
        // Clamp observations to physiological hard limits before accepting them
        val clampedPeak = observedPeakMins.coerceIn(
            LearnedInsulinProfile.PEAK_MIN_MINUTES,
            LearnedInsulinProfile.PEAK_MAX_MINUTES
        )
        val clampedDia = observedDiaMins.coerceIn(
            LearnedInsulinProfile.DIA_MIN_MINUTES,
            LearnedInsulinProfile.DIA_MAX_MINUTES
        )

        // Reject implausible observations — peak must be less than DIA
        if (clampedPeak >= clampedDia) {
            aapsLogger.debug(
                LTag.APS,
                "ProfileLearner: rejecting observation peak=$clampedPeak >= dia=$clampedDia for $mode"
            )
            return
        }

        val current = getProfile(mode)

        // Effective learning rate blends base rate with mode's signal quality weight
        val alpha = (learningRate * mode.learningWeight).coerceIn(0.01, 0.5)

        // EWMA update for peak
        val newPeak = ewma(current.peakMinutes, clampedPeak, alpha)

        // EWMA update for DIA — suppressed for EXTENDED mode
        val newDia = if (mode.diaLearningEnabled) {
            ewma(current.diaMinutes, clampedDia, alpha)
        } else {
            current.diaMinutes  // hold DIA at current value
        }

        val newSampleCount = current.sampleCount + 1
        val newConfidence  = current.normalizedConfidence
            .coerceAtLeast(alpha)   // confidence can only grow
            .coerceAtMost(1.0)

        val updated = current.copy(
            peakMinutes   = newPeak,
            diaMinutes    = newDia,
            confidence    = newConfidence,
            sampleCount   = newSampleCount,
            lastUpdatedMs = System.currentTimeMillis()
        )

        profiles[mode] = updated
        saveProfile(updated)

        aapsLogger.debug(
            LTag.APS,
            "ProfileLearner updated ${mode.label}: " +
                "peak ${fmtChange(current.peakMinutes, newPeak)} " +
                "dia ${fmtChange(current.diaMinutes, newDia)} " +
                "α=${"%.3f".format(Locale.US, alpha)} n=$newSampleCount"
        )
    }

    /**
     * Reset the learned profile for [mode] back to its default prior.
     * Useful if the user changes insulin type or suspects corrupt data.
     */
    fun resetProfile(mode: MealMode) {
        val default = LearnedInsulinProfile.defaultFor(mode)
        profiles[mode] = default
        saveProfile(default)
        aapsLogger.debug(LTag.APS, "ProfileLearner: reset $mode to defaults")
    }

    /**
     * Reset ALL learned profiles back to defaults.
     */
    fun resetAll() {
        MealMode.entries.forEach { resetProfile(it) }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Standard EWMA: new = (1 - α) * old + α * observed */
    private fun ewma(old: Double, observed: Double, alpha: Double): Double =
        (1.0 - alpha) * old + alpha * observed

    private fun prefKeyFor(mode: MealMode): StringKey =
        when (mode) {
            MealMode.FASTING   -> StringKey.ApsSmartInsulinProfileFasting
            MealMode.LOW_CARB  -> StringKey.ApsSmartInsulinProfileLowCarb
            MealMode.BREAKFAST -> StringKey.ApsSmartInsulinProfileBreakfast
            MealMode.LUNCH     -> StringKey.ApsSmartInsulinProfileLunch
            MealMode.DINNER    -> StringKey.ApsSmartInsulinProfileDinner
            MealMode.EXTENDED  -> StringKey.ApsSmartInsulinProfileExtended
        }

    private fun loadProfile(mode: MealMode): LearnedInsulinProfile {
        return try {
            val json = preferences.get(prefKeyFor(mode))
            if (json.isBlank()) return LearnedInsulinProfile.defaultFor(mode)
            LearnedInsulinProfile.fromJson(JSONObject(json), mode)
        } catch (_: Exception) {
            LearnedInsulinProfile.defaultFor(mode)
        }
    }

    /**
     * Clears all learned profiles and re-seeds from the current profile DIA and insulin peak.
     * Call this after changing insulin type or if learned values have drifted badly.
     */
    override fun resetProfiles() {
        val profile  = profileFunction.getProfile()
        val diaMins  = profile?.dia?.times(60.0) ?: LearnedInsulinProfile.FALLBACK_DIA_MINS
        val peakMins = activePlugin.activeInsulin.peak.toDouble()
        aapsLogger.debug(LTag.APS,
                         "ProfileLearner: resetting all modes — seeding peak=${peakMins}m dia=${diaMins}m from current profile/insulin")
        MealMode.entries.forEach { mode ->
            val seeded = LearnedInsulinProfile.defaultFor(mode, peakMins, diaMins)
            profiles[mode] = seeded
            saveProfile(seeded)
        }
    }

    private fun saveProfile(profile: LearnedInsulinProfile) {
        try {
            preferences.put(prefKeyFor(profile.mode), profile.toJson().toString())
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ProfileLearner: failed to save ${profile.mode}: ${e.message}")
        }
    }

    private fun fmtChange(old: Double, new: Double): String =
        "%.1f→%.1f".format(Locale.US, old, new)
}