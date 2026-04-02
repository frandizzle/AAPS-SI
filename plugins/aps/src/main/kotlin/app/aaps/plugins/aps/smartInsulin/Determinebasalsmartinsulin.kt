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
import kotlin.math.max
import kotlin.math.min

/**
 * SmartInsulin APS — core basal/SMB determination.
 *
 * ## BG Prediction
 * Projects glucose forward using:
 *   - IOB activity curve from iobArray (U/min * ISF = mg/dL/min drop)
 *   - Delta momentum (ci) from shortAvgDelta, fading linearly to zero by t=60 min
 *   - Exponential tail extrapolation beyond iobArray length
 *
 * ## predictedMin
 * Only tracked after the first 90 minutes (insulinPeakTicks) of the curve.
 * This mirrors stock AAPS behaviour — the early trough (while insulin is still
 * peaking) is not used for suspend decisions, preventing the "bucket load or
 * nothing" problem where pred30/pred60 are fine but pred_min hits the floor.
 *
 * ## SMB / Basal gating
 *   - LGS threshold active: hard zero temp (LGS_SUSPEND)
 *   - predictedMin < lowGuard  → SUSPEND: zero TBR, block SMBs
 *   - predictedMin < warnGuard → CAUTION: scaled TBR, block SMBs
 *   - predictedMin >= warnGuard → NORMAL: profile basal, SMBs allowed
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
        if (profile.skip_neutral_temps && r == profile.current_basal) {
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
        learnedProfile:           LearnedInsulinProfile,
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
        var rT = RT(
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

        // Target with activity offset
        val activityOffsetMgdl = activityTargetOffsetMmol * MMOL_TO_MGDL
        val targetBg = if (isTempTarget) oapsProfile.target_bg
        else oapsProfile.target_bg + activityOffsetMgdl
        val profileBasal = oapsProfile.current_basal * basalMultiplier

        val highTempTargetActive = isTempTarget && targetBg > profileTargetMgdl

        // Dawn window
        val cal = java.util.Calendar.getInstance().also { it.timeInMillis = currentTime }
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val inDawnWindow = mealMode == MealMode.FASTING && glucoseStatus.delta > 0 && run {
            if (dawnWindowStartHour <= dawnWindowEndHour)
                currentHour in dawnWindowStartHour until dawnWindowEndHour
            else currentHour >= dawnWindowStartHour || currentHour < dawnWindowEndHour
        }
        val dawnFraction = if (inDawnWindow) dawnSmbReduction else 1.0
        val cgmFraction  = if (!cgmDeltaPlausible) 0.0 else cgmSmbFraction

        // Rebound taper — only applies during the active rebound window (BG crossed back above
        // lowGuard after a real low). Starts at 30% and tapers linearly back to 100% over
        // reboundWindowMins (configurable, default 60). Outside the window taper is always 1.0.
        val reboundMins = if (inReboundWindow) (msSinceLastSuspend / 60_000.0) else 0.0
        val rawTaper = (reboundMins / reboundWindowMins).coerceIn(0.0, 1.0)
        val reboundTaperFraction = if (inReboundWindow) 0.3 + (0.7 * rawTaper) else 1.0

        // Guard thresholds in mg/dL
        val lowGuardMgdl  = lowGuardMmol * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        // ── Build prediction curve ────────────────────────────────────────────
        // ci = observed delta minus expected BGI — positive means carbs/UAM pushing BG up
        val bgi = -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0)
        val ci  = min(glucoseStatus.shortAvgDelta, glucoseStatus.delta) - bgi

        val predictedBg = predictBgCurve(
            startBg       = currentBg,
            ci            = ci,
            iobArray      = iobArray,
            isfMgdl       = dosingIsfMgdl,
            learnedProfile = learnedProfile,
            ticks         = learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / 5
        )

        // predictedMin: only look after insulin peak (plus a 10 min buffer) to avoid
        // suspending on the early trough while insulin is still peaking. The +2 tick
        // buffer prevents a single noisy trough right at the peak boundary from driving
        // a suspend decision — fallingIntoLow and LGS cover that window anyway.
        // Seeded from activeInsulin.peak + profile DIA at n=0, transitions to learned
        // values once sampleCount >= PEAK_LEARNING_MIN_SAMPLES.
        // Hard rails ensure corrupt learned values can never cause unsafe behaviour.
        val insulinPeakTicks = run {
            val ticks = (learnedProfile.safePeakMinutes / 5.0).toInt() + 2  // +2 ticks = +10 min buffer
            if (learnedProfile.sampleCount < PEAK_LEARNING_MIN_SAMPLES)
                ticks.coerceIn(10, 18)  // seeded from real insulin, capped at 90 min until trusted
            else
                ticks.coerceIn(10, 24)  // hard rails: 50–120 min once learned
        }
        // Full-curve min for SAFETY — suspends if BG predicted below lowGuard at any point
        val predictedMinSafety = predictedBg.minOrNull() ?: currentBg
        // Post-peak min for DOSING — avoids suppressing SMBs on early descending curve
        val predictedMin = if (predictedBg.size > insulinPeakTicks)
            predictedBg.drop(insulinPeakTicks).minOrNull() ?: currentBg
        else
            predictedBg.minOrNull() ?: currentBg

        val predictedAt30 = if (predictedBg.size > 5)  predictedBg[5]  else predictedBg.lastOrNull() ?: currentBg
        val predictedAt60 = if (predictedBg.size > 11) predictedBg[11] else predictedBg.lastOrNull() ?: currentBg

        // Populate rT.predBGs.IOB for the overview prediction graph
        val rawPrediction = mutableListOf<Int>()
        predictedBg.take(learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / 5)
            .forEach { rawPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
        rT.predBGs = app.aaps.core.interfaces.aps.Predictions()
        rT.predBGs?.IOB = rawPrediction

        // ── IOB / headroom ────────────────────────────────────────────────────
        val iobHeadroom  = (oapsProfile.max_iob - currentIob).coerceAtLeast(0.0)
        val iobOk        = currentIob < oapsProfile.max_iob
        val bgAboveGuard = currentBg - lowGuardMgdl

        // Stock OpenAPS-style insulinReq: how much insulin is needed to bring
        // predictedMin to target. predictedMin already has existing IOB baked in,
        // so this naturally self-limits — no iobSufficient gate needed.
        val predMinGapMgdl = (predictedMin - targetBg).coerceAtLeast(0.0)
        val insulinReq     = predMinGapMgdl / dosingIsfMgdl

        // ── Reason string header ──────────────────────────────────────────────
        val sb = StringBuilder()
        // Pipe-separated compact format — each key piece separated by " | "
        sb.append("SI mode=${mealMode.label}")
        sb.append(" | BG=${fmt(currentBg, isMmol)}")
        sb.append(" | d=${fmt(delta, isMmol)}")
        sb.append(" | IOB=${"%.2f".format(Locale.US, currentIob)}/${"%.0f".format(Locale.US, oapsProfile.max_iob)}")
        sb.append(" | pred_min=${fmt(predictedMinSafety, isMmol)} lo=${fmt(lowGuardMgdl, isMmol)} warn=${fmt(warnGuardMgdl, isMmol)}")
        sb.append(" | target=${fmt(targetBg, isMmol)}${if (isTempTarget) "(tmp)" else ""}")
        sb.append(" | ISF=${fmt(dosingIsfMgdl, isMmol)}")
        sb.append(" | basal=${"%.3f".format(Locale.US, profileBasal)}(x${"%.2f".format(Locale.US, basalMultiplier)})")
        val pkLabel = if (learnedProfile.sampleCount < PEAK_LEARNING_MIN_SAMPLES) "Peak" else "Learned pk"
        sb.append(" | ${pkLabel}=${learnedProfile.safePeakMinutes.toInt()}m DIA=${learnedProfile.safeDiaMinutes.toInt()}m")
        sb.append(" | aggr=${"%.2f".format(Locale.US, aggressiveness)}")
        if (inDawnWindow) sb.append(" | dawn(-${"%.0f".format(Locale.US, dawnSmbReduction * 100)}%)")
        if (highTempTargetActive) sb.append(" | highTT=smbOff")
        if (inReboundWindow) {
            val reboundMinsLeft = (reboundWindowMins - reboundMins).coerceAtLeast(0.0)
            sb.append(" | rebound(${reboundMins.toInt()}min left=${reboundMinsLeft.toInt()}min taper=${"%.2f".format(Locale.US, reboundTaperFraction)})")
        } else if (bgWentLow) {
            sb.append(" | rebound=watching")
        }
        if (activityLevel != ActivityMonitor.ActivityLevel.SEDENTARY)
            sb.append(" | activity=${activityLevel.label}(+${if (isMmol) "%.1f".format(activityTargetOffsetMmol) else "%.0f".format(activityOffsetMgdl)}${if (isMmol) "mmol" else "mg/dL"})")
        if (cgmWarmupReason.isNotEmpty()) sb.append(" | $cgmWarmupReason")
        sb.append(" | $tirSummary")

        // ── Dynamic zero-temp duration ────────────────────────────────────────
        // How long should a zero temp run if the loop goes offline?
        // Uses AutoISF-style math: bgUndershoot / ISF / basal → hours → minutes
        // Rounded to nearest 30 min, clamped 30–90 min.
        // This ensures a suspend set now will protect for long enough even if
        // the loop doesn't run again for 60+ minutes (connectivity loss, etc).
        fun suspendDurationMins(worstBgMgdl: Double): Int {
            val bgUndershoot    = targetBg - worstBgMgdl  // how far below target worst case goes
            val insulinReqU     = bgUndershoot / dosingIsfMgdl
            val durationHours   = insulinReqU / profileBasal
            val durationMins    = (durationHours * 60.0).coerceIn(30.0, 90.0)
            return (Math.round(durationMins / 30.0) * 30).toInt().coerceIn(30, 90)
        }


        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()

        val fallingFast    = delta < -FALLING_FAST_MGDL_PER_5MIN
        val fallingIntoLow = fallingFast && predictedAt30 < warnGuardMgdl

        var smbOut = 0.0

        when {
            // ── LGS hard suspend ─────────────────────────────────────────────
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                sb.append(" | LGS_SUSPEND | BG=${fmt(currentBg, isMmol)} < lgs=${fmt(lgsThresholdMgdl, isMmol)}")
                setTempBasal(0.0, suspendDurationMins(currentBg), oapsProfile, rT, currentTemp)
            }

            // ── Predictive suspend ───────────────────────────────────────────
            predictedMinSafety < lowGuardMgdl || fallingIntoLow -> {
                val worstBg = if (fallingIntoLow) predictedAt30 else predictedMinSafety
                val suspendMins = suspendDurationMins(worstBg)
                val reason = when {
                    fallingIntoLow -> "SUSPEND fallingIntoLow pred30=${fmt(predictedAt30, isMmol)} delta=${String.format(Locale.US, "%.1f", delta)} dur=${suspendMins}m"
                    else           -> "SUSPEND pred_min=${fmt(predictedMinSafety, isMmol)} < lowGuard=${fmt(lowGuardMgdl, isMmol)} dur=${suspendMins}m"
                }
                sb.append(" | $reason")
                setTempBasal(0.0, suspendMins, oapsProfile, rT, currentTemp)
            }

            // ── Caution zone ─────────────────────────────────────────────────
            predictedMinSafety < warnGuardMgdl -> {
                val guardGap   = warnGuardMgdl - predictedMinSafety
                val warnFrac   = 1.0 - (guardGap / (warnGuardMgdl - lowGuardMgdl)).coerceIn(0.0, 1.0)
                val cautionTbr = (profileBasal * warnFrac).coerceAtMost(profileBasal)
                sb.append(" | CAUTION | pred_min=${fmt(predictedMinSafety, isMmol)} | warnGuard=${fmt(warnGuardMgdl, isMmol)} | tbrFrac=${"%.2f".format(Locale.US, warnFrac)} | tbr=${"%.3f".format(Locale.US, cautionTbr)}")
                setTempBasal(cautionTbr * reboundTaperFraction, 30, oapsProfile, rT, currentTemp)
            }

            // ── Normal dosing ─────────────────────────────────────────────────
            else -> {
                // Stock-style: SMB = insulinReq/2, scaled by aggressiveness/dawn/cgm.
                // insulinReq is based on predictedMin gap to target — self-limits as
                // prediction curve drops. No iobSufficient gate; existing IOB is already
                // baked into predictedMin so there's no double-dosing risk.
                val smbAllowed = microBolusAllowed &&
                    !highTempTargetActive &&
                    bgAboveGuard > 0.0 &&
                    insulinReq > 0.0

                val correctionUnits = if (smbAllowed) {
                    // The plugin passes exactly the right fraction for this cycle —
                    // either the UAM entry fraction or SMB_DELIVERY_FRACTION (0.5).
                    // Use it directly. coerceIn(0.1, 0.9) is the OpenAPS safety cap.
                    insulinReq * (uamSmbFraction * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction
                } else 0.0

                val bolusStep      = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
                val rawSmb         = if (correctionUnits > 0.0) (Math.ceil(correctionUnits / bolusStep) * bolusStep) else 0.0
                val smbCap         = minOf(maxSmbU, iobHeadroom)
                val clampedSmb     = rawSmb.coerceAtMost(smbCap)
                val constrainedSmb = if (clampedSmb >= bolusStep) clampedSmb else 0.0

                // TBR correction is independent of smbAllowed — high temp target blocks
                // SMBs but still needs elevated TBR to bring predMin to the temp target.
                // insulinReq is already computed against targetBg (which IS the temp target
                // when active), so TBR naturally aims for 6.5 not 5.5.
                val tbrCorrectionU  = if (iobOk && insulinReq > 0.0) insulinReq * aggressiveness else 0.0
                val remainingU      = (tbrCorrectionU - constrainedSmb).coerceAtLeast(0.0)

                val tbrRateRaw = when {
                    !iobOk           -> 0.0
                    remainingU > 0.0 -> (profileBasal + remainingU / TBR_WINDOW_HOURS)
                        .coerceAtMost(oapsProfile.max_basal)
                        .coerceAtMost(maxTbrU)
                    // Target respect: reduce basal when pred_min is below target.
                    // Gate: only fires for targets > 6.0 mmol normally, OR always if switch is on.
                    // Uses ISF math so small gaps → tiny reduction, large gaps → zero basal.
                    predictedMin < targetBg &&
                        (targetRespectEnabled || targetBg > (6.0 * MMOL_TO_MGDL)) -> {
                        val missingBgMgdl   = targetBg - predictedMin
                        val missingInsulinU = missingBgMgdl / dosingIsfMgdl
                        val reducedBasal    = profileBasal - (missingInsulinU / TBR_WINDOW_HOURS)
                        reducedBasal.coerceIn(0.0, profileBasal)
                    }
                    else             -> profileBasal
                }
                val tbrRate = tbrRateRaw * reboundTaperFraction

                val reboundSmbAllowed = reboundTaperFraction >= REBOUND_SMB_GATE
                val finalSmb = if (reboundSmbAllowed) constrainedSmb else 0.0

                val trigger = when {
                    !iobOk      -> "maxIOB(${String.format(Locale.US, "%.2f", currentIob)}/${String.format(Locale.US, "%.2f", oapsProfile.max_iob)})"
                    !smbAllowed -> "blocked"
                    else        -> "predMinGap(${fmt(predictedMin, isMmol)}->${fmt(targetBg, isMmol)})"
                }

                val reboundStr = when {
                    inReboundWindow -> {
                        val minsLeft = (reboundWindowMins - reboundMins).coerceAtLeast(0.0)
                        val smbState = if (!reboundSmbAllowed) "smbBlocked" else "smbAllowed"
                        " rebound(elapsed=%.0fmin left=%.0fmin taper=%.2f %s tbrRaw=%.3f->%.3f)".format(
                            Locale.US, reboundMins, minsLeft, reboundTaperFraction, smbState, tbrRateRaw, tbrRate)
                    }
                    else -> ""
                }

                // Activity/CGM suffix
                val activityStr = when (activityLevel) {
                    ActivityMonitor.ActivityLevel.SEDENTARY -> ""
                    else -> " activity=${activityLevel.label}(+${if (isMmol) "%.1f".format(activityTargetOffsetMmol) else "%.0f".format(activityOffsetMgdl)}${if (isMmol) "mmol" else "mg/dL"})"
                }
                val cgmBlockStr = when {
                    !cgmDeltaPlausible    -> " cgm=smbBlocked(artefactDelta)"
                    cgmSmbFraction == 0.0 -> " cgm=smbBlocked(warmup<12h)"
                    cgmSmbFraction < 1.0  -> " cgm=smb×${(cgmSmbFraction * 100).toInt()}%(warmup)"
                    else                  -> ""
                }

                sb.append(" | NORMAL | targetBG=${fmt(targetBg, isMmol)} | microBolus=$microBolusAllowed | trigger=$trigger | smb=${"%.3f".format(Locale.US, finalSmb)} | tbr=${"%.3f".format(Locale.US, tbrRate)}$reboundStr$activityStr$cgmBlockStr")
                smbOut = finalSmb
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

    // ── Prediction curve ──────────────────────────────────────────────────────
    private fun predictBgCurve(
        startBg:        Double,
        ci:             Double,
        iobArray:       Array<IobTotal>,
        isfMgdl:        Double,
        learnedProfile: LearnedInsulinProfile,
        ticks:          Int
    ): List<Double> {
        var bg           = startBg
        val predictions  = mutableListOf<Double>()
        for (tick in 1..ticks) {
            val activity   = getActivityAtMinute(tick * 5, iobArray, learnedProfile)
            val iobDelta   = -(activity * isfMgdl * 5.0)
            val predDev    = ci * (1.0 - minOf(1.0, (tick - 1) / (60.0 / 5.0)))
            bg += iobDelta + predDev
            predictions.add(bg)
        }
        return predictions
    }

    private fun getActivityAtMinute(
        minutes:        Int,
        iobArray:       Array<IobTotal>,
        learnedProfile: LearnedInsulinProfile
    ): Double {
        val idx = minutes / 5
        if (idx < iobArray.size) return max(0.0, iobArray[idx].activity)
        val lastActivity = iobArray.lastOrNull()?.activity ?: return 0.0
        val extraTicks = idx - iobArray.size + 1
        return max(0.0, lastActivity * Math.exp(-extraTicks * 0.05))
    }

    companion object {
        private const val MMOL_TO_MGDL           = 18.0
        private const val SMB_DELIVERY_FRACTION  = 0.5
        private const val TBR_WINDOW_HOURS       = 0.5
        private const val REBOUND_WINDOW_MINS_DEFAULT = 60.0   // default, overridden by user setting
        private const val REBOUND_SMB_GATE       = 0.825 // SMBs unlock at 75% of window: taper=0.3+(0.7×0.75)=0.825
        private const val FALLING_FAST_MGDL_PER_5MIN = 2.0 * MMOL_TO_MGDL / 5.0
        private const val PEAK_LEARNING_MIN_SAMPLES  = 5
    }
}