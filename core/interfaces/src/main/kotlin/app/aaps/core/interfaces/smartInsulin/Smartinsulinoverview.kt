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
        /** null = full learning active
         *  "limited" = meal mode (only DIA/peak learning)
         *  "off: <reason>" = fully suppressed */
        val learningState: String
    )

    fun overviewState(): OverviewState

    val overviewStateFlow: kotlinx.coroutines.flow.StateFlow<OverviewState?>
}