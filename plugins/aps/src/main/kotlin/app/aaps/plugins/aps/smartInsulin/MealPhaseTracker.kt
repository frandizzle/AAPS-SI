package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * MealPhaseTracker
 *
 * Detects and tracks the three physiological phases of a meal response:
 *
 *   CARB      — rapid BG rise from carbohydrate absorption. High positive delta.
 *               Minimum duration: 40 min (your config choice).
 *
 *   PROTEIN_FAT — sustained plateau or slow rise from protein/fat digestion.
 *               Delta slowing, BG elevated but stable. Minimum: 60 min.
 *
 *   TAIL      — BG returning toward target. Delta turning negative.
 *               Minimum: 30 min. This is the crash-risk window.
 *
 * Phase transitions are driven purely by BG delta shape and elapsed time —
 * no COB dependency. Works for both manual meal mode selection and UAM detection.
 *
 * This class is DETECTION ONLY — no dosing influence yet. Output is logged
 * and stored in [lastPhaseDebug] for display and validation. Once phase
 * detection is validated on real meal data, [MealPhaseProfileLearner] will
 * consume the completed session data to adjust ISF and SMB fraction per phase.
 *
 * Session lifecycle:
 *   - Starts when mealMode transitions from FASTING to any meal/UAM mode
 *   - Invalidated (not learned from) if mode returns to FASTING before TAIL completes
 *   - Flagged (learned from with annotation) if a manual bolus occurs during session
 *   - Completes when BG returns within target band during TAIL phase
 */
