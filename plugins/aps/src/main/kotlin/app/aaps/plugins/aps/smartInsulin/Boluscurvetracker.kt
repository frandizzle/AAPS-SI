package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches post-bolus CGM response and feeds observations to [ProfileLearner].
 * Stub until full bolus curve detection implementation.
 */
@Singleton
class BolusCurveTracker @Inject constructor(
    private val profileLearner: ProfileLearner
) {
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode: MealMode,
        iobArray: Array<IobTotal>
    ) {
        // Stub — full implementation will detect post-bolus BG peak
        // and call profileLearner.observeBolusCurve(mealMode, peakMins, diaMins, learningRate)
    }
}