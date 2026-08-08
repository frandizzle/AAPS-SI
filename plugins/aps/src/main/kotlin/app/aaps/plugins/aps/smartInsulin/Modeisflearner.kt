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

    private class ModeState(var mult: Double = 1.0, var episodes: Int = 0)

    private val states = mutableMapOf<MealMode, ModeState>()

    // Currently-active episode tracking
    private var activeMode:     MealMode? = null
    private var activeStartMs   = 0L
    private var episodeLow      = false
    private var episodeMaxDura  = 1.0

    // Pending post-episode evaluation (settling tail)
    private var pendingMode:      MealMode? = null
    private var pendingEvalAtMs  = 0L
    private var pendingMaxDura   = 1.0
    private var pendingTailGrams = 0.0

    /** Human-readable summary of the most recent learning decision — for the SI tab. */
    var lastOutcome = ""
        private set

    init { restore() }

    companion object {
        private const val TAIL_MS                 = 75 * 60_000L  // settling tail before judging the episode
        private const val STRENGTHEN_STEP         = 0.975         // -2.5% per strengthen (lower ISF = more insulin)
        private const val WEAKEN_STEP             = 1.05          // +5% per weaken — asymmetric, safety-biased
        private const val MULT_MIN                = 0.6
        private const val MULT_MAX                = 1.4
        private const val STRENGTHEN_MARGIN_MGDL  = 18.0          // ~1 mmol above target at eval = under-dosed
        private const val TAIL_CONTAMINATION_G    = 8.0           // est. grams during tail that voids the evaluation
        private const val DURA_INTERVENTION_MULT  = 1.15          // DURA peak ≥ this counts as "ISF was too weak"

        private const val K_MULT = "mult"
        private const val K_N    = "n"
    }

    /** Multiplier to apply to the mode's dosing ISF. <1.0 = stronger (lower ISF). */
    fun multiplier(mode: MealMode): Double = states[mode]?.mult ?: 1.0

    fun statusString(): String = buildString {
        MealMode.entries.forEach { mode ->
            val s = states[mode] ?: return@forEach
            if (s.episodes > 0) appendLine("${mode.label.padEnd(20)} ×${"%.3f".format(s.mult)} (n=${s.episodes})")
        }
        if (lastOutcome.isNotEmpty()) appendLine("Last: $lastOutcome")
    }.trimEnd()

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
        nowMs:          Long
    ) {
        if (activeModeNow != null) {
            if (activeMode == null || modeStartMs != activeStartMs) {
                // A new activation while an evaluation is still pending contaminates it —
                // the tail can no longer be judged cleanly.
                pendingMode?.let { skipPending("superseded by new ${activeModeNow.label} activation") }
                activeMode     = activeModeNow
                activeStartMs  = modeStartMs
                episodeLow     = false
                episodeMaxDura = 1.0
            }
            if (lowActive) episodeLow = true
            if (duraMult > episodeMaxDura) episodeMaxDura = duraMult
            return
        }

        // No mode active — did one just end?
        if (activeMode != null) {
            val ended   = activeMode!!
            val hadLow  = episodeLow
            val maxDura = episodeMaxDura
            activeMode    = null
            activeStartMs = 0L
            if (hadLow) {
                // A low during the episode is a definitive outcome — no tail wait needed, and
                // deliberately NOT skippable by later contamination: weaken signals must land.
                applyOutcome(ended, WEAKEN_STEP, "low during ${ended.label} episode — weakened")
            } else {
                pendingMode      = ended
                pendingEvalAtMs  = nowMs + TAIL_MS
                pendingMaxDura   = maxDura
                pendingTailGrams = 0.0
            }
        }

        val p = pendingMode ?: return
        if (lowActive) {
            applyOutcome(p, WEAKEN_STEP, "low during ${p.label} settling tail — weakened")
            pendingMode = null
            return
        }
        pendingTailGrams += tailGramsThisCycle(deltaMgdl, activityPerMin, fastingIsfMgdl, carbRatio)
        if (pendingTailGrams > TAIL_CONTAMINATION_G) {
            skipPending("fresh absorption (~${"%.0f".format(pendingTailGrams)}g) in ${p.label} tail")
            return
        }
        if (nowMs >= pendingEvalAtMs) {
            when {
                bgMgdl > targetMgdl + STRENGTHEN_MARGIN_MGDL ->
                    applyOutcome(p, STRENGTHEN_STEP, "${p.label} ended ${"%.1f".format((bgMgdl - targetMgdl) / 18.0)}mmol above target — strengthened")
                pendingMaxDura >= DURA_INTERVENTION_MULT ->
                    applyOutcome(p, STRENGTHEN_STEP, "${p.label} needed DURA ×${"%.2f".format(pendingMaxDura)} — strengthened")
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

    private fun applyOutcome(mode: MealMode, step: Double, reason: String) {
        val s = states.getOrPut(mode) { ModeState() }
        s.mult = (s.mult * step).coerceIn(MULT_MIN, MULT_MAX)
        s.episodes++
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
        activeMode = null; activeStartMs = 0L; episodeLow = false; episodeMaxDura = 1.0
        pendingMode = null
        lastOutcome = ""
        sp.edit { putString(StringKey.ApsSmartInsulinModeIsfLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "ModeIsfLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (mode, s) ->
                json.put(mode.name, JSONObject().put(K_MULT, s.mult).put(K_N, s.episodes))
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
                    episodes = obj.optInt(K_N, 0)
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ModeIsfLearner: restore failed: ${e.message}")
        }
    }
}
