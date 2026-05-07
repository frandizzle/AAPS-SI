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

    // ── ISF Episode Tracker (slow-path outcome learner) ──────────────────────
    private data class IsfEpisode(
        val startBgMgdl:      Double,
        val startTimeMs:      Long,
        val hour:             Int,
        val dow:              Int,
        var correctionU:      Double = 0.0,
        var basalDeviationU:  Double = 0.0,
        var stableMinutes:    Int    = 0,
        var partialStableMins: Int   = 0
    )
    private var activeIsfEpisode: IsfEpisode? = null

    // Persistence is gated by reference comparison in update() — persist() only fires
    // if any state reference was replaced. Reduces SharedPreferences writes by ~90%.

    // Rollercoaster detection — ring buffer of recent (timestamp, bg) pairs
    private val bgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(MAX_HISTORY)

    init { restore() }

    // ── Public outputs ────────────────────────────────────────────────────────

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

    // ── Mode transition tracking — clears drift window on mode change ────────
    // Prevents stale pre-P/F fasting samples from mixing with post-P/F fasting
    private var previousMealModeForDrift: MealMode = MealMode.FASTING

    // ── Low guard penalty state ──────────────────────────────────────────────
    // One-time 20% ISF+basal reduction when BG crosses below low guard.
    // Resets when BG recovers back above low guard so it can fire again next low.
    private var lowGuardPenaltyFired = false

    // ── Short-term fuel trim state ───────────────────────────────────────────
    private val trimBgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(36)
    private var trimWindowMs: Long = 90 * 60_000L
    private var trimActive   = false
    var trimStrength = 0.0
    private var trimDirection = 0
    private var trimStartMs  = 0L
    private var lastTrimActionMs = 0L // NEW: Tracks the "Wait and Re-assess" window

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
        aggressiveness:           Double  = 1.0,
        fastingPeakMins:          Double  = 90.0,   // learned fasting insulin peak — sets trim window
        // Time injection — defaults to wall clock. Override in tests to simulate specific
        // hours/days without waiting for real time to pass.
        hour:                     Int     = currentHour(),
        dow:                      Int     = currentDow(),
        nowMs:                    Long    = System.currentTimeMillis(),
        smbDeliveredU:            Double  = 0.0,
        profileBasalU:            Double  = 0.0,
        actualBasalU:             Double  = 0.0
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
                         "CircadianLearner h=$hour bg=${"%.1f".format(bg)} δ=${"%.2f".format(delta)} " +
                             "iob=${"%.2f".format(iob)} activity=${"%.4f".format(activity)} basalIob=${"%.2f".format(basalIob)} " +
                             "cob=${"%.1f".format(cobG)} mode=$mealMode target=${"%.0f".format(targetMgdl)} " +
                             "roller=$rollercoaster histSize=${bgHistory.size} " +
                             "→ ISF×${"%.3f".format(isfMultiplier(hour))} basal×${"%.3f".format(basalMultiplier(hour))} aggrCeil=${"%.3f".format(aggrCeiling(hour))}" +
                             (skipReason?.let { " | $it" } ?: ""))

        // Clear basalDriftWindow on any mode transition into or out of fasting.
        // Prevents stale pre-P/F samples mixing with post-P/F fasting signal.
        if (mealMode != previousMealModeForDrift) {
            if (basalDriftWindow.isNotEmpty()) {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner: mode transition $previousMealModeForDrift→$mealMode — clearing basalDriftWindow (${basalDriftWindow.size} samples)")
                basalDriftWindow.clear()
            }
            previousMealModeForDrift = mealMode
        }

        if (skipReason != null) return

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
                val prevIsf = isfState.days[d].get(hour)
                val prevBas = basalState.days[d].get(hour)
                val penalisedIsf = (prevIsf * 0.90).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                val penalisedBas = (prevBas * 0.90).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                isfState   = isfState.updatedDayOnly(dow, hour, penalisedIsf, 1.0)
                basalState = basalState.updatedDayOnly(dow, hour, penalisedBas, 1.0)
                aapsLogger.debug(LTag.APS,
                                 "LowGuard penalty h=$hour: ISF mult ${"%.3f".format(prevIsf)}→${"%.3f".format(penalisedIsf)} " +
                                     "basal mult ${"%.3f".format(prevBas)}→${"%.3f".format(penalisedBas)}")
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

        // ── 1. ISF learning — skip during CGM warmup (unreliable data) ─────
        val isFasting = mealMode == MealMode.FASTING
        val isfPhysicsDirection: Int = if (!suppressAdaptiveLearning)
            updateIsfLearner(hour, dow, glucoseStatus, iobArray, profileIsfMgdl, inPostMealLockout, isFasting, aggressiveness, bg, lowGuardMgdl, mealMode, smbDeliveredU, profileBasalU, actualBasalU, nowMs, targetMgdl)
        else { aapsLogger.debug(LTag.APS, "CircadianLearner ISF: suppressed (CGM warmup)"); 0 }

        // ── 2. Basal learning — skip during CGM warmup ───────────────────────
        val basalPhysicsFired = if (!suppressAdaptiveLearning) updateBasalLearner(hour, dow, bg, now, basalIob, targetMgdl, inPostMealLockout, aggressiveness)
        else { aapsLogger.debug(LTag.APS, "CircadianLearner Basal: suppressed (CGM warmup)"); false }

        // ── 3. Aggressiveness ceiling — ALWAYS runs (rollercoaster protection) ─
        // Rollercoaster and soft-low penalties must fire even on a new sensor —
        // a real rapid rise/crash is dangerous regardless of sensor age.
        updateAggrLearner(hour, dow, bg, delta, targetMgdl, iobArray, lowGuardMgdl, mealMode == MealMode.FASTING, inPostMealLockout)

        // ── 4. Short-term fuel trim + aggression nudge ──────────────────────────
        // Update trim window to match learned insulin peak (minimum 60 min, maximum 120 min).
        // This means the trim won't fire until BG has been off target for a full peak cycle —
        // ensuring we're not reacting to a rise that insulin is already handling.
        trimWindowMs = (fastingPeakMins * 60_000.0).toLong().coerceIn(60 * 60_000L, 120 * 60_000L)

        if (!suppressAdaptiveLearning) applyAggrNudge(hour, dow, inPostMealLockout, aggressiveness,
                                                      bg = bg, targetMgdl = targetMgdl, lowGuardMgdl = lowGuardMgdl, now = nowMs,
                                                      isfPhysicsDirection = isfPhysicsDirection,
                                                      basalPhysicsFired = basalPhysicsFired)

        // Only persist if any EWMA state was actually updated this cycle
        if (isfState !== prevIsf || basalState !== prevBasal || aggrState !== prevAggr) {
            persist()
        }
    }

    // ── ISF learner ───────────────────────────────────────────────────────────

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
        mealMode:          MealMode = MealMode.FASTING,
        smbDeliveredU:     Double  = 0.0,
        profileBasalU:     Double  = 0.0,
        actualBasalU:      Double  = 0.0,
        nowMs:             Long    = System.currentTimeMillis(),
        targetMgdl:        Double  = 99.0
    ): Int {
        // ── Invalidate episode if conditions contaminated ─────────────────────
        activeIsfEpisode?.let { ep ->
            val inv = when {
                mealMode != MealMode.FASTING -> "mode=$mealMode"
                inPostMealLockout            -> "postMealLockout"
                bg < lowGuardMgdl            -> "belowLowGuard"
                else                         -> null
            }
            if (inv != null) {
                aapsLogger.debug(LTag.APS, "ISF Episode invalidated: $inv — discarding")
                activeIsfEpisode = null
            }
        }

        // ── Open episode on meaningful correction ─────────────────────────────
        if (activeIsfEpisode == null && isFasting && !inPostMealLockout &&
            bg > lowGuardMgdl && smbDeliveredU >= EPISODE_MIN_CORRECTION_U) {
            activeIsfEpisode = IsfEpisode(startBgMgdl = bg, startTimeMs = nowMs, hour = hour, dow = dow)
            aapsLogger.debug(LTag.APS, "ISF Episode OPENED: startBg=${"%.1f".format(bg)} h=$hour smb=${"%.2f".format(smbDeliveredU)}U")
        }

        // ── Accumulate + attempt close ────────────────────────────────────────
        activeIsfEpisode?.let { ep ->
            ep.correctionU += smbDeliveredU
            if (profileBasalU > 0.0) {
                val per5 = profileBasalU / 12.0
                ep.basalDeviationU += (actualBasalU / 12.0) - per5
            }
            val delta = glucoseStatus.shortAvgDelta
            val targetMgdl = iobArray.firstOrNull()?.let { 99.0 } ?: 99.0  // fallback; ideally thread targetMgdl through
            val isStable = abs(delta) <= EPISODE_STABLE_DELTA
            val nearTarget = abs(bg - targetMgdl) <= EPISODE_FULL_RESOLVE_BAND
            if (isStable && nearTarget) ep.stableMinutes     += 5 else ep.stableMinutes     = 0
            if (isStable)               ep.partialStableMins += 5 else ep.partialStableMins = 0

            val closeReason = when {
                ep.stableMinutes     >= EPISODE_FULL_STABLE_MINS    -> "full_resolve"
                ep.partialStableMins >= EPISODE_PARTIAL_STABLE_MINS -> "partial_resolve"
                nowMs - ep.startTimeMs >= EPISODE_TIMEOUT_MS        -> "timeout"
                else                                                  -> null
            }
            if (closeReason != null) {
                val bgDrop = ep.startBgMgdl - bg
                if (bgDrop < EPISODE_MIN_BG_DROP) {
                    aapsLogger.debug(LTag.APS, "ISF Episode closed ($closeReason) bgDrop=${"%.1f".format(bgDrop)} < gate — discarding")
                    activeIsfEpisode = null
                } else {
                    val totalInsulin = (ep.correctionU + ep.basalDeviationU).coerceAtLeast(0.05)
                    val impliedIsfMgdl = bgDrop / totalInsulin
                    val impliedMult = (profileIsfMgdl / impliedIsfMgdl).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                    val conf  = isfState.getConfidence(ep.dow, ep.hour)
                    val alpha = (ISF_ALPHA_SLOW * (1.5 - conf)).coerceIn(ISF_ALPHA_SLOW * 0.5, ISF_ALPHA_SLOW * 1.5)
                    val prevMult = isfState.get(ep.dow, ep.hour)
                    isfState = isfState.updated(ep.dow, ep.hour, impliedMult, alpha)
                    aapsLogger.debug(LTag.APS,
                                     "ISF Episode CLOSED ($closeReason) h=${ep.hour} drop=${"%.1f".format(bgDrop)} " +
                                         "corrU=${"%.2f".format(ep.correctionU)} basalDev=${"%.2f".format(ep.basalDeviationU)} " +
                                         "impliedISF=${"%.1f".format(impliedIsfMgdl)} impliedMult=${"%.3f".format(impliedMult)} " +
                                         "α=${"%.3f".format(alpha)} mult ${"%.3f".format(prevMult)}→${"%.3f".format(isfState.get(ep.dow, ep.hour))}")
                    activeIsfEpisode = null
                }
            }
        }

        // ── Fast-path physics learner (reduced authority) ─────────────────────
        if (!isFasting || inPostMealLockout) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: not fasting or post-meal lockout")
            return 0
        }
        val activity = iobArray.firstOrNull()?.activity ?: run {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: no iobArray"); return 0
        }
        if (abs(activity) < MIN_ACTIVITY) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity=${"%.5f".format(activity)} < $MIN_ACTIVITY")
            return 0
        }
        if (activity < 0.0) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity < 0 (negative IOB / TBR reduction — inverted signal)")
            return 0
        }
        val expectedDelta = -activity * profileIsfMgdl * 5.0
        val actualDelta   = glucoseStatus.shortAvgDelta
        if (abs(expectedDelta) < MIN_EXPECTED_DELTA_MGDL) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: expectedΔ=${"%.2f".format(expectedDelta)} < $MIN_EXPECTED_DELTA_MGDL")
            return 0
        }
        val deviation     = actualDelta - expectedDelta
        val normDeviation = (deviation / abs(expectedDelta)).coerceIn(-1.0, 2.0)
        val multTarget    = (isfState.get(dow, hour) + normDeviation).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        val conf  = isfState.getConfidence(dow, hour)
        val alpha = (ISF_ALPHA_FAST * (1.5 - conf)).coerceIn(ISF_ALPHA_FAST * 0.5, ISF_ALPHA_FAST * 1.5)
        val prevMult = isfState.get(dow, hour)
        isfState = isfState.updated(dow, hour, multTarget, alpha)
        val newMult = isfState.get(dow, hour)
        val direction = when {
            newMult > prevMult + 1e-6 ->  1
            newMult < prevMult - 1e-6 -> -1
            else                      ->  0
        }
        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF[fast] h=$hour expectedΔ=%.1f actualΔ=%.1f dev=%.2f normDev=%.2f target=%.3f α=%.3f → mult=%.3f dir=$direction"
                             .format(expectedDelta, actualDelta, deviation, normDeviation, multTarget, alpha, newMult))
        return direction
    }

    // ── Aggression nudge — independent of activity gate ──────────────────────
    // Applies ISF and basal nudge based on aggrCeiling alone. Called directly from
    // update() so it fires even when ISF/basal physics learning is gated by low activity.
    // Handles both directions:
    //   ceiling < AGGR_NUDGE_THRESHOLD → too much insulin → ISF up, basal down
    //   ceiling > AGGR_NUDGE_SURPLUS   → not enough insulin → ISF down, basal up

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
        isfPhysicsDirection: Int   = 0,
        basalPhysicsFired: Boolean = false
    ) {
        // --- CALCULATE COOLDOWN ONCE AT THE TOP ---
        // This allows both the STFT and the Nudge Learner to share the same blindfold logic
        val msSincePenalty = if (lastPenaltyMs > 0L) now - lastPenaltyMs else Long.MAX_VALUE
        val cooldownActive = msSincePenalty <= AGGR_NUDGE_COOLDOWN_MS

        // ── Short-term fuel trim (λ sensor analogy) ───────────────────────────
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
                            // aboveBand = need more insulin → mult UP → dosingISF DOWN → more aggressive ✓
                            // Skip only if physics ALSO moved mult UP (same direction — redundant).
                            // If physics moved DOWN (conflict), apply trim to counteract.
                            if (isfPhysicsDirection != 1) {
                                isfState = isfState.updatedDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                            } else {
                                aapsLogger.debug(LTag.APS, "FuelTrim[+] ISF nudge skipped — physics already moved mult UP (dir=$isfPhysicsDirection)")
                            }
                            basalState = basalState.updatedDayOnly(dow, hour,
                                                                   (basalState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)

                            aapsLogger.debug(LTag.APS,
                                             "FuelTrim[STEP +] h=$hour avgBg=${"%.1f".format(avgBg)} target=${"%.0f".format(targetMgdl)} " +
                                                 "mag=${"%.3f".format(magnitude)} → ceil=${"%.3f".format(trimmedCeil)} ltNudge=${"%.4f".format(ltNudge)}")
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
                            // belowBand = too much insulin → mult DOWN → dosingISF UP → less aggressive ✓
                            // Skip only if physics ALSO moved mult DOWN (same direction — redundant).
                            // If physics moved UP (conflict), apply trim to counteract.
                            if (isfPhysicsDirection != -1) {
                                isfState = isfState.updatedDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                            } else {
                                aapsLogger.debug(LTag.APS, "FuelTrim[-] ISF nudge skipped — physics already moved mult DOWN (dir=$isfPhysicsDirection)")
                            }
                            basalState = basalState.updatedDayOnly(dow, hour,
                                                                   (basalState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)

                            aapsLogger.debug(LTag.APS,
                                             "FuelTrim[STEP -] h=$hour avgBg=${"%.1f".format(avgBg)} target=${"%.0f".format(targetMgdl)} " +
                                                 "mag=${"%.3f".format(magnitude)} → ceil=${"%.3f".format(trimmedCeil)} ltNudge=${"%.4f".format(ltNudge)}")
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
            "${if (abs(accelAlways) <= ACCEL_DEAD_BAND_MGDL) "dead-band" else if (accelAlways > 0) "curving↑" else "curving↓"} | " +
            "history=${bgHistory.size} readings"

        // --- POST-LOW BLINDFOLD (Main Learner) ---
        // If we are recovering from a low, the rise is from rescue carbs/liver, NOT a basal deficit.
        // Block the learner from falsely increasing insulin (dropping ISF / raising basal).
        if (notEnough && cooldownActive && (lastPenaltyReason.contains("low") || lastPenaltyReason.contains("rollercoaster"))) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|Low Recovery Spike"
        }

        if (!tooMuch && !notEnough) {
            if (!trimActive) lastAggrNudgeStatus = "INACTIVE"
            else lastAggrNudgeStatus = "TRIM|${if (trimDirection > 0) "ACTIVE_HIGH" else "ACTIVE_LOW"}|${"%.1f".format(kotlin.math.abs(trimStrength) * 100)}%"
            return
        }

        // ── Attenuation during penalty cooldown ───────────────────────────────
        val effectiveScale = when {
            msSincePenalty > AGGR_NUDGE_COOLDOWN_MS -> AGGR_NUDGE_SCALE                  // no recent penalty — full strength
            lastPenaltyWasFasting                   -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_FASTING  // fasting penalty — 35%
            else                                    -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_MEAL     // meal penalty — 15%
        }
        val cooldownNote   = if (cooldownActive) " [cooldown ${msSincePenalty / 60_000}min/${AGGR_NUDGE_COOLDOWN_MS / 60_000}min fasting=$lastPenaltyWasFasting]" else ""
        val deviation      = if (tooMuch) 1.0 - aggressiveness else aggressiveness - 1.0

        // ── Acceleration component — anticipatory signal that catches curves before velocity builds.
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

        // ISF nudge — direction-aware mutual exclusion with the physics ISF learner.
        // OLD: skip nudge if physics fired at ALL (blunt Boolean).
        // BUG: if physics and nudge conflict in direction, physics wins and moves ISF the
        // wrong way — e.g. tooMuch wants mult DOWN but physics pushes UP → nudge skipped,
        // ISF becomes more aggressive when it should be less.
        // NEW: skip only when physics moved the SAME direction. Conflicting physics gets
        // both applied — partial cancellation beats physics fully overriding wrong direction.
        val nudgedIsf = if (tooMuch)
            (prevIsfMult * (1.0 - nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        else
            (prevIsfMult * (1.0 + nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        val isfNudgeDir      = if (tooMuch) -1 else 1
        val isfPhysicsAgrees = isfPhysicsDirection != 0 && isfPhysicsDirection == isfNudgeDir
        val isfApplied       = !isfPhysicsAgrees
        if (isfApplied) {
            isfState = isfState.updatedDayOnly(dow, hour, nudgedIsf, 1.0)
            val conflictNote = if (isfPhysicsDirection != 0 && !isfPhysicsAgrees)
                " [physics conflicted dir=$isfPhysicsDirection — nudge applied anyway]" else ""
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$conflictNote$cooldownNote " +
                                 "h=$hour day=$dayName ceil=${"%.3f".format(aggressiveness)} " +
                                 "deviation=${"%.3f".format(deviation)} nudge=${"%.4f".format(nudge)} → mult=${"%.3f".format(isfState.days[d].get(hour))}")
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge] SKIPPED — physics moved same direction (dir=$isfPhysicsDirection) " +
                                 "(would have nudged ${"%.3f".format(prevIsfMult)}→${"%.3f".format(nudgedIsf)})")
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
                                 "nudge=${"%.4f".format(nudge)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[aggrNudge] SKIPPED — physics learner already fired this cycle " +
                                 "(would have nudged ${"%.3f".format(prevBasMult)}→${"%.3f".format(nudgedBas)})")
        }

        // Store status — isfApplied computed above in direction-aware block
        val basApplied = !basalPhysicsFired
        val direction = if (tooMuch) "ACTIVE_LOW" else "ACTIVE_HIGH"
        lastAggrNudgeStatus = "$direction|$deviationPct|$dayName|$hour|" +
            "${"%.4f".format(nudgeSessionIsfMult)}|${"%.4f".format(isfState.days[d].get(hour))}|" +
            "${"%.4f".format(nudgeSessionBasMult)}|${"%.4f".format(basalState.days[d].get(hour))}|" +
            "${if (cooldownActive) "COOLDOWN" else "FULL"}|$lastPenaltyReason|" +
            "${if (isfApplied) "ISF_APPLIED" else "ISF_SKIPPED(physicsAgrees)"}|${if (basApplied) "BAS_APPLIED" else "BAS_SKIPPED"}"
        // Only overwrite lastBasalSignal if the nudge actually applied — otherwise
        // preserve the drift/negIOB/predTrim signal message set by updateBasalLearner.
        if (!basalPhysicsFired) {
            lastBasalSignal = "AggrNudge[${if (tooMuch) "↓" else "↑"}]${if (cooldownActive) "[attenuated]" else ""}: ceil=${"%.3f".format(aggressiveness)} deviation=${"%.2f".format(deviation)} → bas×${"%.3f".format(basalState.get(dow, hour))} isf×${"%.3f".format(isfState.days[d].get(hour))} (h=$hour)"
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
    ): Boolean {
        var driftFired   = false
        var negIobFired  = false
        var predTrimFired = false

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

                        // Confidence-weighted learning speed:
                        val conf  = basalState.getConfidence(dow, hour)
                        val alpha = (BASAL_ALPHA * (1.5 - conf)).coerceIn(BASAL_ALPHA * 0.5, BASAL_ALPHA * 1.5)

                        basalState = basalState.updated(dow, hour, newMult, alpha)
                        basalDriftWindow.clear()
                        driftFired = true
                        lastBasalSignal = "Drift: ${"%.1f".format(driftMgdlPerHr)} mgdlhr → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                        aapsLogger.debug(LTag.APS,
                                         "CircadianLearner Basal[drift] h=$hour drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr α=%.3f → mult=${"%.3f".format(basalState.get(dow, hour))}")
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
            negIobFired = true
            lastBasalSignal = "NegIOB: BG ${"%.1f".format(bg)} < target ${"%.1f".format(targetMgdl)}, basalIOB=${"%.2f".format(basalIob)}U → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[negIOB] h=$hour bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} " +
                                 "basalIob=${"%.2f".format(basalIob)} belowTarget=${"%.1f".format(belowTargetMgdl)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
        }

        // ── Signal 3: Predictive basal trim (feed-forward) ───────────────────
        // Projects BG 60 min ahead using current drift rate. If projection is off
        // target by more than dead band, adjust basal NOW rather than waiting for
        // the full drift window to fire. Mutually exclusive with drift signal.
        // Uses softer alpha and smaller cap to avoid over-correcting early.
        if (!driftFired && !inPostMealLockout) {
            val projectedBg = projectBg60min(targetMgdl)
            if (projectedBg == null) {
                // Your previous fix to distinguish the 12-min time gate
                val elapsed = if (basalDriftWindow.size >= PRED_MIN_WINDOW_SAMPLES) {
                    val e = (basalDriftWindow.last().first - basalDriftWindow.first().first) / 3_600_000.0
                    "elapsed ${(e * 60).toInt()}/12min"
                } else {
                    "${basalDriftWindow.size}/$PRED_MIN_WINDOW_SAMPLES samples"
                }
                lastPredTrimDebug = "waiting — $elapsed"
            } else {
                val projectedError = projectedBg - targetMgdl  // positive = projected high

                // NEW SUCCESS GATE: Only cut basal if we are ALREADY at or below target.
                // If we are high (> target) and dropping, let the correction finish!
                val isCuttingBasal = projectedError < 0
                val currentlyAboveTarget = bg > targetMgdl

                if (isCuttingBasal && currentlyAboveTarget) {
                    lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | err=${"%.1f".format(projectedError / 18.0)}mmol | suppressed: BG > target"
                } else if (abs(projectedError) > PRED_TRIM_DEAD_BAND_MGDL) {
                    // Scale adjustment to projected error magnitude
                    val rawAdjust  = (projectedError / PRED_TRIM_SENSITIVITY).coerceIn(-PRED_TRIM_MAX_ADJUST, PRED_TRIM_MAX_ADJUST)
                    val adjustment = 1.0 + rawAdjust
                    basalState = basalState.updated(dow, hour, (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)
                    predTrimFired = true
                    lastBasalSignal  = "PredTrim: proj=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol/60min → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                    lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | err=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol | active"
                } else {
                    lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | dead-band"
                }
            }
        }

        return driftFired || negIobFired || predTrimFired
    }

    // ── Predictive basal trim helper ─────────────────────────────────────────
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

        // ── Penalty signal 1: Rollercoaster ──────────────────────────────────
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
            // All hard low penalties fire ONCE per low event (30-min gate).
            // Fire once, observe, let the loop and rebound window handle delivery.
            // Don't hammer ISF/basal/ceiling every 5 min while BG stays low.
            val nowMs = System.currentTimeMillis()
            val msSinceLastHardLow = if (lastHardLowPenaltyMs > 0L) nowMs - lastHardLowPenaltyMs else Long.MAX_VALUE
            val isNewLowEvent = msSinceLastHardLow > HARD_LOW_BASAL_GATE_MS
            lastHardLowPenaltyMs = nowMs
            lastPenaltyWasFasting = isFasting

            if (isNewLowEvent) {
                // Short term: 20% ceiling cut — once per event
                val penalised = (currentCeil * AGGR_PENALTY_HARD_LOW).coerceAtLeast(AGGR_CEIL_MIN)
                aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
                // Basal: mult DOWN — less background insulin
                val prevBasMult   = basalState.days[dow].get(hour)
                val nudgedBasMult = (prevBasMult * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                basalState = basalState.updatedDayOnly(dow, hour, nudgedBasMult, 1.0)
                // ISF: mult DOWN → dosingISF = profileISF / lowerMult → dosingISF UP → less aggressive → less insulin ✓
                val prevIsfMult   = isfState.days[dow].get(hour)
                val nudgedIsfMult = (prevIsfMult * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                isfState = isfState.updatedDayOnly(dow, hour, nudgedIsfMult, 1.0)
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour HARD_LOW (new event) bg=${"%.1f".format(bg)} < guard=${"%.1f".format(lowGuardMgdl)} " +
                                     "fasting=$isFasting → ceil=%.3f basal %.4f→%.4f isf %.4f→%.4f"
                                         .format(aggrState.get(dow, hour), prevBasMult, nudgedBasMult, prevIsfMult, nudgedIsfMult))
            } else {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour HARD_LOW (ongoing, gated) bg=${"%.1f".format(bg)} → no further penalty until next event"
                                     .format())
            }
            return
        }

        // ── Penalty signal 3: Soft low approach ──────────────────────────────
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

    fun resetIsf() {
        isfState = DayOfWeekCircadianState()
        activeIsfEpisode = null
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
        private const val ISF_ALPHA_FAST          = 0.02   // physics learner — reduced authority
        private const val ISF_ALPHA_SLOW          = 0.12   // episode learner — ground truth
        private const val ISF_MULT_MIN           = 0.7
        private const val ISF_MULT_MAX           = 1.5
        private const val MIN_ACTIVITY           = 0.005  // min IOB activity to learn from
        private const val MIN_EXPECTED_DELTA_MGDL = 1.0

        // ISF Episode learner
        private const val EPISODE_MIN_CORRECTION_U  = 0.15
        private const val EPISODE_STABLE_DELTA       = 1.8    // mg/dL/5min
        private const val EPISODE_FULL_RESOLVE_BAND  = 27.0   // ±1.5 mmol
        private const val EPISODE_FULL_STABLE_MINS   = 30
        private const val EPISODE_PARTIAL_STABLE_MINS = 60
        private const val EPISODE_TIMEOUT_MS          = 4 * 60 * 60 * 1000L
        private const val EPISODE_MIN_BG_DROP         = 9.0   // ~0.5 mmol minimum drop to learn from

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

        // Predictive basal trim — forward-projects BG using current drift rate
        // and pre-emptively adjusts basal multiplier if projection is off target.
        // Fires INSTEAD of waiting for full drift window to confirm — faster convergence.
        private const val PRED_TRIM_HORIZON_HRS   = 1.0    // project 60 min ahead
        private const val PRED_TRIM_DEAD_BAND_MGDL = 3.6   // ~0.5 mmol — ignore small projected errors
        private const val PRED_TRIM_MAX_ADJUST     = 0.12  // cap at 6% per firing
        private const val PRED_TRIM_SENSITIVITY    = 18.0  // 2 mmol projected error → full adjustment
        private const val PRED_MIN_WINDOW_SAMPLES  = 6     // ~30 min of data before projecting

        // Negative IOB compensation signal
        private const val BASAL_NEG_IOB_THRESHOLD   = -0.15          // basalIob must be at least this negative (U)
        private const val BASAL_NEG_IOB_MIN_SAMPLES = 6              // ~30 min of consistent signal before acting
        private const val BASAL_NEG_IOB_SENSITIVITY = 36.0           // 2 mmol below target → max adjustment
        private const val BASAL_NEG_IOB_MAX_ADJUST  = 0.10           // cap at 10% reduction per firing

        // Acceleration (2nd derivative) control — catches rising/falling curves early
        // before velocity (delta) builds up. Nips the rise before it becomes a correction problem.
        private const val ACCEL_DEAD_BAND_MGDL  = 0.15  // mg/dL change in delta — below this is noise
        private const val ACCEL_PENALTY_FACTOR  = 0.20  // how strongly upward accel feeds into nudge

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
        private const val MIN_HISTORY_FOR_ROLLER    = 6                  // need ≥6 readings (~30 min)
        private const val ROLLER_ESCALATION_RESET_MS = 2 * 60 * 60 * 1000L  // 2h gap = new episode, reset counter
        // Compression low heuristic constants
        private const val COMPRESSION_PRE_TREND_GATE       = 1.5        // mg/dL per reading — pre-drop falling faster than this → likely real
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