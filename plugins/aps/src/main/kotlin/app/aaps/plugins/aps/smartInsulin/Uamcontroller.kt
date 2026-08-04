package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.interfaces.sharedPreferences.SP
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UAM (Unannounced Meal) auto-detection controller.
 *
 * Monitors fasting BG during configured time windows and auto-activates the
 * appropriate UAM meal mode when a confirmed BG rise is detected.
 */
@Singleton
class UamController @Inject constructor(
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
    private var lastResolvedMode: MealMode? = null  // for window-change streak reset

    // Stuck-high state for UAM_PROTEIN_FAT detection
    private var stuckHighReadings = 0
    // Last BG seen during a rise streak — for statusString display only
    private var lastRiseBgMmol = 0.0
    // Last 2-reading rise seen by burst logic — for statusString display only
    private var lastBurstRiseMmol = 0.0
    // Most recent cycle's contribution to the burst rise — for breakdown display "(prev, last)"
    private var lastBurstDeltaMmol = 0.0
    // Post-meal lockout state — updated each cycle for statusString access
    private var currentlyInPostMealLockout = false
    private var currentlyPastNightCutoff   = false
    private var currentlyInMealMode        = false  // true when meal/UAM mode active — P/F blocked
    private var currentlyCgmWarmup         = false  // true when CGM is in warmup — UAM/P/F blocked
    private var currentlyHighTempTarget    = false  // true when high temp target active — UAM/P/F blocked
    private var lastMealEndedMs            = 0L     // timestamp of last meal/UAM mode expiry — tracked for display only; P/F no longer requires it
    private var lastStuckAvgDelta          = 0.0   // last shortAvgDelta seen by checkStuckHigh
    private var lastStuckBgMmol            = 0.0   // last BG seen by checkStuckHigh
    // Burst detection uses a simple 2-reading sliding window, independent of streak state.
    private var burstPrevBgMmol            = 0.0
    private var burstPrevBgTimestampMs     = 0L
    private var burstAnchorBgMmol          = 0.0
    // Rebound window transition tracking
    private var wasInReboundWindow         = false
    private var reboundExpiredMs           = 0L
    private var lastEpisodeWasSoftLanding  = false

    init {
        val saved = sp.getString(StringKey.ApsSmartInsulinLastMealEndedMs.key, StringKey.ApsSmartInsulinLastMealEndedMs.defaultValue).toLongOrNull() ?: 0L
        if (saved > 0L) {
            val isExpired = (System.currentTimeMillis() - saved) > 10 * 60 * 60 * 1000L
            if (!isExpired) {
                aapsLogger.debug(LTag.APS, "UAM: restored lastMealEndedMs from prefs — P/F armed")
                lastMealEndedMs = saved
            } else {
                aapsLogger.debug(LTag.APS, "UAM: discarding stale lastMealEndedMs (>10h old) — P/F disarmed")
                sp.edit { putString(StringKey.ApsSmartInsulinLastMealEndedMs.key, "0") }
                lastMealEndedMs = 0L
            }
        }
    }

    // Last reject tracking for debug display
    private data class RejectInfo(val reason: String, val deltaActual: Double, val deltaNeeded: Double,
                                  val unexpectedActual: Double, val unexpectedNeeded: Double,
                                  val wasDirtyWindow: Boolean)
    private var lastReject: RejectInfo? = null

    companion object {
        private const val LOW_BLOCK_MINS            = 90L
        private const val POST_REBOUND_LOCKOUT_MINS = 60L
        private const val BURST_MAX_CYCLE_GAP_MS    = 390_000L   // 6.5 min
        private const val SHORT_AVG_DELTA_FRACTION   = 0.75
        private const val WOBBLE_TOLERANCE_MMOL       = 0.3
        private const val DIRTY_WINDOW_DELTA_MULTIPLIER   = 1.5
        private const val DIRTY_WINDOW_UNEXPECTED_MULT    = 1.67
        private const val LAST_UAM_DISPLAY_WINDOW_MS  = 4 * 60 * 60 * 1000L
        private const val STUCK_DELTA_MIN_MMOL        = -0.15
        private const val STUCK_DELTA_MAX_MMOL        = 0.25
    }

    // ── Unit conversion helpers ───────────────────────────────────────────────
    // All UnitDoubleKey values are stored in mg/dL by SmartInsulinUnitPreference.
    // defaultValues are already in mg/dL, so no heuristic conversion is needed.
    // mgdlPrefMmol: reads mg/dL pref, returns mmol
    // unitPrefMmol: alias for clarity at call sites that think in mmol
    private fun mgdlPrefMmol(key: UnitDoubleKey): Double = sp.getDouble(key.key, key.defaultValue) / 18.0
    private fun unitPrefMmol(key: UnitDoubleKey): Double = sp.getDouble(key.key, key.defaultValue) / 18.0
    private fun isfPrefMgdl(key: UnitDoubleKey): Double  = sp.getDouble(key.key, key.defaultValue)

    // ── Unit-aware display helpers ────────────────────────────────────────────
    private val isMmol: Boolean get() =
        profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    private fun fmtBg(mmol: Double): String = if (isMmol) String.format("%.1f", mmol) else String.format("%.0f", mmol * 18.0)
    private fun fmtDelta(mmol: Double): String = if (isMmol) String.format("%+.2f", mmol) else String.format("%+.1f", mmol * 18.0)
    private fun fmtThresh(mmol: Double): String = if (isMmol) String.format("%.2f", mmol) else String.format("%.1f", mmol * 18.0)

    fun onLoopCycle(
        currentMealMode:   MealMode,
        currentBgMmol:     Double,
        deltaMmol:         Double,
        shortAvgDeltaMmol: Double,
        bgiMmol:           Double,
        currentHour:       Int,
        currentMinute:     Int = 0,
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
        currentlyInPostMealLockout = inPostMealLockout
        currentlyCgmWarmup         = cgmInWarmup && sp.getBoolean(BooleanKey.ApsSmartInsulinUamCgmWarmupBlock.key, BooleanKey.ApsSmartInsulinUamCgmWarmupBlock.defaultValue)
        currentlyHighTempTarget    = highTempTarget
        justFiredThisCycle         = null

        if (lastMealEndedMs > 0L) {
            val isExpired = (System.currentTimeMillis() - lastMealEndedMs) > 10 * 60 * 60 * 1000L
            if (isExpired) {
                lastMealEndedMs = 0L
                sp.edit { putString(StringKey.ApsSmartInsulinLastMealEndedMs.key, "0") }
            }
        }

        val wasMealMode = currentlyInMealMode
        currentlyInMealMode = currentMealMode != MealMode.FASTING
        if (wasMealMode && !currentlyInMealMode) {
            lastMealEndedMs = System.currentTimeMillis()
            sp.edit { putString(StringKey.ApsSmartInsulinLastMealEndedMs.key, lastMealEndedMs.toString()) }
        }

        if (wasInReboundWindow && !inReboundWindow) {
            reboundExpiredMs = System.currentTimeMillis()
        }
        wasInReboundWindow = inReboundWindow

        if (!sp.getBoolean(BooleanKey.ApsSmartInsulinUamEnabled.key, BooleanKey.ApsSmartInsulinUamEnabled.defaultValue)) {
            resetStreak()
            stuckHighReadings = 0
            return
        }

        if (currentMealMode != MealMode.FASTING || highTempTarget) {
            resetStreak(); stuckHighReadings = 0; return
        }

        if (cgmInWarmup && sp.getBoolean(BooleanKey.ApsSmartInsulinUamCgmWarmupBlock.key, BooleanKey.ApsSmartInsulinUamCgmWarmupBlock.defaultValue)) {
            resetStreak(); stuckHighReadings = 0; return
        }

        val nightCutoff = sp.getInt(IntKey.ApsSmartInsulinUamNightCutoffHour.key, IntKey.ApsSmartInsulinUamNightCutoffHour.defaultValue)
        val dayStart    = sp.getInt(IntKey.ApsSmartInsulinUamDayStartHour.key, IntKey.ApsSmartInsulinUamDayStartHour.defaultValue)
        val inActiveWindow = if (nightCutoff > dayStart) currentHour in dayStart until nightCutoff else currentHour >= dayStart || currentHour < nightCutoff

        if (!inActiveWindow) {
            currentlyPastNightCutoff = true
            resetStreak(); stuckHighReadings = 0
            if (lastMealEndedMs > 0L) {
                lastMealEndedMs = 0L
                sp.edit { putString(StringKey.ApsSmartInsulinLastMealEndedMs.key, "0") }
            }
            return
        }
        currentlyPastNightCutoff = false

        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val msSincePostRebound = if (reboundExpiredMs > 0L) System.currentTimeMillis() - reboundExpiredMs else Long.MAX_VALUE
        val inPostReboundLockout = msSincePostRebound < POST_REBOUND_LOCKOUT_MINS * 60_000L

        if (bgWentLow || inReboundWindow) lastEpisodeWasSoftLanding = softLandingBypass
        else if (!inPostReboundLockout) lastEpisodeWasSoftLanding = false

        val effectiveBypass = softLandingBypass || (inPostReboundLockout && lastEpisodeWasSoftLanding)
        // msSinceLow < LOW_BLOCK_MINS must ALSO be bypassed for soft landing —
        // previously it was a separate condition that stayed true for 90 min
        // regardless of bypass, blocking UAM even when the low was shallow.
        val blockedByLow = if (effectiveBypass) false
        else (bgWentLow || inReboundWindow || msSinceLow < LOW_BLOCK_MINS * 60_000L || inPostReboundLockout)

        if (blockedByLow) {
            resetStreak(); return
        }

        checkStuckHigh(currentBgMmol, deltaMmol, shortAvgDeltaMmol, currentHour, bgWentLow, inReboundWindow, lastLowTimeMs, currentMealMode, inPostMealLockout, profileTargetMmol, bgTimestampMs)

        val timeSinceLastBgMs  = bgTimestampMs - burstPrevBgTimestampMs
        val freshCycle         = timeSinceLastBgMs in 1L..BURST_MAX_CYCLE_GAP_MS

        if (burstPrevBgMmol == 0.0 || !freshCycle || currentBgMmol < burstPrevBgMmol - 0.01) {
            burstAnchorBgMmol = currentBgMmol
        } else if (burstAnchorBgMmol == 0.0) {
            burstAnchorBgMmol = burstPrevBgMmol
        }

        lastBurstRiseMmol = if (burstAnchorBgMmol > 0.0 && currentBgMmol > burstAnchorBgMmol) currentBgMmol - burstAnchorBgMmol else 0.0
        lastBurstDeltaMmol = if (lastBurstRiseMmol > 0.0 && freshCycle && currentBgMmol > burstPrevBgMmol) currentBgMmol - burstPrevBgMmol else 0.0

        val uamMode = resolveUamMode(currentHour, currentMinute) ?: run {
            lastReject = RejectInfo("no meal window active at hour $currentHour", 0.0, 0.0, 0.0, 0.0, inPostMealLockout)
            burstPrevBgMmol = currentBgMmol; burstPrevBgTimestampMs = bgTimestampMs
            resetStreak(); return
        }

        if (consecutiveRiseReadings > 0 && lastResolvedMode != null && lastResolvedMode != uamMode) resetStreak()
        lastResolvedMode = uamMode

        val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold)
        val aboveThreshold = currentBgMmol >= triggerThresholdMmol || (consecutiveRiseReadings > 0 && shortAvgDeltaMmol > 0.0 && currentBgMmol >= triggerThresholdMmol - WOBBLE_TOLERANCE_MMOL)

        if (!aboveThreshold) {
            resetStreak(); burstPrevBgMmol = currentBgMmol; burstPrevBgTimestampMs = bgTimestampMs; return
        }

        val burstThresholdPref = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold)
        if (burstThresholdPref > 0.0 && burstAnchorBgMmol > 0.0 && freshCycle && lastBurstRiseMmol >= burstThresholdPref - 0.01) {
            triggerUam(uamMode, currentBgMmol, deltaMmol, lastBurstRiseMmol)
            resetStreak()
            // Clear all burst state so we don't immediately re-fire on the next reading.
            // Without this, burstAnchorBgMmol stays at the pre-burst value and the
            // accumulated rise carries over — potentially re-triggering next cycle.
            burstPrevBgMmol = 0.0; burstPrevBgTimestampMs = 0L; burstAnchorBgMmol = 0.0
            lastBurstRiseMmol = 0.0; lastBurstDeltaMmol = 0.0
            return
        }
        burstPrevBgMmol = currentBgMmol; burstPrevBgTimestampMs = bgTimestampMs

        val riseMinDeltaBase   = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta)
        val riseReadingsNeeded = sp.getInt(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.key, IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.defaultValue)
        val dirtyMultiplier    = if (inPostMealLockout) DIRTY_WINDOW_DELTA_MULTIPLIER else 1.0
        val unexpectedMultiplier = if (inPostMealLockout) DIRTY_WINDOW_UNEXPECTED_MULT else 1.0
        val riseMinDelta       = riseMinDeltaBase * dirtyMultiplier
        val shortAvgThreshold  = riseMinDelta * SHORT_AVG_DELTA_FRACTION
        val unexpectedMin      = riseMinDeltaBase * SHORT_AVG_DELTA_FRACTION * unexpectedMultiplier
        val unexpectedDelta = deltaMmol - bgiMmol
        val unexpectedShort = shortAvgDeltaMmol - bgiMmol

        val wobbleEnabled = sp.getBoolean(BooleanKey.ApsSmartInsulinUamWobbleTolerance.key, BooleanKey.ApsSmartInsulinUamWobbleTolerance.defaultValue)
        val trendConfirmedByAvg = wobbleEnabled && shortAvgDeltaMmol >= riseMinDelta
        val deltaMin = if (trendConfirmedByAvg) riseMinDelta * 0.5 else riseMinDelta
        val risingNow = deltaMmol >= deltaMin && shortAvgDeltaMmol >= shortAvgThreshold && unexpectedDelta >= unexpectedMin && unexpectedShort >= unexpectedMin * SHORT_AVG_DELTA_FRACTION

        if (risingNow) {
            if (bgTimestampMs > 0L && bgTimestampMs == lastCountedBgTimestampMs) { /* skip */ }
            else {
                if (consecutiveRiseReadings == 0) bgAtStreakStart = currentBgMmol
                consecutiveRiseReadings++
                lastCountedBgTimestampMs = bgTimestampMs
            }
            lastRiseBgMmol = currentBgMmol
        } else {
            val rejectReason = when {
                deltaMmol < deltaMin                  -> "Δ ${fmtDelta(deltaMmol)} < ${fmtThresh(deltaMin)}"
                shortAvgDeltaMmol < shortAvgThreshold -> "avg ${fmtDelta(shortAvgDeltaMmol)} < ${fmtThresh(shortAvgThreshold)}"
                unexpectedDelta < unexpectedMin       -> "uΔ ${fmtDelta(unexpectedDelta)} < ${fmtThresh(unexpectedMin)}"
                unexpectedShort < unexpectedMin * SHORT_AVG_DELTA_FRACTION -> "uAvg ${fmtDelta(unexpectedShort)} < ${fmtThresh(unexpectedMin * SHORT_AVG_DELTA_FRACTION)}"
                else                                  -> "threshold not met"
            }
            lastReject = RejectInfo(rejectReason, deltaMmol, riseMinDelta, unexpectedDelta, unexpectedMin, inPostMealLockout)
            val preserveBurst = bgAtStreakStart > 0.0 && currentBgMmol > bgAtStreakStart
            val savedStreakStart = bgAtStreakStart
            resetStreak()
            if (preserveBurst) bgAtStreakStart = savedStreakStart
            return
        }

        if (consecutiveRiseReadings >= riseReadingsNeeded) {
            triggerUam(uamMode, currentBgMmol, deltaMmol, currentBgMmol - bgAtStreakStart)
            resetStreak()
        }
    }

    private fun checkStuckHigh(currentBgMmol: Double, deltaMmol: Double, shortAvgDeltaMmol: Double, currentHour: Int, bgWentLow: Boolean, inReboundWindow: Boolean, lastLowTimeMs: Long, currentMealMode: MealMode, inPostMealLockout: Boolean, profileTargetMmol: Double, bgTimestampMs: Long = 0L) {
        // No longer requires a tracked meal to have ended first (lastMealEndedMs != 0L) — P/F is
        // purely BG-pattern-based (stuck + high + flat for N readings) and shouldn't depend on
        // whether a Smart Meal Dialog activation happened to precede it. Real digestion tails
        // happen even after untracked meals, or long after any tracked meal has "expired."
        if (!sp.getBoolean(BooleanKey.ApsSmartInsulinUamProteinFatEnabled.key, BooleanKey.ApsSmartInsulinUamProteinFatEnabled.defaultValue) || currentMealMode != MealMode.FASTING) {
            stuckHighReadings = 0; return
        }
        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val msSincePostRebound = if (reboundExpiredMs > 0L) System.currentTimeMillis() - reboundExpiredMs else Long.MAX_VALUE
        val effectiveBypass = (msSincePostRebound < POST_REBOUND_LOCKOUT_MINS * 60_000L) && lastEpisodeWasSoftLanding
        val blockedByLow = if (effectiveBypass) false
        else (bgWentLow || inReboundWindow || msSinceLow < LOW_BLOCK_MINS * 60_000L || (msSincePostRebound < POST_REBOUND_LOCKOUT_MINS * 60_000L))
        if (blockedByLow) { stuckHighReadings = 0; return }
        val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold)
        if (stuckHighReadings > 0 && currentBgMmol <= profileTargetMmol) {
            stuckHighReadings = 0; return
        }
        lastStuckAvgDelta = shortAvgDeltaMmol; lastStuckBgMmol = currentBgMmol
        val isStuck = currentBgMmol >= triggerThresholdMmol && shortAvgDeltaMmol >= STUCK_DELTA_MIN_MMOL && shortAvgDeltaMmol <= STUCK_DELTA_MAX_MMOL
        if (isStuck) {
            if (bgTimestampMs > 0L && bgTimestampMs == lastStuckBgTimestampMs) return
            lastStuckBgTimestampMs = bgTimestampMs
            stuckHighReadings++
            val needed = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatStuckReadings.key, IntKey.ApsSmartInsulinUamProteinFatStuckReadings.defaultValue)
            if (stuckHighReadings >= needed) {
                triggerUam(MealMode.UAM_PROTEIN_FAT, currentBgMmol, deltaMmol, 0.0)
                stuckHighReadings = 0
            }
        } else {
            stuckHighReadings = 0; lastStuckBgTimestampMs = 0L
        }
    }

    fun debugSummary(): String {
        val riseMinDeltaBase = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta)
        val riseNeeded       = sp.getInt(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.key, IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.defaultValue)
        val u                = unitLabel
        return buildString {
            appendLine("  UAM thresholds:")
            appendLine("    normal: Δ≥${fmtThresh(riseMinDeltaBase)}$u  readings=$riseNeeded")
            appendLine("    dirty : Δ≥${fmtThresh(riseMinDeltaBase * DIRTY_WINDOW_DELTA_MULTIPLIER)}$u")
            lastReject?.let { appendLine("  Last UAM reject${if (it.wasDirtyWindow) " [dirty]" else ""}: ${it.reason}") }
            if (sp.getBoolean(BooleanKey.ApsSmartInsulinUamProteinFatEnabled.key, BooleanKey.ApsSmartInsulinUamProteinFatEnabled.defaultValue)) {
                val stuckNeeded = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatStuckReadings.key, IntKey.ApsSmartInsulinUamProteinFatStuckReadings.defaultValue)
                appendLine("  P/F detection: enabled (flat Δ ${fmtThresh(STUCK_DELTA_MIN_MMOL)}→${fmtThresh(STUCK_DELTA_MAX_MMOL)}$u for $stuckNeeded readings)")
            } else appendLine("  P/F detection: disabled")
        }.trimEnd()
    }

    fun statusString(): String? {
        val dirtyTag = if (currentlyInPostMealLockout) "[dirty] " else ""
        val msSincePostRebound   = if (reboundExpiredMs > 0L) System.currentTimeMillis() - reboundExpiredMs else Long.MAX_VALUE
        val inPostReboundLockout = msSincePostRebound < POST_REBOUND_LOCKOUT_MINS * 60_000L
        val effectiveBypass      = inPostReboundLockout && lastEpisodeWasSoftLanding

        val uamLine = when {
            currentlyInMealMode        -> null
            currentlyHighTempTarget    -> "UAM: off (high temp target set)"
            currentlyCgmWarmup         -> "UAM: off (new sensor <24h)"
            currentlyPastNightCutoff   -> "UAM: off (outside hours)"
            inPostReboundLockout && !effectiveBypass &&
                consecutiveRiseReadings == 0 && bgAtStreakStart == 0.0 -> {
                val leftMins = POST_REBOUND_LOCKOUT_MINS - msSincePostRebound / 60_000L
                "UAM: off (post-rebound lockout — ${leftMins}min left)"
            }
            consecutiveRiseReadings > 0 || bgAtStreakStart > 0.0 -> {
                val needed = sp.getInt(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.key, IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.defaultValue)
                val threshNote = if (currentlyInPostMealLockout) " δ≥${fmtThresh(mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta) * DIRTY_WINDOW_DELTA_MULTIPLIER)}" else ""
                val burstThreshold = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold)
                // Show burst breakdown once there's a prior accumulated rise (2+ readings)
                val burstNote = if (burstThreshold > 0.0) {
                    val burstLast = lastBurstDeltaMmol
                    val burstPrev = lastBurstRiseMmol - burstLast
                    val breakdown = if (burstPrev > 0.01) " (${fmtDelta(burstPrev)}, ${fmtDelta(burstLast)})" else ""
                    " | Burst: ${fmtDelta(lastBurstRiseMmol)}/${fmtDelta(burstThreshold)}$unitLabel$breakdown"
                } else ""
                "UAM: ${dirtyTag}watching ($consecutiveRiseReadings/$needed rising$threshNote$burstNote)"
            }
            lastUamMode != null && lastUamTimeMs > 0L &&
                (System.currentTimeMillis() - lastUamTimeMs) < LAST_UAM_DISPLAY_WINDOW_MS -> {
                val cal = Calendar.getInstance().also { it.timeInMillis = lastUamTimeMs }
                "UAM: last ${lastUamMode!!.label} ${"%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}${if (lastUamTriggerCount > 1) " (×$lastUamTriggerCount)" else ""}"
            }
            else -> {
                val needed = sp.getInt(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.key, IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.defaultValue)
                val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold)
                val dirtyNote = if (currentlyInPostMealLockout) " [dirty]" else ""
                "UAM: ${dirtyTag}armed (0/$needed >=${fmtBg(triggerThresholdMmol)}$unitLabel$dirtyNote)"
            }
        }

        val pfLine = when {
            !sp.getBoolean(BooleanKey.ApsSmartInsulinUamProteinFatEnabled.key, BooleanKey.ApsSmartInsulinUamProteinFatEnabled.defaultValue) -> null
            currentlyHighTempTarget  -> "P/F: off (high temp target set)"
            currentlyCgmWarmup       -> "P/F: off (new sensor <24h)"
            currentlyPastNightCutoff -> "P/F: off (outside hours)"
            inPostReboundLockout && !effectiveBypass -> {
                val leftMins = POST_REBOUND_LOCKOUT_MINS - msSincePostRebound / 60_000L
                "P/F: off (rebound lockout — ${leftMins}min left)"
            }
            currentlyInMealMode      -> "P/F: armed (after meal expires)"
            else -> {
                val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold)
                val stuckNeeded = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatStuckReadings.key, IntKey.ApsSmartInsulinUamProteinFatStuckReadings.defaultValue)
                if (stuckHighReadings > 0)
                    "P/F: $stuckHighReadings/$stuckNeeded stuck ≥${fmtBg(triggerThresholdMmol)}$unitLabel"
                else
                    "P/F: 0/$stuckNeeded below ≥${fmtBg(triggerThresholdMmol)}$unitLabel"
            }
        }

        return listOfNotNull(uamLine, pfLine).joinToString(" | ").ifEmpty { null }
    }

    private fun resetStreak() { consecutiveRiseReadings = 0; bgAtStreakStart = 0.0; lastCountedBgTimestampMs = 0L; lastRiseBgMmol = 0.0; lastBurstRiseMmol = 0.0; lastBurstDeltaMmol = 0.0 }

    var justFiredThisCycle: MealMode? = null
        private set

    private fun triggerUam(mode: MealMode, bgMmol: Double, deltaMmol: Double, totalRise: Double) {
        justFiredThisCycle = mode
        val durationMins = uamDurationMins(mode)
        val now = System.currentTimeMillis()
        lastUamTriggerCount = if (lastUamMode == mode && now - lastUamTimeMs < 4 * 60 * 60 * 1000L) lastUamTriggerCount + 1 else 1
        lastUamMode = mode; lastUamTimeMs = now
        mealOverrideManager.activateOverride(mode = mode, doseU = null, carbsG = 0, modeWindowMs = durationMins * 60_000L, preBolus2U = 0.0, preBolus2DelayMs = 0L, preBolus3U = 0.0, preBolus3DelayMs = 0L)
    }

    // Afternoon/Dinner get minute-precision boundaries (e.g. Afternoon ending 17:30, Dinner
    // taking over from there) — the other windows stay whole-hour only (minuteKey = null -> :00).
    private fun boundaryMins(hourKey: IntKey, minuteKey: IntKey?): Int {
        val hour   = sp.getInt(hourKey.key, hourKey.defaultValue)
        val minute = minuteKey?.let { sp.getInt(it.key, it.defaultValue) } ?: 0
        return hour * 60 + minute
    }

    private fun resolveUamMode(currentHour: Int, currentMinute: Int): MealMode? {
        val nowMins = currentHour * 60 + currentMinute
        val candidates = listOf(
            Triple(MealMode.UAM_BREAKFAST, boundaryMins(IntKey.ApsSmartInsulinUamBreakfastStartHour, null), boundaryMins(IntKey.ApsSmartInsulinUamBreakfastEndHour, null)),
            Triple(MealMode.UAM_LUNCH, boundaryMins(IntKey.ApsSmartInsulinUamLunchStartHour, null), boundaryMins(IntKey.ApsSmartInsulinUamLunchEndHour, null)),
            Triple(MealMode.UAM_DINNER, boundaryMins(IntKey.ApsSmartInsulinUamDinnerStartHour, IntKey.ApsSmartInsulinUamDinnerStartMinute), boundaryMins(IntKey.ApsSmartInsulinUamDinnerEndHour, IntKey.ApsSmartInsulinUamDinnerEndMinute)),
            Triple(MealMode.UAM_SNACK, boundaryMins(IntKey.ApsSmartInsulinUamSnackStartHour, null), boundaryMins(IntKey.ApsSmartInsulinUamSnackEndHour, null)),
            Triple(MealMode.UAM_AFTERNOON, boundaryMins(IntKey.ApsSmartInsulinUamAfternoonStartHour, IntKey.ApsSmartInsulinUamAfternoonStartMinute), boundaryMins(IntKey.ApsSmartInsulinUamAfternoonEndHour, IntKey.ApsSmartInsulinUamAfternoonEndMinute)),
        )
        return candidates.firstOrNull { (mode, startMins, endMins) -> uamModeEnabled(mode) && minsInWindow(nowMins, startMins, endMins) }?.first
    }

    private fun minsInWindow(nowMins: Int, startMins: Int, endMins: Int): Boolean =
        if (startMins <= endMins) nowMins in startMins until endMins else nowMins >= startMins || nowMins < endMins

    private fun uamModeEnabled(mode: MealMode): Boolean = when (mode) {
        MealMode.UAM_BREAKFAST -> sp.getBoolean(BooleanKey.ApsSmartInsulinUamBreakfastEnabled.key, BooleanKey.ApsSmartInsulinUamBreakfastEnabled.defaultValue)
        MealMode.UAM_LUNCH     -> sp.getBoolean(BooleanKey.ApsSmartInsulinUamLunchEnabled.key, BooleanKey.ApsSmartInsulinUamLunchEnabled.defaultValue)
        MealMode.UAM_DINNER    -> sp.getBoolean(BooleanKey.ApsSmartInsulinUamDinnerEnabled.key, BooleanKey.ApsSmartInsulinUamDinnerEnabled.defaultValue)
        MealMode.UAM_SNACK     -> sp.getBoolean(BooleanKey.ApsSmartInsulinUamSnackEnabled.key, BooleanKey.ApsSmartInsulinUamSnackEnabled.defaultValue)
        MealMode.UAM_AFTERNOON -> sp.getBoolean(BooleanKey.ApsSmartInsulinUamAfternoonEnabled.key, BooleanKey.ApsSmartInsulinUamAfternoonEnabled.defaultValue)
        else                   -> false
    }

    private fun uamDurationMins(mode: MealMode): Long = when (mode) {
        MealMode.UAM_BREAKFAST -> sp.getInt(IntKey.ApsSmartInsulinUamBreakfastDurationMins.key, IntKey.ApsSmartInsulinUamBreakfastDurationMins.defaultValue).toLong()
        MealMode.UAM_LUNCH     -> sp.getInt(IntKey.ApsSmartInsulinUamLunchDurationMins.key, IntKey.ApsSmartInsulinUamLunchDurationMins.defaultValue).toLong()
        MealMode.UAM_DINNER    -> sp.getInt(IntKey.ApsSmartInsulinUamDinnerDurationMins.key, IntKey.ApsSmartInsulinUamDinnerDurationMins.defaultValue).toLong()
        MealMode.UAM_SNACK     -> sp.getInt(IntKey.ApsSmartInsulinUamSnackDurationMins.key, IntKey.ApsSmartInsulinUamSnackDurationMins.defaultValue).toLong()
        MealMode.UAM_AFTERNOON -> sp.getInt(IntKey.ApsSmartInsulinUamAfternoonDurationMins.key, IntKey.ApsSmartInsulinUamAfternoonDurationMins.defaultValue).toLong()
        MealMode.UAM_PROTEIN_FAT -> sp.getInt(IntKey.ApsSmartInsulinUamProteinFatDurationMins.key, IntKey.ApsSmartInsulinUamProteinFatDurationMins.defaultValue).toLong()
        else                   -> 30L
    }

    /**
     * Called each loop cycle when a UAM mode is currently active.
     * Cancels the active UAM override if BG has returned to target or below.
     *
     * @param currentBgMmol   latest CGM reading in mmol/L
     * @param profileTargetMmol profile target in mmol/L
     * @return true if UAM was cancelled this cycle
     */
    fun checkAutoCancelUam(currentBgMmol: Double, profileTargetMmol: Double): Boolean {
        if (!sp.getBoolean(BooleanKey.ApsSmartInsulinUamEnabled.key, BooleanKey.ApsSmartInsulinUamEnabled.defaultValue)) return false
        if (currentBgMmol <= profileTargetMmol + 0.01) {
            aapsLogger.debug(LTag.APS, "UAM: auto-cancel — BG ${fmtBg(currentBgMmol)} returned to/below target ${fmtBg(profileTargetMmol)}")
            mealOverrideManager.cancelOverride()
            return true
        }
        return false
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