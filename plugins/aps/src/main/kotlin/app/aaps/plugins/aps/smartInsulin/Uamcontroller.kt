package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.interfaces.sharedPreferences.SP
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
    private val sp:                  SP,
    private val mealOverrideManager: MealOverrideManager,
    private val profileUtil:         ProfileUtil,
    private val aapsLogger:          AAPSLogger
) {

    // ── State ─────────────────────────────────────────────────────────────────
    private var consecutiveRiseReadings = 0
    private var bgAtStreakStart:          Double = 0.0
    private var lastCountedBgTimestampMs: Long   = 0L  // CGM timestamp of last reading that incremented streak
    private var lastStuckBgTimestampMs:   Long   = 0L  // CGM timestamp of last reading that incremented P/F streak
    private var lastUamMode:    MealMode? = null
    private var lastUamTimeMs:  Long      = 0L
    private var lastUamTriggerCount       = 0
    private var previousMealMode: MealMode?  = null  // for expiry transition detection
    private var lastResolvedMode: MealMode? = null  // for window-change streak reset

    // Burst tracking — independent of delta streak, tracks from local BG minimum.
    // bgBurstTrackStart resets to current BG whenever BG drops, so it always reflects
    // the lowest recent BG. This means burst can fire on +3+8+9 (total=+20) even though
    // the +3 failed the delta threshold and never started a streak.
    private var bgBurstTrackStart: Double = 0.0

    // Stuck-high state for UAM_PROTEIN_FAT detection
    private var stuckHighReadings = 0
    // Last BG seen during a rise streak — for statusString display only
    private var lastRiseBgMmol = 0.0
    // Post-meal lockout state — updated each cycle for statusString access
    private var currentlyInPostMealLockout = false
    private var currentlyPastNightCutoff   = false
    private var currentlyInMealMode        = false  // true when meal/UAM mode active — P/F blocked
    private var currentlyCgmWarmup         = false  // true when CGM is in warmup — UAM/P/F blocked
    private var currentlyHighTempTarget    = false  // true when high temp target active — UAM/P/F blocked
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
        // Stricter thresholds during post-meal dirty window — distinguishes genuine
        // second meal (strong sharp rise) from fat/protein tail (slow weak rise)
        private const val DIRTY_WINDOW_DELTA_MULTIPLIER   = 1.5   // 0.2 → 0.3
        private const val DIRTY_WINDOW_UNEXPECTED_MULT    = 1.67  // 0.15 → 0.25

        // How long to show "UAM: last <mode> HH:MM" in the status line.
        // After this window the display resets to idle/armed — prevents showing a
        // 12-hour-old detection time indefinitely.
        private const val LAST_UAM_DISPLAY_WINDOW_MS  = 4 * 60 * 60 * 1000L  // 4h

        // Stuck-high detection for UAM_PROTEIN_FAT
        // Delta must be in this range to count as "stuck" (not falling, not spiking)
        private const val STUCK_DELTA_MIN_MMOL        = -0.15  // -0.1 with small noise tolerance — genuinely falling (-0.2+) excluded
        private const val STUCK_DELTA_MAX_MMOL        = 0.25   // not spiking — raised from 0.2 to tolerate slight noise
        // 6 readings = 30 min at 5 min intervals
        // STUCK_READINGS_NEEDED moved to user preference ApsSmartInsulinUamProteinFatStuckReadings
    }

    // ── Unit conversion helpers ───────────────────────────────────────────────
    // All SI UnitDoubleKey values store raw mg/dL in SharedPreferences.
    // We MUST NOT use preferences.get(UnitDoubleKey) here — PreferencesImpl applies
    // valueInCurrentUnitsDetect() which uses a <36 heuristic that misidentifies small
    // mg/dL values (e.g. 3.6 riseMinDelta, 9/18/27 activity targets) as mmol and
    // multiplies by 18, making thresholds 18× too high for mg/dL users.
    // Read a UnitDoubleKey value, handling both storage formats:
    //  - New format: stored as mg/dL float (after AdaptiveUnitPreference fix)
    //  - Old format: stored as mmol display value float (before fix)
    // BG/threshold keys: values <20 were stored as mmol → ×18 to get mg/dL
    // ISF keys: already stored as mg/dL by sp.putDouble — use sp.getDouble directly
    private fun rawMgdl(key: UnitDoubleKey, mmolThreshold: Double = 20.0): Double {
        val raw = sp.getDouble(key.key, key.defaultValue)
        return if (raw < mmolThreshold) raw * 18.0 else raw
    }
    private fun unitPrefMmol(key: UnitDoubleKey): Double = rawMgdl(key) / 18.0
    private fun purePrefMmol(key: UnitDoubleKey): Double = sp.getDouble(key.key, key.defaultValue) / 18.0
    private fun isfPrefMgdl(key: UnitDoubleKey): Double  = sp.getDouble(key.key, key.defaultValue)

    // ── Unit-aware display helpers ────────────────────────────────────────────
    // Internal BG/threshold values are always in mmol. Convert to mg/dL for display
    // when the user has selected mg/dL units. Delta values follow the same rule.
    private val isMmol: Boolean get() =
        profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL

    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"

    /** Format a BG or threshold value (internal mmol) for display in user units */
    private fun fmtBg(mmol: Double): String =
        if (isMmol) String.format("%.1f", mmol)
        else        String.format("%.0f", mmol * 18.0)

    /** Format a delta value (internal mmol) for display in user units */
    private fun fmtDelta(mmol: Double): String =
        if (isMmol) String.format("%+.2f", mmol)
        else        String.format("%+.1f", mmol * 18.0)

    /** Format a delta threshold (internal mmol, no sign) for display in user units */
    private fun fmtThresh(mmol: Double): String =
        if (isMmol) String.format("%.2f", mmol)
        else        String.format("%.1f", mmol * 18.0)

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
     * @param profileTargetMmol  Profile target in mmol — P/F streak resets if BG returns to target
     * @param softLandingBypass  True if soft landing — UAM allowed during rebound window
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
        inPostMealLockout:  Boolean,
        profileTargetMmol:  Double,
        softLandingBypass:  Boolean = false,
        bgTimestampMs:      Long    = 0L
    ) {
        previousMealMode           = currentMealMode
        currentlyInPostMealLockout = inPostMealLockout
        currentlyCgmWarmup         = cgmInWarmup && preferences.get(BooleanKey.ApsSmartInsulinUamCgmWarmupBlock)
        currentlyHighTempTarget    = highTempTarget
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
        if (softLandingBypass && (bgWentLow || inReboundWindow)) {
            aapsLogger.debug(LTag.APS, "UAM: soft landing bypass active — detection allowed during rebound")
        }

        // ── Safety block: recent low / rebound ───────────────────────────────
        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val lowBlockMs = LOW_BLOCK_MINS * 60_000L
        // softLandingBypass overrides bgWentLow/inReboundWindow but not the time-based block
        val blockedByLow = (bgWentLow || inReboundWindow || msSinceLow < lowBlockMs) && !softLandingBypass
        if (blockedByLow) {
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
        checkStuckHigh(currentBgMmol, deltaMmol, shortAvgDeltaMmol, currentHour, bgWentLow, inReboundWindow, lastLowTimeMs, currentMealMode, inPostMealLockout, profileTargetMmol, bgTimestampMs)

        // ── Resolve time window ───────────────────────────────────────────────
        val uamMode = resolveUamMode(currentHour) ?: run {
            lastReject = RejectInfo("no meal window active at hour $currentHour", 0.0, 0.0, 0.0, 0.0, inPostMealLockout)
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
        val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold)

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

        // ── Burst tracking — update local BG minimum ─────────────────────────
        // Track from the lowest recent BG, but RESET the memory when the upward
        // trend dies (shortAvgDeltaMmol <= 0.0). This prevents a low from hours
        // ago from triggering a phantom burst on slow baseline drift.
        if (bgBurstTrackStart <= 0.0 || currentBgMmol < bgBurstTrackStart || shortAvgDeltaMmol <= 0.0) {
            bgBurstTrackStart = currentBgMmol
        }

        // ── Rise confirmation: delta, shortAvgDelta, AND BGI-gap ─────────────
        // unexpectedDelta = how much BG is rising beyond what insulin predicts.
        // BGI is typically negative (insulin pulling BG down), so unexpectedDelta
        // is larger than raw delta when insulin is active — amplifying genuine UAM signal.
        // A low unexpectedDelta means the rise is mostly explained by weak/absent insulin
        // activity and is likely drift or noise rather than food.
        val riseMinDeltaBase   = purePrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta)
        val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)

        // During post-meal dirty window, require a stronger rise to confirm it's a new
        // meal rather than a fat/protein tail. Slow tails fail the stricter bar and
        // fall through to STFT + UAM_PROTEIN_FAT stuck-high detection instead.
        val dirtyMultiplier    = if (inPostMealLockout) DIRTY_WINDOW_DELTA_MULTIPLIER else 1.0
        val unexpectedMultiplier = if (inPostMealLockout) DIRTY_WINDOW_UNEXPECTED_MULT else 1.0
        val riseMinDelta       = riseMinDeltaBase * dirtyMultiplier
        val shortAvgThreshold  = riseMinDelta * SHORT_AVG_DELTA_FRACTION
        // unexpectedMin = 75% of riseMinDeltaBase — scales with user setting.
        // Gate 1 (Δ >= riseMinDelta) always evaluated first, so a rise smaller than
        // riseMinDelta can never trigger UAM regardless of unexpectedMin.
        // The 75% gives slight tolerance for meals eaten with active IOB — if BG rises
        // 0.20 but IOB accounts for -0.02, unexpected = 0.18 which passes 0.15 gate.
        val unexpectedMin      = riseMinDeltaBase * SHORT_AVG_DELTA_FRACTION * unexpectedMultiplier

        val unexpectedDelta = deltaMmol - bgiMmol
        val unexpectedShort = shortAvgDeltaMmol - bgiMmol

        // shortAvgDelta is the primary trend confirmation — it smooths over single noisy
        // readings. If shortAvgDelta confirms a genuine rise, allow instantaneous delta
        // to be a CGM noise reading without resetting the streak.
        // Rule: if shortAvgDelta >= riseMinDelta, delta only needs >= 50% of threshold.
        // This handles: shortAvg=+0.20, delta=+0.06 (noisy reading mid-rise) → still counts.
        // shortAvgDelta is the primary trend confirmation — it smooths over single noisy
        // readings. If shortAvgDelta confirms a genuine rise, allow instantaneous delta
        // to be a CGM noise reading without resetting the streak.
        // Rule: if shortAvgDelta >= riseMinDelta, delta only needs >= 50% of threshold.
        // This handles: shortAvg=+0.20, delta=+0.06 (noisy reading mid-rise) → still counts.
        // Can be disabled in settings — when OFF every reading must meet the full threshold.
        val wobbleEnabled = preferences.get(BooleanKey.ApsSmartInsulinUamWobbleTolerance)
        val trendConfirmedByAvg = wobbleEnabled && shortAvgDeltaMmol >= riseMinDelta
        val deltaMin = if (trendConfirmedByAvg) riseMinDelta * 0.5 else riseMinDelta
        val risingNow = deltaMmol >= deltaMin &&
            shortAvgDeltaMmol >= shortAvgThreshold &&
            unexpectedDelta >= unexpectedMin &&
            unexpectedShort >= unexpectedMin * SHORT_AVG_DELTA_FRACTION

        if (risingNow) {
            // Only count each CGM reading once — prevents manual loop refreshes from gaming the streak
            if (bgTimestampMs > 0L && bgTimestampMs == lastCountedBgTimestampMs) {
                aapsLogger.debug(LTag.APS, "UAM: same CGM reading (${bgTimestampMs}), skipping streak increment")
            } else {
                if (consecutiveRiseReadings == 0) bgAtStreakStart = currentBgMmol
                consecutiveRiseReadings++
                lastCountedBgTimestampMs = bgTimestampMs
            }
            lastRiseBgMmol = currentBgMmol
            val totalRise = currentBgMmol - bgAtStreakStart
            val dirtyTag = if (inPostMealLockout) " [DIRTY×${String.format("%.1f", dirtyMultiplier)}]" else ""
            aapsLogger.debug(LTag.APS,
                             "UAM: rise $consecutiveRiseReadings/$riseReadingsNeeded$dirtyTag " +
                                 "bg=${fmtBg(currentBgMmol)}$unitLabel " +
                                 "Δ=${fmtDelta(deltaMmol)}(≥${fmtThresh(riseMinDelta)}) " +
                                 "avg=${fmtDelta(shortAvgDeltaMmol)} " +
                                 "bgi=${fmtDelta(bgiMmol)} " +
                                 "uΔ=${fmtDelta(unexpectedDelta)}(≥${fmtThresh(unexpectedMin)}) " +
                                 "uAvg=${fmtDelta(unexpectedShort)} " +
                                 "total=${fmtDelta(totalRise)} mode=${uamMode.label}")
        } else {
            if (consecutiveRiseReadings > 0) {
                val dirtyNote = if (inPostMealLockout) " [dirty-window stricter thresholds]" else ""
                aapsLogger.debug(LTag.APS,
                                 "UAM: streak broken$dirtyNote — " +
                                     "Δ=${fmtDelta(deltaMmol)}(need>=${fmtThresh(riseMinDelta)}) " +
                                     "avg=${fmtDelta(shortAvgDeltaMmol)}(need>=${fmtThresh(shortAvgThreshold)}) " +
                                     "uΔ=${fmtDelta(unexpectedDelta)}(need>=${fmtThresh(unexpectedMin)}) " +
                                     "bgi=${fmtDelta(bgiMmol)}, reset")
            }
            // Always record reject reason — even at streak=0 so SI tab shows why UAM isn't counting
            val rejectReason = when {
                deltaMmol < deltaMin                  -> "Δ ${fmtDelta(deltaMmol)} < ${fmtThresh(deltaMin)}"
                shortAvgDeltaMmol < shortAvgThreshold -> "avg ${fmtDelta(shortAvgDeltaMmol)} < ${fmtThresh(shortAvgThreshold)}"
                unexpectedDelta < unexpectedMin       -> "uΔ ${fmtDelta(unexpectedDelta)} < ${fmtThresh(unexpectedMin)}"
                unexpectedShort < unexpectedMin * SHORT_AVG_DELTA_FRACTION -> "uAvg ${fmtDelta(unexpectedShort)} < ${fmtThresh(unexpectedMin * SHORT_AVG_DELTA_FRACTION)}"
                else                                  -> "threshold not met"
            }
            lastReject = RejectInfo(rejectReason, deltaMmol, riseMinDelta, unexpectedDelta, unexpectedMin, inPostMealLockout)
            resetStreak(); return
        }

        // ── Burst trigger — fire immediately on large sudden rise ────────────
        // ── Burst trigger — fire immediately on large sudden rise ────────────
        // Uses absoluteRise (from the local trough) rather than totalRise (from streak start).
        // This catches sequences like +3(fail)+8+9 = +20 total, where the failed +3 would
        // have reset bgAtStreakStart to a higher value losing the early rise.
        // Gated by consecutiveRiseReadings >= 1 — only bursts during an ACTIVE confirmed rise,
        // not on slow baseline drift from an old trough.
        val burstThreshold = purePrefMmol(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold)
        val absoluteRise   = currentBgMmol - bgBurstTrackStart
        if (burstThreshold > 0.0 && absoluteRise >= burstThreshold && consecutiveRiseReadings >= 1) {
            aapsLogger.debug(LTag.APS,
                             "UAM: BURST trigger (absolute) — absoluteRise=${fmtDelta(absoluteRise)}$unitLabel " +
                                 ">= threshold=${fmtBg(burstThreshold)}$unitLabel " +
                                 "after $consecutiveRiseReadings readings — firing ${uamMode.label}")
            triggerUam(uamMode, currentBgMmol, deltaMmol, absoluteRise)
            bgBurstTrackStart = 0.0
            resetStreak()
            return
        }

        // ── Normal trigger — consecutive readings met ─────────────────────────
        val totalRise = currentBgMmol - bgAtStreakStart
        if (consecutiveRiseReadings >= riseReadingsNeeded) {
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
        inPostMealLockout: Boolean,
        profileTargetMmol: Double,
        bgTimestampMs:     Long = 0L
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

        // P/F only arms after a meal or UAM mode has occurred today.
        // Without a prior meal, stuck-high fasting BG is a basal issue — not fat/protein tail.
        // lastMealEndedMs resets at midnight so P/F requires a same-day meal each day.
        if (lastMealEndedMs <= 0L) {
            if (stuckHighReadings > 0) {
                aapsLogger.debug(LTag.APS, "UAM_PROTEIN_FAT: blocked — no meal has occurred today")
                stuckHighReadings = 0
            }
            return
        }

        // Respect the same safety blocks as rise detection
        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val lowBlockMs = LOW_BLOCK_MINS * 60_000L
        if (bgWentLow || inReboundWindow || msSinceLow < lowBlockMs) {
            stuckHighReadings = 0
            return
        }

        // P/F uses its own threshold — higher than rise detection threshold
        // since fat/protein genuinely elevates BG, don't want P/F firing near target
        val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold)

        // Reset if BG has returned to profile target — stuck-high condition no longer valid
        if (stuckHighReadings > 0 && currentBgMmol <= profileTargetMmol) {
            aapsLogger.debug(LTag.APS,
                             "UAM_PROTEIN_FAT: streak reset — BG ${fmtBg(currentBgMmol)}$unitLabel " +
                                 "back at/below target ${fmtBg(profileTargetMmol)}$unitLabel")
            stuckHighReadings = 0
            return
        }

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
                                 "bg=${fmtBg(currentBgMmol)}(need>=${fmtBg(triggerThresholdMmol)}) " +
                                 "avg=${fmtDelta(shortAvgDeltaMmol)}(need ${fmtThresh(STUCK_DELTA_MIN_MMOL)}→${fmtThresh(STUCK_DELTA_MAX_MMOL)})")
        }

        if (isStuck) {
            // Only count each CGM reading once
            if (bgTimestampMs > 0L && bgTimestampMs == lastStuckBgTimestampMs) {
                aapsLogger.debug(LTag.APS, "UAM_PROTEIN_FAT: same CGM reading, skipping increment")
                return
            }
            lastStuckBgTimestampMs = bgTimestampMs
            stuckHighReadings++
            aapsLogger.debug(LTag.APS,
                             "UAM_PROTEIN_FAT: stuck-high $stuckHighReadings/${preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)} " +
                                 "bg=${fmtBg(currentBgMmol)}$unitLabel " +
                                 "avg=${fmtDelta(shortAvgDeltaMmol)}$unitLabel")

            if (stuckHighReadings >= preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)) {
                aapsLogger.debug(LTag.APS,
                                 "UAM_PROTEIN_FAT: TRIGGERING after ${stuckHighReadings * 5}min stuck above " +
                                     "${fmtBg(triggerThresholdMmol)}$unitLabel")
                triggerUam(MealMode.UAM_PROTEIN_FAT, currentBgMmol, deltaMmol, 0.0)
                stuckHighReadings = 0
            }
        } else {
            if (stuckHighReadings > 0)
                aapsLogger.debug(LTag.APS,
                                 "UAM_PROTEIN_FAT: stuck streak broken " +
                                     "(bg=${fmtBg(currentBgMmol)} avg=${fmtDelta(shortAvgDeltaMmol)}), reset")
            stuckHighReadings = 0
            lastStuckBgTimestampMs = 0L
        }
    }

    /**
     * Full debug summary for the SmartInsulin tab — shows thresholds, active state, last reject.
     */
    fun debugSummary(): String {
        val riseMinDeltaBase = purePrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta)
        val normalDelta      = riseMinDeltaBase
        val dirtyDelta       = riseMinDeltaBase * DIRTY_WINDOW_DELTA_MULTIPLIER
        val normalUnexpected = riseMinDeltaBase * SHORT_AVG_DELTA_FRACTION
        val dirtyUnexpected  = riseMinDeltaBase * SHORT_AVG_DELTA_FRACTION * DIRTY_WINDOW_UNEXPECTED_MULT
        val riseNeeded       = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
        val activeMode       = if (currentlyInPostMealLockout) "dirty" else "normal"
        val u                = unitLabel

        return buildString {
            appendLine("  UAM thresholds:")
            appendLine("    normal: Δ≥${fmtThresh(normalDelta)}$u  uΔ≥${fmtThresh(normalUnexpected)}$u  readings=$riseNeeded")
            appendLine("    dirty : Δ≥${fmtThresh(dirtyDelta)}$u  uΔ≥${fmtThresh(dirtyUnexpected)}$u  readings=$riseNeeded")
            appendLine("    active: $activeMode")
            val reject = lastReject
            if (reject != null) {
                val dirtyTag = if (reject.wasDirtyWindow) " [dirty]" else ""
                appendLine("  Last UAM reject$dirtyTag: ${reject.reason}")
            }
            // P/F stuck-high detail
            val triggerMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold)
            val pfEnabled = preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled)
            if (pfEnabled) {
                val stuckNeeded = preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)
                appendLine("  P/F detection: enabled (flat Δ ${fmtThresh(STUCK_DELTA_MIN_MMOL)}→${fmtThresh(STUCK_DELTA_MAX_MMOL)}$u for $stuckNeeded readings)")
                when {
                    currentlyPastNightCutoff ->
                        appendLine("  P/F stuck: off (outside active window ${preferences.get(IntKey.ApsSmartInsulinUamDayStartHour)}:00–${preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)}:00)")
                    currentlyInMealMode ->
                        appendLine("  P/F stuck: off (meal mode active — will arm after expiry)")
                    else -> {
                        val avgStr   = fmtDelta(lastStuckAvgDelta)
                        val bgStr    = fmtBg(lastStuckBgMmol)
                        val countStr = "$stuckHighReadings/$stuckNeeded"
                        val rangeStr = "${fmtThresh(STUCK_DELTA_MIN_MMOL)}→${fmtThresh(STUCK_DELTA_MAX_MMOL)}"
                        val meetsRange = lastStuckAvgDelta >= STUCK_DELTA_MIN_MMOL && lastStuckAvgDelta <= STUCK_DELTA_MAX_MMOL
                        val meetsBg    = lastStuckBgMmol >= triggerMmol
                        val blockReason = when {
                            !meetsBg    -> " ✗ BG $bgStr < ${fmtBg(triggerMmol)}"
                            !meetsRange -> " ✗ avg $avgStr outside $rangeStr"
                            else        -> " ✓ counting"
                        }
                        appendLine("  P/F stuck: $countStr  avg=$avgStr$u ($rangeStr)$blockReason")
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

        // ── UAM meal detection status ─────────────────────────────────────────
        val uamLine = when {
            currentlyInMealMode        -> null  // meal mode active — UAM not needed
            currentlyHighTempTarget    -> "UAM: off (high temp target set)"
            currentlyCgmWarmup         -> "UAM: off (new sensor <24h)"
            currentlyPastNightCutoff   -> "UAM: off (outside hours)"
            consecutiveRiseReadings > 0 -> {
                val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
                val threshNote = if (currentlyInPostMealLockout) " δ≥${fmtThresh(purePrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta) * DIRTY_WINDOW_DELTA_MULTIPLIER)}" else ""
                val burstThreshold = purePrefMmol(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold)
                val totalRise    = if (bgAtStreakStart > 0.0) lastRiseBgMmol - bgAtStreakStart else 0.0
                val absoluteRise = if (bgBurstTrackStart > 0.0) lastRiseBgMmol - bgBurstTrackStart else 0.0
                val burstNote = if (burstThreshold > 0.0) " | Burst: ${fmtDelta(absoluteRise)}/${fmtDelta(burstThreshold)}$unitLabel" else ""
                "UAM: ${dirtyTag}watching ($consecutiveRiseReadings/$riseReadingsNeeded rising$threshNote$burstNote)"
            }
            lastUamMode != null && lastUamTimeMs > 0L &&
                (System.currentTimeMillis() - lastUamTimeMs) < LAST_UAM_DISPLAY_WINDOW_MS -> {
                val cal = Calendar.getInstance().also { it.timeInMillis = lastUamTimeMs }
                val timeStr = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                val countStr = if (lastUamTriggerCount > 1) " (×$lastUamTriggerCount)" else ""
                "UAM: last ${lastUamMode!!.label} $timeStr$countStr"
            }
            else -> {
                val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
                val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold)
                val dirtyNote = if (currentlyInPostMealLockout) " [dirty]" else ""
                "UAM: ${dirtyTag}armed (0/$riseReadingsNeeded >=${fmtBg(triggerThresholdMmol)}$unitLabel$dirtyNote)"
            }
        }

        // ── P/F stuck-high status ─────────────────────────────────────────────
        val pfLine = when {
            !preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled) -> null
            currentlyHighTempTarget  -> "P/F: off (high temp target set)"
            currentlyCgmWarmup       -> "P/F: off (new sensor <24h)"
            currentlyPastNightCutoff -> "P/F: off (outside hours)"
            currentlyInMealMode      -> "P/F: armed (after meal expires)"
            else -> {
                val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold)
                val stuckNeeded = preferences.get(IntKey.ApsSmartInsulinUamProteinFatStuckReadings)
                if (stuckHighReadings > 0)
                    "P/F: $stuckHighReadings/$stuckNeeded stuck ≥${fmtBg(triggerThresholdMmol)}$unitLabel"
                else
                    "P/F: 0/$stuckNeeded below ≥${fmtBg(triggerThresholdMmol)}$unitLabel"
            }
        }

        return listOfNotNull(uamLine, pfLine).joinToString(" | ").ifEmpty { null }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun resetStreak() {
        consecutiveRiseReadings   = 0
        bgAtStreakStart           = 0.0
        lastCountedBgTimestampMs  = 0L
        lastRiseBgMmol            = 0.0
    }

    /** The UAM mode fired this cycle — set by triggerUam, reset at start of each cycle. Null if nothing fired. */
    var justFiredThisCycle: MealMode? = null
        private set

    private fun triggerUam(mode: MealMode, bgMmol: Double, deltaMmol: Double, totalRise: Double) {
        justFiredThisCycle = mode
        val durationMins = uamDurationMins(mode)
        val isfMgdl      = uamIsfMgdl(mode)
        val now          = System.currentTimeMillis()

        lastUamTriggerCount = if (lastUamMode == mode &&
            now - lastUamTimeMs < 4 * 60 * 60 * 1000L) lastUamTriggerCount + 1 else 1
        lastUamMode    = mode
        lastUamTimeMs  = now

        aapsLogger.debug(LTag.APS,
                         "UAM: TRIGGERING ${mode.label} " +
                             "bg=${fmtBg(bgMmol)}$unitLabel " +
                             "Δ=${fmtDelta(deltaMmol)}$unitLabel " +
                             "totalRise=${fmtDelta(totalRise)}$unitLabel " +
                             "isf=${if (isfMgdl > 0.0) "${if (isMmol) String.format("%.1f", isfMgdl / 18.0) else String.format("%.0f", isfMgdl)}$unitLabel" else "profile"} duration=${durationMins}min " +
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

    private fun uamIsfMgdl(mode: MealMode): Double = when (mode) {
        MealMode.UAM_BREAKFAST   -> isfPrefMgdl(UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf)
        MealMode.UAM_LUNCH       -> isfPrefMgdl(UnitDoubleKey.ApsSmartInsulinUamLunchIsf)
        MealMode.UAM_DINNER      -> isfPrefMgdl(UnitDoubleKey.ApsSmartInsulinUamDinnerIsf)
        MealMode.UAM_SNACK       -> isfPrefMgdl(UnitDoubleKey.ApsSmartInsulinUamSnackIsf)
        MealMode.UAM_AFTERNOON   -> isfPrefMgdl(UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf)
        MealMode.UAM_PROTEIN_FAT -> isfPrefMgdl(UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf)
        else                     -> 0.0
    }
}