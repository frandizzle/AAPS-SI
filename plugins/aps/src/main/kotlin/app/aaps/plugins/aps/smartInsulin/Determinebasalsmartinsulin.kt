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
import kotlin.math.min

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
        dosingIsfMgdl:         Double,         // ISF × mode multiplier — used ONLY for dose sizing, not prediction
        microBolusAllowed:     Boolean,
        inReboundWindow:       Boolean,
        msSinceLastSuspend:    Long,
        currentTime:           Long,
        isTempTarget:          Boolean,
        profileTargetMgdl:     Double,         // unmodified profile target — for high temp target SMB suppression
        dawnWindowStartHour:   Int,
        dawnWindowEndHour:     Int,
        dawnSmbReduction:      Double,         // fraction 0.1–1.0; 0.5 = 50% of normal SMB
        bgWentLow:             Boolean         // true if BG crossed below threshold during suspend — for reason string
    ): APSResult {

        val result = apsResultProvider.get()

        val lowGuardMgdl  = lowGuardMmol  * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        val isMmol       = oapsProfile.out_units == "mmol/L"
        val currentBg    = glucoseStatus.glucose          // mg/dL
        val delta        = glucoseStatus.shortAvgDelta    // mg/dL
        val minDelta     = minOf(glucoseStatus.delta, glucoseStatus.shortAvgDelta)
        val isfMgdl      = oapsProfile.sens               // already mg/dL (getIsfMgdl)
        val profileBasalRaw = oapsProfile.current_basal
        val profileBasal    = profileBasalRaw * basalMultiplier
        val targetBg     = oapsProfile.target_bg          // already mg/dL (may be temp target)
        val currentIob   = iobArray.firstOrNull()?.iob ?: 0.0

        // ── High temp target → suppress SMBs (matches stock AAPS behaviour) ──
        val highTempTargetActive = isTempTarget && targetBg > profileTargetMgdl

        // ── Dawn / fasting rise window detection ─────────────────────────────
        // If fasting mode + time in user-defined window + BG rising → apply SMB reduction
        val cal = java.util.Calendar.getInstance().also { it.timeInMillis = currentTime }
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val inDawnWindow = mealMode == MealMode.FASTING && delta > 0 && run {
            if (dawnWindowStartHour <= dawnWindowEndHour)
                currentHour in dawnWindowStartHour until dawnWindowEndHour
            else  // wraps midnight e.g. 22–06
                currentHour >= dawnWindowStartHour || currentHour < dawnWindowEndHour
        }
        // Effective SMB fraction: if dawn window active use dawnSmbReduction, else full 1.0
        val dawnFraction = if (inDawnWindow) dawnSmbReduction else 1.0

        // ci = current deviation from IOB-only prediction (matches AutoISF)
        // bgi = expected BG change from current IOB activity alone
        // ci = minDelta - bgi: positive = carbs/UAM pushing BG up, negative = activity/other pulling down
        val bgi = -((iobArray.firstOrNull()?.activity ?: 0.0) * isfMgdl * 5.0)
        val ci  = minDelta - bgi

        // ── Build prediction curves ──────────────────────────────────────────
        // Always run to 240 mins for the graph (matches AutoISF 48-point convention).
        // Algorithm reads at fixed indices 30 and 60 — independent of graph horizon.
        val predictedBg = predictBgCurve(
            currentBg             = currentBg,
            ci                    = ci,
            iobArray              = iobArray,
            isfMgdl               = isfMgdl,
            learnedProfile        = learnedProfile,
            predictionHorizonMins = 240
        )

        val predictedMin  = predictedBg.minOrNull() ?: currentBg
        // List is now 5-min ticks: index 6 = 30 mins, index 12 = 60 mins
        val predictedAt30 = if (predictedBg.size > 6)  predictedBg[6]  else predictedBg.lastOrNull() ?: currentBg
        val predictedAt60 = if (predictedBg.size > 12) predictedBg[12] else predictedBg.lastOrNull() ?: currentBg

        // ── Downsample curve to 5-min intervals for predBGs ──────────────────
        val rawPrediction: MutableList<Int> = mutableListOf()
        rawPrediction.add(currentBg.coerceIn(39.0, 401.0).toInt())  // index 0 = now
        predictedBg.take(24).forEach { rawPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
        val iobPrediction: List<Int> = rawPrediction

        // ── Rebound taper state (computed early — used in reason string and decision) ──
        val reboundCancelled     = false  // cancellation handled upstream via bgWentLow flag
        val reboundMins          = if (inReboundWindow) (msSinceLastSuspend / 60_000.0) else 0.0
        val reboundTaperFraction = if (inReboundWindow)
            (reboundMins / REBOUND_TAPER_MINS).coerceIn(0.0, 1.0)
        else 1.0  // 1.0 = full normal dosing

        // ── Reason string ────────────────────────────────────────────────────
        fun fmt(mgdl: Double) = if (isMmol) "%.1f".format(Locale.US, mgdl / MMOL_TO_MGDL) else "%.1f".format(Locale.US, mgdl)
        val units  = if (isMmol) "mmol" else "mg/dL"

        val sb = StringBuilder()
        sb.append("SI mode=${mealMode.label} ")
        sb.append("BG=${fmt(currentBg)} Delta=%.2f IOB=%.2f/%.2f ".format(Locale.US, delta, currentIob, oapsProfile.max_iob))
        sb.append("pred_min=${fmt(predictedMin)} pred30m=${fmt(predictedAt30)} pred60m=${fmt(predictedAt60)} $units ")
        sb.append("target=${fmt(targetBg)}${if (isTempTarget) "(tmp)" else ""} ")
        sb.append("ISF=${fmt(dosingIsfMgdl)} basal=%.3f(x%.2f) ".format(Locale.US, profileBasal, basalMultiplier))
        sb.append("learnedPeak=${learnedProfile.peakMinutes.toInt()}m learnedDIA=${learnedProfile.diaMinutes.toInt()}m ")
        sb.append("aggr=%.2f ".format(Locale.US, aggressiveness))
        if (inDawnWindow) sb.append("dawnWindow(reduction=%.0f%%) ".format(Locale.US, dawnSmbReduction * 100))
        if (highTempTargetActive) sb.append("highTempTarget=smbOff ")
        if (inReboundWindow) {
            val reboundMinsLeft = (REBOUND_TAPER_MINS - reboundMins).coerceAtLeast(0.0)
            sb.append("rebound(elapsed=%.0fmin left=%.0fmin taper=%.2f) ".format(Locale.US, reboundMins, reboundMinsLeft, reboundTaperFraction))
        } else if (bgWentLow) {
            sb.append("rebound=watching ")  // went low but BG hasn't crossed back up yet
        }
        sb.append("$tirSummary ")
        // ── Decision: collect into local vars, call with() exactly once ──────
        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()

        // Falling-into-low: dropping fast AND pred30 already below warnGuard
        val fallingFast    = delta < -FALLING_FAST_MGDL_PER_5MIN
        val fallingIntoLow = fallingFast && predictedAt30 < warnGuardMgdl

        // Rebound protection: only active if BG actually went under 4.7 mmol and has since crossed back up
        // (inReboundWindow is false if BG never crossed the threshold — see SmartInsulinPlugin)

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

                // SMB gated purely on predictions — if pred60 is above target, dose.
                // High temp target (> profile target) suppresses SMBs entirely.
                // Delta is already baked into the prediction curve, no separate delta gate needed.
                val smbAllowed = microBolusAllowed &&
                    !highTempTargetActive &&
                    bgAboveGuard > 0.0 &&
                    predictedAt60 > targetBg &&
                    !iobSufficient

                // Correction based on pred30 gap, scaled by dawn reduction fraction if in dawn window
                val correctionUnits = if (smbAllowed) {
                    val pred30Gap = (predictedAt30 - targetBg).coerceAtLeast(0.0)
                    // aggressiveness scales delivery fraction: 1.0=50%, 1.5=75%, 0.5=25%
                    // dawnFraction further scales down during dawn/fasting rise window
                    (pred30Gap / dosingIsfMgdl) * (SMB_DELIVERY_FRACTION * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction
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
                    // Size TBR correction from pred30 gap — consistent with SMB sizing
                    val pred30Gap = (predictedAt30 - targetBg).coerceAtLeast(0.0)
                    (pred30Gap / dosingIsfMgdl) * aggressiveness
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
                val reboundStr = if (reboundCancelled) {
                    "reboundCancelled(BG=${fmt(currentBg)} rising)"
                } else if (inReboundWindow) {
                    val minsLeft = ((REBOUND_TAPER_MINS - reboundMins).coerceAtLeast(0.0))
                    val smbState = if (!reboundSmbAllowed) "smbBlocked" else "smbAllowed"
                    " rebound(elapsed=%.0fmin left=%.0fmin taper=%.2f %s tbrRaw=%.3f->%.3f)".format(
                        Locale.US, reboundMins, minsLeft, reboundTaperFraction, smbState, tbrRateRaw, tbrRate
                    )
                } else ""

                sb.append("NORMAL targetBG=${fmt(targetBg)} microBolus=$microBolusAllowed trigger=$trigger ".format())
                sb.append("smb=%.3f tbr=%.3f%s".format(Locale.US, finalSmb, tbrRate, reboundStr))
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
        ci:                    Double,
        iobArray:              Array<IobTotal>,
        isfMgdl:               Double,
        learnedProfile:        LearnedInsulinProfile,
        predictionHorizonMins: Int
    ): List<Double> {
        // Iterate in 5-min ticks matching AutoISF convention.
        // predBGI = -(activity * ISF * 5) where activity is U/min — gives mg/dL per 5-min tick.
        // predDev (ci) fades linearly to zero over 12 ticks (60 mins) — matches AutoISF IOB curve.
        val ticks = predictionHorizonMins / 5
        val predictions  = mutableListOf<Double>()
        var bg           = currentBg
        for (tick in 1..ticks) {
            val activity   = getActivityAtMinute(tick * 5, iobArray, learnedProfile)
            val iobDelta   = -(activity * isfMgdl * 5.0)
            val predDev    = ci * (1.0 - minOf(1.0, (tick - 1) / (60.0 / 5.0)))
            bg += iobDelta + predDev
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
        private const val DELTA_RISING_THRESHOLD_MGDL_PER_5MIN  = 0.5   // delta above this = "rising" trigger
        private const val FALLING_FAST_MGDL_PER_5MIN            = 2.0   // suspend early if falling faster than this
        private const val SMB_DELIVERY_FRACTION                  = 0.5   // deliver 50% of effective gap per cycle
        private const val TBR_WINDOW_HOURS                       = 0.5   // spread remaining correction over 30 mins
        private const val REBOUND_TAPER_MINS                     = 60.0  // ramp back to full dosing over 60 mins post-suspend
        private const val REBOUND_SMB_GATE                       = 0.5   // block SMBs until 50% through rebound window
        private const val LN2                             = 0.693147
    }
}