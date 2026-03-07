package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.iob.IobTotal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks post-bolus CGM curves and feeds observations to [ProfileLearner].
 * Stub — full implementation comes in next step.
 */
@Singleton
class BolusCurveTracker @Inject constructor(
    private val profileLearner: ProfileLearner
) {
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode:      MealMode,
        iobArray:      Array<IobTotal>
    ) {
        // Stub — full curve tracking implementation to follow
    }
}