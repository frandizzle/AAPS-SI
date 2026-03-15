package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UAM (Unannounced Meal) auto-detection controller.
 *
 * Monitors fasting BG during configured time windows and auto-activates the
 * appropriate UAM meal mode when a confirmed BG rise is detected.
 *
 * ## Detection logic
 * - Current hour must fall within a UAM mode's configured window
 * - BG must be above [uamTriggerThresholdMmol] (default 6.0 mmol)
 * - [riseConsecutiveReadings] consecutive CGM readings each with delta >= [riseMinDeltaMmol]
 * → auto-activate the matching UAM mode via [MealOverrideManager.activateOverride]
 *
 * ## Hard cutoff
 * All UAM modes are disabled at [nightCutoffHour] (default 23:00). STFT handles
 * overnight sticky BG instead.
 *
 * ## Re-arm
 * After a UAM mode expires, the controller waits [reArmDelayMins] before allowing
 * re-activation. If BG is still elevated after re-arm, it will trigger again.
 *
 * ## Learner isolation
 * UAM modes (e.g. UAM_LUNCH) are separate from manual modes (LUNCH) in MealMode,
 * so ProfileLearner accumulates independent peak/DIA data for auto-detected meals.
 */
@Singleton
class UamController @Inject constructor(
    private val preferences:          Preferences,
    private val mealOverrideManager:  MealOverrideManager,
    private val aapsLogger:           AAPSLogger
) {

    // ── State ─────────────────────────────────────────────────────────────────
    private var consecutiveRiseReadings = 0
    private var lastUamMode:   MealMode? = null
    private var lastUamTimeMs: Long      = 0L
    private var lastUamTriggerCount      = 0
    private var uamExpiredAtMs: Long     = 0L  // when the last UAM mode expired

    companion object {
        private const val MMOL_TO_MGDL = 18.0
        private const val HARD_CUTOFF_HOUR_DEFAULT = 23
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once per loop cycle from SmartInsulinPlugin.invoke().
     * Only acts when [currentMealMode] is FASTING — UAM won't fire over a manual mode.
     */
    fun onLoopCycle(
        currentMealMode: MealMode,
        currentBgMmol:   Double,
        deltaMmol:       Double,
        currentHour:     Int
    ) {
        if (!preferences.get(BooleanKey.ApsSmartInsulinUamEnabled)) {
            consecutiveRiseReadings = 0
            return
        }

        // Only detect when fasting — don't fire over a manual or existing UAM mode
        if (currentMealMode != MealMode.FASTING) {
            consecutiveRiseReadings = 0
            return
        }

        // Hard cutoff — no UAM after configured hour
        val nightCutoff = preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)
        if (currentHour >= nightCutoff) {
            if (consecutiveRiseReadings > 0) {
                aapsLogger.debug(LTag.APS, "UAM: night cutoff reached (hour=$currentHour >= $nightCutoff), resetting")
                consecutiveRiseReadings = 0
            }
            return
        }

        // Check re-arm delay — don't re-trigger too soon after last UAM expiry
        val reArmDelayMs = preferences.get(IntKey.ApsSmartInsulinUamReArmDelayMins) * 60_000L
        if (uamExpiredAtMs > 0L && System.currentTimeMillis() - uamExpiredAtMs < reArmDelayMs) {
            val waitMins = (reArmDelayMs - (System.currentTimeMillis() - uamExpiredAtMs)) / 60_000
            aapsLogger.debug(LTag.APS, "UAM: re-arm delay active (${waitMins}min remaining)")
            return
        }

        // Find which UAM mode window we're in (if any)
        val uamMode = resolveUamMode(currentHour) ?: run {
            consecutiveRiseReadings = 0
            return
        }

        // Check BG above trigger threshold
        val triggerThresholdMmol = preferences.get(DoubleKey.ApsSmartInsulinUamTriggerThresholdMmol)
        if (currentBgMmol < triggerThresholdMmol) {
            consecutiveRiseReadings = 0
            return
        }

        // Accumulate rise readings
        val riseMinDelta       = preferences.get(DoubleKey.ApsSmartInsulinUamRiseMinDeltaMmol)
        val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)

        if (deltaMmol >= riseMinDelta) {
            consecutiveRiseReadings++
            aapsLogger.debug(LTag.APS,
                             "UAM: rise detected $consecutiveRiseReadings/$riseReadingsNeeded " +
                                 "bg=${currentBgMmol}mmol delta=+${deltaMmol}mmol mode=${uamMode.label}")
        } else {
            if (consecutiveRiseReadings > 0)
                aapsLogger.debug(LTag.APS, "UAM: rise streak broken (delta=${deltaMmol}mmol < min ${riseMinDelta}mmol), reset")
            consecutiveRiseReadings = 0
            return
        }

        // Trigger if enough consecutive rising readings
        if (consecutiveRiseReadings >= riseReadingsNeeded) {
            triggerUam(uamMode, currentBgMmol, deltaMmol)
            consecutiveRiseReadings = 0
        }
    }

    /**
     * Call when a UAM mode expires so the re-arm timer starts.
     * SmartInsulinPlugin should call this when it detects the active UAM mode has expired.
     */
    fun onUamModeExpired() {
        uamExpiredAtMs = System.currentTimeMillis()
        aapsLogger.debug(LTag.APS, "UAM: mode expired, re-arm timer started")
    }

    /** Status string for loop reason output — null if nothing to show */
    fun statusString(): String? {
        val now = System.currentTimeMillis()

        // Show active watching state
        if (consecutiveRiseReadings > 0) {
            val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
            return "UAM: watching ($consecutiveRiseReadings/$riseReadingsNeeded rising)"
        }

        // Show last trigger history
        if (lastUamMode != null && lastUamTimeMs > 0L) {
            val cal = Calendar.getInstance().also { it.timeInMillis = lastUamTimeMs }
            val h   = cal.get(Calendar.HOUR_OF_DAY)
            val m   = cal.get(Calendar.MINUTE)
            val timeStr = "%02d:%02d".format(h, m)
            val countStr = if (lastUamTriggerCount > 1) " (×$lastUamTriggerCount)" else ""
            return "UAM: last ${lastUamMode!!.label} $timeStr$countStr"
        }

        return null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun triggerUam(mode: MealMode, bgMmol: Double, deltaMmol: Double) {
        val durationMins = uamDurationMins(mode)
        val isfMmol      = uamIsfMmol(mode)
        val now          = System.currentTimeMillis()

        // Track trigger history
        lastUamTriggerCount = if (lastUamMode == mode &&
            now - lastUamTimeMs < 4 * 60 * 60 * 1000L) lastUamTriggerCount + 1 else 1
        lastUamMode   = mode
        lastUamTimeMs = now
        uamExpiredAtMs = 0L  // reset expiry — new activation in progress

        aapsLogger.debug(LTag.APS,
                         "UAM: TRIGGERING ${mode.label} bg=${bgMmol}mmol delta=+${deltaMmol}mmol " +
                             "isf=${isfMmol}mmol duration=${durationMins}min (trigger #$lastUamTriggerCount)")

        mealOverrideManager.activateOverride(
            mode         = mode,
            doseU        = null,   // UAM is ISF-only, no bolus
            carbsG       = 0,
            modeWindowMs = durationMins * 60_000L
        )
    }

    /**
     * Resolve which UAM mode applies for [currentHour].
     * Returns null if no window matches or that mode is disabled.
     */
    private fun resolveUamMode(currentHour: Int): MealMode? {
        // Check each UAM mode window in priority order
        val candidates = listOf(
            Triple(MealMode.UAM_BREAKFAST,
                   preferences.get(IntKey.ApsSmartInsulinUamBreakfastStartHour),
                   preferences.get(IntKey.ApsSmartInsulinUamBreakfastEndHour)),
            Triple(MealMode.UAM_LUNCH,
                   preferences.get(IntKey.ApsSmartInsulinUamLunchStartHour),
                   preferences.get(IntKey.ApsSmartInsulinUamLunchEndHour)),
            Triple(MealMode.UAM_DINNER,
                   preferences.get(IntKey.ApsSmartInsulinUamDinnerStartHour),
                   preferences.get(IntKey.ApsSmartInsulinUamDinnerEndHour)),
            Triple(MealMode.UAM_SNACK,
                   preferences.get(IntKey.ApsSmartInsulinUamSnackStartHour),
                   preferences.get(IntKey.ApsSmartInsulinUamSnackEndHour)),
            Triple(MealMode.UAM_LOW_CARB,
                   preferences.get(IntKey.ApsSmartInsulinUamLowCarbStartHour),
                   preferences.get(IntKey.ApsSmartInsulinUamLowCarbEndHour))
        )

        return candidates.firstOrNull { (mode, start, end) ->
            uamModeEnabled(mode) && hourInWindow(currentHour, start, end)
        }?.first
    }

    private fun hourInWindow(hour: Int, start: Int, end: Int): Boolean =
        if (start <= end) hour in start until end
        else hour >= start || hour < end  // handles midnight wrap

    private fun uamModeEnabled(mode: MealMode): Boolean = when (mode) {
        MealMode.UAM_BREAKFAST -> preferences.get(BooleanKey.ApsSmartInsulinUamBreakfastEnabled)
        MealMode.UAM_LUNCH     -> preferences.get(BooleanKey.ApsSmartInsulinUamLunchEnabled)
        MealMode.UAM_DINNER    -> preferences.get(BooleanKey.ApsSmartInsulinUamDinnerEnabled)
        MealMode.UAM_SNACK     -> preferences.get(BooleanKey.ApsSmartInsulinUamSnackEnabled)
        MealMode.UAM_LOW_CARB  -> preferences.get(BooleanKey.ApsSmartInsulinUamLowCarbEnabled)
        else                   -> false
    }

    private fun uamDurationMins(mode: MealMode): Long = when (mode) {
        MealMode.UAM_BREAKFAST -> preferences.get(IntKey.ApsSmartInsulinUamBreakfastDurationMins).toLong()
        MealMode.UAM_LUNCH     -> preferences.get(IntKey.ApsSmartInsulinUamLunchDurationMins).toLong()
        MealMode.UAM_DINNER    -> preferences.get(IntKey.ApsSmartInsulinUamDinnerDurationMins).toLong()
        MealMode.UAM_SNACK     -> preferences.get(IntKey.ApsSmartInsulinUamSnackDurationMins).toLong()
        MealMode.UAM_LOW_CARB  -> preferences.get(IntKey.ApsSmartInsulinUamLowCarbDurationMins).toLong()
        else                   -> 30L
    }

    private fun uamIsfMmol(mode: MealMode): Double = when (mode) {
        MealMode.UAM_BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinUamBreakfastIsf)
        MealMode.UAM_LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinUamLunchIsf)
        MealMode.UAM_DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinUamDinnerIsf)
        MealMode.UAM_SNACK     -> preferences.get(DoubleKey.ApsSmartInsulinUamSnackIsf)
        MealMode.UAM_LOW_CARB  -> preferences.get(DoubleKey.ApsSmartInsulinUamLowCarbIsf)
        else                   -> preferences.get(DoubleKey.ApsSmartInsulinUamLowCarbIsf)
    }
}