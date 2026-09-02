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
 *   - BG still above target+margin at evaluation → mode ISF STRENGTHENS (small step)
 *   - DURA had to intervene hard mid-episode     → STRENGTHENS (a clean ending that only
 *     happened because DURA cranked ISF still means the base mode ISF is too weak)
 *   - Fresh absorption during the tail (ate again) or a new mode activation → episode SKIPPED,
 *     not learned from — contaminated evaluations must never move the multiplier
 *
 * The learned value is a per-mode multiplier applied to the mode's dosing ISF (static override
 * or profile ISF), hard-railed to [MULT_MIN, MULT_MAX]. One bounded update per episode keeps
 * convergence slow and safe: a consistently under-dosed Dinner takes ~a week of dinners to
 * drift meaningfully stronger, and any single odd evening moves it at most one step.
 */
@Singleton
class ModeIsfLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private class ModeState(var mult: Double = 1.0, var episodes: Int = 0, var baseSig: Double = Double.NaN)

    private val states = mutableMapOf<MealMode, ModeState>()

    // Currently-active episode tracking
    private var activeMode:      MealMode? = null
    private var activeStartMs    = 0L
    private var episodeLow       = false
    private var episodeEarlyLow  = false  // low inside the UAM entry window — belongs to
    // UamEntryFractionLearner (shape), not to this learner (magnitude)
    private var episodeUndershoot = false  // BG dropped close to the low guard without reaching
    // it — the mode overshot its job, just not far enough to be scored as a hypo
    private var episodeLowWasUnexplained = false  // the low came with a drop insulin can't
    // explain (exercise etc.) — weaken at a reduced step
    private var episodeMaxDura   = 1.0

    // Pending post-episode evaluation (settling tail)
    private var pendingMode:      MealMode? = null
    private var pendingEvalAtMs  = 0L
    private var pendingMaxDura   = 1.0
    private var pendingTailGrams = 0.0
    private var pendingStartMs   = 0L

    // Post-episode low/undershoot watch. Deliberately separate from the pending evaluation:
    // the evaluation asks "did this land well?" and is legitimately voided by contamination or
    // by a new activation, whereas this asks "did this mode drive BG too far down?" — a safety
    // signal that must survive both. It matters more since at-target auto-cancel landed: a mode
    // that cancels the moment BG reaches target ends with MORE insulin on board than one that
    // ran to expiry, so the low it causes now routinely arrives after the mode is already gone.
    private var watchMode:     MealMode? = null
    private var watchUntilMs  = 0L
    private var watchStartMs  = 0L
    private var watchReduced  = false  // evaluation was voided — still weaken, but at a smaller step

    /** The episode this learner last actually MOVED the multiplier for. A shape handoff for an
     *  episode already corrected here must not correct it a second time. */
    private var lastMovedMode:    MealMode? = null
    private var lastMovedStartMs = 0L

    /** Human-readable summary of the most recent learning decision — for the SI tab. */
    var lastOutcome = ""
        private set

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
        /** Step fraction once the evaluation has been voided — the low is still real, but the
         *  episode is no longer clean enough to credit it in full. */
        private const val VOIDED_STEP_FRACTION      = 0.5
        private const val MULT_MIN                = 0.6
        private const val MULT_MAX                = 1.4
        private const val STRENGTHEN_MARGIN_MGDL  = 18.0          // ~1 mmol above target at eval = under-dosed
        private const val TAIL_CONTAMINATION_G    = 8.0           // est. grams during tail that voids the evaluation
        private const val DURA_INTERVENTION_MULT  = 1.15          // DURA peak ≥ this counts as "ISF was too weak"

        private const val K_MULT = "mult"
        private const val K_N    = "n"
        private const val K_BASE = "baseSig"
    }

    /** Multiplier to apply to the mode's dosing ISF. <1.0 = stronger (lower ISF). */
    fun multiplier(mode: MealMode): Double = states[mode]?.mult ?: 1.0

    /** How many episodes have been evaluated for this mode. */
    fun episodeCount(mode: MealMode): Int = states[mode]?.episodes ?: 0

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
        undershootActive: Boolean = false  // BG within the caller's undershoot band above the low
        // guard — too far down to call the episode a success, not far enough to call it a hypo
    ) {
        if (activeModeNow != null) {
            if (activeMode == null || modeStartMs != activeStartMs) {
                // A new activation while an evaluation is still pending contaminates it —
                // the tail can no longer be judged cleanly.
                pendingMode?.let { skipPending("superseded by new ${activeModeNow.label} activation") }

                // The watch hands over to the new episode, which tracks its own lows from here.
                // Keeping the old one open would weaken twice for a single low.
                clearWatch()

                // A mode replaced mid-episode (a UAM window taking over from P/F, or a meal
                // activated while a UAM runs) can never be judged on where BG lands — that tail
                // belongs to whatever took over. But a low or an undershoot it had ALREADY
                // caused is evidence about its own dose, and would otherwise be discarded along
                // with the episode. episodeEarlyLow is deliberately not carried forward: the
                // entry-fraction learner owns entry-window lows.
                activeMode?.let { superseded ->
                    when {
                        episodeLow        ->
                            applyOutcome(superseded, weakenStep(episodeLowWasUnexplained),
                                         "low during ${superseded.label} before ${activeModeNow.label} took over — weakened",
                                         activeStartMs)
                        episodeUndershoot ->
                            applyOutcome(superseded, weakenStep(episodeLowWasUnexplained, undershoot = true),
                                         "${superseded.label} undershot before ${activeModeNow.label} took over — weakened at reduced step",
                                         activeStartMs)
                    }
                }

                // Base-change reset: the learned multiplier is a correction RELATIVE to the
                // base ISF the user had set when it was learned. If the user changes the
                // override (often in the same direction the learner was already pushing),
                // keeping the multiplier would silently double-apply that correction — so a
                // changed base resets this mode to a clean slate and re-learns from there.
                val s = states.getOrPut(activeModeNow) { ModeState() }
                if (!s.baseSig.isNaN() && kotlin.math.abs(s.baseSig - baseSignature) > 0.01 && (s.mult != 1.0 || s.episodes > 0)) {
                    s.mult = 1.0
                    s.episodes = 0
                    lastOutcome = "${activeModeNow.label} ISF override changed — learned multiplier reset"
                    aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
                }
                s.baseSig = baseSignature
                persist()

                activeMode      = activeModeNow
                activeStartMs   = modeStartMs
                episodeLow      = false
                episodeEarlyLow = false
                episodeUndershoot = false
                episodeLowWasUnexplained = false
                episodeMaxDura  = 1.0
            }
            if (lowActive) {
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
        if (activeMode != null) {
            val ended        = activeMode!!
            val hadLow       = episodeLow
            val hadEarlyLow  = episodeEarlyLow
            val hadUndershoot = episodeUndershoot
            val maxDura      = episodeMaxDura
            val unexplained  = episodeLowWasUnexplained
            val endedStartMs = activeStartMs
            activeMode    = null
            activeStartMs = 0L
            if (hadLow) {
                // A low during the episode is a definitive outcome — no tail wait needed, and
                // deliberately NOT skippable by later contamination: weaken signals must land.
                applyOutcome(ended, weakenStep(unexplained),
                             if (unexplained) "low during ${ended.label} episode, but BG was falling faster than insulin explains (exercise?) — weakened at reduced step"
                             else "low during ${ended.label} episode — weakened",
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
                applyOutcome(ended, weakenStep(unexplained, undershoot = true),
                             "${ended.label} undershot toward the low guard — weakened at reduced step",
                             endedStartMs)
                clearWatch()
            } else {
                pendingMode      = ended
                pendingEvalAtMs  = nowMs + TAIL_MS
                pendingMaxDura   = maxDura
                pendingTailGrams = 0.0
                pendingStartMs   = endedStartMs
                openWatch(ended, nowMs, endedStartMs)
            }
        }

        // Low watch, checked before (and independently of) the pending evaluation. A mode that
        // auto-cancels at target hands back a BG that looks fine and an IOB tail that isn't, so
        // this has to keep looking after the evaluation itself has been voided or superseded.
        watchMode?.let { w ->
            if (lowActive || undershootActive) {
                val soft = undershootActive && !lowActive
                applyOutcome(w, weakenStep(exerciseSuspected, undershoot = soft, voided = watchReduced),
                             (if (soft) "BG near the low guard after ${w.label} ended" else "low after ${w.label} ended") +
                                 (if (exerciseSuspected) ", but BG was falling faster than insulin explains (exercise?)" else "") +
                                 (if (watchReduced) ", episode no longer clean" else "") +
                                 " — weakened" + (if (soft || exerciseSuspected || watchReduced) " at reduced step" else ""),
                             watchStartMs)
                clearWatch()
                pendingMode = null
                return
            }
            if (nowMs >= watchUntilMs) clearWatch()
        }

        val p = pendingMode ?: return
        pendingTailGrams += tailGramsThisCycle(deltaMgdl, activityPerMin, fastingIsfMgdl, carbRatio)
        if (pendingTailGrams > TAIL_CONTAMINATION_G) {
            // Voids the *evaluation* only. The low watch stays open at a reduced step: BG that
            // refuses to fall as fast as the insulin on board predicts reads as fresh absorption,
            // which is exactly what a fat tail looks like — so this fires routinely on the very
            // episodes most likely to end low, and dropping the watch here would lose them.
            skipPending("fresh absorption (~${"%.0f".format(pendingTailGrams)}g) in ${p.label} tail")
            watchReduced = true
            return
        }
        if (nowMs >= pendingEvalAtMs) {
            when {
                bgMgdl > targetMgdl + STRENGTHEN_MARGIN_MGDL ->
                    applyOutcome(p, STRENGTHEN_STEP, "${p.label} ended ${"%.1f".format((bgMgdl - targetMgdl) / 18.0)}mmol above target — strengthened", pendingStartMs)
                pendingMaxDura >= DURA_INTERVENTION_MULT ->
                    applyOutcome(p, STRENGTHEN_STEP, "${p.label} needed DURA ×${"%.2f".format(pendingMaxDura)} — strengthened", pendingStartMs)
                else -> {
                    val s = states.getOrPut(p) { ModeState() }
                    s.episodes++
                    persist()
                    lastOutcome = "${p.label} on target — no change (n=${s.episodes})"
                    aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
                }
            }
            pendingMode = null
        }
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
    private fun weakenStep(unexplained: Boolean, undershoot: Boolean = false, voided: Boolean = false): Double {
        var fraction = 1.0
        if (unexplained) fraction *= UNEXPLAINED_STEP_FRACTION
        if (undershoot)  fraction *= UNDERSHOOT_STEP_FRACTION
        if (voided)      fraction *= VOIDED_STEP_FRACTION
        return 1.0 + (WEAKEN_STEP - 1.0) * fraction
    }

    private fun openWatch(mode: MealMode, nowMs: Long, episodeStartMs: Long) {
        watchMode    = mode
        watchUntilMs = nowMs + WATCH_MS
        watchStartMs = episodeStartMs
        watchReduced = false
    }

    private fun clearWatch() {
        watchMode    = null
        watchUntilMs = 0L
        watchStartMs = 0L
        watchReduced = false
    }

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
        if (mode == lastMovedMode && episodeStartMs == lastMovedStartMs) {
            // Already corrected for this same episode (a DURA strengthen, say). One episode,
            // one move — the handoff is a fallback for a blind spot, not a second helping.
            aapsLogger.debug(LTag.APS, "ModeIsfLearner: shape handoff for ${mode.label} ignored — episode already learned from")
            return
        }
        applyOutcome(mode, STRENGTHEN_STEP,
                     "${mode.label} spiked with entry front-loading already maxed out — strengthened",
                     episodeStartMs)
    }

    private fun applyOutcome(mode: MealMode, step: Double, reason: String, episodeStartMs: Long = 0L) {
        val s = states.getOrPut(mode) { ModeState() }
        s.mult = (s.mult * step).coerceIn(MULT_MIN, MULT_MAX)
        s.episodes++
        lastMovedMode    = mode
        lastMovedStartMs = episodeStartMs
        persist()
        lastOutcome = "$reason → ×${"%.3f".format(s.mult)} (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: $lastOutcome")
    }

    private fun skipPending(reason: String) {
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: evaluation skipped — $reason")
        lastOutcome = "skipped: $reason"
        pendingMode = null
    }

    fun reset() {
        states.clear()
        activeMode = null; activeStartMs = 0L; episodeLow = false; episodeEarlyLow = false
        episodeUndershoot = false; episodeLowWasUnexplained = false; episodeMaxDura = 1.0
        pendingMode = null; pendingStartMs = 0L
        lastMovedMode = null; lastMovedStartMs = 0L
        clearWatch()
        lastOutcome = ""
        sp.edit { putString(StringKey.ApsSmartInsulinModeIsfLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (mode, s) ->
                val obj = JSONObject().put(K_MULT, s.mult).put(K_N, s.episodes)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                json.put(mode.name, obj)
            }
            sp.edit { putString(StringKey.ApsSmartInsulinModeIsfLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ModeIsfLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringKey.ApsSmartInsulinModeIsfLearnerState.key, StringKey.ApsSmartInsulinModeIsfLearnerState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val mode = try { MealMode.valueOf(key) } catch (_: Exception) { return@forEach }
                val obj  = json.optJSONObject(key) ?: return@forEach
                states[mode] = ModeState(
                    mult     = obj.optDouble(K_MULT, 1.0).coerceIn(MULT_MIN, MULT_MAX),
                    episodes = obj.optInt(K_N, 0),
                    baseSig  = obj.optDouble(K_BASE, Double.NaN)
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ModeIsfLearner: restore failed: ${e.message}")
        }
    }
}
