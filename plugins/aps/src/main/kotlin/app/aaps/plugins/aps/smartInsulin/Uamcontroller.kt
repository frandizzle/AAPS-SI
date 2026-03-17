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
    private var previousMealMode: MealMode?  = null  // for expiry transition detection
    private var lastResolvedMode: MealMode? = null  // for window-change streak reset

    // Stuck-high state for UAM_PROTEIN_FAT detection
    private var stuckHighReadings = 0
    // Post-meal lockout state — updated each cycle for statusString access
    private var currentlyInPostMealLockout = false
    private var currentlyPastNightCutoff   = false
    private var currentlyInMealMode        = false  // true when meal/UAM mode active — P/F blocked
    private var lastMealEndedMs            = 0L     // timestamp of last meal/UAM mode expiry — P/F only arms after this
    private var lastStuckAvgDelta          = 0.0   // last shortAvgDelta seen by checkStuckHigh
    private var lastStuckBgMmol            = 0.0   // last BG seen by checkStuckHigh

    // Last reject tracking for debug display
    private data class RejectInfo(val reason: String, val deltaActual: Double, val deltaNeeded: Double,
                                  val unexpectedActual: Double, val unexpectedNeeded: Double,
                                  val wasDirtyWindow: Boolean)
    private var lastReject: RejectInfo? = null  // consecutive readings with BG above threshold and flat delta

    companion object {
        // How long after a real low to block UAM
        private const val LOW_BLOCK_MINS            = 90L
        // shortAvgDelta must be at least this fraction of riseMinDelta
        private const val SHORT_AVG_DELTA_FRACTION   = 0.75
        // Wobble tolerance: how far below trigger threshold a single reading can dip
        // without resetting an active rise streak (CGM noise/compression mitigation)
        private const val WOBBLE_TOLERANCE_MMOL       = 0.3
        // Minimum unexpected rise (delta - BGI) to confirm UAM vs natural drift
        private const val UNEXPECTED_RISE_MIN_MMOL        = 0.15
        // Stricter thresholds during post-meal dirty window — distinguishes genuine
        // second meal (strong sharp rise) from fat/protein tail (slow weak rise)
        private const val DIRTY_WINDOW_DELTA_MULTIPLIER   = 1.5   // 0.2 → 0.3
        private const val DIRTY_WINDOW_UNEXPECTED_MULT    = 1.67  // 0.15 → 0.25

        // Stuck-high detection for UAM_PROTEIN_FAT
        // Delta must be in this range to count as "stuck" (not falling, not spiking)
        private const val STUCK_DELTA_MIN_MMOL        = -0.15  // -0.1 with small noise tolerance — genuinely falling (-0.2+) excluded
        private const val STUCK_DELTA_MAX_MMOL        = 0.25   // not spiking — raised from 0.2 to tolerate slight noise
        // 6 readings = 30 min at 5 min intervals
        // STUCK_READINGS_NEEDED moved to user preference ApsSmartInsulinUamProteinFatStuckReadings
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once per loop cycle from SmartInsulinPlugin.invoke().
     *
     * @param currentMealMode    Current active meal mode
     * @param currentBgMmol      Current BG in mmol/L
     * @param deltaMmol          5-min delta in mmol/L
     * @param shortAvgDeltaMmol  Short average delta (~15 min) in mmol/L
     * @param bgiMmol            Blood glucose impact from insulin activity (negative = insulin pulling BG down)
     *                           Computed as: -(iobActivity * ISF * 5) / 18.0
     * @param currentHour        Hour of day (0-23)
     * @param bgWentLow          True if a real low occurred (rebound protection)
     * @param inReboundWindow    True if currently in post-low rebound window
     * @param lastLowTimeMs      Timestamp of last low event (0 if never)
     * @param highTempTarget     True if a high temp target is active — blocks UAM triggering
     * @param cgmInWarmup        True if CGM is in warmup period — blocks UAM if pref enabled
     * @param inPostMealLockout  True if within post-meal dirty window — stricter thresholds apply
     */
    fun onLoopCycle(
        currentMealMode:   MealMode,
        currentBgMmol:     Double,
        deltaMmol:         Double,
        shortAvgDeltaMmol: Double,
        bgiMmol:           Double,
        currentHour:       Int,
        bgWentLow:         Boolean,
        inReboundWindow:   Boolean,
        lastLowTimeMs:     Long,
        highTempTarget:    Boolean,
        cgmInWarmup:       Boolean,
        inPostMealLockout: Boolean
    ) {
        previousMealMode           = currentMealMode
        currentlyInPostMealLockout = inPostMealLockout
        justFiredThisCycle         = null  // reset each cycle

        // Reset lastMealEndedMs if it's from a previous calendar day
        if (lastMealEndedMs > 0L) {
            val mealCal = java.util.Calendar.getInstance().also { it.timeInMillis = lastMealEndedMs }
            val nowCal  = java.util.Calendar.getInstance()
            if (mealCal.get(java.util.Calendar.DAY_OF_YEAR) != nowCal.get(java.util.Calendar.DAY_OF_YEAR) ||
                mealCal.get(java.util.Calendar.YEAR) != nowCal.get(java.util.Calendar.YEAR)) {
                aapsLogger.debug(LTag.APS, "UAM: new day — resetting lastMealEndedMs, P/F requires today's meal")
                lastMealEndedMs = 0L
            }
        }

        val wasMealMode = currentlyInMealMode
        currentlyInMealMode = currentMealMode != MealMode.FASTING
        // Track when meal mode expires so P/F knows a meal has happened
        if (wasMealMode && !currentlyInMealMode) {
            lastMealEndedMs = System.currentTimeMillis()
            aapsLogger.debug(LTag.APS, "UAM: meal mode ended — P/F armed for fat/protein tail")
        }

        if (!preferences.get(BooleanKey.ApsSmartInsulinUamEnabled)) {
            resetStreak()
            stuckHighReadings = 0
            return
        }

        // Only detect during fasting — don't stack on manual or active UAM mode
        if (currentMealMode != MealMode.FASTING) {
            resetStreak()
            stuckHighReadings = 0
            return
        }

        // High temp target — user deliberately conservative (exercise/illness). Block UAM.
        if (highTempTarget) {
            if (consecutiveRiseReadings > 0 || stuckHighReadings > 0) {
                aapsLogger.debug(LTag.APS, "UAM: blocked — high temp target active, streak reset")
                resetStreak()
                stuckHighReadings = 0
            }
            return
        }

        // CGM warmup — deltas unreliable, block UAM detection
        if (cgmInWarmup && preferences.get(BooleanKey.ApsSmartInsulinUamCgmWarmupBlock)) {
            if (consecutiveRiseReadings > 0 || stuckHighReadings > 0) {
                aapsLogger.debug(LTag.APS, "UAM: blocked — CGM in warmup, streak reset")
                resetStreak()
                stuckHighReadings = 0
            }
            return
        }

        // ── Hard night cutoff ─────────────────────────────────────────────────
        val nightCutoff = preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)
        val dayStart    = preferences.get(IntKey.ApsSmartInsulinUamDayStartHour)
        // UAM active window: dayStart until nightCutoff, wrapping midnight.
        // e.g. dayStart=10, cutoff=1 → active 10am–1am (blocked 1am–10am).
        // When cutoff < dayStart the window crosses midnight — split into two ranges.
        val inActiveWindow = if (nightCutoff > dayStart) {
            currentHour in dayStart until nightCutoff          // simple: e.g. 9am–11pm
        } else {
            currentHour >= dayStart || currentHour < nightCutoff  // wraps: e.g. 10am–1am
        }
        if (!inActiveWindow) {
            currentlyPastNightCutoff = true
            if (consecutiveRiseReadings > 0 || stuckHighReadings > 0) {
                aapsLogger.debug(LTag.APS, "UAM: outside active window (hour=$currentHour window=${dayStart}:00-${nightCutoff}:00), reset")
                resetStreak()
                stuckHighReadings = 0
            }
            return
        }
        currentlyPastNightCutoff = false

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

        // Re-arm deliberately removed — UAM fires once, loop handles the rest.
        // A fresh genuine rise will be detected naturally without a re-arm gate.

        // ── Protein/Fat stuck-high detection (runs in parallel with rise detection) ──
        // UAM_PROTEIN_FAT has its own separate counter and logic — it's not time-window
        // gated like meal slots. Runs every fasting cycle after safety checks pass.
        checkStuckHigh(currentBgMmol, deltaMmol, shortAvgDeltaMmol, currentHour, bgWentLow, inReboundWindow, lastLowTimeMs, currentMealMode, inPostMealLockout)

        // ── Resolve time window ───────────────────────────────────────────────
        val uamMode = resolveUamMode(currentHour) ?: run {
            resetStreak(); return
        }

        // Reset streak if we've moved into a different meal window mid-streak.
        // Avoids carrying a Lunch-window streak into the Dinner window.
        if (consecutiveRiseReadings > 0 && lastResolvedMode != null && lastResolvedMode != uamMode) {
            aapsLogger.debug(LTag.APS, "UAM: window changed ${lastResolvedMode!!.label}→${uamMode.label}, streak reset")
            resetStreak()
        }
        lastResolvedMode = uamMode

        // ── BG above trigger threshold ────────────────────────────────────────
        val triggerThresholdMmol = preferences.get(DoubleKey.ApsSmartInsulinUamTriggerThresholdMmol)

        // Wobble tolerance: allow a single dip up to 0.3 mmol below threshold without
        // killing an active streak, as long as shortAvgDelta is still positive.
        // CGM noise/compression commonly produces one low reading mid-rise — without this,
        // a genuine upward trend gets reset by a single noisy point.
        val aboveThreshold = currentBgMmol >= triggerThresholdMmol ||
            (consecutiveRiseReadings > 0 &&
                shortAvgDeltaMmol > 0.0 &&
                currentBgMmol >= triggerThresholdMmol - WOBBLE_TOLERANCE_MMOL)

        if (!aboveThreshold) {
            resetStreak(); return
        }

        // ── Rise confirmation: delta, shortAvgDelta, AND BGI-gap ─────────────
        // unexpectedDelta = how much BG is rising beyond what insulin predicts.
        // BGI is typically negative (insulin pulling BG down), so unexpectedDelta
        // is larger than raw delta when insulin is active — amplifying genuine UAM signal.
        // A low unexpectedDelta means the rise is mostly explained by weak/absent insulin
        // activity and is likely drift or noise rather than food.
        val riseMinDeltaBase   = preferences.get(DoubleKey.ApsSmartInsulinUamRiseMinDeltaMmol)
        val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)

        // During post-meal dirty window, require a stronger rise to confirm it's a new
        // meal rather than a fat/protein tail. Slow tails fail the stricter bar and
        // fall through to STFT + UAM_PROTEIN_FAT stuck-high detection instead.
        val dirtyMultiplier    = if (inPostMealLockout) DIRTY_WINDOW_DELTA_MULTIPLIER else 1.0
        val unexpectedMultiplier = if (inPostMealLockout) DIRTY_WINDOW_UNEXPECTED_MULT else 1.0
        val riseMinDelta       = riseMinDeltaBase * dirtyMultiplier
        val shortAvgThreshold  = riseMinDelta * SHORT_AVG_DELTA_FRACTION
        val unexpectedMin      = UNEXPECTED_RISE_MIN_MMOL * unexpectedMultiplier

        val unexpectedDelta = deltaMmol - bgiMmol
        val unexpectedShort = shortAvgDeltaMmol - bgiMmol

        // Delta wobble tolerance: if shortAvgDelta is close to riseMinDelta (>= 85%),
        // allow both delta and shortAvg to be slightly below threshold.
        // This handles the common case where delta consistently reads 0.17 but displays
        // as 0.2 due to rounding — both values confirm a genuine rise just under threshold.
        val closeToThreshold = shortAvgDeltaMmol >= riseMinDelta * 0.85
        val deltaWobbleTolerance = if (closeToThreshold) 0.85 else 1.0
        val risingNow = deltaMmol >= riseMinDelta * deltaWobbleTolerance &&
            shortAvgDeltaMmol >= shortAvgThreshold * deltaWobbleTolerance &&
            unexpectedDelta >= unexpectedMin &&
            unexpectedShort >= unexpectedMin * SHORT_AVG_DELTA_FRACTION

        if (risingNow) {
            if (consecutiveRiseReadings == 0) bgAtStreakStart = currentBgMmol
            consecutiveRiseReadings++
            val totalRise = currentBgMmol - bgAtStreakStart
            val dirtyTag = if (inPostMealLockout) " [DIRTY×${String.format("%.1f", dirtyMultiplier)}]" else ""
            aapsLogger.debug(LTag.APS,
                             "UAM: rise $consecutiveRiseReadings/$riseReadingsNeeded$dirtyTag " +
                                 "bg=${String.format("%.1f", currentBgMmol)}mmol " +
                                 "Δ=${String.format("%+.2f", deltaMmol)}(≥${String.format("%.2f", riseMinDelta)}) " +
                                 "avg=${String.format("%+.2f", shortAvgDeltaMmol)} " +
                                 "bgi=${String.format("%+.2f", bgiMmol)} " +
                                 "uΔ=${String.format("%+.2f", unexpectedDelta)}(≥${String.format("%.2f", unexpectedMin)}) " +
                                 "uAvg=${String.format("%+.2f", unexpectedShort)} " +
                                 "total=${String.format("%+.1f", totalRise)}mmol mode=${uamMode.label}")
        } else {
            if (consecutiveRiseReadings > 0) {
                val dirtyNote = if (inPostMealLockout) " [dirty-window stricter thresholds]" else ""
                aapsLogger.debug(LTag.APS,
                                 "UAM: streak broken$dirtyNote — " +
                                     "Δ=${String.format("%+.2f", deltaMmol)}(need>=${String.format("%.2f", riseMinDelta)}) " +
                                     "avg=${String.format("%+.2f", shortAvgDeltaMmol)}(need>=${String.format("%.2f", shortAvgThreshold)}) " +
                                     "uΔ=${String.format("%+.2f", unexpectedDelta)}(need>=${String.format("%.2f", unexpectedMin)}) " +
                                     "bgi=${String.format("%+.2f", bgiMmol)}, reset")
            }
            // Record reject reason for SI tab debug display
            val rejectReason = when {
                deltaMmol < riseMinDelta         -> "Δ ${String.format("%.2f", deltaMmol)} < ${String.format("%.2f", riseMinDelta)}"
                shortAvgDeltaMmol < shortAvgThreshold -> "avg ${String.format("%.2f", shortAvgDeltaMmol)} < ${String.format("%.2f", shortAvgThreshold)}"
                unexpectedDelta < unexpectedMin  -> "uΔ ${String.format("%.2f", unexpectedDelta)} < ${String.format("%.2f", unexpectedMin)}"
                unexpectedShort < unexpectedMin * SHORT_AVG_DELTA_FRACTION -> "uAvg ${String.format("%.2f", unexpectedShort)} < ${String.format("%.2f", unexpectedMin * SHORT_AVG_DELTA_FRACTION)}"
                else                             -> "threshold not met"
            }
            lastReject = RejectInfo(rejectReason, deltaMmol, riseMinDelta, unexpectedDelta, unexpectedMin, inPostMealLockout)
            resetStreak(); return
        }

        // ── Trigger once consecutive readings met ────────────────────────────
        // The multi-layer confirmation (consecutive + shortAvgDelta + BGI gap) is
        // sufficient — no additional total-rise gate needed. Fires exactly when
        // settings say it should.
        if (consecutiveRiseReadings >= riseReadingsNeeded) {
            val totalRise = currentBgMmol - bgAtStreakStart
            triggerUam(uamMode, currentBgMmol, deltaMmol, totalRise)
            resetStreak()
        }
    }

    /**
     * Stuck-high detection for UAM_PROTEIN_FAT.
     * Called every fasting loop cycle. Triggers when BG has been above threshold
     * with flat delta for [STUCK_READINGS_NEEDED] consecutive readings (default 30 min).
     * Only fires when no meal-slot UAM window is active — protein/fat is the fallback.
     */
    private fun checkStuckHigh(
        currentBgMmol:     Double,
        deltaMmol:         Double,
        shortAvgDeltaMmol: Double,
        currentHour:       Int,
        bgWentLow:         Boolean,
        inReboundWindow:   Boolean,
        lastLowTimeMs:     Long,
        currentMealMode:   MealMode,
        inPostMealLockout: Boolean
    ) {
        // Check P/F preference directly — uamModeEnabled() returns false for P/F
        if (!preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled)) {
            stuckHighReadings = 0
            return
        }

        // Block P/F while a meal or UAM mode is active — let those handle the carb rise.
        // P/F is for the fat/protein TAIL after the meal mode expires, not the initial rise.
        if (currentMealMode != MealMode.FASTING) {
            if (stuckHighReadings > 0) stuckHighReadings = 0
            return
        }

        // P/F runs as default fasting watchdog within the active time window.
        // If BG is stuck above threshold during fasting, P/F handles it regardless of
        // whether a meal has occurred — the time window + flat delta + 4 readings is
        // sufficient filter. UAM rise detection overrides P/F if carbs arrive.

        // Respect the same safety blocks as rise detection
        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val lowBlockMs = LOW_BLOCK_MINS * 60_000L
        if (bgWentLow || inReboundWindow || msSinceLow < lowBlockMs) {
            stuckHighReadings = 0
            return
        }

        // P/F uses its own threshold — higher than rise detection threshold
        // since fat/protein genuinely elevates BG, don't want P/F firing near target
        val triggerThresholdMmol = preferences.get(DoubleKey.ApsSmartInsulinUamProteinFatThresholdMmol)

        // Track last values for SI tab debug display
        lastStuckAvgDelta = shortAvgDeltaMmol
        lastStuckBgMmol   = currentBgMmol

        // BG must be above threshold AND shortAvgDelta must be flat (not falling, not spiking).
        // Using shortAvgDelta rather than instantaneous delta prevents a single noisy CGM
        // reading (e.g. +0.3 on an otherwise flat plateau) from killing a 25-min streak.
        val isStuck = currentBgMmol >= triggerThresholdMmol &&
            shortAvgDeltaMmol >= STUCK_DELTA_MIN_MMOL &&
            shortAvgDeltaMmol <= STUCK_DELTA_MAX_MMOL

        // Debug: always log isStuck evaluation so we can see why it's not counting
        if (!isStuck) {
            aapsLogger.debug(LTag.APS,
                             "UAM_PROTEIN_FAT: not stuck — " +
                                 "bg=${String.format("%.2f", currentBgMmol)}(need>=${String.format("%.1f", triggerThresholdMmol)}) " +
                                 "avg=${String.format("%+.3f", shortAvgDeltaMmol)}(need ${String.format("%.2f", STUCK_DELTA_MIN_MMOL)}→${String.format("%.2f", STUCK_DELTA_MAX_MMOL)})")
        }

        if (isStuck) {
            stuckHighReadings++
            aapsLogger.debug(LTag.APS,
                             "UAM_PROTEIN_FAT: stuck-high $stuckHighReadings/${preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)} " +
                                 "bg=${String.format("%.1f", currentBgMmol)}mmol " +
                                 "avg=${String.format("%+.2f", shortAvgDeltaMmol)}mmol")

            if (stuckHighReadings >= preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)) {
                aapsLogger.debug(LTag.APS,
                                 "UAM_PROTEIN_FAT: TRIGGERING after ${stuckHighReadings * 5}min stuck above " +
                                     "${triggerThresholdMmol}mmol")
                triggerUam(MealMode.UAM_PROTEIN_FAT, currentBgMmol, deltaMmol, 0.0)
                stuckHighReadings = 0
            }
        } else {
            if (stuckHighReadings > 0)
                aapsLogger.debug(LTag.APS,
                                 "UAM_PROTEIN_FAT: stuck streak broken " +
                                     "(bg=${String.format("%.1f", currentBgMmol)} avg=${String.format("%+.2f", shortAvgDeltaMmol)}), reset")
            stuckHighReadings = 0
        }
    }

    /**
     * Full debug summary for the SmartInsulin tab — shows thresholds, active state, last reject.
     */
    fun debugSummary(): String {
        val riseMinDeltaBase = preferences.get(DoubleKey.ApsSmartInsulinUamRiseMinDeltaMmol)
        val normalDelta      = riseMinDeltaBase
        val dirtyDelta       = riseMinDeltaBase * DIRTY_WINDOW_DELTA_MULTIPLIER
        val normalUnexpected = UNEXPECTED_RISE_MIN_MMOL
        val dirtyUnexpected  = UNEXPECTED_RISE_MIN_MMOL * DIRTY_WINDOW_UNEXPECTED_MULT
        val riseNeeded       = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
        val activeMode       = if (currentlyInPostMealLockout) "dirty" else "normal"

        return buildString {
            appendLine("  UAM thresholds:")
            appendLine("    normal: Δ≥${String.format("%.2f", normalDelta)}  uΔ≥${String.format("%.2f", normalUnexpected)}  readings=$riseNeeded")
            appendLine("    dirty : Δ≥${String.format("%.2f", dirtyDelta)}  uΔ≥${String.format("%.2f", dirtyUnexpected)}  readings=$riseNeeded")
            appendLine("    active: $activeMode")
            val reject = lastReject
            if (reject != null) {
                val dirtyTag = if (reject.wasDirtyWindow) " [dirty]" else ""
                appendLine("  Last UAM reject$dirtyTag: ${reject.reason}")
            }
            // P/F stuck-high detail
            val triggerMmol = preferences.get(DoubleKey.ApsSmartInsulinUamTriggerThresholdMmol)
            val pfEnabled = preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled)
            if (pfEnabled) {
                val stuckNeeded = preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)
                appendLine("  P/F detection: enabled (flat Δ ${STUCK_DELTA_MIN_MMOL}→${STUCK_DELTA_MAX_MMOL}mmol for $stuckNeeded readings)")
                when {
                    currentlyPastNightCutoff ->
                        appendLine("  P/F stuck: off (outside active window ${preferences.get(IntKey.ApsSmartInsulinUamDayStartHour)}:00–${preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)}:00)")
                    currentlyInMealMode ->
                        appendLine("  P/F stuck: off (meal mode active — will arm after expiry)")

                    else -> {
                        val avgStr = String.format("%+.2f", lastStuckAvgDelta)
                        val bgStr  = String.format("%.1f", lastStuckBgMmol)
                        val countStr = "$stuckHighReadings/$stuckNeeded"
                        val rangeStr = "${STUCK_DELTA_MIN_MMOL}→${STUCK_DELTA_MAX_MMOL}"
                        val meetsRange = lastStuckAvgDelta >= STUCK_DELTA_MIN_MMOL && lastStuckAvgDelta <= STUCK_DELTA_MAX_MMOL
                        val meetsBg    = lastStuckBgMmol >= triggerMmol
                        val blockReason = when {
                            !meetsBg    -> " ✗ BG ${bgStr} < ${triggerMmol}"
                            !meetsRange -> " ✗ avg ${avgStr} outside ${rangeStr}"
                            else        -> " ✓ counting"
                        }
                        appendLine("  P/F stuck: $countStr  avg=${avgStr}mmol (${rangeStr})$blockReason")
                    }
                }
            } else {
                appendLine("  P/F detection: disabled")
            }
        }.trimEnd()
    }

    /** Status string for loop reason output — null if nothing to show */
    fun statusString(): String? {
        val dirtyTag = if (currentlyInPostMealLockout) "[dirty] " else ""
        if (consecutiveRiseReadings > 0) {
            val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
            val threshNote = if (currentlyInPostMealLockout) " δ≥${String.format("%.2f", preferences.get(DoubleKey.ApsSmartInsulinUamRiseMinDeltaMmol) * DIRTY_WINDOW_DELTA_MULTIPLIER)}" else ""
            return "UAM: ${dirtyTag}watching ($consecutiveRiseReadings/$riseReadingsNeeded rising$threshNote)"
        }
        // Always show P/F count if enabled and in active window
        if (!currentlyPastNightCutoff && preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled)) {
            val triggerThresholdMmol = preferences.get(DoubleKey.ApsSmartInsulinUamTriggerThresholdMmol)
            val stuckNeeded = preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)
            if (stuckHighReadings > 0 || consecutiveRiseReadings == 0) {
                return "UAM: P/F $stuckHighReadings/$stuckNeeded stuck ≥${String.format("%.1f", triggerThresholdMmol)}mmol"
            }
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

    /** The UAM mode fired this cycle — set by triggerUam, reset at start of each cycle. Null if nothing fired. */
    var justFiredThisCycle: MealMode? = null
        private set

    private fun triggerUam(mode: MealMode, bgMmol: Double, deltaMmol: Double, totalRise: Double) {
        justFiredThisCycle = mode
        val durationMins = uamDurationMins(mode)
        val isfMmol      = uamIsfMmol(mode)
        val now          = System.currentTimeMillis()

        lastUamTriggerCount = if (lastUamMode == mode &&
            now - lastUamTimeMs < 4 * 60 * 60 * 1000L) lastUamTriggerCount + 1 else 1
        lastUamMode    = mode
        lastUamTimeMs  = now

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
            Triple(MealMode.UAM_AFTERNOON,
                   preferences.get(IntKey.ApsSmartInsulinUamAfternoonStartHour),
                   preferences.get(IntKey.ApsSmartInsulinUamAfternoonEndHour)),
            // UAM_PROTEIN_FAT has no time window — handled separately by checkStuckHigh()
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
        MealMode.UAM_AFTERNOON -> preferences.get(BooleanKey.ApsSmartInsulinUamAfternoonEnabled)
        MealMode.UAM_PROTEIN_FAT  -> false  // no time window — P/F uses direct pref check in checkStuckHigh()
        else                   -> false
    }

    private fun uamDurationMins(mode: MealMode): Long = when (mode) {
        MealMode.UAM_BREAKFAST -> preferences.get(IntKey.ApsSmartInsulinUamBreakfastDurationMins).toLong()
        MealMode.UAM_LUNCH     -> preferences.get(IntKey.ApsSmartInsulinUamLunchDurationMins).toLong()
        MealMode.UAM_DINNER    -> preferences.get(IntKey.ApsSmartInsulinUamDinnerDurationMins).toLong()
        MealMode.UAM_SNACK     -> preferences.get(IntKey.ApsSmartInsulinUamSnackDurationMins).toLong()
        MealMode.UAM_AFTERNOON -> preferences.get(IntKey.ApsSmartInsulinUamAfternoonDurationMins).toLong()
        MealMode.UAM_PROTEIN_FAT  -> preferences.get(IntKey.ApsSmartInsulinUamProteinFatDurationMins).toLong()
        else                   -> 30L
    }

    private fun uamIsfMmol(mode: MealMode): Double = when (mode) {
        MealMode.UAM_BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinUamBreakfastIsf)
        MealMode.UAM_LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinUamLunchIsf)
        MealMode.UAM_DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinUamDinnerIsf)
        MealMode.UAM_SNACK     -> preferences.get(DoubleKey.ApsSmartInsulinUamSnackIsf)
        MealMode.UAM_AFTERNOON -> preferences.get(DoubleKey.ApsSmartInsulinUamAfternoonIsf)
        MealMode.UAM_PROTEIN_FAT  -> preferences.get(DoubleKey.ApsSmartInsulinUamProteinFatIsf)
        else                   -> 0.0  // no ISF override for unknown modes
    }
}