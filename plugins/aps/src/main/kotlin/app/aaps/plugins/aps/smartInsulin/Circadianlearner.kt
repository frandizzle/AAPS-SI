package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
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
 *  1. ISF multiplier    — learns deviation patterns per hour ? scales dosingISF up/down
 *  2. Basal multiplier  — learns fasting BG drift per hour  ? replaces flat BasalLearner
 *  3. Aggression ceiling — detects rollercoasters + soft-low-approach per hour ? caps aggressiveness
 *
 * All three use 24-bucket EWMA. Only update during FASTING mode with zero COB.
 * Persisted as JSON in SharedPreferences.
 */
@Singleton
class CircadianLearner @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val sp: SP
) {

    // -- State -----------------------------------------------------------------

    private var isfState:   DayOfWeekCircadianState = DayOfWeekCircadianState()
    private var basalState: DayOfWeekCircadianState = DayOfWeekCircadianState()
    private var aggrState:  DayOfWeekCircadianState = DayOfWeekCircadianState()

    // Persistence is gated by reference comparison in update() — persist() only fires
    // if any state reference was replaced. Reduces SharedPreferences writes by ~90%.

    // Rollercoaster detection — ring buffer of recent (timestamp, bg) pairs
    private val bgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(MAX_HISTORY)

    init { restore() }

    // -- Public outputs --------------------------------------------------------

    /** ISF multiplier for current hour (0.7–1.5). >1.0 = less aggressive ISF */
    fun isfMultiplier(hour: Int = currentHour(), dow: Int = currentDow()): Double =
        isfState.get(dow, hour).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

    /** Basal multiplier for current hour (0.5–1.5) */
    fun basalMultiplier(hour: Int = currentHour(), dow: Int = currentDow()): Double =
        basalState.get(dow, hour).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

    /** Aggressiveness ceiling for current hour (0.6–1.2).
     *  Global aggressiveness should be clamped to min(globalAggr, aggrCeiling) */
    fun aggrCeiling(hour: Int = currentHour(), dow: Int = currentDow()): Double =
        aggrState.get(dow, hour).coerceIn(AGGR_CEIL_MIN, AGGR_CEIL_MAX)

    // Last basal learning signal for SI tab display
    var lastBasalSignal:  String = "No signal yet"
    var lastAccelDebug:    String = "No data"
        private set
    var lastPredTrimDebug: String = "No data"
        private set

    // Consolidated per-cycle diagnostic summary — shown in-app via Fragment debug
    // section instead of logcat, since logcat isn't practically viewable on-device.
    var lastCycleSummary: String = "No cycle data yet"
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

    // -- Mode transition tracking — clears drift window on mode change --------
    // Prevents stale pre-P/F fasting samples from mixing with post-P/F fasting
    private var previousMealModeForDrift: MealMode = MealMode.FASTING

    // -- Low guard penalty state ----------------------------------------------
    // One-time 20% ISF+basal reduction when BG crosses below low guard.
    // Resets when BG recovers back above low guard so it can fire again next low.
    private var lowGuardPenaltyFired = false

    // -- Short-term fuel trim state -------------------------------------------
    private val trimBgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(36)
    private var trimWindowMs: Long = 90 * 60_000L
    var trimActive: Boolean = false
        private set
    var trimStrength = 0.0
    private var trimDirection = 0
    private var trimStartMs  = 0L
    val trimMins: Long get() = if (trimActive && trimStartMs > 0L) (System.currentTimeMillis() - trimStartMs) / 60_000L else 0L
    private var lastTrimActionMs = 0L // NEW: Tracks the "Wait and Re-assess" window

    // Last aggression nudge status for SI tab display
    var lastAggrNudgeStatus: String = "Inactive — no data yet"
        private set

    /** Called from plugin when learning is blocked — keeps status current */
    fun pauseNudgeStatus(reason: String) {
        lastAggrNudgeStatus = "PAUSED|$reason"
    }

    // -- Core update — called every loop cycle ---------------------------------

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
        aggressiveness:           Double  = 1.0,
        fastingPeakMins:          Double  = 90.0,   // learned fasting insulin peak — sets trim window
        // Time injection — defaults to wall clock. Override in tests to simulate specific
        // hours/days without waiting for real time to pass.
        hour:                     Int     = currentHour(),
        dow:                      Int     = currentDow(),
        nowMs:                    Long    = System.currentTimeMillis()
    ) {
        val bg   = glucoseStatus.glucose
        val delta  = glucoseStatus.shortAvgDelta
        val now    = nowMs
        val iob    = iobArray.firstOrNull()?.iob      ?: 0.0
        val activity = iobArray.firstOrNull()?.activity ?: 0.0
        val basalIob = iobArray.firstOrNull()?.basaliob ?: 0.0
        val rollercoaster = if (bgHistory.size >= MIN_HISTORY_FOR_ROLLER) detectRollercoaster(targetMgdl, lowGuardMgdl) else false
        // NOTE: computed BEFORE bgHistory.addLast — only valid for diagnostic logging.
        // updateAggrLearner re-computes post-addLast so the penalty sees the current reading.

        // Snapshot state references — persist only if any state object was replaced
        val prevIsf   = isfState
        val prevBasal = basalState
        val prevAggr  = aggrState

        // Guard skip reason logged before early return
        val skipReason = when {
            mealMode != MealMode.FASTING -> "skip: mode=$mealMode"
            cobG > COB_THRESHOLD_G       -> "skip: cob=${"%.1f".format(cobG)}g"
            else                         -> null
        }

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner h=$hour bg=${"%.1f".format(bg)} d=${"%.2f".format(delta)} " +
                             "iob=${"%.2f".format(iob)} activity=${"%.4f".format(activity)} basalIob=${"%.2f".format(basalIob)} " +
                             "cob=${"%.1f".format(cobG)} mode=$mealMode target=${"%.0f".format(targetMgdl)} " +
                             "roller=$rollercoaster histSize=${bgHistory.size} " +
                             "? ISF×${"%.3f".format(isfMultiplier(hour))} basal×${"%.3f".format(basalMultiplier(hour))} aggrCeil=${"%.3f".format(aggrCeiling(hour))}" +
                             (skipReason?.let { " | $it" } ?: ""))

        // Clear basalDriftWindow on any mode transition into or out of fasting.
        // Prevents stale pre-P/F fasting samples from mixing with post-P/F fasting signal.
        if (mealMode != previousMealModeForDrift) {
            if (basalDriftWindow.isNotEmpty()) {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner: mode transition $previousMealModeForDrift?$mealMode — clearing basalDriftWindow (${basalDriftWindow.size} samples)")
                basalDriftWindow.clear()
            }
            previousMealModeForDrift = mealMode
        }

        if (skipReason != null) {
            // Still emit the summary line on skip cycles so the log has no gaps —
            // this is exactly the window (post-meal, P/F) where most contradictions
            // in mult direction turned out to originate from a PRIOR cycle's write
            // being misread as "from this moment" in the UI's was/now comparison.
            lastCycleSummary =
                "h=$hour bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} " +
                    "mode=$mealMode cob=${"%.1f".format(cobG)}\nSKIPPED ($skipReason) — no writes this cycle\n" +
                    "ISF×${"%.3f".format(isfMultiplier(hour))} basal×${"%.3f".format(basalMultiplier(hour))} aggrCeil=${"%.3f".format(aggrCeiling(hour))}"
            aapsLogger.debug(LTag.APS, "CircadianLearner SUMMARY ${lastCycleSummary.replace("\n", " | ")}")
            return
        }

        // Clear trim history if BG crosses below low guard
        if (bg < lowGuardMgdl) {
            if (trimBgHistory.isNotEmpty()) {
                aapsLogger.debug(LTag.APS, "FuelTrim: clearing history — BG below low guard (${"%.1f".format(bg)} < ${"%.0f".format(lowGuardMgdl)})")
                trimBgHistory.clear()
                trimActive = false
                trimStrength = 0.0
                trimDirection = 0
            }
            // One-time low guard penalty — 20% ISF mult and basal mult reduction at this hour.
            // Only fires once per low event. Tells the learner "too much insulin at this hour".
            // Does NOT keep firing so recovery BG behaviour doesn't compound the penalty.
            if (!lowGuardPenaltyFired) {
                lowGuardPenaltyFired = true
                val d = dow.coerceIn(0, 6)
                val prevIsfVal = isfState.days[d].get(hour)
                val prevBasVal = basalState.days[d].get(hour)
                val penalisedIsf = (prevIsfVal * 0.90).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                val penalisedBas = (prevBasVal * 0.90).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                isfState   = isfState.updatedDayOnly(dow, hour, penalisedIsf, 1.0)
                basalState = basalState.updatedDayOnly(dow, hour, penalisedBas, 1.0)
                aapsLogger.debug(LTag.APS,
                                 "LowGuard penalty h=$hour: ISF mult ${"%.3f".format(prevIsfVal)}?${"%.3f".format(penalisedIsf)} " +
                                     "basal mult ${"%.3f".format(prevBasVal)}?${"%.3f".format(penalisedBas)}")
            }
        } else {
            // BG recovered above low guard — reset so penalty can fire again next low
            if (lowGuardPenaltyFired) {
                aapsLogger.debug(LTag.APS, "LowGuard penalty reset — BG recovered above low guard")
                lowGuardPenaltyFired = false
            }
        }

        // Maintain BG history for rollercoaster detection
        bgHistory.addLast(now to bg)
        // Prune entries older than detection window
        while (bgHistory.isNotEmpty() && now - bgHistory.first().first > ROLLER_WINDOW_MS)
            bgHistory.removeFirst()

        // -- 1. ISF learning — skip during CGM warmup (unreliable data) -----
        val isFasting = mealMode == MealMode.FASTING
        val isfPhysicsFromIsf = if (!suppressAdaptiveLearning)
            updateIsfLearner(hour, dow, glucoseStatus, iobArray, profileIsfMgdl, inPostMealLockout, isFasting, aggressiveness, bg, lowGuardMgdl, targetMgdl)
        else { aapsLogger.debug(LTag.APS, "CircadianLearner ISF: suppressed (CGM warmup)"); false }

        // -- 2. Basal learning — skip during CGM warmup -----------------------
        // Pre-compute notEnough here so updateBasalLearner can suppress PredTrim when
        // the aggrNudge is actively calling for more insulin — they measure different
        // horizons and cancel each other when both fire simultaneously.
        val aggrNotEnough = !inPostMealLockout && aggressiveness > AGGR_NUDGE_SURPLUS
        val basalPhysicsFired = if (!suppressAdaptiveLearning) updateBasalLearner(hour, dow, bg, now, basalIob, targetMgdl, inPostMealLockout, aggressiveness, aggrNotEnough, lowGuardMgdl,
                                                                                  activity = activity,
                                                                                  shortAvgDelta = delta,
                                                                                  profileIsfMgdl = profileIsfMgdl,
                                                                                  isfLearningActive = isfPhysicsFromIsf)
        else { aapsLogger.debug(LTag.APS, "CircadianLearner Basal: suppressed (CGM warmup)"); false }
        // Signal 4 (subTarget) writes isfState directly inside updateBasalLearner.
        // Merge into isfPhysicsFired so applyAggrNudge doesn't double-write ISF the same cycle.
        val isfPhysicsFired = isfPhysicsFromIsf || basalPhysicsFired

        // -- 3. Aggressiveness ceiling — ALWAYS runs (rollercoaster protection) -
        // Rollercoaster and soft-low penalties must fire even on a new sensor —
        // a real rapid rise/crash is dangerous regardless of sensor age.
        updateAggrLearner(hour, dow, bg, delta, targetMgdl, iobArray, lowGuardMgdl, mealMode == MealMode.FASTING, inPostMealLockout)

        // -- 4. Short-term fuel trim + aggression nudge --------------------------
        // Update trim window to match learned insulin peak (minimum 60 min, maximum 120 min).
        // This means the trim won't fire until BG has been off target for a full peak cycle —
        // ensuring we're not reacting to a rise that insulin is already handling.
        trimWindowMs = (fastingPeakMins * 60_000.0).toLong().coerceIn(60 * 60_000L, 120 * 60_000L)

        if (!suppressAdaptiveLearning) applyAggrNudge(hour, dow, inPostMealLockout, aggressiveness,
                                                      bg = bg, targetMgdl = targetMgdl, lowGuardMgdl = lowGuardMgdl, now = nowMs,
                                                      isfPhysicsFired = isfPhysicsFired,
                                                      basalPhysicsFired = basalPhysicsFired,
                                                      iob = iob)

        // -- CYCLE SUMMARY — single consolidated string, every cycle -------------
        // Purpose: make "why did this number move" answerable from one place
        // instead of re-deriving signal interactions from source. Captures:
        //   - net mult change this cycle for the CURRENT hour bucket (ISF, basal, aggrCeil)
        //   - which writer(s) actually fired (vs just ran and no-op'd)
        //   - the live diagnostic strings each signal already maintains
        // direction tag: UP = more aggressive/more insulin, DOWN = less aggressive/less insulin
        // (UP for ISF mult specifically means dosingISF goes DOWN — smaller mmol — more insulin)
        // Stored in lastCycleSummary (multi-line, on-device viewable via Fragment debug
        // section) and also mirrored to logcat as a single line for anyone who does have
        // log access.
        run {
            val isfBefore   = prevIsf.get(dow, hour)
            val isfAfter    = isfState.get(dow, hour)
            val basBefore   = prevBasal.get(dow, hour)
            val basAfter    = basalState.get(dow, hour)
            val ceilBefore  = prevAggr.get(dow, hour)
            val ceilAfter   = aggrState.get(dow, hour)

            fun dirTag(before: Double, after: Double): String = when {
                after > before + 1e-6 -> "UP"
                after < before - 1e-6 -> "DOWN"
                else                  -> "—"
            }

            lastCycleSummary =
                "h=$hour dow=${DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0,6)]} " +
                    "bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} delta=${"%.2f".format(delta)}\n" +
                    "iob=${"%.2f".format(iob)} basalIob=${"%.2f".format(basalIob)} activity=${"%.4f".format(activity)} " +
                    "postMeal=$inPostMealLockout mode=$mealMode\n" +
                    "ISF mult ${"%.3f".format(isfBefore)}→${"%.3f".format(isfAfter)} [${dirTag(isfBefore, isfAfter)}] " +
                    "(fired=$isfPhysicsFromIsf)\n" +
                    "BASAL mult ${"%.3f".format(basBefore)}→${"%.3f".format(basAfter)} [${dirTag(basBefore, basAfter)}] " +
                    "(fired=$basalPhysicsFired)\n" +
                    "CEIL ${"%.3f".format(ceilBefore)}→${"%.3f".format(ceilAfter)} [${dirTag(ceilBefore, ceilAfter)}] " +
                    "(notEnough=$aggrNotEnough)\n" +
                    "basalSignal: $lastBasalSignal\n" +
                    "aggrNudge: $lastAggrNudgeStatus\n" +
                    "predTrim: $lastPredTrimDebug"

            aapsLogger.debug(LTag.APS, "CircadianLearner SUMMARY ${lastCycleSummary.replace("\n", " | ")}")
        }

        // Only persist if any EWMA state was actually updated this cycle
        if (isfState !== prevIsf || basalState !== prevBasal || aggrState !== prevAggr) {
            persist()
        }
    }

    // -- ISF learner -----------------------------------------------------------

    private fun updateIsfLearner(
        hour:              Int,
        dow:               Int,
        glucoseStatus:     GlucoseStatus,
        iobArray:          Array<IobTotal>,
        profileIsfMgdl:    Double,
        inPostMealLockout: Boolean,
        isFasting:         Boolean,
        aggressiveness:    Double,
        bg:                Double  = 0.0,
        lowGuardMgdl:      Double  = 90.0,
        targetMgdl:        Double  = 99.0
    ): Boolean {
        // Only train ISF from clean fasting signal — meal/UAM/P/F BG changes are food-driven
        if (!isFasting || inPostMealLockout) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: not fasting or post-meal lockout")
            return false
        }
        val activity = iobArray.firstOrNull()?.activity ?: run {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: no iobArray"); return false
        }
        if (abs(activity) < MIN_ACTIVITY) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity=${"%.5f".format(activity)} < $MIN_ACTIVITY")
            return false
        }
        // In AAPS, normal active insulin produces POSITIVE activity.
        // Negative activity = pump withholding insulin (negative IOB / TBR cut) — inverted signal.
        // Skip negative activity to avoid learning from inverted signals.
        // Proven by UamController BGI calc: uamBgiMmol = -(activity * ISF * 5) / 18 — the
        // negation is required because activity is positive when insulin is actively working.
        if (activity < 0.0) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity < 0 (negative IOB / TBR reduction — inverted signal)")
            return false
        }

        // Multiply by -1: positive activity means insulin pulling BG DOWN ? negative expected delta
        val expectedDelta = -activity * profileIsfMgdl * 5.0   // negative = BG expected to fall
        val actualDelta   = glucoseStatus.shortAvgDelta

        if (abs(expectedDelta) < MIN_EXPECTED_DELTA_MGDL) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: expected?=${"%.2f".format(expectedDelta)} < $MIN_EXPECTED_DELTA_MGDL")
            return false
        }

        val deviation     = actualDelta - expectedDelta
        // Safety-biased clamp: negative deviation (BG fell MORE than expected -> LESS insulin)
        // may move up to 2.0 (fast retreat); positive deviation (insulin looked weak -> MORE
        // insulin) is capped at 1.0 so a single noisy reading can't push dosing ISF down hard.
        var normDeviation = (deviation / abs(expectedDelta)).coerceIn(-1.0, 0.5)   // was (-2.0, 1.0)        // dosingISF = profileISF / isfMult
        // expectedDelta is negative (BG should fall from insulin)
        // actualDelta - expectedDelta:
        //   BG drops MORE than expected ? deviation negative ? mult DOWN ? dosingISF UP ? less aggressive ?
        //   BG drops LESS than expected (insulin weaker) ? deviation positive ? mult UP ? dosingISF DOWN ? more aggressive ?

        // --- BELOW-TARGET GUARD ---
        // The deviation calc above only compares actual vs IOB-predicted delta — it has no
        // awareness of where BG actually sits. During a sustained low with decaying IOB tail,
        // BG can flatten out (stop falling) while activity is still positive. That reads as
        // "insulin weaker than expected" (positive deviation) and would push mult UP (more
        // aggressive) — exactly backwards when BG is already below target. Block ONLY the
        // up-direction in that case; the down-direction (less aggressive) stays available
        // since reducing aggressiveness while low is always safe.
        if (normDeviation > 0.0 && bg < targetMgdl) {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF: blocked positive normDev=${"%.2f".format(normDeviation)} — bg=${"%.1f".format(bg)} < target=${"%.1f".format(targetMgdl)}, would increase aggressiveness while low")
            normDeviation = 0.0
        }
        val multTarget    = (isfState.get(dow, hour) + normDeviation).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

        // Confidence-weighted learning speed:
        // High confidence hours (0.8+) learn slower (stable).
        // Low confidence hours (<0.2) learn faster (converge quickly).
        val conf  = isfState.getConfidence(dow, hour)
        val alpha = (ISF_ALPHA * (1.5 - conf)).coerceIn(ISF_ALPHA * 0.5, ISF_ALPHA * 1.5)

        // Write to both day bucket AND global so the blended output actually reflects
        // what the learner has observed. updatedDayOnly left global at 1.0 permanently,
        // causing get() to return 1.0 regardless of day bucket learnings until day
        // confidence crossed DAY_CONFIDENCE_THRESHOLD.
        isfState = isfState.updated(dow, hour, multTarget, alpha)

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF h=$hour expected?=%.1f actual?=%.1f dev=%.2f normDev=%.2f target=%.3f a=%.3f ? mult=%.3f"
                             .format(expectedDelta, actualDelta, deviation, normDeviation, multTarget, alpha, isfState.get(dow, hour)))
        return true
    }

    // -- Aggression nudge — independent of activity gate ----------------------
    // Applies ISF and basal nudge based on aggrCeiling alone. Called directly from
    // update() so it fires even when ISF/basal physics learning is gated by low activity.
    // Handles both directions:
    //   ceiling < AGGR_NUDGE_THRESHOLD ? too much insulin ? ISF up, basal down
    //   ceiling > AGGR_NUDGE_SURPLUS   ? not enough insulin ? ISF down, basal up

    /**
     * Computes BG acceleration (2nd derivative) from the last 3 readings in bgHistory.
     * Positive = BG curving upward (velocity increasing), negative = curving downward.
     * Units: mg/dL change in delta per 5-min interval.
     */
    private fun computeAcceleration(): Double {
        if (bgHistory.size < 3) return 0.0
        val size = bgHistory.size
        val a = bgHistory[size - 3].second  // 10 min ago
        val b = bgHistory[size - 2].second  // 5 min ago
        val c = bgHistory[size - 1].second  // now
        return (c - b) - (b - a)            // change in delta = acceleration
    }

    private fun applyAggrNudge(
        hour:              Int,
        dow:               Int,
        inPostMealLockout: Boolean,
        aggressiveness:    Double,
        bg:                Double  = 0.0,
        targetMgdl:        Double  = 99.0,
        lowGuardMgdl:      Double  = 90.0,
        now:               Long    = System.currentTimeMillis(),
        isfPhysicsFired:   Boolean = false,
        basalPhysicsFired: Boolean = false,
        iob:               Double  = 0.0
    ) {
        // --- CALCULATE COOLDOWN ONCE AT THE TOP ---
        // This allows both the STFT and the Nudge Learner to share the same blindfold logic
        val msSincePenalty = if (lastPenaltyMs > 0L) now - lastPenaltyMs else Long.MAX_VALUE
        val cooldownActive = msSincePenalty <= AGGR_NUDGE_COOLDOWN_MS

        // -- Short-term fuel trim (? sensor analogy) ---------------------------
        if (!inPostMealLockout && bg > 0.0) {
            trimBgHistory.addLast(now to bg)
            while (trimBgHistory.isNotEmpty() && now - trimBgHistory.first().first > trimWindowMs)
                trimBgHistory.removeFirst()

            val windowReadings = trimWindowMs / (5 * 60_000L)  // expected readings in window
            if (trimBgHistory.size >= windowReadings.coerceAtLeast(6)) {
                val avgBg     = trimBgHistory.map { it.second }.average()

                // --- STFT POST-LOW BLINDFOLD ---
                // Prevent the trim from injecting permanent long-term nudges during rebound.
                // We only block "aboveBand" (adding insulin). "belowBand" (cutting insulin) stays active.
                val isLowRecovery = cooldownActive &&
                    (lastPenaltyReason.contains("low") || lastPenaltyReason.contains("rollercoaster"))
                val aboveBand = (avgBg > targetMgdl + TRIM_DEAD_BAND_MGDL) && !isLowRecovery
                val belowBand = avgBg < targetMgdl - TRIM_DEAD_BAND_MGDL

                // --- HUMAN "STEP AND WAIT" LOGIC ---
                val timeSinceLastAction = now - lastTrimActionMs
                val readyToReassess = timeSinceLastAction >= trimWindowMs

                when {
                    aboveBand -> {
                        // Not enough insulin — trim ceiling UP (more aggressive)
                        if (readyToReassess) {
                            val magnitude  = ((avgBg - targetMgdl) / targetMgdl).coerceIn(0.0, TRIM_MAX_STRENGTH)
                            trimStrength   = magnitude
                            trimDirection  = +1
                            if (!trimActive) { trimActive = true; trimStartMs = now }

                            val trimmedCeil = (aggrState.get(dow, hour) + magnitude * TRIM_CEIL_SCALE)
                                .coerceIn(AGGR_CEIL_MIN, TRIM_CEIL_MAX)
                            aggrState = aggrState.updatedDayOnly(dow, hour, trimmedCeil, 1.0)

                            val ltNudge = magnitude * TRIM_LONG_TERM_FRACTION
                            val d = dow.coerceIn(0, 6)
                            // Mutual exclusion: don't stack the FuelTrim long-term nudge on top of a physics-learner
                            // update in the same cycle (same intent as the aggrNudge block below).
                            if (!isfPhysicsFired)
                                isfState = isfState.updatedDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                            if (!basalPhysicsFired)
                                basalState = basalState.updatedDayOnly(dow, hour,
                                                                       (basalState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)

                            lastTrimActionMs = now // Reset the timer. We wait 90 mins from NOW before pushing harder.

                            aapsLogger.debug(LTag.APS,
                                             "FuelTrim[STEP +] h=$hour avgBg=${"%.1f".format(avgBg)} target=${"%.0f".format(targetMgdl)} " +
                                                 "mag=${"%.3f".format(magnitude)} ? ceil=${"%.3f".format(trimmedCeil)} ltNudge=${"%.4f".format(ltNudge)}")
                        } else {
                            aapsLogger.debug(LTag.APS, "FuelTrim[WAIT]: Holding extra insulin, waiting for peak (${timeSinceLastAction / 60_000}/${trimWindowMs / 60_000} mins)")
                        }
                    }
                    belowBand -> {
                        // Too much insulin — trim ceiling DOWN (less aggressive)
                        if (readyToReassess) {
                            val baseMagnitude = ((targetMgdl - avgBg) / targetMgdl).coerceIn(0.0, TRIM_MAX_STRENGTH)
                            val amplifier  = if (avgBg < lowGuardMgdl) 2.0 else 1.0
                            val magnitude  = (baseMagnitude * amplifier).coerceIn(0.0, TRIM_MAX_STRENGTH)
                            trimStrength   = -magnitude
                            trimDirection  = -1
                            if (!trimActive) { trimActive = true; trimStartMs = now }

                            val trimmedCeil = (aggrState.get(dow, hour) - magnitude * TRIM_CEIL_SCALE)
                                .coerceIn(TRIM_CEIL_MIN, AGGR_CEIL_MAX)
                            aggrState = aggrState.updatedDayOnly(dow, hour, trimmedCeil, 1.0)

                            val ltNudge = magnitude * TRIM_LONG_TERM_FRACTION
                            val d = dow.coerceIn(0, 6)
                            // Mutual exclusion: don't stack the FuelTrim long-term nudge on top of a physics-learner
                            // update in the same cycle (same intent as the aggrNudge block below).
                            if (!isfPhysicsFired)
                                isfState = isfState.updatedDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                            if (!basalPhysicsFired)
                                basalState = basalState.updatedDayOnly(dow, hour,
                                                                       (basalState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)

                            lastTrimActionMs = now // Reset the timer.

                            aapsLogger.debug(LTag.APS,
                                             "FuelTrim[STEP -] h=$hour avgBg=${"%.1f".format(avgBg)} target=${"%.0f".format(targetMgdl)} " +
                                                 "mag=${"%.3f".format(magnitude)} ? ceil=${"%.3f".format(trimmedCeil)} ltNudge=${"%.4f".format(ltNudge)}")
                        } else {
                            aapsLogger.debug(LTag.APS, "FuelTrim[WAIT]: Holding reduced insulin, waiting for peak (${timeSinceLastAction / 60_000}/${trimWindowMs / 60_000} mins)")
                        }
                    }
                    else -> {
                        // SAFETY VALVE: BG is back in band. Start decaying immediately so we don't overshoot.
                        if (trimActive) {
                            trimStrength *= TRIM_DECAY_RATE
                            if (kotlin.math.abs(trimStrength) < 0.005) {
                                trimActive = false; trimStrength = 0.0; trimDirection = 0
                                // Set timer to half a window ago rather than 0 — prevents
                                // immediate re-fire if BG briefly dips into band then exits.
                                // Requires at least half a peak window before next trim fires.
                                lastTrimActionMs = now - (trimWindowMs / 2)
                                aapsLogger.debug(LTag.APS, "FuelTrim: decayed to neutral at h=$hour")
                            }
                        }
                    }
                }
            }
        }

        var tooMuch      = !inPostMealLockout && aggressiveness < AGGR_NUDGE_THRESHOLD
        var notEnough    = !inPostMealLockout && aggressiveness > AGGR_NUDGE_SURPLUS

        // Always compute and log acceleration — even when ceiling is neutral —
        // so the debug panel shows current data regardless of nudge state.
        val accelAlways = computeAcceleration()
        lastAccelDebug = "accel=${"%.2f".format(accelAlways)} mgdl/5min | " +
            "${if (abs(accelAlways) <= ACCEL_DEAD_BAND_MGDL) "dead-band" else if (accelAlways > 0) "curving?" else "curving?"} | " +
            "history=${bgHistory.size} readings"

        // --- POST-LOW BLINDFOLD (Main Learner) ---
        // If we are recovering from a low, the rise is from rescue carbs/liver, NOT a basal deficit.
        // Block the learner from falsely increasing insulin (dropping ISF / raising basal).
        if (notEnough && cooldownActive && (lastPenaltyReason.contains("low") || lastPenaltyReason.contains("rollercoaster"))) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|Low Recovery Spike"
        }

        // --- NEGATIVE IOB + AT/BELOW TARGET GUARD ---
        // When IOB is meaningfully negative AND BG is at or below target, the loop is already
        // withholding insulin — that IS the correct response. The aggrCeiling history may say
        // "not enough insulin at this hour" but the live state contradicts it: negative IOB with
        // BG at/below target means any extra learned insulin would push BG lower, not higher.
        // Block the notEnough nudge; let the loop handle it and wait for BG to actually rise
        // before we conclude the profile needs more insulin.
        if (notEnough && iob < AGGR_NUDGE_NEG_IOB_GATE && bg <= targetMgdl + TRIM_DEAD_BAND_MGDL) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|NegIOB@target (iob=${"%.2f".format(iob)}U bg=${"%.1f".format(bg)})"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner aggrNudge[notEnough] blocked — negative IOB with BG at/below target " +
                                 "(iob=${"%.2f".format(iob)}U bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)})")
        }

        if (!tooMuch && !notEnough) {
            if (!trimActive) lastAggrNudgeStatus = "INACTIVE"
            else lastAggrNudgeStatus = "TRIM|${if (trimDirection > 0) "ACTIVE_HIGH" else "ACTIVE_LOW"}|${"%.1f".format(kotlin.math.abs(trimStrength) * 100)}%"
            return
        }

        // -- Attenuation during penalty cooldown -------------------------------
        val effectiveScale = when {
            msSincePenalty > AGGR_NUDGE_COOLDOWN_MS -> AGGR_NUDGE_SCALE                  // no recent penalty — full strength
            lastPenaltyWasFasting                   -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_FASTING  // fasting penalty — 35%
            else                                    -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_MEAL     // meal penalty — 15%
        }
        val cooldownNote   = if (cooldownActive) " [cooldown ${msSincePenalty / 60_000}min/${AGGR_NUDGE_COOLDOWN_MS / 60_000}min fasting=$lastPenaltyWasFasting]" else ""
        val deviation      = if (tooMuch) 1.0 - aggressiveness else aggressiveness - 1.0

        // -- Acceleration component — anticipatory signal that catches curves before velocity builds.
        val accel = computeAcceleration()

        // Acceleration aligns with deviation based on direction:
        // tooMuch (need less insulin): Curving DOWN (accel < 0) agrees, curving UP (accel > 0) contradicts.
        // notEnough (need more insulin): Curving UP (accel > 0) agrees, curving DOWN (accel < 0) contradicts.
        val accelNudge = when {
            abs(accel) <= ACCEL_DEAD_BAND_MGDL -> 0.0
            tooMuch -> -accel * ACCEL_PENALTY_FACTOR  // Crash (-5) -> returns +1.0 -> increases deviation
            else -> accel * ACCEL_PENALTY_FACTOR      // Rise (+5) -> returns +1.0 -> increases deviation
        }
        // Append nudge contribution to the debug string now that we know it
        if (accelNudge != 0.0) lastAccelDebug += " | nudge=${"%.3f".format(accelNudge)}"

        // Combine: accel adds to existing deviation, or provides standalone signal.
        // coerceAtLeast(0.0) ensures a contradicting accel can cancel but not reverse the nudge.
        val combinedDeviation = (deviation + accelNudge).coerceAtLeast(0.0)
        val nudge          = combinedDeviation * effectiveScale
        val dayName        = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        val deviationPct   = "${"%.0f".format(deviation * 100)}%"
        val d          = dow.coerceIn(0, 6)
        val prevIsfMult = isfState.days[d].get(hour)
        val prevBasMult = basalState.days[d].get(hour)

        // Capture session-start multipliers on first nudge of this hour/day combo.
        if (nudgeSessionHour != hour || nudgeSessionDow != dow) {
            nudgeSessionHour    = hour
            nudgeSessionDow     = dow
            nudgeSessionIsfMult = prevIsfMult
            nudgeSessionBasMult = prevBasMult
            nudgeSessionBlendedIsfMult = isfMultiplier(hour, dow)
            nudgeSessionBlendedBasMult = basalMultiplier(hour, dow)
        }

        // ISF nudge — skip if physics learner already updated ISF this cycle.
        // Both push isfState based on different signals and can disagree; mutual exclusion
        // prevents them cancelling each other out. Basal nudge still fires independently.
        val nudgedIsf = if (tooMuch)
            (prevIsfMult * (1.0 - nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        else
            (prevIsfMult * (1.0 + nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        if (!isfPhysicsFired) {
            isfState = isfState.updatedDayOnly(dow, hour, nudgedIsf, 1.0)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$cooldownNote " +
                                 "h=$hour day=$dayName ceil=${"%.3f".format(aggressiveness)} " +
                                 "deviation=${"%.3f".format(deviation)} nudge=${"%.4f".format(nudge)} ? mult=${"%.3f".format(isfState.days[d].get(hour))}")
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge] SKIPPED — physics learner already fired this cycle " +
                                 "(would have nudged ${"%.3f".format(prevIsfMult)}?${"%.3f".format(nudgedIsf)})")
        }

        // Basal nudge — skip if physics learner (drift/negIOB/predTrim) already fired this cycle.
        // Same mutual exclusion pattern as ISF: both signals push basalState from different
        // sources and can disagree cycle-to-cycle. Exclusive write keeps the signal clean.
        val nudgedBas = if (tooMuch)
            (prevBasMult * (1.0 - nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        else
            (prevBasMult * (1.0 + nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        if (!basalPhysicsFired) {
            basalState = basalState.updatedDayOnly(dow, hour, nudgedBas, 1.0)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$cooldownNote " +
                                 "h=$hour ceil=${"%.3f".format(aggressiveness)} deviation=${"%.3f".format(deviation)} " +
                                 "nudge=${"%.4f".format(nudge)} ? mult=${"%.3f".format(basalState.get(dow, hour))}")
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[aggrNudge] SKIPPED — physics learner already fired this cycle " +
                                 "(would have nudged ${"%.3f".format(prevBasMult)}?${"%.3f".format(nudgedBas)})")
        }

        // Store status — include whether physics learner overrode the nudge this cycle
        val isfApplied  = !isfPhysicsFired
        val basApplied  = !basalPhysicsFired
        val direction = if (tooMuch) "ACTIVE_LOW" else "ACTIVE_HIGH"
        lastAggrNudgeStatus = "$direction|$deviationPct|$dayName|$hour|" +
            "${"%.4f".format(nudgeSessionIsfMult)}|${"%.4f".format(isfState.days[d].get(hour))}|" +
            "${"%.4f".format(nudgeSessionBasMult)}|${"%.4f".format(basalState.days[d].get(hour))}|" +
            "${if (cooldownActive) "COOLDOWN" else "FULL"}|$lastPenaltyReason|" +
            "${if (isfApplied) "ISF_APPLIED" else "ISF_SKIPPED"}|${if (basApplied) "BAS_APPLIED" else "BAS_SKIPPED"}"
        // Only overwrite lastBasalSignal if the nudge actually applied — otherwise
        // preserve the drift/negIOB/predTrim signal message set by updateBasalLearner.
        if (!basalPhysicsFired) {
            lastBasalSignal = "AggrNudge[${if (tooMuch) "⬇️" else "⬆️"}]${if (cooldownActive) "[attenuated]" else ""}: ceil=${"%.3f".format(aggressiveness)} deviation=${"%.2f".format(deviation)} ? bas×${"%.3f".format(basalState.get(dow, hour))} isf×${"%.3f".format(isfState.days[d].get(hour))} (h=$hour)"
        }
    }

    // -- Basal learner ---------------------------------------------------------
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

    // Signal 4: sustained below-target + negative IOB window.
    // Stores (timestampMs, bg) pairs collected only when both conditions are met each cycle.
    // Window resets on any reading that breaks either condition, preventing stale signal mixing.
    private val subTargetNegIobWindow: ArrayDeque<Pair<Long, Double>> = ArrayDeque(SUB_TARGET_WINDOW_MAX)

    private fun updateBasalLearner(
        hour:              Int,
        dow:               Int,
        bg:                Double,
        now:               Long,
        basalIob:          Double,
        targetMgdl:        Double,
        inPostMealLockout: Boolean,
        aggressiveness:    Double,
        aggrNotEnough:     Boolean = false,
        lowGuardMgdl:      Double  = 90.0,
        activity:          Double  = 0.0,
        shortAvgDelta:     Double  = 0.0,
        profileIsfMgdl:    Double  = 0.0,
        isfLearningActive: Boolean = false   // true when updateIsfLearner fired this cycle
    ): Boolean {
        var signal0Fired = false
        var driftFired   = false
        var negIobFired  = false
        var predTrimFired = false

        // -- Signal 0: Per-cycle unexplained-delta basal learning -----------------
        // Mirror of ISF learning but for basal. During fasting when ISF is NOT learning
        // (activity below threshold, or negative activity), we instead attribute any BG
        // movement not explained by current IOB activity to basal error.
        //
        // Logic:
        //   expectedDeltaFromActivity = -activity * profileIsfMgdl * 5  (same as ISF learner)
        //   unexplainedDelta = actualDelta - expectedDeltaFromActivity
        //
        // If IOB activity is near-zero (loop quiet, basal dominant), actualDelta IS the
        // basal signal. If BG is rising with no IOB effect → basal too low → mult UP.
        // If BG falling with no IOB effect → basal too high → mult DOWN.
        //
        // Gated on:
        //   - ISF learner did NOT fire this cycle (mutually exclusive — if ISF is learning
        //     from activity, the same activity explains the delta; no residual for basal)
        //   - activity below ISF learning threshold (clean basal-dominant window)
        //   - basalIob near zero (loop not aggressively compensating — would mask signal)
        //   - BG in a reasonable range (above low guard, below high sanity gate)
        //   - Not in post-meal lockout
        if (!isfLearningActive &&
            !inPostMealLockout &&
            abs(activity) < MIN_ACTIVITY &&           // same gate that blocks ISF — basal is dominant
            abs(basalIob) < BASAL_S0_MAX_BASALIOB &&  // loop not aggressively pushing/cutting
            bg > lowGuardMgdl &&
            bg < BASAL_S0_HIGH_GATE_MGDL &&
            profileIsfMgdl > 0.0) {

            // With near-zero activity, expectedDelta ≈ 0. actualDelta is driven by basal.
            // Positive delta (BG rising) → basal too low → mult UP (more background insulin)
            // Negative delta (BG falling) → basal too high → mult DOWN (less background insulin)
            val unexplainedDelta = shortAvgDelta   // activity ≈ 0, so expected ≈ 0

            if (abs(unexplainedDelta) >= BASAL_S0_MIN_DELTA_MGDL) {
                // Normalise to a fractional adjustment, same clamp philosophy as ISF:
                // allow faster retreat (negative, BG falling too much) than advance (BG rising).
                val normAdj = (unexplainedDelta / BASAL_S0_SENSITIVITY).coerceIn(-1.0, 0.5)
                val newMult = (basalState.get(dow, hour) + normAdj).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

                val conf  = basalState.getConfidence(dow, hour)
                val alpha = (BASAL_ALPHA * (1.5 - conf)).coerceIn(BASAL_ALPHA * 0.5, BASAL_ALPHA * 1.5)

                basalState = basalState.updated(dow, hour, newMult, alpha)
                signal0Fired = true
                lastBasalSignal = "CyclicDelta: Δ=${"%.2f".format(unexplainedDelta)} normAdj=${"%.3f".format(normAdj)} → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[S0] h=$hour delta=${"%.2f".format(unexplainedDelta)} " +
                                     "normAdj=${"%.3f".format(normAdj)} activity=${"%.5f".format(activity)} " +
                                     "basalIob=${"%.2f".format(basalIob)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
            } else {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[S0] skip: delta=${"%.2f".format(unexplainedDelta)} < $BASAL_S0_MIN_DELTA_MGDL noise gate")
            }
        }

        // -- Signal 1: Drift-based learning -----------------------------------
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

                        // Confidence-weighted learning speed:
                        val conf  = basalState.getConfidence(dow, hour)
                        val alpha = (BASAL_ALPHA * (1.5 - conf)).coerceIn(BASAL_ALPHA * 0.5, BASAL_ALPHA * 1.5)

                        basalState = basalState.updated(dow, hour, newMult, alpha)
                        basalDriftWindow.clear()
                        driftFired = true
                        lastBasalSignal = "Drift: ${"%.1f".format(driftMgdlPerHr)} mgdlhr ? ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                        aapsLogger.debug(LTag.APS,
                                         "CircadianLearner Basal[drift] h=$hour drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr a=%.3f ? mult=${"%.3f".format(basalState.get(dow, hour))}")
                    }
                }
            } else {
                aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: elapsed=${"%.2f".format(elapsedHrs)}h < $BASAL_MIN_ELAPSED_HRS")
            }
        } else {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: only ${basalDriftWindow.size}/${BASAL_MIN_SAMPLES} samples")
        }

        // -- Signal 2: Negative IOB compensation signal ------------------------
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
            negIobFired = true
            lastBasalSignal = "NegIOB: BG ${"%.1f".format(bg)} < target ${"%.1f".format(targetMgdl)}, basalIOB=${"%.2f".format(basalIob)}U ? ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[negIOB] h=$hour bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} " +
                                 "basalIob=${"%.2f".format(basalIob)} belowTarget=${"%.1f".format(belowTargetMgdl)} ? mult=${"%.3f".format(basalState.get(dow, hour))}")
        }

        // -- Signal 3: Predictive basal trim (feed-forward) -------------------
        // Projects BG 60 min ahead using current drift rate. If projection is off
        // target by more than dead band, adjust basal NOW rather than waiting for
        // the full drift window to fire. Mutually exclusive with drift signal.
        // Uses softer alpha and smaller cap to avoid over-correcting early.
        //
        // Suppressed when aggrNudge notEnough is active: aggrNudge operates on a
        // multi-hour sustained pattern horizon while PredTrim operates on a 60-min
        // forward projection. When BG has been high for hours and is now falling,
        // PredTrim projects low and pushes basal DOWN at exactly the same time
        // aggrNudge is pushing it UP — they deadlock and basal never moves.
        // The aggrNudge signal is the higher-confidence signal in this scenario.
        if (aggrNotEnough) {
            lastPredTrimDebug = "suppressed — aggrNudge notEnough active (aggressiveness=${"%.3f".format(aggressiveness)})"
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal[predTrim] suppressed: aggrNudge notEnough active, aggressiveness=${"%.3f".format(aggressiveness)}")
        } else if (!driftFired && !inPostMealLockout) {
            val projectedBg = projectBg60min(targetMgdl)
            if (projectedBg == null) {
                lastPredTrimDebug = "waiting — ${basalDriftWindow.size}/$PRED_MIN_WINDOW_SAMPLES samples"
            } else {
                val projectedError = projectedBg - targetMgdl  // positive = projected high
                lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | err=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol | ${if (abs(projectedError) <= PRED_TRIM_DEAD_BAND_MGDL) "dead-band — no action" else "active"}"
                if (abs(projectedError) > PRED_TRIM_DEAD_BAND_MGDL) {
                    // Scale adjustment to projected error magnitude, capped at PRED_TRIM_MAX_ADJUST
                    val rawAdjust  = (projectedError / PRED_TRIM_SENSITIVITY).coerceIn(-PRED_TRIM_MAX_ADJUST, PRED_TRIM_MAX_ADJUST)
                    val adjustment = 1.0 + rawAdjust  // >1 = more basal needed, <1 = less
                    val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                    basalState = basalState.updated(dow, hour, newMult, BASAL_ALPHA * 0.5)  // softer alpha
                    predTrimFired = true
                    lastBasalSignal  = "PredTrim: proj=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol/60min ? ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                    lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | err=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol | " +
                        "rawAdj=${if (rawAdjust > 0) "+" else ""}${"%.3f".format(rawAdjust)} | mult=${"%.3f".format(basalState.get(dow, hour))} (EWMA a=0.03 — slow)"
                    aapsLogger.debug(LTag.APS,
                                     "CircadianLearner Basal[predTrim] h=$hour projectedBg=${"%.1f".format(projectedBg)} " +
                                         "target=${"%.1f".format(targetMgdl)} error=${"%.1f".format(projectedError)} rawAdjust=${"%.3f".format(rawAdjust)} ? mult=${"%.3f".format(basalState.get(dow, hour))}")
                }
            }
        }

        // -- Signal 4: Sustained sub-target + negative IOB — ISF and basal too strong -----------
        // The existing signals have a blind spot: drift shows near-zero (loop zero-temping keeps
        // BG flat), negIOB fires point-in-time on basal only, and predTrim is a forward projection.
        // None of them cleanly capture the pattern "I've been sitting below target for 45+ min with
        // the loop backed off — therefore my ISF and basal are too aggressive."
        //
        // This signal requires a sustained window of consistent evidence before moving anything,
        // so a single dip or brief excursion below target doesn't corrupt the learner.
        // Both ISF and basal mult are reduced — less aggressive ISF and lower background insulin.
        //
        // Mutually exclusive with drift, negIOB, and predTrim (all three are higher-confidence
        // signals when they fire; this is the fallback for when the loop compensation masks them).
        var subTargetFired = false
        val qualifyingCycle = !driftFired && !negIobFired && !predTrimFired &&
            !inPostMealLockout &&
            bg >= lowGuardMgdl &&                           // not a hard low — that's handled elsewhere
            bg < targetMgdl - SUB_TARGET_DEAD_BAND_MGDL && // meaningfully below target, not just touching it
            basalIob < SUB_TARGET_NEG_IOB_GATE              // loop is actively withholding insulin

        if (qualifyingCycle) {
            subTargetNegIobWindow.addLast(now to bg)
        } else {
            // Any cycle that breaks either condition resets the window — stale signal cleared
            if (subTargetNegIobWindow.isNotEmpty()) {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[subTarget] window reset — condition broke " +
                                     "(bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} basalIob=${"%.2f".format(basalIob)} " +
                                     "drift=$driftFired negIob=$negIobFired postMeal=$inPostMealLockout)")
                subTargetNegIobWindow.clear()
            }
        }

        // Prune stale entries regardless
        while (subTargetNegIobWindow.isNotEmpty() &&
            now - subTargetNegIobWindow.first().first > SUB_TARGET_WINDOW_MS)
            subTargetNegIobWindow.removeFirst()

        if (subTargetNegIobWindow.size >= SUB_TARGET_MIN_SAMPLES) {
            val oldest     = subTargetNegIobWindow.first()
            val newest     = subTargetNegIobWindow.last()
            val elapsedHrs = (newest.first - oldest.first) / 3_600_000.0
            if (elapsedHrs >= SUB_TARGET_MIN_ELAPSED_HRS) {
                val avgBg        = subTargetNegIobWindow.map { it.second }.average()
                val belowTarget  = targetMgdl - avgBg   // positive: how far below target on average
                // Scale reduction to how far below target — deeper = stronger signal.
                // Cap at SUB_TARGET_MAX_ADJUST to stay conservative per firing.
                val rawAdjust    = (belowTarget / SUB_TARGET_SENSITIVITY).coerceIn(0.0, SUB_TARGET_MAX_ADJUST)
                val adjustment   = 1.0 - rawAdjust      // <1.0 = reduce multiplier

                val newBasMult   = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                basalState = basalState.updated(dow, hour, newBasMult, BASAL_ALPHA * 0.5)

                // ISF mult DOWN → dosingISF = profileISF / lowerMult → dosingISF UP → less aggressive
                val newIsfMult   = (isfState.get(dow, hour) * adjustment).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                isfState = isfState.updated(dow, hour, newIsfMult, ISF_ALPHA * 0.5)

                subTargetNegIobWindow.clear()
                subTargetFired = true
                lastBasalSignal = "SubTarget: avg ${"%.1f".format(avgBg / 18.0)}mmol < target ${"%.1f".format(targetMgdl / 18.0)}mmol " +
                    "for ${"%.0f".format(elapsedHrs * 60)}min basalIob<${SUB_TARGET_NEG_IOB_GATE}U " +
                    "→ bas×${"%.3f".format(basalState.get(dow, hour))} isf×${"%.3f".format(isfState.get(dow, hour))} (h=$hour)"
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[subTarget] h=$hour avgBg=${"%.1f".format(avgBg)} " +
                                     "target=${"%.1f".format(targetMgdl)} elapsed=${"%.1f".format(elapsedHrs * 60)}min " +
                                     "rawAdj=${"%.3f".format(rawAdjust)} → bas×${"%.3f".format(basalState.get(dow, hour))} " +
                                     "isf×${"%.3f".format(isfState.get(dow, hour))}")
            }
        }

        return signal0Fired || driftFired || negIobFired || predTrimFired || subTargetFired
    }

    // -- Predictive basal trim helper -----------------------------------------
    // Projects BG 60 min ahead using current drift rate from basalDriftWindow.
    // Returns projected BG in mg/dL, or null if insufficient data.
    private fun projectBg60min(targetMgdl: Double): Double? {
        if (basalDriftWindow.size < PRED_MIN_WINDOW_SAMPLES) return null
        val oldest     = basalDriftWindow.first()
        val newest     = basalDriftWindow.last()
        val elapsedHrs = (newest.first - oldest.first) / 3_600_000.0
        if (elapsedHrs < 0.2) return null  // need at least 12 min of spread
        val driftMgdlPerHr = (newest.second - oldest.second) / elapsedHrs
        if (abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR) return null  // sanity gate
        return newest.second + (driftMgdlPerHr * PRED_TRIM_HORIZON_HRS)
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

        // -- Penalty signal 1: Rollercoaster ----------------------------------
        // Re-computed here (not cached from update()'s earlier call) so this
        // sees the current BG reading that was just added to bgHistory.
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
                                 "CircadianLearner Aggr h=$hour ROLLERCOASTER #$consecutiveRollercoasters detected fasting=$isFasting ? ceil=%.3f"
                                     .format(aggrState.get(dow, hour)))
            }
            return
        }

        // -- Penalty signal 2: Hard low — BG below low guard -----------------
        // Only penalise the fasting learner if this is a genuine fasting low.
        // If in meal mode, UAM, P/F, or post-meal lockout, the low is food-driven —
        // penalising the fasting profile would corrupt clean fasting data.
        if (bg < lowGuardMgdl && isFasting && !inPostMealLockout) {
            // All hard low penalties fire ONCE per low event (30-min gate).
            // Fire once, observe, let the loop and rebound window handle delivery.
            // Don't hammer ISF/basal/ceiling every 5 min while BG stays low.
            val nowMs = System.currentTimeMillis()
            val msSinceLastHardLow = if (lastHardLowPenaltyMs > 0L) nowMs - lastHardLowPenaltyMs else Long.MAX_VALUE
            val isNewLowEvent = msSinceLastHardLow > HARD_LOW_BASAL_GATE_MS
            lastHardLowPenaltyMs = nowMs
            lastPenaltyWasFasting = isFasting
            // Reset lowGuardPenaltyFired on each new low event so the ISF/basal penalty
            // fires once per episode (gated by HARD_LOW_BASAL_GATE_MS), not just once ever.
            // Without this reset, lowGuardPenaltyFired stays true after the first event
            // and subsequent new events skip the ISF/basal nudge entirely.
            if (isNewLowEvent) lowGuardPenaltyFired = false

            if (isNewLowEvent) {
                // Short term: ceiling cut — applied directly (alpha=1.0), not EWMA-softened.
                // Using AGGR_ALPHA_PENALTY=0.25 here only moved the ceiling by ~2.5% which
                // is invisible in the table and has no meaningful effect on dosing.
                // Direct write ensures the penalty is actually felt this cycle.
                val penalised = (currentCeil * AGGR_PENALTY_HARD_LOW).coerceAtLeast(AGGR_CEIL_MIN)
                aggrState = aggrState.updatedDayOnly(dow, hour, penalised, 1.0)
                // Basal: mult DOWN — less background insulin
                val d = dow.coerceIn(0, 6)
                val prevBasVal   = basalState.days[d].get(hour)
                val nudgedBasMult = (prevBasVal * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                basalState = basalState.updatedDayOnly(dow, hour, nudgedBasMult, 1.0)
                // ISF: mult DOWN ? dosingISF = profileISF / lowerMult ? dosingISF UP ? less aggressive ? less insulin ?
                val prevIsfVal   = isfState.days[d].get(hour)
                val nudgedIsfMult = (prevIsfVal * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                isfState = isfState.updatedDayOnly(dow, hour, nudgedIsfMult, 1.0)
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour HARD_LOW (new event) bg=${"%.1f".format(bg)} < guard=${"%.1f".format(lowGuardMgdl)} " +
                                     "fasting=$isFasting ? ceil=%.3f basal %.4f?%.4f isf %.4f?%.4f"
                                         .format(aggrState.get(dow, hour), prevBasVal, nudgedBasMult, prevIsfVal, nudgedIsfMult))
            } else {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour HARD_LOW (ongoing, gated) bg=${"%.1f".format(bg)} ? no further penalty until next event"
                                     .format())
            }
            return
        }

        // -- Penalty signal 3: Soft low approach ------------------------------
        // Same gate as hard low — only penalise fasting profile for fasting lows.
        val iob = iobArray.firstOrNull()?.iob ?: 0.0
        // Soft low approach: BG above low guard but falling fast with IOB on board.
        // Previous check was bg < lowGuardMgdl which is unreachable — hard low already
        // returns at line 697. Correct condition: within SOFT_LOW_APPROACH_MGDL above guard.
        val approachingLow = isFasting && !inPostMealLockout &&
            bg >= lowGuardMgdl && bg < lowGuardMgdl + SOFT_LOW_APPROACH_MGDL &&
            delta < SOFT_LOW_DELTA_MGDL && iob > SOFT_LOW_MIN_IOB
        if (approachingLow) {
            val penalised = (currentCeil * AGGR_PENALTY_SOFT_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            lastPenaltyMs   = System.currentTimeMillis()
            lastPenaltyWasFasting = isFasting
            lastPenaltyReason = "soft low approach"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour SOFT_LOW_APPROACH bg=$bg delta=$delta fasting=$isFasting ? ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
            return
        }

        // -- Recovery signal: good outcome ------------------------------------
        val stableNearTarget = abs(bg - targetMgdl) < STABLE_BAND_MGDL && abs(delta) < STABLE_DELTA_MGDL
        if (stableNearTarget && currentCeil < 1.0) {
            val recovered = (currentCeil + AGGR_RECOVERY_STEP).coerceAtMost(AGGR_CEIL_MAX)
            aggrState = aggrState.updated(dow, hour, recovered, AGGR_ALPHA_RECOVERY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour STABLE_RECOVERY ? ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
        }
    }

    // -- Rollercoaster detection -----------------------------------------------

    /**
     * Returns true if BG has crossed the target band 2+ times within the detection window.
     * Zero-crossing count on (bg - target) sign changes.
     */
    private fun detectRollercoaster(targetMgdl: Double, lowGuardMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false

        // Asymmetric thresholds — physiologically correct:
        // Low side = user's low guard (personal safety floor, not arbitrary dead band)
        // High side = target + dead band (ignores normal post-target wiggles)
        // Requires chronological alternation — Under?Above?Under or Above?Under?Above.
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

        // -- Find low period: first and last readings below low guard ---------
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
            // If BG was already trending down > 1.5 mg/dL per reading ? more likely real
            if (preAvgDelta < -COMPRESSION_PRE_TREND_GATE) return false
        }

        // 3. Rapid symmetric recovery — large positive deltas on the way back up
        val recoverySlice = bgHistory.filter { it.first in lowEnd..(lowEnd + 20 * 60_000L) }
        if (recoverySlice.size >= 2) {
            val maxRecoveryDelta = recoverySlice.zipWithNext()
                .maxOfOrNull { (a, b) -> b.second - a.second } ?: 0.0
            // If recovery is slow (small deltas) ? more likely carb-driven real rebound
            if (maxRecoveryDelta < COMPRESSION_MIN_RECOVERY_DELTA) return false
        }

        // 4. Baseline return — BG lands back near where it started
        val preLowReadings = bgHistory.filter { it.first in preDropStart until lowStart }.takeLast(3)
        val postRecoveryReadings = bgHistory.filter { it.first > lowEnd + 10 * 60_000L }.take(3)
        if (preLowReadings.size >= 2 && postRecoveryReadings.size >= 2) {
            val preLowAvg  = preLowReadings.map { it.second }.average()
            val postRecAvg = postRecoveryReadings.map { it.second }.average()
            // If BG lands more than ~1.1 mmol (20 mg/dL) from where it started ? real event
            if (kotlin.math.abs(postRecAvg - preLowAvg) > COMPRESSION_BASELINE_RETURN_GATE) return false
        }

        // All four criteria met — high confidence this is a compression artifact
        return true
    }

    // -- Persistence -----------------------------------------------------------

    private fun persist() {
        try {
            val json = JSONObject().apply {
                put("isf",   isfState.toJson())
                put("basal", basalState.toJson())
                put("aggr",  aggrState.toJson())
            }
            sp.edit { putString(StringKey.ApsSmartInsulinCircadianState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner persist failed: ${e.message}")
        }
    }

    private fun restore() {
        try {
            val raw = sp.getString(StringKey.ApsSmartInsulinCircadianState.key, StringKey.ApsSmartInsulinCircadianState.defaultValue)
            if (raw.isNullOrBlank()) return
            val json = JSONObject(raw)

            // -- Normal 7-day format -------------------------------------------
            isfState   = DayOfWeekCircadianState.fromJson(json.getJSONObject("isf"))
            basalState = DayOfWeekCircadianState.fromJson(json.getJSONObject("basal"))
            aggrState  = DayOfWeekCircadianState.fromJson(json.getJSONObject("aggr"))
            aapsLogger.debug(LTag.APS, "CircadianLearner restored (day-of-week)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner restore failed: ${e.message}")
        }
    }

    // -- Reset -----------------------------------------------------------------

    fun reset() {
        isfState   = DayOfWeekCircadianState()
        basalState = DayOfWeekCircadianState()
        aggrState  = DayOfWeekCircadianState()
        bgHistory.clear()
        sp.edit { putString(StringKey.ApsSmartInsulinCircadianState.key, "") }
        aapsLogger.debug(LTag.APS, "CircadianLearner reset")
    }

    fun resetIsf() {
        isfState = DayOfWeekCircadianState()
        persist()
        aapsLogger.debug(LTag.APS, "CircadianLearner ISF state reset — multiplier back to 1.0")
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

    // -- Status summary for tab UI ---------------------------------------------

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

    // -- Helpers ---------------------------------------------------------------

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun currentDow(): Int  = DayOfWeekCircadianState.currentDayOfWeek()

    /** Blend learned value toward default (1.0) based on confidence */
    private fun blend(learned: Double, default: Double, confidence: Double) =
        default + (learned - default) * confidence

    // -- Constants -------------------------------------------------------------

    companion object {
        // ISF learner
        private const val ISF_ALPHA              = 0.04   // slow EWMA — each sample moves ~8%
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
        private const val BASAL_DRIFT_SENSITIVITY  = 18.0            // 18 mg/dL/hr drift ? 1.0 multiplier adjustment (1 mmol/L/hr)
        private const val BASAL_WINDOW_MAX         = 30              // ring buffer max size

        // Signal 0: per-cycle unexplained-delta basal learning
        // Fires when ISF is NOT learning (activity < MIN_ACTIVITY) so they're mutually exclusive.
        // With near-zero IOB activity, BG delta is driven by basal — use it directly.
        // Same philosophy as ISF learning but for background insulin.
        private const val BASAL_S0_MAX_BASALIOB    = 0.20   // basalIob within ±0.20U — loop not aggressively compensating
        private const val BASAL_S0_HIGH_GATE_MGDL  = 180.0  // don't learn above 10 mmol — likely post-meal contamination
        private const val BASAL_S0_MIN_DELTA_MGDL  = 1.0    // noise gate — same as ISF MIN_EXPECTED_DELTA_MGDL
        private const val BASAL_S0_SENSITIVITY     = 5.0    // 5 mg/dL/5min unexplained delta → ~1.0 full mult adjustment

        // Predictive basal trim — forward-projects BG using current drift rate
        // and pre-emptively adjusts basal multiplier if projection is off target.
        // Fires INSTEAD of waiting for full drift window to confirm — faster convergence.
        private const val PRED_TRIM_HORIZON_HRS   = 1.0    // project 60 min ahead
        private const val PRED_TRIM_DEAD_BAND_MGDL = 9.0   // ~0.5 mmol — ignore small projected errors
        private const val PRED_TRIM_MAX_ADJUST     = 0.06  // cap at 6% per firing
        private const val PRED_TRIM_SENSITIVITY    = 36.0  // 2 mmol projected error ? full adjustment
        private const val PRED_MIN_WINDOW_SAMPLES  = 6     // ~30 min of data before projecting

        // Negative IOB compensation signal
        private const val BASAL_NEG_IOB_THRESHOLD   = -0.15          // basalIob must be at least this negative (U)
        private const val BASAL_NEG_IOB_MIN_SAMPLES = 6              // ~30 min of consistent signal before acting
        private const val BASAL_NEG_IOB_SENSITIVITY = 36.0           // 2 mmol below target ? max adjustment
        private const val BASAL_NEG_IOB_MAX_ADJUST  = 0.10           // cap at 10% reduction per firing

        // Signal 4: sustained sub-target + negative IOB — ISF and basal too strong
        private const val SUB_TARGET_WINDOW_MS        = 60 * 60_000L  // 60 min collection window
        private const val SUB_TARGET_WINDOW_MAX        = 20            // ring buffer max (~100 min at 5-min intervals)
        private const val SUB_TARGET_MIN_SAMPLES       = 9             // ~45 min of consistent signal required
        private const val SUB_TARGET_MIN_ELAPSED_HRS   = 0.6           // at least 36 min of spread in the window
        private const val SUB_TARGET_DEAD_BAND_MGDL    = 5.4           // ~0.3 mmol — must be this far below target to qualify
        private const val SUB_TARGET_NEG_IOB_GATE      = -0.15         // basalIob must be at least this negative (U)
        private const val SUB_TARGET_SENSITIVITY        = 18.0          // 1 mmol below target → ~5.5% reduction per firing
        private const val SUB_TARGET_MAX_ADJUST         = 0.08          // cap at 8% reduction per firing

        // Acceleration (2nd derivative) control — catches rising/falling curves early
        // before velocity (delta) builds up. Nips the rise before it becomes a correction problem.
        private const val ACCEL_DEAD_BAND_MGDL  = 0.15  // mg/dL change in delta — below this is noise
        private const val ACCEL_PENALTY_FACTOR  = 0.20  // how strongly upward accel feeds into nudge

        // Aggression nudge signal — fast-path basal/ISF correction driven by aggrCeiling.
        // aggrCeiling is a slow per-hour per-day EWMA — it won't drop below threshold
        // from a single bad cycle. It represents weeks of consistent pattern at that hour.
        // No additional cycle counting needed — the ceiling IS the confirmation filter.
        private const val AGGR_NUDGE_THRESHOLD    = 0.95           // ceiling below this ? too much insulin, nudge to reduce
        private const val AGGR_NUDGE_SURPLUS      = 1.05           // ceiling above this ? not enough insulin, nudge to increase
        private const val AGGR_NUDGE_SCALE        = 0.04           // 20% deviation ? 0.8% nudge per cycle
        private const val AGGR_NUDGE_COOLDOWN_MS  = 120 * 60_000L  // 120 min penalty cooldown window
        private const val AGGR_NUDGE_ATTN_FASTING = 0.35           // attenuated scale during cooldown — fasting penalty (more likely profile issue)
        private const val AGGR_NUDGE_ATTN_MEAL    = 0.15           // attenuated scale during cooldown — meal/post-meal penalty (less likely profile issue)
        private const val AGGR_NUDGE_NEG_IOB_GATE = -0.20          // IOB must be less negative than this to block notEnough nudge when at/below target

        // Aggressiveness ceiling
        private const val AGGR_ALPHA_PENALTY    = 0.25   // penalty applies quickly
        private const val AGGR_ALPHA_RECOVERY   = 0.04   // recovery is slow
        private const val AGGR_PENALTY_ROLLER       = 0.85   // 15% cut on rollercoaster
        private const val AGGR_PENALTY_HARD_LOW     = 0.90   // 10% short-term ceiling cut when BG crosses below low guard
        private const val AGGR_HARD_LOW_BASAL_NUDGE = 0.90   // 10% long-term basal nudge down on hard low (once per event)
        private const val HARD_LOW_BASAL_GATE_MS    = 30 * 60_000L  // basal nudge only fires once per 30-min low event
        private const val AGGR_PENALTY_SOFT_LOW     = 0.90   // 10% cut on soft low approach
        private const val AGGR_RECOVERY_STEP    = 0.01   // +1% per stable cycle
        private const val AGGR_CEIL_MIN         = 0.60
        private const val AGGR_CEIL_MAX         = 1.20
        private const val STABLE_BAND_MGDL      = 18.0   // ±1 mmol = stable
        private const val STABLE_DELTA_MGDL     = 1.5    // mg/dL per 5min = flat
        private const val SOFT_LOW_DELTA_MGDL   = -1.5   // falling at least this fast (mg/dL per 5min)
        private const val SOFT_LOW_APPROACH_MGDL = 18.0  // ~1 mmol above low guard — approaching but not yet below
        private const val SOFT_LOW_MIN_IOB      = 0.3    // must have meaningful IOB

        // Rollercoaster detection
        private const val ROLLER_WINDOW_MS          = 90 * 60 * 1000L   // 90 min window
        private const val ROLLER_CROSSING_THRESHOLD = 2                  // 2+ extreme swings = rollercoaster
        private const val ROLLER_DEAD_BAND_MGDL     = 18.0              // ~1.0 mmol above target to count as high crossing
        private const val MIN_HISTORY_FOR_ROLLER    = 6                  // need =6 readings (~30 min)
        private const val ROLLER_ESCALATION_RESET_MS = 2 * 60 * 60 * 1000L  // 2h gap = new episode, reset counter
        // Compression low heuristic constants
        private const val COMPRESSION_PRE_TREND_GATE       = 1.5        // mg/dL per reading — pre-drop falling faster than this ? likely real
        private const val COMPRESSION_MIN_RECOVERY_DELTA   = 10.0       // mg/dL per 5min — recovery must be fast to flag as compression (~0.55 mmol/5min)
        private const val COMPRESSION_BASELINE_RETURN_GATE = 20.0       // mg/dL — post-recovery must land within ~1.1 mmol of pre-drop baseline

        // Short-term fuel trim
        private const val TRIM_DEAD_BAND_MGDL      = 5.4    // ~0.3 mmol — must be this far from target to trim
        private const val TRIM_MAX_STRENGTH         = 0.20   // cap trim magnitude at 20%
        private const val TRIM_CEIL_SCALE           = 0.15   // ceiling shift per unit of trim magnitude
        private const val TRIM_CEIL_MAX             = 1.20   // ceiling upper bound from trim
        private const val TRIM_CEIL_MIN             = 0.80   // ceiling lower bound from trim
        private const val TRIM_LONG_TERM_FRACTION   = 0.50   // long-term nudge = 50% of trim magnitude
        private const val TRIM_DECAY_RATE           = 0.70   // trim decays by 30% each in-range cycle

        // General
        private const val COB_THRESHOLD_G = 5.0   // ignore cycles with active carbs
        private const val MAX_HISTORY     = 30     // ring buffer size
    }
}