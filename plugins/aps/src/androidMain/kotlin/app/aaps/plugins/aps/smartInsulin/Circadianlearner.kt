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
 *     (dosingISF = trueISF / mult, so mult > 1.0 means a SMALLER ISF number and MORE insulin)
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

    /**
     * When each (day, hour) bucket was last seen FASTING with insulin actually working — the
     * evidence [retroAttributeHardLow] needs to charge a low back to the hours that dosed it.
     *
     * A last-seen timestamp rather than the raw cycles: the question asked of it is only "did this
     * hour dose within the lookback", which one long per bucket answers exactly, and which is small
     * enough to ride along in the state JSON. Persisted because the alternative is that a phone
     * reboot silently disables the attribution for four hours — it fails closed (no record means no
     * blame), so the cost is a missed correction that nothing would report.
     */
    private val lastFastingInsulinMs: MutableMap<Int, Long> = HashMap()
    /** Set when [lastFastingInsulinMs] has moved enough to be worth an SP write. */
    private var insulinPresenceDirty = false

    init { restore() }

    // Minute-of-hour for the cycle currently being processed. Set once at the top of update()
    // and read by every state write so all writes in a cycle share one timestamp, and so tests
    // can drive the hour-boundary behaviour deterministically.
    private var cycleMinute: Int = 0

    // -- State write helpers ---------------------------------------------------
    // Every isfState/basalState/aggrState write goes through these rather than calling
    // updated()/updatedDayOnly() directly, so that (a) the minute-of-hour spread is applied
    // uniformly and (b) the day-vs-global split for each writer is stated in exactly one place.

    /** Physics write: measured behaviour of this hour, applies to every weekday. Day + global. */
    private fun writeIsf(dow: Int, hour: Int, value: Double, alpha: Double) {
        isfState = isfState.updatedSplit(dow, hour, cycleMinute, value, alpha, alpha)
    }

    private fun writeBasal(dow: Int, hour: Int, value: Double, alpha: Double) {
        basalState = basalState.updatedSplit(dow, hour, cycleMinute, value, alpha, alpha)
    }

    private fun writeAggr(dow: Int, hour: Int, value: Double, alpha: Double) {
        aggrState = aggrState.updatedSplit(dow, hour, cycleMinute, value, alpha, alpha)
    }

    /** Day-scoped write: nudge/trim corrections. Leaves the cross-day baseline untouched. */
    private fun writeIsfDayOnly(dow: Int, hour: Int, value: Double, alpha: Double) {
        isfState = isfState.updatedSplit(dow, hour, cycleMinute, value, alpha, 0.0)
    }

    private fun writeBasalDayOnly(dow: Int, hour: Int, value: Double, alpha: Double) {
        basalState = basalState.updatedSplit(dow, hour, cycleMinute, value, alpha, 0.0)
    }

    private fun writeAggrDayOnly(dow: Int, hour: Int, value: Double, alpha: Double) {
        aggrState = aggrState.updatedSplit(dow, hour, cycleMinute, value, alpha, 0.0)
    }

    /**
     * Hard-low ceiling penalty: full-strength on the day it happened, and a slow trickle into the
     * cross-day baseline so a low that recurs at the same hour on several days starts suppressing
     * that hour on days it has not happened on yet. This is the channel that makes "I go low at
     * 4AM most nights" generalise — the ceiling propagates, and every day's nudge then acts on it.
     */
    private fun writeAggrHardLow(dow: Int, hour: Int, value: Double) {
        aggrState = aggrState.updatedSplit(dow, hour, cycleMinute, value, 1.0, AGGR_HARD_LOW_GLOBAL_ALPHA)
    }

    // -- Public outputs --------------------------------------------------------

    /**
     * ISF multiplier for the current moment (0.7–1.5). >1.0 = MORE aggressive
     * (dosingISF = profileISF / mult → smaller ISF → more insulin).
     *
     * Interpolated across the hour boundary by [minute] — learning earned late in one hour is
     * already partly in force at the start of the next, instead of being dropped at :00.
     */
    fun isfMultiplier(hour: Int = currentHour(), dow: Int = currentDow(), minute: Int = currentMinute()): Double =
        isfState.get(dow, hour, minute).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

    /** Basal multiplier for the current moment (0.5–1.5). Interpolated across the hour boundary. */
    fun basalMultiplier(hour: Int = currentHour(), dow: Int = currentDow(), minute: Int = currentMinute()): Double =
        basalState.get(dow, hour, minute).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

    /** Aggressiveness ceiling for the current moment (AGGR_CEIL_MIN–AGGR_CEIL_MAX).
     *  Global aggressiveness should be clamped to min(globalAggr, aggrCeiling).
     *  Interpolated across the hour boundary. */
    fun aggrCeiling(hour: Int = currentHour(), dow: Int = currentDow(), minute: Int = currentMinute()): Double =
        aggrState.get(dow, hour, minute).coerceIn(AGGR_CEIL_MIN, AGGR_CEIL_MAX)

    // Last basal learning signal for SI tab display
    var lastBasalSignal:  String = "No signal yet"
        private set
    var lastAccelDebug:    String = "No data"
        private set
    var lastPredTrimDebug: String = "No data"
        private set

    // Consolidated per-cycle diagnostic summary — shown in-app via Fragment debug
    // section instead of logcat, since logcat isn't practically viewable on-device.
    var lastCycleSummary: String = "No cycle data yet"
        private set

    /** What the last hard low charged back to earlier hours, for the SI tab. */
    var lastRetroAttribution: String = "No low attributed yet"
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
    // Recovery escalation — mirrors consecutiveRollercoasters but for the OPPOSITE direction:
    // penalties cut the ceiling hard and instantly (up to -15%, alpha=1.0 for hard lows), but
    // recovery was a flat +1%/cycle regardless of how long stability had held, so a suppressed
    // ceiling could stay suppressed for hours after the cause resolved — pure "under-dosing
    // highs" time that costs HbA1c without ever showing up as a low. Recovery speed now escalates
    // the longer stability persists, the same way the rollercoaster penalty escalates with
    // repetition. Reset to 0 by any penalty firing or by stability breaking.
    private var consecutiveStableCycles: Int = 0

    // -- Mode transition tracking — clears drift window on mode change --------
    // Prevents stale pre-P/F fasting samples from mixing with post-P/F fasting
    private var previousMealModeForDrift: MealMode = MealMode.FASTING

    // -- Short-term fuel trim state -------------------------------------------
    private val trimBgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(36)
    private var trimWindowMs: Long = 90 * 60_000L
    var trimActive: Boolean = false
        private set
    var trimStrength = 0.0
        private set
    private var trimDirection = 0
    private var trimStartMs  = 0L
    val trimMins: Long get() = if (trimActive && trimStartMs > 0L) (System.currentTimeMillis() - trimStartMs) / 60_000L else 0L
    private var lastTrimActionMs = 0L // NEW: Tracks the "Wait and Re-assess" window
    // True while the active cut came from an overshoot crash (spike that fell back through
    // target). Lets the SI tab label it distinctly and keeps it out of the "sustained low" copy.
    private var trimWasOvershoot = false

    // Last aggression nudge status for SI tab display
    var lastAggrNudgeStatus: String = "Inactive — no data yet"
        private set

    /** Called from plugin when learning is blocked — keeps status current */
    /**
     * Publishes a PAUSED status for the SI tab. Currently unused by the plugin — the nudge's own
     * blindfolds set their PAUSED status inline, which keeps the status honest about whether the
     * nudge actually ran. Kept as the entry point for any external pause reason.
     */
    fun pauseNudgeStatus(reason: String) {
        lastAggrNudgeStatus = "PAUSED|$reason"
    }

    /**
     * End any in-progress short-term fuel-trim episode. Called whenever the cycle is not clean
     * fasting (meal-mode / COB skip, or post-meal lockout). A meal changes the BG picture enough
     * that a pre-meal excursion and a post-meal one are genuinely different episodes, so the
     * sustained-trim clock (trimMins) must not span the interruption and the next fasting
     * excursion starts a fresh episode. No-op when nothing is active.
     */
    private fun resetTrim() {
        if (!trimActive && trimStrength == 0.0 && trimDirection == 0 && trimBgHistory.isEmpty()) return
        trimBgHistory.clear()
        trimActive    = false
        trimStrength  = 0.0
        trimDirection = 0
        trimStartMs   = 0L
        trimWasOvershoot = false
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
        inReboundWindow:          Boolean = false,
        aggressiveness:           Double  = 1.0,
        fastingPeakMins:          Double  = 90.0,   // learned fasting insulin peak — sets trim window
        // Learned fasting DIA. Only used by the retroactive low attribution, which needs the full
        // activity curve (peak alone can't say how much of a 3h-old dose is still working).
        fastingDiaMins:           Double  = 300.0,
        // Time injection — defaults to wall clock. Override in tests to simulate specific
        // hours/days without waiting for real time to pass.
        hour:                     Int     = currentHour(),
        dow:                      Int     = currentDow(),
        minute:                   Int     = currentMinute(),
        nowMs:                    Long    = System.currentTimeMillis()
    ) {
        // Pin the minute for every write this cycle (see cycleMinute / the write helpers).
        cycleMinute = minute.coerceIn(0, 59)
        val bg   = glucoseStatus.glucose
        val delta  = glucoseStatus.shortAvgDelta
        val now    = nowMs
        val iob    = iobArray.firstOrNull()?.iob      ?: 0.0
        val activity = iobArray.firstOrNull()?.activity ?: 0.0
        val basalIob = iobArray.firstOrNull()?.basaliob ?: 0.0
        // Recorded before the meal-mode skip below, not after: an hour only qualifies for
        // retroactive blame if it was FASTING, and that distinction can only be drawn if
        // meal-mode cycles are recorded too. Skipping them would leave holes in the ring that
        // are indistinguishable from "no insulin was working then".
        recordInsulinPresence(now, hour, dow, iob, mealMode == MealMode.FASTING)
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
                clearDriftWindow()
            }
            previousMealModeForDrift = mealMode
        }

        if (skipReason != null) {
            // Not clean fasting this cycle (meal mode or COB on board) — end any in-progress
            // fuel-trim episode so the sustained-trim clock never spans the interruption.
            resetTrim()
            // And drop the drift window. The mode-transition clear above misses the COB case
            // (carbs logged without a meal mode), which leaves pre-meal samples sitting in the
            // buffer for up to 90 min. Worse, driftCumExpectedMgdl does not accumulate on a
            // skipped cycle, so insulin that acted during the skip is missing from the
            // compensation — differencing across the gap reads the meal's rise as unexplained
            // and pushes basal UP. The below-target/lockout guards and the 27 mg/dL/hr sanity
            // gate caught most of it; this closes the hole instead.
            if (basalDriftWindow.isNotEmpty()) {
                aapsLogger.debug(LTag.APS, "CircadianLearner: $skipReason — clearing basalDriftWindow (${basalDriftWindow.size} samples)")
                clearDriftWindow()
            }
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
        if (bgBelowGuard(bg, lowGuardMgdl)) {
            if (trimBgHistory.isNotEmpty()) {
                aapsLogger.debug(LTag.APS, "FuelTrim: clearing history — BG below low guard (${"%.1f".format(bg)} < ${"%.0f".format(lowGuardMgdl)})")
                trimBgHistory.clear()
                trimActive = false
                trimStrength = 0.0
                trimDirection = 0
                trimWasOvershoot = false
            }
            // NOTE: the ISF/basal low-guard penalty itself is no longer applied here.
            // This block and updateAggrLearner's "Hard low" penalty (Signal 2 there) were
            // two independent mechanisms both cutting ISF/basal ×0.90 on the same low event,
            // using two different "is this a new event" definitions (boolean-reset-on-recovery
            // here vs. a 30-min time gate there) — they collided and could apply the cut up to
            // three times across one isolated low (this block once, the aggr block once in the
            // SAME cycle, then this block's flag got reset by the aggr block and fired again
            // next cycle), corrupting that hour's learned ISF/basal toward ~0.9³ ≈ 0.73 instead
            // of the intended 0.90. updateAggrLearner's version is kept as the single source of
            // truth: it already has the correct !inPostMealLockout / isFasting gates (this block
            // never had those) and a proper time-based new-event definition. See
            // updateAggrLearner's "Penalty signal 2: Hard low" block.
        }

        // Maintain BG history for rollercoaster detection
        bgHistory.addLast(now to bg)
        // Prune entries older than detection window
        while (bgHistory.isNotEmpty() && now - bgHistory.first().first > ROLLER_WINDOW_MS)
            bgHistory.removeFirst()

        // -- 1. ISF learning — skip during CGM warmup (unreliable data) -----
        val isFasting = mealMode == MealMode.FASTING
        pendingCrossBasal = null  // never carry an unapplied nudge into a later cycle
        val isfPhysicsFromIsf = if (!suppressAdaptiveLearning)
            updateIsfLearner(hour, dow, glucoseStatus, iobArray, profileIsfMgdl, inPostMealLockout, isFasting, bg, lowGuardMgdl, targetMgdl)
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
                                                                                  isfLearningActive = isfPhysicsFromIsf,
                                                                                  totalIob = iob)
        else {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal: suppressed (CGM warmup)")
            // Same gap argument as the skip path above — no basal cycle ran, so the drift
            // accumulator has a hole in it and the window can no longer be differenced.
            clearDriftWindow()
            false
        }

        // The ISF learner's basal cross-nudge, applied only if no basal signal measured this hour
        // itself this cycle. See the note where it is recorded.
        val crossBasalApplied = pendingCrossBasal?.let { c ->
            if (basalPhysicsFired) {
                aapsLogger.debug(LTag.APS, "CircadianLearner ISF crossBasal dropped: a basal signal already wrote h=${c.hour} this cycle")
                false
            } else {
                writeBasal(c.dow, c.hour, c.target, c.alpha)
                true
            }
        } ?: false
        pendingCrossBasal = null

        // Signal 4 (subTarget) writes isfState directly inside updateBasalLearner, and Signals
        // 0-3 now also write a smaller CROSS_NUDGE_FRACTION cross-nudge to isfState. Merge into
        // isfPhysicsFired so applyAggrNudge doesn't double-write ISF the same cycle.
        val isfPhysicsFired = isfPhysicsFromIsf || basalPhysicsFired
        // Symmetric merge for basal: the ISF learner's cross-nudge is a basalState write too, so
        // applyAggrNudge's basal-side mutual exclusion has to see it as well as updateBasalLearner's
        // own signals — but only when it actually landed, since it is dropped above whenever a
        // basal signal measured the same hour.
        val basalPhysicsFiredCombined = basalPhysicsFired || crossBasalApplied

        // -- 3. Aggressiveness ceiling — ALWAYS runs (rollercoaster protection) -
        // Rollercoaster and soft-low penalties must fire even on a new sensor —
        // a real rapid rise/crash is dangerous regardless of sensor age.
        updateAggrLearner(hour, dow, bg, delta, targetMgdl, iobArray, lowGuardMgdl, mealMode == MealMode.FASTING, inPostMealLockout,
                          fastingPeakMins = fastingPeakMins, fastingDiaMins = fastingDiaMins)

        // -- 4. Short-term fuel trim + aggression nudge --------------------------
        // Update trim window to match learned insulin peak (minimum 60 min, maximum 120 min).
        // This means the trim won't fire until BG has been off target for a full peak cycle —
        // ensuring we're not reacting to a rise that insulin is already handling.
        trimWindowMs = (fastingPeakMins * 60_000.0).toLong().coerceIn(60 * 60_000L, 120 * 60_000L)

        if (!suppressAdaptiveLearning) applyAggrNudge(hour, dow, inPostMealLockout, aggressiveness,
                                                      inReboundWindow = inReboundWindow,
                                                      bg = bg, targetMgdl = targetMgdl, lowGuardMgdl = lowGuardMgdl, now = nowMs,
                                                      isfPhysicsFired = isfPhysicsFired,
                                                      basalPhysicsFired = basalPhysicsFiredCombined,
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
            // changed=<bool> reflects whether the value ACTUALLY moved this cycle (before≠after),
            // ground-truth rather than a tracked flag. Previously this label only checked
            // isfPhysicsFromIsf/basalPhysicsFired, which under-reported: Signal 4 and FuelTrim
            // can both write isfState/basalState too, and neither set those specific flags, so
            // the summary could show a value that moved with "fired=false" next to it. Deriving
            // from the actual before/after diff can't go stale the next time a new writer is
            // added, since it doesn't need to be told who wrote — only whether something did.
            fun changed(before: Double, after: Double): Boolean = abs(after - before) > 1e-6

            lastCycleSummary =
                "h=$hour dow=${DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0,6)]} " +
                    "bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} delta=${"%.2f".format(delta)}\n" +
                    "iob=${"%.2f".format(iob)} basalIob=${"%.2f".format(basalIob)} activity=${"%.4f".format(activity)} " +
                    "postMeal=$inPostMealLockout mode=$mealMode\n" +
                    "ISF mult ${"%.3f".format(isfBefore)}→${"%.3f".format(isfAfter)} [${dirTag(isfBefore, isfAfter)}] " +
                    "(mainLearner=$isfPhysicsFromIsf changed=${changed(isfBefore, isfAfter)})\n" +
                    "BASAL mult ${"%.3f".format(basBefore)}→${"%.3f".format(basAfter)} [${dirTag(basBefore, basAfter)}] " +
                    "(physicsSignal=$basalPhysicsFired changed=${changed(basBefore, basAfter)})\n" +
                    "CEIL ${"%.3f".format(ceilBefore)}→${"%.3f".format(ceilAfter)} [${dirTag(ceilBefore, ceilAfter)}] " +
                    "(notEnough=$aggrNotEnough)\n" +
                    "basalSignal: $lastBasalSignal\n" +
                    "aggrNudge: $lastAggrNudgeStatus\n" +
                    "predTrim: $lastPredTrimDebug\n" +
                    "retroLow: $lastRetroAttribution"

            aapsLogger.debug(LTag.APS, "CircadianLearner SUMMARY ${lastCycleSummary.replace("\n", " | ")}")
        }

        // Only persist if any EWMA state was actually updated this cycle
        // insulinPresenceDirty is in the condition because the presence map changes on cycles where
        // no learner wrote anything — without it the map would only ever reach disk by accident.
        if (isfState !== prevIsf || basalState !== prevBasal || aggrState !== prevAggr || insulinPresenceDirty) {
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
        // Safety-biased clamp, CURRENT values (-1.0, 0.5) — was (-2.0, 1.0), tightened this
        // session: negative deviation (BG fell MORE than expected -> LESS insulin) may move
        // down to -1.0 (retreat from aggressiveness); positive deviation (insulin looked weak
        // -> MORE insulin) is capped at +0.5 so a single noisy reading can't push dosing ISF
        // down hard. The retreat direction is intentionally allowed further than the
        // more-aggressive direction — safety bias toward backing off, not pushing harder.
        var normDeviation = (deviation / abs(expectedDelta)).coerceIn(-1.0, 0.5)
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
        // ISF_NORM_GAIN converts a dimensionless prediction error into a multiplier offset.
        // normDeviation is a RATIO ("BG moved 60% more than predicted") and the multiplier lives
        // in [0.7, 1.5]; adding one straight to the other, as this did, meant a single sample
        // could propose the entire range. At 0.2 the strongest admissible sample proposes about a
        // fifth of the range, which the EWMA below then applies a fraction of.
        val multTarget    = (isfState.get(dow, hour) + normDeviation * ISF_NORM_GAIN).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

        // Confidence-weighted learning speed:
        // High confidence hours (0.8+) learn slower (stable).
        // Low confidence hours (<0.2) learn faster (converge quickly).
        val conf  = isfState.getConfidence(dow, hour)
        // Signal-strength weight. normDeviation divides by expectedDelta, so a weak expected
        // signal inflates ordinary CGM noise into a large apparent deviation — at the old
        // admission floor of 1 mg/dL, ±1–2 mg/dL of shortAvgDelta noise alone produced
        // normDeviation of ±1. The floor now excludes those cycles outright and this weight
        // ramps the rest in, so a cycle with a barely-admissible signal contributes
        // proportionally less than one with a strong insulin action to measure against.
        val signalWeight = (abs(expectedDelta) / ISF_FULL_WEIGHT_DELTA_MGDL).coerceIn(0.0, 1.0)
        val alpha = (ISF_ALPHA * (1.5 - conf)).coerceIn(ISF_ALPHA * 0.5, ISF_ALPHA * 1.5) * signalWeight
        if (alpha <= 0.0) return false

        // Write to both day bucket AND global so the blended output actually reflects
        // what the learner has observed. updatedDayOnly left global at 1.0 permanently,
        // causing get() to return 1.0 regardless of day bucket learnings until day
        // confidence crossed DAY_CONFIDENCE_THRESHOLD.
        writeIsf(dow, hour, multTarget, alpha)

        // Cross-nudge: a genuine ISF shift is often co-caused by a broader sensitivity change
        // that likely also affects basal — nudge basalState a smaller amount (CROSS_NUDGE_FRACTION)
        // in the same direction, as a lower-confidence co-movement prior rather than a direct
        // measurement (this cycle's data measured ISF, not basal).
        //
        // Recorded rather than written, because this learner runs BEFORE updateBasalLearner and
        // cannot yet know whether a basal signal of its own is about to fire on this same hour.
        // When one does, its measurement supersedes this prior and the nudge is dropped — two
        // writes in one cycle stacked a direct measurement on top of a co-movement guess, which
        // is the same double-pull already excluded between the basal signals themselves. The
        // caller applies or drops it (see update()).
        pendingCrossBasal = PendingCrossBasal(
            dow, hour,
            (basalState.get(dow, hour) + normDeviation * ISF_NORM_GAIN * CROSS_NUDGE_FRACTION).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX),
            alpha
        )

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF h=$hour expected?=%.1f actual?=%.1f dev=%.2f normDev=%.2f w=%.2f target=%.3f a=%.3f ? mult=%.3f crossBasal(pending)=%.3f"
                             .format(expectedDelta, actualDelta, deviation, normDeviation, signalWeight, multTarget, alpha, isfState.get(dow, hour), pendingCrossBasal!!.target))
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

    /**
     * If the hour just changed while a correction is actively pushing in one direction (FuelTrim
     * or aggrNudge), seed the new hour's bucket with whichever of {outgoing hour's ending value,
     * new hour's own value} is FURTHER ALONG in that direction — rather than letting the new
     * hour's independent history discard the progress the episode already made and force it to
     * re-earn the same ground. Never regresses a bucket that's already more corrected on its own
     * merits than the outgoing hour was. Safe to call more than once per cycle (from both the
     * FuelTrim-gated and aggrNudge-gated call sites) — a no-op once a bucket already reflects the
     * carried-over value.
     */
    private fun carryOverHourBoundary(dow: Int, hour: Int, pushingUp: Boolean, pushingDown: Boolean) {
        if (!pushingUp && !pushingDown) return
        if (nudgeSessionHour == -1 || (nudgeSessionHour == hour && nudgeSessionDow == dow)) return
        val outDow = nudgeSessionDow.coerceIn(0, 6)
        val d0     = dow.coerceIn(0, 6)
        val outgoingIsfMult = isfState.days[outDow].get(nudgeSessionHour)
        val outgoingBasMult = basalState.days[outDow].get(nudgeSessionHour)
        val newHourIsfMult  = isfState.days[d0].get(hour)
        val newHourBasMult  = basalState.days[d0].get(hour)

        val carriedIsf = (if (pushingUp) maxOf(outgoingIsfMult, newHourIsfMult) else minOf(outgoingIsfMult, newHourIsfMult))
            .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        val carriedBas = (if (pushingUp) maxOf(outgoingBasMult, newHourBasMult) else minOf(outgoingBasMult, newHourBasMult))
            .coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

        if (carriedIsf != newHourIsfMult) writeIsfDayOnly(dow, hour, carriedIsf, 1.0)
        if (carriedBas != newHourBasMult) writeBasalDayOnly(dow, hour, carriedBas, 1.0)

        if (carriedIsf != newHourIsfMult || carriedBas != newHourBasMult) {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner hour-boundary carryover: h=$nudgeSessionHour→$hour (pushingUp=$pushingUp) " +
                                 "isf ${"%.3f".format(newHourIsfMult)}→${"%.3f".format(carriedIsf)} " +
                                 "bas ${"%.3f".format(newHourBasMult)}→${"%.3f".format(carriedBas)}")
        }
    }

    /**
     * Pulls the day bucket back toward the cross-day physics baseline when the aggression ceiling
     * is neutral — the counter-force to [applyAggrNudge].
     *
     * Only the part of the displacement that exceeds [AGGR_NUDGE_DECAY_BAND] is unwound, so
     * genuine day-specific physics learning survives while nudge-sized excursions expire. Skips
     * any quantity a physics learner already wrote this cycle: a fresh measurement outranks a
     * decay toward an average.
     *
     * @return true if either bucket actually moved (drives the DECAY status in the SI tab).
     */
    private fun decayTowardBaseline(
        dow:               Int,
        hour:              Int,
        isfPhysicsFired:   Boolean,
        basalPhysicsFired: Boolean
    ): Boolean {
        val d = dow.coerceIn(0, 6)
        var moved = false

        if (!isfPhysicsFired) {
            val edge = bandEdge(isfState.days[d].get(hour), isfState.globalValue(hour, cycleMinute))
            if (edge != null) {
                writeIsfDayOnly(dow, hour, edge.coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), AGGR_NUDGE_DECAY_ALPHA)
                moved = true
            }
        }
        if (!basalPhysicsFired) {
            val edge = bandEdge(basalState.days[d].get(hour), basalState.globalValue(hour, cycleMinute))
            if (edge != null) {
                writeBasalDayOnly(dow, hour, edge.coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), AGGR_NUDGE_DECAY_ALPHA)
                moved = true
            }
        }
        if (moved) {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner decay h=$hour day=${DayOfWeekCircadianState.DAY_LABELS[d]} " +
                                 "isf ${"%.3f".format(isfState.days[d].get(hour))} (base ${"%.3f".format(isfState.globalValue(hour, cycleMinute))}) " +
                                 "bas ${"%.3f".format(basalState.days[d].get(hour))} (base ${"%.3f".format(basalState.globalValue(hour, cycleMinute))})")
        }
        return moved
    }

    /**
     * The edge of the ±[AGGR_NUDGE_DECAY_BAND] tolerance band around [baseline] that [day] has
     * escaped, or null if it is still inside the band and needs no correction. EWMA-ing toward
     * this edge asymptotes at the band rather than collapsing onto the baseline.
     */
    private fun bandEdge(day: Double, baseline: Double): Double? {
        val hi = baseline * (1.0 + AGGR_NUDGE_DECAY_BAND)
        val lo = baseline * (1.0 - AGGR_NUDGE_DECAY_BAND)
        return when {
            day > hi -> hi
            day < lo -> lo
            else     -> null
        }
    }

    private fun applyAggrNudge(
        hour:              Int,
        dow:               Int,
        inPostMealLockout: Boolean,
        aggressiveness:    Double,
        inReboundWindow:   Boolean = false,
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

        // Hour-boundary carryover for FuelTrim specifically — gated on FuelTrim's OWN direction
        // state (already vetted by its own internal guards, e.g. isLowRecovery/overshootCrash),
        // not the aggrNudge tooMuch/notEnough flags (those get their own carryover call below,
        // AFTER their blindfold guards run, so a rebound-triggered false "notEnough" can't carry
        // progress across an hour boundary any more than it could act on it directly).
        carryOverHourBoundary(dow, hour, pushingUp = trimActive && trimDirection > 0, pushingDown = trimActive && trimDirection < 0)

        // BUG FIX: FuelTrim (below) and the aggrNudge tooMuch/notEnough block (further down in
        // this same function) both write isfState/basalState, and both were only checking the
        // INCOMING isfPhysicsFired/basalPhysicsFired params — which reflect the physics learners
        // from update(), not each other. So when no physics fired but FuelTrim DID fire this
        // cycle, aggrNudge's block had no way to know and could write on top of FuelTrim's value
        // in the same cycle — multiplicative double-compounding in the same direction. These
        // local flags track whether FuelTrim actually wrote this cycle; OR'd into every
        // downstream check so aggrNudge correctly defers when FuelTrim already acted.
        var trimWroteIsf   = false
        var trimWroteBasal = false
        // Set when FuelTrim sees a post-spike crash through target this cycle (scope-visible to
        // the aggrNudge guards below, where overshootCrash itself is out of scope).
        var overshootThisCycle = false

        // Post-meal lockout while still in FASTING mode doesn't trigger the skip-return above,
        // so close any trim episode here too — same rule: only clean fasting accumulates.
        if (inPostMealLockout) resetTrim()

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

                // --- OVERSHOOT-CRASH GUARD (post-spike recovery back through target) ---
                // avgBg is a trailing mean over the whole trim window (up to 120 min). After a
                // high spike that crashes back THROUGH target, that mean is still dominated by the
                // spike, so aboveBand reads "sustained high" and ADDS insulin — at the exact moment
                // the live reading has already fallen to/below target. That is the signature of too
                // much insulin already on board, and the swing itself is excess loop gain; adding
                // more just deepens the next low. detectRollercoaster() does NOT catch this: it
                // requires BG to reach the low guard, but here only the (higher) target was crossed.
                // Anchor on the live reading. A real overshoot is when the window mean and the live
                // reading STRADDLE target: mean on the high side of the dead band, current reading on
                // the LOW side. Using bare `bg <= target` instead fired during normal at-target running
                // (live reading just under target + a slightly-high trailing mean), hijacking the cut
                // branch and blocking the increase-learning. Require bg a full dead band below target so
                // this only triggers on a genuine straddle/crash — your 4.6-vs-5.5 case still qualifies.
                val overshootCrash = (avgBg > targetMgdl + TRIM_DEAD_BAND_MGDL) &&
                    (bg < targetMgdl - TRIM_DEAD_BAND_MGDL)
                overshootThisCycle = overshootCrash

                // bg >= targetMgdl is the live-reading gate, and it is the one that was missing.
                //
                // aboveBand is decided on avgBg, a trailing mean over up to 120 min. The
                // overshootCrash guard below already handles a mean that is high while the live
                // reading has crashed a FULL dead band under target — but between target and
                // target - dead band there was no guard at all. A BG that has come down to 5.2
                // against a 5.5 target, flat, with negative IOB and a zero TBR, still read as
                // "sustained high" and had insulin ADDED, because the mean behind it was 5.9.
                //
                // That is the engine of the rollercoaster: dose, undershoot through target,
                // trailing mean stays high, dose again on the way down, undershoot further. The
                // mean says where BG has BEEN; the live reading says where it IS, and nothing
                // below target should be adding insulin on the strength of the former.
                //
                // Not folded into overshootCrash: that flips to the CUT branch, and the earlier
                // attempt to widen it there fired during ordinary at-target running and blocked
                // increase-learning entirely. Gating only the ADD branch leaves this zone neutral,
                // which is the honest answer for "recently high, currently under target".
                val aboveBand = (avgBg > targetMgdl + TRIM_DEAD_BAND_MGDL) &&
                    bg >= targetMgdl &&
                    !isLowRecovery && !overshootCrash
                val belowBand = (avgBg < targetMgdl - TRIM_DEAD_BAND_MGDL) || overshootCrash

                // --- HUMAN "STEP AND WAIT" LOGIC ---
                val timeSinceLastAction = now - lastTrimActionMs
                val readyToReassess = timeSinceLastAction >= trimWindowMs

                when {
                    aboveBand -> {
                        // Not enough insulin — trim ceiling UP (more aggressive)
                        if (readyToReassess) {
                            val magnitude  = ((avgBg - targetMgdl) / targetMgdl).coerceIn(0.0, TRIM_MAX_STRENGTH)
                            // BUG FIX: trimStartMs previously only reset when trimActive was false.
                            // If BG flickered out of band and was mid-decay (trimActive still true,
                            // trimStrength not yet below the 0.005 floor) when it re-entered aboveBand,
                            // trimStartMs was NEVER refreshed — the displayed "sustained high for Xm"
                            // kept counting from the ORIGINAL episode's start even though the live
                            // magnitude driving the current dose is from a much more recent re-trigger.
                            // A genuinely new episode is: direction was anything other than +1, OR we
                            // were fully inactive. Either case means this is a fresh high, not a
                            // continuation — reset the clock regardless of trimActive's exact state.
                            val isNewEpisode = !trimActive || trimDirection != +1
                            trimStrength   = magnitude
                            trimDirection  = +1
                            trimActive     = true
                            trimWasOvershoot = false
                            if (isNewEpisode) trimStartMs = now

                            val trimmedCeil = (aggrState.get(dow, hour) + magnitude * TRIM_CEIL_SCALE)
                                .coerceIn(AGGR_CEIL_MIN, TRIM_CEIL_MAX)
                            writeAggrDayOnly(dow, hour, trimmedCeil, 1.0)

                            val ltNudge = magnitude * TRIM_LONG_TERM_FRACTION
                            val d = dow.coerceIn(0, 6)
                            // Mutual exclusion: don't stack the FuelTrim long-term nudge on top of a physics-learner
                            // update in the same cycle (same intent as the aggrNudge block below).
                            if (!isfPhysicsFired) {
                                writeIsfDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                                trimWroteIsf = true
                            }
                            if (!basalPhysicsFired) {
                                writeBasalDayOnly(dow, hour,
                                                                       (basalState.days[d].get(hour) * (1.0 + ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)
                                trimWroteBasal = true
                            }

                            lastTrimActionMs = now // Reset the timer. We wait 90 mins from NOW before pushing harder.

                            aapsLogger.debug(LTag.APS,
                                             "FuelTrim[STEP +] h=$hour avgBg=${"%.1f".format(avgBg)} target=${"%.0f".format(targetMgdl)} " +
                                                 "mag=${"%.3f".format(magnitude)} ? ceil=${"%.3f".format(trimmedCeil)} ltNudge=${"%.4f".format(ltNudge)}" +
                                                 (if (isNewEpisode) " [NEW EPISODE — timer reset]" else ""))
                        } else {
                            aapsLogger.debug(LTag.APS, "FuelTrim[WAIT]: Holding extra insulin, waiting for peak (${timeSinceLastAction / 60_000}/${trimWindowMs / 60_000} mins)")
                        }
                    }
                    belowBand -> {
                        // Too much insulin — trim ceiling DOWN (less aggressive)
                        if (readyToReassess) {
                            // During an overshoot crash avgBg is stale-high, so the avg-anchored
                            // formula would yield ~0. Anchor on the live reading instead: the cut
                            // scales with how far below target we ALREADY are, amplified below low guard.
                            val effLow        = if (overshootCrash) bg else avgBg
                            val baseMagnitude = ((targetMgdl - effLow) / targetMgdl).coerceIn(0.0, TRIM_MAX_STRENGTH)
                            val amplifier     = if (effLow < lowGuardMgdl) 2.0 else 1.0
                            val magnitude     = (baseMagnitude * amplifier).coerceIn(0.0, TRIM_MAX_STRENGTH)
                            trimWasOvershoot  = overshootCrash
                            // Same fix as aboveBand — see comment there.
                            val isNewEpisode = !trimActive || trimDirection != -1
                            trimStrength   = -magnitude
                            trimDirection  = -1
                            trimActive     = true
                            if (isNewEpisode) trimStartMs = now

                            val trimmedCeil = (aggrState.get(dow, hour) - magnitude * TRIM_CEIL_SCALE)
                                .coerceIn(TRIM_CEIL_MIN, AGGR_CEIL_MAX)
                            writeAggrDayOnly(dow, hour, trimmedCeil, 1.0)

                            val ltNudge = magnitude * TRIM_LONG_TERM_FRACTION
                            val d = dow.coerceIn(0, 6)
                            // Mutual exclusion: don't stack the FuelTrim long-term nudge on top of a physics-learner
                            // update in the same cycle (same intent as the aggrNudge block below).
                            if (!isfPhysicsFired) {
                                writeIsfDayOnly(dow, hour,
                                                                   (isfState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX), BASAL_ALPHA * 0.5)
                                trimWroteIsf = true
                            }
                            if (!basalPhysicsFired) {
                                writeBasalDayOnly(dow, hour,
                                                                       (basalState.days[d].get(hour) * (1.0 - ltNudge)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX), BASAL_ALPHA * 0.5)
                                trimWroteBasal = true
                            }

                            lastTrimActionMs = now // Reset the timer.

                            aapsLogger.debug(LTag.APS,
                                             "FuelTrim[${if (overshootCrash) "OVERSHOOT -" else "STEP -"}] h=$hour avgBg=${"%.1f".format(avgBg)} bg=${"%.1f".format(bg)} target=${"%.0f".format(targetMgdl)} " +
                                                 "mag=${"%.3f".format(magnitude)} ? ceil=${"%.3f".format(trimmedCeil)} ltNudge=${"%.4f".format(ltNudge)}" +
                                                 (if (isNewEpisode) " [NEW EPISODE — timer reset]" else ""))
                        } else {
                            aapsLogger.debug(LTag.APS, "FuelTrim[WAIT]: Holding reduced insulin, waiting for peak (${timeSinceLastAction / 60_000}/${trimWindowMs / 60_000} mins)")
                        }
                    }
                    else -> {
                        // SAFETY VALVE: BG is back in band. Start decaying immediately so we don't overshoot.
                        if (trimActive) {
                            trimStrength *= TRIM_DECAY_RATE
                            if (kotlin.math.abs(trimStrength) < 0.005) {
                                trimActive = false; trimStrength = 0.0; trimDirection = 0; trimStartMs = 0L; trimWasOvershoot = false
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

        // --- REBOUND-WINDOW BLINDFOLD ---
        // The loop's own post-low rebound window is the authoritative "we are recovering from a
        // low" signal. The penalty-cooldown blindfold below only arms if a penalty happened to
        // fire, so an isolated low that the loop caught and suspended out of — without tripping a
        // rollercoaster or soft-low penalty — left the nudge free to read the rescue-carb rise as
        // "not enough insulin" and stack basal-up onto a recovery. Gate on the window itself.
        // Only the add-insulin direction is blocked; cutting insulin during a recovery is safe.
        if (notEnough && inReboundWindow) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|Post-low recovery window"
            aapsLogger.debug(LTag.APS, "CircadianLearner aggrNudge[notEnough] blocked — inside post-low rebound window")
        }

        // --- POST-LOW BLINDFOLD (Main Learner) ---
        // If we are recovering from a low, the rise is from rescue carbs/liver, NOT a basal deficit.
        // Block the learner from falsely increasing insulin (dropping ISF / raising basal).
        if (notEnough && cooldownActive && (lastPenaltyReason.contains("low") || lastPenaltyReason.contains("rollercoaster"))) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|Low Recovery Spike"
        }

        // --- AT/BELOW-TARGET GUARD ---
        // "Not enough insulin" is contradicted by live BG at or below target regardless of
        // IOB — there is no IOB value that makes adding insulin while under target during
        // fasting the right conclusion. Supersedes the old NegIOB@target guard (which only
        // engaged when iob < -0.20 and let the nudge write up every cycle through shallow
        // sub-target hovers where IOB sat near zero).
        if (notEnough && bg <= targetMgdl + TRIM_DEAD_BAND_MGDL) {
            notEnough = false
            lastAggrNudgeStatus = "PAUSED|At/below target (bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)})"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner aggrNudge[notEnough] blocked — BG at/below target " +
                                 "(bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} iob=${"%.2f".format(iob)})")
        }

        // --- OVERSHOOT GUARD (aggrNudge) ---
        // FuelTrim already detected a post-spike crash through target this cycle and is cutting.
        // A global "needs more insulin" score is stale against the live crash, so block notEnough:
        // otherwise its writes are skipped by the trimWrote guards anyway but it still overwrites the
        // status to ACTIVE_HIGH, making the SI tab read "adding insulin" mid-overshoot. Blocking it
        // lets the no-op return below publish the correct TRIM|OVERSHOOT status instead.
        if (notEnough && overshootThisCycle) {
            notEnough = false
            aapsLogger.debug(LTag.APS, "CircadianLearner aggrNudge[notEnough] blocked — FuelTrim overshoot crash this cycle (bg through target)")
        }

        if (!tooMuch && !notEnough) {
            // -- Dead-band restoring force ------------------------------------
            // The ceiling is neutral for this hour, so whatever displacement a past penalty
            // drove into these buckets is no longer supported by evidence. Without this, a
            // single bad low left a permanent mark: the nudge only ever pushed away from the
            // baseline and nothing ever pushed back, so the multipliers stayed where the worst
            // night of the month put them until the opposite penalty happened to fire.
            //
            // The decay pulls the day bucket back toward the cross-day physics baseline, but
            // only as far as DECAY_BAND — a weekday genuinely can differ from the week's average
            // (shift work, a standing Saturday ride), and that difference is learned by the
            // physics learners, which write day and global together. Collapsing the day bucket
            // all the way onto global would delete real day-specific learning along with the
            // stale nudge. Pulling it back to within ±DECAY_BAND removes excursions only the
            // nudge could have produced and leaves ordinary day-to-day variation alone.
            val decayed = !trimActive && decayTowardBaseline(dow, hour, isfPhysicsFired, basalPhysicsFired)
            lastAggrNudgeStatus = when {
                trimActive -> "TRIM|${if (trimDirection > 0) "ACTIVE_HIGH" else if (trimWasOvershoot) "OVERSHOOT" else "ACTIVE_LOW"}|${"%.1f".format(kotlin.math.abs(trimStrength) * 100)}%"
                decayed    -> "DECAY|${"%.3f".format(isfState.days[dow.coerceIn(0, 6)].get(hour))}|${"%.3f".format(basalState.days[dow.coerceIn(0, 6)].get(hour))}"
                else       -> "INACTIVE"
            }
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
        // Reuses accelAlways (computed earlier in this same function) rather than calling
        // computeAcceleration() again — bgHistory doesn't change in between, so the second
        // call was a redundant identical computation.
        val accel = accelAlways

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

        // -- Bounded target, not an open-loop step -----------------------------
        // This used to be `mult *= (1 ± deviation * scale)` written back with alpha 1.0 every
        // cycle: an integrator whose output never fed back into its input. The ceiling only
        // recovers on stability near target, which is unreachable while the nudge is still
        // cutting insulin — so "hour ran high" was not a signal that could stop it, and both
        // multipliers walked to their floors over a few days of occupying that hour.
        //
        // Now the ceiling names a DESTINATION rather than a direction. The nudge-free cross-day
        // baseline (global — the physics learners write it, the nudge never does) is the anchor,
        // the ceiling deficit sets a bounded displacement from that anchor, and the day bucket
        // EWMAs toward it. Steps shrink as the gap closes, so it converges instead of ratcheting;
        // if the ceiling then recovers, the target moves back and the same mechanism unwinds it.
        val displacement = (combinedDeviation * AGGR_NUDGE_AUTHORITY).coerceAtMost(AGGR_NUDGE_MAX_DISPLACEMENT)
        val nudgeDir     = if (tooMuch) -1.0 else 1.0
        val isfBaseline  = isfState.globalValue(hour, cycleMinute)
        val basBaseline  = basalState.globalValue(hour, cycleMinute)
        val isfTarget    = (isfBaseline * (1.0 + nudgeDir * displacement)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        val basTarget    = (basBaseline * (1.0 + nudgeDir * displacement)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        // effectiveScale is now the RATE of approach to that target, not the size of a step off
        // the current value. That also defuses the cooldown attenuation: throttling the rate
        // right after a penalty slows how fast the nudge gets there but no longer changes where
        // it ends up, so a busy cooldown period can't quietly under- or over-shoot the endpoint.
        val nudgeAlpha     = effectiveScale.coerceIn(0.0, 1.0)
        val dayName        = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        val deviationPct   = "${"%.0f".format(deviation * 100)}%"
        // Hour-boundary carryover for aggrNudge — gated on tooMuch/notEnough AFTER all the
        // blindfold guards above (post-low pause, at/below-target, overshoot) have already had
        // the chance to clear a false positive. Using the fully-guarded flags here (rather than
        // the raw aggressiveness comparison) means a rebound that's correctly blocked from acting
        // this cycle can't carry stale "notEnough" progress across an hour boundary either.
        carryOverHourBoundary(dow, hour, pushingUp = notEnough, pushingDown = tooMuch)

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

        // ISF nudge — skip if physics learner OR FuelTrim already updated ISF this cycle.
        // Both push isfState based on different signals and can disagree; mutual exclusion
        // prevents them cancelling each other out / compounding. Basal nudge still fires independently.
        val nudgedIsf = prevIsfMult + (isfTarget - prevIsfMult) * nudgeAlpha
        if (!isfPhysicsFired && !trimWroteIsf) {
            writeIsfDayOnly(dow, hour, isfTarget, nudgeAlpha)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$cooldownNote " +
                                 "h=$hour day=$dayName ceil=${"%.3f".format(aggressiveness)} " +
                                 "deviation=${"%.3f".format(deviation)} disp=${"%.3f".format(displacement)} " +
                                 "base=${"%.3f".format(isfBaseline)} target=${"%.3f".format(isfTarget)} a=${"%.3f".format(nudgeAlpha)} " +
                                 "? mult=${"%.3f".format(isfState.days[d].get(hour))}")
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner ISF[aggrNudge] SKIPPED — ${if (isfPhysicsFired) "physics learner" else "FuelTrim"} already fired this cycle " +
                                 "(would have nudged ${"%.3f".format(prevIsfMult)}?${"%.3f".format(nudgedIsf)})")
        }

        // Basal nudge — skip if physics learner (drift/negIOB/predTrim) OR FuelTrim already fired this cycle.
        // Same mutual exclusion pattern as ISF: both signals push basalState from different
        // sources and can disagree cycle-to-cycle. Exclusive write keeps the signal clean.
        val nudgedBas = prevBasMult + (basTarget - prevBasMult) * nudgeAlpha
        if (!basalPhysicsFired && !trimWroteBasal) {
            writeBasalDayOnly(dow, hour, basTarget, nudgeAlpha)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[aggrNudge/${if (tooMuch) "reduce" else "increase"}]$cooldownNote " +
                                 "h=$hour ceil=${"%.3f".format(aggressiveness)} deviation=${"%.3f".format(deviation)} " +
                                 "disp=${"%.3f".format(displacement)} base=${"%.3f".format(basBaseline)} target=${"%.3f".format(basTarget)} " +
                                 "a=${"%.3f".format(nudgeAlpha)} ? mult=${"%.3f".format(basalState.get(dow, hour))}")
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[aggrNudge] SKIPPED — ${if (basalPhysicsFired) "physics learner" else "FuelTrim"} already fired this cycle " +
                                 "(would have nudged ${"%.3f".format(prevBasMult)}?${"%.3f".format(nudgedBas)})")
        }

        // Store status — include whether physics learner or FuelTrim overrode the nudge this cycle
        val isfApplied  = !isfPhysicsFired && !trimWroteIsf
        val basApplied  = !basalPhysicsFired && !trimWroteBasal
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
    // Instead: use a sustained BG drift window collected during fasting (low COB, no bolus —
    // already gated upstream in update()). Each sample also carries the running integral of
    // insulin's expected BG effect, and the drift slope is computed NET of that integral —
    // so the window no longer needs insulin to be absent, just modeled. Once enough samples
    // accumulate over a long enough window, the compensated net drift tells us whether
    // profile basal is too high or too low — regardless of what the loop did to achieve it.

    // Each drift sample carries the running integral of insulin's EXPECTED BG effect
    // (mg/dL, from -activity * ISF * 5 per cycle) at the moment the sample was taken.
    // Compensated drift over the window = (ΔBG − ΔcumExpected) / elapsed — i.e. the BG
    // movement NOT explained by insulin activity, attributable to basal error. This is what
    // lets the window collect samples at everyday IOB levels instead of requiring IOB ≈ 0:
    // an SMB tail's expected effect is modeled out rather than contaminating the slope.
    private data class DriftSample(val timeMs: Long, val bg: Double, val cumExpectedMgdl: Double)

    private val basalDriftWindow: ArrayDeque<DriftSample> = ArrayDeque(BASAL_WINDOW_MAX)
    private var driftCumExpectedMgdl = 0.0

    private fun clearDriftWindow() {
        basalDriftWindow.clear()
        driftCumExpectedMgdl = 0.0
    }

    /** ISF learning's co-movement nudge to basal, held until updateBasalLearner has had its say. */
    private data class PendingCrossBasal(val dow: Int, val hour: Int, val target: Double, val alpha: Double)
    private var pendingCrossBasal: PendingCrossBasal? = null

    /** Consecutive cycles Signal 0's conditions have held, with the sign it has held in. */
    private var s0RunLength = 0
    private var s0RunSign   = 0

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
        isfLearningActive: Boolean = false,  // true when updateIsfLearner fired this cycle
        totalIob:          Double  = 0.0     // total IOB (basal + SMB) — the loop is SMB-based, so
        // basalIob alone misses SMB tails; use this for "is the
        // loop currently doing meaningful work" gates
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
        //   - total IOB near zero (loop not aggressively compensating via basal OR SMB —
        //     basalIob alone is unreliable here: this is an SMB-based loop, so a zero-temp
        //     following an SMB shows up as deeply negative basalIob even while the SMB
        //     itself is still active and doing the real work. Total iob captures both.)
        //   - BG in a reasonable range (above low guard, below high sanity gate)
        //   - Not in post-meal lockout
        if (!isfLearningActive &&
            !inPostMealLockout &&
            abs(activity) < MIN_ACTIVITY &&           // same gate that blocks ISF — basal is dominant
            abs(totalIob) < BASAL_S0_MAX_BASALIOB &&  // total IOB (basal+SMB) near zero — clean basal-only window
            bg > lowGuardMgdl &&
            bg < BASAL_S0_HIGH_GATE_MGDL &&
            profileIsfMgdl > 0.0) {
            // (a cycle that fails these conditions breaks the run — see the else at the end)

            // With near-zero activity, expectedDelta ≈ 0. actualDelta is driven by basal.
            // Positive delta (BG rising) → basal too low → mult UP (more background insulin)
            // Negative delta (BG falling) → basal too high → mult DOWN (less background insulin)
            val unexplainedDelta = shortAvgDelta   // activity ≈ 0, so expected ≈ 0

            // --- BELOW-TARGET GUARD (Signal 0) ---
            // Same blind spot as the main ISF learner had: bg ∈ (lowGuard, target) rising —
            // exactly a rescue-carb/liver rebound out of a recent low — looks identical to
            // "basal too low" (positive unexplainedDelta) but isn't one. The loop having
            // zero-temped through the low means totalIob is often near zero too, so this
            // gate alone doesn't exclude it. Block only the UP direction (more basal) when
            // BG is below target; the DOWN direction (less basal) stays available since
            // reducing basal while below target is never the wrong call.
            val blockUp = unexplainedDelta > 0.0 && bg < targetMgdl
            if (blockUp) {
                s0RunLength = 0; s0RunSign = 0
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[S0] blocked: positive delta=${"%.2f".format(unexplainedDelta)} " +
                                     "but bg=${"%.1f".format(bg)} < target=${"%.1f".format(targetMgdl)} — likely rebound, not basal deficit")
            } else if (abs(unexplainedDelta) >= BASAL_S0_MIN_DELTA_MGDL) {
                // Same direction as the run so far, or the run starts again from this reading.
                val sign = if (unexplainedDelta > 0) 1 else -1
                if (sign == s0RunSign) s0RunLength++ else { s0RunSign = sign; s0RunLength = 1 }
                // Normalise to a fractional adjustment, same clamp philosophy as ISF:
                // allow faster retreat (negative, BG falling too much) than advance (BG rising).
                // Capped far tighter than the old (−1.0, +0.5): with alpha up to 0.09 this bounds
                // a single cycle to ~2% of the multiplier, in line with every other signal here.
                // Still asymmetric — retreat (less basal) may be larger than advance.
                val normAdj = (unexplainedDelta / BASAL_S0_SENSITIVITY)
                    .coerceIn(-BASAL_S0_MAX_ADJ_DOWN, BASAL_S0_MAX_ADJ_UP)
                if (s0RunLength < BASAL_S0_MIN_RUN) {
                    aapsLogger.debug(LTag.APS,
                                     "CircadianLearner Basal[S0] waiting: ${s0RunLength}/$BASAL_S0_MIN_RUN cycles " +
                                         "of delta=${"%.2f".format(unexplainedDelta)}")
                } else {
                val newMult = (basalState.get(dow, hour) + normAdj).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

                val conf  = basalState.getConfidence(dow, hour)
                val alpha = (BASAL_ALPHA * (1.5 - conf)).coerceIn(BASAL_ALPHA * 0.5, BASAL_ALPHA * 1.5)

                writeBasal(dow, hour, newMult, alpha)
                signal0Fired = true

                // Cross-nudge to ISF — no extra gating needed: Signal 0 only ever runs when
                // !isfLearningActive (its own entry condition above), so the main ISF learner
                // can't have also written isfState this same cycle.
                val crossIsfTarget = (isfState.get(dow, hour) + normAdj * CROSS_NUDGE_FRACTION).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                writeIsf(dow, hour, crossIsfTarget, alpha)

                lastBasalSignal = "CyclicDelta: Δ=${"%.2f".format(unexplainedDelta)} normAdj=${"%.3f".format(normAdj)} → ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[S0] h=$hour delta=${"%.2f".format(unexplainedDelta)} " +
                                     "normAdj=${"%.3f".format(normAdj)} activity=${"%.5f".format(activity)} " +
                                     "totalIob=${"%.2f".format(totalIob)} → mult=${"%.3f".format(basalState.get(dow, hour))}")
                }
            } else {
                // Under the noise gate: the story has stopped being told, so the run restarts.
                s0RunLength = 0; s0RunSign = 0
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[S0] skip: delta=${"%.2f".format(unexplainedDelta)} < $BASAL_S0_MIN_DELTA_MGDL noise gate")
            }
        } else {
            // Conditions for a clean basal-only read no longer hold (insulin working, a meal
            // lockout, BG out of range) — whatever run was building is no longer one story.
            s0RunLength = 0; s0RunSign = 0
        }

        // -- Signal 1: Drift-based learning (activity-compensated) -------------
        // Measures sustained BG drift NOT explained by insulin activity during fasting —
        // if the compensated residual drifts up or down over 60+ min, profile basal is wrong.
        //
        // Previously this window only collected samples while totalIob < 0.5U (an SMB tail
        // decaying looks exactly like "basal too high" raw drift), which on an SMB-heavy
        // loop meant the window almost never filled outside overnight hours — basal learning
        // was starved of samples while ISF (gated only on activity being PRESENT) learned
        // constantly. Now insulin's expected effect is integrated alongside each sample and
        // subtracted out of the slope, so the tail no longer masquerades as basal error and
        // collection can continue at everyday IOB levels. A high gross-contamination cap
        // remains: at very large IOB the compensation itself (proportional to activity × ISF)
        // amplifies any ISF miscalibration into the residual, so those cycles are skipped —
        // WITHOUT clearing the window: the accumulator keeps integrating through the gap, so
        // differences across it stay valid (unlike the raw-slope days, a gap no longer
        // corrupts the window).
        driftCumExpectedMgdl += -activity * profileIsfMgdl * 5.0
        if (totalIob < BASAL_DRIFT_MAX_IOB) {
            basalDriftWindow.addLast(DriftSample(now, bg, driftCumExpectedMgdl))
        } else {
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[drift] skip sample: totalIob=${"%.2f".format(totalIob)}U > $BASAL_DRIFT_MAX_IOB gross gate (window kept, accumulator running)")
        }
        while (basalDriftWindow.isNotEmpty() && now - basalDriftWindow.first().timeMs > BASAL_DRIFT_WINDOW_MS)
            basalDriftWindow.removeFirst()

        // Firing condition gated on !signal0Fired (mutual exclusion — Signal 0 runs first in
        // the function and targets the same near-zero-activity regime; if it already fired,
        // skip drift's own adjustment this cycle). The window collection above stays
        // unconditional regardless, since predTrim shares this same buffer and needs it kept
        // fresh even on cycles where Signal 0 fired instead of drift.
        if (!signal0Fired && basalDriftWindow.size >= BASAL_MIN_SAMPLES) {
            val oldest     = basalDriftWindow.first()
            val newest     = basalDriftWindow.last()
            val elapsedHrs = (newest.timeMs - oldest.timeMs) / 3_600_000.0
            if (elapsedHrs >= BASAL_MIN_ELAPSED_HRS) {
                // Compensated drift: BG movement minus insulin's expected contribution over
                // the same span — what's left is attributable to basal (see collection above).
                val driftMgdlPerHr = ((newest.bg - oldest.bg) - (newest.cumExpectedMgdl - oldest.cumExpectedMgdl)) / elapsedHrs
                when {
                    abs(driftMgdlPerHr) < BASAL_MIN_DRIFT_MGDL_HR ->
                        aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr < noise gate")
                    abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR -> {
                        aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr > sanity gate")
                        clearDriftWindow()
                    }
                    else -> {
                        val adjustment = 1.0 + (driftMgdlPerHr / BASAL_DRIFT_SENSITIVITY)
                        // --- BELOW-TARGET GUARD (Signal 1) ---
                        // Same blind spot as Signal 0: a positive drift (BG rising, adjustment > 1.0,
                        // pushing basal UP) measured while current bg is below target is far more
                        // likely a post-low/rescue-carb rebound than a genuine basal deficit. Clamp
                        // the up-direction adjustment to neutral (1.0) in that case; the down-direction
                        // (adjustment < 1.0, BG falling, less basal) is never blocked.
                        // --- POST-MEAL LOCKOUT GUARD (Signal 1) ---
                        // The drift window deliberately keeps running through the post-meal
                        // lockout, on the reasoning that it measures real BG physics. That holds
                        // for the down-direction, but not the up: a fat/protein tail still lifting
                        // BG hours after the meal reads as a sustained unexplained rise, and AAPS's
                        // COB has usually decayed below the 5 g gate by then, so nothing else
                        // catches it. Raising basal on a P/F tail is exactly the corruption the
                        // lockout exists to prevent. Block the up-direction only; a fall during
                        // the lockout still means too much basal and is safe to learn from.
                        val guardedAdjustment = if (adjustment > 1.0 && (bg < targetMgdl || inPostMealLockout)) {
                            aapsLogger.debug(LTag.APS,
                                             "CircadianLearner Basal[drift] up-direction blocked: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr " +
                                                 "but ${if (inPostMealLockout) "post-meal lockout active — likely fat/protein tail" else "bg=${"%.1f".format(bg)} < target=${"%.1f".format(targetMgdl)} — likely rebound"}, not basal deficit")
                            1.0
                        } else adjustment
                        val newMult    = (basalState.get(dow, hour) * guardedAdjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

                        // Confidence-weighted learning speed:
                        val conf  = basalState.getConfidence(dow, hour)
                        val alpha = (BASAL_ALPHA * (1.5 - conf)).coerceIn(BASAL_ALPHA * 0.5, BASAL_ALPHA * 1.5)

                        writeBasal(dow, hour, newMult, alpha)
                        clearDriftWindow()
                        driftFired = true

                        // Cross-nudge to ISF — gated on !isfLearningActive: unlike Signal 0, drift
                        // has no structural exclusion against the main ISF learner firing the same
                        // cycle (different, not-guaranteed-disjoint activity/IOB gates), so this
                        // guard is needed to avoid colliding with that write.
                        if (!isfLearningActive) {
                            val crossIsfMult = (isfState.get(dow, hour) * (1.0 + (guardedAdjustment - 1.0) * CROSS_NUDGE_FRACTION))
                                .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                            writeIsf(dow, hour, crossIsfMult, alpha)
                        }

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
        //
        // SMB CAVEAT: this is an SMB-based loop. A zero-temp following an SMB shows up as
        // deeply negative basalIob even when the SMB (not excess basal) is what's actually
        // suppressing BG. basalIob alone can't tell the two apart. Require totalIob to also
        // be low/negative — if there's a meaningful positive total IOB, an SMB tail is still
        // active and is the more likely explanation; don't blame basal for what the SMB did.
        //
        // Mutually exclusive with drift AND Signal 0 — if either fired this cycle, skip negIOB.
        // BUG FIX (found in audit): previously only excluded drift, not Signal 0. Both target
        // the same quiet-fasting/near-zero-activity regime, so in a sub-target window with
        // low activity, Signal 0 and negIOB could BOTH fire in the same cycle — two separate
        // EWMA pulls toward "less basal" stacking on top of each other in one cycle, driving
        // that hour's mult down faster than either signal's own alpha/cap intends.
        // Not active during post-meal lockout — negative IOB could be meal bolus tail.
        if (!driftFired &&
            !signal0Fired &&
            !inPostMealLockout &&
            bg < targetMgdl &&
            basalIob < BASAL_NEG_IOB_THRESHOLD &&
            totalIob < BASAL_NEG_IOB_TOTAL_GATE &&   // no active SMB tail explaining the suppression
            basalDriftWindow.size >= BASAL_NEG_IOB_MIN_SAMPLES) {

            val belowTargetMgdl = targetMgdl - bg
            val adjustment = 1.0 - (belowTargetMgdl / BASAL_NEG_IOB_SENSITIVITY).coerceIn(0.0, BASAL_NEG_IOB_MAX_ADJUST)
            val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
            writeBasal(dow, hour, newMult, BASAL_ALPHA * 0.5) // half alpha — softer signal
            negIobFired = true

            // Cross-nudge to ISF — gated on !isfLearningActive: negIOB has no structural
            // exclusion against the main ISF learner firing the same cycle.
            if (!isfLearningActive) {
                val crossIsfMult = (isfState.get(dow, hour) * (1.0 - (1.0 - adjustment) * CROSS_NUDGE_FRACTION))
                    .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                writeIsf(dow, hour, crossIsfMult, ISF_ALPHA * 0.5)
            }

            lastBasalSignal = "NegIOB: BG ${"%.1f".format(bg)} < target ${"%.1f".format(targetMgdl)}, basalIOB=${"%.2f".format(basalIob)}U totalIOB=${"%.2f".format(totalIob)}U ? ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Basal[negIOB] h=$hour bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} " +
                                 "basalIob=${"%.2f".format(basalIob)} totalIob=${"%.2f".format(totalIob)} belowTarget=${"%.1f".format(belowTargetMgdl)} ? mult=${"%.3f".format(basalState.get(dow, hour))}")
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
        } else if (!driftFired && !signal0Fired && !negIobFired && !inPostMealLockout) {
            val projectedBg = projectBg60min(targetMgdl)
            if (projectedBg == null) {
                lastPredTrimDebug = "waiting — ${basalDriftWindow.size}/$PRED_MIN_WINDOW_SAMPLES samples"
            } else {
                val projectedError = projectedBg - targetMgdl  // positive = projected high
                val wouldFire = kotlin.math.abs(projectedError) > PRED_TRIM_DEAD_BAND_MGDL
                // --- BELOW-TARGET GUARD (Signal 3) ---
                // Same blind spot as Signals 0 and 1: a projected-high while current BG is below
                // target is more likely a rebound/recovery than a genuine basal deficit. Block
                // only the up-direction; the down-direction (less basal) is never blocked.
                val blockUp = projectedError > 0.0 && bg < targetMgdl
                when {
                    wouldFire && blockUp -> {
                        lastPredTrimDebug = "blocked UP — proj=${"%.1f".format(projectedBg / 18.0)}mmol high but bg=${"%.1f".format(bg / 18.0)}mmol < target"
                        aapsLogger.debug(LTag.APS, "CircadianLearner Basal[predTrim] up-direction blocked: projectedError=${"%.1f".format(projectedError)} but bg below target")
                    }
                    wouldFire -> {
                        // Scale adjustment to projected error magnitude, capped at PRED_TRIM_MAX_ADJUST
                        val rawAdjust  = (projectedError / PRED_TRIM_SENSITIVITY).coerceIn(-PRED_TRIM_MAX_ADJUST, PRED_TRIM_MAX_ADJUST)
                        val adjustment = 1.0 + rawAdjust  // >1 = more basal needed, <1 = less
                        val newMult    = (basalState.get(dow, hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                        writeBasal(dow, hour, newMult, BASAL_ALPHA * 0.5)  // softer alpha
                        predTrimFired = true

                        // Cross-nudge to ISF — gated on !isfLearningActive: predTrim has no
                        // structural exclusion against the main ISF learner firing the same cycle.
                        if (!isfLearningActive) {
                            val crossIsfMult = (isfState.get(dow, hour) * (1.0 + rawAdjust * CROSS_NUDGE_FRACTION))
                                .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                            writeIsf(dow, hour, crossIsfMult, ISF_ALPHA * 0.5)
                        }

                        lastBasalSignal  = "PredTrim: proj=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol/60min ? ×${"%.3f".format(basalState.get(dow, hour))} (h=$hour)"
                        lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | err=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol | " +
                            "rawAdj=${if (rawAdjust > 0) "+" else ""}${"%.3f".format(rawAdjust)} | mult=${"%.3f".format(basalState.get(dow, hour))} (EWMA a=0.03 — slow)"
                        aapsLogger.debug(LTag.APS,
                                         "CircadianLearner Basal[predTrim] h=$hour projectedBg=${"%.1f".format(projectedBg)} " +
                                             "target=${"%.1f".format(targetMgdl)} error=${"%.1f".format(projectedError)} rawAdjust=${"%.3f".format(rawAdjust)} ? mult=${"%.3f".format(basalState.get(dow, hour))}")
                    }
                    else -> {
                        lastPredTrimDebug = "proj=${"%.1f".format(projectedBg / 18.0)}mmol | err=${if (projectedError > 0) "+" else ""}${"%.1f".format(projectedError / 18.0)}mmol | ${if (kotlin.math.abs(projectedError) <= PRED_TRIM_DEAD_BAND_MGDL) "dead-band — no action" else "active"}"
                    }
                }
            }
        }

        // -- Signal 4: Sustained sub-target + negative IOB — ISF and basal too strong -----------
        // The existing signals have a blind spot: drift shows near-zero (loop zero-temping keeps
        // BG flat), negIOB fires point-in-time on basal only, and predTrim is a forward projection.
        // None of them cleanly capture the pattern "I've been sitting below target for 45+ min with
        // the loop backed off — therefore my ISF and basal are too aggressive."
        //
        // SMB CAVEAT: basalIob alone can't distinguish "basal genuinely too strong" from "an SMB
        // is still active and the zero-temp afterward is just bookkeeping." If totalIob is still
        // meaningfully positive, an SMB tail is the more likely explanation for sub-target BG —
        // require totalIob to also be low/negative before concluding ISF/basal need weakening.
        //
        // This signal requires a sustained window of consistent evidence before moving anything,
        // so a single dip or brief excursion below target doesn't corrupt the learner.
        // Both ISF and basal mult are reduced — less aggressive ISF and lower background insulin.
        //
        // Mutually exclusive with drift, negIOB, and predTrim (all three are higher-confidence
        // signals when they fire; this is the fallback for when the loop compensation masks them).
        var subTargetFired = false
        val qualifyingCycle = !driftFired && !signal0Fired && !negIobFired && !predTrimFired &&
            !inPostMealLockout &&
            !bgBelowGuard(bg, lowGuardMgdl) &&              // not a hard low — that's handled elsewhere
            bg < targetMgdl - SUB_TARGET_DEAD_BAND_MGDL && // meaningfully below target, not just touching it
            basalIob < SUB_TARGET_NEG_IOB_GATE &&           // loop is actively withholding basal
            totalIob < SUB_TARGET_TOTAL_IOB_GATE            // no active SMB tail explaining the sub-target BG

        if (qualifyingCycle) {
            subTargetNegIobWindow.addLast(now to bg)
        } else {
            // Any cycle that breaks either condition resets the window — stale signal cleared
            if (subTargetNegIobWindow.isNotEmpty()) {
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Basal[subTarget] window reset — condition broke " +
                                     "(bg=${"%.1f".format(bg)} target=${"%.1f".format(targetMgdl)} basalIob=${"%.2f".format(basalIob)} " +
                                     "totalIob=${"%.2f".format(totalIob)} drift=$driftFired negIob=$negIobFired postMeal=$inPostMealLockout)")
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
                writeBasal(dow, hour, newBasMult, BASAL_ALPHA * 0.5)

                // ISF mult DOWN → dosingISF = profileISF / lowerMult → dosingISF UP → less aggressive
                val newIsfMult   = (isfState.get(dow, hour) * adjustment).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                writeIsf(dow, hour, newIsfMult, ISF_ALPHA * 0.5)

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
    /**
     * Projects BG 60 minutes ahead using a kinematic (2nd-order) model rather than
     * pure linear extrapolation.
     *
     * PREVIOUSLY: projectedBg = newestBg + (driftMgdlPerHr * horizon) — a straight line.
     * BG does not move in straight lines; it has acceleration (the curve flattening out
     * after a peak, or steepening into one). A linear model over-corrects exactly when the
     * curve is already flattening — e.g. a sustained high that's just started coming down
     * gets projected as if it'll keep falling at the CURRENT rate for the full 60 minutes,
     * when in reality deceleration means it's likely to level off well before then. That
     * false low projection then drives PredTrim to cut basal harder than warranted right as
     * BG is naturally stabilising — precisely the deadlock-prone scenario already described
     * in the predTrim caller's comments (suppressed when aggrNudge notEnough is active).
     *
     * NOW: projectedBg = currentBg + v*t + 0.5*a*t² — a standard kinematic equation.
     *   v (velocity)     = driftMgdlPerHr, from the same basalDriftWindow regression as before
     *   a (acceleration) = computeAcceleration(), already computed elsewhere in this file for
     *                      the aggression nudge, converted from mg/dL per (5min)² to mg/dL/hr²
     *   t (time)         = PRED_TRIM_HORIZON_HRS (1.0 = 60 min)
     *
     * SAFETY: acceleration is extrapolated furthest of the three terms (t² vs t), so a single
     * noisy 3-point computeAcceleration() reading could swing the projection wildly if used
     * unclamped. The acceleration TERM (0.5*a*t²) is clamped to PRED_ACCEL_MAX_CONTRIB_MGDL
     * before being added — it can nudge the linear projection but never dominate or reverse it.
     */
    private fun projectBg60min(targetMgdl: Double): Double? {
        if (basalDriftWindow.size < PRED_MIN_WINDOW_SAMPLES) return null
        val oldest     = basalDriftWindow.first()
        val newest     = basalDriftWindow.last()
        val elapsedHrs = (newest.timeMs - oldest.timeMs) / 3_600_000.0
        if (elapsedHrs < 0.2) return null  // need at least 12 min of spread
        // COMPENSATED drift, same as Signal 1: the window now collects samples at everyday
        // IOB levels, so the raw slope routinely contains a decaying SMB tail. PredTrim
        // adjusts BASAL, so its velocity must be the basal-attributable residual — projecting
        // the raw slope here would cut basal for what an SMB did (exactly the contamination
        // the old totalIob<0.5 collection gate existed to prevent).
        val driftMgdlPerHr = ((newest.bg - oldest.bg) - (newest.cumExpectedMgdl - oldest.cumExpectedMgdl)) / elapsedHrs
        if (abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR) return null  // sanity gate

        val t = PRED_TRIM_HORIZON_HRS
        val linearTerm = driftMgdlPerHr * t

        // computeAcceleration() is mg/dL per (5min)² — convert to mg/dL/hr² by the ratio
        // of time units squared: (60min/5min)² = 144.
        val accelMgdlPerHr2 = computeAcceleration() * ACCEL_5MIN2_TO_HR2
        val rawAccelTerm     = 0.5 * accelMgdlPerHr2 * t * t
        val accelTerm         = rawAccelTerm.coerceIn(-PRED_ACCEL_MAX_CONTRIB_MGDL, PRED_ACCEL_MAX_CONTRIB_MGDL)

        val projected = newest.bg + linearTerm + accelTerm

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner predTrim kinematic: drift=${"%.2f".format(driftMgdlPerHr)}mg/dL/hr " +
                             "accel=${"%.2f".format(accelMgdlPerHr2)}mg/dL/hr2 linearTerm=${"%.1f".format(linearTerm)} " +
                             "rawAccelTerm=${"%.1f".format(rawAccelTerm)} clampedAccelTerm=${"%.1f".format(accelTerm)} " +
                             "? projected=${"%.1f".format(projected)} (was linear-only=${"%.1f".format(newest.bg + linearTerm)})")

        return projected
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
        inPostMealLockout: Boolean = false,
        fastingPeakMins:  Double  = 90.0,
        fastingDiaMins:   Double  = 300.0
    ) {
        val currentCeil = aggrState.get(dow, hour)

        // -- Penalty signal 1: Rollercoaster ----------------------------------
        // Re-computed here (not cached from update()'s earlier call) so this
        // sees the current BG reading that was just added to bgHistory.
        val rollercoaster = detectRollercoaster(targetMgdl, lowGuardMgdl)
        if (rollercoaster) {
            consecutiveStableCycles = 0
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
                writeAggr(dow, hour, penalised, AGGR_ALPHA_PENALTY)
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
        if (bgBelowGuard(bg, lowGuardMgdl) && isFasting && !inPostMealLockout) {
            consecutiveStableCycles = 0
            // All hard low penalties fire ONCE per low event (30-min gate).
            // Fire once, observe, let the loop and rebound window handle delivery.
            // Don't hammer ISF/basal/ceiling every 5 min while BG stays low.
            val nowMs = System.currentTimeMillis()
            val msSinceLastHardLow = if (lastHardLowPenaltyMs > 0L) nowMs - lastHardLowPenaltyMs else Long.MAX_VALUE
            val isNewLowEvent = msSinceLastHardLow > HARD_LOW_BASAL_GATE_MS
            lastPenaltyWasFasting = isFasting
            // This block is the single source of truth for the hard-low ISF/basal/ceiling
            // penalty (the duplicate version that used to live in update() was removed).
            //
            // BUG FIX: lastHardLowPenaltyMs previously updated on EVERY cycle BG was below
            // guard, not just when a penalty actually fired. That meant it tracked "time since
            // the last low READING," not "time since the last penalized EVENT." A BG that sat
            // low continuously refreshed this timestamp every 5 minutes; if it then recovered
            // and dipped low again within 30 min of that LAST READING (not the event start),
            // isNewLowEvent read false and the genuinely new low got no penalty at all. Now
            // only stamped inside the isNewLowEvent block below, so it tracks event starts.

            if (isNewLowEvent) {
                lastHardLowPenaltyMs = nowMs
                // BUG FIX (found in audit): this block previously only stamped
                // lastHardLowPenaltyMs (used for this signal's own 30-min re-fire gate) but
                // never lastPenaltyMs/lastPenaltyReason — the fields the rebound blindfolds
                // (FuelTrim's isLowRecovery at the aboveBand check, and the main notEnough
                // pause) actually key on. Rollercoaster and soft-low both stamp those; hard-low
                // didn't. Result: an ISOLATED hard low (one that doesn't happen to coincide
                // with a prior soft-low or rollercoaster reading on the same approach) left the
                // rebound blindfold completely unarmed — a rescue-carb/liver rebound right after
                // could be read as "not enough insulin" and stack basal-up/ISF-down onto the
                // rebound, which is precisely the scenario the blindfold exists to prevent.
                lastPenaltyMs     = nowMs
                lastPenaltyReason = "hard low"
                // Short term: ceiling cut — applied directly (alpha=1.0), not EWMA-softened.
                // Using AGGR_ALPHA_PENALTY=0.25 here only moved the ceiling by ~2.5% which
                // is invisible in the table and has no meaningful effect on dosing.
                // Direct write ensures the penalty is actually felt this cycle.
                val penalised = (currentCeil * AGGR_PENALTY_HARD_LOW).coerceAtLeast(AGGR_CEIL_MIN)
                // Day bucket takes the full cut immediately; the cross-day baseline takes a slow
                // trickle so a low that repeats at this hour across nights generalises to every
                // day, while a one-off stays where it happened. See writeAggrHardLow.
                writeAggrHardLow(dow, hour, penalised)
                // Basal: mult DOWN — less background insulin
                val d = dow.coerceIn(0, 6)
                val prevBasVal   = basalState.days[d].get(hour)
                val nudgedBasMult = (prevBasVal * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
                writeBasalDayOnly(dow, hour, nudgedBasMult, 1.0)
                // ISF: mult DOWN ? dosingISF = profileISF / lowerMult ? dosingISF UP ? less aggressive ? less insulin ?
                val prevIsfVal   = isfState.days[d].get(hour)
                val nudgedIsfMult = (prevIsfVal * AGGR_HARD_LOW_BASAL_NUDGE).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                writeIsfDayOnly(dow, hour, nudgedIsfMult, 1.0)
                // ...and the same cut, scaled down, to the hours that actually delivered the
                // insulin now bottoming out. See retroAttributeHardLow.
                retroAttributeHardLow(dow, hour, fastingPeakMins, fastingDiaMins)
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
        // Previous check was bg < lowGuardMgdl which is unreachable — the hard-low penalty
        // block above (gated on bg < lowGuardMgdl) already handles and returns for that case.
        // Correct condition here: within SOFT_LOW_APPROACH_MGDL above guard, not below it.
        val approachingLow = isFasting && !inPostMealLockout &&
            // Complement of the hard-low test, so a BG level with the guard falls to this signal
            // rather than through the gap between the two.
            !bgBelowGuard(bg, lowGuardMgdl) && bg < lowGuardMgdl + SOFT_LOW_APPROACH_MGDL &&
            delta < SOFT_LOW_DELTA_MGDL && iob > SOFT_LOW_MIN_IOB
        if (approachingLow) {
            consecutiveStableCycles = 0
            val penalised = (currentCeil * AGGR_PENALTY_SOFT_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            writeAggr(dow, hour, penalised, AGGR_ALPHA_PENALTY)
            lastPenaltyMs   = System.currentTimeMillis()
            lastPenaltyWasFasting = isFasting
            lastPenaltyReason = "soft low approach"
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour SOFT_LOW_APPROACH bg=$bg delta=$delta fasting=$isFasting ? ceil=%.3f"
                                 .format(aggrState.get(dow, hour)))
            return
        }

        // -- Recovery signal: good outcome ------------------------------------
        // BUG FIX: stableNearTarget previously used a symmetric ±18mg/dL (±1mmol) band,
        // which let it fire when BG was BELOW target (e.g. bg=89, target=99 — 10mg/dL low,
        // well within the symmetric band, flagged "stable"). Recovering the ceiling means
        // becoming MORE aggressive — that should only happen when BG is stable AT OR ABOVE
        // target, never when it's stable-but-low. A loop sitting quietly below target with
        // basal backed off is not evidence the ceiling was too conservative; it's evidence
        // the current (suppressed) ceiling is appropriate or even still slightly too high.
        val stableNearTarget = bg >= targetMgdl - STABLE_RECOVERY_LOW_TOLERANCE_MGDL &&
            bg - targetMgdl < STABLE_BAND_MGDL &&
            abs(delta) < STABLE_DELTA_MGDL
        if (stableNearTarget) {
            consecutiveStableCycles++
            if (currentCeil < 1.0) {
                // Escalating recovery step — the longer stability has genuinely held, the faster
                // we trust it and speed up unwinding a prior penalty, mirroring how the
                // rollercoaster penalty escalates with repetition in the other direction.
                val escalatedStep = (AGGR_RECOVERY_STEP + consecutiveStableCycles * AGGR_RECOVERY_ESCALATION_PER_CYCLE)
                    .coerceAtMost(AGGR_RECOVERY_MAX_STEP)
                val recovered = (currentCeil + escalatedStep).coerceAtMost(AGGR_CEIL_MAX)
                writeAggr(dow, hour, recovered, AGGR_ALPHA_RECOVERY)
                aapsLogger.debug(LTag.APS,
                                 "CircadianLearner Aggr h=$hour STABLE_RECOVERY (streak=$consecutiveStableCycles step=%.4f) ? ceil=%.3f"
                                     .format(escalatedStep, aggrState.get(dow, hour)))
            }
        } else {
            consecutiveStableCycles = 0
        }
    }

    // -- Retroactive low attribution -------------------------------------------

    /**
     * Stamps this bucket if the cycle was fasting with insulin on board, and drops buckets that
     * have aged out of [RETRO_LOOKBACK_MINS].
     *
     * Pruning here rather than at read time is what lets [hadFastingInsulin] be a plain lookup:
     * the attribution only ever runs from a hard-low cycle, which has just recorded, so anything
     * still in the map is inside the window by construction.
     */
    private fun recordInsulinPresence(nowMs: Long, hour: Int, dow: Int, iobU: Double, fasting: Boolean) {
        if (fasting && iobU >= RETRO_MIN_IOB_U) {
            val key = bucketKey(dow, hour)
            if (nowMs - (lastFastingInsulinMs[key] ?: 0L) >= RETRO_PERSIST_INTERVAL_MS) insulinPresenceDirty = true
            lastFastingInsulinMs[key] = nowMs
        }
        val cutoff = nowMs - RETRO_LOOKBACK_MINS * 60_000L
        val expired = lastFastingInsulinMs.entries.filter { it.value < cutoff }.map { it.key }
        if (expired.isNotEmpty()) {
            expired.forEach { lastFastingInsulinMs.remove(it) }
            insulinPresenceDirty = true
        }
    }

    private fun bucketKey(dow: Int, hour: Int): Int = dow.coerceIn(0, 6) * 24 + hour.coerceIn(0, 23)

    /** True if this bucket dosed while fasting inside the lookback window. */
    private fun hadFastingInsulin(key: Int): Boolean = lastFastingInsulinMs.containsKey(key)

    /**
     * Charges a hard low back to the hours that DOSED the insulin, not just the hour it surfaced in.
     *
     * The hole this closes: the hard-low penalty writes to the hour the low is observed in. But
     * insulin peaks around [LearnedInsulinProfile.FALLBACK_PEAK_MINS] after delivery and keeps
     * working for hours after that — BG bottoming out at 07:30 is being driven mostly by insulin
     * delivered between 05:00 and 06:30. Hour 7 gets cut, the hours that over-dosed are left
     * exactly as aggressive as they were, and the same low happens again tomorrow. The 7AM bucket
     * ends up carrying a correction for a mistake it did not make, which also makes it too weak
     * for the highs it does cause.
     *
     * Attribution is the insulin activity curve itself, integrated minute by minute over the last
     * [RETRO_LOOKBACK_MINS] and summed into whichever clock hour each minute fell in — so how the
     * blame splits follows the learned peak/DIA rather than a fixed table, and respects where in
     * the hour the low actually happened. Weights are normalised so the most-responsible earlier
     * hour takes the SAME cut the observed hour just took, and hours below [RETRO_MIN_WEIGHT] of
     * that take none: this is meant to move the two or three hours that dosed, not to smear a
     * tenth of a penalty across the whole morning.
     *
     * Two gates keep it from blaming the clock instead of the insulin:
     *  - an hour is only charged if [insulinPresence] has a FASTING cycle for it with real IOB.
     *    An hour the loop spent zero-temping did not cause this low, whatever the kernel says
     *    about its lag, and an hour spent in a meal mode was dosing for food — neither belongs in
     *    the fasting profile. No record at all (fresh start) also means no blame.
     *  - the observed hour is skipped here; it already took its full penalty at the call site.
     *
     * Writes are day-scoped and pinned to :30, since the kernel has already decided the split
     * across hours and a minute-of-hour bleed on top would double-count it. The ceiling keeps the
     * same slow global trickle as the primary penalty, scaled by weight, so a low that recurs at
     * the same hours across days still generalises across weekdays.
     */
    private fun retroAttributeHardLow(dow: Int, hour: Int, peakMins: Double, diaMins: Double) {
        val observed       = bucketKey(dow, hour)
        val nowMinuteOfDay = hour.coerceIn(0, 23) * 60 + cycleMinute
        // Activity mass per preceding clock hour. Derived from the injected hour/minute rather
        // than wall-clock so it stays consistent with everything else this cycle wrote.
        val mass = HashMap<Int, Double>()
        for (lag in 1..RETRO_LOOKBACK_MINS) {
            val a = InsulinActivityCurve.activityFraction(lag.toDouble(), peakMins, diaMins)
            if (a <= 0.0) continue
            val past     = nowMinuteOfDay - lag
            val dayShift = Math.floorDiv(past, MINUTES_PER_DAY)
            val h        = Math.floorMod(past, MINUTES_PER_DAY) / 60
            val d        = Math.floorMod(dow + dayShift, 7)
            val key      = bucketKey(d, h)
            if (key == observed) continue
            mass[key] = (mass[key] ?: 0.0) + a
        }
        val peakMass = mass.values.maxOrNull() ?: 0.0
        if (peakMass <= 0.0) { lastRetroAttribution = "h=$hour low — nothing to attribute back"; return }

        val applied = StringBuilder()
        var skippedNoInsulin = 0
        mass.entries.sortedByDescending { it.value }.forEach { (key, m) ->
            val w = (m / peakMass).coerceIn(0.0, 1.0)
            if (w < RETRO_MIN_WEIGHT) return@forEach
            if (!hadFastingInsulin(key)) { skippedNoInsulin++; return@forEach }
            val d = key / 24
            val h = key % 24
            val isfPrev = isfState.days[d].get(h)
            val basPrev = basalState.days[d].get(h)
            val ceilPrev = aggrState.days[d].get(h)
            val isfNew  = (isfPrev  * (1.0 - (1.0 - AGGR_HARD_LOW_BASAL_NUDGE) * w)).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
            val basNew  = (basPrev  * (1.0 - (1.0 - AGGR_HARD_LOW_BASAL_NUDGE) * w)).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
            val ceilNew = (ceilPrev * (1.0 - (1.0 - AGGR_PENALTY_HARD_LOW)     * w)).coerceAtLeast(AGGR_CEIL_MIN)
            isfState   = isfState.updatedSplit(d, h, RETRO_WRITE_MINUTE, isfNew, 1.0, 0.0)
            basalState = basalState.updatedSplit(d, h, RETRO_WRITE_MINUTE, basNew, 1.0, 0.0)
            aggrState  = aggrState.updatedSplit(d, h, RETRO_WRITE_MINUTE, ceilNew, 1.0, AGGR_HARD_LOW_GLOBAL_ALPHA * w)
            applied.append(if (applied.isEmpty()) "" else ", ")
                .append("h=$h(${DayOfWeekCircadianState.DAY_LABELS[d]}) w=${"%.2f".format(w)} ")
                .append("isf ${"%.3f".format(isfPrev)}→${"%.3f".format(isfNew)} bas ${"%.3f".format(basPrev)}→${"%.3f".format(basNew)}")
        }
        lastRetroAttribution = when {
            applied.isNotEmpty() -> "low at h=$hour charged back to $applied" +
                (if (skippedNoInsulin > 0) " (${skippedNoInsulin} hour(s) skipped — no fasting insulin recorded)" else "")
            skippedNoInsulin > 0 -> "low at h=$hour — no earlier hour had fasting insulin on board, nothing charged back"
            else                 -> "low at h=$hour — nothing to attribute back"
        }
        aapsLogger.debug(LTag.APS, "CircadianLearner RETRO $lastRetroAttribution")
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
            val presence = JSONObject()
            lastFastingInsulinMs.forEach { (bucket, ms) -> presence.put(bucket.toString(), ms) }
            val json = JSONObject().apply {
                put("isf",   isfState.toJson())
                put("basal", basalState.toJson())
                put("aggr",  aggrState.toJson())
                put(K_PRESENCE, presence)
            }
            sp.edit { putString(StringKey.ApsSmartInsulinCircadianState.key, json.toString()) }
            insulinPresenceDirty = false
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

            // optJSONObject, not getJSONObject: state saved before the retro attribution existed
            // has no such key, and that must load normally rather than throwing away the ISF,
            // basal and aggr buckets that were read a line earlier.
            json.optJSONObject(K_PRESENCE)?.let { presence ->
                lastFastingInsulinMs.clear()
                presence.keys().forEach { k ->
                    k.toIntOrNull()?.let { bucket -> lastFastingInsulinMs[bucket] = presence.optLong(k, 0L) }
                }
            }
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner restored (day-of-week), ${lastFastingInsulinMs.size} insulin-presence buckets")
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
        lastFastingInsulinMs.clear()
        insulinPresenceDirty = false
        lastRetroAttribution = "No low attributed yet"
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
    private fun currentMinute(): Int = Calendar.getInstance().get(Calendar.MINUTE)
    private fun currentDow(): Int  = DayOfWeekCircadianState.currentDayOfWeek()

    // -- Constants -------------------------------------------------------------

    companion object {
        // Cross-nudge — a signal measured for one quantity (ISF or basal) also nudges the OTHER
        // a smaller amount in the same direction, as a lower-confidence co-movement prior: the
        // most likely cause of a genuine, sustained miscalibration (exercise, illness, hormones,
        // weight change) usually shifts whole-body sensitivity broadly, not just the one quantity
        // that happened to be directly measurable this cycle. Deliberately NOT applied by giving
        // ISF a full copy of basal's 5 signals — those all fire specifically when insulin activity
        // is near zero, which is exactly the regime where ISF has no correction event to measure
        // a sensitivity ratio against at all (see updateIsfLearner's own activity gate). Attributing
        // quiet-fasting drift to ISF would mean learning from data where ISF wasn't even in play.
        private const val CROSS_NUDGE_FRACTION = 0.15

        // ISF learner
        private const val ISF_ALPHA              = 0.04   // slow EWMA — each sample moves ~8%
        private const val ISF_MULT_MIN           = 0.7
        private const val ISF_MULT_MAX           = 1.5
        private const val MIN_ACTIVITY           = 0.005  // min IOB activity to learn from
        // Admission floor for the ISF signal. normDeviation is deviation/expectedDelta, so this
        // is the denominator gate: below it, shortAvgDelta noise (±1–2 mg/dL) dominates the
        // numerator and the learner is fitting sensor noise. 5 mg/dL of expected movement needs
        // roughly a real SMB tail to be acting; quieter cycles are the basal learner's regime
        // (it fires when activity < MIN_ACTIVITY), so nothing is left unlearned by raising this.
        private const val MIN_EXPECTED_DELTA_MGDL = 5.0
        // Expected movement at which a sample carries full weight (~0.5 mmol/5min). Between the
        // floor and this, samples ramp in linearly rather than counting the same as a strong one.
        private const val ISF_FULL_WEIGHT_DELTA_MGDL = 9.0
        // Ratio -> multiplier-offset gain. See the comment at the multTarget calculation.
        private const val ISF_NORM_GAIN          = 0.2

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
        // Gross-contamination cap only, NOT the old "must be nearly insulin-free" gate: the
        // drift window is activity-compensated now (insulin's expected effect is subtracted
        // from the slope), so everyday SMB-tail IOB no longer corrupts the signal. Above this
        // cap the compensation term itself (activity × ISF) gets large enough that any ISF
        // miscalibration is amplified into the residual — skip those cycles (window kept).
        private const val BASAL_DRIFT_MAX_IOB      = 3.0

        // Signal 0: per-cycle unexplained-delta basal learning
        // Fires when ISF is NOT learning (activity < MIN_ACTIVITY) so they're mutually exclusive.
        // With near-zero IOB activity, BG delta is driven by basal — use it directly.
        // Same philosophy as ISF learning but for background insulin.
        private const val BASAL_S0_MAX_BASALIOB    = 0.20   // basalIob within ±0.20U — loop not aggressively compensating
        private const val BASAL_S0_HIGH_GATE_MGDL  = 180.0  // don't learn above 10 mmol — likely post-meal contamination
        private const val BASAL_S0_MIN_DELTA_MGDL  = 1.0    // noise gate — same as ISF MIN_EXPECTED_DELTA_MGDL
        private const val BASAL_S0_MAX_ADJ_DOWN    = 0.25   // per-cycle clamp, safety direction
        private const val BASAL_S0_MAX_ADJ_UP      = 0.20
        /**
         * Qualifying cycles in a row before Signal 0 writes anything. The drift signal has always
         * required a window; this one fired on a single reading, so one noisy delta could move an
         * hour's basal on its own. Three readings is 10-15 minutes of the same story.
         */
        private const val BASAL_S0_MIN_RUN         = 3
        /**
         * Unexplained delta, per 5 min, that asks for a FULL-scale multiplier change.
         *
         * Was 5.0, which meant an ordinary quiet-fasting drift of −3 mg/dL (0.17 mmol/5min, about
         * 2 mmol/hr) asked for basal ×0.40 — a 60% cut from one reading — and only the rails and
         * the EWMA stopped it landing. On a fresh bucket (confidence 0, so alpha at its 0.09
         * ceiling) that still moved basal 4.5% and ISF 0.8% in a SINGLE cycle, which is more than
         * the drift signal moves in an hour and made this by far the twitchiest knob here.
         *
         * 15 mg/dL per 5 min is ~1 mmol/hr of unexplained movement for a full-scale ask, so
         * everyday drift now asks for an everyday correction.
         */
        private const val BASAL_S0_SENSITIVITY     = 15.0

        // Predictive basal trim — forward-projects BG using current drift rate
        // and pre-emptively adjusts basal multiplier if projection is off target.
        // Fires INSTEAD of waiting for full drift window to confirm — faster convergence.
        private const val PRED_TRIM_HORIZON_HRS   = 1.0    // project 60 min ahead
        // Kinematic projection constants (see projectBg60min):
        private const val ACCEL_5MIN2_TO_HR2          = 144.0  // (60/5)^2 — converts computeAcceleration()'s
        // mg/dL per (5min)^2 units to mg/dL/hr^2
        private const val PRED_ACCEL_MAX_CONTRIB_MGDL = 27.0   // ~1.5 mmol cap on the acceleration term's
        // contribution to the 60-min projection — it can
        // refine the linear projection but never dominate
        // or reverse it from a single noisy reading
        private const val PRED_TRIM_DEAD_BAND_MGDL = 9.0   // ~0.5 mmol — ignore small projected errors
        private const val PRED_TRIM_MAX_ADJUST     = 0.06  // cap at 6% per firing
        private const val PRED_TRIM_SENSITIVITY    = 36.0  // 2 mmol projected error ? full adjustment
        private const val PRED_MIN_WINDOW_SAMPLES  = 6     // ~30 min of data before projecting

        // Negative IOB compensation signal
        private const val BASAL_NEG_IOB_THRESHOLD   = -0.15          // basalIob must be at least this negative (U)
        private const val BASAL_NEG_IOB_TOTAL_GATE  = 0.30           // totalIob must be below this — rules out active SMB tail
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
        private const val SUB_TARGET_TOTAL_IOB_GATE    = 0.30          // totalIob must be below this — rules out active SMB tail
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
        // Rate at which the day bucket approaches the nudge target (EWMA alpha). At 0.04 it
        // covers half the remaining gap in ~17 cycles (~85 min of occupying that hour). This is
        // a RATE now, not a step size — it sets how fast the target is reached, never how far.
        private const val AGGR_NUDGE_SCALE        = 0.04
        // Ceiling deficit -> displacement from the physics baseline. At 1.0 the mapping is
        // direct and legible: a ceiling of 0.85 ("run this hour at 85% aggressiveness") parks the
        // multipliers 15% below the cross-day baseline, and no further.
        private const val AGGR_NUDGE_AUTHORITY    = 1.0
        // Hard cap on that displacement. The ceiling floor is AGGR_CEIL_MIN (0.60), which would
        // otherwise map to a 40% cut; the nudge is a heuristic correction sitting on top of
        // measured physics, so it is not allowed to move dosing by more than a quarter on its own.
        private const val AGGR_NUDGE_MAX_DISPLACEMENT = 0.25
        // Dead-band restoring force: rate at which a displaced day bucket returns toward the
        // cross-day baseline while the ceiling is neutral. At 0.02, half the excess is unwound in
        // ~35 cycles at that hour — about three days — so an unrepeated bad night expires instead
        // of being permanent, while a pattern that keeps re-firing the ceiling holds its ground.
        private const val AGGR_NUDGE_DECAY_ALPHA  = 0.02
        // Displacement from baseline the decay tolerates without acting. Real day-of-week physics
        // differences live inside this band and are left alone; only larger excursions, which
        // only the nudge can produce, are unwound.
        private const val AGGR_NUDGE_DECAY_BAND   = 0.05
        private const val AGGR_NUDGE_COOLDOWN_MS  = 120 * 60_000L  // 120 min penalty cooldown window
        private const val AGGR_NUDGE_ATTN_FASTING = 0.35           // attenuated scale during cooldown — fasting penalty (more likely profile issue)
        private const val AGGR_NUDGE_ATTN_MEAL    = 0.15           // attenuated scale during cooldown — meal/post-meal penalty (less likely profile issue)

        // Aggressiveness ceiling
        private const val AGGR_ALPHA_PENALTY    = 0.25   // penalty applies quickly
        private const val AGGR_ALPHA_RECOVERY   = 0.04   // recovery is slow
        private const val AGGR_PENALTY_ROLLER       = 0.85   // 15% cut on rollercoaster
        private const val AGGR_PENALTY_HARD_LOW     = 0.90   // 10% short-term ceiling cut when BG crosses below low guard
        private const val AGGR_HARD_LOW_BASAL_NUDGE = 0.90   // 10% long-term basal nudge down on hard low (once per event)
        // Fraction of a hard-low ceiling penalty that reaches the cross-day (global) bucket.
        // At 0.05 a single low moves the cross-day ceiling for that hour by ~0.5%, but three or
        // four lows at the same hour on different nights compound into a real suppression that
        // applies on nights it has not happened yet — which is the point: a 4AM low is a property
        // of 4AM, not of Tuesday. Deliberately much smaller than the day-bucket write so one bad
        // night can never rewrite the baseline for the whole week.
        private const val AGGR_HARD_LOW_GLOBAL_ALPHA = 0.05
        private const val HARD_LOW_BASAL_GATE_MS    = 30 * 60_000L  // basal nudge only fires once per 30-min low event
        private const val AGGR_PENALTY_SOFT_LOW     = 0.90   // 10% cut on soft low approach
        private const val AGGR_RECOVERY_STEP    = 0.01   // +1% per stable cycle (base — see escalation below)
        // Recovery escalation: step grows with consecutiveStableCycles, capped at 4x the base
        // step once ~30 consecutive stable cycles (~2.5h) have genuinely held — penalties cut
        // hard and instantly, so recovery should eventually speed up too rather than staying a
        // flat +1%/cycle crawl that leaves a suppressed ceiling costing time-in-range long after
        // the cause has resolved.
        private const val AGGR_RECOVERY_ESCALATION_PER_CYCLE = 0.001
        private const val AGGR_RECOVERY_MAX_STEP             = 0.04
        private const val AGGR_CEIL_MIN         = 0.60
        private const val AGGR_CEIL_MAX         = 1.40
        private const val STABLE_BAND_MGDL      = 18.0   // ±1 mmol = stable
        private const val STABLE_DELTA_MGDL     = 1.5    // mg/dL per 5min = flat
        private const val STABLE_RECOVERY_LOW_TOLERANCE_MGDL = 3.6  // ~0.2 mmol grace below target — noise tolerance only, not a real "below target" allowance
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
        // Kept equal to AGGR_CEIL_MAX (1.40) — FuelTrim's aboveBand branch clamps to this bound
        // directly, so if it were left lower than AGGR_CEIL_MAX, FuelTrim would actively pull a
        // ceiling back down whenever the slow-recovery mechanism had legitimately earned it above
        // this value, undermining the point of raising AGGR_CEIL_MAX at all.
        private const val TRIM_CEIL_MAX             = 1.40   // ceiling upper bound from trim
        private const val TRIM_CEIL_MIN             = 0.80   // ceiling lower bound from trim
        private const val TRIM_LONG_TERM_FRACTION   = 0.50   // long-term nudge = 50% of trim magnitude
        private const val TRIM_DECAY_RATE           = 0.70   // trim decays by 30% each in-range cycle

        // General
        private const val COB_THRESHOLD_G = 5.0   // ignore cycles with active carbs
        private const val MAX_HISTORY     = 30     // ring buffer size

        // -- Retroactive low attribution ------------------------------------
        /** How far back a low can be charged. Beyond 4h the activity curve contributes
         *  almost nothing, and the further back it reaches the more other causes it collects. */
        private const val RETRO_LOOKBACK_MINS      = 240
        /** Share of the most-responsible hour's cut below which an hour is left alone. Keeps this
         *  to the two or three hours that actually dosed instead of smearing across the morning. */
        private const val RETRO_MIN_WEIGHT         = 0.15
        /** IOB that counts as "insulin was working in this hour". */
        private const val RETRO_MIN_IOB_U          = 0.05
        /** How stale a bucket's stamp must be before refreshing it is worth an SP write. Well under
         *  [RETRO_LOOKBACK_MINS], so a restart can never land on a stamp old enough to have expired
         *  when it should not have. */
        private const val RETRO_PERSIST_INTERVAL_MS = 10 * 60_000L
        private const val K_PRESENCE               = "insulinPresence"
        /** Retro writes land at :30 — dead centre of the bucket, so [CircadianState.updatedSmoothed]
         *  bleeds nothing into the neighbour. The kernel already decided the split across hours. */
        private const val RETRO_WRITE_MINUTE       = 30
        private const val MINUTES_PER_DAY          = 24 * 60
    }
}