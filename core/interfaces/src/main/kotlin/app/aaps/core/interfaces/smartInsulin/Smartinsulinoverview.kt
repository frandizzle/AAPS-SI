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
     * Current meal mode display string for the Overview COB cell.
     * Format: "Mode: Dinner 47m" while an override is active,
     *         "Mode: Fasting" when no override is running.
     */
    fun overviewModeText(): String

    /**
     * Returns null if adaptive learning is currently active.
     * Returns a short human-readable reason string if suppressed, e.g.:
     *   "activity (Moderate)", "CGM warmup", "learning off"
     *
     * Overview uses this to show "State: Learning" or "State: Not Learning".
     */
    fun learningSuppressionReason(): String?
}