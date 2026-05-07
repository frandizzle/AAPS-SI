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
 * 1. ISF multiplier    — learns deviation patterns per hour → scales dosingISF up/down
 * 2. Basal multiplier  — learns fasting BG drift per hour  → replaces flat BasalLearner
 * 3. Aggression ceiling — detects rollercoasters + soft-low-approach per hour → caps aggressiveness
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
        val startBgMgdl:       Double,
        val startTimeMs:       Long,
        val hour:              Int,
        val dow:               Int,
        var correctionU:       Double = 0.0,  // cumulative SMBs/boluses during episode
        var basalDeviationU:   Double = 0.0,  // cumulative (delivered - profile) basal
        var stableMinutes:     Int    = 0,    // consecutive stable readings at/near target
        var partialStableMins: Int    = 0     // consecutive stable readings anywhere
    )

    private var activeIsfEpisode: IsfEpisode? = null
    private var lastEpisodeBgMgdl: Double = 0.0  // tracks BG each cycle for delta accounting

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
     * Global aggressiveness should be clamped to min(globalAggr, aggrCeiling) */
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
        smbDeliveredU:            Double  = 0.0,    // SMBs delivered this cycle
        profileBasalU:            Double  = 0.0,    // profile basal U/hr
        actualBasalU:             Double  = 0.0,    // actual delivered basal this cycle
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
            updateIsfLearner(hour, dow, glucoseStatus, iobArray, profileIsfMgdl, inPostMealLockout, isFasting, aggressiveness, bg, lowGuardMgdl, mealMode, smbDeliveredU, profileBasalU, actualBasalU, targetMgdl, nowMs)
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

        // Only persist if any EWMA state was actually updated this cycle or active episode changed
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
        bg:                Double = 0.0,
        lowGuardMgdl:      Double = 90.0,
        mealMode:          MealMode = MealMode.FASTING,
        smbDeliveredU:     Double = 0.0,   // SMBs delivered this cycle
        profileBasalU:     Double = 0.0,   // profile basal U/hr
        actualBasalU:      Double = 0.0,   // actual delivered basal this cycle
        targetMgdl:        Double = 99.0,
        nowMs:             Long   = System.currentTimeMillis()
    ): Int {
        val delta    = glucoseStatus.shortAvgDelta
        val activity = iobArray.firstOrNull()?.activity ?: 0.0

        // ── Invalidation — kill active episode if conditions are contaminated ────
        activeIsfEpisode?.let { ep ->
            val shouldInvalidate = when {
                mealMode != MealMode.FASTING -> "mode=${mealMode}"
                inPostMealLockout            -> "postMealLockout"
                bg < lowGuardMgdl            -> "belowLowGuard"
                else                         -> null
            }
            if (shouldInvalidate != null) {
                aapsLogger.debug(LTag.APS, "ISF Episode invalidated: $shouldInvalidate — discarding")
                activeIsfEpisode = null
                lastEpisodeBgMgdl = 0.0
                return 0
            }
        }

        // ── Open a new episode when a meaningful correction fires ────────────────
        if (activeIsfEpisode == null &&
            isFasting &&
            !inPostMealLockout &&
            bg > lowGuardMgdl &&
            smbDeliveredU >= EPISODE_MIN_CORRECTION_U) {
            activeIsfEpisode = IsfEpisode(
                startBgMgdl = bg,
                startTimeMs = nowMs,
                hour        = hour,
                dow         = dow
            )
            lastEpisodeBgMgdl = bg
            aapsLogger.debug(LTag.APS,
                             "ISF Episode OPENED: startBg=${"%.1f".format(bg)} h=$hour smb=${"%.2f".format(smbDeliveredU)}U")
        }

        // ── Accumulate episode state each cycle ──────────────────────────────────
        activeIsfEpisode?.let { ep ->
            // Accumulate correction insulin (SMBs this cycle)
            ep.correctionU += smbDeliveredU
            // Accumulate basal deviation: (actual - profile) per 5-min interval
            if (profileBasalU > 0.0) {
                val profilePer5min = profileBasalU / 12.0
                val actualPer5min  = actualBasalU  / 12.0
                ep.basalDeviationU += (actualPer5min - profilePer5min)
            }
            // Track stability
            val isStable = abs(delta) <= EPISODE_STABLE_DELTA
            val isNearTarget = abs(bg - targetMgdl) <= EPISODE_FULL_RESOLVE_BAND
            if (isStable && isNearTarget) ep.stableMinutes     += 5 else ep.stableMinutes     = 0
            if (isStable)                 ep.partialStableMins += 5 else ep.partialStableMins = 0
            val elapsed = nowMs - ep.startTimeMs

            // ── Attempt episode close ────────────────────────────────────────────
            val closeReason: String? = when {
                ep.stableMinutes     >= EPISODE_FULL_STABLE_MINS   -> "full_resolve"
                ep.partialStableMins >= EPISODE_PARTIAL_STABLE_MINS -> "partial_resolve"
                elapsed              >= EPISODE_TIMEOUT_MS          -> "timeout"
                else                                                 -> null
            }

            if (closeReason != null) {
                val bgDrop = ep.startBgMgdl - bg  // positive = dropped
                if (bgDrop < EPISODE_MIN_BG_DROP) {
                    aapsLogger.debug(LTag.APS,
                                     "ISF Episode closed ($closeReason) but bgDrop=${"%.1f".format(bgDrop)} < gate — discarding")
                    activeIsfEpisode = null
                    lastEpisodeBgMgdl = 0.0
                } else {
                    // Basal-corrected total effective insulin
                    val totalInsulin = (ep.correctionU + ep.basalDeviationU).coerceAtLeast(0.05)
                    val impliedIsfMgdl = bgDrop / totalInsulin
                    // Convert to multiplier: mult = profileISF / impliedISF
                    val impliedMult = (profileIsfMgdl / impliedIsfMgdl)
                        .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                    val conf  = isfState.getConfidence(ep.dow, ep.hour)
                    val alpha = (ISF_ALPHA_SLOW * (1.5 - conf)).coerceIn(ISF_ALPHA_SLOW * 0.5, ISF_ALPHA_SLOW * 1.5)
                    val prevMult = isfState.get(ep.dow, ep.hour)
                    isfState = isfState.updated(ep.dow, ep.hour, impliedMult, alpha)
                    val newMult = isfState.get(ep.dow, ep.hour)

                    aapsLogger.debug(LTag.APS,
                                     "ISF Episode CLOSED ($closeReason) h=${ep.hour} " +
                                         "drop=${"%.1f".format(bgDrop)}mg/dL correctionU=${"%.2f".format(ep.correctionU)} " +
                                         "basalDev=${"%.2f".format(ep.basalDeviationU)} totalU=${"%.2f".format(totalInsulin)} " +
                                         "impliedISF=${"%.1f".format(impliedIsfMgdl)} profileISF=${"%.1f".format(profileIsfMgdl)} " +
                                         "impliedMult=${"%.3f".format(impliedMult)} α=${"%.3f".format(alpha)} " +
                                         "mult ${"%.3f".format(prevMult)}→${"%.3f".format(newMult)}")
                    activeIsfEpisode = null
                    lastEpisodeBgMgdl = 0.0
                }
            }
        }

        // ── Fast-path physics learner (reduced authority) ────────────────────────
        if (!isFasting || inPostMealLockout) return 0
        if (abs(activity) < MIN_ACTIVITY || activity < 0.0) return 0

        val expectedDelta = -activity * profileIsfMgdl * 5.0
        val actualDelta   = glucoseStatus.shortAvgDelta

        if (abs(expectedDelta) < MIN_EXPECTED_DELTA_MGDL) return 0

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
                         "CircadianLearner ISF[fast] h=$hour expectedΔ=${"%.1f".format(expectedDelta)} " +
                             "actualΔ=${"%.1f".format(actualDelta)} normDev=${"%.2f".format(normDeviation)} " +
                             "target=${"%.3f".format(multTarget)} α=${"%.3f".format(alpha)} → mult=${"%.3f".format(newMult)} dir=$direction")

        return direction
    }

    // ── Aggression nudge logic ────────────────────────────────────────────────

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
        val msSincePenalty = if (lastPenaltyMs > 0L) now - lastPenaltyMs else Long.MAX_VALUE
        val cooldownActive = msSincePenalty <= AGGR_NUDGE_COOLDOWN_MS

        // ── Short-term fuel trim (λ sensor analogy) ───────────────────────────
        if (!inPostMealLockout && bg > 0.0) {
            trimBgHistory.addLast(now to bg)
            while (trimBgHistory.isNotEmpty() && now - trimBgHistory.first().first > trimWindowMs)
                trimBgHistory.removeFirst()

            val windowReadings = trimWindowMs / (5 * 60_000L)
            if (trimBgHistory.size >= windowReadings.coerceAtLeast(6)) {
                val avgBg     = trimBgHistory.map { it.second }.average()
                val isLowRecovery = cooldownActive &&
                    (lastPenaltyReason.contains("low") || lastPenaltyReason.contains("rollercoaster"))
                val aboveBand = (avgBg > targetMgdl + TRIM_DEAD_BAND_MGDL) && !isLowRecovery
                val belowBand = avgBg < targetMgdl - TRIM_DEAD_BAND_MGDL
                val timeSinceLastAction = now - lastTrimActionMs
                val readyToReassess = timeSinceLastAction >= trimWindowMs

                when {
                    aboveBand -> {
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
                            if (isfPhysicsDirection != 1) {
                                isfState = isfState.updatedDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                            }
                            basalState = basalState.updatedDayOnly(dow, hour,
                                                                   (basalState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)
                            lastTrimActionMs = now
                        }
                    }
                    belowBand -> {
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
                            if (isfPhysicsDirection != -1) {
                                isfState = isfState.updatedDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                            }
                            basalState = basalState.updatedDayOnly(dow, hour,
                                                                   (basalState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)
                            lastTrimActionMs = now
                        }
                    }
                    else -> {
                        if (trimActive) {
                            trimStrength *= TRIM_DECAY_RATE
                            if (kotlin.math.abs(trimStrength) < 0.005) {
                                trimActive = false; trimStrength = 0.0; trimDirection = 0
                                lastTrimActionMs = now - (trimWindowMs / 2)
                            }
                        }
                    }
                }
            }
        }

        var tooMuch      = !inPostMealLockout && aggressiveness < AGGR_NUDGE_THRESHOLD
        var notEnough    = !inPostMealLockout && aggressiveness > AGGR_NUDGE_SURPLUS

        val accelAlways = computeAcceleration()
        lastAccelDebug = "accel=${"%.2f".format(accelAlways)} mgdl/5min | " +
            "${if (abs(accelAlways) <= ACCEL_DEAD_BAND_MGDL) "dead-band" else if (accelAlways > 0) "curving↑" else "curving↓"} | " +
            "history=${bgHistory.size} readings"

        if (notEnough && cooldownActive && (lastPenaltyReason.contains("low") || lastPenaltyReason.contains("rollercoaster"))) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|Low Recovery Spike"
        }

        if (!tooMuch && !notEnough) {
            if (!trimActive) lastAggrNudgeStatus = "INACTIVE"
            else lastAggrNudgeStatus = "TRIM|${if (trimDirection > 0) "ACTIVE_HIGH" else "ACTIVE_LOW"}|${"%.1f".format(kotlin.math.abs(trimStrength) * 100)}%"
            return
        }

        val effectiveScale = when {
            msSincePenalty > AGGR_NUDGE_COOLDOWN_MS -> AGGR_NUDGE_SCALE
            lastPenaltyWasFasting                   -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_FASTING
            else                                    -> AGGR_NUDGE_SCALE * AGGR_NUDGE_ATTN_MEAL
        }

        val deviation      = if (tooMuch) 1.0 - aggressiveness else aggressiveness - 1.0
        val accel = computeAcceleration()
        val accelNudge = when {
            abs(accel) <= ACCEL_DEAD_BAND_MGDL -> 0.0
            tooMuch -> -accel * ACCEL_PENALTY_FACTOR
            else -> accel * ACCEL_PENALTY_FACTOR
        }
        val combinedDeviation = (deviation + accelNudge).coerceAtLeast(0.0)
        val nudge          = combinedDeviation * effectiveScale
        val d          = dow.coerceIn(0, 6)
        val prevIsfMult = isfState.days[d].get(hour)
        val prevBasMult = basalState.days[d].get(hour)

        if (nudgeSessionHour != hour || nudgeSessionDow != dow) {
            nudgeSessionHour    = hour
            nudgeSessionDow     = dow
            nudgeSessionIsfMult = prevIsfMult
            nudgeSessionBasMult = prevBasMult
        }

        val nudgedIsf = if (tooMuch)
            (prevIsfMult * (1.0 - nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        else
            (prevIsfMult * (1.0 + nudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

        val isfNudgeDir      = if (tooMuch) -1 else 1
        val isfPhysicsAgrees = isfPhysicsDirection != 0 && isfPhysicsDirection == isfNudgeDir
        val isfApplied       = !isfPhysicsAgrees
        if (isfApplied) {
            isfState = isfState.updatedDayOnly(dow, hour, nudgedIsf, 1.0)
        }

        val nudgedBas = if (tooMuch)
            (prevBasMult * (1.0 - nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        else
            (prevBasMult * (1.0 + nudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        if (!basalPhysicsFired) {
            basalState = basalState.updatedDayOnly(dow, hour, nudgedBas, 1.0)
        }
    }

    // ── Basal learner ─────────────────────────────────────────────────────────

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
                    abs(driftMgdlPerHr) < BASAL_MIN_DRIFT_MGDL_HR -> { /* Noise gate */ }
                    abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR -> {
                        basalDriftWindow.clear()
                    }
                    else -> {
                        val adjustment = 1.0 + (driftMgdlPerHr / BASAL_DRIFT_SENSITIVITY)
                        val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                        val conf  = basalState.getConfidence(dow, hour)
                        val alpha = (BASAL_ALPHA * (1.5 - conf)).coerceIn(BASAL_ALPHA * 0.5, BASAL_ALPHA * 1.5)
                        basalState = basalState.updated(dow, hour, newMult, alpha)
                        basalDriftWindow.clear()
                        driftFired = true
                    }
                }
            }
        }

        if (!driftFired && !inPostMealLockout && bg < targetMgdl &&
            basalIob < BASAL_NEG_IOB_THRESHOLD && basalDriftWindow.size >= BASAL_NEG_IOB_MIN_SAMPLES) {
            val belowTargetMgdl = targetMgdl - bg
            val adjustment = 1.0 - (belowTargetMgdl / BASAL_NEG_IOB_SENSITIVITY).coerceIn(0.0, BASAL_NEG_IOB_MAX_ADJUST)
            val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
            basalState = basalState.updated(dow, hour, newMult, BASAL_ALPHA * 0.5)
            negIobFired = true
        }

        if (!driftFired && !inPostMealLockout) {
            val projectedBg = projectBg60min(targetMgdl)
            if (projectedBg != null) {
                val projectedError = projectedBg - targetMgdl
                val isCuttingBasal = projectedError < 0
                val currentlyAboveTarget = bg > targetMgdl

                if (!(isCuttingBasal && currentlyAboveTarget) && abs(projectedError) > PRED_TRIM_DEAD_BAND_MGDL) {
                    val rawAdjust  = (projectedError / PRED_TRIM_SENSITIVITY).coerceIn(-PRED_TRIM_MAX_ADJUST, PRED_TRIM_MAX_ADJUST)
                    val adjustment = 1.0 + rawAdjust
                    basalState = basalState.updated(dow, hour, (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)
                    predTrimFired = true
                }
            }
        }
        return driftFired || negIobFired || predTrimFired
    }

    private fun projectBg60min(targetMgdl: Double): Double? {
        if (basalDriftWindow.size < PRED_MIN_WINDOW_SAMPLES) return null
        val oldest     = basalDriftWindow.first()
        val newest     = basalDriftWindow.last()
        val elapsedHrs = (newest.first - oldest.first) / 3_600_000.0
        if (elapsedHrs < 0.2) return null
        val driftMgdlPerHr = (newest.second - oldest.second) / elapsedHrs
        if (abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR) return null
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
        val rollercoaster = detectRollercoaster(targetMgdl, lowGuardMgdl)
        if (rollercoaster) {
            if (!isLikelyCompression(targetMgdl, lowGuardMgdl)) {
                val penalised = (currentCeil * AGGR_PENALTY_ROLLER).coerceAtLeast(AGGR_CEIL_MIN)
                aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
                lastPenaltyMs = System.currentTimeMillis()
                lastPenaltyWasFasting = isFasting
                lastPenaltyReason = "rollercoaster"
                val now = System.currentTimeMillis()
                if (lastRollercoasterMs > 0L && now - lastRollercoasterMs > ROLLER_ESCALATION_RESET_MS) {
                    consecutiveRollercoasters = 0
                }
                consecutiveRollercoasters++
                lastRollercoasterMs = now
            }
            return
        }

        if (bg < lowGuardMgdl && isFasting && !inPostMealLockout) {
            val nowMs = System.currentTimeMillis()
            val msSinceLastHardLow = if (lastHardLowPenaltyMs > 0L) nowMs - lastHardLowPenaltyMs else Long.MAX_VALUE
            val isNewLowEvent = msSinceLastHardLow > HARD_LOW_BASAL_GATE_MS
            lastHardLowPenaltyMs = nowMs
            lastPenaltyWasFasting = isFasting

            if (isNewLowEvent) {
                val penalised = (currentCeil * AGGR_PENALTY_HARD_LOW).coerceAtLeast(AGGR_CEIL_MIN)
                aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
                val nudgedBasMult = (basalState.days[dow].get(hour) * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                basalState = basalState.updatedDayOnly(dow, hour, nudgedBasMult, 1.0)
                val nudgedIsfMult = (isfState.days[dow].get(hour) * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                isfState = isfState.updatedDayOnly(dow, hour, nudgedIsfMult, 1.0)
            }
            return
        }

        val iob = iobArray.firstOrNull()?.iob ?: 0.0
        val approachingLow = isFasting && !inPostMealLockout &&
            bg >= lowGuardMgdl && bg < lowGuardMgdl + SOFT_LOW_APPROACH_MGDL &&
            delta < SOFT_LOW_DELTA_MGDL && iob > SOFT_LOW_MIN_IOB
        if (approachingLow) {
            val penalised = (currentCeil * AGGR_PENALTY_SOFT_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            lastPenaltyMs   = System.currentTimeMillis()
            lastPenaltyWasFasting = isFasting
            lastPenaltyReason = "soft low approach"
            return
        }

        val stableNearTarget = abs(bg - targetMgdl) < STABLE_BAND_MGDL && abs(delta) < STABLE_DELTA_MGDL
        if (stableNearTarget && currentCeil < 1.0) {
            val recovered = (currentCeil + AGGR_RECOVERY_STEP).coerceAtMost(AGGR_CEIL_MAX)
            aggrState = aggrState.updated(dow, hour, recovered, AGGR_ALPHA_RECOVERY)
        }
    }

    private fun detectRollercoaster(targetMgdl: Double, lowGuardMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false
        val highThreshold = targetMgdl + ROLLER_DEAD_BAND_MGDL
        val lowThreshold  = lowGuardMgdl
        var state = 0
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

    private fun isLikelyCompression(targetMgdl: Double, lowGuardMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false
        val lowStart = bgHistory.firstOrNull { it.second <= lowGuardMgdl }?.first ?: return false
        val lowEnd   = bgHistory.lastOrNull  { it.second <= lowGuardMgdl }?.first ?: return false
        val durationMins = (lowEnd - lowStart) / 60_000.0
        if (durationMins < 10.0 || durationMins > 45.0) return false
        val preDropStart = lowStart - 30 * 60_000L
        val preDropSlice = bgHistory.filter { it.first in preDropStart until lowStart }
        if (preDropSlice.size >= 3) {
            val preAvgDelta = (preDropSlice.last().second - preDropSlice.first().second) / preDropSlice.size.toDouble()
            if (preAvgDelta < -COMPRESSION_PRE_TREND_GATE) return false
        }
        val recoverySlice = bgHistory.filter { it.first in lowEnd..(lowEnd + 20 * 60_000L) }
        if (recoverySlice.size >= 2) {
            val maxRecoveryDelta = recoverySlice.zipWithNext().maxOfOrNull { (a, b) -> b.second - a.second } ?: 0.0
            if (maxRecoveryDelta < COMPRESSION_MIN_RECOVERY_DELTA) return false
        }
        val preLowReadings = bgHistory.filter { it.first in preDropStart until lowStart }.takeLast(3)
        val postRecoveryReadings = bgHistory.filter { it.first > lowEnd + 10 * 60_000L }.take(3)
        if (preLowReadings.size >= 2 && postRecoveryReadings.size >= 2) {
            val preLowAvg  = preLowReadings.map { it.second }.average()
            val postRecAvg = postRecoveryReadings.map { it.second }.average()
            if (kotlin.math.abs(postRecAvg - preLowAvg) > COMPRESSION_BASELINE_RETURN_GATE) return false
        }
        return true
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persist() {
        try {
            val json = JSONObject().apply {
                put("isf",   isfState.toJson())
                put("basal", basalState.toJson())
                put("aggr",  aggrState.toJson())

                activeIsfEpisode?.let { ep ->
                    put("isfEpisode", JSONObject().apply {
                        put("startBg",      ep.startBgMgdl)
                        put("startMs",      ep.startTimeMs)
                        put("hour",         ep.hour)
                        put("dow",          ep.dow)
                        put("correctionU",  ep.correctionU)
                        put("basalDevU",    ep.basalDeviationU)
                        put("stableMins",   ep.stableMinutes)
                        put("partialMins",  ep.partialStableMins)
                    })
                }
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

            // Migration check
            if (json.getJSONObject("isf").has("values")) {
                isfState   = migrateFlatTo7Day(json.getJSONObject("isf"))
                basalState = migrateFlatTo7Day(json.getJSONObject("basal"))
                aggrState  = migrateFlatTo7Day(json.getJSONObject("aggr"))
                persist()
                return
            }

            isfState   = DayOfWeekCircadianState.fromJson(json.getJSONObject("isf"))
            basalState = DayOfWeekCircadianState.fromJson(json.getJSONObject("basal"))
            aggrState  = DayOfWeekCircadianState.fromJson(json.getJSONObject("aggr"))

            json.optJSONObject("isfEpisode")?.let { ep ->
                val startMs = ep.getLong("startMs")
                if (System.currentTimeMillis() - startMs < EPISODE_TIMEOUT_MS) {
                    activeIsfEpisode = IsfEpisode(
                        startBgMgdl     = ep.getDouble("startBg"),
                        startTimeMs     = startMs,
                        hour            = ep.getInt("hour"),
                        dow             = ep.getInt("dow"),
                        correctionU     = ep.getDouble("correctionU"),
                        basalDeviationU = ep.getDouble("basalDevU"),
                        stableMinutes   = ep.getInt("stableMins"),
                        partialStableMins = ep.getInt("partialMins")
                    )
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner restore failed: ${e.message}")
        }
    }

    private fun migrateFlatTo7Day(flatJson: JSONObject): DayOfWeekCircadianState {
        val vArr   = flatJson.getJSONArray("values")
        val cArr   = flatJson.getJSONArray("confidence")
        val values = DoubleArray(24) { vArr.getDouble(it) }
        val conf   = DoubleArray(24) { cArr.getDouble(it) }
        val flatState = CircadianState(values, conf)
        return DayOfWeekCircadianState(Array(7) { flatState }, flatState)
    }

    fun reset() {
        isfState = DayOfWeekCircadianState(); basalState = DayOfWeekCircadianState(); aggrState = DayOfWeekCircadianState()
        activeIsfEpisode = null; bgHistory.clear()
        preferences.put(StringKey.ApsSmartInsulinCircadianState, "")
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        // ISF learner constants
        private const val ISF_ALPHA_FAST           = 0.02   // physics learner — reduced authority
        private const val ISF_ALPHA_SLOW           = 0.12   // episode learner — higher authority
        private const val ISF_MULT_MIN           = 0.7
        private const val ISF_MULT_MAX           = 1.5
        private const val MIN_ACTIVITY           = 0.005
        private const val MIN_EXPECTED_DELTA_MGDL = 1.0

        // ISF Episode learner (slow-path)
        private const val EPISODE_MIN_CORRECTION_U = 0.15
        private const val EPISODE_STABLE_DELTA     = 1.8
        private const val EPISODE_FULL_RESOLVE_BAND = 27.0
        private const val EPISODE_FULL_STABLE_MINS = 30
        private const val EPISODE_PARTIAL_STABLE_MINS = 60
        private const val EPISODE_TIMEOUT_MS       = 4 * 60 * 60 * 1000L
        private const val EPISODE_MIN_BG_DROP      = 9.0

        // Basal learner
        private const val BASAL_ALPHA              = 0.06
        private const val BASAL_MULT_MIN           = 0.5
        private const val BASAL_MULT_MAX           = 1.5
        private const val BASAL_DRIFT_WINDOW_MS    = 90 * 60 * 1000L
        private const val BASAL_MIN_SAMPLES        = 12
        private const val BASAL_MIN_ELAPSED_HRS    = 0.5
        private const val BASAL_MIN_DRIFT_MGDL_HR  = 2.0
        private const val BASAL_MAX_DRIFT_MGDL_HR  = 27.0
        private const val BASAL_DRIFT_SENSITIVITY  = 18.0
        private const val BASAL_WINDOW_MAX         = 30

        // Pred trim
        private const val PRED_TRIM_HORIZON_HRS   = 1.0
        private const val PRED_TRIM_DEAD_BAND_MGDL = 3.6
        private const val PRED_TRIM_MAX_ADJUST     = 0.12
        private const val PRED_TRIM_SENSITIVITY    = 18.0
        private const val PRED_MIN_WINDOW_SAMPLES  = 6

        // Neg IOB
        private const val BASAL_NEG_IOB_THRESHOLD   = -0.15
        private const val BASAL_NEG_IOB_MIN_SAMPLES = 6
        private const val BASAL_NEG_IOB_SENSITIVITY = 36.0
        private const val BASAL_NEG_IOB_MAX_ADJUST  = 0.10

        // Aggr Nudge / Accel
        private const val ACCEL_DEAD_BAND_MGDL  = 0.15
        private const val ACCEL_PENALTY_FACTOR  = 0.20
        private const val AGGR_NUDGE_THRESHOLD    = 0.95
        private const val AGGR_NUDGE_SURPLUS      = 1.05
        private const val AGGR_NUDGE_SCALE        = 0.04
        private const val AGGR_NUDGE_COOLDOWN_MS  = 120 * 60_000L
        private const val AGGR_NUDGE_ATTN_FASTING = 0.35
        private const val AGGR_NUDGE_ATTN_MEAL    = 0.15

        // Aggr Ceil
        private const val AGGR_ALPHA_PENALTY    = 0.25
        private const val AGGR_ALPHA_RECOVERY   = 0.04
        private const val AGGR_PENALTY_ROLLER       = 0.85
        private const val AGGR_PENALTY_HARD_LOW     = 0.90
        private const val AGGR_HARD_LOW_BASAL_NUDGE = 0.90
        private const val HARD_LOW_BASAL_GATE_MS    = 30 * 60_000L
        private const val AGGR_PENALTY_SOFT_LOW     = 0.90
        private const val AGGR_RECOVERY_STEP    = 0.01
        private const val AGGR_CEIL_MIN         = 0.60
        private const val AGGR_CEIL_MAX         = 1.20
        private const val STABLE_BAND_MGDL      = 18.0
        private const val STABLE_DELTA_MGDL     = 1.5
        private const val SOFT_LOW_DELTA_MGDL   = -1.5
        private const val SOFT_LOW_APPROACH_MGDL = 18.0
        private const val SOFT_LOW_MIN_IOB      = 0.3

        // Roller / Compression
        private const val ROLLER_WINDOW_MS          = 90 * 60 * 1000L
        private const val ROLLER_CROSSING_THRESHOLD = 2
        private const val ROLLER_DEAD_BAND_MGDL     = 18.0
        private const val MIN_HISTORY_FOR_ROLLER    = 6
        private const val ROLLER_ESCALATION_RESET_MS = 2 * 60 * 60 * 1000L
        private const val COMPRESSION_PRE_TREND_GATE       = 1.5
        private const val COMPRESSION_MIN_RECOVERY_DELTA   = 10.0
        private const val COMPRESSION_BASELINE_RETURN_GATE = 20.0

        // Fuel Trim
        private const val TRIM_DEAD_BAND_MGDL      = 5.4
        private const val TRIM_MAX_STRENGTH         = 0.20
        private const val TRIM_CEIL_SCALE           = 0.15
        private const val TRIM_CEIL_MAX             = 1.20
        private const val TRIM_CEIL_MIN             = 0.80
        private const val TRIM_LONG_TERM_FRACTION   = 0.50
        private const val TRIM_DECAY_RATE           = 0.70

        private const val COB_THRESHOLD_G = 5.0
        private const val MAX_HISTORY     = 30
    }

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun currentDow(): Int  = DayOfWeekCircadianState.currentDayOfWeek()
}