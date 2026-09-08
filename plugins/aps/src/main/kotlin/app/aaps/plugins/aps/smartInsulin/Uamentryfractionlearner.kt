package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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
 *   - Ended HIGH → that's a magnitude problem, not shape → fraction unchanged
 *       ([ModeIsfLearner] owns it).
 *   - Low LATE (in the settling tail) → magnitude problem → fraction unchanged.
 *
 * Only the UAM entry modes are learned — P/F is a tail correction, not a meal-entry event, and
 * the manually-activated modes don't use the entry-burst mechanism at all.
 *
 * The learned value is an ADDITIVE offset to the user's configured per-mode fraction, railed to
 * [OFFSET_MIN]..[OFFSET_MAX] so it can refine the setting but never wander far from it. The
 * entry SMB *count* stays manual by design: it's a coarse integer whose effects overlap heavily
 * with the fraction, and learning both would re-create the double-actuation problem this
 * arbitration exists to avoid.
 */
@Singleton
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
        var lastReason: String = ""
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

    // Pending post-episode evaluation (settling tail)
    private var pendingMode:          MealMode? = null
    private var pendingEvalAtMs       = 0L
    private var pendingExcursion      = 0.0
    private var pendingPeakOffsetMs   = 0L
    private var pendingPeakMgdl       = 0.0
    private var pendingStartMs        = 0L
    private var pendingTailGrams      = 0.0

    /** Human-readable summary of the most recent learning decision — for the SI tab. */
    var lastOutcome = ""
        private set

    /** Set when a strengthen was warranted but the fraction is already railed at [FRACTION_MAX].
     *  Read once by the caller via [consumeMagnitudeHandoff]. */
    private var magnitudeHandoff: Pair<MealMode, Long>? = null

    init { restore() }

    companion object {
        /** Lows within this long after a UAM entry are attributed to the entry burst's shape,
         *  not to the mode's overall ISF. [ModeIsfLearner] reads this to arbitrate. */
        const val ENTRY_ATTRIBUTION_MS = 75 * 60_000L

        private const val TAIL_MS                    = 75 * 60_000L
        private const val OFFSET_MIN                 = -0.25
        private const val OFFSET_MAX                 = 0.25
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
        private const val PEAK_STRENGTHEN_MARGIN_MGDL = 54.0  // ~3 mmol above target

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

        private const val K_OFFSET = "offset"
        private const val K_N      = "n"
        private const val K_BASE   = "baseSig"
        private const val K_WHY    = "why"

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
        exerciseSuspected: Boolean = false
    ) {
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
            }
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
            if (hadEarlyLow) {
                // Definitive shape evidence — lands immediately, not skippable by later noise.
                val unexplained = earlyLowUnexplained
                val step = if (unexplained) WEAKEN_STEP * UNEXPLAINED_STEP_FRACTION else WEAKEN_STEP
                applyOutcome(ended, -step,
                             if (unexplained) "low soon after ${ended.label} entry, but BG was falling faster than insulin explains (exercise?) — reduced at a smaller step"
                             else "low soon after ${ended.label} entry — entry fraction reduced")
            } else {
                pendingMode        = ended
                pendingEvalAtMs    = nowMs + TAIL_MS
                pendingExcursion   = excursion
                pendingPeakOffsetMs = maxBgOffsetMs
                pendingPeakMgdl     = maxBgInEntryWindow
                pendingStartMs      = activeStartMsBeforeReset
                pendingTailGrams   = 0.0
            }
        }

        val p = pendingMode ?: return
        if (lowActive) {
            // A low this late is a magnitude problem — ModeIsfLearner weakens for it. Entry
            // shape gets no evidence from it, so abandon the evaluation unchanged.
            skipPending("late low in ${p.label} tail — magnitude signal, left to mode ISF")
            return
        }
        pendingTailGrams += tailGramsThisCycle(deltaMgdl, activityPerMin, fastingIsfMgdl, carbRatio)
        if (pendingTailGrams > TAIL_CONTAMINATION_G) {
            skipPending("fresh absorption (~${"%.0f".format(pendingTailGrams)}g) in ${p.label} tail")
            return
        }
        if (nowMs >= pendingEvalAtMs) {
            val endedOnTarget   = bgMgdl <= targetMgdl + ON_TARGET_MARGIN_MGDL
            val peakAboveTarget = pendingPeakMgdl - targetMgdl
            // Two ways to be too slow off the mark: a big rise measured from entry, or a peak
            // that got well above target at all. The second exists because the first is blind
            // to however much of the spike happened before the detector could fire.
            val bigExcursion    = pendingExcursion >= EXCURSION_STRENGTHEN_MGDL ||
                (peakAboveTarget >= PEAK_STRENGTHEN_MARGIN_MGDL && pendingExcursion >= MIN_EXCURSION_FOR_PEAK_MGDL)
            val peakWasLate     = pendingPeakOffsetMs >= MIN_PEAK_OFFSET_FOR_STRENGTHEN_MS
            val peakMins        = pendingPeakOffsetMs / 60_000
            val excMmol         = "%.1f".format(pendingExcursion / 18.0)
            val peakMmol        = "%.1f".format(pendingPeakMgdl / 18.0)
            when {
                bigExcursion && endedOnTarget && peakWasLate -> {
                    // Graduated: a spike twice the size of the bar is twice the evidence, and a
                    // flat step means a mode that overshoots at every single meal still needs
                    // most of a week to move a tenth. Capped at WEAKEN_STEP, so however bad the
                    // episode, front-loading never ratchets up faster than an early low pulls it
                    // back down.
                    val overshoot = maxOf(pendingExcursion / EXCURSION_STRENGTHEN_MGDL,
                                          peakAboveTarget / PEAK_STRENGTHEN_MARGIN_MGDL)
                    val step = (STRENGTHEN_STEP * overshoot).coerceIn(STRENGTHEN_STEP, MAX_STRENGTHEN_STEP)
                    if (railedHigh(p)) {
                        // Nowhere left to go: the first SMBs already carry the whole computed
                        // requirement. Accumulating a positive offset here would be dead state —
                        // adjustedFraction clamps it away — and would read on the tab as a
                        // learner that moved when nothing changed. The evidence is real, so it
                        // goes to the only actuator still holding travel.
                        magnitudeHandoff = p to pendingStartMs
                        note(p, "${p.label} reached ${peakMmol}mmol, ended on target, but entry fraction is already at max — handed to mode ISF")
                    } else {
                        applyOutcome(p, step,
                                     "${p.label} reached ${peakMmol}mmol (+${excMmol} after entry) peaking ${peakMins}min in, ended on target — entry fraction raised")
                    }
                }
                bigExcursion && endedOnTarget ->
                    // Fast-carb signature: the peak beat the entry insulin, so a bigger entry
                    // SMB could not have prevented it — only landed later and caused a low.
                    note(p, "${p.label} reached ${peakMmol}mmol but peaked only ${peakMins}min after entry — fast carbs, not a shape problem")
                !endedOnTarget ->
                    // Named separately from "shape OK": an episode that ended high is not
                    // evidence the entry burst was right, it's evidence handed to ModeIsfLearner.
                    // Reporting it as OK is how a mode sits at n=5 looking like it was judged fine.
                    note(p, "${p.label} ended ${"%.1f".format(bgMgdl / 18.0)}mmol, above target — magnitude signal, left to mode ISF")
                else ->
                    note(p, "${p.label} peaked ${peakMmol}mmol, ended on target — entry shape OK")
            }
            pendingMode = null
        }
    }

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
        pendingMode = null; pendingPeakMgdl = 0.0; pendingStartMs = 0L
        magnitudeHandoff = null
        lastOutcome = ""
        sp.edit { putString(StringKey.ApsSmartInsulinUamEntryFractionLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (mode, s) ->
                val obj = JSONObject().put(K_OFFSET, s.offset).put(K_N, s.episodes).put(K_WHY, s.lastReason)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                json.put(mode.name, obj)
            }
            sp.edit { putString(StringKey.ApsSmartInsulinUamEntryFractionLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "UamEntryFractionLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringKey.ApsSmartInsulinUamEntryFractionLearnerState.key, StringKey.ApsSmartInsulinUamEntryFractionLearnerState.defaultValue)
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
                    lastReason = obj.optString(K_WHY, "")
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "UamEntryFractionLearner: restore failed: ${e.message}")
        }
    }
}
