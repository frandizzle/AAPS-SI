package app.aaps.plugins.aps.smartInsulin.ice

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Confidence in the current ICE signal, decomposed into independent sub-scores.
 *
 * ## Why decomposed
 *
 * The UI / debug card needs to show *why* confidence is low so misbehavior can be
 * tuned. "ICE confidence = 0.2" alone is useless; "ICE confidence = 0.2 because
 * magnitude=0.8 but persistence=0.25 (only 1 cycle above floor)" lets you see
 * whether to raise the floor (too noisy) or extend the persistence window.
 *
 * ## Combination
 *
 * Final [score] is the product of the four components, gated by [disabled]:
 *
 *   score = if (disabled != null) 0.0
 *           else magnitude × persistence × consistency × cgmQuality
 *
 * Product (not sum) means any one component going to zero zeroes the whole thing —
 * which is the right safety property. Below-floor magnitude OR single-cycle blip OR
 * jittery signal OR warming-up sensor should all individually defeat confidence.
 *
 * ## Tuning knobs (passed in by caller from preferences)
 *
 * - [IceConfidenceParams.magnitudeFloorMgdlH] — ICE below this absolute value scores 0
 * - [IceConfidenceParams.magnitudeStrongMgdlH] — ICE at/above this scores 1.0
 * - [IceConfidenceParams.persistenceCyclesForFull] — N cycles of sustained signal = full persistence
 * - [IceConfidenceParams.consistencyWindowCycles] — how many recent cycles drive variance check
 */
data class IceConfidence(
    /** Composite confidence, 0.0 — 1.0. This is what the blender uses. */
    val score: Double,

    /** How strong the absolute ICE is relative to the floor/strong thresholds. */
    val magnitude: Double,

    /** How many recent cycles agree with the current sign and exceed the floor. */
    val persistence: Double,

    /** Inverse of normalised variance — low jitter = high consistency. */
    val consistency: Double,

    /** 1.0 normally, 0.0 during CGM warmup / quality issues. */
    val cgmQuality: Double,

    /** Non-null if ICE is being explicitly suppressed (score forced to 0). */
    val disabled: IceDisableReason? = null,

    /** Human-readable explanation suitable for the debug card. */
    val reasonText: String = ""
) {
    companion object {
        /** Zero confidence with a disable reason — for situations where ICE is gated off. */
        fun disabled(reason: IceDisableReason): IceConfidence = IceConfidence(
            score       = 0.0,
            magnitude   = 0.0,
            persistence = 0.0,
            consistency = 0.0,
            cgmQuality  = if (reason == IceDisableReason.CGM_WARMUP || reason == IceDisableReason.CGM_QUALITY) 0.0 else 1.0,
            disabled    = reason,
            reasonText  = reason.displayLabel
        )
    }
}

/**
 * User-tunable parameters for confidence scoring. All loaded from preferences by the caller.
 *
 * Defaults are conservative — designed so the system is reluctant to fire on light noise
 * but reliably engages on real meals. Tune from here based on logged behaviour.
 */
data class IceConfidenceParams(
    /** Absolute ICE (mg/dL/h) below which we contribute zero magnitude. Default ~5 mg/dL/h = 0.3 mmol/h. */
    val magnitudeFloorMgdlH: Double = 5.4,

    /** Absolute ICE (mg/dL/h) at which we contribute full magnitude. Default ~27 mg/dL/h = 1.5 mmol/h. */
    val magnitudeStrongMgdlH: Double = 27.0,

    /** Cycles of sustained agreement needed for full persistence credit. */
    val persistenceCyclesForFull: Int = 3,

    /** How many recent cycles to consider for variance/consistency. */
    val consistencyWindowCycles: Int = 5,

    /** Coefficient of variation at/above which consistency drops to zero. */
    val consistencyCvCeiling: Double = 1.0
)

