package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.smartInsulin.MealMode
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SmartInsulin APS — core basal/SMB determination.
 *
 * refactored for split PK/PD model:
 * 1. LearnedInsulinKinetics: Represents the global drug action (Peak/DIA).
 * 2. LearnedCarbAbsorption: Represents the per-mode food absorption (Duration).
 */
@Singleton
class DetermineBasalSmartInsulin @Inject constructor(
    private val apsResultProvider: Provider<APSResult>
) {

    private fun fmt(mgdl: Double, isMmol: Boolean): String =
        if (isMmol) String.format(Locale.US, "%.1f", mgdl / MMOL_TO_MGDL)
        else        String.format(Locale.US, "%.0f", mgdl)

    private fun setTempBasal(rate: Double, duration: Int, profile: OapsProfile, rT: RT, currentTemp: CurrentTemp) {
        val maxSafe = min(profile.max_basal,
                          min(profile.max_daily_safety_multiplier * profile.max_daily_basal,
                              profile.current_basal_safety_multiplier * profile.current_basal))
        val r = rate.coerceIn(0.0, maxSafe)
        if (profile.skip_neutral_temps && abs(r - profile.current_basal) < NEUTRAL_TEMP_EPSILON) {
            if (currentTemp.duration > 0) { rT.duration = 0; rT.rate = 0.0 }
            return
        }
        rT.duration = duration
        rT.rate = r
    }

    fun determine_basal(
        glucoseStatus:            GlucoseStatus,
        currentTemp:              CurrentTemp,
        iobArray:                 Array<IobTotal>,
        oapsProfile:              OapsProfile,
        mealData:                 MealData,
        profile:                  Profile,
        kinetics:                 LearnedInsulinKinetics,
        carbAbsorption:           LearnedCarbAbsorption,
        mealMode:                 MealMode,
        lowGuardMmol:             Double,
        warnGuardMmol:            Double,
        maxSmbU:                  Double,
        maxTbrU:                  Double,
        aggressiveness:           Double,
        tirSummary:               String,
        basalMultiplier:          Double,
        dosingIsfMgdl:            Double,
        microBolusAllowed:        Boolean,
        inReboundWindow:          Boolean,
        msSinceLastSuspend:       Long,
        currentTime:              Long,
        isTempTarget:             Boolean,
        profileTargetMgdl:        Double,
        dawnWindowStartHour:      Int,
        dawnWindowEndHour:        Int,
        dawnSmbReduction:         Double,
        bgWentLow:                Boolean,
        activityLevel:            ActivityMonitor.ActivityLevel,
        activityTargetOffsetMmol: Double,
        cgmSmbFraction:           Double,
        cgmDeltaPlausible:        Boolean,
        cgmWarmupReason:          String,
        uamSmbFraction:           Double = 1.0,
        targetRespectEnabled:     Boolean = false,
        reboundWindowMins:        Double = 60.0,
        isMmol:                   Boolean = true
    ): APSResult {

        val result = apsResultProvider.get()
        val rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp = currentTime,
            consoleLog = mutableListOf(),
            consoleError = mutableListOf()
        )

        val currentBg      = glucoseStatus.glucose
        val delta          = glucoseStatus.delta
        val shortAvgDelta  = glucoseStatus.shortAvgDelta
        val iob_data       = iobArray[0]
        val currentIob     = iob_data.iob

        val activityOffsetMgdl = activityTargetOffsetMmol * MMOL_TO_MGDL
        val targetBg = if (isTempTarget) oapsProfile.target_bg else oapsProfile.target_bg + activityOffsetMgdl
        val profileBasal = oapsProfile.current_basal * basalMultiplier

        val cal = java.util.Calendar.getInstance().also { it.timeInMillis = currentTime }
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val inDawnWindow = mealMode == MealMode.FASTING && glucoseStatus.delta > 0 && run {
            if (dawnWindowStartHour <= dawnWindowEndHour) currentHour in dawnWindowStartHour until dawnWindowEndHour
            else currentHour >= dawnWindowStartHour || currentHour < dawnWindowEndHour
        }
        val dawnFraction = if (inDawnWindow) dawnSmbReduction else 1.0
        val cgmFraction  = if (!cgmDeltaPlausible) 0.0 else cgmSmbFraction

        val reboundMins = if (inReboundWindow) (msSinceLastSuspend / 60_000.0) else 0.0
        val rawTaper = (reboundMins / reboundWindowMins).coerceIn(0.0, 1.0)
        val reboundTaperFraction = if (inReboundWindow) 0.3 + (0.7 * rawTaper) else 1.0

        val lowGuardMgdl  = lowGuardMmol * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        // ci = carb impact (delta above predicted BGI)
        val bgi = -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0)
        val ci  = min(glucoseStatus.shortAvgDelta, glucoseStatus.delta) - bgi

        // Prediction: Use global kinetics for insulin, and learned carb absorption for meals
        val predictedBg = predictBgCurve(
            startBg       = currentBg,
            ci            = ci,
            iobArray      = iobArray,
            isfMgdl       = dosingIsfMgdl,
            kinetics      = kinetics,
            carbAbs       = carbAbsorption,
            mealMode      = mealMode
        )

        val predictedMinSafety = predictedBg.minOrNull() ?: currentBg
        val predictedMin = predictedBg.minOrNull() ?: currentBg
        val predictedAt60 = if (predictedBg.size > 11) predictedBg[11] else predictedBg.lastOrNull() ?: currentBg

        // Populate prediction graph
        rT.predBGs = app.aaps.core.interfaces.aps.Predictions()
        rT.predBGs?.IOB = predictedBg.take(kinetics.diaMinutes.toInt() / 5).map { it.toInt() }

        val iobHeadroom  = (oapsProfile.max_iob - currentIob).coerceAtLeast(0.0)
        val iobOk        = currentIob < oapsProfile.max_iob
        val bgAboveGuard = currentBg - lowGuardMgdl

        val predMinGapMgdl = (predictedMin - targetBg).coerceAtLeast(0.0)
        val insulinReq     = predMinGapMgdl / dosingIsfMgdl

        val sb = StringBuilder()
        sb.append("SI mode=${mealMode.label} | BG=${fmt(currentBg, isMmol)} | IOB=${"%.2f".format(currentIob)}")
        sb.append(" | kinetics=${kinetics.peakMinutes.toInt()}m/${kinetics.diaMinutes.toInt()}m")
        if (mealMode != MealMode.FASTING) sb.append(" | food_abs=${carbAbsorption.absorptionMinutes.toInt()}m")
        sb.append(" | aggr=${"%.2f".format(aggressiveness)} | $tirSummary")

        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()
        val fallingFast    = delta < -FALLING_FAST_MGDL_PER_5MIN

        var smbOut = 0.0
        when {
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                setTempBasal(0.0, 30, oapsProfile, rT, currentTemp)
            }
            predictedMinSafety < lowGuardMgdl -> {
                setTempBasal(0.0, 30, oapsProfile, rT, currentTemp)
            }
            else -> {
                val smbAllowed = microBolusAllowed && !isTempTarget && bgAboveGuard > 0.0 && insulinReq > 0.0
                val correctionUnits = if (smbAllowed) {
                    insulinReq * (uamSmbFraction * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction
                } else 0.0

                val bolusStep      = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
                val clampedSmb     = (Math.ceil(correctionUnits / bolusStep) * bolusStep).coerceAtMost(minOf(maxSmbU, iobHeadroom))
                val constrainedSmb = if (clampedSmb >= bolusStep) clampedSmb else 0.0

                val reducedBasal = if (predictedMin < targetBg) {
                    (profileBasal - (targetBg - predictedMin) / dosingIsfMgdl / 0.5).coerceIn(0.0, profileBasal)
                } else profileBasal
                
                val tbrRate = reducedBasal * reboundTaperFraction
                smbOut = if (reboundTaperFraction >= REBOUND_SMB_GATE) constrainedSmb else 0.0
                setTempBasal(tbrRate, 30, oapsProfile, rT, currentTemp)
            }
        }

        rT.reason.append(sb)
        rT.units = smbOut.takeIf { it > 0.0 }
        rT.eventualBG = predictedAt60
        rT.targetBG = targetBg
        rT.bg = currentBg
        rT.IOB = currentIob
        rT.COB = mealData.mealCOB
        result.with(rT)
        return result
    }

    private fun predictBgCurve(
        startBg:  Double,
        ci:       Double,
        iobArray: Array<IobTotal>,
        isfMgdl:  Double,
        kinetics: LearnedInsulinKinetics,
        carbAbs:  LearnedCarbAbsorption,
        mealMode: MealMode
    ): List<Double> {
        var bg           = startBg
        val predictions  = mutableListOf<Double>()
        // Predict forward for the duration of insulin action
        val ticks = kinetics.diaMinutes.toInt() / 5
        
        for (tick in 1..ticks) {
            val minutes = tick * 5
            val activity = if (tick - 1 < iobArray.size) iobArray[tick - 1].activity else 0.0
            val iobDelta = -(activity * isfMgdl * 5.0)

            // Carb Impact Decay: 
            // If in meal mode, use the learned carb absorption duration to fade CI.
            val carbDuration = if (mealMode == MealMode.FASTING) 60.0 else carbAbs.absorptionMinutes
            val carbFade = (1.0 - (minutes / carbDuration)).coerceAtLeast(0.0)
            val carbDelta = ci * carbFade

            bg += iobDelta + carbDelta
            predictions.add(bg)
        }
        return predictions
    }

    companion object {
        private const val MMOL_TO_MGDL           = 18.0
        private const val REBOUND_SMB_GATE       = 0.825
        private const val FALLING_FAST_MGDL_PER_5MIN = 2.0 * MMOL_TO_MGDL / 5.0
        private const val NEUTRAL_TEMP_EPSILON       = 1e-6
    }
}
