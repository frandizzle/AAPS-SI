package app.aaps.plugins.aps.smartInsulin.ice

/**
 * Tuning constants for the UAM (unannounced-meal) mode of the ICE system.
 *
 * ## Why these aren't prefs yet
 * The AAPS `DoubleKey` / `BooleanKey` enums live in `core/keys`, outside the
 * `plugins/aps` module that owns SmartInsulin. Adding user-facing preferences
 * for these values requires edits to that other module — out of scope for
 * ice-step28 which lives entirely in `plugins/aps`. The defaults below are
 * chosen so behaviour is sensible out of the box; promoting any of them to a
 * preference is a small follow-up:
 *
 * 1. Add `ApsSmartInsulinUamIceUserWeight` (etc) to `core/keys/DoubleKey.kt`
 *    with the same default as the constant here.
 * 2. Add the preference to the SmartInsulin XML so it shows in settings.
 * 3. Replace each `UamIceConfig.X` reference in `SmartInsulinPlugin` with
 *    `preferences.get(DoubleKey.ApsSmartInsulinUamIceX)`.
 *
 * Same hardcoded-constants pattern is already used for [GiBucket] timings.
 *
 * ## How these compare to COB (announced) mode
 * | Setting             | COB / announced mode               | UAM / unannounced (this file)         |
 * | ------------------- | ---------------------------------- | ------------------------------------- |
 * | userWeight          | `ApsSmartInsulinIceUserWeight`     | [USER_WEIGHT]                         |
 * | learningThreshold   | `ApsSmartInsulinIceLearningBlock…` | [LEARNING_THRESHOLD]                  |
 * | decay timeline      | n/a — meal curve has explicit dur. | [DECAY_MINUTES]                       |
 * | aggression cap      | no explicit cap                    | [AGGRESSION_CAP] applied post-compute |
 * | announcedMealFloor  | yes — `0.5 × commitmentFraction`   | n/a (no announcement to floor)        |
 */
object UamIceConfig {

    /**
     * Trust slider for UAM-mode dosing — multiplied by IceTracker confidence
     * to produce `iceBlendWeight`. Lower than typical COB-mode weight because
     * an unannounced rise has higher false-positive risk: it could be a
     * genuine missed meal, dawn phenomenon, stress hyperglycemia, or a
     * rebound from a low. Misclassifying any of those as a real meal and
     * full-blend-chasing the rise costs hypos.
     *
     * Range 0.0–1.0, recommended 0.0–0.5. Default 0.3 — modest blend; the
     * loop responds meaningfully without committing to a full meal-equivalent
     * chase.
     */
    const val USER_WEIGHT: Double = 0.3

    /**
     * Confidence floor for UAM to count as "driving" the loop. Below this
     * value of `IceTracker.confidenceScore`, the system observes the rise
     * but doesn't change dosing behaviour — needs several consistent cycles
     * of persistent positive momentum before acting. Higher than the COB
     * threshold because the user hasn't told us a meal is happening, so we
     * want stronger signal before committing dose.
     *
     * Range 0.0–1.0, recommended 0.4–0.8. Default 0.6 — roughly "3+
     * consistent cycles with non-trivial magnitude" given typical IceTracker
     * scoring.
     */
    const val LEARNING_THRESHOLD: Double = 0.6

    /**
     * Linear decay duration for the UAM forward-projection curve, in minutes.
     * In UAM mode, the IceTracker's current observed rate is projected
     * forward and faded linearly to zero over this many minutes — represents
     * "this momentum will keep going for a while, then taper unless something
     * keeps driving it."
     *
     * Used in `iceFutureMgdlPerH` construction at the no-meal-but-observed
     * branch. Replaces what was previously a hardcoded 60-min ramp
     * (`tick / 12.0`).
     *
     * Range 30–120 min, recommended 45–90 min. Default 60 min — preserves
     * pre-step28 behaviour exactly when this constant is at its default.
     */
    const val DECAY_MINUTES: Int = 60

    /**
     * Hard upper bound on `iceAggrAdjust` when in UAM mode. The aggression
     * adjust is a multiplier on `baseAggressiveness` used in dose scaling;
     * in COB mode it can climb to whatever `computeIceAggressionAdjust`
     * returns (then clamped by the global 0.3–2.0 coerce). In UAM mode we
     * cap tighter because over-dosing on a false-positive UAM is costly:
     * a 30g phantom meal worth of insulin delivered into a dawn-phenomenon
     * rise drives a hypo a couple hours later.
     *
     * Range 1.0–2.0, recommended 1.2–1.5. Default 1.3 — allows up to +30%
     * boost on baseline aggression, no more.
     */
    const val AGGRESSION_CAP: Double = 1.3
}