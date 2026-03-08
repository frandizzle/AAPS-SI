package app.aaps.core.interfaces.smartInsulin

/**
 * Exposes learning controls to the automation module without
 * creating a dependency on plugins/aps internals.
 */
interface SmartInsulinLearner {
    /** Reset all learned profiles back to current profile/insulin defaults. */
    fun resetProfiles()
}