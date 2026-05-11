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
 * PdpLearner — per-hour accuracy learner for Persistent Deviation Prediction.
 *
 * ## What it learns (per hour, 24 slots)
 *
 * ### Strength multiplier [MIN_STRENGTH_MULT, MAX_STRENGTH_MULT]
 * Scales the user's configured ciStrength/risingStrength for this hour.
 * - secondaryWon → nudge UP (this hour needs more PDP aggressiveness)
 * - primaryWon   → nudge DOWN toward 1.0 (over-predicted, ease off)
 * - artifact     → no update (both wrong, CGM noise — skip)
 *
 * ### Fade multiplier [MIN_FADE_MULT, MAX_FADE_MULT]
 * Scales the user's configured fadeMins for this hour.
 * - secondaryWon by large margin → nudge UP (deviation lasts longer here)
 * - primaryWon                   → nudge DOWN (secondary faded too slowly)
 * Fade learns slower than strength (FADE_ALPHA < STRENGTH_ALPHA) for safety.
 *
 * ## Artifact / fake-rise protection
 * If BOTH primaryErr and secondaryErr exceed ARTIFACT_THRESHOLD_MGDL (2 mmol),
 * neither prediction was right — this is a CGM artifact or sensor noise.
 * Skip learning entirely. This prevents a brief spike→crash training the
 * learner to be more aggressive at that hour.
 *
 * ## Confidence
 * Grows with samples, decays slowly without. Low confidence blends both
 * multipliers back toward 1.0 so new hours start conservative.
 * blendWeightConfidenceScale floors at 0.5 so PDP works from day 1.
 *
 * ## Persistence
 * Manual JSON matching AggressionLearner/BasalLearner pattern.
 * Load handles migration from old format (strength → strengthMult).
 */
