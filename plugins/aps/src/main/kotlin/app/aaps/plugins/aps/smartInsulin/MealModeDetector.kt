package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.GlucoseStatus

/**
 * Stateless detector that classifies the current metabolic state as a [MealMode].
 *
 * Detection logic (evaluated in priority order):
 *
 * 1. EXTENDED — large COB + sustained positive delta suggests slow/fat-heavy absorption
 * 2. MEAL     — significant COB present
 * 3. LOW_CARB — small COB below user threshold, or recent carbs but nearly absorbed
 * 4. FASTING  — no meaningful COB
 *
 * All thresholds are passed in from preferences at call time — no SP access here.
 */
object MealModeDetector {

    // ── Internal thresholds ──────────────────────────────────────────────────

    /** COB above this (g) triggers MEAL mode */
    private const val MEAL_COB_THRESHOLD_G = 10.0

    /**
     * COB above this (g) combined with sustained rise triggers EXTENDED mode.
     * Intentionally higher than MEAL_COB_THRESHOLD_G — extended meals have more on board.
     */
    private const val EXTENDED_COB_THRESHOLD_G = 30.0

    /**
     * Delta threshold (mmol/L per 5 min) above which a rising BG with large COB
     * suggests extended/fat-slowed absorption rather than a normal meal peak.
     */
    private const val EXTENDED_DELTA_THRESHOLD_MMOL = 0.3

    /**
     * How long after the last carb entry (ms) we still consider carbs "recent"
     * for LOW_CARB classification even if COB has mostly absorbed.
     */
    private const val RECENT_CARB_WINDOW_MS = 60L * 60_000L   // 60 minutes

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Determine the current [MealMode].
     *
     * @param mealData         AAPS meal data (COB, last carb time, etc.)
     * @param glucoseStatus    Current glucose reading + delta values
     * @param lowCarbThreshold User-configured low-carb ceiling in grams (from IntKey.ApsSmartInsulinLowCarbThresholdG)
     * @param lowCarbEnabled   Whether low-carb mode is active (from BooleanKey.ApsSmartInsulinLowCarbMode)
     * @return                 The detected [MealMode]
     */
    fun detect(
        mealData:         MealData,
        glucoseStatus:    GlucoseStatus,
        lowCarbThreshold: Int     = 20,
        lowCarbEnabled:   Boolean = true
    ): MealMode {
        val cob          = mealData.carbs              // grams currently on board
        val lastCarbTime = mealData.lastCarbTime       // epoch ms of last carb entry, 0 if none
        val now          = System.currentTimeMillis()
        val delta        = glucoseStatus.delta         // mmol/L change over last 5 min

        // ── 1. EXTENDED — large COB with sustained positive BG rise ─────────
        // Indicates fat/protein slowing absorption; normal insulin activity model breaks down
        if (cob >= EXTENDED_COB_THRESHOLD_G && delta >= EXTENDED_DELTA_THRESHOLD_MMOL) {
            return MealMode.EXTENDED
        }

        // ── 2. MEAL — significant active carbs on board ──────────────────────
        if (cob >= MEAL_COB_THRESHOLD_G) {
            return MealMode.MEAL
        }

        // ── 3. LOW_CARB — small COB, or carbs eaten recently but mostly absorbed
        // Only applied when user has enabled low-carb mode
        if (lowCarbEnabled) {
            val hasSmallCob    = cob > 0.0 && cob < lowCarbThreshold.toDouble()
            val recentLowCarbs = lastCarbTime > 0 &&
                (now - lastCarbTime) < RECENT_CARB_WINDOW_MS &&
                cob < lowCarbThreshold.toDouble()

            if (hasSmallCob || recentLowCarbs) {
                return MealMode.LOW_CARB
            }
        }

        // ── 4. FASTING — no meaningful carb activity ─────────────────────────
        return MealMode.FASTING
    }

    /**
     * Convenience overload for unit tests and callers that already have scalar values.
     */
    fun detect(
        cobGrams:         Double,
        lastCarbTimeMs:   Long,
        deltaMMol:        Double,
        lowCarbThreshold: Int     = 20,
        lowCarbEnabled:   Boolean = true
    ): MealMode {
        val now = System.currentTimeMillis()
        val delta = deltaMMol

        if (cobGrams >= EXTENDED_COB_THRESHOLD_G && delta >= EXTENDED_DELTA_THRESHOLD_MMOL)
            return MealMode.EXTENDED

        if (cobGrams >= MEAL_COB_THRESHOLD_G)
            return MealMode.MEAL

        if (lowCarbEnabled) {
            val hasSmallCob    = cobGrams > 0.0 && cobGrams < lowCarbThreshold.toDouble()
            val recentLowCarbs = lastCarbTimeMs > 0 &&
                (now - lastCarbTimeMs) < RECENT_CARB_WINDOW_MS &&
                cobGrams < lowCarbThreshold.toDouble()

            if (hasSmallCob || recentLowCarbs) return MealMode.LOW_CARB
        }

        return MealMode.FASTING
    }
}
