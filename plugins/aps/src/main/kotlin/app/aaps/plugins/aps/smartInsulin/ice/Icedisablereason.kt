package app.aaps.plugins.aps.smartInsulin.ice

/**
 * Why ICE tracking is currently disabled / scored at zero confidence.
 *
 * ICE relies on the insulin model being accurate (ICE = observed BG change minus
 * modeled insulin BG change). These disable conditions are situations where either
 * the insulin model is unreliable (warmup, sensor issues) or where the observed
 * signal is contaminated by non-meal influences (exercise sensitivity changes),
 * so trusting ICE-driven dosing would be unsafe.
 *
 * When disabled, IceTracker still records cycles into its buffer so that history
 * is intact when the disable condition clears. But IceConfidence.score is forced
 * to 0.0 and the disabled reason is carried forward to the debug snapshot so the
 * UI / logs make clear *why* nothing is happening.
 */
enum class IceDisableReason(val displayLabel: String) {
    /** User has the ICE feature toggled off in preferences. */
    PREFERENCE_DISABLED("ICE disabled in settings"),

    /** Sensor is within its first 24h — readings unreliable, insulin model untrustworthy. */
    CGM_WARMUP("CGM in warmup (<24h)"),

    /** Current CGM reading is suspect (compression low, dropout, calibration error). */
    CGM_QUALITY("CGM quality flag set"),

    /** A high temp target is active — exercise mode signal, ISF/dosing shouldn't be inferred from BG drop. */
    EXERCISE_TEMP_TARGET("Exercise temp target active"),

    /** Activity monitor (HR/steps) reports user is active — sensitivity shifted from baseline. */
    ACTIVITY_DETECTED("Activity detected (HR/steps)"),

    /** Not enough cycles in history to compute meaningful ICE yet. */
    INSUFFICIENT_HISTORY("Building history…")
}