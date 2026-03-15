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
 * - No recent low / rebound window active (hard block)
 * - BG must be above [triggerThresholdMmol] (default 6.0 mmol)
 * - [riseConsecutiveReadings] consecutive readings with:
 *     - delta >= [riseMinDeltaMmol]
 *     - shortAvgDelta >= [riseMinDeltaMmol] * 0.75  (filters single-reading noise)
 * - Total BG rise since streak start >= [RISE_TOTAL_MMOL_MIN] (filters wobble streaks)
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
 * ## Safety blocks
 * UAM will not fire if:
 *   - A real low occurred recently (within [LOW_BLOCK_MINS])
 *   - The rebound window is active
 *
 * ## Window priority
 * Breakfast → Lunch → Dinner → Snack → Low Carb (first match wins).
 * If windows overlap, the earlier meal slot takes priority.
 */
@Singleton
class UamController @Inject constructor(
    private val preferences:         Preferences,
    private val mealOverrideManager: MealOverrideManager,
    private val aapsLogger:          AAPSLogger
) {

    // ── State ─────────────────────────────────────────────────────────────────
    private var consecutiveRiseReadings = 0
    private var bgAtStreakStart:  Double  = 0.0
    private var lastUamMode:    MealMode? = null
    private var lastUamTimeMs:  Long      = 0L
    private var lastUamTriggerCount       = 0
    private var uamExpiredAtMs: Long      = 0L
    private var previousMealMode: MealMode? = null  // for expiry transition detection

    companion object {
        // Minimum total BG rise over the full streak before triggering
        private const val RISE_TOTAL_MMOL_MIN      = 0.8
        // How long after a real low to block UAM
        private const val LOW_BLOCK_MINS            = 90L
        // shortAvgDelta must be at least this fraction of riseMinDelta
        private const val SHORT_AVG_DELTA_FRACTION  = 0.75
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once per loop cycle from SmartInsulinPlugin.invoke().
     *
     * @param currentMealMode    Current active meal mode
     * @param currentBgMmol      Current BG in mmol/L
     * @param deltaMmol          5-min delta in mmol/L
     * @param shortAvgDeltaMmol  Short average delta (~15 min) in mmol/L
     * @param currentHour        Hour of day (0-23)
     * @param bgWentLow          True if a real low occurred (rebound protection)
     * @param inReboundWindow    True if currently in post-low rebound window
     * @param lastLowTimeMs      Timestamp of last low event (0 if never)
     */
    fun onLoopCycle(
        currentMealMode:   MealMode,
        currentBgMmol:     Double,
        deltaMmol:         Double,
        shortAvgDeltaMmol: Double,
        currentHour:       Int,
        bgWentLow:         Boolean,
        inReboundWindow:   Boolean,
        lastLowTimeMs:     Long
    ) {
        // ── Expiry detection — track mode transitions ─────────────────────────
        // When we go from a UAM mode back to FASTING, the mode just expired
        if (previousMealMode?.isUam == true && currentMealMode == MealMode.FASTING) {
            if (uamExpiredAtMs == 0L) {
                uamExpiredAtMs = System.currentTimeMillis()
                aapsLogger.debug(LTag.APS, "UAM: ${previousMealMode!!.label} expired, re-arm timer started")
            }
        }
        previousMealMode = currentMealMode

        if (!preferences.get(BooleanKey.ApsSmartInsulinUamEnabled)) {
            resetStreak(); return
        }

        // Only detect during fasting — don't stack on manual or active UAM mode
        if (currentMealMode != MealMode.FASTING) {
            resetStreak(); return
        }

        // ── Hard night cutoff ─────────────────────────────────────────────────
        val nightCutoff = preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)
        if (currentHour >= nightCutoff) {
            if (consecutiveRiseReadings > 0) {
                aapsLogger.debug(LTag.APS, "UAM: night cutoff (hour=$currentHour >= $nightCutoff), reset")
                resetStreak()
            }
            return
        }

        // ── Safety block: recent low / rebound ───────────────────────────────
        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val lowBlockMs = LOW_BLOCK_MINS * 60_000L
        if (bgWentLow || inReboundWindow || msSinceLow < lowBlockMs) {
            if (consecutiveRiseReadings > 0) {
                val reason = when {
                    inReboundWindow         -> "rebound window active"
                    bgWentLow               -> "recent low (bgWentLow)"
                    else                    -> "low ${msSinceLow / 60_000}min ago < ${LOW_BLOCK_MINS}min block"
                }
                aapsLogger.debug(LTag.APS, "UAM: blocked — $reason, streak reset")
                resetStreak()
            }
            return
        }

        // ── Re-arm delay ─────────────────────────────────────────────────────
        val reArmDelayMs = preferences.get(IntKey.ApsSmartInsulinUamReArmDelayMins) * 60_000L
        if (uamExpiredAtMs > 0L && System.currentTimeMillis() - uamExpiredAtMs < reArmDelayMs) {
            val waitMins = (reArmDelayMs - (System.currentTimeMillis() - uamExpiredAtMs)) / 60_000
            aapsLogger.debug(LTag.APS, "UAM: re-arm delay active (${waitMins}min remaining)")
            return
        }

        // ── Resolve time window ───────────────────────────────────────────────
        val uamMode = resolveUamMode(currentHour) ?: run {
            resetStreak(); return
        }

        // ── BG above trigger threshold ────────────────────────────────────────
        val triggerThresholdMmol = preferences.get(DoubleKey.ApsSmartInsulinUamTriggerThresholdMmol)
        if (currentBgMmol < triggerThresholdMmol) {
            resetStreak(); return
        }

        // ── Rise confirmation: delta AND shortAvgDelta ────────────────────────
        val riseMinDelta      = preferences.get(DoubleKey.ApsSmartInsulinUamRiseMinDeltaMmol)
        val shortAvgThreshold = riseMinDelta * SHORT_AVG_DELTA_FRACTION
        val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
        val risingNow = deltaMmol >= riseMinDelta && shortAvgDeltaMmol >= shortAvgThreshold

        if (risingNow) {
            if (consecutiveRiseReadings == 0) bgAtStreakStart = currentBgMmol
            consecutiveRiseReadings++
            val totalRise = currentBgMmol - bgAtStreakStart
            aapsLogger.debug(LTag.APS,
                             "UAM: rise $consecutiveRiseReadings/$riseReadingsNeeded " +
                                 "bg=${String.format("%.1f", currentBgMmol)}mmol " +
                                 "Δ=+${String.format("%.2f", deltaMmol)} avg=+${String.format("%.2f", shortAvgDeltaMmol)} " +
                                 "total=+${String.format("%.1f", totalRise)}mmol mode=${uamMode.label}")
        } else {
            if (consecutiveRiseReadings > 0)
                aapsLogger.debug(LTag.APS,
                                 "UAM: streak broken " +
                                     "(Δ=${String.format("%.2f", deltaMmol)} needed>=$riseMinDelta, " +
                                     "avg=${String.format("%.2f", shortAvgDeltaMmol)} needed>=${String.format("%.2f", shortAvgThreshold)}), reset")
            resetStreak(); return
        }

        // ── Total rise gate ───────────────────────────────────────────────────
        if (consecutiveRiseReadings >= riseReadingsNeeded) {
            val totalRise = currentBgMmol - bgAtStreakStart
            if (totalRise < RISE_TOTAL_MMOL_MIN) {
                aapsLogger.debug(LTag.APS,
                                 "UAM: readings met but total rise ${String.format("%.2f", totalRise)}mmol " +
                                     "< ${RISE_TOTAL_MMOL_MIN}mmol min — holding for more movement")
                return  // keep streak alive, don't reset
            }
            triggerUam(uamMode, currentBgMmol, deltaMmol, totalRise)
            resetStreak()
        }
    }

    /** Status string for loop reason output — null if nothing to show */
    fun statusString(): String? {
        if (consecutiveRiseReadings > 0) {
            val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
            return "UAM: watching ($consecutiveRiseReadings/$riseReadingsNeeded rising)"
        }
        if (lastUamMode != null && lastUamTimeMs > 0L) {
            val cal     = Calendar.getInstance().also { it.timeInMillis = lastUamTimeMs }
            val timeStr = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val countStr = if (lastUamTriggerCount > 1) " (×$lastUamTriggerCount)" else ""
            return "UAM: last ${lastUamMode!!.label} $timeStr$countStr"
        }
        return null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun resetStreak() {
        consecutiveRiseReadings = 0
        bgAtStreakStart         = 0.0
    }

    private fun triggerUam(mode: MealMode, bgMmol: Double, deltaMmol: Double, totalRise: Double) {
        val durationMins = uamDurationMins(mode)
        val isfMmol      = uamIsfMmol(mode)
        val now          = System.currentTimeMillis()

        lastUamTriggerCount = if (lastUamMode == mode &&
            now - lastUamTimeMs < 4 * 60 * 60 * 1000L) lastUamTriggerCount + 1 else 1
        lastUamMode    = mode
        lastUamTimeMs  = now
        uamExpiredAtMs = 0L

        aapsLogger.debug(LTag.APS,
                         "UAM: TRIGGERING ${mode.label} " +
                             "bg=${String.format("%.1f", bgMmol)}mmol " +
                             "Δ=+${String.format("%.2f", deltaMmol)}mmol " +
                             "totalRise=+${String.format("%.1f", totalRise)}mmol " +
                             "isf=${isfMmol}mmol duration=${durationMins}min " +
                             "(trigger #$lastUamTriggerCount)")

        mealOverrideManager.activateOverride(
            mode         = mode,
            doseU        = null,
            carbsG       = 0,
            modeWindowMs = durationMins * 60_000L
        )
    }

    private fun resolveUamMode(currentHour: Int): MealMode? {
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
        else hour >= start || hour < end

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