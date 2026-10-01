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
 * Episode-outcome ISF learning per meal/UAM mode.
 *
 * Deliberately does NOT attribute anything mid-meal — per-cycle deviation attribution during
 * a meal is hopelessly confounded by carbs. Instead, each completed mode activation is judged
 * as a whole AFTER a settling tail, when absorption should be finished:
 *
 *   - Episode (or tail) contained a low/rebound  → mode ISF WEAKENS (bigger step — safety bias)
 *   - Episode (or tail) dipped close to the low guard → mode ISF WEAKENS at a reduced step.
 *     Scoring only frank hypos left a blind spot big enough to drive through: a mode that hands
 *     BG back just above the guard never tripped it, so it was recorded as a clean success. The
 *     caller decides how close counts, anchored to the same low guard.
 *   - Spike too high: peak ≥3mmol over target AND ≥1.5mmol above where the mode STARTED, held
 *     there 30min inside the first 75min, with no front-loading left to fix it (UAM entry
 *     fraction railed; manual modes have none) → STRENGTHENS. This is the phase mode ISF owns.
 *     The rise requirement matters as much as the height: a mode fired at 9mmol clears a
 *     "3mmol over target" bar standing still, and charging it for a spike it never had is how a
 *     mode gets strengthened for arriving after someone else's high.
 *   - BG still above target+margin at evaluation, DURA not engaged, and BG has FLATTENED
 *                                                 → STRENGTHENS (small step)
 *   - BG still above target+margin but STILL FALLING and below where the mode started → NO
 *     CHANGE. It is working through a high it inherited. This exemption is deliberately bounded
 *     by physics rather than by blame: the moment BG stops coming down while still over target,
 *     whichever mode is running takes the correction. A genuinely under-treated stretch cannot be
 *     passed along the chain forever, because it always flattens eventually.
 *   - BG still above target+margin at evaluation, DURA engaged → NO CHANGE. A stalled tail is
 *     [DuraStrengthLearner]'s to fix. Charging it here strengthened the spike response for a
 *     fat/protein stall, which then stacked with DURA and caused the lows. "Needed DURA" no
 *     longer strengthens here either, for the same reason.
 *   - A low WITH DURA cranked                    → mostly charged to DURA, not here. The mode's
 *     own dose took BG up and over safely; what took it too far was the tail correction, and
 *     [DuraStrengthLearner] cuts its ceiling for the same episode. Weakening the mode ISF a
 *     full step on top would fix the tail by under-dosing the meal.
 *   - Fresh absorption during the tail (ate again) or a new mode activation → episode SKIPPED,
 *     not learned from — contaminated evaluations must never move the multiplier
 *
 * The learned value is a per-mode multiplier applied to the mode's dosing ISF (static override
 * or profile ISF), hard-railed to [MULT_MIN, MULT_MAX]. One bounded update per episode keeps
 * convergence slow and safe: a consistently under-dosed Dinner takes ~a week of dinners to
 * drift meaningfully stronger, and any single odd evening moves it at most one step.
 */
