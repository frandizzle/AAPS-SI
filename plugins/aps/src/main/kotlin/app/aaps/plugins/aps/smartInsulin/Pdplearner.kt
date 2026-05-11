package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * PdpLearner — Persistent Deviation Prediction per-hour accuracy learner.
 *
 * Tracks how well the secondary (PDP) prediction curve matches actual BG movement
 * compared to the primary (IOB-only) prediction curve. Uses this accuracy signal
 * to nudge a per-hour ciStrength multiplier up or down over time.
 *
 * ## How it learns
 * Each loop cycle after a prediction was made, we compare:
 *   primaryError   = |actual BG - primaryPredictedBg|   (mg/dL)
 *   secondaryError = |actual BG - secondaryPredictedBg| (mg/dL)
 *
 * If secondary was more accurate → nudge hourlyStrength[hour] UP
 *   (deviation persists longer here than IOB alone predicts)
 * If primary was more accurate   → nudge hourlyStrength[hour] toward neutral 1.0
 *   (IOB alone explains BG here, don't need extra deviation persistence)
 *
 * ## Per-hour state
 * 24 slots, one per hour of day. Each slot tracks:
 *   strength    — learned ciStrength multiplier, clamped [MIN_STRENGTH, MAX_STRENGTH]
 *   confidence  — 0.0–1.0, grows with samples, decays slowly without them
 *   samples     — total observations recorded for this hour
 *
 * ## Blend weight scaling
 * effectiveCiStrength() blends the learned value toward 1.0 when confidence is low,
 * so early learning doesn't wildly shift dosing on sparse data.
 *
 * ## Persistence
 * Manual JSON via SharedPreferences (StringKey.ApsSmartInsulinPdpLearnerState).
 * Matches the pattern used by AggressionLearner, BasalLearner, CircadianLearner.
 *
 * ## Safety
 * No side effects on meal modes or UAM — purely fasting prediction adjustment.
 * effectivePdpBlend = 0.0 when mealMode != FASTING (enforced in DetermineBasalSmartInsulin).
 */
@Singleton
class PdpLearner @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger:  AAPSLogger
) {

    // ── Per-hour state ────────────────────────────────────────────────────────

    data class HourSlot(
        val strength:   Double = 1.0,   // learned ciStrength multiplier
        val confidence: Double = 0.0,   // 0.0–1.0
        val samples:    Int    = 0      // total observations
    )

    private val hours: Array<HourSlot> = Array(24) { HourSlot() }

    companion object {
        private const val MIN_STRENGTH             = 0.3    // floor: PDP can't drop below 30% of base ci
        private const val MAX_STRENGTH             = 3.0    // ceiling: PDP can't exceed 3x base ci
        private const val LEARN_ALPHA              = 0.08   // EMA step size per observation
        private const val CONFIDENCE_GAIN_BASE     = 0.05   // confidence gained per sample
        private const val CONFIDENCE_DECAY         = 0.002  // slow decay per non-sampled cycle (~8h to halve at 12 cycles/h)
        private const val MIN_ERROR_MGDL           = 1.8    // ~0.1 mmol — ignore near-identical predictions (noise floor)
        private const val CONFIDENCE_BLEND_FLOOR   = 0.2    // below this, blend heavily toward neutral 1.0
        private const val MIN_SAMPLES_FULL_TRUST   = 20     // samples for full confidence contribution
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        load()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a prediction accuracy observation for [hour].
     * Called each loop cycle during FASTING when we have a fresh CGM reading to
     * compare against the predictions made on the previous cycle.
     *
     * @param hour              Hour of day (0–23) when the prediction was made
     * @param primaryErrMgdl    |actual - primaryPredicted| at t+5min (mg/dL)
     * @param secondaryErrMgdl  |actual - secondaryPredicted| at t+5min (mg/dL)
     */
    fun recordAccuracy(hour: Int, primaryErrMgdl: Double, secondaryErrMgdl: Double) {
        val h    = hour.coerceIn(0, 23)
        val slot = hours[h]

        // Skip if both predictions are nearly identical — no useful signal
        val errDiff = abs(primaryErrMgdl - secondaryErrMgdl)
        if (errDiff < MIN_ERROR_MGDL) {
            aapsLogger.debug(LTag.APS,
                             "PdpLearner[h=$h]: skip — errDiff=${String.format("%.1f", errDiff)} mg/dL < noise floor")
            tickDecayExcept(h)
            return
        }

        val secondaryWon = secondaryErrMgdl < primaryErrMgdl

        // Win ratio: how much better the winning prediction was (0=tie, 1=complete win)
        val betterErr = minOf(primaryErrMgdl, secondaryErrMgdl).coerceAtLeast(0.1)
        val worseErr  = maxOf(primaryErrMgdl, secondaryErrMgdl).coerceAtLeast(0.1)
        val winRatio  = (1.0 - betterErr / worseErr).coerceIn(0.0, 1.0)

        // Target: secondary win → push strength up; primary win → nudge toward neutral 1.0
        // We never push below 1.0 on primary win — that would make PDP work against IOB.
        val target = when {
            secondaryWon -> (slot.strength + winRatio * 0.5).coerceAtMost(MAX_STRENGTH)
            else         -> (slot.strength - winRatio * 0.3).coerceAtLeast(1.0)
        }

        val newStrength   = (slot.strength + LEARN_ALPHA * (target - slot.strength))
            .coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        val newSamples    = slot.samples + 1
        val confContrib   = CONFIDENCE_GAIN_BASE * (newSamples.toDouble() / (newSamples + MIN_SAMPLES_FULL_TRUST))
        val newConfidence = (slot.confidence + confContrib).coerceIn(0.0, 1.0)

        hours[h] = slot.copy(
            strength   = newStrength,
            confidence = newConfidence,
            samples    = newSamples
        )

        aapsLogger.debug(LTag.APS,
                         "PdpLearner[h=$h]: ${if (secondaryWon) "PDP" else "IOB"} won " +
                             "pErr=${String.format("%.1f", primaryErrMgdl)} " +
                             "sErr=${String.format("%.1f", secondaryErrMgdl)} " +
                             "ratio=${String.format("%.2f", winRatio)} " +
                             "str ${String.format("%.3f", slot.strength)}→${String.format("%.3f", newStrength)} " +
                             "conf=${String.format("%.2f", newConfidence)} n=$newSamples")

        tickDecayExcept(h)
        save()
    }

    /**
     * Effective ciStrength for [hour].
     * Blends learned value toward 1.0 when confidence is low.
     * At confidence=0.0 → returns baseCiStrength (user preference, no learned boost).
     * At confidence=1.0 → returns baseCiStrength * learnedMultiplier.
     */
    fun effectiveCiStrength(hour: Int, baseCiStrength: Double): Double {
        val h     = hour.coerceIn(0, 23)
        val slot  = hours[h]
        val blend = slot.confidence.coerceIn(0.0, 1.0)
        // Blend: confidence=0 → multiplier=1.0 (no effect), confidence=1 → full learned multiplier
        val learnedMult = 1.0 + blend * (slot.strength - 1.0)
        return (baseCiStrength * learnedMult).coerceIn(MIN_STRENGTH, MAX_STRENGTH)
    }

    /**
     * Confidence scale for blend weight [0.0, 1.0].
     * Below CONFIDENCE_BLEND_FLOOR: heavily dampened so untrained hours don't
     * apply aggressive PDP blending. Scales linearly to 1.0 above the floor.
     */
    /**
     * Confidence scale for blend weight [0.5, 1.0].
     * Floors at 0.5 so PDP works from day 1 at half the configured max blend weight.
     * As learning accumulates confidence, scale rises toward 1.0 (full max blend).
     * This way the user doesn't need to wait for observations before PDP does anything —
     * it starts conservative and learns to be more aggressive where it proves accurate.
     */
    fun blendWeightConfidenceScale(hour: Int): Double {
        val conf = hours[hour.coerceIn(0, 23)].confidence
        // Floor at 0.5: new install starts at half blend weight, not zero
        // Scales from 0.5 → 1.0 as confidence grows from 0 → 1.0
        return 0.5 + 0.5 * conf.coerceIn(0.0, 1.0)
    }

    fun strengthAt(hour: Int): Double  = hours[hour.coerceIn(0, 23)].strength
    fun confidenceAt(hour: Int): Double = hours[hour.coerceIn(0, 23)].confidence
    fun samplesAt(hour: Int): Int       = hours[hour.coerceIn(0, 23)].samples

    fun reset() {
        for (h in 0..23) hours[h] = HourSlot()
        save()
        aapsLogger.debug(LTag.APS, "PdpLearner: reset")
    }

    // ── Persistence — manual JSON, matches AggressionLearner/BasalLearner pattern ──

    private fun save() {
        try {
            val arr = JSONArray()
            for (h in 0..23) {
                arr.put(JSONObject().apply {
                    put("strength",   hours[h].strength)
                    put("confidence", hours[h].confidence)
                    put("samples",    hours[h].samples)
                })
            }
            preferences.put(StringKey.ApsSmartInsulinPdpLearnerState, arr.toString())
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "PdpLearner: save failed — ${e.message}")
        }
    }

    private fun load() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinPdpLearnerState)
            if (raw.isBlank()) return
            val arr = JSONArray(raw)
            for (h in 0 until minOf(24, arr.length())) {
                val obj = arr.getJSONObject(h)
                hours[h] = HourSlot(
                    strength   = obj.optDouble("strength",   1.0),
                    confidence = obj.optDouble("confidence", 0.0),
                    samples    = obj.optInt("samples",       0)
                )
            }
            val total = hours.sumOf { it.samples }
            aapsLogger.debug(LTag.APS, "PdpLearner: loaded ($total total samples)")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "PdpLearner: load failed, using defaults — ${e.message}")
            for (h in 0..23) hours[h] = HourSlot()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Public alias for plugin-level decay tick when PDP didn't blend this cycle. */
    fun tickAllDecay(exceptHour: Int) = tickDecayExcept(exceptHour)

    private fun tickDecayExcept(exceptHour: Int) {
        for (h in 0..23) {
            if (h != exceptHour && hours[h].confidence > 0.0) {
                hours[h] = hours[h].copy(
                    confidence = (hours[h].confidence - CONFIDENCE_DECAY).coerceAtLeast(0.0)
                )
            }
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    fun summaryTable(currentHour: Int, baseCiStrength: Double): String = buildString {
        appendLine("── PDP Learner 24h ──────────────────")
        appendLine("  Hr  Strength  Effective  Conf   n")
        for (h in 0..23) {
            val marker    = if (h == currentHour) "▶" else " "
            val slot      = hours[h]
            val effective = effectiveCiStrength(h, baseCiStrength)
            appendLine(
                "$marker ${h.toString().padStart(2)}  " +
                    "${"%.3f".format(slot.strength).padStart(8)}  " +
                    "${"%.3f".format(effective).padStart(9)}  " +
                    "${"%.2f".format(slot.confidence).padStart(5)}  " +
                    "${slot.samples}"
            )
        }
    }.trimEnd()
}