package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
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
        val blendMult:    Double = 1.0,  // learned blend weight multiplier
        val confidence:   Double = 0.0,  // 0.0–1.0
        val samples:      Int    = 0
    )

    // Thread-safe copy-on-write array — written from loop coroutine,
    // read from UI thread. @Volatile ensures writes are immediately visible.
    @Volatile
    private var hours: Array<HourSlot> = Array(24) { HourSlot() }

    private fun updateSlot(h: Int, newSlot: HourSlot) {
        hours = hours.clone().also { it[h] = newSlot }
    }

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
        private const val MIN_BLEND_MULT            = 0.2   // blend weight can drop to 20% of max
        private const val MAX_BLEND_MULT            = 1.5   // blend weight can rise to 150% of max
        private const val BLEND_ALPHA               = 0.03  // very slow — blend weight changes conservatively
        // Rising pathway specific — learns even slower, no min readings gate
        private const val RISING_BLEND_ALPHA        = 0.02  // half speed of stuck-high blend learning
        private const val RISING_STRENGTH_ALPHA     = 0.04  // half speed of stuck-high strength learning
        private const val MIN_ERROR_MGDL           = 1.8   // ~0.1 mmol noise floor
        private const val ARTIFACT_THRESHOLD_MGDL  = 90.0  // 5.0 mmol — both wrong → artifact, skip
        private const val FADE_WIN_RATIO_THRESHOLD = 0.3   // min win ratio to adjust fade

        // Episode outcome learning — slower than t+5min accuracy, each episode carries more weight
        private const val EPISODE_STRENGTH_ALPHA    = 0.10  // strength response to episode outcome
        private const val EPISODE_BLEND_ALPHA       = 0.07  // blend response to episode outcome
        private const val EPISODE_FADE_ALPHA        = 0.05  // fade response to episode outcome

        // Landing zone boundaries — relative to target
        // Perfect: within ±0.3 mmol of target (e.g. 5.2–5.8 at target=5.5)
        // Good:    within +0.5 mmol above target (e.g. 5.5–6.0)
        // Partial: within +1.0 mmol above target (e.g. 6.0–6.5)
        // Missed:  more than +1.0 mmol above target (e.g. >6.5)
        private const val LANDING_PERFECT_BAND_MMOL = 0.3   // ±0.3 of target = perfect
        private const val LANDING_GOOD_HI_OFFSET    = 0.5   // target + 0.5 mmol = good ceiling
        private const val LANDING_MISS_OFFSET        = 1.0   // target + 1.0 mmol = miss threshold
        private const val MAX_EPISODE_MINS           = 240.0 // 4h — close episode if still open
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
                                 "pErr=${String.format(Locale.US, "%.1f", primaryErrMgdl)} " +
                                 "sErr=${String.format(Locale.US, "%.1f", secondaryErrMgdl)} both > 2mmol, skipping")
            tickDecayExcept(h)
            return
        }

        // Noise floor — predictions nearly identical, no useful signal
        val errDiff = abs(primaryErrMgdl - secondaryErrMgdl)
        if (errDiff < MIN_ERROR_MGDL) {
            aapsLogger.debug(LTag.APS,
                             "PdpLearner[$pathway h=$h]: skip — errDiff=${String.format(Locale.US, "%.1f", errDiff)} < noise floor")
            tickDecayExcept(h)
            return
        }

        val secondaryWon = secondaryErrMgdl < primaryErrMgdl
        val betterErr    = minOf(primaryErrMgdl, secondaryErrMgdl).coerceAtLeast(0.1)
        val worseErr     = maxOf(primaryErrMgdl, secondaryErrMgdl).coerceAtLeast(0.1)
        val winRatio     = (1.0 - betterErr / worseErr).coerceIn(0.0, 1.0)

        // ── Strength ─────────────────────────────────────────────────────────
        // secondaryWon → push up toward MAX_STRENGTH_MULT
        // primaryWon   → pull DOWN toward MIN_STRENGTH_MULT (not just 1.0)
        //   This allows the learner to fully dial back aggressiveness at hours where
        //   primary IOB consistently outperforms — not just neutralise but suppress.
        //   Example: if user's ciStrength=5 is too much at 8am, strengthMult can drop
        //   below 1.0 so effective ciStrength = 5 × 0.6 = 3.0 at that hour.
        // Rising pathway uses slower alpha — nuanced morning learning.
        val strengthAlpha = if (pathway == "rising") RISING_STRENGTH_ALPHA else STRENGTH_ALPHA
        val strengthTarget = when {
            secondaryWon -> (slot.strengthMult + winRatio * 0.5).coerceAtMost(MAX_STRENGTH_MULT)
            else         -> (slot.strengthMult - winRatio * 0.3).coerceAtLeast(MIN_STRENGTH_MULT)
        }
        val newStrengthMult = (slot.strengthMult + strengthAlpha * (strengthTarget - slot.strengthMult))
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

        // ── Blend weight ─────────────────────────────────────────────────────
        // Learns whether this hour's blend weight was too high or too low.
        // Rising pathway uses slower alpha — gives it time to learn whether
        // morning rises genuinely need more or less insulin, without overreacting.
        val blendAlpha = if (pathway == "rising") RISING_BLEND_ALPHA else BLEND_ALPHA
        val newBlendMult = if (winRatio > FADE_WIN_RATIO_THRESHOLD) {
            val blendTarget = when {
                secondaryWon -> (slot.blendMult + winRatio * 0.2).coerceAtMost(MAX_BLEND_MULT)
                else         -> (slot.blendMult - winRatio * 0.15).coerceAtLeast(MIN_BLEND_MULT)
            }
            (slot.blendMult + blendAlpha * (blendTarget - slot.blendMult))
                .coerceIn(MIN_BLEND_MULT, MAX_BLEND_MULT)
        } else slot.blendMult

        updateSlot(h, slot.copy(
            strengthMult = newStrengthMult,
            fadeMult     = newFadeMult,
            blendMult    = newBlendMult,
            confidence   = newConfidence,
            samples      = newSamples
        ))

        aapsLogger.debug(LTag.APS,
                         "PdpLearner[$pathway h=$h]: ${if (secondaryWon) "PDP" else "IOB"} won " +
                             "ratio=${String.format(Locale.US, "%.2f", winRatio)} " +
                             "pErr=${String.format(Locale.US, "%.1f", primaryErrMgdl)} " +
                             "sErr=${String.format(Locale.US, "%.1f", secondaryErrMgdl)} " +
                             "str ${String.format(Locale.US, "%.3f", slot.strengthMult)}→${String.format(Locale.US, "%.3f", newStrengthMult)} " +
                             "fade ${String.format(Locale.US, "%.3f", slot.fadeMult)}→${String.format(Locale.US, "%.3f", newFadeMult)} " +
                             "blend ${String.format(Locale.US, "%.3f", slot.blendMult)}→${String.format(Locale.US, "%.3f", newBlendMult)} " +
                             "conf=${String.format(Locale.US, "%.2f", newConfidence)} n=$newSamples")

        tickDecayExcept(h)
        save()
    }

    /**
     * Record episode outcome — called when a PDP episode closes (blend drops to 0).
     *
     * An episode opens when PDP starts blending and closes when blend returns to 0
     * (BG resolved and pathway counters reset). The outcome tells the learner whether
     * the overall dosing decision during the episode was correct:
     *
     * ## Outcome scoring
     *
     * PERFECT landing (no low, BG ends in target–5.5 mmol):
     *   → Gentle positive reinforcement on strength and blend — "this was right"
     *
     * GOOD landing (no low, BG ends in 5.5–6.0 mmol):
     *   → Very small positive nudge — could have been slightly more aggressive
     *
     * MISSED (BG still above 6.5 mmol when episode closed, no low):
     *   → Nudge strength and blend UP — PDP didn't deliver enough
     *
     * OVERSHOT into warnGuard:
     *   → Nudge strength and blend DOWN — PDP was too aggressive
     *
     * OVERSHOT into lowGuard:
     *   → Stronger pull down — significant over-correction
     *
     * ## Fade scoring
     *
     * Short episode + clean landing → fade was appropriate or too long, nudge slightly down
     * Long episode (BG slow to come down) + clean landing → fade was appropriate, hold
     * Long episode + missed → fade was too short, nudge up
     *
     * @param hour              Hour when episode started (0–23)
     * @param pathway           "stuck" or "rising"
     * @param landingBgMmol     BG when episode closed
     * @param nadirBgMmol       Lowest BG observed during episode
     * @param durationMins      How long the episode lasted
     * @param warnGuardMmol     Warn guard threshold (caution zone floor)
     * @param lowGuardMmol      Low guard threshold (suspend floor)
     * @param targetMmol        Profile target BG
     */
    fun recordEpisodeOutcome(
        hour:           Int,
        pathway:        String,
        landingBgMmol:  Double,
        nadirBgMmol:    Double,
        durationMins:   Double,
        warnGuardMmol:  Double,
        lowGuardMmol:   Double,
        targetMmol:     Double
    ) {
        val h    = hour.coerceIn(0, 23)
        val slot = hours[h]

        // ── Classify outcome ─────────────────────────────────────────────────
        val severeOvershot = nadirBgMmol < lowGuardMmol
        val overshot       = nadirBgMmol < warnGuardMmol
        // Perfect: landed within ±0.3 mmol of target, no low at all
        val perfectLanding = !overshot
            && landingBgMmol >= (targetMmol - LANDING_PERFECT_BAND_MMOL)
            && landingBgMmol <= (targetMmol + LANDING_PERFECT_BAND_MMOL)
        // Good: landed within target + 0.5 mmol (slightly high but acceptable)
        val goodLanding    = !overshot && !perfectLanding
            && landingBgMmol <= (targetMmol + LANDING_GOOD_HI_OFFSET)
        // Missed: still more than 1.0 mmol above target when episode closed
        val missed         = !overshot && landingBgMmol > (targetMmol + LANDING_MISS_OFFSET)
        val longEpisode    = durationMins > 120.0

        val outcomeLabel = when {
            severeOvershot -> "SEVERE_LOW"
            overshot       -> "OVERSHOT"
            perfectLanding -> "PERFECT"
            goodLanding    -> "GOOD"
            missed         -> "MISSED"
            else           -> "PARTIAL"
        }

        // ── Strength nudge ───────────────────────────────────────────────────
        // Perfect → ZERO — settings are exactly right, don't change them
        // Good    → tiny nudge up — slightly short of perfect, could be marginally more aggressive
        // Partial → small nudge up — needed a bit more
        // Missed  → meaningful nudge up — clearly not enough
        // Overshot → pull down proportional to severity
        val strengthDelta = when {
            severeOvershot -> -0.40  // crossed low guard — significantly too aggressive
            overshot       -> -0.20  // crossed warn guard — too aggressive
            perfectLanding -> 0.0    // perfect — settings are right, hold completely
            goodLanding    -> +0.03  // slightly short, very small nudge
            missed         -> +0.18  // clearly needed more
            else           -> +0.08  // partial (6.0–6.5) — needed a bit more
        }
        val newStrengthTarget = (slot.strengthMult + strengthDelta).coerceIn(MIN_STRENGTH_MULT, MAX_STRENGTH_MULT)
        val newStrengthMult   = (slot.strengthMult + EPISODE_STRENGTH_ALPHA * (newStrengthTarget - slot.strengthMult))
            .coerceIn(MIN_STRENGTH_MULT, MAX_STRENGTH_MULT)

        // ── Blend nudge ──────────────────────────────────────────────────────
        // Same logic as strength — perfect = 0, everything else nudges proportionally
        val blendDelta = when {
            severeOvershot -> -0.35  // crossed low guard — pull blend down hard
            overshot       -> -0.18  // crossed warn guard — meaningful pull down
            perfectLanding -> 0.0    // perfect — hold blend steady
            goodLanding    -> +0.02  // slightly short — very small nudge
            missed         -> +0.15  // clearly not enough blend
            else           -> +0.06  // partial — needed a bit more blend
        }
        val newBlendTarget = (slot.blendMult + blendDelta).coerceIn(MIN_BLEND_MULT, MAX_BLEND_MULT)
        val newBlendMult   = (slot.blendMult + EPISODE_BLEND_ALPHA * (newBlendTarget - slot.blendMult))
            .coerceIn(MIN_BLEND_MULT, MAX_BLEND_MULT)

        // ── Fade nudge ───────────────────────────────────────────────────────
        // Long episode + missed → fade too short (deviation resolved before BG corrected)
        // Short episode + overshot → fade too long (kept blending after BG corrected)
        // Perfect landing regardless of duration → fade was broadly correct, hold
        // Fade scoring: perfect = hold; overshoot = shorten; miss = lengthen if slow
        val fadeDelta = when {
            severeOvershot              -> -0.20  // crossed low guard → shorten fade significantly
            overshot && !longEpisode    -> -0.12  // quick overshoot → fade dragged on too long
            overshot && longEpisode     -> -0.06  // slow overshoot → slight trim
            missed && longEpisode       -> +0.10  // BG slow to correct → extend fade
            missed && !longEpisode      -> +0.04  // quick miss → small nudge
            perfectLanding              -> 0.0    // perfect → hold fade completely
            goodLanding                 -> 0.0    // good → hold fade
            else                        -> +0.03  // partial → slight fade extension
        }
        val newFadeTarget = (slot.fadeMult + fadeDelta).coerceIn(MIN_FADE_MULT, MAX_FADE_MULT)
        val newFadeMult   = (slot.fadeMult + EPISODE_FADE_ALPHA * (newFadeTarget - slot.fadeMult))
            .coerceIn(MIN_FADE_MULT, MAX_FADE_MULT)

        updateSlot(h, slot.copy(
            strengthMult = newStrengthMult,
            blendMult    = newBlendMult,
            fadeMult     = newFadeMult
            // confidence and samples not updated — episode scoring is outcome-based,
            // not sample-count-based. Confidence grows from recordAccuracy() only.
        ))

        aapsLogger.debug(LTag.APS,
                         "PdpLearner episode[$pathway h=$h]: $outcomeLabel " +
                             "landing=${String.format(Locale.US, "%.1f", landingBgMmol)} " +
                             "nadir=${String.format(Locale.US, "%.1f", nadirBgMmol)} " +
                             "dur=${durationMins.toInt()}min " +
                             "str ${String.format(Locale.US, "%.3f", slot.strengthMult)}→${String.format(Locale.US, "%.3f", newStrengthMult)} " +
                             "blend ${String.format(Locale.US, "%.3f", slot.blendMult)}→${String.format(Locale.US, "%.3f", newBlendMult)} " +
                             "fade ${String.format(Locale.US, "%.3f", slot.fadeMult)}→${String.format(Locale.US, "%.3f", newFadeMult)}")

        save()
    }

    // ── Effective values (confidence-blended) ─────────────────────────────────

    /**
     * Effective ciStrength for stuck-high pathway at [hour].
     * confidence=0 → baseCiStrength unchanged; confidence=1 → baseCiStrength * learnedMult
     */
    fun effectiveCiStrength(hour: Int, baseCiStrength: Double): Double {
        if (baseCiStrength <= 0.0) return 0.0  // guard: coerceIn(min, 0) would crash if min > 0
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
        if (baseRisingStrength <= 0.0) return 0.0  // guard: prevents coerceIn crash
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
     * Effective blend weight multiplier for [hour].
     * Confidence-blends the learned blendMult toward 1.0 at low confidence.
     * confidence=0 → returns 1.0 (no adjustment, use pdpMaxBlend as-is)
     * confidence=1 → returns full learned blendMult
     *
     * NOTE: This is the SINGLE confidence gate for blend weight.
     * blendWeightConfidenceScale() has been removed — it was being applied on top
     * of this function, double-penalising low confidence and explaining why blend
     * was only 35% despite pdpMaxBlend=0.7 (Deepseek review fix #2 and #6).
     */
    fun effectiveBlendMult(hour: Int): Double {
        val slot  = hours[hour.coerceIn(0, 23)]
        val blend = slot.confidence.coerceIn(0.0, 1.0)
        // Floor at 0.5: new install starts at 50% of blendMult (not zero),
        // so PDP has immediate effect while learner builds confidence.
        val scaledBlend = 0.5 + 0.5 * blend
        val mult  = 1.0 + scaledBlend * (slot.blendMult - 1.0)
        return mult.coerceIn(MIN_BLEND_MULT, MAX_BLEND_MULT)
    }

    /** @deprecated Use effectiveBlendMult() — keeping for binary compatibility only */
    @Deprecated("Double-applies confidence. Use effectiveBlendMult() instead.")
    fun blendWeightConfidenceScale(hour: Int): Double = effectiveBlendMult(hour)

    fun strengthMultAt(hour: Int): Double = hours[hour.coerceIn(0, 23)].strengthMult
    fun fadeMultAt(hour: Int): Double     = hours[hour.coerceIn(0, 23)].fadeMult
    fun confidenceAt(hour: Int): Double   = hours[hour.coerceIn(0, 23)].confidence
    fun samplesAt(hour: Int): Int         = hours[hour.coerceIn(0, 23)].samples

    /** Public decay tick for hours that didn't sample this cycle. */
    fun tickAllDecay(exceptHour: Int) = tickDecayExcept(exceptHour)

    fun reset() {
        for (h in 0..23) updateSlot(h, HourSlot())
        save()
        aapsLogger.debug(LTag.APS, "PdpLearner: reset")
    }

    /**
     * Reset all slots and seed strengthMult to [seedStrengthMult].
     * Confidence is zeroed so the seed only influences dosing once
     * real episode data accumulates — safe to start conservatively.
     *
     * Example: seedStrengthMult=0.5 with baseCiStrength=5 means
     * effectiveCiStrength starts at 5 (confidence=0 → mult=1.0 always),
     * then as confidence builds it converges toward 5×0.5=2.5.
     * Episode outcome learning adjusts from there.
     */
    fun resetWithSeed(seedStrengthMult: Double) {
        val seed = seedStrengthMult.coerceIn(MIN_STRENGTH_MULT, MAX_STRENGTH_MULT)
        for (h in 0..23) updateSlot(h, HourSlot(strengthMult = seed))
        save()
        aapsLogger.debug(LTag.APS, "PdpLearner: reset with strengthMult seed=${"%.2f".format(seed)}")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun save() {
        try {
            val arr = JSONArray()
            for (h in 0..23) {
                arr.put(JSONObject().apply {
                    put("strengthMult", hours[h].strengthMult)
                    put("fadeMult",     hours[h].fadeMult)
                    put("blendMult",    hours[h].blendMult)
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
                updateSlot(h, HourSlot(
                    // Migrate from old format: "strength" → "strengthMult"
                    strengthMult = obj.optDouble("strengthMult", obj.optDouble("strength", 1.0)),
                    fadeMult     = obj.optDouble("fadeMult",     1.0),
                    blendMult    = obj.optDouble("blendMult",    1.0),
                    confidence   = obj.optDouble("confidence",   0.0),
                    samples      = obj.optInt("samples",         0)
                ))
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
                updateSlot(h, hours[h].copy(
                    confidence = (hours[h].confidence - CONFIDENCE_DECAY).coerceAtLeast(0.0)
                ))
            }
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    fun summaryTable(currentHour: Int, baseCiStrength: Double, baseFadeMins: Double): String = buildString {
        appendLine("── PDP Learner 24h ──────────────────────────────────────────")
        appendLine("  Hr  StrMlt  FadeMlt  BlendMlt  Conf   n")
        for (h in 0..23) {
            val marker  = if (h == currentHour) "▶" else " "
            val slot    = hours[h]
            appendLine(
                "$marker ${h.toString().padStart(2)}  " +
                    "${"%.3f".format(slot.strengthMult).padStart(6)}  " +
                    "${"%.3f".format(slot.fadeMult).padStart(7)}  " +
                    "${"%.3f".format(slot.blendMult).padStart(8)}  " +
                    "${"%.2f".format(slot.confidence).padStart(5)}  " +
                    "${slot.samples}"
            )
        }
    }.trimEnd()
}