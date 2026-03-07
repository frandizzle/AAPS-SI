package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.Predictions
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
        maxSmbU:               Double,
        microBolusAllowed:     Boolean,
        currentTime:           Long
    ): APSResult {

        val result = apsResultProvider.get()

        val lowGuardMgdl  = lowGuardMmol  * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        val currentBg    = glucoseStatus.glucose
        val delta        = glucoseStatus.shortAvgDelta
        val isfMgdl      = oapsProfile.sens
        val profileBasal = oapsProfile.current_basal
        val targetBg     = oapsProfile.target_bg
        val currentIob   = iobArray.firstOrNull()?.iob ?: 0.0

        // ── Build prediction curves ──────────────────────────────────────────
        // Decision curve: user-configured horizon (default 60 min) for zone logic
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

        // ── Downsample curve to 5-min intervals for predBGs ──────────────────
        val iobPrediction: List<Int> = (0 until predictedBg.size step 5).map {
            predictedBg[it].coerceAtLeast(39.0).toInt()
        }

        // ── Reason string ────────────────────────────────────────────────────
        val isMmol = oapsProfile.out_units == "mmol/L"
        fun fmt(mgdl: Double) = if (isMmol) "%.1f".format(mgdl / MMOL_TO_MGDL) else "%.1f".format(mgdl)
        val units  = if (isMmol) "mmol" else "mg/dL"

        val sb = StringBuilder()
        sb.append("SI mode=${mealMode.label} ")
        sb.append("BG=${fmt(currentBg)} Δ=%.2f IOB=%.2f/%.2f ".format(delta, currentIob, oapsProfile.max_iob))
        sb.append("pred_min=${fmt(predictedMin)} pred30=${fmt(predictedAt30)} pred60=${fmt(predictedAt60)} $units ")
        sb.append("ISF=${fmt(isfMgdl)} basal=%.3f ".format(profileBasal))
        sb.append("learnedPeak=${learnedProfile.peakMinutes.toInt()}m learnedDIA=${learnedProfile.diaMinutes.toInt()}m ")

        // ── Decision: collect into local vars, call with() exactly once ──────
        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()

        var rateOut     = 0.0
        var durationOut = 0
        var smbOut      = 0.0
        var tempRequested = false

        when {
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                sb.append("LGS_SUSPEND BG=${fmt(currentBg)} < lgs=${fmt(lgsThresholdMgdl)}")
                rateOut       = 0.0
                durationOut   = 30
                tempRequested = true
            }

            predictedMin < lowGuardMgdl -> {
                sb.append("SUSPEND pred_min=${fmt(predictedMin)} < lowGuard=${fmt(lowGuardMgdl)}")
                rateOut       = 0.0
                durationOut   = 30
                tempRequested = true
            }

            predictedMin < warnGuardMgdl -> {
                val headroom    = predictedMin - lowGuardMgdl
                val guardWindow = warnGuardMgdl - lowGuardMgdl
                val scale       = if (guardWindow > 0.0) (headroom / guardWindow).coerceIn(0.0, 1.0) else 0.0
                val reduced     = (profileBasal * scale * 0.5).coerceAtLeast(0.0)
                sb.append("CAUTION scale=%.2f reducedBasal=%.3f".format(scale, reduced))
                rateOut       = reduced
                durationOut   = 30
                tempRequested = true
            }

            else -> {
                // SMB fires when BG is above lowGuard and rising (or stable above target).
                val bgAboveGuard  = currentBg - lowGuardMgdl
                val isRising      = delta > DELTA_RISING_THRESHOLD_MGDL_PER_5MIN
                val isAboveTarget = currentBg > targetBg

                val smbAllowed = microBolusAllowed &&
                    bgAboveGuard > 0.0 &&
                    (isRising || isAboveTarget) &&
                    delta >= -DELTA_SMB_CUTOFF_MGDL_PER_5MIN &&
                    currentIob < oapsProfile.max_iob

                // Size the SMB based on where BG is heading, not just where it is now.
                // Use pred30 as the reference — cover the gap between pred30 and target,
                // deliver SMB_DELIVERY_FRACTION of that correction per cycle so we don't
                // over-deliver on a single reading.
                val correctionUnits = if (smbAllowed) {
                    val eventualGap = (predictedAt30 - targetBg).coerceAtLeast(0.0)
                    val correction  = eventualGap / isfMgdl
                    correction * SMB_DELIVERY_FRACTION
                } else 0.0

                val bolusStep      = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
                val roundedSmb     = if (correctionUnits > 0.0)
                    (Math.ceil(correctionUnits / bolusStep) * bolusStep) else 0.0
                val constrainedSmb = if (roundedSmb >= bolusStep) roundedSmb.coerceAtMost(maxSmbU) else 0.0

                // TBR to cover the remaining correction not delivered by SMB.
                // totalCorrection = full pred30 gap / ISF (before delivery fraction).
                // remainingU = totalCorrection - constrainedSmb (what SMB won't cover).
                // Express as a 30-min TBR rate: remainingU / 0.5h + profileBasal.
                val totalCorrection = if (smbAllowed) {
                    val eventualGap = (predictedAt30 - targetBg).coerceAtLeast(0.0)
                    (eventualGap / isfMgdl).coerceAtLeast(0.0)
                } else 0.0
                val remainingU     = (totalCorrection - constrainedSmb).coerceAtLeast(0.0)
                val tbrRate        = if (remainingU > 0.0)
                    (profileBasal + remainingU / TBR_WINDOW_HOURS).coerceAtMost(oapsProfile.max_basal)
                else profileBasal

                val trigger = when {
                    !smbAllowed    -> "blocked"
                    isAboveTarget  -> "aboveTarget"
                    isRising       -> "rising"
                    else           -> "none"
                }
                sb.append("NORMAL targetBG=${fmt(targetBg)} microBolus=$microBolusAllowed trigger=$trigger smb=%.3f tbr=%.3f".format(constrainedSmb, tbrRate))
                rateOut       = tbrRate
                durationOut   = if (remainingU > 0.0) 30 else 0
                tempRequested = remainingU > 0.0
                smbOut        = constrainedSmb
            }
        }

        // ── Single with() call initialises lateinit RT and drives isChangeRequested
        result.with(
            RT(
                algorithm         = APSResult.Algorithm.SMB,
                runningDynamicIsf = false,
                timestamp         = currentTime,
                bg                = currentBg,
                eventualBG        = predictedAt60,
                targetBG          = targetBg,
                rate              = if (tempRequested) rateOut else null,
                duration          = if (tempRequested) durationOut else null,
                units             = smbOut.takeIf { it > 0.0 },
                deliverAt         = currentTime,
                reason            = sb,
                predBGs           = Predictions(IOB = iobPrediction)
            )
        )

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
            // Momentum fades linearly over DELTA_FADE_MINS — but cap contribution
            // per minute so a single large delta reading doesn't create a spike
            val momentumWeight = max(0.0, 1.0 - t.toDouble() / DELTA_FADE_MINS)
            val momentumDelta  = (deltaPerMin * momentumWeight).coerceIn(-MAX_MOMENTUM_MGDL_PER_MIN, MAX_MOMENTUM_MGDL_PER_MIN)
            // Clamp total step so prediction can't drop faster than MAX_DROP_MGDL_PER_MIN
            val step           = (iobDelta + momentumDelta).coerceAtLeast(-MAX_DROP_MGDL_PER_MIN)
            bg += step
            // Never predict below 39 mg/dL (sensor floor)
            bg = bg.coerceAtLeast(39.0)
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
        private const val MMOL_TO_MGDL                         = 18.0
        private const val DELTA_FADE_MINS                       = 30.0  // momentum fades over 30 mins
        private const val MAX_MOMENTUM_MGDL_PER_MIN             = 2.0   // cap momentum contribution per minute
        private const val MAX_DROP_MGDL_PER_MIN                 = 3.0   // cap how fast prediction can fall per minute
        private const val DELTA_SMB_CUTOFF_MGDL_PER_5MIN      = 1.0   // don't SMB if falling faster than this
        private const val DELTA_RISING_THRESHOLD_MGDL_PER_5MIN = 0.5   // delta above this = "rising" trigger
        private const val SMB_DELIVERY_FRACTION                 = 0.5   // deliver 50% of pred30 correction per cycle
        private const val TBR_WINDOW_HOURS                      = 0.5   // spread remaining correction over 30 mins via TBR
        private const val LN2                             = 0.693147
    }
}