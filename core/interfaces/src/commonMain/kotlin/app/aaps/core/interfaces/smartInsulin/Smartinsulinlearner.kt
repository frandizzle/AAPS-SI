package app.aaps.core.interfaces.smartInsulin

/**
 * Exposes learning controls for the SmartInsulin tab UI.
 */
interface SmartInsulinLearner {
    /** Reset all learned profiles back to defaults. */
    fun resetProfiles()
}