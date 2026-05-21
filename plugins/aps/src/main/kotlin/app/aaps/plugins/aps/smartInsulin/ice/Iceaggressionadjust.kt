package app.aaps.plugins.aps.smartInsulin.ice

/**
 * Computes the aggression-multiplier applied to determine_basal's `aggressiveness`
 * input based on the current ICE state.
 *
 * ## What this does
 *
 * Maps observed ICE (the "what's pushing BG around that insulin doesn't explain"
 * signal) into a scalar that modulates how hard the loop dosing pushes:
 *
 *   - ICE > 0 (food/stress/dawn pushing BG up)  → multiplier > 1.0, loop pushes harder
 *   - ICE < 0 (exercise/late-insulin pulling BG down) → multiplier < 1.0, loop backs off
 *   - ICE disabled or low confidence → multiplier = 1.0 (no influence)
 *
 * ## Asymmetric safety
 *
 * Negative ICE (BG falling unexpectedly) gets [negativeBoost]× the weight of
 * positive ICE. Rationale: the cost of an over-dose-induced hypo is much higher
 * than the cost of a slight high. When BG is unexpectedly dropping, we want the
 * loop to back off faster than it would push when BG is unexpectedly rising.
 * Default 1.5× means a unit of negative ICE removes 1.5× as much aggressiveness
 * as the same unit of positive ICE adds.
 *
 * ## Confidence gating
 *
 * The user-set weight is multiplied by [confidence] before being applied, so a
 * noisy / sparse / single-cycle ICE signal has minimal effect even when the user
 * has the weight set to 1.0. By the time confidence reaches its full 1.0, the
 * user's weight setting is fully respected.
 *
 * ## Bounds
 *
 * The returned multiplier is hard-clamped to [0.3, 2.0]. Combined with the
 * upstream aggressiveness (typically 0.5–1.5 from the learners), this means
 * the worst-case effective aggressiveness is in [0.15, 3.0] — well within
 * determine_basal's safety guards (max_basal, max_iob, max_smb caps still apply).
 *
 * ## Disabled path
 *
 * When ICE is disabled (CGM warmup, exercise, activity detected, kill switch),
 * this function returns exactly 1.0 — no influence. Combined with the IceTracker
 * forcing confidence to zero in those states, this is belt-and-braces.
 */
fun computeIceAggressionAdjust(
    /** Current ICE in mg/dL/h, or null if no signal yet. */
    iceMgdlPerH: Double?,
    /** Confidence 0.0–1.0 from IceConfidence.score. */
    confidence: Double,
    /** True if any disable reason is set (forces neutral return). */
    disabled: Boolean,
    /** User's preference 0.0–1.0 — 0 = no ICE influence, 1 = full ICE drives aggression. */
    userWeight: Double,
    /** ICE magnitude (mg/dL/h) at which effect saturates. Default 27 ≈ 1.5 mmol/h. */
    saturationMgdlH: Double = 27.0,
    /** Asymmetric safety factor — how much extra weight to apply on the falling side. */
    negativeBoost: Double = 1.5
): Double {
    // Hard returns for any reason ICE shouldn't influence dosing.
    if (disabled) return 1.0
    if (iceMgdlPerH == null) return 1.0
    if (!iceMgdlPerH.isFinite()) return 1.0   // defensive — NaN/Inf must never escape
    if (userWeight <= 0.0) return 1.0
    if (confidence <= 0.0) return 1.0
    if (saturationMgdlH <= 0.0) return 1.0     // defensive — bad config

    // How much of the user's weight actually applies, given current confidence.
    val effectiveWeight = (confidence * userWeight).coerceIn(0.0, 1.0)

    // Normalised ICE: 0.0 at no signal, ±1.0 at saturation, clamped beyond.
    val normalised = (iceMgdlPerH / saturationMgdlH).coerceIn(-1.0, 1.0)

    val adjust = if (normalised >= 0.0) {
        // Positive ICE → push harder. At full effectiveWeight + saturated ICE, +1.0 = 2x.
        1.0 + normalised * effectiveWeight
    } else {
        // Negative ICE → back off. Boosted by negativeBoost for asymmetric safety.
        1.0 + normalised * effectiveWeight * negativeBoost
    }

    return adjust.coerceIn(0.3, 2.0)
}