@SingleIn(AppScope::class)
class ModeIsfLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private data class ModeState(var mult: Double = 1.0, var episodes: Int = 0, var baseSig: Double = Double.NaN)

    /**
     * Keyed by [PfWindow.stateKey], not by [MealMode], so Protein/Fat holds one correction per ISF
     * window instead of one averaged across all three. Every other mode keys to its plain enum
     * name, exactly as before.
     */
    private val states = mutableMapOf<String, ModeState>()

    /** A mode together with the state slot and label it is being learned under this episode. */
    private data class Scope(val mode: MealMode, val key: String, val label: String)

    private fun scopeOf(mode: MealMode, window: PfWindow) =
        Scope(mode, PfWindow.stateKey(mode, window), PfWindow.label(mode, window))

    // Currently-active episode tracking
    private var activeScope:     Scope? = null
    private var activeStartMs    = 0L
    private var episodeLow       = false
    private var episodeEarlyLow  = false  // low inside the UAM entry window — belongs to
    // UamEntryFractionLearner (shape), not to this learner (magnitude)
    private var episodeUndershoot = false  // BG dropped close to the low guard without reaching
    // it — the mode overshot its job, just not far enough to be scored as a hypo
    private var episodeLowWasUnexplained = false  // the low came with a drop insulin can't
    // explain (exercise etc.) — weaken at a reduced step
    private var episodeMaxDura   = 1.0
    /** Highest BG seen during the episode — the excursion this learner used to be blind to. */
    private var episodePeakMgdl  = 0.0
    /** BG when the mode opened, so a spike is measured as a RISE rather than as an altitude. */
    private var episodeStartBgMgdl = 0.0
    /** BG was already low (or close to it) when the mode opened. That low is not the mode's doing,
     *  so it is not scored until BG has come out of it, or gone clearly lower than it started. */
    private var episodeCarriedInLow = false
    /** Recent deltas (newest first) at the evaluation, for the still-falling test. */
    private val tailDeltas       = ArrayDeque<Double>()
    /** Longest unbroken stretch spent at or above the spike bar, and the run currently open. */
    private var episodeSpikeMs   = 0L
    private var spikeRunStartMs  = 0L
    /** True if the entry-fraction knob was already at its ceiling at any point in the episode. */
    private var episodeShapeRailed = false

    // Pending post-episode evaluation (settling tail)
    private var pendingScope:     Scope? = null
    private var pendingEvalAtMs  = 0L
    private var pendingMaxDura   = 1.0
    /** The ended episode's spike was too high for its dose — captured at episode end, before a
     *  new activation can reset the spike tracking it was read from. */
    private var pendingSpikeTooHigh = false
    private var pendingStartBgMgdl  = 0.0
    /** The step the window verdict applied (1.0 for none). Not 1.0 means the tail must not
     *  strengthen the same episode again. */
    private var pendingWindowStep = 1.0
    private var pendingTailGrams = 0.0
    private var pendingStartMs   = 0L

    // Post-episode low/undershoot watch. Deliberately separate from the pending evaluation:
    // the evaluation asks "did this land well?" and is legitimately voided by contamination or
    // by a new activation, whereas this asks "did this mode drive BG too far down?" — a safety
    // signal that must survive both. It matters more since at-target auto-cancel landed: a mode
    // that cancels the moment BG reaches target ends with MORE insulin on board than one that
    // ran to expiry, so the low it causes now routinely arrives after the mode is already gone.
    private var watchScope:    Scope? = null
    private var watchUntilMs  = 0L
    private var watchStartMs  = 0L
    private var watchReduced  = false  // evaluation was voided — still weaken, but at a smaller step
    private var watchCounted  = false  // the window verdict already counted this episode once
    private var watchWindowStep = 1.0  // what the window verdict applied; a charged low undoes it first
    private var watchMaxDura  = 1.0    // how hard DURA pushed in the episode being watched

    /** The episode this learner last actually MOVED the multiplier for. A shape handoff for an
     *  episode already corrected here must not correct it a second time. */
    private var lastMovedMode:    MealMode? = null
    private var lastMovedStartMs = 0L

    /** Human-readable summary of the most recent learning decision — for the SI tab. */
    /** Called with each new verdict; the plugin points it at [LearningJournal]. */
    var onOutcome: ((String) -> Unit)? = null

    var lastOutcome = ""
        private set(value) {
            field = value
            if (value.isNotEmpty()) onOutcome?.invoke(value)
        }

    init { restore() }

    companion object {
        private const val TAIL_MS                 = 75 * 60_000L  // settling tail before judging the episode
        /** How long after a mode ends its insulin can still be blamed for a low. Outlives
         *  [TAIL_MS] because the evaluation only needs absorption to be finished, whereas the
         *  low watch needs the mode's *insulin* to be finished — which takes longer. */
        private const val WATCH_MS                = TAIL_MS + 30 * 60_000L
        private const val STRENGTHEN_STEP         = 0.975         // -2.5% per strengthen (lower ISF = more insulin)
        private const val WEAKEN_STEP             = 1.05          // +5% per weaken — asymmetric, safety-biased
        /**
         * Fraction of the normal weaken step applied when the low came alongside a BG drop
         * insulin can't account for (see [UnexplainedDropTracker]) — typically exercise.
         *
         * Deliberately reduced rather than skipped: the classifier can be wrong, and this is the
         * safety direction, so some correction must still land. Mirrors the confound-handling
         * idiom already used by BolusCurveTracker (learn slower on a suspected confound rather
         * than discarding the observation).
         */
        private const val UNEXPLAINED_STEP_FRACTION = 0.4
        /**
         * Step fraction for an undershoot — BG close to the low guard but never below it.
         * Real evidence the mode dosed too hard, but a milder outcome than a hypo, so it earns a
         * milder correction. Without this the learner is blind to exactly the episode that ends
         * a whisker above the guard: not a low, yet plainly too much insulin.
         */
        private const val UNDERSHOOT_STEP_FRACTION = 0.5
        /**
         * An undershoot only counts once the mode has had time to act. Guards the case of a mode
         * started at a BG that is already inside the undershoot band (a pre-bolus taken at 4.7),
         * where the first cycles say nothing about whether the dose was too big.
         */
        private const val UNDERSHOOT_MIN_ELAPSED_MS = 25 * 60_000L
        /** How much lower than the starting BG a carried-in low must go before it counts (~0.5 mmol). */
        private const val CARRIED_IN_LOW_DEEPER_MGDL = 9.0
        /** Step fraction once the evaluation has been voided — the low is still real, but the
         *  episode is no longer clean enough to credit it in full. */
        private const val VOIDED_STEP_FRACTION      = 0.5
        /**
         * Peak this far above target makes the episode a LATE one — the same ~3 mmol bar
         * [UamEntryFractionLearner] uses to call a spike a front-loading failure, so the two
         * learners agree on what counts as one.
         */
        private const val LATE_SPIKE_MARGIN_MGDL    = 54.0
        /**
         * How long BG must stay at or above that bar before the episode counts as under-dosed
         * rather than simply spiky.
         *
         * Six readings. Insulin that is late but sufficient has visibly turned the curve inside
         * that window; insulin that is short leaves BG flat up there, which is the state this is
         * actually trying to name.
         */
        private const val SPIKE_SUSTAINED_MS        = 30 * 60_000L
        /** The spike phase: how long after activation a held peak still belongs to the mode's
         *  own dose rather than to a stalled tail. */
        private const val SPIKE_PHASE_MS            = 75 * 60_000L
        /** A peak must be at least this far above where the mode STARTED to count as its spike. */
        private const val MIN_RISE_FOR_SPIKE_MGDL   = 27.0   // ~1.5 mmol, same bar the entry learner uses
        /** Falling at least this fast still counts as working through it, not stalled. */
        private const val STILL_FALLING_MGDL_PER_5MIN = 1.8
        private const val TREND_READINGS            = 3
        /** DURA at or above this during the episode means DURA was working the tail. Same bar
         *  [DuraStrengthLearner] uses to call DURA engaged. */
        private const val DURA_ENGAGED_MULT         = 1.10
        /** Weaken-step fraction for a hard low that followed a late spike — half the fault was
         *  timing, so only half the step is charged to dose. */
        private const val LATE_SPIKE_STEP_FRACTION  = 0.5
        private const val MULT_MIN                = 0.6
        private const val MULT_MAX                = 1.4
        private const val STRENGTHEN_MARGIN_MGDL  = 18.0          // ~1 mmol above target at eval = under-dosed
        private const val TAIL_CONTAMINATION_G    = 8.0           // est. grams during tail that voids the evaluation
        private const val DURA_INTERVENTION_MULT  = 1.15          // DURA peak ≥ this: a low is mostly DURA's doing
        /**
         * Weaken-step fraction for a low that landed with DURA at or past
         * [DURA_INTERVENTION_MULT] — the tail, not the meal dose, drove BG down.
         *
         * Reduced rather than skipped for the usual reason: the episode's total insulin really was
         * too much, and if the mode's base dose is the actual culprit the low will keep recurring.
         * That case resolves itself — every such low cuts DURA's strength by 15%, so DURA engages
         * less each time, and once it no longer reaches the bar these lows land here at the full
         * step. Blame follows whichever knob is still doing the pushing.
         */
        private const val DURA_ATTRIBUTION_STEP_FRACTION = 0.35

        private const val K_MULT = "mult"
        private const val K_N    = "n"
        private const val K_BASE = "baseSig"
    }

    /** Multiplier to apply to the mode's dosing ISF. <1.0 = stronger (lower ISF). */
    fun multiplier(mode: MealMode, window: PfWindow = PfWindow.NONE): Double =
        states[PfWindow.stateKey(mode, window)]?.mult ?: 1.0

    /** How many episodes have been evaluated for this mode. */
    fun episodeCount(mode: MealMode, window: PfWindow = PfWindow.NONE): Int =
        states[PfWindow.stateKey(mode, window)]?.episodes ?: 0

    /**
     * Call once per loop cycle. [duraMult] is this cycle's DURA multiplier (1.0 when inactive).
     * [deltaMgdl]/[activityPerMin]/[fastingIsfMgdl]/[carbRatio] feed the tail contamination
     * check (insulin-pull-only carb-equivalent residual — no target-seek term, fasting rules).
     */
    fun onCycle(
        activeModeNow:  MealMode?,
        modeStartMs:    Long,
        bgMgdl:         Double,
        targetMgdl:     Double,
        lowActive:      Boolean,
        duraMult:       Double,
        deltaMgdl:      Double,
        activityPerMin: Double,
        fastingIsfMgdl: Double,
        carbRatio:      Double,
        nowMs:          Long,
        baseSignature:  Double = 0.0,  // fingerprint of the mode's user-set ISF override(s);
        // a change means the user re-based the mode, so the old learned correction is stale
        exerciseSuspected: Boolean = false,  // BG dropping faster than insulin explains — a low
        // right now is probably not an ISF problem
        undershootActive: Boolean = false,  // BG within the caller's undershoot band above the low
        // guard — too far down to call the episode a success, not far enough to call it a hypo
        entryShapeRailed: Boolean = false,  // this mode's UAM entry fraction is already at its
        // ceiling, so no amount of further front-loading is available to fix a late spike
        modeInsulinShare: Double = 1.0,  // how much of the insulin behind a post-mode low was this
        // mode's, rather than the loop's own corrections since it ended (see ModeInsulinShare)
        watchMs: Long = WATCH_MS,        // how long the low watch stays open after a mode ends
        learningEnabled: Boolean = true,  // user switch. Off FREEZES this learner: episodes stop
        // being scored and anything in flight is dropped, but the multipliers already learned keep
        // being dosed from. Clearing them is a separate, deliberate act (the reset button).
        secondWave: Boolean = false,  // a second lot of food was eaten inside this episode's own
        // window — see SecondWaveDetector. The episode can no longer say anything about the dose
        // it was given, so it is not scored; lows still land, they are a safety signal either way.
        pfWindow: PfWindow = PfWindow.NONE  // which P/F ISF window this episode doses from; NONE
        // for every other mode. Resolved once when the episode opens and held for its whole life,
        // so an episode running across a window boundary is still judged as one thing.
    ) {
        if (!learningEnabled) {
            // Drop anything in flight rather than leaving it to be judged whenever the switch comes
            // back on — that verdict would be about an episode from another era.
            if (activeScope != null || pendingScope != null || watchScope != null) {
                activeScope = null; activeStartMs = 0L
                pendingScope = null
                clearWatch()
                lastOutcome = "mode ISF learning is switched off — episode dropped"
                aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
            }
            return
        }
        if (activeModeNow != null) {
            if (activeScope == null || modeStartMs != activeStartMs) {
                // A new activation while an evaluation is still pending contaminates it —
                // the tail can no longer be judged cleanly.
                // The window was judged when it ended; only its tail is cut short here, and the new
                // mode learns from its own episode.
                pendingScope?.let { tailNotJudged(it, "${activeModeNow.label} took over") }

                // The watch hands over to the new episode, which tracks its own lows from here.
                // Keeping the old one open would weaken twice for a single low.
                clearWatch()

                // A mode replaced mid-episode (a UAM window taking over from P/F, or a meal
                // activated while a UAM runs) can never be judged on where BG lands — that tail
                // belongs to whatever took over. But a low or an undershoot it had ALREADY
                // caused is evidence about its own dose, and would otherwise be discarded along
                // with the episode. episodeEarlyLow is deliberately not carried forward: the
                // entry-fraction learner owns entry-window lows.
                activeScope?.let { superseded ->
                    when {
                        episodeLow        ->
                            applyOutcome(superseded, weakenStep(episodeLowWasUnexplained, duraDrove = duraDrove(episodeMaxDura)),
                                         "low during ${superseded.label} before ${activeModeNow.label} took over${duraNote(episodeMaxDura)} — weakened",
                                         activeStartMs)
                        episodeUndershoot ->
                            // Same reading as the episode-end branch — a spike that settled just
                            // under target with no front-loading left is late, not excessive.
                            if (lateSpikeWithNoShapeLeft(targetMgdl) && !episodeLowWasUnexplained)
                                applyOutcome(superseded, strengthenStep(),
                                             "${superseded.label} peaked ${"%.1f".format(episodePeakMgdl / 18.0)}mmol then settled just under target with entry front-loading maxed — strengthened",
                                             activeStartMs)
                            else
                                applyOutcome(superseded, weakenStep(episodeLowWasUnexplained, undershoot = true, duraDrove = duraDrove(episodeMaxDura)),
                                             "${superseded.label} undershot before ${activeModeNow.label} took over${duraNote(episodeMaxDura)} — weakened at reduced step",
                                             activeStartMs)
                    }
                }

                // Base-change reset: the learned multiplier is a correction RELATIVE to the
                // base ISF the user had set when it was learned. If the user changes the
                // override (often in the same direction the learner was already pushing),
                // keeping the multiplier would silently double-apply that correction — so a
                // changed base resets this mode to a clean slate and re-learns from there.
                val opening = scopeOf(activeModeNow, pfWindow)
                val s = states.getOrPut(opening.key) { ModeState() }
                if (!s.baseSig.isNaN() && kotlin.math.abs(s.baseSig - baseSignature) > 0.01 && (s.mult != 1.0 || s.episodes > 0)) {
                    s.mult = 1.0
                    s.episodes = 0
                    lastOutcome = "${opening.label} ISF override changed — learned multiplier reset"
                    aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
                }
                s.baseSig = baseSignature
                persist()

                activeScope     = opening
                activeStartMs   = modeStartMs
                episodeLow      = false
                episodeEarlyLow = false
                episodeUndershoot = false
                episodeLowWasUnexplained = false
                episodeMaxDura  = 1.0
                episodePeakMgdl = 0.0
                episodeSpikeMs  = 0L
                spikeRunStartMs = 0L
                episodeStartBgMgdl = bgMgdl
                episodeCarriedInLow = lowActive || undershootActive
                tailDeltas.clear()
                episodeShapeRailed = false
            }
            if (bgMgdl > episodePeakMgdl) episodePeakMgdl = bgMgdl
            if (entryShapeRailed) episodeShapeRailed = true
            tailDeltas.addFirst(deltaMgdl)
            while (tailDeltas.size > TREND_READINGS) tailDeltas.removeLast()
            // Time STUCK above the bar, not merely time touching it. A peak is a moment; what
            // separates a meal the loop is handling from one it isn't is how long BG sits up
            // there. Longest unbroken run, so a spike that crosses the bar twice on its way
            // through doesn't add up to a stall it never had.
            // Only inside the spike phase. Past it, BG parked high is a fat/protein stall — DURA's
            // problem — and counting it here would charge the spike knob for it.
            if (bgMgdl - targetMgdl >= spikeMarginMgdl() && nowMs - activeStartMs <= SPIKE_PHASE_MS) {
                if (spikeRunStartMs == 0L) spikeRunStartMs = nowMs
                episodeSpikeMs = maxOf(episodeSpikeMs, nowMs - spikeRunStartMs)
            } else spikeRunStartMs = 0L
            // A low the user started the mode in (eating to stop a low, or to stop a spike while
            // already low) says nothing about the mode's dose. It stops being "carried in" once BG
            // has come out of it - a later low is then a new one - or once BG has gone clearly
            // lower than where the mode started, which the mode's insulin may well have caused.
            if (episodeCarriedInLow) {
                if (!lowActive && !undershootActive) episodeCarriedInLow = false
                else if (bgMgdl < episodeStartBgMgdl - CARRIED_IN_LOW_DEEPER_MGDL) {
                    episodeCarriedInLow = false
                    aapsLogger.debug(LTag.APS, "ModeIsfLearner: ${activeScope?.label} started low and BG fell further (" +
                        "${"%.1f".format(episodeStartBgMgdl / 18.0)}→${"%.1f".format(bgMgdl / 18.0)} mmol) — this low counts")
                }
            }
            if (episodeCarriedInLow) {
                // Not scored: the low was there before the mode.
            } else if (lowActive) {
                // ARBITRATION with UamEntryFractionLearner: a low soon after a UAM entry is
                // evidence the entry burst was too front-loaded (a SHAPE problem the entry
                // learner owns) — not that the mode's overall ISF is too strong. Attributing
                // it to both would double-correct a single mistake. Record it separately and
                // let the entry learner act; only later lows move this learner's multiplier.
                val early = UamEntryFractionLearner.isEntryMode(activeModeNow) &&
                    (nowMs - activeStartMs) <= UamEntryFractionLearner.ENTRY_ATTRIBUTION_MS
                if (early) episodeEarlyLow = true else episodeLow = true
                if (exerciseSuspected) episodeLowWasUnexplained = true
            } else if (undershootActive && (nowMs - activeStartMs) >= UNDERSHOOT_MIN_ELAPSED_MS) {
                episodeUndershoot = true
                if (exerciseSuspected) episodeLowWasUnexplained = true
            }
            if (duraMult > episodeMaxDura) episodeMaxDura = duraMult
            return
        }

        // No mode active — did one just end?
        if (activeScope != null) {
            val ended        = activeScope!!
            val hadLow       = episodeLow
            val hadEarlyLow  = episodeEarlyLow
            val hadUndershoot = episodeUndershoot
            val maxDura      = episodeMaxDura
            val unexplained  = episodeLowWasUnexplained
            val endedStartMs = activeStartMs
            activeScope   = null
            activeStartMs = 0L
            val lateSpike    = lateSpikeWithNoShapeLeft(targetMgdl)
            val peakMmol     = "%.1f".format(episodePeakMgdl / 18.0)
            if (hadLow) {
                // A low during the episode is a definitive outcome — no tail wait needed, and
                // deliberately NOT skippable by later contamination: weaken signals must land.
                //
                // A preceding spike does NOT flip this one. BG reaching the low guard means the
                // total really was too much, whenever it arrived, and strengthening from there
                // would deepen the next one. It only softens the step: half the episode's fault
                // was timing, and the full step would price it all as dose.
                applyOutcome(ended, weakenStep(unexplained, lateSpike = lateSpike, duraDrove = duraDrove(maxDura)),
                             when {
                                 unexplained -> "low during ${ended.label} episode, but BG was falling faster than insulin explains (exercise?) — weakened at reduced step"
                                 lateSpike   -> "low during ${ended.label} episode, but it sat ${episodeSpikeMs / 60_000}min at ${peakMmol}mmol first with entry front-loading maxed${duraNote(maxDura)} — weakened at reduced step"
                                 duraDrove(maxDura) -> "low during ${ended.label} episode, peak ${peakMmol}mmol, DURA ×${"%.2f".format(maxDura)} drove the descent — DURA takes the correction, ${ended.label} ISF weakened at a small step"
                                 else        -> "low during ${ended.label} episode — weakened"
                             },
                             endedStartMs)
                clearWatch()
            } else if (hadEarlyLow) {
                // Only an early (entry-window) low: the entry-fraction learner is acting on it.
                // Judging it here too would correct one mistake twice.
                skipPending("early low after ${ended.label} entry — attributed to UAM entry fraction")
                clearWatch()
            } else if (hadUndershoot) {
                // Never reached the low guard, so no other learner sees this at all — but the
                // mode drove BG down near the low guard, which is over-dosing by any reading of it.
                //
                // Unless it spiked hard on the way there with the shape knob already railed. An
                // episode that peaked 3mmol over target and then settled just UNDER it never went
                // low at all; calling that over-dosing and weakening is what walks a mode steadily
                // weaker meal after meal while the spikes get worse. Nothing here is unsafe to
                // strengthen: BG stayed above the guard the whole way, and any episode that does
                // reach it still weakens, at twice this step, in the branch above.
                if (lateSpike && !unexplained) {
                    applyOutcome(ended, strengthenStep(),
                                 "${ended.label} sat ${episodeSpikeMs / 60_000}min at ${peakMmol}mmol then settled just under target with entry front-loading maxed — late, not too much — strengthened",
                                 endedStartMs)
                } else {
                    applyOutcome(ended, weakenStep(unexplained, undershoot = true, duraDrove = duraDrove(maxDura)),
                                 "${ended.label} undershot toward the low guard${duraNote(maxDura)} — weakened at reduced step",
                                 endedStartMs)
                }
                clearWatch()
            } else if (secondWave) {
                // No pending evaluation at all: whatever BG did from here reflects food this mode
                // was never dosed for. The low watch below still opens — if the stack of both
                // meals drives BG down, that is real and this learner must see it.
                lastOutcome = "${ended.label} not scored — more food during the episode"
                aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
                openWatch(ended, nowMs, endedStartMs, maxDura, watchMs)
            } else {
                pendingScope     = ended
                pendingEvalAtMs  = nowMs + TAIL_MS
                pendingMaxDura   = maxDura
                pendingSpikeTooHigh = spikeTooHigh(ended.mode, targetMgdl)
                pendingStartBgMgdl  = episodeStartBgMgdl
                pendingTailGrams = 0.0
                pendingStartMs   = endedStartMs
                // Judge the window now: the spike, and where BG was when the mode ended. The tail
                // after this only has to catch a crash (the low watch) or a BG that is still high
                // at its end. A mode that takes over during the tail can then cut it short without
                // losing anything this mode already showed.
                pendingWindowStep = judgeWindow(ended, bgMgdl, targetMgdl)
                openWatch(ended, nowMs, endedStartMs, maxDura, watchMs, counted = true, windowStep = pendingWindowStep)
            }
        }

        // Low watch, checked before (and independently of) the pending evaluation. A mode that
        // auto-cancels at target hands back a BG that looks fine and an IOB tail that isn't, so
        // this has to keep looking after the evaluation itself has been voided or superseded.
        watchScope?.let { w ->
            if (lowActive || undershootActive) {
                val soft = undershootActive && !lowActive
                // Charged by whose insulin it was, not by how long ago the mode ended. Below the
                // bar the loop's own corrections own this low, and the circadian hard-low penalty
                // is already charging the hour for it.
                if (!ModeInsulinShare.chargeable(modeInsulinShare)) {
                    noChange(w, "low after ${w.label} ended, but only ${ModeInsulinShare.describe(modeInsulinShare, w.label)} — the loop's own insulin since, not this mode's", countEpisode = !watchCounted)
                    clearWatch()
                    pendingScope = null
                    return
                }
                // A mode strengthened for ending high and then crashed was not under-dosed after
                // all: undo that strengthen before weakening, so the episode always nets weaker.
                val undo = 1.0 / watchWindowStep
                applyOutcome(w, undo * weakenStep(exerciseSuspected, undershoot = soft, voided = watchReduced, duraDrove = duraDrove(watchMaxDura), share = modeInsulinShare),
                             (if (soft) "BG near the low guard after ${w.label} ended" else "low after ${w.label} ended") +
                                 (if (exerciseSuspected) ", but BG was falling faster than insulin explains (exercise?)" else "") +
                                 (if (watchReduced) ", episode no longer clean" else "") +
                                 duraNote(watchMaxDura) +
                                 ", ${ModeInsulinShare.describe(modeInsulinShare, w.label)}" +
                                 " — weakened" + (if (soft || exerciseSuspected || watchReduced || duraDrove(watchMaxDura) || modeInsulinShare < 1.0) " at reduced step" else "") +
                                 (if (watchWindowStep != 1.0) ", and the strengthen at mode end undone" else ""),
                             watchStartMs, countEpisode = !watchCounted)
                clearWatch()
                pendingScope = null
                return
            }
            if (nowMs >= watchUntilMs) clearWatch()
        }

        val p = pendingScope ?: return
        tailDeltas.addFirst(deltaMgdl)
        while (tailDeltas.size > TREND_READINGS) tailDeltas.removeLast()
        pendingTailGrams += tailGramsThisCycle(deltaMgdl, activityPerMin, fastingIsfMgdl, carbRatio)
        if (pendingTailGrams > TAIL_CONTAMINATION_G) {
            // Voids the *evaluation* only. The low watch stays open at a reduced step: BG that
            // refuses to fall as fast as the insulin on board predicts reads as fresh absorption,
            // which is exactly what a fat tail looks like — so this fires routinely on the very
            // episodes most likely to end low, and dropping the watch here would lose them.
            tailNotJudged(p, "fresh absorption (~${"%.0f".format(pendingTailGrams)}g)")
            watchReduced = true
            return
        }
        if (nowMs >= pendingEvalAtMs) {
            judgeTailEnd(p, bgMgdl, targetMgdl)
            pendingScope = null
            tailDeltas.clear()
        }
    }

    /**
     * The verdict on the mode's own window, at the moment it ends: one step at most. Returns the
     * step applied (1.0 for none), so the tail does not strengthen the same episode again and a
     * low in the tail can undo it.
     */
    private fun judgeWindow(p: Scope, bgMgdl: Double, targetMgdl: Double): Double {
        val aboveMmol = "%.1f".format((bgMgdl - targetMgdl) / 18.0)
        val endedHigh = bgMgdl > targetMgdl + strengthenMarginMgdl()
        when {
            pendingSpikeTooHigh -> {
                val step = strengthenStep()
                applyOutcome(p, step, "${p.label} spike held ≥3mmol over target for 30min with no front-loading left — strengthened", pendingStartMs)
                return step
            }
            // Working through a high it inherited: below where it started and still coming
            // down. Charging this reads someone else's high as this mode's under-dosing.
            endedHigh && stillFalling() && bgMgdl < pendingStartBgMgdl - MIN_RISE_FOR_SPIKE_MGDL ->
                noChange(p, "${p.label} ended ${aboveMmol}mmol above target but ${"%.1f".format((pendingStartBgMgdl - bgMgdl) / 18.0)}mmol below where it started, still falling — working through an inherited high, no change")
            endedHigh && pendingMaxDura < DURA_ENGAGED_MULT -> {
                val step = strengthenStep()
                applyOutcome(p, step, "${p.label} ended ${aboveMmol}mmol above target — strengthened", pendingStartMs)
                return step
            }
            endedHigh -> noChange(p, "${p.label} ended ${aboveMmol}mmol above target with DURA working (×${"%.2f".format(pendingMaxDura)}) — a stall is DURA's to fix, no change")
            else      -> noChange(p, "${p.label} ended on target — no change")
        }
        return 1.0
    }

    /**
     * End of the settling tail with no low in it. Only one thing is left to learn: BG still high
     * after the mode's insulin has done its work means the dose was too small - unless the window
     * already said so. On target, or already strengthened, there is nothing new to record.
     */
    private fun judgeTailEnd(p: Scope, bgMgdl: Double, targetMgdl: Double) {
        val endedHigh = bgMgdl > targetMgdl + strengthenMarginMgdl()
        val inheritedHigh = stillFalling() && bgMgdl < pendingStartBgMgdl - MIN_RISE_FOR_SPIKE_MGDL
        if (endedHigh && pendingWindowStep == 1.0 && !inheritedHigh && pendingMaxDura < DURA_ENGAGED_MULT) {
            val step = strengthenStep()
            applyOutcome(p, step,
                         "${p.label}: BG still ${"%.1f".format((bgMgdl - targetMgdl) / 18.0)}mmol above target ${TAIL_MS / 60_000}min after it ended — strengthened",
                         pendingStartMs, countEpisode = false)
            // The low watch runs a little past the tail; a low in it undoes this too.
            if (watchScope == p) watchWindowStep = step
        } else aapsLogger.debug(LTag.APS, "ModeIsfLearner: ${p.label} tail ended at ${"%.1f".format(bgMgdl / 18.0)}mmol — window verdict stands")
    }

    /** The tail stops early. The window verdict stands; the low watch is handled by the caller. */
    private fun tailNotJudged(p: Scope, reason: String) {
        lastOutcome = "${p.label} tail not judged — $reason (window already judged)"
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
        pendingScope = null
    }

    /** Insulin-pull-only residual (no target-seek term) — conservative fasting-rules check
     *  for "is something still absorbing during the tail". */
    private fun tailGramsThisCycle(deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double): Double {
        if (carbRatio <= 0.0 || isfMgdl <= 0.0) return 0.0
        val ci = deltaMgdl - (-activityPerMin * isfMgdl * 5.0)
        if (ci <= 0.0) return 0.0
        return ci / (isfMgdl / carbRatio)
    }

    /**
     * Weaken step, scaled down for each reason to be less than fully confident in the signal:
     * the drop looks externally caused, the outcome was an undershoot rather than a hypo, or the
     * episode was no longer clean when the low landed. Reasons compound — always reduce, never
     * discard, since this is the safety direction.
     */
    /**
     * True when the episode says the insulin arrived LATE rather than in the wrong amount, and
     * there is no front-loading left to fix that with.
     *
     * The distinction this learner could not previously draw: it judges only where BG LANDS. A
     * meal that runs 4.7 → 9.7 and then settles just under target books exactly the same weaken as
     * one that never rose at all, because both ended below target. The first is not too much
     * insulin — it is too little, too late, and weakening it makes tomorrow's spike worse. That is
     * a shape problem, and it is normally the entry-fraction learner's to fix; this only applies
     * once that knob is railed at its maximum and cannot fix anything.
     *
     * A spike ALONE is not that evidence, though, and this is the important half of the test. UAM
     * cannot fire until BG is already climbing, so every UAM episode spikes by construction; a
     * 7-8mmol peak that turns and comes back is the system working, and strengthening on it would
     * ratchet the mode up meal after meal for doing its job. What separates the two is not how
     * high BG got but how long it STAYED there: insulin that is merely late still bends the curve
     * within a reading or two of the peak, while insulin that is genuinely short leaves BG parked
     * above the bar. So both must hold — a peak past [LATE_SPIKE_MARGIN_MGDL] AND
     * [SPIKE_SUSTAINED_MS] stuck at or above it.
     */
    /**
     * The spike itself was too high for the dose — the one outcome this learner strengthens on
     * without a tail. Same held-peak test as [lateSpikeWithNoShapeLeft]; the difference is who else
     * could fix it. A UAM entry mode has the entry fraction to front-load first, so this waits for
     * that to rail. A manual mode has no such knob, so the ISF is the only lever. P/F is excluded:
     * it is a tail mode, and BG held high in it is the stall DURA exists for.
     */
    private fun spikeTooHigh(mode: MealMode, targetMgdl: Double): Boolean {
        if (mode == MealMode.UAM_PROTEIN_FAT) return false
        val noShapeLeft = if (UamEntryFractionLearner.isEntryMode(mode)) episodeShapeRailed else true
        return noShapeLeft &&
            (episodePeakMgdl - targetMgdl) >= spikeMarginMgdl() &&
            (episodePeakMgdl - episodeStartBgMgdl) >= MIN_RISE_FOR_SPIKE_MGDL &&
            episodeSpikeMs >= spikeSustainedMs()
    }

    private fun stillFalling(): Boolean =
        tailDeltas.size >= TREND_READINGS && tailDeltas.average() <= -STILL_FALLING_MGDL_PER_5MIN

    private fun noChange(scope: Scope, outcome: String, countEpisode: Boolean = true) {
        val s = states.getOrPut(scope.key) { ModeState() }
        if (countEpisode) s.episodes++
        persist()
        lastOutcome = "$outcome (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
    }

    private fun lateSpikeWithNoShapeLeft(targetMgdl: Double): Boolean =
        episodeShapeRailed &&
            (episodePeakMgdl - targetMgdl) >= spikeMarginMgdl() &&
            episodeSpikeMs >= spikeSustainedMs()

    // -- User bias ------------------------------------------------------------
    // One dial, read fresh each time so a change takes effect on the next episode rather than at
    // the next restart. Steps that add insulin scale up toward reactive; steps that remove it only
    // ever scale up toward conservative (see LearningBias).
    private fun bias() = LearningBias.from(sp)
    private fun strengthenStep() = scaleStrengthenStep(STRENGTHEN_STEP, bias())
    private fun spikeMarginMgdl() = LATE_SPIKE_MARGIN_MGDL * bias().barScale
    private fun spikeSustainedMs() = (SPIKE_SUSTAINED_MS * bias().barScale).toLong()
    private fun strengthenMarginMgdl() = STRENGTHEN_MARGIN_MGDL * bias().barScale

    private fun weakenStep(unexplained: Boolean, undershoot: Boolean = false, voided: Boolean = false,
                           lateSpike: Boolean = false, duraDrove: Boolean = false,
                           share: Double = 1.0): Double {
        var fraction = bias().safetyScale
        if (unexplained) fraction *= UNEXPLAINED_STEP_FRACTION
        if (undershoot)  fraction *= UNDERSHOOT_STEP_FRACTION
        if (voided)      fraction *= VOIDED_STEP_FRACTION
        if (lateSpike)   fraction *= LATE_SPIKE_STEP_FRACTION
        if (duraDrove)   fraction *= DURA_ATTRIBUTION_STEP_FRACTION
        fraction *= share.coerceIn(0.0, 1.0)
        return 1.0 + (WEAKEN_STEP - 1.0) * fraction
    }

    private fun openWatch(mode: Scope, nowMs: Long, episodeStartMs: Long, maxDura: Double, watchMs: Long = WATCH_MS,
                          counted: Boolean = false, windowStep: Double = 1.0) {
        watchCounted = counted
        watchWindowStep = windowStep
        watchScope   = mode
        watchUntilMs = nowMs + watchMs
        watchStartMs = episodeStartMs
        watchReduced = false
        watchMaxDura = maxDura
    }

    private fun clearWatch() {
        watchScope   = null
        watchUntilMs = 0L
        watchStartMs = 0L
        watchReduced = false
        watchMaxDura = 1.0
        watchCounted = false
        watchWindowStep = 1.0
    }

    /** True when DURA was pushing hard enough that the low is its bill, not the mode dose's. */
    private fun duraDrove(maxDura: Double) = maxDura >= DURA_INTERVENTION_MULT

    /** " with DURA ×1.23 pushing — mostly charged to DURA" for the outcome line, or "". */
    private fun duraNote(maxDura: Double) =
        if (duraDrove(maxDura)) " with DURA ×${"%.2f".format(maxDura)} pushing — mostly charged to DURA" else ""

    /**
     * The entry-fraction learner judged this episode under-front-loaded, but its knob is already
     * railed at the maximum share — the first SMBs carry the whole computed requirement and there
     * is no more shape to give.
     *
     * The two learners are kept apart so one mistake never gets corrected twice, and that holds
     * for as long as the shape knob can actually move. A railed knob corrects nothing. Without
     * this the pair has a blind spot exactly where it hurts: a meal that spikes hard and still
     * lands on target fails this learner's own test (which only strengthens on ending HIGH) and
     * fails the entry learner's actuator, so "big spike, good landing, front-loading maxed" is
     * seen by neither and the mode never gets stronger.
     *
     * Strengthens at the normal step — half the weaken step, and every low or undershoot still
     * pulls back twice as fast and cannot be skipped.
     */
    fun noteShapeRailed(mode: MealMode, episodeStartMs: Long) {
        // Entry modes never carry a P/F window, so the plain scope is always the right slot here.
        if (mode == lastMovedMode && episodeStartMs == lastMovedStartMs) {
            // Already corrected for this same episode (a DURA strengthen, say). One episode,
            // one move — the handoff is a fallback for a blind spot, not a second helping.
            aapsLogger.debug(LTag.APS, "ModeIsfLearner: shape handoff for ${mode.label} ignored — episode already learned from")
            return
        }
        applyOutcome(scopeOf(mode, PfWindow.NONE), STRENGTHEN_STEP,
                     "${mode.label} spiked with entry front-loading already maxed out — strengthened",
                     episodeStartMs)
    }

    private fun applyOutcome(scope: Scope, step: Double, reason: String, episodeStartMs: Long = 0L,
                             countEpisode: Boolean = true) {
        val s = states.getOrPut(scope.key) { ModeState() }
        s.mult = (s.mult * step).coerceIn(MULT_MIN, MULT_MAX)
        if (countEpisode) s.episodes++
        lastMovedMode    = scope.mode
        lastMovedStartMs = episodeStartMs
        persist()
        lastOutcome = "$reason → ×${"%.3f".format(s.mult)} (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
    }

    private fun skipPending(reason: String) {
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: evaluation skipped — $reason")
        lastOutcome = "skipped: $reason"
        pendingScope = null
    }

    fun reset() {
        states.clear()
        episodePeakMgdl = 0.0; episodeSpikeMs = 0L; spikeRunStartMs = 0L; episodeShapeRailed = false
        activeScope = null; activeStartMs = 0L; episodeLow = false; episodeEarlyLow = false
        episodeUndershoot = false; episodeLowWasUnexplained = false; episodeMaxDura = 1.0
        episodeCarriedInLow = false
        pendingScope = null; pendingStartMs = 0L
        lastMovedMode = null; lastMovedStartMs = 0L
        clearWatch()
        lastOutcome = ""
        sp.edit { putString(StringNonKey.ApsSmartInsulinModeIsfLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (key, s) ->
                val obj = JSONObject().put(K_MULT, s.mult).put(K_N, s.episodes)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                json.put(key, obj)
            }
            sp.edit { putString(StringNonKey.ApsSmartInsulinModeIsfLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ModeIsfLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinModeIsfLearnerState.key, StringNonKey.ApsSmartInsulinModeIsfLearnerState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val obj = json.optJSONObject(key) ?: return@forEach
                val st  = ModeState(
                    mult     = obj.optDouble(K_MULT, 1.0).coerceIn(MULT_MIN, MULT_MAX),
                    episodes = obj.optInt(K_N, 0),
                    baseSig  = obj.optDouble(K_BASE, Double.NaN)
                )
                if (key == MealMode.UAM_PROTEIN_FAT.name) {
                    // Migration: state written before P/F was split by ISF window. That single
                    // multiplier was learned from episodes across all of them, so it is the best
                    // starting estimate for each — seed every window with it and let them diverge
                    // from real evidence rather than throwing the history away and restarting at
                    // 1.0. The episode count rides along for the same reason: those episodes did
                    // inform the value each window now starts from.
                    PfWindow.PF_WINDOWS.forEach { w ->
                        states.getOrPut(PfWindow.stateKey(MealMode.UAM_PROTEIN_FAT, w)) { st.copy() }
                    }
                    return@forEach
                }
                states[key] = st
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ModeIsfLearner: restore failed: ${e.message}")
        }
    }
}
