package app.aaps.core.interfaces.smartInsulin

/**
 * Immutable snapshot of a pending meal override.
 * Lives in core/interfaces so both plugins/aps and plugins/automation can access it.
 */
data class MealOverrideState(
    val mode:          MealMode,
    val lockedDoseU:   Double?,
    val lockedCarbsG:  Int,
    val triggerTimeMs: Long,
    val expiryMs:      Long,
    val bolusFired:    Boolean = false,
    val modeExpiryMs:  Long
) {
    val hasPendingBolus: Boolean get() = lockedDoseU != null && !bolusFired

    companion object {
        const val BOLUS_WINDOW_MS = 30 * 60 * 1000L
    }
}