/**
 * Compute confidence given a buffer of recent ICE samples.
 *
 * ## Algorithm
 *
 * 1. If [forcedDisable] is set, return [IceConfidence.disabled].
 * 2. Find the most recent sample with a non-null ICE value — call it `current`.
 *    If none exists, return INSUFFICIENT_HISTORY.
 * 3. **Magnitude**: linear interpolate |currentIce| between floor → 0.0 and strong → 1.0.
 * 4. **Persistence**: walk backwards from current, count cycles where:
 *      - ICE is non-null
 *      - |ICE| >= floor
 *      - sign(ICE) == sign(currentIce)
 *    Divide by [persistenceCyclesForFull], cap at 1.0.
 * 5. **Consistency**: take last [consistencyWindowCycles] non-null ICE values.
 *    If <2 samples, consistency = 0. Otherwise compute coefficient of variation
 *    (stddev / mean), invert against ceiling: 1.0 - (cv / ceiling), coerced to [0, 1].
 * 6. **CGM quality**: 1.0 unless the current sample had a CGM-quality disable.
 * 7. Compose: product of all four.
 */
fun computeIceConfidence(
    samples: List<IceSample>,
    params: IceConfidenceParams = IceConfidenceParams(),
    forcedDisable: IceDisableReason? = null
): IceConfidence {
    if (forcedDisable != null) {
        return IceConfidence.disabled(forcedDisable)
    }

    val withIce = samples.filter { it.iceMgdlPerHour != null }
    val current = withIce.lastOrNull()
        ?: return IceConfidence.disabled(IceDisableReason.INSUFFICIENT_HISTORY)

    val currentIce: Double = current.iceMgdlPerHour!!  // safe — filter above

    // ── Magnitude ─────────────────────────────────────────────────────────
    val absIce = abs(currentIce)
    val magnitude = when {
        absIce <= params.magnitudeFloorMgdlH   -> 0.0
        absIce >= params.magnitudeStrongMgdlH -> 1.0
        else -> {
            val range = params.magnitudeStrongMgdlH - params.magnitudeFloorMgdlH
            ((absIce - params.magnitudeFloorMgdlH) / range).coerceIn(0.0, 1.0)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────
    val currentSign = sign(currentIce)
    var streak = 0
    for (s in withIce.asReversed()) {
        val ice = s.iceMgdlPerHour ?: break
        if (abs(ice) < params.magnitudeFloorMgdlH) break
        if (sign(ice) != currentSign) break
        streak++
    }
    val persistence = (streak.toDouble() / params.persistenceCyclesForFull).coerceIn(0.0, 1.0)

    // ── Consistency ───────────────────────────────────────────────────────
    val recent = withIce.takeLast(params.consistencyWindowCycles)
        .mapNotNull { it.iceMgdlPerHour }
    val consistency: Double = if (recent.size < 2) {
        0.0
    } else {
        val mean = recent.average()
        if (abs(mean) < 1e-6) {
            // Mean ~zero; can't meaningfully compute CV. Treat as inconsistent.
            0.0
        } else {
            val variance = recent.sumOf { (it - mean) * (it - mean) } / recent.size
            val stddev = sqrt(variance)
            val cv = stddev / abs(mean)
            (1.0 - (cv / params.consistencyCvCeiling)).coerceIn(0.0, 1.0)
        }
    }

    // ── CGM quality ───────────────────────────────────────────────────────
    val cgmQuality = if (current.disabled == IceDisableReason.CGM_QUALITY
        || current.disabled == IceDisableReason.CGM_WARMUP) 0.0 else 1.0

    // ── Compose ────────────────────────────────────────────────────────────
    val score = magnitude * persistence * consistency * cgmQuality

    val reasonText = buildString {
        append("mag=${"%.2f".format(magnitude)} ")
        append("(|ICE|=${"%.1f".format(absIce)} mg/dL/h), ")
        append("persist=${"%.2f".format(persistence)} ($streak cycles), ")
        append("consist=${"%.2f".format(consistency)}, ")
        append("cgm=${"%.1f".format(cgmQuality)}")
    }

    return IceConfidence(
        score       = score,
        magnitude   = magnitude,
        persistence = persistence,
        consistency = consistency,
        cgmQuality  = cgmQuality,
        disabled    = null,
        reasonText  = reasonText
    )
}