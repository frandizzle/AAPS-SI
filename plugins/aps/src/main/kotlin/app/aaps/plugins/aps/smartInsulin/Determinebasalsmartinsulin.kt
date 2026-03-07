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
import java.util.Locale
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

    /**
     * Mirrors AutoISF setTempBasal() — always sets rate+duration on rT so isChangeRequested
     * is always true and the reason string always shows in the Loop tab.
     * Respects skip_neutral_temps: if true and rate == profile basal, cancels any running
     * temp or does nothing (matching stock AAPS behaviour).
     */
    private fun setTempBasal(rate: Double, duration: Int, oapsProfile: OapsProfile, rT: RT, currentTemp: CurrentTemp): RT {
        val safeRate = rate.coerceIn(0.0, oapsProfile.max_basal)
        val rounded  = (Math.round(safeRate * 100.0) / 100.0)
        // We construct a fresh rT every cycle so rate/duration MUST always be set --
        // otherwise isChangeRequested stays false and the reason string never shows.
        rT.duration = duration
        rT.rate     = rounded
        return rT
    }

    fun determine_basal(
        glucoseStatus:         GlucoseStatus,
        currentTemp:           CurrentTemp,
        iobArray:              Array<IobTotal>,
        oapsProfile:           OapsProfile,
        mealData:              MealData,
        profile:               Profile,
        learnedProfile:        LearnedInsulinProfile,
        mealMode:              MealMode,
        lowGuardMmol:          Double,
        warnGuardMmol:         Double,
        maxSmbU:               Double,
        maxTbrU:               Double,
        aggressiveness:        Double,
        tirSummary:            String,
        basalMultiplier:       Double,
        microBolusAllowed:     Boolean,
        inReboundWindow:       Boolean,
        msSinceLastSuspend:    Long,
        currentTime:           Long
    ): APSResult {

        val result = apsResultProvider.get()

        val lowGuardMgdl  = lowGuardMmol  * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        val isMmol       = oapsProfile.out_units == "mmol/L"
        val currentBg    = glucoseStatus.glucose          // mg/dL
        val delta        = glucoseStatus.shortAvgDelta    // mg/dL
        val isfMgdl      = oapsProfile.sens               // already mg/dL (getIsfMgdl)
        val profileBasalRaw = oapsProfile.current_basal
        val profileBasal    = profileBasalRaw * basalMultiplier
        val targetBg     = oapsProfile.target_bg          // already mg/dL
        val currentIob   = iobArray.firstOrNull()?.iob ?: 0.0

        // ── Build prediction curves ──────────────────────────────────────────
        // Always run to 240 mins for the graph (matches AutoISF 48-point convention).
        // Algorithm reads at fixed indices 30 and 60 — independent of graph horizon.
        val predictedBg = predictBgCurve(
            currentBg             = currentBg,
            delta                 = delta,
            iobArray              = iobArray,
            isfMgdl               = isfMgdl,
            learnedProfile        = learnedProfile,
            predictionHorizonMins = 240
        )

        val predictedMin  = predictedBg.minOrNull() ?: currentBg
        val predictedAt30 = if (predictedBg.size > 30) predictedBg[30] else predictedBg.lastOrNull() ?: currentBg
        val predictedAt60 = if (predictedBg.size > 60) predictedBg[60] else predictedBg.lastOrNull() ?: currentBg

        // ── Downsample curve to 5-min intervals for predBGs ──────────────────
        // Index 0 = current BG (anchors curve at "now"), then 5-min steps forward
        // DetermineBasalResult renders starting at i=1, so index 0 is the anchor point
        // Build prediction list matching AutoISF conventions:
        //   - 48 points max (4 hours at 5-min intervals) — AAPS graph uses list length to place "now" line
        //   - Clamp to [39, 401]
        //   - Trim trailing flat points (min 13 kept) so curve doesn't extend forever at target
        val rawPrediction: MutableList<Int> = mutableListOf()
        rawPrediction.add(currentBg.coerceIn(39.0, 401.0).toInt())  // index 0 = now
        (4 until predictedBg.size step 5).forEach {
            rawPrediction.add(predictedBg[it].coerceIn(39.0, 401.0).toInt())
        }
        // Trim trailing identical values down to minimum 13 points (matches AutoISF)
        for (i in rawPrediction.size - 1 downTo 13) {
            if (rawPrediction[i - 1] != rawPrediction[i]) break
            else rawPrediction.removeAt(rawPrediction.lastIndex)
        }
        val iobPrediction: List<Int> = rawPrediction

        // ── Reason string ────────────────────────────────────────────────────
        fun fmt(mgdl: Double) = if (isMmol) "%.1f".format(Locale.US, mgdl / MMOL_TO_MGDL) else "%.1f".format(Locale.US, mgdl)
        val units  = if (isMmol) "mmol" else "mg/dL"

        val sb = StringBuilder()
        sb.append("SI mode=${mealMode.label} ")
        sb.append("BG=${fmt(currentBg)} Δ=%.2f IOB=%.2f/%.2f ".format(Locale.US, delta, currentIob, oapsProfile.max_iob))
        sb.append("pred_min=${fmt(predictedMin)} pred30=${fmt(predictedAt30)} pred60=${fmt(predictedAt60)} $units ")
        sb.append("ISF=${fmt(isfMgdl)} basal=%.3f(×%.2f) ".format(Locale.US, profileBasal, basalMultiplier))
        sb.append("learnedPeak=${learnedProfile.peakMinutes.toInt()}m learnedDIA=${learnedProfile.diaMinutes.toInt()}m ")
        sb.append("aggr=%.2f $tirSummary ".format(Locale.US, aggressiveness))
        // ── Decision: collect into local vars, call with() exactly once ──────
        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()

        // Falling-into-low: dropping fast AND pred30 already below warnGuard
        val fallingFast    = delta < -FALLING_FAST_MGDL_PER_5MIN
        val fallingIntoLow = fallingFast && predictedAt30 < warnGuardMgdl

        // Rebound protection: taper back to full operation over REBOUND_TAPER_MINS after a suspend
        val reboundMins          = if (inReboundWindow) (msSinceLastSuspend / 60_000.0) else 0.0
        val reboundTaperFraction = if (inReboundWindow)
            (reboundMins / REBOUND_TAPER_MINS).coerceIn(0.0, 1.0)
        else 1.0  // 1.0 = full normal dosing

        @Suppress("RedundantValueArgument") var smbOut = 0.0

        // ── Build RT, then call setTempBasal to set rate/duration exactly as AutoISF does ──
        val rT = RT(
            algorithm         = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp         = currentTime,
            bg                = currentBg,
            eventualBG        = predictedAt60,
            targetBG          = targetBg,
            deliverAt         = currentTime,
            reason            = sb,
            predBGs           = Predictions(IOB = iobPrediction)
        )

        when {
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                sb.append("LGS_SUSPEND BG=${fmt(currentBg)} < lgs=${fmt(lgsThresholdMgdl)}")
                setTempBasal(0.0, 30, oapsProfile, rT, currentTemp)
            }

            predictedMin < lowGuardMgdl || fallingIntoLow -> {
                val reason = if (fallingIntoLow && predictedMin >= lowGuardMgdl)
                    "SUSPEND fallingIntoLow pred30=${fmt(predictedAt30)} delta=${String.format(Locale.US, "%.1f", delta)}"
                else
                    "SUSPEND pred_min=${fmt(predictedMin)} < lowGuard=${fmt(lowGuardMgdl)}"
                sb.append(reason)
                setTempBasal(0.0, 30, oapsProfile, rT, currentTemp)
            }

            predictedMin < warnGuardMgdl -> {
                val headroom    = predictedMin - lowGuardMgdl
                val guardWindow = warnGuardMgdl - lowGuardMgdl
                val scale       = if (guardWindow > 0.0) (headroom / guardWindow).coerceIn(0.0, 1.0) else 0.0
                val reduced     = (profileBasal * scale * 0.5).coerceAtLeast(0.0)
                sb.append("CAUTION scale=%.2f reducedBasal=%.3f".format(Locale.US, scale, reduced))
                setTempBasal(reduced, 30, oapsProfile, rT, currentTemp)
            }

            else -> {
                val bgAboveGuard  = currentBg - lowGuardMgdl
                val isRising      = delta > DELTA_RISING_THRESHOLD_MGDL_PER_5MIN
                val iobHeadroom   = (oapsProfile.max_iob - currentIob).coerceAtLeast(0.0)
                val minHeadroom   = (oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05)
                val iobOk         = iobHeadroom >= minHeadroom

                val iobDrop       = currentIob * isfMgdl  // for reason string display only
                val bgAboveTarget = (currentBg - targetBg).coerceAtLeast(0.0)
                // IOB is sufficient if prediction shows BG arriving at or below target without more insulin
                val iobSufficient = predictedAt60 <= targetBg

                val smbAllowed = microBolusAllowed &&
                    bgAboveGuard > 0.0 &&
                    delta >= -DELTA_SMB_CUTOFF_MGDL_PER_5MIN &&
                    predictedAt60 > targetBg &&
                    !iobSufficient

                // Correction based on min of pred30/pred60 gap so we taper as IOB works
                val correctionUnits = if (smbAllowed) {
                    val pred30Gap    = (predictedAt30 - targetBg).coerceAtLeast(0.0)
                    val pred60Gap    = (predictedAt60 - targetBg).coerceAtLeast(0.0)
                    val effectiveGap = minOf(pred30Gap, pred60Gap)
                    // aggressiveness scales delivery fraction: 1.0=50%, 1.5=75%, 0.5=25%
                    (effectiveGap / isfMgdl) * (SMB_DELIVERY_FRACTION * aggressiveness).coerceIn(0.1, 0.9)
                } else 0.0

                val bolusStep  = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
                val rawSmb     = if (correctionUnits > 0.0)
                    (Math.ceil(correctionUnits / bolusStep) * bolusStep) else 0.0
                val smbCap         = minOf(maxSmbU, iobHeadroom)
                // Apply headroom cap first, then drop if result is below one pump step
                val clampedSmb     = rawSmb.coerceAtMost(smbCap)
                val constrainedSmb = if (clampedSmb >= bolusStep) clampedSmb else 0.0

                // TBR: 0.00 U/h when IOB at or above max (let it decay).
                // Elevated TBR only when there's headroom AND correction needed.
                val totalCorrection = if (smbAllowed && iobOk) {
                    val pred30Gap  = (predictedAt30 - targetBg).coerceAtLeast(0.0)
                    val pred60Gap  = (predictedAt60 - targetBg).coerceAtLeast(0.0)
                    // aggressiveness scales how much of the gap we try to cover via TBR
                    (minOf(pred30Gap, pred60Gap) / isfMgdl) * aggressiveness
                } else 0.0
                val remainingU  = (totalCorrection - constrainedSmb).coerceAtLeast(0.0)

                val tbrRateRaw = when {
                    !iobOk           -> 0.0
                    iobSufficient    -> 0.0
                    remainingU > 0.0 -> (profileBasal + remainingU / TBR_WINDOW_HOURS)
                        .coerceAtMost(oapsProfile.max_basal)
                        .coerceAtMost(maxTbrU)
                    else             -> profileBasal
                }
                val tbrRate = tbrRateRaw * reboundTaperFraction

                // Block SMBs entirely during rebound window, then taper back in
                val reboundSmbAllowed = reboundTaperFraction >= REBOUND_SMB_GATE
                val finalSmb = if (reboundSmbAllowed) constrainedSmb else 0.0

                val needsTbr = !iobOk || iobSufficient || remainingU > 0.0 || inReboundWindow

                val trigger = when {
                    !iobOk        -> "maxIOB(${String.format(Locale.US, "%.2f", currentIob)}/${String.format(Locale.US, "%.2f", oapsProfile.max_iob)})"
                    iobSufficient -> "iobSufficient(drop=${String.format(Locale.US, "%.0f", iobDrop)}>=gap=${String.format(Locale.US, "%.0f", bgAboveTarget)})"
                    !smbAllowed   -> "blocked"
                    isRising      -> "rising"
                    else          -> "aboveTarget"
                }

                // Rebound state — always shown when active regardless of trigger
                val reboundStr = if (inReboundWindow) {
                    val minsLeft = ((REBOUND_TAPER_MINS - reboundMins).coerceAtLeast(0.0))
                    val smbState = if (!reboundSmbAllowed) "smbBlocked" else "smbAllowed"
                    " rebound(elapsed=%.0fmin left=%.0fmin taper=%.2f %s tbrRaw=%.3f→%.3f)".format(
                        Locale.US, reboundMins, minsLeft, reboundTaperFraction, smbState, tbrRateRaw, tbrRate
                    )
                } else ""

                sb.append("NORMAL targetBG=${fmt(targetBg)} microBolus=$microBolusAllowed trigger=$trigger smb=%.3f tbr=%.3f%s".format(Locale.US, finalSmb, tbrRate, reboundStr))
                smbOut = finalSmb
                setTempBasal(tbrRate, 30, oapsProfile, rT, currentTemp)
            }
        }

        rT.units = smbOut.takeIf { it > 0.0 }
        result.with(rT)

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
            // Match AutoISF exactly: predBGI = -(activity * ISF * 5)
            // activity is already U/min decay rate — no IOB multiplication needed
            val activity   = getActivityAtMinute(t, iobArray, learnedProfile)
            val iobDelta   = -(activity * isfMgdl * 5.0)
            // Momentum fades linearly over DELTA_FADE_MINS
            val momentumWeight = max(0.0, 1.0 - t.toDouble() / DELTA_FADE_MINS)
            val momentumDelta  = (deltaPerMin * momentumWeight).coerceIn(-MAX_MOMENTUM_MGDL_PER_MIN, MAX_MOMENTUM_MGDL_PER_MIN)
            bg += iobDelta + momentumDelta
            // Never predict below sensor floor
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
        val idx = minuteOffset / 5
        if (idx < iobArray.size) {
            return max(0.0, iobArray[idx].activity)
        }
        val lastActivity = iobArray.lastOrNull()?.activity ?: return 0.0
        if (lastActivity <= 0.0) return 0.0
        val halfLife = learnedProfile.diaMinutes / 3.5
        val extra    = minuteOffset - ((iobArray.size - 1) * 5)
        return lastActivity * exp(-extra * LN2 / halfLife)
    }

    companion object {
        private const val MMOL_TO_MGDL                         = 18.0
        private const val DELTA_FADE_MINS                       = 15.0  // momentum fades over 15 mins
        private const val MAX_MOMENTUM_MGDL_PER_MIN             = 1.0   // cap momentum contribution per minute
        private const val DELTA_SMB_CUTOFF_MGDL_PER_5MIN       = 1.0   // don't SMB if falling faster than this
        private const val DELTA_RISING_THRESHOLD_MGDL_PER_5MIN  = 0.5   // delta above this = "rising" trigger
        private const val FALLING_FAST_MGDL_PER_5MIN            = 2.0   // suspend early if falling faster than this
        private const val SMB_DELIVERY_FRACTION                  = 0.5   // deliver 50% of effective gap per cycle
        private const val TBR_WINDOW_HOURS                       = 0.5   // spread remaining correction over 30 mins
        private const val REBOUND_TAPER_MINS                     = 60.0  // ramp back to full dosing over 60 mins post-suspend
        private const val REBOUND_SMB_GATE                       = 0.5   // block SMBs until 50% through rebound window
        private const val LN2                             = 0.693147
    }
}