@Singleton
class MealPhaseTracker @Inject constructor(
    private val aapsLogger:  AAPSLogger,
    private val preferences: Preferences
) {

    // ── Phase definition ──────────────────────────────────────────────────────

    enum class MealPhase {
        CARB,           // rapid carb absorption
        PROTEIN_FAT,    // protein/fat plateau
        TAIL            // returning to target — crash risk window
    }

    // ── Minimum phase durations (your specified values) ───────────────────────

    private val CARB_MIN_MS        = 40 * 60_000L   // 40 min
    private val PF_MIN_MS          = 60 * 60_000L   // 60 min
    private val TAIL_MIN_MS        = 30 * 60_000L   // 30 min

    // ── Phase transition thresholds ───────────────────────────────────────────

    // CARB → P/F: delta must slow to below this (mmol/L per 5 min reading)
    private val CARB_EXIT_DELTA_MMOL = 0.15

    // P/F → TAIL: delta must turn negative by at least this much
    private val PF_EXIT_DELTA_MMOL   = -0.10

    // TAIL complete: BG within this many mmol of target
    private val TAIL_COMPLETE_MMOL   = 0.5

    // Number of consecutive readings that must agree before a phase transition fires
    // Prevents sensor noise causing premature transitions
    private val TRANSITION_CONFIRM_READINGS = 3

    // ── Session state ─────────────────────────────────────────────────────────

    var currentPhase: MealPhase = MealPhase.CARB
        private set

    var sessionActive: Boolean = false
        private set

    var sessionMode: MealMode = MealMode.FASTING
        private set

    var sessionStartMs: Long = 0L
        private set
    private var phaseStartMs        = 0L
    private var lastMealMode        = MealMode.FASTING

    // Phase durations accumulated this session
    private var carbPhaseDurationMs = 0L
    private var pfPhaseDurationMs   = 0L

    // Delta history — smoothed short-avg delta readings (mmol/L per 5-min interval)
    // Kept as a sliding window of the last 12 readings (~60 min)
    private val deltaHistory = ArrayDeque<Pair<Long, Double>>(12)

    // Transition confirmation counter — must see N consistent readings before transitioning
    private var transitionCandidateCount = 0
    private var transitionCandidatePhase: MealPhase? = null

    // Session outcome tracking
    private var carbPhasePeakBgMmol   = 0.0
    private var pfPhasePeakBgMmol     = 0.0
    private var tailPhaseNadirBgMmol  = Double.MAX_VALUE
    private var manualBolusDetected   = false
    private var manualBolusU          = 0.0

    // Per-phase low flags — low in any phase = unambiguous over-delivery signal
    // Gate: carb-phase low only counted after CARB_LOW_GATE_MS to avoid blaming
    // pre-meal IOB for an early dip that wasn't caused by meal-mode SMBs.
    private var carbPhaseWentLow      = false
    private var pfPhaseWentLow        = false
    private var tailPhaseWentLow      = false

    // P/F phase high — sustained elevated BG deep into plateau = under-delivery signal
    // Requires PFHIGH_CONFIRM_READINGS consecutive readings above threshold before flagging.
    // NOT tracked for carb phase (expected to exceed 10 mmol without prebolus).
    private var pfHighConsecutive     = 0
    private var pfPhaseWentHigh       = false

    // Time gates
    private val CARB_LOW_GATE_MS        = 20 * 60_000L  // ignore lows in first 20 min of carb phase
    private val PF_HIGH_THRESHOLD_MMOL  = 10.0           // sustained above this = under-delivery
    private val PFHIGH_CONFIRM_READINGS = 3              // 3 consecutive readings = 15 min sustained

    // IOB-peak fallback for CARB→P/F transition.
    // If delta-based gate hasn't fired by this offset from first bolus delivery, force the handoff.
    // 75 min = typical NovoRapid peak offset. Prevents a perfect prebolus suppressing the signal.
    private val INSULIN_PEAK_OFFSET_MS  = 75 * 60_000L

    // ── IOB / insulin tracking ────────────────────────────────────────────────

    // Epoch ms of the first bolus for this session (PB1 or manual start).
    // Supplied each cycle via onLoopCycle — 0L until first bolus is known.
    private var firstBolusMs: Long = 0L

    // IOB snapshots at phase boundaries — primary learning signal
    private var iobAtCarbExit: Double = 0.0
    private var iobAtPfExit:   Double = 0.0

    // Running AUC above target per phase (mmol·min — (bg - target) × 5 each cycle, floored at 0)
    private var bgAucCarbPhase: Double = 0.0
    private var bgAucPfPhase:   Double = 0.0
    private var bgAucTailPhase: Double = 0.0

    // Prebolus total (PB1 + PB2 + PB3) — set once at session start via notifyPrebolus()
    private var prebolusU: Double = 0.0

    // Total SMBs delivered during this session (beyond prebolus), split by phase
    private var totalSmbsDeliveredU: Double = 0.0
    private var carbPhaseSmbsU:      Double = 0.0
    private var pfPhaseSmbsU:        Double = 0.0
    private var tailPhaseSmbsU:      Double = 0.0

    // ── Display / debug ───────────────────────────────────────────────────────

    var lastPhaseDebug: String = "No session active"
        private set

    /** Per-cycle transition gate debug — shows exactly why CARB→P/F hasn't fired yet */
    var lastTransitionDebug: String = ""
        private set

    // ── Completed session callback ────────────────────────────────────────────
    // Set by MealPhaseProfileLearner once it's ready to consume sessions.
    // Null until learner is wired in — detection still runs, sessions are just logged.
    var onSessionComplete: ((CompletedMealSession) -> Unit)? = null

    // ── Completed session data class ──────────────────────────────────────────

    data class CompletedMealSession(
        val mode:                MealMode,
        val sessionStartMs:      Long,
        val sessionEndMs:        Long,
        val carbPhaseDurationMs: Long,
        val pfPhaseDurationMs:   Long,
        val tailPhaseDurationMs: Long,
        val carbPhasePeakBgMmol: Double,
        val pfPhasePeakBgMmol:   Double,
        val tailNadirBgMmol:     Double,
        val manualBolusDetected: Boolean,
        val manualBolusU:        Double,
        val targetBgMmol:        Double,
        // ── Per-phase outcome flags ───────────────────────────────────────────
        // Low flags: unambiguous — BG crossed below low guard during that phase
        val carbPhaseWentLow:    Boolean,   // SMB fraction too high in carb window
        val pfPhaseWentLow:      Boolean,   // ISF too aggressive during plateau
        val tailPhaseWentLow:    Boolean,   // tail taper too slow / ISF too aggressive
        // High flag: P/F only — sustained (3+ readings) above PF_HIGH_THRESHOLD_MMOL
        // Carb phase high is normal without prebolus — not tracked
        val pfPhaseWentHigh:     Boolean,   // ISF too conservative during plateau
        val isClean:             Boolean,   // true = no confounders, best for learning
        // ── IOB / insulin context ────────────────────────────────────────────
        val iobAtCarbExit:       Double,    // IOB (U) when CARB→P/F fired — high = prebolus still active
        val iobAtPfExit:         Double,    // IOB (U) when P/F→TAIL fired — near 0 = tail is unprotected
        val prebolusU:           Double,    // PB1 + PB2 + PB3 total delivered before/at meal start
        val totalSmbsDeliveredU: Double,    // SMBs fired during session (beyond prebolus)
        val carbPhaseSmbsU:      Double,    // SMBs fired during carb phase specifically
        val pfPhaseSmbsU:        Double,    // SMBs fired during P/F phase
        val tailPhaseSmbsU:      Double,    // SMBs fired during tail phase
        // Total insulin this session: prebolus + all SMBs
        val totalSessionInsulinU: Double,
        // ── BG area-above-target per phase (mmol·min) ─────────────────────────
        // 0 = BG never exceeded target during that phase (perfect prebolus)
        // High = prolonged elevation — learning signal for dose insufficiency
        val bgAucCarbMmolMin:    Double,
        val bgAucPfMmolMin:      Double,
        val bgAucTailMmolMin:    Double
    ) {
        val totalDurationMs: Long get() = sessionEndMs - sessionStartMs
        val totalDurationMins: Double get() = totalDurationMs / 60_000.0
        val carbPhaseMins: Double get() = carbPhaseDurationMs / 60_000.0
        val pfPhaseMins: Double get() = pfPhaseDurationMs / 60_000.0
        val tailPhaseMins: Double get() = tailPhaseDurationMs / 60_000.0
    }

    // ── Main entry point — called every loop cycle ────────────────────────────

    /**
     * Called once per loop cycle with current state.
     *
     * @param now           Current timestamp ms
     * @param mealMode      Current meal mode from MealModeDetector
     * @param bgMmol        Current smoothed BG in mmol/L
     * @param shortAvgDelta Smoothed short-average delta mmol/L per 5-min interval
     *                      (lagging — used for history/stacking detection)
     * @param delta         Instantaneous point-to-point delta mmol/L per 5-min interval
     *                      (current — used alongside shortAvgDelta for transition detection
     *                      to avoid lagging average preventing plateau recognition)
     * @param targetBgMmol  Current dosing target in mmol/L
     * @param lowGuardMmol  Low guard threshold in mmol/L
     */
    fun onLoopCycle(
        now:                Long,
        mealMode:           MealMode,
        bgMmol:             Double,
        shortAvgDelta:      Double,
        delta:              Double = shortAvgDelta,
        targetBgMmol:       Double,
        lowGuardMmol:       Double = 4.0,
        iobU:               Double = 0.0,           // current total IOB in units
        firstBolusEpochMs:  Long   = 0L,            // epoch ms of PB1/first meal bolus (0 = unknown)
        smbsDeliveredU:     Double = 0.0            // SMBs fired THIS cycle (not cumulative)
    ) {
        val wasFasting    = lastMealMode == MealMode.FASTING
        val isFasting     = mealMode    == MealMode.FASTING
        val modeChanged   = mealMode != lastMealMode

        // Track first bolus timestamp — set once, never overwritten
        if (sessionActive && firstBolusMs == 0L && firstBolusEpochMs > 0L)
            firstBolusMs = firstBolusEpochMs

        // Accumulate SMBs delivered this session, split by phase
        if (sessionActive && smbsDeliveredU > 0.0) {
            totalSmbsDeliveredU += smbsDeliveredU
            when (currentPhase) {
                MealPhase.CARB        -> carbPhaseSmbsU += smbsDeliveredU
                MealPhase.PROTEIN_FAT -> pfPhaseSmbsU   += smbsDeliveredU
                MealPhase.TAIL        -> tailPhaseSmbsU  += smbsDeliveredU
            }
        }

        // ── Session start ─────────────────────────────────────────────────────
        if (wasFasting && !isFasting) {
            startSession(now, mealMode, bgMmol)
            lastMealMode = mealMode
            return
        }

        // ── Session invalidation — returned to fasting ────────────────────────
        // Smart check: if BG is already near/at target when mode drops to fasting,
        // this is most likely a successful UAM auto-cancel (meal handled perfectly)
        // or a natural end to the meal. Treat it as a successful early completion
        // rather than invalidating — these are our most valuable clean sessions.
        // Only truly invalidate if BG is still meaningfully elevated.
        //
        // NOTE: natural meal mode *timeouts* with elevated BG are handled BEFORE this
        // path via [onMealModeExpired], which the plugin calls when it detects
        // previousWasRealMeal && mealMode == FASTING. That call either completes
        // (BG near target) or transitions the session to P/F continuation (BG elevated),
        // so sessionActive will already be false or the phase will be P/F by the time
        // we reach here. The elevated-BG invalidation below therefore only fires for
        // genuinely unexpected fasting returns (manual cancel, mode glitch, etc.).
        if (sessionActive && isFasting) {
            val phaseElapsedMs = now - phaseStartMs
            val bgNearTarget   = bgMmol <= targetBgMmol + TAIL_COMPLETE_MMOL

            // If we're already in TAIL phase, fasting is expected — BG is returning
            // to target after the meal. Don't invalidate, let the cycle continue so
            // tailPhaseNadirBgMmol and tailPhaseWentLow are tracked correctly.
            // The tail completion check at the bottom of the cycle will fire when
            // BG reaches target.
            if (currentPhase == MealPhase.TAIL) {
                // fall through — let the rest of onLoopCycle run normally
            } else if (bgNearTarget) {
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseTracker: mode returned to fasting with BG ${"%.1f".format(bgMmol)} near target " +
                                     "— treating as successful completion (UAM auto-cancel or natural end)")
                completeSession(now, phaseElapsedMs, targetBgMmol)
                lastMealMode = mealMode
                return
            } else {
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseTracker: session INVALIDATED — returned to fasting during $currentPhase " +
                                     "with BG ${"%.1f".format(bgMmol)}mmol (${"%+.1f".format(bgMmol - targetBgMmol)} above target) " +
                                     "after ${(now - sessionStartMs) / 60_000}min — manual cancel or unexpected mode drop")
                lastPhaseDebug = "Session cancelled — returned to fasting early with BG still elevated. Not learned from."
                resetSession()
                lastMealMode = mealMode
                return
            }
        }

        // ── No active session ─────────────────────────────────────────────────
        if (!sessionActive) {
            lastMealMode = mealMode
            return
        }

        // ── Mode changed within meal (e.g. UAM_BREAKFAST → UAM_PROTEIN_FAT) ──
        // Keep session running — mode transitions within a meal are expected
        if (modeChanged && !isFasting) {
            aapsLogger.debug(LTag.APS, "MealPhaseTracker: meal mode changed $lastMealMode → $mealMode within session")
        }

        // ── Update delta history ──────────────────────────────────────────────
        deltaHistory.addLast(now to shortAvgDelta)
        while (deltaHistory.size > 12) deltaHistory.removeFirst()

        // ── Auto-detect manual bolus during session ───────────────────────────
        // Manual boluses are flagged via onManualBolus(units) called externally.
        // We can't query the persistence layer here (suspend function) and we can't
        // use iobArray.lastBolusTime because it includes SMBs (would false-flag every session).
        // Future: hook onManualBolus() into the AAPS bolus wizard event bus.

        // ── Meal stacking detection ───────────────────────────────────────────
        // If we're in P/F or TAIL phase and delta surges back to carb-level spike,
        // user ate again. The BG curve is no longer a clean single meal.
        // Threshold is intentionally higher than CARB_EXIT_DELTA_MMOL (0.15) to avoid
        // false triggers from sensor noise or fast-acting corrections.
        // Only fires if we're at least 30 min into P/F or TAIL (early spikes are
        // normal P/F variation, not second meals).
        val avgRecentDeltaForStack = recentAvgDelta(3)
        val MEAL_STACK_DELTA_MMOL  = 0.40  // sustained 0.4 mmol/5min rise = second meal
        if (currentPhase != MealPhase.CARB &&
            avgRecentDeltaForStack > MEAL_STACK_DELTA_MMOL &&
            (now - phaseStartMs) > 30 * 60_000L) {
            aapsLogger.debug(LTag.APS,
                             "MealPhaseTracker: secondary BG spike detected in $currentPhase " +
                                 "(avgDelta=${"%.2f".format(avgRecentDeltaForStack)} mmol/5min > $MEAL_STACK_DELTA_MMOL) " +
                                 "— likely meal stacking, invalidating session")
            lastPhaseDebug = "Session invalidated — secondary spike detected (meal stacking). BG curve no longer clean."
            resetSession()
            lastMealMode = mealMode
            return
        }

        // ── Track per-phase BG extremes and outcome flags ─────────────────────
        val carbPhaseElapsed = if (currentPhase == MealPhase.CARB) now - phaseStartMs else Long.MAX_VALUE

        // Accumulate BG area-above-target per phase every cycle (5-min interval assumed)
        val bgExcess = (bgMmol - targetBgMmol).coerceAtLeast(0.0)
        when (currentPhase) {
            MealPhase.CARB        -> bgAucCarbPhase += bgExcess * 5.0
            MealPhase.PROTEIN_FAT -> bgAucPfPhase   += bgExcess * 5.0
            MealPhase.TAIL        -> bgAucTailPhase  += bgExcess * 5.0
        }

        when (currentPhase) {
            MealPhase.CARB -> {
                if (bgMmol > carbPhasePeakBgMmol) carbPhasePeakBgMmol = bgMmol
                // Low gate: ignore first 20 min — could be pre-meal IOB, not carb-phase SMBs
                if (!carbPhaseWentLow &&
                    carbPhaseElapsed > CARB_LOW_GATE_MS &&
                    bgMmol < lowGuardMmol) {
                    carbPhaseWentLow = true
                    aapsLogger.debug(LTag.APS,
                                     "MealPhaseTracker: LOW during CARB phase at ${carbPhaseElapsed / 60_000}min " +
                                         "bg=${"%.1f".format(bgMmol)} < lowGuard=${"%.1f".format(lowGuardMmol)} " +
                                         "— SMB fraction was too high")
                }
            }
            MealPhase.PROTEIN_FAT -> {
                if (bgMmol > pfPhasePeakBgMmol) pfPhasePeakBgMmol = bgMmol
                // Low detection
                if (!pfPhaseWentLow && bgMmol < lowGuardMmol) {
                    pfPhaseWentLow = true
                    aapsLogger.debug(LTag.APS,
                                     "MealPhaseTracker: LOW during P/F phase " +
                                         "bg=${"%.1f".format(bgMmol)} < lowGuard=${"%.1f".format(lowGuardMmol)} " +
                                         "— ISF too aggressive during plateau")
                }
                // High detection — requires PFHIGH_CONFIRM_READINGS consecutive readings
                // above threshold to confirm sustained elevation (not just a transient spike)
                if (bgMmol > PF_HIGH_THRESHOLD_MMOL) {
                    pfHighConsecutive++
                    if (!pfPhaseWentHigh && pfHighConsecutive >= PFHIGH_CONFIRM_READINGS) {
                        pfPhaseWentHigh = true
                        aapsLogger.debug(LTag.APS,
                                         "MealPhaseTracker: SUSTAINED HIGH during P/F phase " +
                                             "bg=${"%.1f".format(bgMmol)} > ${PF_HIGH_THRESHOLD_MMOL}mmol " +
                                             "for $pfHighConsecutive readings — ISF too conservative during plateau")
                    }
                } else {
                    pfHighConsecutive = 0  // reset on any reading below threshold
                }
            }
            MealPhase.TAIL -> {
                if (bgMmol < tailPhaseNadirBgMmol) tailPhaseNadirBgMmol = bgMmol
                if (!tailPhaseWentLow && bgMmol < lowGuardMmol) {
                    tailPhaseWentLow = true
                    aapsLogger.debug(LTag.APS,
                                     "MealPhaseTracker: LOW during TAIL phase " +
                                         "bg=${"%.1f".format(bgMmol)} < lowGuard=${"%.1f".format(lowGuardMmol)} " +
                                         "— tail ISF needs raising (taper too slow)")
                }
            }
        }

        // ── Phase transition logic ────────────────────────────────────────────
        val phaseElapsedMs = now - phaseStartMs
        val avgRecentDelta = recentAvgDelta(3)   // smoothed avg of last 3 readings (~15 min)
        val avgEarlyDelta  = earlyAvgDelta(3)    // smoothed avg of readings 4-6 back (~30 min ago)

        when (currentPhase) {

            MealPhase.CARB -> {
                // Transition to P/F when EITHER:
                //   A) BG-delta gate: min time elapsed + delta slowing + both avg & instant below threshold
                //   B) IOB-peak fallback: firstBolus + INSULIN_PEAK_OFFSET_MS has passed
                //      This handles perfect prebolus sessions where BG barely rises —
                //      delta never climbs high enough to trigger the delta gate, so we
                //      fall back to insulin pharmacokinetics: at 75min post-bolus, peak
                //      IOB has passed and the carb phase is physiologically over.
                val iobPeakPassed = firstBolusMs > 0L && (now - firstBolusMs) >= INSULIN_PEAK_OFFSET_MS

                if (phaseElapsedMs >= CARB_MIN_MS) {
                    val deltaSlowing    = avgRecentDelta < avgEarlyDelta - 0.05
                    val avgDeltaLow     = avgRecentDelta < CARB_EXIT_DELTA_MMOL
                    val instantDeltaLow = delta < CARB_EXIT_DELTA_MMOL + 0.05
                    val hasEarlyHistory = deltaHistory.size >= 6
                    val deltaGate       = avgDeltaLow && instantDeltaLow && (!hasEarlyHistory || deltaSlowing)

                    val transitionReady = deltaGate || iobPeakPassed

                    if (transitionReady) {
                        // Snapshot IOB at carb exit before handing off
                        iobAtCarbExit = iobU
                        val reason = when {
                            deltaGate && iobPeakPassed -> "delta+iobPeak"
                            iobPeakPassed              -> "iobPeak (prebolus suppressed spike)"
                            else                       -> "delta"
                        }
                        aapsLogger.debug(LTag.APS,
                                         "MealPhaseTracker CARB→P/F via $reason iobAtExit=${"%.2f".format(iobAtCarbExit)}U")
                        confirmTransition(MealPhase.PROTEIN_FAT, now, phaseElapsedMs)
                    } else {
                        resetTransitionCandidate()
                    }
                    aapsLogger.debug(LTag.APS,
                                     "MealPhaseTracker CARB check: elapsed=${phaseElapsedMs/60_000}min " +
                                         "avgRecent=${"%.3f".format(avgRecentDelta)} avgEarly=${"%.3f".format(avgEarlyDelta)} " +
                                         "instant=${"%.3f".format(delta)} " +
                                         "deltaGate=$deltaGate iobPeakPassed=$iobPeakPassed " +
                                         "confirm=$transitionCandidateCount/$TRANSITION_CONFIRM_READINGS")
                    lastTransitionDebug = buildString {
                        append("CARB→P/F gates (${phaseElapsedMs/60_000}min elapsed):\n")
                        append("  avgΔ ${"%.2f".format(avgRecentDelta)} mmol  ")
                        append(if (avgDeltaLow) "✓ <0.15" else "✗ need <0.15")
                        append("\n")
                        append("  instΔ ${"%.2f".format(delta)} mmol  ")
                        append(if (instantDeltaLow) "✓ <0.20" else "✗ need <0.20")
                        append("\n")
                        if (hasEarlyHistory) {
                            append("  slowing ${"%.2f".format(avgRecentDelta)}<${"%.2f".format(avgEarlyDelta)}-0.05  ")
                            append(if (deltaSlowing) "✓" else "✗")
                            append("\n")
                        } else {
                            append("  slowing — skip (history building ${deltaHistory.size}/6)\n")
                        }
                        val bolusAgeMin = if (firstBolusMs > 0L) (now - firstBolusMs) / 60_000 else -1L
                        append("  IOB-peak fallback: ")
                        append(if (iobPeakPassed) "✓ fired (bolus ${bolusAgeMin}min ago)"
                               else if (firstBolusMs > 0L) "✗ ${bolusAgeMin}min / 75min"
                        else "✗ no bolus time known")
                        append("\n")
                        val confirmStr = if (transitionCandidateCount > 0)
                            "  confirm $transitionCandidateCount/$TRANSITION_CONFIRM_READINGS readings"
                        else
                            "  waiting for first confirm"
                        append(confirmStr)
                    }
                }
            }

            MealPhase.PROTEIN_FAT -> {
                // Transition to TAIL when:
                // 1. Minimum P/F time elapsed (60 min)
                // 2. Delta has turned negative — BOTH smoothed avg AND instantaneous
                // 3. BG still above target (natural descent, not a crash)
                // 4. N consecutive readings confirm
                if (phaseElapsedMs >= PF_MIN_MS) {
                    val avgNegative     = avgRecentDelta < PF_EXIT_DELTA_MMOL
                    val instantNegative = delta < PF_EXIT_DELTA_MMOL + 0.05   // slightly lenient for instantaneous
                    val bgAboveTarget   = bgMmol > targetBgMmol
                    val deltaNegative   = avgNegative || instantNegative  // either signal is sufficient — avg lags

                    if (deltaNegative && bgAboveTarget) {
                        iobAtPfExit = iobU  // snapshot IOB at P/F→TAIL handoff
                        aapsLogger.debug(LTag.APS,
                                         "MealPhaseTracker P/F→TAIL iobAtExit=${"%.2f".format(iobAtPfExit)}U")
                        confirmTransition(MealPhase.TAIL, now, phaseElapsedMs)
                    } else {
                        resetTransitionCandidate()
                    }
                }
            }

            MealPhase.TAIL -> {
                // Session complete when:
                // 1. Minimum tail time elapsed (30 min)
                // 2. BG has returned to within TAIL_COMPLETE_MMOL of target OR gone below target
                // Note: intentionally NOT using abs() — a crash below target is still a
                // completed tail AND is the most valuable learning data for the taper learner.
                // Using abs() would trap the session in TAIL forever during a crash.
                if (phaseElapsedMs >= TAIL_MIN_MS) {
                    val bgNearOrBelowTarget = bgMmol <= targetBgMmol + TAIL_COMPLETE_MMOL

                    if (bgNearOrBelowTarget) {
                        completeSession(now, phaseElapsedMs, targetBgMmol)
                        lastMealMode = mealMode
                        return
                    }
                }
            }
        }

        // ── Update debug string ───────────────────────────────────────────────
        updateDebug(now, bgMmol, shortAvgDelta, targetBgMmol, phaseElapsedMs)
        lastMealMode = mealMode
    }

    // ── Prebolus notification ────────────────────────────────────────────────────

    /**
     * Call when prebolus amounts are known for this session (after PB1 fires, and again
     * as PB2/PB3 deliver). Cumulative — calling multiple times adds to the total.
     * Safe to call if no session is active (no-op).
     */
    fun notifyPrebolus(units: Double) {
        if (!sessionActive || units <= 0.0) return
        prebolusU += units
        aapsLogger.debug(LTag.APS,
                         "MealPhaseTracker: prebolus +${"%.2f".format(units)}U → total=${"%.2f".format(prebolusU)}U")
    }

    // ── Manual bolus notification ─────────────────────────────────────────────

    /**
     * Call when a manual bolus is delivered during an active session.
     * Session stays valid but is flagged — learner uses it as a signal
     * that SMB fraction was too low.
     */
    fun onManualBolus(units: Double) {
        if (!sessionActive) return
        manualBolusDetected = true
        manualBolusU       += units
        aapsLogger.debug(LTag.APS,
                         "MealPhaseTracker: manual bolus ${"%+.2f".format(units)}U during $currentPhase phase " +
                             "— session flagged (SMB fraction may have been too low)")
    }

    // ── Meal mode expiry notification ─────────────────────────────────────────

    /**
     * Call this from the plugin when a real meal mode (Lunch, Dinner, etc.) expires
     * naturally due to its duration timeout — i.e. at the point where the plugin detects
     * [previousWasRealMeal && mealMode == FASTING].
     *
     * This allows MealPhaseTracker to distinguish between:
     *   - Natural timeout with BG still elevated → hand off to P/F tracking (don't invalidate)
     *   - Natural timeout with BG near target    → treat as successful completion
     *   - Manual cancel (not called here)        → normal fasting-return invalidation path
     *
     * Must be called **before** [onLoopCycle] for the same cycle so that when
     * [onLoopCycle] sees mealMode == FASTING it finds no active session to invalidate.
     *
     * @param now         Current timestamp ms
     * @param bgMmol      Current BG in mmol/L
     * @param targetBgMmol Current dosing target in mmol/L
     */
    fun onMealModeExpired(now: Long, bgMmol: Double, targetBgMmol: Double) {
        if (!sessionActive) return

        val phaseElapsedMs = now - phaseStartMs
        val bgNearTarget   = bgMmol <= targetBgMmol + TAIL_COMPLETE_MMOL

        if (bgNearTarget) {
            // BG already back near target when mode expired — treat as successful completion.
            // This is a clean UAM auto-cancel or a meal that ended perfectly on schedule.
            aapsLogger.debug(LTag.APS,
                             "MealPhaseTracker: meal mode expired with BG ${"%.1f".format(bgMmol)} " +
                                 "near target — treating as successful completion")
            completeSession(now, phaseElapsedMs, targetBgMmol)
            return
        }

        // BG still elevated — meal mode ran out before the response finished.
        // Advance phase based on where we are in the meal curve.
        aapsLogger.debug(LTag.APS,
                         "MealPhaseTracker: meal mode expired with BG ${"%.1f".format(bgMmol)} " +
                             "${"%.1f".format(bgMmol - targetBgMmol)}mmol above target after ${(now - sessionStartMs) / 60_000}min")

        when (currentPhase) {
            MealPhase.CARB -> {
                // Meal mode expired while still in carb phase — advance to P/F.
                // Plugin will activate UAM_PROTEIN_FAT mode next cycle.
                carbPhaseDurationMs      = phaseElapsedMs
                currentPhase             = MealPhase.PROTEIN_FAT
                phaseStartMs             = now
                transitionCandidateCount = 0
                transitionCandidatePhase = null
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseTracker: CARB→P/F on mode expiry " +
                                     "(carb phase was ${phaseElapsedMs / 60_000}min)")
                lastPhaseDebug = "Mode expired — advanced CARB→P/F | " +
                    "bg=${"%.1f".format(bgMmol)}mmol | session=${(now - sessionStartMs) / 60_000}min"
            }
            MealPhase.PROTEIN_FAT -> {
                // P/F mode expired — this IS the signal that the plateau is done and the
                // tail risk window is starting. The user's configured P/F duration reflects
                // their physiology. Advancing to TAIL immediately rather than waiting for
                // delta-based detection preserves the session for tail crash learning.
                pfPhaseDurationMs        = phaseElapsedMs
                currentPhase             = MealPhase.TAIL
                phaseStartMs             = now
                transitionCandidateCount = 0
                transitionCandidatePhase = null
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseTracker: P/F→TAIL on mode expiry " +
                                     "(pf phase was ${phaseElapsedMs / 60_000}min) " +
                                     "— tail crash risk window now active")
                lastPhaseDebug = "P/F mode expired → TAIL phase | " +
                    "bg=${"%.1f".format(bgMmol)}mmol | session=${(now - sessionStartMs) / 60_000}min"
            }
            MealPhase.TAIL -> {
                // Already in tail — nothing to change
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseTracker: already in TAIL on mode expiry " +
                                     "(${phaseElapsedMs / 60_000}min in tail so far)")
                lastPhaseDebug = "Mode expired — continuing in TAIL | " +
                    "bg=${"%.1f".format(bgMmol)}mmol | session=${(now - sessionStartMs) / 60_000}min"
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun startSession(now: Long, mode: MealMode, bgMmol: Double) {
        sessionActive          = true
        sessionMode            = mode
        sessionStartMs         = now
        phaseStartMs           = now
        currentPhase           = MealPhase.CARB
        carbPhasePeakBgMmol    = bgMmol
        pfPhasePeakBgMmol      = bgMmol
        tailPhaseNadirBgMmol   = Double.MAX_VALUE
        manualBolusDetected    = false
        manualBolusU           = 0.0
        carbPhaseWentLow       = false
        pfPhaseWentLow         = false
        tailPhaseWentLow       = false
        pfHighConsecutive      = 0
        pfPhaseWentHigh        = false
        carbPhaseDurationMs    = 0L
        pfPhaseDurationMs      = 0L
        deltaHistory.clear()
        transitionCandidateCount = 0
        transitionCandidatePhase = null
        firstBolusMs             = 0L
        iobAtCarbExit            = 0.0
        iobAtPfExit              = 0.0
        bgAucCarbPhase           = 0.0
        bgAucPfPhase             = 0.0
        bgAucTailPhase           = 0.0
        prebolusU                = 0.0
        totalSmbsDeliveredU      = 0.0
        carbPhaseSmbsU           = 0.0
        pfPhaseSmbsU             = 0.0
        tailPhaseSmbsU           = 0.0

        aapsLogger.debug(LTag.APS,
                         "MealPhaseTracker: session START mode=$mode bg=${"%.1f".format(bgMmol)}mmol")
        lastPhaseDebug = "Session started | mode=${mode.label} | phase=CARB | bg=${"%.1f".format(bgMmol)}mmol"
        lastTransitionDebug = "Waiting — min 40min in CARB phase before checking"
    }

    private fun confirmTransition(target: MealPhase, now: Long, phaseElapsedMs: Long) {
        if (transitionCandidatePhase != target) {
            transitionCandidatePhase  = target
            transitionCandidateCount  = 1
            return
        }
        transitionCandidateCount++
        if (transitionCandidateCount >= TRANSITION_CONFIRM_READINGS) {
            executeTransition(target, now, phaseElapsedMs)
        }
    }

    private fun executeTransition(target: MealPhase, now: Long, phaseElapsedMs: Long) {
        val fromPhase = currentPhase
        when (fromPhase) {
            MealPhase.CARB        -> { carbPhaseDurationMs = phaseElapsedMs; lastTransitionDebug = "✓ Transitioned to P/F at ${phaseElapsedMs/60_000}min" }
            MealPhase.PROTEIN_FAT -> { pfPhaseDurationMs   = phaseElapsedMs; lastTransitionDebug = "" }
            MealPhase.TAIL        -> { lastTransitionDebug = "" }
        }
        currentPhase             = target
        phaseStartMs             = now
        transitionCandidateCount = 0
        transitionCandidatePhase = null

        aapsLogger.debug(LTag.APS,
                         "MealPhaseTracker: phase $fromPhase → $target " +
                             "after ${phaseElapsedMs / 60_000}min")
    }

    private fun resetTransitionCandidate() {
        transitionCandidateCount = 0
        transitionCandidatePhase = null
    }

    private fun completeSession(now: Long, tailElapsedMs: Long, targetBgMmol: Double) {
        val session = CompletedMealSession(
            mode                = sessionMode,
            sessionStartMs      = sessionStartMs,
            sessionEndMs        = now,
            carbPhaseDurationMs = carbPhaseDurationMs,
            pfPhaseDurationMs   = pfPhaseDurationMs,
            tailPhaseDurationMs = tailElapsedMs,
            carbPhasePeakBgMmol = carbPhasePeakBgMmol,
            pfPhasePeakBgMmol   = pfPhasePeakBgMmol,
            tailNadirBgMmol     = tailPhaseNadirBgMmol,
            manualBolusDetected = manualBolusDetected,
            manualBolusU        = manualBolusU,
            targetBgMmol        = targetBgMmol,
            carbPhaseWentLow    = carbPhaseWentLow,
            pfPhaseWentLow      = pfPhaseWentLow,
            tailPhaseWentLow    = tailPhaseWentLow,
            pfPhaseWentHigh     = pfPhaseWentHigh,
            isClean             = !manualBolusDetected,
            iobAtCarbExit       = iobAtCarbExit,
            iobAtPfExit         = iobAtPfExit,
            prebolusU            = prebolusU,
            totalSmbsDeliveredU  = totalSmbsDeliveredU,
            carbPhaseSmbsU       = carbPhaseSmbsU,
            pfPhaseSmbsU         = pfPhaseSmbsU,
            tailPhaseSmbsU       = tailPhaseSmbsU,
            totalSessionInsulinU = prebolusU + totalSmbsDeliveredU,
            bgAucCarbMmolMin     = bgAucCarbPhase,
            bgAucPfMmolMin       = bgAucPfPhase,
            bgAucTailMmolMin     = bgAucTailPhase
        )

        val nadirDisplay = if (session.tailNadirBgMmol < Double.MAX_VALUE / 2)
            "${"%.1f".format(session.tailNadirBgMmol)}mmol" else "n/a"
        aapsLogger.debug(LTag.APS,
                         "MealPhaseTracker: session COMPLETE mode=${session.mode.label} " +
                             "carb=${session.carbPhaseMins.toInt()}min(peak=${"%.1f".format(session.carbPhasePeakBgMmol)}mmol${if (session.carbPhaseWentLow) " ⚠LOW" else ""}) " +
                             "pf=${session.pfPhaseMins.toInt()}min(peak=${"%.1f".format(session.pfPhasePeakBgMmol)}mmol${if (session.pfPhaseWentLow) " ⚠LOW" else ""}${if (session.pfPhaseWentHigh) " ↑HIGH" else ""}) " +
                             "tail=${session.tailPhaseMins.toInt()}min(nadir=$nadirDisplay${if (session.tailPhaseWentLow) " ⚠LOW" else ""}) " +
                             "clean=${session.isClean}" +
                             if (session.manualBolusDetected) " manualBolus=${"%+.2f".format(session.manualBolusU)}U" else "")

        lastPhaseDebug = buildCompleteSummary(session)

        // Emit to learner if wired in
        onSessionComplete?.invoke(session)

        resetSession()
    }

    private fun resetSession() {
        sessionActive            = false
        sessionMode              = MealMode.FASTING
        currentPhase             = MealPhase.CARB
        carbPhasePeakBgMmol      = 0.0
        pfPhasePeakBgMmol        = 0.0
        tailPhaseNadirBgMmol     = Double.MAX_VALUE
        manualBolusDetected      = false
        manualBolusU             = 0.0
        carbPhaseWentLow         = false
        pfPhaseWentLow           = false
        tailPhaseWentLow         = false
        pfHighConsecutive        = 0
        pfPhaseWentHigh          = false
        carbPhaseDurationMs      = 0L
        pfPhaseDurationMs        = 0L
        deltaHistory.clear()
        transitionCandidateCount = 0
        transitionCandidatePhase = null
        firstBolusMs             = 0L
        iobAtCarbExit            = 0.0
        iobAtPfExit              = 0.0
        bgAucCarbPhase           = 0.0
        bgAucPfPhase             = 0.0
        bgAucTailPhase           = 0.0
        prebolusU                = 0.0
        totalSmbsDeliveredU      = 0.0
        carbPhaseSmbsU           = 0.0
        pfPhaseSmbsU             = 0.0
        tailPhaseSmbsU           = 0.0
    }

    // ── Delta helpers ─────────────────────────────────────────────────────────

    /** Average delta of the N most recent readings */
    private fun recentAvgDelta(n: Int): Double {
        val recent = deltaHistory.takeLast(n.coerceAtMost(deltaHistory.size))
        return if (recent.isEmpty()) 0.0 else recent.map { it.second }.average()
    }

    /** Average delta of readings N+1 to 2N back (for trend comparison) */
    private fun earlyAvgDelta(n: Int): Double {
        val size   = deltaHistory.size
        if (size < n * 2) return recentAvgDelta(n)
        val early  = deltaHistory.toList().subList(size - n * 2, size - n)
        return if (early.isEmpty()) 0.0 else early.map { it.second }.average()
    }

    // ── Debug string builders ─────────────────────────────────────────────────

    private fun updateDebug(
        now:           Long,
        bgMmol:        Double,
        delta:         Double,
        targetBgMmol:  Double,
        phaseElapsedMs: Long
    ) {
        val phaseElapsedMin = phaseElapsedMs / 60_000
        val sessionElapsedMin = (now - sessionStartMs) / 60_000
        val phaseName = currentPhase.name
        val minRequired = when (currentPhase) {
            MealPhase.CARB        -> CARB_MIN_MS / 60_000
            MealPhase.PROTEIN_FAT -> PF_MIN_MS / 60_000
            MealPhase.TAIL        -> TAIL_MIN_MS / 60_000
        }
        val waitingStr = if (phaseElapsedMs < when (currentPhase) {
                MealPhase.CARB -> CARB_MIN_MS; MealPhase.PROTEIN_FAT -> PF_MIN_MS; MealPhase.TAIL -> TAIL_MIN_MS
            }) " [min not reached: ${phaseElapsedMin}/${minRequired}min]" else ""

        val confirmStr = if (transitionCandidateCount > 0)
            " [transition confirm: $transitionCandidateCount/$TRANSITION_CONFIRM_READINGS → $transitionCandidatePhase]" else ""

        val manualStr  = if (manualBolusDetected) " | manualBolus=${"%+.2f".format(manualBolusU)}U" else ""
        val lowStr     = buildString {
            if (carbPhaseWentLow)  append(" | ⚠LOW:carb")
            if (pfPhaseWentLow)    append(" | ⚠LOW:pf")
            if (tailPhaseWentLow)  append(" | ⚠LOW:tail")
            if (pfPhaseWentHigh)   append(" | ↑HIGH:pf")
        }

        lastPhaseDebug = "$phaseName | mode=${sessionMode.label} | " +
            "bg=${"%.1f".format(bgMmol)}mmol | Δ=${"%+.2f".format(delta)}mmol/5min | " +
            "phase=${phaseElapsedMin}min | session=${sessionElapsedMin}min" +
            waitingStr + confirmStr + manualStr + lowStr
    }

    private fun buildCompleteSummary(session: CompletedMealSession): String {
        val carbStr = "Carb: ${session.carbPhaseMins.toInt()}min peak=${"%.1f".format(session.carbPhasePeakBgMmol)}mmol"
        val pfStr   = "P/F: ${session.pfPhaseMins.toInt()}min peak=${"%.1f".format(session.pfPhasePeakBgMmol)}mmol"
        val nadirStr = if (session.tailNadirBgMmol < Double.MAX_VALUE / 2)
            "${"%.1f".format(session.tailNadirBgMmol)}mmol" else "n/a"
        val tailStr = "Tail: ${session.tailPhaseMins.toInt()}min nadir=$nadirStr"
        val cleanStr = if (session.isClean) "clean" else "flagged(manual ${"%+.2f".format(session.manualBolusU)}U)"
        return "✓ Session complete | ${session.mode.label} | $carbStr | $pfStr | $tailStr | $cleanStr"
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    /** True if currently in TAIL phase — used by dosing to gate crash-protection ISF scaling */
    val isInTailPhase: Boolean get() = sessionActive && currentPhase == MealPhase.TAIL

    /** Human-readable current phase for display in SmartInsulinScreen */
    val phaseLabel: String
        get() = when {
            !sessionActive -> ""
            else -> when (currentPhase) {
                MealPhase.CARB        -> "Carb absorption"
                MealPhase.PROTEIN_FAT -> "Protein/Fat plateau"
                MealPhase.TAIL        -> "Tail — tapering"
            }
        }

    /** Status of an individual meal phase — mirrors FragmentData.PhaseStatus for UI */
    data class PhaseStatus(
        val state:           PhaseState,
        val durationMins:    Int    = 0,
        val peakOrNadirMmol: Double = 0.0
    ) {
        enum class PhaseState { PENDING, IN_PROGRESS, COMPLETE }
    }

    fun carbPhaseStatus(): PhaseStatus = when {
        !sessionActive                        -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
        currentPhase == MealPhase.CARB        -> PhaseStatus(PhaseStatus.PhaseState.IN_PROGRESS, peakOrNadirMmol = carbPhasePeakBgMmol)
        carbPhaseDurationMs > 0               -> PhaseStatus(PhaseStatus.PhaseState.COMPLETE, (carbPhaseDurationMs / 60_000).toInt(), carbPhasePeakBgMmol)
        else                                  -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
    }

    fun pfPhaseStatus(): PhaseStatus = when {
        !sessionActive                        -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
        currentPhase == MealPhase.CARB        -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
        currentPhase == MealPhase.PROTEIN_FAT -> PhaseStatus(PhaseStatus.PhaseState.IN_PROGRESS, peakOrNadirMmol = pfPhasePeakBgMmol)
        pfPhaseDurationMs > 0                 -> PhaseStatus(PhaseStatus.PhaseState.COMPLETE, (pfPhaseDurationMs / 60_000).toInt(), pfPhasePeakBgMmol)
        else                                  -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
    }

    fun tailPhaseStatus(): PhaseStatus = when {
        !sessionActive                        -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
        currentPhase != MealPhase.TAIL        -> PhaseStatus(PhaseStatus.PhaseState.PENDING)
        else                                  -> PhaseStatus(PhaseStatus.PhaseState.IN_PROGRESS,
                                                             peakOrNadirMmol = if (tailPhaseNadirBgMmol == Double.MAX_VALUE) 0.0 else tailPhaseNadirBgMmol)
    }

    // ── End of MealPhaseTracker ───────────────────────────────────────────────

    /** Elapsed time in current phase as a fraction of the expected phase duration (0.0–1.0+) */
    val tailPhaseProgress: Double
        get() {
            if (!isInTailPhase) return 0.0
            val elapsed = System.currentTimeMillis() - phaseStartMs
            // Expected tail duration — starts conservative at 60 min until learner has data
            val expectedMs = 60 * 60_000L
            return (elapsed.toDouble() / expectedMs).coerceIn(0.0, 1.5)
        }
}