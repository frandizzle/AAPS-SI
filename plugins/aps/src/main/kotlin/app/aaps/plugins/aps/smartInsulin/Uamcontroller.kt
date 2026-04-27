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
 * - BG must be above trigger threshold (default 6.0 mmol)
 * - consecutive readings with:
 * - delta >= riseMinDelta
 * - shortAvgDelta >= riseMinDelta * 0.75  (filters single-reading noise)
 * - Total BG rise since streak start >= rise threshold (filters wobble streaks)
 * → auto-activate the matching UAM mode via [MealOverrideManager.activateOverride]
 *
 * ## Hard cutoff
 * All UAM modes are disabled at night cutoff hour (default 23:00). STFT handles
 * overnight sticky BG instead.
 *
 * ## Safety blocks
 * UAM will not fire if:
 * - A real low occurred recently (within [LOW_BLOCK_MINS])
 * - The rebound window is active
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
    private var lastMealEndedMs            = 0L     // timestamp of last meal/UAM mode expiry — P/F only arms after this
    private var lastStuckAvgDelta          = 0.0   // last shortAvgDelta seen by checkStuckHigh
    private var lastStuckBgMmol            = 0.0   // last BG seen by checkStuckHigh
    // Burst detection uses a simple 2-reading sliding window, independent of streak state.
    // Rationale: the streak counter can restart after any single-cycle wobble or threshold miss,
    // which means bgAtStreakStart re-anchors too late on real meal spikes — the visual rise can
    // already be 1.5+ mmol but streak-anchored burst never fires because currentBg - bgAtStreakStart
    // stays sub-threshold. Whenever UAM is armed and the last two readings' rise exceeds the user's
    // burst threshold, fire burst — that's the whole point of burst.
    private var burstPrevBgMmol            = 0.0
    private var burstPrevBgTimestampMs     = 0L
    private var burstAnchorBgMmol          = 0.0
    // Rebound window transition tracking — prevents post-low counter-regulatory hyperglycemia
    // from triggering UAM/P/F in the first hour after recovery ends. When BG crashes low,
    // the liver releases glucagon → BG rebounds high → can get stuck above target for up to
    // an hour. Without this lockout, that physiological rebound looks like an unannounced
    // meal or P/F tail, causing spurious correction boluses that drive another low.
    // NOT persisted across app restarts — worst case reverts to pre-fix behaviour (rare
    // and only if app restarts in the exact lockout window).
    private var wasInReboundWindow         = false
    private var reboundExpiredMs           = 0L
    private var lastEpisodeWasSoftLanding  = false

    init {
        // Restore lastMealEndedMs from SharedPreferences so P/F gate survives app restarts.
        // Rely solely on the 10h expiry window; allow crossing midnight so late-night
        // protein/fat rises are caught.
        val saved = preferences.get(StringKey.ApsSmartInsulinLastMealEndedMs).toLongOrNull() ?: 0L
        if (saved > 0L) {
            val isExpired = (System.currentTimeMillis() - saved) > 10 * 60 * 60 * 1000L
            if (!isExpired) {
                aapsLogger.debug(LTag.APS, "UAM: restored lastMealEndedMs from prefs — P/F armed")
                lastMealEndedMs = saved
            } else {
                aapsLogger.debug(LTag.APS, "UAM: discarding stale lastMealEndedMs (>10h old) — P/F disarmed")
                preferences.put(StringKey.ApsSmartInsulinLastMealEndedMs, "0")
                lastMealEndedMs = 0L
            }
        }
    }

    // Last reject tracking for debug display
    private data class RejectInfo(val reason: String, val deltaActual: Double, val deltaNeeded: Double,
                                  val unexpectedActual: Double, val unexpectedNeeded: Double,
                                  val wasDirtyWindow: Boolean)
    private var lastReject: RejectInfo? = null  // consecutive readings with BG above threshold and flat delta

    companion object {
        // How long after a real low to block UAM
        private const val LOW_BLOCK_MINS            = 90L
        // How long to block UAM/P/F after the rebound recovery window itself expires.
        // Covers cases where the rebound window extends past LOW_BLOCK_MINS (rollercoaster
        // extension, or user-configured longer window) — counter-regulatory hyperglycemia
        // can keep BG stuck above target for up to ~60 min post-rebound, and P/F would
        // misread that as a fat/protein tail. Blocks both regular UAM and P/F triggering.
        private const val POST_REBOUND_LOCKOUT_MINS = 60L
        // Maximum CGM gap allowed for the 2-reading burst check to fire. Dexcom/Libre
        // deliver a reading every 5 min; allow 6.5 min to tolerate normal jitter and
        // the occasional late reading. Anything beyond this means readings were missed
        // (UAM disabled, sensor stopped, app killed) and the previous BG is stale —
        // firing burst on a multi-hour "rise" would be dangerous (e.g. overnight spike
        // from 5.0 → 8.0 read as a single-cycle +3.0 jump).
        private const val BURST_MAX_CYCLE_GAP_MS    = 390_000L   // 6.5 min
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
    }

    // ── Unit conversion helpers ───────────────────────────────────────────────
    private fun mgdlPrefMmol(key: UnitDoubleKey): Double = sp.getDouble(key.key, key.defaultValue) / 18.0
    private fun rawMgdl(key: UnitDoubleKey, mmolThreshold: Double = 20.0): Double {
        val raw = sp.getDouble(key.key, key.defaultValue)
        return if (raw < mmolThreshold) raw * 18.0 else raw
    }
    private fun unitPrefMmol(key: UnitDoubleKey): Double = rawMgdl(key) / 18.0
    private fun isfPrefMgdl(key: UnitDoubleKey): Double  = sp.getDouble(key.key, key.defaultValue)

    // ── Unit-aware display helpers ────────────────────────────────────────────
    private val isMmol: Boolean get() =
        profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL

    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"

    private fun fmtBg(mmol: Double): String =
        if (isMmol) String.format("%.1f", mmol)
        else        String.format("%.0f", mmol * 18.0)

    private fun fmtDelta(mmol: Double): String =
        if (isMmol) String.format("%+.2f", mmol)
        else        String.format("%+.1f", mmol * 18.0)

    private fun fmtThresh(mmol: Double): String =
        if (isMmol) String.format("%.2f", mmol)
        else        String.format("%.1f", mmol * 18.0)

    // ── Public API ────────────────────────────────────────────────────────────

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
        currentlyInPostMealLockout = inPostMealLockout
        currentlyCgmWarmup         = cgmInWarmup && preferences.get(BooleanKey.ApsSmartInsulinUamCgmWarmupBlock)
        currentlyHighTempTarget    = highTempTarget
        justFiredThisCycle         = null  // reset each cycle

        // ── ROBUST EXPIRY: Reset lastMealEndedMs if >10h old.
        // Midnight reset removed — allowing P/F to cross into the next day.
        if (lastMealEndedMs > 0L) {
            val isExpired = (System.currentTimeMillis() - lastMealEndedMs) > 10 * 60 * 60 * 1000L
            if (isExpired) {
                aapsLogger.debug(LTag.APS, "UAM: last meal >10h old — resetting lastMealEndedMs, P/F disarmed")
                lastMealEndedMs = 0L
                preferences.put(StringKey.ApsSmartInsulinLastMealEndedMs, "0")
            }
        }

        val wasMealMode = currentlyInMealMode
        currentlyInMealMode = currentMealMode != MealMode.FASTING
        // Track when meal mode expires so P/F knows a meal has happened
        if (wasMealMode && !currentlyInMealMode) {
            lastMealEndedMs = System.currentTimeMillis()
            preferences.put(StringKey.ApsSmartInsulinLastMealEndedMs, lastMealEndedMs.toString())
            aapsLogger.debug(LTag.APS, "UAM: meal mode ended — P/F armed for fat/protein tail (persisted)")
        }

        // Track when rebound recovery window expires — starts a post-rebound lockout that
        // blocks UAM/P/F for POST_REBOUND_LOCKOUT_MINS to prevent counter-regulatory
        // hyperglycemia (post-low liver glucagon rebound) from triggering false corrections.
        if (wasInReboundWindow && !inReboundWindow) {
            reboundExpiredMs = System.currentTimeMillis()
            aapsLogger.debug(LTag.APS,
                             "UAM: rebound window expired — P/F and UAM locked out for ${POST_REBOUND_LOCKOUT_MINS}min " +
                                 "to avoid counter-regulatory rebound triggering false corrections")
        }
        wasInReboundWindow = inReboundWindow

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

            // --- NEW: Automatically wipe ghost meals overnight ---
            if (lastMealEndedMs > 0L) {
                aapsLogger.debug(LTag.APS, "UAM: outside active window — resetting lastMealEndedMs, P/F disarmed")
                lastMealEndedMs = 0L
                preferences.put(StringKey.ApsSmartInsulinLastMealEndedMs, "0")
            }
            return
        }

        currentlyPastNightCutoff = false
        if (softLandingBypass && (bgWentLow || inReboundWindow)) {
            aapsLogger.debug(LTag.APS, "UAM: soft landing bypass active — detection allowed during rebound")
        }

        // ── Safety block: recent low / rebound / post-rebound lockout ────────
        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val lowBlockMs = LOW_BLOCK_MINS * 60_000L
        val msSincePostRebound = if (reboundExpiredMs > 0L) System.currentTimeMillis() - reboundExpiredMs else Long.MAX_VALUE
        val postReboundLockoutMs = POST_REBOUND_LOCKOUT_MINS * 60_000L
        val inPostReboundLockout = msSincePostRebound < postReboundLockoutMs

        // Track soft-landing status throughout the episode (rebound -> lockout).
        // Captures from the plugin's softLandingBypass flag during the active rebound.
        if (bgWentLow || inReboundWindow) {
            lastEpisodeWasSoftLanding = softLandingBypass
        } else if (!inPostReboundLockout) {
            // Once the lockout expires, neutralise the flag for the next episode.
            lastEpisodeWasSoftLanding = false
        }

        val effectiveBypass = softLandingBypass || (inPostReboundLockout && lastEpisodeWasSoftLanding)
        val blockedByLow = (bgWentLow || inReboundWindow || msSinceLow < lowBlockMs || inPostReboundLockout) && !effectiveBypass

        if (blockedByLow) {
            if (consecutiveRiseReadings > 0) {
                val reason = when {
                    inReboundWindow         -> "rebound window active"
                    bgWentLow               -> "recent low (bgWentLow)"
                    inPostReboundLockout    -> "post-rebound lockout ${msSincePostRebound / 60_000}min < ${POST_REBOUND_LOCKOUT_MINS}min"
                    else                    -> "low ${msSinceLow / 60_000}min ago < ${LOW_BLOCK_MINS}min block"
                }
                aapsLogger.debug(LTag.APS, "UAM: blocked — $reason, streak reset")
                resetStreak()
            }
            return
        }

        // ── Protein/Fat stuck-high detection ──────────────────────────────────
        checkStuckHigh(currentBgMmol, deltaMmol, shortAvgDeltaMmol, currentHour, bgWentLow, inReboundWindow, lastLowTimeMs, currentMealMode, inPostMealLockout, profileTargetMmol, bgTimestampMs)

        // ── Independent Burst Accumulation ────────────────────────────────────
        // We track the rise even when below threshold or outside windows so that
        // we can trigger the moment we cross them.
        val timeSinceLastBgMs  = bgTimestampMs - burstPrevBgTimestampMs
        val freshCycle         = timeSinceLastBgMs in 1L..BURST_MAX_CYCLE_GAP_MS

        if (burstPrevBgMmol == 0.0 || !freshCycle || currentBgMmol < burstPrevBgMmol - 0.01) {
            burstAnchorBgMmol = currentBgMmol
        } else {
            // Rising! If we don't have an anchor yet, seed it from the previous reading.
            if (burstAnchorBgMmol == 0.0) {
                burstAnchorBgMmol = burstPrevBgMmol
            }
        }

        lastBurstRiseMmol = if (burstAnchorBgMmol > 0.0 && currentBgMmol > burstAnchorBgMmol) currentBgMmol - burstAnchorBgMmol else 0.0
        // Track the most recent cycle's contribution — used in statusString() breakdown "(prev, last)".
        // Use actual reading-to-reading difference (not smoothed deltaMmol) so the two values
        // always sum exactly to lastBurstRiseMmol.
        lastBurstDeltaMmol = if (lastBurstRiseMmol > 0.0 && freshCycle && currentBgMmol > burstPrevBgMmol)
            currentBgMmol - burstPrevBgMmol else 0.0

        // ── Resolve time window ───────────────────────────────────────────────
        val uamMode = resolveUamMode(currentHour) ?: run {
            lastReject = RejectInfo("no meal window active at hour $currentHour", 0.0, 0.0, 0.0, 0.0, inPostMealLockout)
            burstPrevBgMmol = currentBgMmol
            burstPrevBgTimestampMs = bgTimestampMs
            resetStreak(); return
        }

        if (consecutiveRiseReadings > 0 && lastResolvedMode != null && lastResolvedMode != uamMode) {
            aapsLogger.debug(LTag.APS, "UAM: window changed ${lastResolvedMode!!.label}→${uamMode.label}, streak reset")
            resetStreak()
        }
        lastResolvedMode = uamMode

        // ── BG above trigger threshold ────────────────────────────────────────
        val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold)

        val aboveThreshold = currentBgMmol >= triggerThresholdMmol ||
            (consecutiveRiseReadings > 0 &&
                shortAvgDeltaMmol > 0.0 &&
                currentBgMmol >= triggerThresholdMmol - WOBBLE_TOLERANCE_MMOL)

        if (!aboveThreshold) {
            resetStreak()
            burstPrevBgMmol = currentBgMmol
            burstPrevBgTimestampMs = bgTimestampMs
            return
        }

        // ── Independent burst trigger ─────────────────────────────────────────
        // Simple rule: cumulative rise ≥ user's burst threshold setting → fire.
        // Independent of streak counter / wobble / delta thresholds.
        // SAFETY GATE: The CGM gap must be a fresh 5-min cycle (allow up to 6.5 min for
        // CGM jitter). This prevents stale-data misfires.
        val burstThresholdPref = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold)

        if (burstThresholdPref > 0.0 && burstAnchorBgMmol > 0.0 && freshCycle) {
            if (lastBurstRiseMmol >= burstThresholdPref - 0.01) {
                aapsLogger.debug(LTag.APS,
                                 "UAM: BURST trigger (multi-reading) — rise=${fmtDelta(lastBurstRiseMmol)}$unitLabel " +
                                     ">= threshold=${fmtBg(burstThresholdPref)}$unitLabel " +
                                     "(anchor=${fmtBg(burstAnchorBgMmol)} → current=${fmtBg(currentBgMmol)}) " +
                                     "firing ${uamMode.label}")
                triggerUam(uamMode, currentBgMmol, deltaMmol, lastBurstRiseMmol)
                resetStreak()
                // Clear burst window so we don't immediately re-fire on the next reading
                burstPrevBgMmol = 0.0
                burstPrevBgTimestampMs = 0L
                burstAnchorBgMmol = 0.0
                lastBurstRiseMmol = 0.0
                lastBurstDeltaMmol = 0.0
                return
            }
        }
        else if (burstPrevBgMmol > 0.0 && !freshCycle && timeSinceLastBgMs > BURST_MAX_CYCLE_GAP_MS) {
            // Log stale-data rejection at debug so you can see it if burst "should have" fired post-gap
            aapsLogger.debug(LTag.APS,
                             "UAM: burst check skipped — CGM gap ${timeSinceLastBgMs / 1000}s > " +
                                 "${BURST_MAX_CYCLE_GAP_MS / 1000}s (stale data, window re-seeded)")
        }
        // Slide the burst window forward regardless of streak logic outcome
        burstPrevBgMmol = currentBgMmol
        burstPrevBgTimestampMs = bgTimestampMs

        // ── Rise confirmation: delta, shortAvgDelta, AND BGI-gap ─────────────
        val riseMinDeltaBase   = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta)
        val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)

        val dirtyMultiplier    = if (inPostMealLockout) DIRTY_WINDOW_DELTA_MULTIPLIER else 1.0
        val unexpectedMultiplier = if (inPostMealLockout) DIRTY_WINDOW_UNEXPECTED_MULT else 1.0
        val riseMinDelta       = riseMinDeltaBase * dirtyMultiplier
        val shortAvgThreshold  = riseMinDelta * SHORT_AVG_DELTA_FRACTION
        val unexpectedMin      = riseMinDeltaBase * SHORT_AVG_DELTA_FRACTION * unexpectedMultiplier

        val unexpectedDelta = deltaMmol - bgiMmol
        val unexpectedShort = shortAvgDeltaMmol - bgiMmol

        val wobbleEnabled = preferences.get(BooleanKey.ApsSmartInsulinUamWobbleTolerance)
        val trendConfirmedByAvg = wobbleEnabled && shortAvgDeltaMmol >= riseMinDelta
        val deltaMin = if (trendConfirmedByAvg) riseMinDelta * 0.5 else riseMinDelta
        val risingNow = deltaMmol >= deltaMin &&
            shortAvgDeltaMmol >= shortAvgThreshold &&
            unexpectedDelta >= unexpectedMin &&
            unexpectedShort >= unexpectedMin * SHORT_AVG_DELTA_FRACTION

        if (risingNow) {
            val isDuplicateReading = bgTimestampMs > 0L && bgTimestampMs == lastCountedBgTimestampMs
            if (isDuplicateReading) {
                aapsLogger.debug(LTag.APS, "UAM: same CGM reading (${bgTimestampMs}), skipping streak increment")
            } else {
                if (consecutiveRiseReadings == 0 && bgAtStreakStart == 0.0) {
                    bgAtStreakStart = currentBgMmol
                } else if (consecutiveRiseReadings == 0 && bgAtStreakStart != 0.0) {
                    aapsLogger.debug(LTag.APS, "UAM: resuming preserved burst (start=${fmtBg(bgAtStreakStart)})")
                }
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

        // ── Normal trigger — consecutive readings met ─────────────────────────
        val totalRise = currentBgMmol - bgAtStreakStart
        if (consecutiveRiseReadings >= riseReadingsNeeded) {
            triggerUam(uamMode, currentBgMmol, deltaMmol, totalRise)
            resetStreak()
        }
    }

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
        if (!preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled)) {
            stuckHighReadings = 0
            return
        }

        if (currentMealMode != MealMode.FASTING) {
            if (stuckHighReadings > 0) stuckHighReadings = 0
            return
        }

        // --- NEW GATE: Block P/F if no meal has finished today yet ---
        if (lastMealEndedMs == 0L) {
            if (stuckHighReadings > 0) stuckHighReadings = 0
            return
        }

        val msSinceLow = if (lastLowTimeMs > 0L) System.currentTimeMillis() - lastLowTimeMs else Long.MAX_VALUE
        val lowBlockMs = LOW_BLOCK_MINS * 60_000L
        val msSincePostRebound = if (reboundExpiredMs > 0L) System.currentTimeMillis() - reboundExpiredMs else Long.MAX_VALUE
        val postReboundLockoutMs = POST_REBOUND_LOCKOUT_MINS * 60_000L
        val inPostReboundLockout = msSincePostRebound < postReboundLockoutMs
        val effectiveBypass = inPostReboundLockout && lastEpisodeWasSoftLanding

        if ((bgWentLow || inReboundWindow || msSinceLow < lowBlockMs || inPostReboundLockout) && !effectiveBypass) {
            if (stuckHighReadings > 0) {
                val reason = when {
                    inReboundWindow                             -> "rebound window active"
                    bgWentLow                                   -> "recent low"
                    msSincePostRebound < postReboundLockoutMs   -> "post-rebound lockout ${msSincePostRebound / 60_000}min < ${POST_REBOUND_LOCKOUT_MINS}min"
                    else                                        -> "low ${msSinceLow / 60_000}min ago < ${LOW_BLOCK_MINS}min"
                }
                aapsLogger.debug(LTag.APS, "UAM_PROTEIN_FAT: streak reset — $reason")
            }
            stuckHighReadings = 0
            return
        }

        val triggerThresholdMmol = unitPrefMmol(UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold)

        if (stuckHighReadings > 0 && currentBgMmol <= profileTargetMmol) {
            aapsLogger.debug(LTag.APS,
                             "UAM_PROTEIN_FAT: streak reset — BG ${fmtBg(currentBgMmol)}$unitLabel " +
                                 "back at/below target ${fmtBg(profileTargetMmol)}$unitLabel")
            stuckHighReadings = 0
            return
        }

        lastStuckAvgDelta = shortAvgDeltaMmol
        lastStuckBgMmol   = currentBgMmol

        val isStuck = currentBgMmol >= triggerThresholdMmol &&
            shortAvgDeltaMmol >= STUCK_DELTA_MIN_MMOL &&
            shortAvgDeltaMmol <= STUCK_DELTA_MAX_MMOL

        if (!isStuck) {
            aapsLogger.debug(LTag.APS,
                             "UAM_PROTEIN_FAT: not stuck — " +
                                 "bg=${fmtBg(currentBgMmol)}(need>=${fmtBg(triggerThresholdMmol)}) " +
                                 "avg=${fmtDelta(shortAvgDeltaMmol)}(need ${fmtThresh(STUCK_DELTA_MIN_MMOL)}→${fmtThresh(STUCK_DELTA_MAX_MMOL)})")
        }

        if (isStuck) {
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

    fun debugSummary(): String {
        val riseMinDeltaBase = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta)
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
                    lastMealEndedMs == 0L ->
                        appendLine("  P/F stuck: off (waiting for first meal today)")
                    reboundExpiredMs > 0L && (System.currentTimeMillis() - reboundExpiredMs) < POST_REBOUND_LOCKOUT_MINS * 60_000L -> {
                        val leftMins = POST_REBOUND_LOCKOUT_MINS - (System.currentTimeMillis() - reboundExpiredMs) / 60_000L
                        appendLine("  P/F stuck: off (post-rebound lockout — ${leftMins}min left)")
                    }
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
                val riseReadingsNeeded = preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)
                val threshNote = if (currentlyInPostMealLockout) " δ≥${fmtThresh(mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta) * DIRTY_WINDOW_DELTA_MULTIPLIER)}" else ""
                val burstThreshold = mgdlPrefMmol(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold)
                // Show breakdown only once there's a prior accumulated rise (2+ readings into burst window)
                val burstNote = if (burstThreshold > 0.0) {
                    val burstLast = lastBurstDeltaMmol
                    val burstPrev = lastBurstRiseMmol - burstLast
                    val breakdown = if (burstPrev > 0.01) " (${fmtDelta(burstPrev)}, ${fmtDelta(burstLast)})" else ""
                    " | Burst: ${fmtDelta(lastBurstRiseMmol)}/${fmtDelta(burstThreshold)}$unitLabel$breakdown"
                } else ""
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

        val pfLine = when {
            !preferences.get(BooleanKey.ApsSmartInsulinUamProteinFatEnabled) -> null
            currentlyHighTempTarget  -> "P/F: off (high temp target set)"
            currentlyCgmWarmup       -> "P/F: off (new sensor <24h)"
            currentlyPastNightCutoff -> "P/F: off (outside hours)"
            inPostReboundLockout && !effectiveBypass -> {
                val leftMins = POST_REBOUND_LOCKOUT_MINS - msSincePostRebound / 60_000L
                "P/F: off (rebound lockout — ${leftMins}min left)"
            }
            currentlyInMealMode      -> "P/F: armed (after meal expires)"
            lastMealEndedMs == 0L    -> "P/F: waiting for first meal today"
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
        lastBurstRiseMmol         = 0.0
        lastBurstDeltaMmol        = 0.0
    }

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
        MealMode.UAM_PROTEIN_FAT  -> false
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