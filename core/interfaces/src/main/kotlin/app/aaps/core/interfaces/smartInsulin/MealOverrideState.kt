package app.aaps.core.interfaces.smartInsulin

/**
 * Immutable snapshot of an active meal mode override.
 *
 * [preBolus2U]       — pre-bolus 2 dose in units. 0.0 = not requested.
 * [preBolus2DelayMs] — ms after triggerTimeMs to fire the second bolus.
 * [preBolus2FiredMs] — wall-clock time it was actually delivered, or null if still pending.
 */
data class MealOverrideState(
    val mode:             MealMode,
    val triggerTimeMs:    Long,
    val modeExpiryMs:     Long,
    val doseU:            Double? = null,  // pre-bolus 1 dose — null if not delivered via SmartMeal
    val preBolus2U:       Double = 0.0,
    val preBolus2DelayMs: Long   = 0L,
    val preBolus2FiredMs: Long?  = null   // null = not yet fired
) {
    /** True if a second bolus was requested and hasn't fired yet */
    val preBolus2Pending: Boolean
        get() = preBolus2U > 0.0 && preBolus2FiredMs == null

    /** Wall-clock time PB2 should fire */
    val preBolus2FireAtMs: Long
        get() = triggerTimeMs + preBolus2DelayMs
}