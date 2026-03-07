package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.MealData

/**
 * Detects the current [MealMode] from loop state.
 *
 * Priority order:
 *   1. Active override from [MealOverrideManager] (user-selected via ActionSmartMeal)
 *   2. EXTENDED — high COB + slow/flat delta (high-fat meal absorption pattern)
 *   3. MEAL     — any meaningful COB
 *   4. LOW_CARB — small COB or mild BG rise with no announced carbs
 *   5. FASTING  — default
 *
 * The detector is stateless; all decisions are made from the inputs on each call.
 */
object MealModeDetector {

    // ── Thresholds ────────────────────────────────────────────────────────────

    /** COB above this → consider it a meal context (g) */
    private const val MEAL_COB_THRESHOLD_G         = 15.0

    /** COB above this + delta slow → EXTENDED mode (high-fat pattern) (g) */
    private const val EXTENDED_COB_THRESHOLD_G     = 30.0

    /** Delta below this while COB is high → suspect extended absorption (mg/dL per 5 min) */
    private const val EXTENDED_SLOW_DELTA_MGDL     = 3.0

    /** Small-but-nonzero COB or mild rise → LOW_CARB (g) */
    private const val LOW_CARB_COB_THRESHOLD_G     = 5.0

    /** Mild BG rise with no COB → LOW_CARB (mg/dL per 5 min) */
    private const val LOW_CARB_DELTA_MGDL          = 2.0

    // ── Primary entry point ───────────────────────────────────────────────────

    /**
     * Detect mode from full loop data.
     *
     * @param mealData       AAPS MealData (COB, mealCOB, etc.)
     * @param glucoseStatus  Current BG and delta
     * @param overrideManager Optional — if an active override exists, it wins
     */
    fun detect(
        mealData:        MealData,
        glucoseStatus:   GlucoseStatus,
        overrideManager: MealOverrideManager? = null
    ): MealMode {
        // 1. User override wins
        overrideManager?.activeMealMode?.let { return it }

        val cob   = mealData.mealCOB
        val delta = glucoseStatus.shortAvgDelta

        return detect(cob = cob, shortAvgDelta = delta)
    }

    /**
     * Scalar overload — used by unit tests and simple call sites.
     */
    fun detect(cob: Double, shortAvgDelta: Double): MealMode = when {
        cob >= EXTENDED_COB_THRESHOLD_G && shortAvgDelta < EXTENDED_SLOW_DELTA_MGDL -> MealMode.EXTENDED
        cob >= MEAL_COB_THRESHOLD_G                                                  -> MealMode.LUNCH  // generic meal → default to LUNCH; override manager sets specific type
        cob >= LOW_CARB_COB_THRESHOLD_G || shortAvgDelta >= LOW_CARB_DELTA_MGDL     -> MealMode.LOW_CARB
        else                                                                          -> MealMode.FASTING
    }
}