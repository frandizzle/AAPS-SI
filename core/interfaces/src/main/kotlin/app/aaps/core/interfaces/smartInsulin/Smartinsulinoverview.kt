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
}