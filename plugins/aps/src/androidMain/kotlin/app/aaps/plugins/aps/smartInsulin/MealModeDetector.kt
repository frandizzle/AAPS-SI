package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager

/**
 * Resolves the current [MealMode].
 *
 * Auto-detection has been removed — mode is always FASTING unless the user
 * has explicitly set one via ActionSmartMeal (stored in [MealOverrideManager]).
 */
object MealModeDetector {

    /**
     * Returns the active user-set meal mode, or FASTING if none is active.
     */
    fun detect(overrideManager: MealOverrideManager? = null): MealMode =
        overrideManager?.activeMealMode ?: MealMode.FASTING
}