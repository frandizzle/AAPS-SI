package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.Profile
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max

/**
 * Core basal/SMB determination for SmartInsulin.
 *
 * ## BG Prediction
 * Projects glucose forward using:
 *   - IOB activity curve from iobArray (U/min * ISF = mg/dL/min drop)
 *   - Delta momentum from shortAvgDelta, fading linearly to zero by t=15 min
 *   - Exponential tail extrapolation beyond iobArray length
 *
 * ## SMB / Basal gating
 *   - predictedMin < lowGuard  → SUSPEND: zero TBR, block SMBs
 *   - predictedMin < warnGuard → CAUTION: scaled TBR, block SMBs
 *   - predictedMin >= warnGuard → NORMAL: profile basal, SMBs allowed
 *
 * ## Prediction graph
 * Populates result.predictionsAsGv with one GV per minute for display
 * on the AAPS home screen prediction curve.
 */
@Singleton
class DetermineBasalSmartInsulin @Inject constructor(
    private val apsResultProvider: Provider<APSResult>
) {

    fun determine_basal(
        glucoseStatus:         GlucoseStatus,
        currentTemp:           CurrentTemp,
        iobArray:              Array<IobTotal>,
        oapsProfile:           OapsProfile,
        mealData:              MealData,
        profile:               Profile,
        learnedProfile:        LearnedInsulinProfile,
        mealMode:              MealMode,
        predictionHorizonMins: Int,
        lowGuardMmol:          Double,
        warnGuardMmol:         Double,
        microBolusAllowed:     Boolean,
        currentTime:           Long
    ): APSResult {

        val result = apsResultProvider.get()

        // Must call with() before accessing predictionsAsGv or any property backed by lateinit result
        result.with(
            RT(
                algorithm        = APSResult.Algorithm.SMB,
                runningDynamicIsf = false,
                timestamp        = currentTime,
                bg               = glucoseStatus.glucose,
                rate             = 0.0,
                duration         = 0,
                reason           = StringBuilder()
            )
        )

        val lowGuardMgdl  = lowGuardMmol  * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        val currentBg    = glucoseStatus.glucose
        val delta        = glucoseStatus.shortAvgDelta
        val isfMgdl      = oapsProfile.sens
        val profileBasal = oapsProfile.current_basal
        val targetBg     = oapsProfile.target_bg
        val currentIob   = iobArray.firstOrNull()?.iob ?: 0.0

        // ── Build prediction curve ───────────────────────────────────────────
        val predictedBg = predictBgCurve(
            currentBg             = currentBg,
            delta                 = delta,
            iobArray              = iobArray,
            isfMgdl               = isfMgdl,
            learnedProfile        = learnedProfile,
            predictionHorizonMins = predictionHorizonMins
        )

        val predictedMin  = predictedBg.minOrNull() ?: currentBg
        val predictedAt30 = if (predictedBg.size > 30) predictedBg[30] else predictedBg.lastOrNull() ?: currentBg
        val predictedAt60 = predictedBg.lastOrNull() ?: currentBg

        // ── Populate prediction GVs for home screen graph ────────────────────
        result.predictionsAsGv.clear()
        predictedBg.forEachIndexed { minuteOffset, bg ->
            result.predictionsAsGv.add(
                GV(
                    timestamp    = currentTime + (minuteOffset + 1) * 60_000L,
                    value        = bg.coerceAtLeast(39.0),   // AAPS clips below 39
                    raw          = null,
                    trendArrow   = TrendArrow.NONE,
                    noise        = null,
                    sourceSensor = SourceSensor.UNKNOWN
                )
            )
        }

        // ── Reason string ────────────────────────────────────────────────────
        val sb = StringBuilder()
        sb.append("SI mode=${mealMode.label} ")
        sb.append("BG=%.1f Δ=%.2f IOB=%.2f ".format(currentBg, delta, currentIob))
        sb.append("pred_min=%.1f pred30=%.1f pred60=%.1f ".format(predictedMin, predictedAt30, predictedAt60))
        sb.append("ISF=%.1f basal=%.3f ".format(isfMgdl, profileBasal))
        sb.append("peak=${learnedProfile.peakMinutes.toInt()}m dia=${learnedProfile.diaMinutes.toInt()}m ")

        // ── LGS: hard suspend on current BG below threshold ─────────────────
        // lgsThreshold is in mg/dL (converted from user's mmol preference by AAPS core)
        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()
        if (lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl) {
            sb.append("LGS_SUSPEND currentBG=%.1f < lgs=%.1f".format(currentBg, lgsThresholdMgdl))
            result.rate                 = 0.0
            result.duration             = 30
            result.isTempBasalRequested = true
            result.smb                  = 0.0
            result.reason               = sb.toString()
            return result
        }

        // ── Zone decision ────────────────────────────────────────────────────
        when {
            predictedMin < lowGuardMgdl -> {
                sb.append("SUSPEND pred_min=%.1f < lowGuard=%.1f".format(predictedMin, lowGuardMgdl))
                result.rate                 = 0.0
                result.duration             = 30
                result.isTempBasalRequested = true
                result.smb                  = 0.0
            }

            predictedMin < warnGuardMgdl -> {
                val headroom    = predictedMin - lowGuardMgdl
                val guardWindow = warnGuardMgdl - lowGuardMgdl
                val scale       = if (guardWindow > 0.0) (headroom / guardWindow).coerceIn(0.0, 1.0) else 0.0
                val reduced     = (profileBasal * scale * 0.5).coerceAtLeast(0.0)
                sb.append("CAUTION scale=%.2f reducedBasal=%.3f".format(scale, reduced))
                result.rate                 = reduced
                result.duration             = 30
                result.isTempBasalRequested = true
                result.smb                  = 0.0
            }

            else -> {
                val smbAllowed = microBolusAllowed &&
                    oapsProfile.enableSMB_always &&
                    currentBg > targetBg &&
                    delta >= -DELTA_SMB_CUTOFF_MGDL_PER_5MIN

                val correctionUnits   = ((currentBg - targetBg) / isfMgdl).coerceAtLeast(0.0)
                val requestedSmb      = if (smbAllowed) (correctionUnits * SMB_CORRECTION_FRACTION).coerceAtLeast(0.0) else 0.0
                val constrainedSmb    = requestedSmb.coerceAtMost(
                    profileBasal / 60.0 * oapsProfile.maxSMBBasalMinutes
                )

                sb.append("NORMAL targetBG=%.1f smbAllowed=$smbAllowed smb=%.3f".format(targetBg, constrainedSmb))
                result.rate                 = profileBasal
                result.duration             = 0
                result.isTempBasalRequested = false
                result.smb                  = constrainedSmb
            }
        }

        result.reason       = sb.toString()
        result.targetBG     = targetBg
        result.deliverAt    = currentTime
        result.hasPredictions = true

        return result
    }

    // ── BG prediction engine ─────────────────────────────────────────────────

    private fun predictBgCurve(
        currentBg:             Double,
        delta:                 Double,
        iobArray:              Array<IobTotal>,
        isfMgdl:               Double,
        learnedProfile:        LearnedInsulinProfile,
        predictionHorizonMins: Int
    ): List<Double> {
        val predictions  = mutableListOf<Double>()
        var bg           = currentBg
        val deltaPerMin  = delta / 5.0

        for (t in 1..predictionHorizonMins) {
            val activity       = getActivityAtMinute(t, iobArray, learnedProfile)
            val iobDelta       = -activity * isfMgdl
            val momentumWeight = max(0.0, 1.0 - t.toDouble() / DELTA_FADE_MINS)
            val momentumDelta  = deltaPerMin * momentumWeight
            bg += iobDelta + momentumDelta
            predictions.add(bg)
        }
        return predictions
    }

    private fun getActivityAtMinute(
        minuteOffset:   Int,
        iobArray:       Array<IobTotal>,
        learnedProfile: LearnedInsulinProfile
    ): Double {
        if (minuteOffset < iobArray.size) {
            return max(0.0, iobArray[minuteOffset].activity)
        }
        val lastActivity = iobArray.lastOrNull()?.activity ?: return 0.0
        if (lastActivity <= 0.0) return 0.0
        val halfLife     = learnedProfile.diaMinutes / 3.5
        val extra        = minuteOffset - (iobArray.size - 1)
        return lastActivity * exp(-extra * LN2 / halfLife)
    }

    companion object {
        private const val MMOL_TO_MGDL                   = 18.0
        private const val DELTA_FADE_MINS                 = 15.0
        private const val DELTA_SMB_CUTOFF_MGDL_PER_5MIN = 1.0
        private const val SMB_CORRECTION_FRACTION         = 0.3
        private const val LN2                             = 0.693147
    }
}