package app.aaps.core.interfaces.smartInsulin

/**
 * Immutable snapshot of an active meal mode override.
 *
 * [preBolus2U]       — pre-bolus 2 dose in units. 0.0 = not requested.
 * [preBolus2DelayMs] — ms after triggerTimeMs to fire the second bolus.
 * [preBolus2FiredMs] — wall-clock time it was actually delivered, -1L if cancelled/failed, or null if still pending.
 * [preBolus3U]       — pre-bolus 3 dose in units. 0.0 = not requested.
 * [preBolus3DelayMs] — ms AFTER PB2 FIRES to fire the third bolus. PB3 cannot begin counting
 *                     down until PB2 has actually delivered (preBolus2FiredMs > 0L).
 * [preBolus3FiredMs] — wall-clock time PB3 was delivered, -1L if cancelled/failed, or null if still pending.
 */
data class MealOverrideState(
    val mode:             MealMode,
    val triggerTimeMs:    Long,
    val modeExpiryMs:     Long,
    val doseU:            Double? = null,  // pre-bolus 1 dose — null if not delivered via SmartMeal
    val preBolus2U:       Double = 0.0,
    val preBolus2DelayMs: Long   = 0L,
    val preBolus2FiredMs: Long?  = null,  // null = not yet fired, -1L = cancelled/failed, >0L = fired
    val preBolus3U:       Double = 0.0,
    val preBolus3DelayMs: Long   = 0L,    // relative to preBolus2FiredMs, NOT triggerTimeMs
    val preBolus3FiredMs: Long?  = null   // null = not yet fired, -1L = cancelled/failed, >0L = fired
) {
    /** True if a second bolus was requested and hasn't fired yet */
    val preBolus2Pending: Boolean
        get() = preBolus2U > 0.0 && preBolus2FiredMs == null

    /** Wall-clock time PB2 should fire */
    val preBolus2FireAtMs: Long
        get() = triggerTimeMs + preBolus2DelayMs

    /** True if a third bolus was requested and hasn't fired yet */
    val preBolus3Pending: Boolean
        get() = preBolus3U > 0.0 && preBolus3FiredMs == null

    /**
     * Wall-clock time PB3 should fire, or null if PB2 hasn't successfully fired yet.
     * PB3 delay is measured from PB2's actual delivery time, not from meal start.
     */
    val preBolus3FireAtMs: Long?
        get() = preBolus2FiredMs?.takeIf { it > 0L }?.plus(preBolus3DelayMs)

    /** True if PB2 has fired successfully (fired time > 0) — required before PB3 can begin counting */
    val preBolus2FiredSuccessfully: Boolean
        get() = preBolus2FiredMs != null && preBolus2FiredMs > 0L
}