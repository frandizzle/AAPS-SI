package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Episode-outcome learning for the UAM *entry* SMB fraction — the front-loading of the first
 * few SMBs after a UAM mode auto-fires.
 *
 * This is a SHAPE knob (how early the insulin arrives), deliberately separated from
 * [ModeIsfLearner]'s MAGNITUDE knob (how much insulin overall). Two learners on one error
 * signal would double-correct every mistake, so the two are arbitrated by the TIMING of the
 * evidence — shape and magnitude have distinguishable signatures:
 *
 *   - Low EARLY (within [ENTRY_ATTRIBUTION_MS] of entry, while the entry burst is peaking)
 *       → too front-loaded → fraction DOWN. [ModeIsfLearner] skips that episode entirely.
 *   - Big BG excursion after entry, or a peak well above target, BUT the episode still ended
 *       on target → total insulin was right, it just arrived too late → fraction UP, by a step
 *       that scales with how far over it went.
 *   - Ended HIGH and BG has FLATTENED OFF → nothing is still bringing it down, so the upfront
 *       dose fell short → fraction UP, scaled by how far over target it stalled. Judged at the
 *       tail deadline or the moment a P/F takeover starts, whichever comes first — a takeover is
 *       itself the statement that this mode didn't finish the job. Never before the entry insulin
 *       has had its peak effect though (see [VERDICT_PEAK_MARGIN_MS]): BG still flat while the
 *       dose is only half-way into its action says nothing about the dose.
 *   - Ended HIGH but BG is STILL COMING DOWN at a real rate → something (a fat/protein tail, or
 *       just insulin still working) is resolving it → fraction unchanged. This gate is the whole
 *       reason the rule above is safe: "still high at 75min" is true both for a meal the entry
 *       dose under-covered AND for every meal with a fat/protein tail, and most meals have one.
 *       Without it the fraction would ratchet up on the majority case and eventually over-dose
 *       the mornings that had nothing to fight. Flat means the holding-up is over.
 *   - Low LATE (in the settling tail) → magnitude problem → fraction unchanged.
 *
 * Only the UAM entry modes are learned — P/F is a tail correction, not a meal-entry event, and
 * the manually-activated modes don't use the entry-burst mechanism at all.
 *
 * The learned value is an ADDITIVE offset to the user's configured per-mode fraction. The offset
 * rails are wide ([OFFSET_MIN]..[OFFSET_MAX]); what actually bounds the result is
 * [FRACTION_MIN]..[FRACTION_MAX], so a mode can be learned anywhere in 0.1..1.0 whatever it was
 * configured at. The
 * entry SMB *count* stays manual by design: it's a coarse integer whose effects overlap heavily
 * with the fraction, and learning both would re-create the double-actuation problem this
 * arbitration exists to avoid.
 */
