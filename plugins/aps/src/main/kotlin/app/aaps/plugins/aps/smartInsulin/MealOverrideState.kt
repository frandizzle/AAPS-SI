package app.aaps.plugins.aps.smartInsulin

/**
 * Immutable snapshot of a pending meal override.
 *
 * Created when the user triggers [ActionSmartMeal]. Held by [MealOverrideManager].
 *
 * @param mode           Meal type selected by user
 * @param lockedDoseU    Insulin dose calculated at trigger time (U). Null = mode-only, no bolus.
 * @param lockedCarbsG   Carb amount used to calculate [lockedDoseU]
 * @param triggerTimeMs  Wall clock ms when user triggered the action
 * @param expiryMs       Wall clock ms after which the pending bolus is cancelled (trigger + 30 min)
 * @param bolusFired     True once the bolus has been successfully enqueued
 * @param modeExpiryMs   Wall clock ms after which the mode override itself expires (trigger + modeWindowMs)
 */
data class MealOverrideState(
    val mode:          MealMode,
    val lockedDoseU:   Double?,
    val lockedCarbsG:  Int,
    val triggerTimeMs: Long,
    val expiryMs:      Long,
    val bolusFired:    Boolean  = false,
    val modeExpiryMs:  Long
) {
    val hasPendingBolus: Boolean get() = lockedDoseU != null && !bolusFired

    companion object {
        /** 30 minutes bolus delivery window */
        const val BOLUS_WINDOW_MS = 30 * 60 * 1000L
    }
}
