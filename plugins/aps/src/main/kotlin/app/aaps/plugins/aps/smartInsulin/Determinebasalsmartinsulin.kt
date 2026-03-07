package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.profile.Profile
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Core basal/SMB determination for SmartInsulin.
 *
 * ## BG Prediction
 * Projects BG forward using a simple physiological model:
 *   - Current IOB is decayed forward using the learned activity curve
 *   - Each future minute: ΔBG = -activity[t] * ISF
 *   - Delta momentum (shortAvgDelta) is blended in for the first 15 min
 *     then fades to zero so pure IOB governs the tail
 *
 * ## SMB / Basal gating
 * Three zones based on the minimum predicted BG across the horizon:
 *   - predictedMin < lowGuard  → suspend: zero basal, block all SMBs
 *   - predictedMin < warnGuard → caution: reduce basal 50%, scale SMB by headroom fraction
 *   - predictedMin >= warnGuard → normal: pass through profile basal, allow SMB
 *
 * ## Learned profile usage
 * The learnedProfile's peakMinutes/diaMinutes are used to shape the
 * insulin activity curve that drives the prediction, replacing the
 * static profile DIA.
 */
@Singleton
class DetermineBasalSmartInsulin @Inject constructor(
    private val apsResultProvider: Provider<APSResult>
) {

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
    ): APSResult {

        val result = apsResultProvider.get()

        // ── Convert guard thresholds to mg/dL ───────────────────────
        val lowGuardMgdl  = lowGuardMmol  * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        // ── Current state ────────────────────────────────────────────
        val currentBg     = glucoseStatus.glucose          // mg/dL
        val delta         = glucoseStatus.shortAvgDelta    // mg/dL per 5 min
        val isfMgdl       = profile.getIsfMgdl("SmartInsulin")
        val profileBasal  = profile.getBasal()             // U/hr at current time
        val currentIob    = iobArray.firstOrNull()?.iob ?: 0.0

        // ── Build predicted BG curve ─────────────────────────────────
        val predictedBg = predictBgCurve(
            currentBg             = currentBg,
            delta                 = delta,
            iobArray              = iobArray,
            isfMgdl               = isfMgdl,
            learnedProfile        = learnedProfile,
            predictionHorizonMins = predictionHorizonMins
        )

        val predictedMin = predictedBg.minOrNull() ?: currentBg
        val predictedAt30 = if (predictedBg.size > 30) predictedBg[30] else predictedBg.lastOrNull() ?: currentBg
        val predictedAt60 = predictedBg.lastOrNull() ?: currentBg

        // ── Build reason string ──────────────────────────────────────
        val sb = StringBuilder()
        sb.append("SmartInsulin mode=${mealMode.label} ")
        sb.append("BG=%.1f delta=%.2f IOB=%.2f ".format(currentBg, delta, currentIob))
        sb.append("pred_min=%.1f pred30=%.1f pred60=%.1f ".format(predictedMin, predictedAt30, predictedAt60))
        sb.append("ISF=%.1f basal=%.3f ".format(isfMgdl, profileBasal))
        sb.append("peak=${learnedProfile.peakMinutes.toInt()}m dia=${learnedProfile.diaMinutes.toInt()}m ")

        // ── Zone decision ────────────────────────────────────────────
        when {
            // ── SUSPEND zone: predicted trough below hard low guard ──
            predictedMin < lowGuardMgdl -> {
                sb.append("SUSPEND pred_min=%.1f < lowGuard=%.1f".format(predictedMin, lowGuardMgdl))
                result.rate              = 0.0
                result.duration          = 30
                result.isTempBasalRequested = true
                result.smb               = 0.0
                result.reason            = sb.toString()
            }

            // ── CAUTION zone: predicted trough below warn guard ──────
            predictedMin < warnGuardMgdl -> {
                // Scale basal down proportionally to how close we are to the hard floor
                val headroom     = predictedMin - lowGuardMgdl
                val guardWindow  = warnGuardMgdl - lowGuardMgdl
                val scaleFactor  = if (guardWindow > 0.0) (headroom / guardWindow).coerceIn(0.0, 1.0) else 0.0
                val reducedBasal = (profileBasal * scaleFactor * 0.5).coerceAtLeast(0.0)

                sb.append("CAUTION scale=%.2f reducedBasal=%.3f".format(scaleFactor, reducedBasal))
                result.rate              = reducedBasal
                result.duration          = 30
                result.isTempBasalRequested = true
                result.smb               = 0.0   // no SMB in caution zone
                result.reason            = sb.toString()
            }

            // ── NORMAL zone: headroom above warn guard ───────────────
            else -> {
                // Allow SMB only if BG is above target and trending level/up
                val targetBg  = profile.getTargetMgdl()
                val smbAllowed = currentBg > targetBg && delta >= -DELTA_SMB_CUTOFF_MGDL_PER_5MIN

                // SMB size: fraction of remaining correction need, capped by profile limits
                val correctionNeeded   = (currentBg - targetBg) / isfMgdl   // units needed
                val maxSmbFraction     = 0.3   // never deliver more than 30% of correction as SMB
                val requestedSmb       = if (smbAllowed) (correctionNeeded * maxSmbFraction).coerceAtLeast(0.0) else 0.0

                sb.append("NORMAL targetBG=%.1f smbAllowed=$smbAllowed requestedSMB=%.3f".format(targetBg, requestedSmb))
                result.rate              = profileBasal
                result.duration          = 0      // 0 = cancel any running TBR, use profile basal
                result.isTempBasalRequested = false
                result.smb               = requestedSmb
                result.reason            = sb.toString()
            }
        }

        result.targetBG        = profile.getTargetMgdl()
        result.deliverAt       = currentTime
        result.hasPredictions  = true

        return result
    }

    // ── BG prediction engine ─────────────────────────────────────────────────

    /**
     * Projects BG forward [predictionHorizonMins] minutes using:
     *   1. IOB activity decay from iobArray (each slot = 1 minute, value = U/min)
     *   2. Delta momentum fading from 100% at t=0 to 0% at t=15min
     *
     * Returns a list of predicted BG values, one per minute from t=1 to t=horizon.
     */
    private fun predictBgCurve(
        currentBg:             Double,
        delta:                 Double,
        iobArray:              Array<IobTotal>,
        isfMgdl:               Double,
        learnedProfile:        LearnedInsulinProfile,
        predictionHorizonMins: Int
    ): List<Double> {
        val predictions = mutableListOf<Double>()
        var bg = currentBg

        // delta is mg/dL per 5 min — convert to per minute
        val deltaPerMin = delta / 5.0

        for (t in 1..predictionHorizonMins) {
            // IOB-driven BG change: activity[t] (U/min) * ISF (mg/dL/U) = mg/dL/min drop
            val activityAtT = getActivityAtMinute(t, iobArray, learnedProfile)
            val iobDelta    = -activityAtT * isfMgdl

            // Delta momentum: linear fade from full at t=1 to zero at t=DELTA_FADE_MINS
            val momentumWeight = max(0.0, 1.0 - t.toDouble() / DELTA_FADE_MINS)
            val momentumDelta  = deltaPerMin * momentumWeight

            bg += iobDelta + momentumDelta
            predictions.add(bg)
        }

        return predictions
    }

    /**
     * Returns the insulin activity (U/min) at [minuteOffset] minutes from now.
     *
     * Uses iobArray slots if available (each slot covers 1 minute starting at index 0 = now).
     * If t is beyond the iobArray length, uses a synthetic exponential decay
     * shaped by the learnedProfile's DIA.
     */
    private fun getActivityAtMinute(
        minuteOffset:   Int,
        iobArray:       Array<IobTotal>,
        learnedProfile: LearnedInsulinProfile
    ): Double {
        // iobArray is indexed by minutes; index 0 = now, index 1 = 1 min from now
        if (minuteOffset < iobArray.size) {
            return max(0.0, iobArray[minuteOffset].activity)
        }
        // Beyond array: extrapolate using exponential decay from last known activity
        val lastActivity = iobArray.lastOrNull()?.activity ?: return 0.0
        if (lastActivity <= 0.0) return 0.0
        val decayHalfLifeMins = learnedProfile.diaMinutes / 3.5  // ~3.5 half-lives in one DIA
        val extraMinutes      = minuteOffset - (iobArray.size - 1)
        return lastActivity * exp(-extraMinutes * LN2 / decayHalfLifeMins)
    }

    companion object {
        private const val MMOL_TO_MGDL              = 18.0
        private const val DELTA_FADE_MINS            = 15.0   // delta momentum fully gone by 15 min
        private const val DELTA_SMB_CUTOFF_MGDL_PER_5MIN = 1.0  // don't SMB if falling > 1 mg/dL/5min
        private const val LN2                        = 0.693147
    }
}