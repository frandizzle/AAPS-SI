package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.profile.Profile
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core basal determination worker for SmartInsulin.
 * Stub — full implementation comes in next step.
 */
@Singleton
class DetermineBasalSmartInsulin @Inject constructor() {

    fun determine_basal(
        glucoseStatus:         GlucoseStatus,
        iobArray:              Array<IobTotal>,
        mealData:              MealData,
        profile:               Profile,
        learnedProfile:        LearnedInsulinProfile,
        mealMode:              MealMode,
        predictionHorizonMins: Int,
        lowGuardMmol:          Double,
        warnGuardMmol:         Double,
        currentTime:           Long
    ): JSONObject {
        // Stub — returns empty result until full implementation
        return JSONObject()
    }
}