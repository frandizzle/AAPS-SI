package app.aaps.core.interfaces.smartInsulin

/**
 * Immutable snapshot of an active meal mode override.
 * Stripped down — pre-bolus firing was removed (handled by SmartMealDialog directly).
 */
data class MealOverrideState(
    val mode:          MealMode,
    val triggerTimeMs: Long,
    val modeExpiryMs:  Long
)