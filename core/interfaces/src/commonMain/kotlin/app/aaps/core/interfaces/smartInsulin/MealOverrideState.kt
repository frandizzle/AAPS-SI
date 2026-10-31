package app.aaps.core.interfaces.smartInsulin

/**
 * Immutable snapshot of an active meal mode override.
 *
 * [preBolus2U]       — pre-bolus 2 dose in units. 0.0 = not requested.
 * [preBolus2DelayMs] — ms after triggerTimeMs to fire the second bolus.
 * [preBolus2FiredMs] — wall-clock time it was actually delivered, or null if still pending.
 * [preBolus3U]       — pre-bolus 3 dose in units.
 * [preBolus3DelayMs] — ms AFTER PB2 FIRES to fire the third bolus.
 * [pfAfter]          — start Protein/Fat (UAM) when this mode runs out, for a meal with a slow fat and protein tail.
 * [addedU]           — insulin given now for food added to this meal later (dessert, a second plate).
 */
data class MealOverrideState(
    val mode:             MealMode,
    val triggerTimeMs:    Long,
    val modeExpiryMs:     Long,
    val doseU:            Double? = null,  // pre-bolus 1 dose — null if not delivered via SmartMeal
    val preBolus2U:       Double = 0.0,
    val preBolus2DelayMs: Long   = 0L,
    val preBolus2FiredMs: Long?  = null,  // null = not yet fired
    val preBolus3U:       Double = 0.0,
    val preBolus3DelayMs: Long   = 0L,
    val preBolus3FiredMs: Long?  = null,
    val duraEnabled:      Boolean = false,  // DURA_ISF: strengthen ISF while BG sits stuck above target
    val duraFloorMgdl:    Double  = 0.0,    // lowest (strongest) ISF DURA may push toward; 0.0 = no-op
    val duraStrength:     Double  = 1.0,    // how fast DURA ramps while stuck (mirrors DuraIsfTracker.DEFAULT_WEIGHT)
    val pfAfter:          Boolean = false,
    val addedU:           Double  = 0.0
) {
    /** True if a second bolus was requested and hasn't fired yet */
    val preBolus2Pending: Boolean
        get() = preBolus2U > 0.0 && preBolus2FiredMs == null

    /** Wall-clock time PB2 should fire */
    val preBolus2FireAtMs: Long
        get() = triggerTimeMs + preBolus2DelayMs

    /** True if PB2 fired successfully (positive timestamp) */
    val preBolus2FiredSuccessfully: Boolean
        get() = preBolus2FiredMs != null && preBolus2FiredMs > 0L

    /** True if a third bolus was requested and hasn't fired yet */
    val preBolus3Pending: Boolean
        get() = preBolus3U > 0.0 && preBolus3FiredMs == null

    /** All insulin this meal gives as fixed doses: what was given plus what is still planned. Cancelled pre-boluses do not count. */
    val plannedTotalU: Double
        get() = (doseU ?: 0.0) + addedU +
            (if (preBolus2FiredMs != -1L) preBolus2U else 0.0) +
            (if (preBolus3FiredMs != -1L) preBolus3U else 0.0)

    /** Fire time for PB3: preBolus3DelayMs relative to PB2 fire time.
     *  Returns null if PB2 hasn't fired yet (can't compute PB3 trigger). */
    val preBolus3FireAtMs: Long?
        get() = if (preBolus2FiredSuccessfully) preBolus2FiredMs!! + preBolus3DelayMs else null
}
