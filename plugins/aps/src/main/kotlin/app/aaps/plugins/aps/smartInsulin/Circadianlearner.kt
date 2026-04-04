package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sign

/**
 * CircadianLearner — three time-of-day aware learning systems:
 *
 *  1. ISF multiplier    — learns deviation patterns per hour → scales dosingISF up/down
 *  2. Basal multiplier  — learns fasting BG drift per hour  → replaces flat BasalLearner
 *  3. Aggression ceiling — detects rollercoasters + soft-low-approach per hour → caps aggressiveness
 *
 * All three use 24-bucket EWMA. Only update during FASTING mode with zero COB.
 * Persisted as JSON in SharedPreferences.
 */
@Singleton
class CircadianLearner @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var isfState:   DayOfWeekCircadianState = DayOfWeekCircadianState()
    private var basalState: DayOfWeekCircadianState = DayOfWeekCircadianState()
    private var aggrState:  DayOfWeekCircadianState = DayOfWeekCircadianState()

    // Rollercoaster detection — ring buffer of recent (timestamp, bg) pairs
    private val bgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(MAX_HISTORY)

    init { restore() }

    // ── Public outputs ────────────────────────────────────────────────────────

    /** ISF multiplier for current hour (0.7–1.5). >1.0 = less aggressive ISF */
    fun isfMultiplier(hour: Int = currentHour(), dow: Int = currentDow()): Double =
        blend(isfState.get(dow, hour), 1.0, isfState.getConfidence(dow, hour))
            .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

    /** Basal multiplier for current hour (0.5–1.5) */
    fun basalMultiplier(hour: Int = currentHour(), dow: Int = currentDow()): Double =
        blend(basalState.get(dow, hour), 1.0, basalState.getConfidence(dow, hour))
            .coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

    /** Aggressiveness ceiling for current hour (0.6–1.2).
     *  Global aggressiveness should be clamped to min(globalAggr, aggrCeiling) */
    fun aggrCeiling(hour: Int = currentHour(), dow: Int = currentDow()): Double =
        blend(aggrState.get(dow, hour), 1.0, aggrState.getConfidence(dow, hour))
            .coerceIn(AGGR_CEIL_MIN, AGGR_CEIL_MAX)

    // Last basal learning signal for SI tab display
    var lastBasalSignal: String = "No signal yet"
        private set

    // Tracks when the last aggressiveness penalty fired (rollercoaster or soft-low).
    // Used by applyAggrNudge to attenuate nudge strength during the cooldown window.
    private var lastPenaltyMs: Long = 0L
    private var lastPenaltyWasFasting: Boolean = false
    private var lastPenaltyReason: String = "penalty"  // used in cooldown note label
    // Hour-start multiplier snapshot — captured once when the nudge fires for the first time
    // in a given hour. Used as "was" baseline so the display shows cumulative learning
    // within the hour rather than a single per-cycle step.
    private var nudgeSessionHour: Int = -1
    private var nudgeSessionDow:  Int = -1
    private var nudgeSessionIsfMult: Double = 1.0
    private var nudgeSessionBasMult: Double = 1.0
    // Blended session-start values — what the loop was actually using at the top of this nudge session
    // These match isfMultiplier()/basalMultiplier() at session start, not raw day bucket.
    var nudgeSessionBlendedIsfMult: Double = 1.0
        private set
    var nudgeSessionBlendedBasMult: Double = 1.0
        private set
    // Exposed so the plugin/fragment can show "hard low penalty applied" in the UI
    var lastHardLowPenaltyMs: Long = 0L
        private set
    // Rollercoaster escalation tracking — exposed to plugin to extend rebound window
    var consecutiveRollercoasters: Int = 0
        private set
    var lastRollercoasterMs: Long = 0L
        private set

    // Last aggression nudge status for SI tab display
    var lastAggrNudgeStatus: String = "Inactive — no data yet"
        private set

    /** Called from plugin when learning is blocked — keeps status current */
    fun pauseNudgeStatus(reason: String) {
        lastAggrNudgeStatus = "PAUSED|$reason"
    }

    // ── Core update — called every loop cycle ─────────────────────────────────

    /**
     * @param glucoseStatus  Current BG/delta from loop
     * @param iobArray       Current IOB array
     * @param mealMode       Active meal mode — only update during FASTING
     * @param cobG           Current COB — skip learning if > 0
     * @param profileIsfMgdl Profile ISF in mg/dL (used for deviation calc)
     * @param targetMgdl     Current target BG
     * @param lowGuardMgdl   User's low guard in mg/dL — used for soft-low penalty
     * @param inPostMealLockout True if post-meal/P/F lockout active — gates negIOB signal only
     * @param aggressiveness Current global aggressiveness score — used for aggression nudge signal
     * @param suppressAdaptiveLearning True = skip ISF/basal updates, keep rollercoaster protection
     */
    fun update(
        glucoseStatus:            GlucoseStatus,
        iobArray:                 Array<IobTotal>,
        mealMode:                 MealMode,
        cobG:                     Double,
        profileIsfMgdl:           Double,
        targetMgdl:               Double,
        suppressAdaptiveLearning: Boolean = false,
        lowGuardMgdl:             Double  = 90.0,
        inPostMealLockout:        Boolean = false,
        aggressiveness:           Double  = 1.0
    ) {
        val hour = currentHour()
        val dow  = currentDow()
        val bg   = glucoseStatus.glucose
        val delta  = glucoseStatus.shortAvgDelta
        val now    = System.currentTimeMillis()
        val iob    = iobArray.firstOrNull()?.iob      ?: 0.0
        val activity = iobArray.firstOrNull()?.activity ?: 0.0
        val basalIob = iobArray.firstOrNull()?.basaliob ?: 0.0
        val rollercoaster = if (bgHistory.size >= MIN_HISTORY_FOR_ROLLER) detectRollercoaster(targetMgdl, lowGuardMgdl) else false

        // Guard skip reason logged before early return
        val skipReason = when {
            mealMode != MealMode.FASTING -> "skip: mode=$mealMode"
            cobG > COB_THRESHOLD_G       -> "skip: cob=${"%.1f".format(cobG)}g"
            else                         -> null
        }

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner h=$hour bg=${"%.1f".format(bg)} δ=${"%.2f".format(delta)} " +
                             "iob=${"%.2f".format(iob)} activity=${"%.4f".format(activity)} basalIob=${"%.2f".format(basalIob)} " +
                             "cob=${"%.1f".format(cobG)} mode=$mealMode target=${"%.0f".format(targetMgdl)} " +
                             "roller=$rollercoaster histSize=${bgHistory.size} " +
                             "→ ISF×${"%.3f".format(isfMultiplier(hour))} basal×${"%.3f".format(basalMultiplier(hour))} aggrCeil=${"%.3f".format(aggrCeiling(hour))}" +
                             (skipReason?.let { " | $it" } ?: ""))

        if (skipReason != null) return

        // Maintain BG history for rollercoaster detection
        bgHistory.addLast(now to bg)
        // Prune entries older than detection window
        while (bgHistory.isNotEmpty() && now - bgHistory.first().first > ROLLER_WINDOW_MS)
            bgHistory.removeFirst()

        // ── 1. ISF learning — skip during CGM warmup (unreliable data) ─────
        val isfPhysicsFired = if (!suppressAdaptiveLearning)
            updateIsfLearner(hour, dow, glucoseStatus, iobArray, profileIsfMgdl, inPostMealLockout, aggressiveness)
        else { aapsLogger.debug(LTag.APS, "CircadianLearner ISF: suppressed (CGM warmup)"); false }

        // ── 2. Basal learning — skip during CGM warmup ───────────────────────
        if (!suppressAdaptiveLearning) updateBasalLearner(hour, dow, bg, now, basalIob, targetMgdl, inPostMealLockout, aggressiveness)
        else aapsLogger.debug(LTag.APS, "CircadianLearner Basal: suppressed (CGM warmup)")

        // ── 3. Aggressiveness ceiling — ALWAYS runs (rollercoaster protection) ─
        // Rollercoaster and soft-low penalties must fire even on a new sensor —
        // a real rapid rise/crash is dangerous regardless of sensor age.
        updateAggrLearner(hour, dow, bg, delta, targetMgdl, iobArray, lowGuardMgdl, mealMode == MealMode.FASTING, inPostMealLockout)

        // ── 4. Aggression nudge — independent of activity gate ───────────────
        // ISF/basal physics learning requires active IOB to fire, but the nudge
        // only needs the ceiling value. Runs even when physics learning is skipped.
        // If ISF physics fired this cycle, skip ISF nudge — physics has real data.
        if (!suppressAdaptiveLearning) applyAggrNudge(hour, dow, inPostMealLockout, aggressiveness)

        persist()
    }

    // ── ISF learner ───────────────────────────────────────────────────────────

    private fun updateIsfLearner(
        hour:              Int,
        dow:               Int,
        glucoseStatus:     GlucoseStatus,
        iobArray:          Array<IobTotal>,
        profileIsfMgdl:    Double,
        inPostMealLockout: Boolean,
        aggressiveness:    Double
    ): Boolean {
        val activity = iobArray.firstOrNull()?.activity ?: run {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: no iobArray"); return false
        }
        if (abs(activity) < MIN_ACTIVITY) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity=${"%.5f".format(activity)} < $MIN_ACTIVITY")
            return false
        }

        val expectedDelta = activity * profileIsfMgdl * 5.0   // same sign as shortAvgDelta (negative = falling)
        val actualDelta   = glucoseStatus.shortAvgDelta

        if (abs(expectedDelta) < MIN_EXPECTED_DELTA_MGDL) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: expectedΔ=${"%.2f".format(expectedDelta)} < $MIN_EXPECTED_DELTA_MGDL")
            return false
        }

        val deviation     = actualDelta - expectedDelta
        val normDeviation = (deviation / abs(expectedDelta)).coerceIn(-1.0, 2.0)
        // dosingISF = profileISF / isfMult
        // BG drops more than expected (deviation negative) → too much insulin → mult DOWN
        // mult DOWN → dosingISF UP → less aggressive ✓
        // BG rises when expected to fall (deviation positive) → not enough → mult UP
        // mult UP → dosingISF DOWN → more aggressive ✓
        val multTarget    = (isfState.get(dow, hour) + normDeviation).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        isfState = isfState.updated(dow, hour, multTarget, ISF_ALPHA)

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF h=$hour expectedΔ=%.1f actualΔ=%.1f dev=%.2f normDev=%.2f target=%.3f → mult=%.3f"
                             .format(expectedDelta, actualDelta, deviation, normDeviation, multTarget, isfState.get(dow, hour)))
        return true
    }

    // ── Aggression nudge — independent of activity gate ──────────────────────
    // Applies ISF and basal nudge based on aggrCeiling alone. Called directly from
    // update() so it fires even when ISF/basal physics learning is gated by low activity.
    // Handles both directions:
    //   ceiling < AGGR_NUDGE_THRESHOLD → too much insulin → ISF up, basal down
    //   ceiling > AGGR_NUDGE_SURPLUS   → not enough insulin → ISF down, basal up

    private fun applyAggrNudge(
        hour:              Int,
        dow:               Int,
        inPostMealLockout: Boolean,
        aggressiveness:    Double,
    ) {
        val tooMuch      = !inPostMealLockout && aggressiveness < AGGR_NUDGE_THRESHOLD
        val notEnough    = !inPostMealLockout && aggressiveness > AGGR_NUDGE_SURPLUS

        if (!tooMuch && !notEnough) {
            lastAggrNudgeStatus = "INACTIVE"
            return
        }

        // ── Attenuation during penalty cooldown ───────────────────────────────
        // If a rollercoaster or soft-low penalty fired recently, attenuate nudge strength.
        // Fasting penalties are more likely a real profile issue → 35% strength.
        // Meal/post-meal penalties are more likely a food/event issue → 15% strength.
        // After 120 min cooldown with no new penalty, full strength resumes.
        val msSincePenalty = if (lastPenaltyMs > 0L) System.currentTimeMillis() - lastPenaltyMs else Long.MAX_VALUE
        val effectiveScale = when {
            msSincePenalty > AGGR_NUDGE_COOLDOWN_MS -> AGGR_NUDGE_SCALE                  // no recent penalty — full strength
            lastPenaltyWasFasting                   -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_FASTING  // fasting penalty — 35%
            else                                    -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_MEAL     // meal penalty — 15%
        }
        val cooldownActive = msSincePenalty <= AGGR_NUDGE_COOLDOWN_MS
        val cooldownNote   = if (cooldownActive) " [cooldown ${msSincePenalty / 60_000}min/${AGGR_NUDGE_COOLDOWN_MS / 60_000}min fasting=$lastPenaltyWasFasting]" else ""
        val deviation      = if (tooMuch) 1.0 - aggressiveness else aggressiveness - 1.0
        val nudge          = deviation * effectiveScale
        val dayName        = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        val deviationPct   = "${"%.0f".format(deviation * 100)}%"
        val d          = dow.coerceIn(0, 6)
        val prevIsfMult = isfState.days[d].get(hour)
        val prevBasMult = basalState.days[d].get(hour)

        // Capture session-start multipliers on first nudge of this hour/day combo.
        // These are the "was" baseline — held constant all hour so the display shows
        // cumulative drift (session start → now) rather than a single tiny 5-min step.
        if (nudgeSessionHour != hour || nudgeSessionDow != dow) {
            nudgeSessionHour    = hour
            nudgeSessionDow     = dow
            nudgeSessionIsfMult = prevIsfMult
            nudgeSessionBasMult = prevBasMult
            // Capture blended values — these match what the loop was actually using
            nudgeSessionBlendedIsfMult = isfMultiplier(hour, dow)
            nudgeSessionBlendedBasMult = basalMultiplier(hour, dow)
        }

        // Nudge always applies ISF direction — physics learner runs separately on its own signal.
        // Both signals agree on direction (sign fix ensures this). Removing the gate ensures
        // the display and the actual delivered ISF always match what the nudge card says.
        // dosingISF = profileISF / isfMult
        // Too much insulin → ISF mult DOWN → dosingISF goes UP → less aggressive → less insulin ✓
        // Not enough insulin → ISF mult UP → dosingISF goes DOWN → more aggressive → more insulin ✓
        val nudgedIsf = if (tooMuch)
            (prevIsfMult * (1.0 - nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        else
            (prevIsfMult * (1.0 + nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        isfState = isfState.updatedDayOnly(dow, hour, nudgedIsf, 1.0)
        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$cooldownNote " +
                             "h=$hour day=$dayName ceil=${"%.3f".format(aggressiveness)} " +
                             "deviation=${"%.3f".format(deviation)} nudge=${"%.4f".format(nudge)} → mult=${"%.3f".format(isfState.days[d].get(hour))}")

        // Too much insulin → basal mult DOWN (less background insulin)
        // Not enough insulin → basal mult UP (more background insulin)
        val nudgedBas = if (tooMuch)
            (prevBasMult * (1.0 - nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        else
            (prevBasMult * (1.0 + nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        basalState = basalState.updatedDayOnly(dow, hour, nudgedBas, 1.0)

        // Store: direction|deviation%|day|hour|sessionIsfMult|newIsfMult|sessionBasMult|newBasMult|cooldown|penaltyReason
        // Parts 4 and 6 are SESSION-START multipliers (hour baseline) — used as "was" in display.
        // Parts 5 and 7 are CURRENT post-nudge multipliers — used as "now" in display.
        val direction = if (tooMuch) "ACTIVE_HIGH" else "ACTIVE_LOW"
        lastAggrNudgeStatus = "$direction|$deviationPct|$dayName|$hour|" +
            "${"%.4f".format(nudgeSessionIsfMult)}|${"%.4f".format(isfState.days[d].get(hour))}|" +
            "${"%.4f".format(nudgeSessionBasMult)}|${"%.4f".format(basalState.days[d].get(hour))}|" +
            "${if (cooldownActive) "COOLDOWN" else "FULL"}|$lastPenaltyReason"
        lastBasalSignal = "AggrNudge[${if (tooMuch) "↓" else "↑"}]${if (cooldownActive) "[attenuated]" else ""}: ceil=${"%.3f".format(aggressiveness)} deviation=${"%.2f".format(deviation)} → bas×${"%.3f".format(basalState.get(dow, hour))} isf×${"%.3f".format(isfState.days[d].get(hour))} (h=$hour)"
        aapsLogger.debug(LTag.APS,
                         "CircadianLearner Basal[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$cooldownNote " +
                             "h=$hour ceil=${"%.3f".format(aggressiveness)} deviation=${"%.3f".format(deviation)} " +
                             "nudge=${"%.4f".format(nudge)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
    }

    // ── Basal learner ─────────────────────────────────────────────────────────
    //
    // In a closed loop, basalIob is almost always negative (loop zero-temps frequently).
    // Gating on basalIob is therefore wrong — it would almost never fire.
    //
    // Instead: use a sustained BG drift window. Collect (timestamp, bg) pairs during
    // quiet fasting periods (low COB, no bolus — already gated upstream in update()).
    // Once enough samples accumulate over a long enough window, the net drift tells us
    // whether profile basal is too high or too low — regardless of what the loop did
    // to achieve it. If BG drifted up even with loop suppressing basal, profile basal
    // is genuinely too low. If BG stayed flat with loop running normally, it's fine.

    private val basalDriftWindow: ArrayDeque<Pair<Long, Double>> = ArrayDeque(BASAL_WINDOW_MAX)

    private fun updateBasalLearner(
        hour:              Int,
        dow:               Int,
        bg:                Double,
        now:               Long,
        basalIob:          Double,
        targetMgdl:        Double,
        inPostMealLockout: Boolean,
        aggressiveness:    Double
    ) {
        var driftFired = false

        // ── Signal 1: Drift-based learning ───────────────────────────────────
        // Measures sustained BG drift during quiet fasting — if BG is drifting up
        // or down over 60+ min despite the loop, profile basal is wrong.
        basalDriftWindow.addLast(now to bg)
        while (basalDriftWindow.isNotEmpty() && now - basalDriftWindow.first().first > BASAL_DRIFT_WINDOW_MS)
            basalDriftWindow.removeFirst()

        if (basalDriftWindow.size >= BASAL_MIN_SAMPLES) {
            val oldest     = basalDriftWindow.first()
            val newest     = basalDriftWindow.last()
            val elapsedHrs = (newest.first - oldest.first) / 3_600_000.0
            if (elapsedHrs >= BASAL_MIN_ELAPSED_HRS) {
                val driftMgdlPerHr = (newest.second - oldest.second) / elapsedHrs
                when {
                    abs(driftMgdlPerHr) < BASAL_MIN_DRIFT_MGDL_HR ->
                        aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr < noise gate")
                    abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR -> {
                        aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr > sanity gate")
                        basalDriftWindow.clear()
                    }
                    else -> {
                        val adjustment = 1.0 + (driftMgdlPerHr / BASAL_DRIFT_SENSITIVITY)
                        val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                        basalState = basalState.updated(dow, hour, newMult, BASAL_ALPHA)
                        basalDriftWindow.clear()
                        driftFired = true
                        lastBasalSignal = "Drift: ${"%.1f".format(driftMgdlPerHr)} mgdlhr → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                        aapsLogger.debug(LTag.APS,
                                         "CircadianLearner Basal[drift] h=$hour drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr → mult=${"%.3f".format(basalState.get(dow, hour))}")
                    }
                }
            } else {
                aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: elapsed=${"%.2f".format(elapsedHrs)}h < $BASAL_MIN_ELAPSED_HRS")
            }
        } else {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: only ${basalDriftWindow.size}/${BASAL_MIN_SAMPLES} samples")
        }

        // ── Signal 2: Negative IOB compensation signal ────────────────────────
        // When the loop has been consistently zero-temping (negative basalIob) to hold BG
        // below target during fasting, drift shows near-zero because the loop is compensating —
        // but profile basal is genuinely too high. This signal catches that blind spot.
        // Mutually exclusive with drift — if drift fired this cycle, skip negIOB.
        // Not active during post-meal lockout — negative IOB could be meal bolus tail.
        if (!driftFired &&
            !inPostMealLockout &&
            bg < targetMgdl &&
            basalIob < BASAL_NEG_IOB_THRESHOLD &&
            basalDriftWindow.size >= BASAL_NEG_IOB_MIN_SAMPLES) {

            val belowTargetMgdl = targetMgdl - bg
            val adjustment = 1.0 - (belowTargetMgdl / BASAL_NEG_IOB_SENSITIVITY).coerceIn(0.0, BASAL_NEG_IOB_MAX_ADJUST)
            val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
            basalState = basalState.updated(dow, hour, newMult, BASAL_ALPHA * 0.5) // half alpha — softer signal
            lastBasalSignal = "NegIOB: BG ${"%.1f".format(bg)} < target ${"%.1f".format(targetMgdl)}, basalIOB=${"%.2f".format(basalIob)}U → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[negIOB] h=$hour bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} " +
                                 "basalIob=${"%.2f".format(basalIob)} belowTarget=${"%.1f".format(belowTargetMgdl)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
        }
    }

    private fun updateAggrLearner(
        hour:             Int,
        dow:              Int,
        bg:               Double,
        delta:            Double,
        targetMgdl:       Double,
        iobArray:         Array<IobTotal>,
        lowGuardMgdl:     Double,
        isFasting:        Boolean = true,
        inPostMealLockout: Boolean = false
    ) {
        val currentCeil = aggrState.get(dow, hour)

        // ── Penalty signal 1: Rollercoaster ──────────────────────────────────
        val rollercoaster = detectRollercoaster(targetMgdl, lowGuardMgdl)
        if (rollercoaster) {
            // Shape-based compression filter — gate only the LEARNER penalty, not safety actions.
            // Real lows (exercise, alcohol, spontaneous) must always fire the penalty regardless of IOB.
            // Compression lows have a characteristic V-shape: stable pre-trend, rapid symmetric
            // drop+recovery, and exact return to pre-drop baseline. If all signs point to a
            // compression artifact, skip the aggrLearner penalty to prevent chronic under-aggression.
            if (isLikelyCompression(targetMgdl, lowGuardMgdl)) {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour ROLLERCOASTER detected but shape suggests compression low — skipping learner penalty")
            } else {
                val penalised = (currentCeil * AGGR_PENALTY_ROLLER).coerceAtLeast(AGGR_CEIL_MIN)
                aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
                lastPenaltyMs = System.currentTimeMillis()
                lastPenaltyWasFasting = isFasting
                lastPenaltyReason = "rollercoaster"
                // Escalating rollercoaster counter — resets if >2h since last one (new episode)
                val now = System.currentTimeMillis()
                if (lastRollercoasterMs > 0L && now - lastRollercoasterMs > ROLLER_ESCALATION_RESET_MS) {
                    consecutiveRollercoasters = 0
                }
                consecutiveRollercoasters++
                lastRollercoasterMs = now
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour ROLLERCOASTER #$consecutiveRollercoasters detected fasting=$isFasting → ceil=%.3f"
                                     .format(aggrState.get(dow, hour)))
            }
            return
        }

        // ── Penalty signal 2: Hard low — BG below low guard ─────────────────
        // Only penalise the fasting learner if this is a genuine fasting low.
        // If in meal mode, UAM, P/F, or post-meal lockout, the low is food-driven —
        // penalising the fasting profile would corrupt clean fasting data.
        if (bg < lowGuardMgdl && isFasting && !inPostMealLockout) {
            // Short term: 20% ceiling cut every cycle (EWMA-bounded — converges quickly)
            val penalised = (currentCeil * AGGR_PENALTY_HARD_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            // NOTE: lastPenaltyMs intentionally NOT set here — hard low is a separate event
            // from rollercoaster/soft-low and should not trigger the nudge cooldown window.
            lastPenaltyWasFasting = isFasting

            // Long term: nudge basal DOWN and ISF mult UP — once per low event (30-min gate)
            // dosingISF = profileISF / isfMult → mult UP = higher dosingISF = less aggressive
            val nowMs = System.currentTimeMillis()
            val msSinceLastHardLow = if (lastHardLowPenaltyMs > 0L) nowMs - lastHardLowPenaltyMs else Long.MAX_VALUE
            val isNewLowEvent = msSinceLastHardLow > HARD_LOW_BASAL_GATE_MS
            lastHardLowPenaltyMs = nowMs
            if (isNewLowEvent) {
                // Basal: mult DOWN — less background insulin
                val prevBasMult   = basalState.days[dow].get(hour)
                val nudgedBasMult = (prevBasMult * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                basalState = basalState.updatedDayOnly(dow, hour, nudgedBasMult, 1.0)
                // ISF: mult UP — higher dosingISF = less aggressive corrections
                val prevIsfMult   = isfState.days[dow].get(hour)
                val nudgedIsfMult = (prevIsfMult / AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                isfState = isfState.updatedDayOnly(dow, hour, nudgedIsfMult, 1.0)
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour HARD_LOW (new event) bg=${"%.1f".format(bg)} < guard=${"%.1f".format(lowGuardMgdl)} " +
                                     "fasting=$isFasting → ceil=%.3f basal %.4f→%.4f isf %.4f→%.4f"
                                         .format(aggrState.get(dow, hour), prevBasMult, nudgedBasMult, prevIsfMult, nudgedIsfMult))
            } else {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour HARD_LOW (ongoing) bg=${"%.1f".format(bg)} → ceil=%.3f (nudges gated)"
                                     .format(aggrState.get(dow, hour)))
            }
            return
        }

        // ── Penalty signal 3: Soft low approach ──────────────────────────────
        // Same gate as hard low — only penalise fasting profile for fasting lows.
        val iob = iobArray.firstOrNull()?.iob ?: 0.0
        val approachingLow = isFasting && !inPostMealLockout &&
            bg < lowGuardMgdl && delta < SOFT_LOW_DELTA_MGDL && iob > SOFT_LOW_MIN_IOB
        if (approachingLow) {
            val penalised = (currentCeil * AGGR_PENALTY_SOFT_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            lastPenaltyMs   = System.currentTimeMillis()
            lastPenaltyWasFasting = isFasting
            lastPenaltyReason = "soft low approach"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour SOFT_LOW_APPROACH bg=$bg delta=$delta fasting=$isFasting → ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
            return
        }

        // ── Recovery signal: good outcome ────────────────────────────────────
        val stableNearTarget = abs(bg - targetMgdl) < STABLE_BAND_MGDL && abs(delta) < STABLE_DELTA_MGDL
        if (stableNearTarget && currentCeil < 1.0) {
            val recovered = (currentCeil + AGGR_RECOVERY_STEP).coerceAtMost(AGGR_CEIL_MAX)
            aggrState = aggrState.updated(dow, hour, recovered, AGGR_ALPHA_RECOVERY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour STABLE_RECOVERY → ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
        }
    }

    // ── Rollercoaster detection ───────────────────────────────────────────────

    /**
     * Returns true if BG has crossed the target band 2+ times within the detection window.
     * Zero-crossing count on (bg - target) sign changes.
     */
    private fun detectRollercoaster(targetMgdl: Double, lowGuardMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false

        // Asymmetric thresholds — physiologically correct:
        // Low side = user's low guard (personal safety floor, not arbitrary dead band)
        // High side = target + dead band (ignores normal post-target wiggles)
        // Requires chronological alternation — Under→Above→Under or Above→Under→Above.
        // Compression low filtering is handled at the PENALTY level (IOB confirmation)
        // not here, so this function purely detects the BG pattern.
        val highThreshold = targetMgdl + ROLLER_DEAD_BAND_MGDL
        val lowThreshold  = lowGuardMgdl

        var state         = 0
        var extremeSwings = 0

        for ((_, bg) in bgHistory) {
            when {
                bg <= lowThreshold  -> {
                    if (state == 1) extremeSwings++
                    state = -1
                }
                bg >= highThreshold -> {
                    if (state == -1) extremeSwings++
                    state = 1
                }
            }
        }
        return extremeSwings >= ROLLER_CROSSING_THRESHOLD
    }

    /**
     * Shape-based heuristic to distinguish compression lows from real BG events.
     * All signals derived from bgHistory — no IOB dependency.
     *
     * A compression low has a characteristic signature:
     *  1. Short total duration (10–45 min) — mechanical artifact resolves quickly
     *  2. Stable pre-drop trend — real lows usually have a falling lead-in
     *  3. Rapid symmetric drop + recovery — big deltas both ways
     *  4. Exact baseline return — BG lands back where it started (carbs usually overshoot)
     *
     * Conservative by design — only returns true when ALL criteria are met.
     * A single ambiguous feature keeps the full penalty.
     */
    private fun isLikelyCompression(targetMgdl: Double, lowGuardMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false

        // ── Find low period: first and last readings below low guard ─────────
        val lowStart = bgHistory.firstOrNull { it.second <= lowGuardMgdl }?.first ?: return false
        val lowEnd   = bgHistory.lastOrNull  { it.second <= lowGuardMgdl }?.first ?: return false
        val durationMins = (lowEnd - lowStart) / 60_000.0

        // 1. Duration 10–45 min — too short = single artifact, too long = real low
        if (durationMins < 10.0 || durationMins > 45.0) return false

        // 2. Pre-drop stability — real lows usually have a falling trend leading in
        val preDropStart = lowStart - 30 * 60_000L
        val preDropSlice = bgHistory.filter { it.first in preDropStart until lowStart }
        if (preDropSlice.size >= 3) {
            // Calculate average delta over pre-drop window
            val preAvgDelta = (preDropSlice.last().second - preDropSlice.first().second) /
                preDropSlice.size.toDouble()
            // If BG was already trending down > 1.5 mg/dL per reading → more likely real
            if (preAvgDelta < -COMPRESSION_PRE_TREND_GATE) return false
        }

        // 3. Rapid symmetric recovery — large positive deltas on the way back up
        val recoverySlice = bgHistory.filter { it.first in lowEnd..(lowEnd + 20 * 60_000L) }
        if (recoverySlice.size >= 2) {
            val maxRecoveryDelta = recoverySlice.zipWithNext()
                .maxOfOrNull { (a, b) -> b.second - a.second } ?: 0.0
            // If recovery is slow (small deltas) → more likely carb-driven real rebound
            if (maxRecoveryDelta < COMPRESSION_MIN_RECOVERY_DELTA) return false
        }

        // 4. Baseline return — BG lands back near where it started
        val preLowReadings = bgHistory.filter { it.first in preDropStart until lowStart }.takeLast(3)
        val postRecoveryReadings = bgHistory.filter { it.first > lowEnd + 10 * 60_000L }.take(3)
        if (preLowReadings.size >= 2 && postRecoveryReadings.size >= 2) {
            val preLowAvg  = preLowReadings.map { it.second }.average()
            val postRecAvg = postRecoveryReadings.map { it.second }.average()
            // If BG lands more than ~1.1 mmol (20 mg/dL) from where it started → real event
            if (kotlin.math.abs(postRecAvg - preLowAvg) > COMPRESSION_BASELINE_RETURN_GATE) return false
        }

        // All four criteria met — high confidence this is a compression artifact
        return true
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persist() {
        try {
            val json = JSONObject().apply {
                put("isf",   isfState.toJson())
                put("basal", basalState.toJson())
                put("aggr",  aggrState.toJson())
            }
            preferences.put(StringKey.ApsSmartInsulinCircadianState, json.toString())
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner persist failed: ${e.message}")
        }
    }

    private fun restore() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinCircadianState)
            if (raw.isBlank()) return
            val json = JSONObject(raw)

            // ── Migration: flat 24h format → 7-day format ─────────────────────
            // Flat format has "values"/"confidence" arrays directly inside each sub-object.
            // Migrate by copying flat values into all 7 day buckets AND global —
            // preserving all learned data rather than discarding it.
            if (json.getJSONObject("isf").has("values")) {
                aapsLogger.debug(LTag.APS, "CircadianLearner: flat format detected, migrating to 7-day structure")
                isfState   = migrateFlatTo7Day(json.getJSONObject("isf"))
                basalState = migrateFlatTo7Day(json.getJSONObject("basal"))
                aggrState  = migrateFlatTo7Day(json.getJSONObject("aggr"))
                persist()  // save immediately in 7-day format
                aapsLogger.debug(LTag.APS, "CircadianLearner: flat→7day migration complete")
                return
            }

            // ── Normal 7-day format ───────────────────────────────────────────
            isfState   = DayOfWeekCircadianState.fromJson(json.getJSONObject("isf"))
            basalState = DayOfWeekCircadianState.fromJson(json.getJSONObject("basal"))
            aggrState  = DayOfWeekCircadianState.fromJson(json.getJSONObject("aggr"))
            aapsLogger.debug(LTag.APS, "CircadianLearner restored (day-of-week)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner restore failed: ${e.message}")
        }
    }

    /**
     * Migrate a flat 24h JSON object to a [DayOfWeekCircadianState].
     * Copies the flat values into all 7 day buckets and global so no data is lost.
     * Confidence is preserved as-is — the data is real, just not day-segmented yet.
     */
    private fun migrateFlatTo7Day(flatJson: JSONObject): DayOfWeekCircadianState {
        val vArr   = flatJson.getJSONArray("values")
        val cArr   = flatJson.getJSONArray("confidence")
        val values = DoubleArray(24) { vArr.getDouble(it) }
        val conf   = DoubleArray(24) { cArr.getDouble(it) }
        val flatState = CircadianState(values, conf)
        // Copy flat state into all 7 day buckets and global
        val days = Array(7) { flatState }
        return DayOfWeekCircadianState(days, flatState)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset() {
        isfState   = DayOfWeekCircadianState()
        basalState = DayOfWeekCircadianState()
        aggrState  = DayOfWeekCircadianState()
        bgHistory.clear()
        preferences.put(StringKey.ApsSmartInsulinCircadianState, "")
        aapsLogger.debug(LTag.APS, "CircadianLearner reset")
    }

    fun resetBasal() {
        basalState = DayOfWeekCircadianState()
        persist()
        aapsLogger.debug(LTag.APS, "CircadianLearner basal state reset")
    }

    fun resetAggr() {
        aggrState = DayOfWeekCircadianState()
        bgHistory.clear()
        persist()
        aapsLogger.debug(LTag.APS, "CircadianLearner aggr state reset")
    }

    // ── Status summary for tab UI ─────────────────────────────────────────────

    /** Average confidence across ISF/basal/aggr for a given hour and day, as 0–100 */
    fun confidencePct(hour: Int, dow: Int = currentDow()): Double {
        val h = hour.coerceIn(0, 23)
        return ((isfState.getConfidence(dow, h) + basalState.getConfidence(dow, h) + aggrState.getConfidence(dow, h)) / 3.0) * 100.0
    }

    fun statusSummary(hour: Int = currentHour(), dow: Int = currentDow()): String {
        val dayLabel = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        return "h=$hour($dayLabel) ISF×%.2f basal×%.2f aggrCeil=%.2f (conf isf=%.0f%% basal=%.0f%% aggr=%.0f%%)"
            .format(
                isfMultiplier(hour, dow), basalMultiplier(hour, dow), aggrCeiling(hour, dow),
                isfState.getConfidence(dow, hour) * 100,
                basalState.getConfidence(dow, hour) * 100,
                aggrState.getConfidence(dow, hour) * 100
            )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun currentDow(): Int  = DayOfWeekCircadianState.currentDayOfWeek()

    /** Blend learned value toward default (1.0) based on confidence */
    private fun blend(learned: Double, default: Double, confidence: Double) =
        default + (learned - default) * confidence

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        // ISF learner
        private const val ISF_ALPHA              = 0.08   // slow EWMA — each sample moves ~8%
        private const val ISF_MULT_MIN           = 0.7
        private const val ISF_MULT_MAX           = 1.5
        private const val MIN_ACTIVITY           = 0.005  // min IOB activity to learn from
        private const val MIN_EXPECTED_DELTA_MGDL = 1.0

        // Basal learner — drift window approach (basalIob gate removed, always negative in closed loop)
        private const val BASAL_ALPHA              = 0.06
        private const val BASAL_MULT_MIN           = 0.5
        private const val BASAL_MULT_MAX           = 1.5
        private const val BASAL_DRIFT_WINDOW_MS    = 90 * 60 * 1000L  // 90 min window to measure drift
        private const val BASAL_MIN_SAMPLES        = 12               // ~60 min of readings
        private const val BASAL_MIN_ELAPSED_HRS    = 0.5              // at least 30 min spread
        private const val BASAL_MIN_DRIFT_MGDL_HR  = 2.0             // < 2 mg/dL/hr = noise, ignore
        private const val BASAL_MAX_DRIFT_MGDL_HR  = 27.0            // > 1.5 mmol/hr = something else going on
        private const val BASAL_DRIFT_SENSITIVITY  = 18.0            // 18 mg/dL/hr drift → 1.0 multiplier adjustment (1 mmol/L/hr)
        private const val BASAL_WINDOW_MAX         = 30              // ring buffer max size

        // Negative IOB compensation signal
        private const val BASAL_NEG_IOB_THRESHOLD   = -0.15          // basalIob must be at least this negative (U)
        private const val BASAL_NEG_IOB_MIN_SAMPLES = 6              // ~30 min of consistent signal before acting
        private const val BASAL_NEG_IOB_SENSITIVITY = 36.0           // 2 mmol below target → max adjustment
        private const val BASAL_NEG_IOB_MAX_ADJUST  = 0.10           // cap at 10% reduction per firing

        // Aggression nudge signal — fast-path basal/ISF correction driven by aggrCeiling.
        // aggrCeiling is a slow per-hour per-day EWMA — it won't drop below threshold
        // from a single bad cycle. It represents weeks of consistent pattern at that hour.
        // No additional cycle counting needed — the ceiling IS the confirmation filter.
        private const val AGGR_NUDGE_THRESHOLD    = 0.95           // ceiling below this → too much insulin, nudge to reduce
        private const val AGGR_NUDGE_SURPLUS      = 1.05           // ceiling above this → not enough insulin, nudge to increase
        private const val AGGR_NUDGE_SCALE        = 0.04           // 20% deviation → 0.8% nudge per cycle
        private const val AGGR_NUDGE_COOLDOWN_MS  = 120 * 60_000L  // 120 min penalty cooldown window
        private const val AGGR_NUDGE_ATTN_FASTING = 0.35           // attenuated scale during cooldown — fasting penalty (more likely profile issue)
        private const val AGGR_NUDGE_ATTN_MEAL    = 0.15           // attenuated scale during cooldown — meal/post-meal penalty (less likely profile issue)

        // Aggressiveness ceiling
        private const val AGGR_ALPHA_PENALTY    = 0.25   // penalty applies quickly
        private const val AGGR_ALPHA_RECOVERY   = 0.04   // recovery is slow
        private const val AGGR_PENALTY_ROLLER       = 0.85   // 15% cut on rollercoaster
        private const val AGGR_PENALTY_HARD_LOW     = 0.80   // 20% short-term ceiling cut when BG crosses below low guard
        private const val AGGR_HARD_LOW_BASAL_NUDGE = 0.90   // 10% long-term basal nudge down on hard low (once per event)
        private const val HARD_LOW_BASAL_GATE_MS    = 30 * 60_000L  // basal nudge only fires once per 30-min low event
        private const val AGGR_PENALTY_SOFT_LOW     = 0.90   // 10% cut on soft low approach
        private const val AGGR_RECOVERY_STEP    = 0.01   // +1% per stable cycle
        private const val AGGR_CEIL_MIN         = 0.60
        private const val AGGR_CEIL_MAX         = 1.20
        private const val STABLE_BAND_MGDL      = 18.0   // ±1 mmol = stable
        private const val STABLE_DELTA_MGDL     = 1.5    // mg/dL per 5min = flat
        private const val SOFT_LOW_DELTA_MGDL   = -1.5   // falling at least this fast (mg/dL per 5min)
        private const val SOFT_LOW_MIN_IOB      = 0.3    // must have meaningful IOB

        // Rollercoaster detection
        private const val ROLLER_WINDOW_MS          = 90 * 60 * 1000L   // 90 min window
        private const val ROLLER_CROSSING_THRESHOLD = 2                  // 2+ extreme swings = rollercoaster
        private const val ROLLER_DEAD_BAND_MGDL     = 18.0              // ~1.0 mmol above target to count as high crossing
        private const val MIN_HISTORY_FOR_ROLLER    = 6                  // need ≥6 readings (~30 min)
        private const val ROLLER_ESCALATION_RESET_MS = 2 * 60 * 60 * 1000L  // 2h gap = new episode, reset counter
        // Compression low heuristic constants
        private const val COMPRESSION_PRE_TREND_GATE       = 1.5        // mg/dL per reading — pre-drop falling faster than this → likely real
        private const val COMPRESSION_MIN_RECOVERY_DELTA   = 10.0       // mg/dL per 5min — recovery must be fast to flag as compression (~0.55 mmol/5min)
        private const val COMPRESSION_BASELINE_RETURN_GATE = 20.0       // mg/dL — post-recovery must land within ~1.1 mmol of pre-drop baseline

        // General
        private const val COB_THRESHOLD_G = 5.0   // ignore cycles with active carbs
        private const val MAX_HISTORY     = 30     // ring buffer size
    }
}