@Singleton
class PdpLearner @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger:  AAPSLogger
) {

    // ── Per-hour state ────────────────────────────────────────────────────────

    data class HourSlot(
        val strengthMult: Double = 1.0,  // learned strength multiplier
        val fadeMult:     Double = 1.0,  // learned fade multiplier
        val confidence:   Double = 0.0,  // 0.0–1.0
        val samples:      Int    = 0
    )

    private val hours: Array<HourSlot> = Array(24) { HourSlot() }

    companion object {
        private const val MIN_STRENGTH_MULT        = 0.3
        private const val MAX_STRENGTH_MULT        = 3.0
        private const val MIN_FADE_MULT            = 0.5   // half configured fadeMins minimum
        private const val MAX_FADE_MULT            = 2.0   // double configured fadeMins maximum
        private const val STRENGTH_ALPHA           = 0.08
        private const val FADE_ALPHA               = 0.05  // slower than strength — more conservative
        private const val CONFIDENCE_GAIN_BASE     = 0.05
        private const val CONFIDENCE_DECAY         = 0.002
        private const val MIN_SAMPLES_FULL_TRUST   = 20
        private const val MIN_ERROR_MGDL           = 1.8   // ~0.1 mmol noise floor
        private const val ARTIFACT_THRESHOLD_MGDL  = 36.0  // 2.0 mmol — both wrong → artifact, skip
        private const val FADE_WIN_RATIO_THRESHOLD = 0.3   // min win ratio to adjust fade
    }

    init { load() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record accuracy observation for [hour].
     *
     * @param hour              Hour of day (0–23) when prediction was made
     * @param primaryErrMgdl    |actual - primaryPredicted| at t+5min (mg/dL)
     * @param secondaryErrMgdl  |actual - secondaryPredicted| at t+5min (mg/dL)
     * @param pathway           "stuck" or "rising" — which PDP pathway was active
     */
    fun recordAccuracy(
        hour:             Int,
        primaryErrMgdl:   Double,
        secondaryErrMgdl: Double,
        pathway:          String = "stuck"
    ) {
        val h    = hour.coerceIn(0, 23)
        val slot = hours[h]

        // Artifact detection — both predictions far off → CGM noise, skip
        if (primaryErrMgdl > ARTIFACT_THRESHOLD_MGDL && secondaryErrMgdl > ARTIFACT_THRESHOLD_MGDL) {
            aapsLogger.debug(LTag.APS,
                             "PdpLearner[$pathway h=$h]: ARTIFACT — " +
                                 "pErr=${String.format("%.1f", primaryErrMgdl)} " +
                                 "sErr=${String.format("%.1f", secondaryErrMgdl)} both > 2mmol, skipping")
            tickDecayExcept(h)
            return
        }

        // Noise floor — predictions nearly identical, no useful signal
        val errDiff = abs(primaryErrMgdl - secondaryErrMgdl)
        if (errDiff < MIN_ERROR_MGDL) {
            aapsLogger.debug(LTag.APS,
                             "PdpLearner[$pathway h=$h]: skip — errDiff=${String.format("%.1f", errDiff)} < noise floor")
            tickDecayExcept(h)
            return
        }

        val secondaryWon = secondaryErrMgdl < primaryErrMgdl
        val betterErr    = minOf(primaryErrMgdl, secondaryErrMgdl).coerceAtLeast(0.1)
        val worseErr     = maxOf(primaryErrMgdl, secondaryErrMgdl).coerceAtLeast(0.1)
        val winRatio     = (1.0 - betterErr / worseErr).coerceIn(0.0, 1.0)

        // ── Strength ─────────────────────────────────────────────────────────
        // secondaryWon → push up; primaryWon → pull toward 1.0 (floor at 1.0 — PDP never fights IOB)
        val strengthTarget = when {
            secondaryWon -> (slot.strengthMult + winRatio * 0.5).coerceAtMost(MAX_STRENGTH_MULT)
            else         -> (slot.strengthMult - winRatio * 0.3).coerceAtLeast(1.0)
        }
        val newStrengthMult = (slot.strengthMult + STRENGTH_ALPHA * (strengthTarget - slot.strengthMult))
            .coerceIn(MIN_STRENGTH_MULT, MAX_STRENGTH_MULT)

        // ── Fade ─────────────────────────────────────────────────────────────
        // Only adjust when win ratio is meaningful.
        // secondaryWon by large margin → deviation lasted longer → increase fade
        // primaryWon → secondary faded too slowly → shorten fade
        // For rising pathway: primaryWon strongly means it was a fake rise → shorten aggressively
        val newFadeMult = if (winRatio > FADE_WIN_RATIO_THRESHOLD) {
            val fadeNudge = when {
                secondaryWon -> winRatio * 0.3
                pathway == "rising" -> -(winRatio * 0.25)  // extra aggressive fade-shortening on fake rises
                else -> -(winRatio * 0.2)
            }
            val fadeTarget = (slot.fadeMult + fadeNudge).coerceIn(MIN_FADE_MULT, MAX_FADE_MULT)
            (slot.fadeMult + FADE_ALPHA * (fadeTarget - slot.fadeMult))
                .coerceIn(MIN_FADE_MULT, MAX_FADE_MULT)
        } else slot.fadeMult

        // ── Confidence ───────────────────────────────────────────────────────
        val newSamples    = slot.samples + 1
        val confContrib   = CONFIDENCE_GAIN_BASE * (newSamples.toDouble() / (newSamples + MIN_SAMPLES_FULL_TRUST))
        val newConfidence = (slot.confidence + confContrib).coerceIn(0.0, 1.0)

        hours[h] = slot.copy(
            strengthMult = newStrengthMult,
            fadeMult     = newFadeMult,
            confidence   = newConfidence,
            samples      = newSamples
        )

        aapsLogger.debug(LTag.APS,
                         "PdpLearner[$pathway h=$h]: ${if (secondaryWon) "PDP" else "IOB"} won " +
                             "ratio=${String.format("%.2f", winRatio)} " +
                             "pErr=${String.format("%.1f", primaryErrMgdl)} " +
                             "sErr=${String.format("%.1f", secondaryErrMgdl)} " +
                             "str ${String.format("%.3f", slot.strengthMult)}→${String.format("%.3f", newStrengthMult)} " +
                             "fade ${String.format("%.3f", slot.fadeMult)}→${String.format("%.3f", newFadeMult)} " +
                             "conf=${String.format("%.2f", newConfidence)} n=$newSamples")

        tickDecayExcept(h)
        save()
    }

    // ── Effective values (confidence-blended) ─────────────────────────────────

    /**
     * Effective ciStrength for stuck-high pathway at [hour].
     * confidence=0 → baseCiStrength unchanged; confidence=1 → baseCiStrength * learnedMult
     */
    fun effectiveCiStrength(hour: Int, baseCiStrength: Double): Double {
        val slot  = hours[hour.coerceIn(0, 23)]
        val blend = slot.confidence.coerceIn(0.0, 1.0)
        val mult  = 1.0 + blend * (slot.strengthMult - 1.0)
        return (baseCiStrength * mult).coerceIn(MIN_STRENGTH_MULT, baseCiStrength * MAX_STRENGTH_MULT)
    }

    /**
     * Effective risingStrength for rising pathway at [hour].
     * Same multiplier as ciStrength but clamped tighter (max 1.5× user setting).
     */
    fun effectiveRisingStrength(hour: Int, baseRisingStrength: Double): Double {
        val slot  = hours[hour.coerceIn(0, 23)]
        val blend = slot.confidence.coerceIn(0.0, 1.0)
        val mult  = 1.0 + blend * (slot.strengthMult - 1.0)
        return (baseRisingStrength * mult).coerceIn(0.5, baseRisingStrength * 1.5)
    }

    /**
     * Effective fade duration at [hour] in minutes.
     * confidence=0 → baseFadeMins unchanged; confidence=1 → baseFadeMins * learnedFadeMult
     */
    fun effectiveFadeMins(hour: Int, baseFadeMins: Double): Double {
        val slot  = hours[hour.coerceIn(0, 23)]
        val blend = slot.confidence.coerceIn(0.0, 1.0)
        val mult  = 1.0 + blend * (slot.fadeMult - 1.0)
        return (baseFadeMins * mult).coerceIn(
            baseFadeMins * MIN_FADE_MULT,
            baseFadeMins * MAX_FADE_MULT
        )
    }

    /**
     * Confidence scale for blend weight [0.5, 1.0].
     * Floors at 0.5 so PDP works from day 1 at half max blend.
     */
    fun blendWeightConfidenceScale(hour: Int): Double =
        0.5 + 0.5 * hours[hour.coerceIn(0, 23)].confidence.coerceIn(0.0, 1.0)

    fun strengthMultAt(hour: Int): Double = hours[hour.coerceIn(0, 23)].strengthMult
    fun fadeMultAt(hour: Int): Double     = hours[hour.coerceIn(0, 23)].fadeMult
    fun confidenceAt(hour: Int): Double   = hours[hour.coerceIn(0, 23)].confidence
    fun samplesAt(hour: Int): Int         = hours[hour.coerceIn(0, 23)].samples

    /** Public decay tick for hours that didn't sample this cycle. */
    fun tickAllDecay(exceptHour: Int) = tickDecayExcept(exceptHour)

    fun reset() {
        for (h in 0..23) hours[h] = HourSlot()
        save()
        aapsLogger.debug(LTag.APS, "PdpLearner: reset")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun save() {
        try {
            val arr = JSONArray()
            for (h in 0..23) {
                arr.put(JSONObject().apply {
                    put("strengthMult", hours[h].strengthMult)
                    put("fadeMult",     hours[h].fadeMult)
                    put("confidence",   hours[h].confidence)
                    put("samples",      hours[h].samples)
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
                    // Migrate from old format: "strength" → "strengthMult"
                    strengthMult = obj.optDouble("strengthMult", obj.optDouble("strength", 1.0)),
                    fadeMult     = obj.optDouble("fadeMult",     1.0),
                    confidence   = obj.optDouble("confidence",   0.0),
                    samples      = obj.optInt("samples",         0)
                )
            }
            aapsLogger.debug(LTag.APS, "PdpLearner: loaded (${hours.sumOf { it.samples }} total samples)")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "PdpLearner: load failed — ${e.message}")
            for (h in 0..23) hours[h] = HourSlot()
        }
    }

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

    fun summaryTable(currentHour: Int, baseCiStrength: Double, baseFadeMins: Double): String = buildString {
        appendLine("── PDP Learner 24h ──────────────────────────────────────────")
        appendLine("  Hr  StrMult  EffStr  FadeMult  EffFade  Conf   n")
        for (h in 0..23) {
            val marker  = if (h == currentHour) "▶" else " "
            val slot    = hours[h]
            val effStr  = effectiveCiStrength(h, baseCiStrength)
            val effFade = effectiveFadeMins(h, baseFadeMins)
            appendLine(
                "$marker ${h.toString().padStart(2)}  " +
                    "${"%.3f".format(slot.strengthMult).padStart(7)}  " +
                    "${"%.2f".format(effStr).padStart(6)}  " +
                    "${"%.3f".format(slot.fadeMult).padStart(8)}  " +
                    "${"%.0f".format(effFade).padStart(5)}m  " +
                    "${"%.2f".format(slot.confidence).padStart(5)}  " +
                    "${slot.samples}"
            )
        }
    }.trimEnd()
}