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

    // Last aggression nudge status for SI tab display
    var lastAggrNudgeStatus: String = "Inactive — no data yet"
        private set

    // Consecutive fasting cycles where aggression has been below nudge threshold
    // Both ISF and basal nudge require sustained signal before firing
    private var aggrNudgeConsecutiveCycles: Int = 0

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
        val rollercoaster = if (bgHistory.size >= MIN_HISTORY_FOR_ROLLER) detectRollercoaster(targetMgdl) else false

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

        // ── Track consecutive fasting cycles with aggression below nudge threshold ──
        // Reset counter if above threshold or not in a clean fasting state.
        // Both ISF and basal nudge read this counter to require sustained signal.
        if (!inPostMealLockout && aggressiveness < AGGR_NUDGE_THRESHOLD)
            aggrNudgeConsecutiveCycles++
        else
            aggrNudgeConsecutiveCycles = 0

        // ── 1. ISF learning — skip during CGM warmup (unreliable data) ─────
        if (!suppressAdaptiveLearning) updateIsfLearner(hour, dow, glucoseStatus, iobArray, profileIsfMgdl, inPostMealLockout, aggressiveness)
        else aapsLogger.debug(LTag.APS, "CircadianLearner ISF: suppressed (CGM warmup)")

        // ── 2. Basal learning — skip during CGM warmup ───────────────────────
        if (!suppressAdaptiveLearning) updateBasalLearner(hour, dow, bg, now, basalIob, targetMgdl, inPostMealLockout, aggressiveness)
        else aapsLogger.debug(LTag.APS, "CircadianLearner Basal: suppressed (CGM warmup)")

        // ── 3. Aggressiveness ceiling — ALWAYS runs (rollercoaster protection) ─
        // Rollercoaster and soft-low penalties must fire even on a new sensor —
        // a real rapid rise/crash is dangerous regardless of sensor age.
        updateAggrLearner(hour, dow, bg, delta, targetMgdl, iobArray, lowGuardMgdl)

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
    ) {
        val activity = iobArray.firstOrNull()?.activity ?: run {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: no iobArray"); return
        }
        if (abs(activity) < MIN_ACTIVITY) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity=${"%.5f".format(activity)} < $MIN_ACTIVITY")
            return
        }

        // Expected delta from IOB activity: bgi = -(activity × ISF × 5min)
        val expectedDelta = -(activity * profileIsfMgdl * 5.0)
        val actualDelta   = glucoseStatus.shortAvgDelta

        if (abs(expectedDelta) < MIN_EXPECTED_DELTA_MGDL) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: expectedΔ=${"%.2f".format(expectedDelta)} < $MIN_EXPECTED_DELTA_MGDL")
            return
        }

        val deviation     = actualDelta - expectedDelta
        val normDeviation = (deviation / abs(expectedDelta)).coerceIn(-1.0, 2.0)
        val multTarget    = (isfState.get(dow, hour) + normDeviation).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        val newMult       = (isfState.get(dow, hour) * (1.0 - ISF_ALPHA) + multTarget * ISF_ALPHA)
            .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        isfState = isfState.updated(dow, hour, newMult, ISF_ALPHA)

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF h=$hour expectedΔ=%.1f actualΔ=%.1f dev=%.2f normDev=%.2f target=%.3f → mult=%.3f"
                             .format(expectedDelta, actualDelta, deviation, normDeviation, multTarget, isfState.get(dow, hour)))

        // ── ISF aggression nudge ──────────────────────────────────────────────
        // Aggression sustained below threshold for AGGR_NUDGE_MIN_CYCLES consecutive
        // fasting cycles → ISF multiplier nudged UP (higher ISF mult = less insulin).
        // Not active during post-meal lockout — IOB pattern unreliable.
        if (!inPostMealLockout &&
            aggressiveness < AGGR_NUDGE_THRESHOLD &&
            aggrNudgeConsecutiveCycles >= AGGR_NUDGE_MIN_CYCLES) {
            val deficit  = 1.0 - aggressiveness
            val nudge    = deficit * AGGR_NUDGE_SCALE
            val nudgedMult = (isfState.get(dow, hour) * (1.0 + nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
            isfState = isfState.updated(dow, hour, nudgedMult, ISF_ALPHA * 0.3)
            lastAggrNudgeStatus = "Active — aggr=${"%.3f".format(aggressiveness)} " +
                "(deficit=${"%.0f".format(deficit * 100)}%, ${aggrNudgeConsecutiveCycles} cycles) → " +
                "ISF×↑${"%.3f".format(isfState.get(dow, hour))} " +
                "bas×↓${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge] h=$hour aggressiveness=${"%.3f".format(aggressiveness)} " +
                                 "cycles=$aggrNudgeConsecutiveCycles deficit=${"%.3f".format(deficit)} nudge=${"%.4f".format(nudge)} → mult=${"%.3f".format(isfState.get(dow, hour))}")
        } else if (aggressiveness >= AGGR_NUDGE_THRESHOLD) {
            lastAggrNudgeStatus = "Inactive — aggr=${"%.3f".format(aggressiveness)} " +
                "(threshold ${"%.2f".format(AGGR_NUDGE_THRESHOLD)})"
        } else {
            // Below threshold but not enough cycles yet
            lastAggrNudgeStatus = "Waiting — aggr=${"%.3f".format(aggressiveness)} " +
                "($aggrNudgeConsecutiveCycles/${AGGR_NUDGE_MIN_CYCLES} cycles)"
        }
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
                        lastBasalSignal = "Drift: ${"%.1f".format(driftMgdlPerHr)} mg/dL/hr → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
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

        // ── Signal 3: Aggression nudge signal ─────────────────────────────────
        // Requires AGGR_NUDGE_MIN_CYCLES consecutive fasting cycles below threshold
        // to confirm a sustained pattern rather than a single bad night.
        if (!inPostMealLockout &&
            aggressiveness < AGGR_NUDGE_THRESHOLD &&
            aggrNudgeConsecutiveCycles >= AGGR_NUDGE_MIN_CYCLES) {
            val deficit    = 1.0 - aggressiveness
            val nudge      = deficit * AGGR_NUDGE_SCALE
            val newMult    = (basalState.get(dow, hour) * (1.0 - nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
            basalState = basalState.updated(dow, hour, newMult, BASAL_ALPHA * 0.3)
            lastBasalSignal = "AggrNudge: aggr=${"%.3f".format(aggressiveness)} deficit=${"%.2f".format(deficit)} " +
                "(${aggrNudgeConsecutiveCycles} cycles) → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[aggrNudge] h=$hour aggressiveness=${"%.3f".format(aggressiveness)} " +
                                 "cycles=$aggrNudgeConsecutiveCycles deficit=${"%.3f".format(deficit)} nudge=${"%.4f".format(nudge)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
        }
    }

    // ── Aggressiveness ceiling learner ────────────────────────────────────────

    private fun updateAggrLearner(
        hour:         Int,
        dow:          Int,
        bg:           Double,
        delta:        Double,
        targetMgdl:   Double,
        iobArray:     Array<IobTotal>,
        lowGuardMgdl: Double
    ) {
        val currentCeil = aggrState.get(dow, hour)

        // ── Penalty signal 1: Rollercoaster ──────────────────────────────────
        val rollercoaster = detectRollercoaster(targetMgdl)
        if (rollercoaster) {
            val penalised = (currentCeil * AGGR_PENALTY_ROLLER).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour ROLLERCOASTER detected → ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
            return
        }

        // ── Penalty signal 2: Soft low approach ──────────────────────────────
        // BG heading toward user's configured low guard with negative delta and IOB on board
        val iob = iobArray.firstOrNull()?.iob ?: 0.0
        val approachingLow = bg < lowGuardMgdl && delta < SOFT_LOW_DELTA_MGDL && iob > SOFT_LOW_MIN_IOB
        if (approachingLow) {
            val penalised = (currentCeil * AGGR_PENALTY_SOFT_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour SOFT_LOW_APPROACH bg=$bg delta=$delta → ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
            return
        }

        // ── Recovery signal: good outcome ────────────────────────────────────
        // BG stable near target → gently recover ceiling toward 1.0
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
    private fun detectRollercoaster(targetMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false
        var crossings = 0
        var lastSign  = 0
        for ((_, bg) in bgHistory) {
            val s = (bg - targetMgdl).sign.toInt()
            if (s != 0 && lastSign != 0 && s != lastSign) crossings++
            if (s != 0) lastSign = s
        }
        return crossings >= ROLLER_CROSSING_THRESHOLD
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

        // Aggression nudge signal — fast-path basal/ISF correction when aggression is sustained low
        // Threshold at 0.80 = 20%+ reduction sustained for multiple cycles before nudging.
        // Single bad nights don't trigger — requires AGGR_NUDGE_MIN_CYCLES consecutive fasting
        // cycles below threshold (~30 min at 5min loop interval) to confirm pattern.
        private const val AGGR_NUDGE_THRESHOLD    = 0.80           // aggression below this triggers nudge
        private const val AGGR_NUDGE_SCALE        = 0.02           // 20% deficit → 0.4% nudge per cycle
        private const val AGGR_NUDGE_MIN_CYCLES   = 6              // ~30 min of sustained below-threshold before nudging

        // Aggressiveness ceiling
        private const val AGGR_ALPHA_PENALTY    = 0.25   // penalty applies quickly
        private const val AGGR_ALPHA_RECOVERY   = 0.04   // recovery is slow
        private const val AGGR_PENALTY_ROLLER   = 0.85   // 15% cut on rollercoaster
        private const val AGGR_PENALTY_SOFT_LOW = 0.90   // 10% cut on soft low approach
        private const val AGGR_RECOVERY_STEP    = 0.01   // +1% per stable cycle
        private const val AGGR_CEIL_MIN         = 0.60
        private const val AGGR_CEIL_MAX         = 1.20
        private const val STABLE_BAND_MGDL      = 18.0   // ±1 mmol = stable
        private const val STABLE_DELTA_MGDL     = 1.5    // mg/dL per 5min = flat
        private const val SOFT_LOW_DELTA_MGDL   = -1.5   // falling at least this fast (mg/dL per 5min)
        private const val SOFT_LOW_MIN_IOB      = 0.3    // must have meaningful IOB

        // Rollercoaster detection
        private const val ROLLER_WINDOW_MS          = 90 * 60 * 1000L   // 90 min window
        private const val ROLLER_CROSSING_THRESHOLD = 2                  // 2+ crossings = rollercoaster
        private const val MIN_HISTORY_FOR_ROLLER    = 6                  // need ≥6 readings (~30 min)

        // General
        private const val COB_THRESHOLD_G = 5.0   // ignore cycles with active carbs
        private const val MAX_HISTORY     = 30     // ring buffer size
    }
}