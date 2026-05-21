package app.aaps.core.interfaces.smartInsulin

/**
 * Exposes SmartInsulin state to the Overview screen.
 *
 * Lives in core/interfaces so plugins/main (OverviewFragment) can inject it
 * without a direct compile dependency on plugins/aps.
 * Implementation: SmartInsulinPlugin in plugins/aps.
 */
interface SmartInsulinOverview {

    /**
     * Snapshot of all state needed to render the Overview info cell.
     * Computed once per invoke() cycle and cached — safe to read from UI thread.
     */
    data class OverviewState(
        /** "Meal: Lunch 177m" or "Meal: Fasting" */
        val modeLine: String,
        /** "PB2 active: 30m" or null if no PB2 pending */
        val pb2Line: String?,
        /** "PB3 active: 25m" or null if no PB3 pending. PB3 will show "waiting for PB2"
         *  until PB2 fires, then count down its own delay relative to PB2's fire time. */
        val pb3Line: String?,
        /** null = full learning active
         *  "limited" = meal mode (only DIA/peak learning)
         *  "off: <reason>" = fully suppressed */
        val learningState: String,
        /** True when in FASTING mode (no meal overrides active) */
        val isFasting: Boolean = true,
        /** True when the algorithm is actively learning (Fasting + no other blocks) */
        val isLearning: Boolean = true
    )

    fun overviewState(): OverviewState

    val overviewStateFlow: kotlinx.coroutines.flow.StateFlow<OverviewState?>

    /**
     * Announce a meal to the loop. Replaces any active announced meal.
     *
     * The loop uses the macros and GI bucket to build an expected ICE absorption curve,
     * which pre-positions dosing before observed ICE catches up to reality. Lives on this
     * interface (rather than a separate one) so the SmartMealDialog ViewModel — which only
     * sees [SmartInsulinOverview] via `activePlugin.smartInsulin` — can call it without a
     * compile-time dependency on the plugins/aps module.
     *
     * @param carbsG total carbohydrates in grams (≥ 0).
     * @param proteinG total protein in grams (≥ 0). Contributes a late hump (~3h) because
     *                 a fraction of protein converts to glucose.
     * @param fatG total fat in grams (≥ 0). Currently used only for the user's record —
     *             its delaying effect on absorption is captured by GI bucket selection.
     * @param giBucketName one of "FAST" / "MEDIUM" / "SLOW" (case-insensitive). FAST = high
     *                     GI (juice, candy, fast carbs). MEDIUM = standard (bread, rice,
     *                     pasta — default). SLOW = low GI (pizza, fatty / large meals,
     *                     bimodal absorption). String rather than enum to avoid a
     *                     core/interfaces dependency on the plugin module.
     * @param commitmentPct user's confidence in these numbers, 0–100. Scales the expected
     *                      curve linearly. 100 = weighed meal / certain; lower values
     *                      reduce the loop's reliance on the prediction.
     */
    fun announceMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String,
        commitmentPct: Int
    )

    /** Clear any active announced meal. The loop reverts to observed-only ICE behaviour. */
    fun clearAnnouncedMeal()
}