@SingleIn(AppScope::class)
class UamEntryFractionLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private class ModeState(
        var offset:     Double = 0.0,
        var episodes:   Int    = 0,
        var baseSig:    Double = Double.NaN,
        /** Why this mode last did (or didn't) move — a global "last outcome" can't answer
         *  "why has Lunch sat at n=5 without budging", which is the question that actually
         *  gets asked of a learner this slow. */
        var lastReason: String = "",
        /** Consecutive slow-return raises with no low since — reported once it gets long. */
        var slowReturnStreak: Int = 0
    )

    private val states = mutableMapOf<MealMode, ModeState>()

    // Currently-active entry episode
    private var activeMode:         MealMode? = null
    private var activeStartMs       = 0L
    private var bgAtEntry           = 0.0
    private var maxBgInEntryWindow  = 0.0
    private var maxBgOffsetMs       = 0L    // when that peak occurred, relative to entry
    private var earlyLow            = false
    private var earlyLowUnexplained = false // that low came with a drop insulin can't explain
    private var episodeBoosted      = false // the new pod boost ran at some point in this episode

    // Pending post-episode evaluation (settling tail)
    private var pendingMode:          MealMode? = null
    private var pendingEvalAtMs       = 0L
    private var pendingExcursion      = 0.0
    private var pendingPeakOffsetMs   = 0L
    private var pendingPeakMgdl       = 0.0
    private var pendingStartMs        = 0L
    private var pendingTailGrams      = 0.0
    /** Recent deltas (newest first) for the flat/still-falling test at the check. */
    private val tailDeltas            = ArrayDeque<Double>()
    /** Learned insulin peak (minutes) seen while the episode was running. */
    private var activePeakMins        = 0.0
    /** Earliest this episode may be judged — entry + learned peak + margin. */
    private var pendingVerdictFromMs  = 0L
    /** A P/F mode has taken over and is waiting on that time. */
    private var pendingHandover       = false

    /** Human-readable summary of the most recent learning decision — for the SI tab. */
    /** Called with each new verdict; the plugin points it at [LearningJournal]. */
    var onOutcome: ((String) -> Unit)? = null

    var lastOutcome = ""
        private set(value) {
            field = value
            if (value.isNotEmpty()) onOutcome?.invoke(value)
        }

    /** Set when a strengthen was warranted but the fraction is already railed at [FRACTION_MAX].
     *  Read once by the caller via [consumeMagnitudeHandoff]. */
    private var magnitudeHandoff: Pair<MealMode, Long>? = null

    init { restore() }

    companion object {
        /** Lows within this long after a UAM entry are attributed to the entry burst's shape,
         *  not to the mode's overall ISF. [ModeIsfLearner] reads this to arbitrate. */
        const val ENTRY_ATTRIBUTION_MS = 75 * 60_000L

        private const val TAIL_MS                    = 75 * 60_000L
        // Wide enough to reach either rail of [FRACTION_MIN]..[FRACTION_MAX] from any configured
        // value — the fraction, not the distance travelled, is what is actually bounded.
        private const val OFFSET_MIN                 = -0.9
        private const val OFFSET_MAX                 = 0.9
        private const val STRENGTHEN_STEP            = 0.03   // +3% fraction — entry arrived too late
        /**
         * Ceiling on a graduated strengthen (see the step calculation at evaluation).
         *
         * Pinned to [WEAKEN_STEP] so the safety direction is never slower per episode than the
         * direction that front-loads more insulin — the asymmetry that makes a flat +0.03 safe
         * is preserved as a bound rather than as a fixed step.
         */
        private const val MAX_STRENGTHEN_STEP        = 0.06
        private const val WEAKEN_STEP                = 0.06   // -6% — asymmetric, safety-biased
        /** Fraction of the weaken step applied when the low came with a BG drop insulin can't
         *  explain (see [UnexplainedDropTracker]) — reduced rather than skipped, since this is
         *  the safety direction and the classifier can be wrong. */
        private const val UNEXPLAINED_STEP_FRACTION  = 0.4
        private const val EXCURSION_STRENGTHEN_MGDL  = 45.0   // ~2.5 mmol rise after entry = too slow off the mark

        /**
         * A peak this far above TARGET is a shape problem on its own terms, even when the rise
         * measured from entry was modest.
         *
         * The excursion bar alone systematically under-reads the meal, and does so by exactly
         * the amount the detector needed to see: UAM cannot fire until BG has been rising for
         * ~15 min, so bgAtEntry is already partway up the spike and everything below it is
         * invisible to a relative measure. An entry at 7.0 topping out at 9.0 books only
         * 2.0 mmol of excursion — under the bar — while being precisely the episode the entry
         * burst exists to prevent. Anchoring to target instead doesn't care how late the
         * detector caught the rise.
         */
        // ~4 mmol above target. Was 3: an unannounced meal on Fiasp rises 3 mmol most of the time,
        // since the loop only starts once the rise is under way, so 3 called ordinary meals failures.
        const val PEAK_STRENGTHEN_MARGIN_MGDL = 72.0

        /**
         * ...but the entry burst must still have had something to blunt. A mode that fired with
         * BG already high and then barely moved peaked above target through no fault of its
         * front-loading — that is late detection, or a magnitude problem, and neither is fixed
         * by giving the first SMBs a bigger share.
         */
        private const val MIN_EXCURSION_FOR_PEAK_MGDL = 27.0  // ~1.5 mmol

        /** BG within this of the running max still counts as being AT the peak, for timing. */
        private const val PEAK_PLATEAU_TOLERANCE_MGDL = 3.6   // ~0.2 mmol

        /**
         * A post-entry spike only counts as "the entry burst was too small" if the peak arrived
         * late enough that a bigger entry SMB could plausibly have blunted it.
         *
         * High-GI food wins the race outright: UAM can't fire until BG has been rising for
         * ~15 min, so entry is already well into the meal, and a subcutaneous SMB given then
         * needs ~45-75 min to peak. A BG top-out 20 min after entry happened before the entry
         * insulin meaningfully acted — front-loading harder wouldn't have changed it, it would
         * just put more insulin in the system that lands AFTER the glucose has cleared. That
         * shows up as a LATE low, which the arbitration hands to ModeIsfLearner — so without
         * this gate the two learners actively fight: entry fraction railing up while mode ISF
         * gets weakened, converging on over-front-loaded and under-dosed.
         *
         * Set below typical insulin peak: by ~40 min an entry SMB is contributing enough that a
         * larger one would have measurably altered the curve.
         */
        private const val MIN_PEAK_OFFSET_FOR_STRENGTHEN_MS = 40 * 60_000L
        private const val ON_TARGET_MARGIN_MGDL      = 18.0   // ~1 mmol — above this at eval is a magnitude problem
        private const val TAIL_CONTAMINATION_G       = 8.0    // est. grams in the tail that void the evaluation
        private const val FRACTION_MIN               = 0.1
        private const val FRACTION_MAX               = 1.0

        /**
         * A mode still this far over target at the check counts as not having come back.
         * Same bar as [ON_TARGET_MARGIN_MGDL], read from the other side.
         */
        private const val SLOW_RETURN_MARGIN_MGDL    = ON_TARGET_MARGIN_MGDL
        /**
         * Falling at least this fast (0.1 mmol/5min = 1.2 mmol/hr) counts as still resolving on
         * its own, so the entry dose is not charged for where BG happens to be right now. Averaged
         * over [TREND_READINGS] readings so one flat reading in a descent doesn't trip it.
         */
        private const val STILL_FALLING_MGDL_PER_5MIN = 1.8
        private const val TREND_READINGS              = 3
        /** Per-episode raise for a stalled return, scaled by how far over target it stalled. */
        private const val SLOW_RETURN_STEP            = 0.03
        /**
         * A low after this trigger has been raising the fraction pulls back harder than the raise
         * that got there — the asymmetry that stops a one-directional signal from ratcheting.
         */
        private const val SLOW_RETURN_REVERSAL_MULT   = 1.5
        /** Consecutive slow-return raises with no low before the SI tab asks you to sanity-check. */
        private const val SLOW_RETURN_STREAK_NOTE     = 3
        /**
         * No verdict until this long past the learned insulin peak for the mode. A short mode
         * (a 45-min breakfast) hands over to P/F while its own entry SMBs are still climbing
         * toward peak activity; calling that "flat, so the dose fell short" reads the insulin's
         * own lag as a shortfall. The tail-deadline path is already clear of this — it sits 75min
         * after the mode ENDS — but the takeover path fires as soon as the trend window fills.
         */
        private const val VERDICT_PEAK_MARGIN_MS      = 15 * 60_000L
        /** Floor and ceiling for that wait, measured from entry, whatever the learned peak says. */
        private const val VERDICT_MIN_AFTER_ENTRY_MS  = 75 * 60_000L
        private const val VERDICT_MAX_AFTER_ENTRY_MS  = 105 * 60_000L

        private const val K_OFFSET = "offset"
        private const val K_N      = "n"
        private const val K_BASE   = "baseSig"
        private const val K_WHY    = "why"
        private const val K_STREAK = "slowReturns"

        /** True for modes that actually use the UAM entry-burst mechanism. */
        fun isEntryMode(mode: MealMode?): Boolean =
            mode != null && mode.isUam && mode != MealMode.UAM_PROTEIN_FAT
    }

    /** Learned additive offset for a mode (0.0 = no adjustment yet). */
    fun offset(mode: MealMode): Double = states[mode]?.offset ?: 0.0

    /** The configured fraction with this mode's learned offset applied, clamped to sane bounds. */
    fun adjustedFraction(mode: MealMode, configuredFraction: Double): Double =
        if (!isEntryMode(mode)) configuredFraction
        else (configuredFraction + offset(mode)).coerceIn(FRACTION_MIN, FRACTION_MAX)

    /** How many episodes have been evaluated for this mode. */
    fun episodeCount(mode: MealMode): Int = states[mode]?.episodes ?: 0

    /** Why this mode last moved, or didn't — for the SI tab. */
    fun lastReason(mode: MealMode): String = states[mode]?.lastReason ?: ""

    /**
     * An episode that wanted MORE front-loading than this learner can deliver, because the
     * configured fraction is already at [FRACTION_MAX] — mode and episode start, one shot.
     *
     * The caller forwards it to [ModeIsfLearner]. Handing it over here rather than acting on it
     * internally keeps the arbitration between the two learners in one visible place.
     */
    fun consumeMagnitudeHandoff(): Pair<MealMode, Long>? =
        magnitudeHandoff.also { magnitudeHandoff = null }

    /**
     * Call once per loop cycle. [baseSignature] is the user's configured fraction for the active
     * mode — a change resets that mode's learned offset (the offset was a correction relative to
     * the old setting; keeping it would double-apply the user's own adjustment).
     */
    fun onCycle(
        activeModeNow:  MealMode?,
        modeStartMs:    Long,
        bgMgdl:         Double,
        targetMgdl:     Double,
        lowActive:      Boolean,
        deltaMgdl:      Double,
        activityPerMin: Double,
        fastingIsfMgdl: Double,
        carbRatio:      Double,
        nowMs:          Long,
        baseSignature:  Double = 0.0,
        exerciseSuspected: Boolean = false,
        insulinPeakMins: Double = 0.0,  // learned activity peak for this mode
        secondWave: Boolean = false,    // more food went in during this episode — see
        // SecondWaveDetector. Nothing after that says anything about how the ENTRY burst was
        // shaped, so the episode is dropped rather than scored.
        newPodBoost: Boolean = false    // the new pod boost is running (or paused under target). An
        // episode it touches is not judged at all, early lows included: the boost changed the dose,
        // and a low already ends the boost for that pod. A verdict still waiting is dropped.
    ) {
        if (newPodBoost) pendingMode?.let { skipPending("new pod boost started") }
        if (isEntryMode(activeModeNow)) {
            val mode = activeModeNow!!
            if (activeMode == null || modeStartMs != activeStartMs) {
                pendingMode?.let { skipPending("superseded by new ${mode.label} activation") }

                val s = states.getOrPut(mode) { ModeState() }
                if (!s.baseSig.isNaN() && kotlin.math.abs(s.baseSig - baseSignature) > 0.001 &&
                    (s.offset != 0.0 || s.episodes > 0)) {
                    s.offset = 0.0
                    s.episodes = 0
                    s.lastReason = ""
                    lastOutcome = "${mode.label} entry fraction changed — learned offset reset"
                    aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
                }
                s.baseSig = baseSignature
                persist()

                activeMode         = mode
                activeStartMs      = modeStartMs
                bgAtEntry          = bgMgdl
                maxBgInEntryWindow = bgMgdl
                maxBgOffsetMs       = 0L
                earlyLow            = false
                earlyLowUnexplained = false
                activePeakMins      = 0.0
                episodeBoosted      = false
            }
            if (newPodBoost) episodeBoosted = true
            if (insulinPeakMins > 0.0) activePeakMins = insulinPeakMins
            // Only the entry window shapes this learner's evidence — later movement is the
            // ISF learner's territory.
            val elapsed = nowMs - activeStartMs
            if (elapsed <= ENTRY_ATTRIBUTION_MS) {
                if (bgMgdl > maxBgInEntryWindow) {
                    maxBgInEntryWindow = bgMgdl
                    maxBgOffsetMs      = elapsed
                } else if (bgMgdl >= maxBgInEntryWindow - PEAK_PLATEAU_TOLERANCE_MGDL) {
                    // Parked at the ceiling is not "the carbs beat the insulin" — it is the
                    // insulin never catching up, which is a front-loading failure. Recording
                    // only the first touch of the max would date a 40-minute plateau to its
                    // start and hand it to the fast-carb branch. Only a real descent leaves
                    // the peak time behind.
                    maxBgOffsetMs = elapsed
                }
                if (lowActive) {
                    earlyLow = true
                    if (exerciseSuspected) earlyLowUnexplained = true
                }
            }
            return
        }

        // Entry mode just ended (or switched to a non-entry mode)?
        if (activeMode != null) {
            val ended        = activeMode!!
            val hadEarlyLow  = earlyLow
            val excursion    = maxBgInEntryWindow - bgAtEntry
            val activeStartMsBeforeReset = activeStartMs
            activeMode    = null
            activeStartMs = 0L
            if (episodeBoosted || newPodBoost) {
                episodeBoosted = false
                note(ended, "${ended.label} not scored — new pod boost")
            } else if (hadEarlyLow) {
                // Definitive shape evidence — lands immediately, not skippable by later noise.
                val unexplained = earlyLowUnexplained
                // Undoing a slow-return run takes a bigger step than the raises that built it, so
                // a trigger that only ever pushes one way can't out-run its own correction.
                val reversing = (states[ended]?.slowReturnStreak ?: 0) > 0
                var step = if (unexplained) weakenStep() * UNEXPLAINED_STEP_FRACTION else weakenStep()
                if (reversing) step *= SLOW_RETURN_REVERSAL_MULT
                clearSlowReturnStreak(ended)
                applyOutcome(ended, -step,
                             if (unexplained) "low soon after ${ended.label} entry, but BG was falling faster than insulin explains (exercise?) — reduced at a smaller step"
                             else "low soon after ${ended.label} entry — entry fraction reduced" +
                                 (if (reversing) " at a bigger step, undoing the slow-return raises" else ""))
            } else if (secondWave) {
                note(ended, "${ended.label} not scored — more food during the episode")
            } else {
                pendingMode        = ended
                pendingEvalAtMs    = nowMs + TAIL_MS
                pendingExcursion   = excursion
                pendingPeakOffsetMs = maxBgOffsetMs
                pendingPeakMgdl     = maxBgInEntryWindow
                pendingStartMs      = activeStartMsBeforeReset
                pendingTailGrams   = 0.0
                tailDeltas.clear()
                pendingHandover    = false
                val peakLagMs = (activePeakMins * 60_000L).toLong() + VERDICT_PEAK_MARGIN_MS
                pendingVerdictFromMs = activeStartMsBeforeReset +
                    peakLagMs.coerceIn(VERDICT_MIN_AFTER_ENTRY_MS, VERDICT_MAX_AFTER_ENTRY_MS)
            }
        }

        val p = pendingMode ?: return
        if (lowActive) {
            // A low this late is a magnitude problem — ModeIsfLearner weakens for it. Entry
            // shape gets no evidence from it, so abandon the evaluation unchanged. The streak
            // still clears: a low is a low, and the run of raises no longer stands unchallenged.
            clearSlowReturnStreak(p)
            skipPending("late low in ${p.label} tail — magnitude signal, left to mode ISF")
            return
        }
        tailDeltas.addFirst(deltaMgdl)
        while (tailDeltas.size > TREND_READINGS) tailDeltas.removeLast()

        // A P/F takeover is the verdict arriving early: this mode handed a still-high BG to the
        // tail machinery, which is exactly the question the check below answers. Waiting out the
        // rest of the tail would judge P/F's work instead of this mode's. It still waits for the
        // entry insulin to have peaked — a 45-min mode hands over well before that.
        if (activeModeNow == MealMode.UAM_PROTEIN_FAT) pendingHandover = true
        if (pendingHandover && tailDeltas.size >= TREND_READINGS && nowMs >= pendingVerdictFromMs) {
            evaluate(p, bgMgdl, targetMgdl, handover = true)
            return
        }
        pendingTailGrams += tailGramsThisCycle(deltaMgdl, activityPerMin, fastingIsfMgdl, carbRatio)
        if (pendingTailGrams > TAIL_CONTAMINATION_G) {
            skipPending("fresh absorption (~${"%.0f".format(pendingTailGrams)}g) in ${p.label} tail")
            return
        }
        if (nowMs >= pendingEvalAtMs) evaluate(p, bgMgdl, targetMgdl, handover = false)
    }

    /**
     * Judges the finished episode. [handover] means a P/F mode has just taken over rather than the
     * tail deadline arriving — same question, answered at the moment the handover says it.
     */
    private fun evaluate(p: MealMode, bgMgdl: Double, targetMgdl: Double, handover: Boolean) {
        run {
            val whenTxt         = if (handover) "handed over to P/F at" else "ended"
            val endedOnTarget   = bgMgdl <= targetMgdl + onTargetMarginMgdl()
            val peakAboveTarget = pendingPeakMgdl - targetMgdl
            // Two ways to be too slow off the mark: a big rise measured from entry, or a peak
            // that got well above target at all. The second exists because the first is blind
            // to however much of the spike happened before the detector could fire.
            val bigExcursion    = pendingExcursion >= excursionBarMgdl() ||
                (peakAboveTarget >= peakBarMgdl() && pendingExcursion >= MIN_EXCURSION_FOR_PEAK_MGDL)
            val peakWasLate     = pendingPeakOffsetMs >= MIN_PEAK_OFFSET_FOR_STRENGTHEN_MS
            val peakMins        = pendingPeakOffsetMs / 60_000
            val excMmol         = BgText.bg(pendingExcursion)
            val peakMmol        = BgText.bg(pendingPeakMgdl)
            when {
                bigExcursion && endedOnTarget && peakWasLate -> {
                    // Graduated: a spike twice the size of the bar is twice the evidence, and a
                    // flat step means a mode that overshoots at every single meal still needs
                    // most of a week to move a tenth. Capped at WEAKEN_STEP, so however bad the
                    // episode, front-loading never ratchets up faster than an early low pulls it
                    // back down.
                    val overshoot = maxOf(pendingExcursion / excursionBarMgdl(),
                                          peakAboveTarget / peakBarMgdl())
                    val step = (strengthenStep() * overshoot).coerceIn(strengthenStep(), maxStrengthenStep())
                    if (railedHigh(p)) {
                        // Nowhere left to go: the first SMBs already carry the whole computed
                        // requirement. Accumulating a positive offset here would be dead state —
                        // adjustedFraction clamps it away — and would read on the tab as a
                        // learner that moved when nothing changed. The evidence is real, so it
                        // goes to the only actuator still holding travel.
                        magnitudeHandoff = p to pendingStartMs
                        note(p, "${p.label} reached ${peakMmol}, ended on target, but entry fraction is already at max — handed to mode ISF")
                    } else {
                        applyOutcome(p, step,
                                     "${p.label} reached ${peakMmol} (+${excMmol} after entry) peaking ${peakMins}min in, ended on target — entry fraction raised")
                    }
                }
                bigExcursion && endedOnTarget ->
                    // Fast-carb signature: the peak beat the entry insulin, so a bigger entry
                    // SMB could not have prevented it — only landed later and caused a low.
                    note(p, "${p.label} reached ${peakMmol} but peaked only ${peakMins}min after entry — fast carbs, not a shape problem")
                !endedOnTarget && stillFalling() ->
                    // Still coming down under its own steam. Where BG happens to be right now says
                    // nothing about the entry dose — and this is the branch every fat/protein meal
                    // lands in, which is what keeps the one below from ratcheting on the majority
                    // of real meals.
                    note(p, "${p.label} $whenTxt ${BgText.bg(bgMgdl)} but still falling ${BgText.bg(-meanTailDelta())}/5min — resolving on its own, entry shape not charged")
                !endedOnTarget && (pendingExcursion >= MIN_EXCURSION_FOR_PEAK_MGDL) -> {
                    // Flat, still over target, and it did rise after entry: nothing is holding BG
                    // up any more, so the front-loading fell short. Scaled by how far over it
                    // stalled, capped like every other raise.
                    val overTarget = (bgMgdl - targetMgdl) / SLOW_RETURN_MARGIN_MGDL
                    val step = (slowReturnStep() * overTarget).coerceIn(slowReturnStep(), maxStrengthenStep())
                    if (railedHigh(p)) {
                        magnitudeHandoff = p to pendingStartMs
                        clearSlowReturnStreak(p)
                        note(p, "${p.label} $whenTxt ${BgText.bg(bgMgdl)} and flat, but entry fraction is already at max — handed to mode ISF")
                    } else {
                        val streak = (states[p]?.slowReturnStreak ?: 0) + 1
                        applyOutcome(p, step,
                                     "${p.label} $whenTxt ${BgText.bg(bgMgdl)} and flat — nothing still bringing it down, entry fraction raised" +
                                         (if (streak >= SLOW_RETURN_STREAK_NOTE) " (${streak}th in a row with no low since — worth a sanity-check)" else ""))
                        states[p]?.let { it.slowReturnStreak = streak; persist() }
                    }
                }
                !endedOnTarget ->
                    note(p, "${p.label} $whenTxt ${BgText.bg(bgMgdl)} but barely rose after entry — not the entry burst's doing, left to mode ISF")
                else ->
                    note(p, "${p.label} peaked ${peakMmol}, ended on target — entry shape OK")
            }
            pendingMode = null
            pendingHandover = false
            tailDeltas.clear()
        }
    }

    /** Mean of the last few tail deltas (mg/dL per 5 min); 0.0 until there are enough. */
    private fun meanTailDelta(): Double =
        if (tailDeltas.size < TREND_READINGS) 0.0 else tailDeltas.average()

    /** BG is coming down fast enough that something is still resolving it. */
    private fun stillFalling(): Boolean = meanTailDelta() <= -STILL_FALLING_MGDL_PER_5MIN

    private fun clearSlowReturnStreak(mode: MealMode) {
        states[mode]?.takeIf { it.slowReturnStreak != 0 }?.let { it.slowReturnStreak = 0; persist() }
    }

    // -- User bias ------------------------------------------------------------
    private fun bias() = LearningBias.from(sp)
    private fun strengthenStep()   = scaleStrengthenAmount(STRENGTHEN_STEP, bias())
    private fun maxStrengthenStep()= scaleStrengthenAmount(MAX_STRENGTHEN_STEP, bias())
    private fun slowReturnStep()   = scaleStrengthenAmount(SLOW_RETURN_STEP, bias())
    private fun weakenStep()       = scaleSafetyAmount(WEAKEN_STEP, bias())
    private fun excursionBarMgdl() = EXCURSION_STRENGTHEN_MGDL * bias().barScale
    private fun peakBarMgdl()      = PEAK_STRENGTHEN_MARGIN_MGDL * bias().barScale
    private fun onTargetMarginMgdl() = ON_TARGET_MARGIN_MGDL * bias().barScale

    /** Insulin-pull-only residual — conservative fasting-rules "is something still absorbing". */
    private fun tailGramsThisCycle(deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double): Double {
        if (carbRatio <= 0.0 || isfMgdl <= 0.0) return 0.0
        val ci = deltaMgdl - (-activityPerMin * isfMgdl * 5.0)
        if (ci <= 0.0) return 0.0
        return ci / (isfMgdl / carbRatio)
    }

    private fun applyOutcome(mode: MealMode, delta: Double, reason: String) {
        val s = states.getOrPut(mode) { ModeState() }
        s.offset = (s.offset + delta).coerceIn(OFFSET_MIN, OFFSET_MAX)
        s.episodes++
        val sign = if (s.offset >= 0) "+" else ""
        s.lastReason = "$reason → $sign${"%.2f".format(s.offset)}"
        persist()
        lastOutcome = "${s.lastReason} (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
    }

    /**
     * True when this mode's configured fraction plus its learned offset already sits at the
     * ceiling, so a further strengthen would be swallowed whole by [adjustedFraction].
     *
     * Public because [ModeIsfLearner] needs it too: with no shape left to give, a spike followed
     * by a soft landing is a timing failure it should stop reading as over-dosing. Answering it
     * here keeps one definition of "the shape knob is out of travel".
     */
    fun isShapeRailed(mode: MealMode): Boolean = railedHigh(mode)

    private fun railedHigh(mode: MealMode): Boolean {
        val s = states[mode] ?: return false
        if (s.baseSig.isNaN()) return false
        return s.baseSig + s.offset >= FRACTION_MAX - 1e-9
    }

    /** A completed evaluation that produced no change — still an episode, and still worth
     *  saying why, since "no change" is the outcome that most needs explaining. */
    private fun note(mode: MealMode, reason: String) {
        val s = states.getOrPut(mode) { ModeState() }
        s.episodes++
        s.lastReason = reason
        persist()
        lastOutcome = "$reason (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
    }

    private fun skipPending(reason: String) {
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: evaluation skipped — $reason")
        lastOutcome = "skipped: $reason"
        pendingMode = null
    }

    fun reset() {
        states.clear()
        activeMode = null; activeStartMs = 0L; earlyLow = false; earlyLowUnexplained = false
        bgAtEntry = 0.0; maxBgInEntryWindow = 0.0; maxBgOffsetMs = 0L
        pendingMode = null; pendingPeakMgdl = 0.0; pendingStartMs = 0L; tailDeltas.clear()
        activePeakMins = 0.0; pendingVerdictFromMs = 0L; pendingHandover = false
        magnitudeHandoff = null
        lastOutcome = ""
        sp.edit { putString(StringNonKey.ApsSmartInsulinUamEntryFractionLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (mode, s) ->
                val obj = JSONObject().put(K_OFFSET, s.offset).put(K_N, s.episodes).put(K_WHY, s.lastReason)
                    .put(K_STREAK, s.slowReturnStreak)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                json.put(mode.name, obj)
            }
            sp.edit { putString(StringNonKey.ApsSmartInsulinUamEntryFractionLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "UamEntryFractionLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinUamEntryFractionLearnerState.key, StringNonKey.ApsSmartInsulinUamEntryFractionLearnerState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val mode = try { MealMode.valueOf(key) } catch (_: Exception) { return@forEach }
                val obj  = json.optJSONObject(key) ?: return@forEach
                states[mode] = ModeState(
                    offset   = obj.optDouble(K_OFFSET, 0.0).coerceIn(OFFSET_MIN, OFFSET_MAX),
                    episodes = obj.optInt(K_N, 0),
                    baseSig  = obj.optDouble(K_BASE, Double.NaN),
                    lastReason = obj.optString(K_WHY, ""),
                    slowReturnStreak = obj.optInt(K_STREAK, 0)
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "UamEntryFractionLearner: restore failed: ${e.message}")
        }
    }
}
