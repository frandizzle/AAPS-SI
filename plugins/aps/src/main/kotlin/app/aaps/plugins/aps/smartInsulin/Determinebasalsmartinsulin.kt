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
        // Pipe-separated compact format — each key piece separated by " | "
        sb.append("SI mode=${mealMode.label}")
        sb.append(" | BG=${fmt(currentBg, isMmol)}")
        sb.append(" | d=${fmt(delta, isMmol)}")
        sb.append(" | IOB=${"%.2f".format(Locale.US, currentIob)}/${"%.0f".format(Locale.US, oapsProfile.max_iob)}")
        sb.append(" | pred_min=${fmt(predictedMinSafety, isMmol)} lo=${fmt(lowGuardMgdl, isMmol)} warn=${fmt(warnGuardMgdl, isMmol)}")
        sb.append(" | target=${fmt(targetBg, isMmol)}${if (isTempTarget) "(tmp)" else ""}")
        sb.append(" | ISF=${fmt(dosingIsfMgdl, isMmol)}")
        sb.append(" | basal=${"%.3f".format(Locale.US, profileBasal)}(x${"%.2f".format(Locale.US, basalMultiplier)})")
        // PK label — "Peak" while still using profile defaults, "Learned pk" once enough samples accumulated
        val pkLabel = if (kinetics.sampleCount < PK_LEARNING_MIN_SAMPLES) "Peak" else "Learned pk"
        sb.append(" | ${pkLabel}=${kinetics.peakMinutes.toInt()}m DIA=${kinetics.diaMinutes.toInt()}m")
        if (mealMode != MealMode.FASTING) sb.append(" | food_abs=${carbAbsorption.absorptionMinutes.toInt()}m")
        sb.append(" | aggr=${"%.2f".format(Locale.US, aggressiveness)}")
        if (inDawnWindow) sb.append(" | dawn(-${"%.0f".format(Locale.US, dawnSmbReduction * 100)}%)")
        if (inReboundWindow) {
            val reboundMinsLeft = (reboundWindowMins - reboundMins).coerceAtLeast(0.0)
            sb.append(" | rebound(${reboundMins.toInt()}min left=${reboundMinsLeft.toInt()}min taper=${"%.2f".format(Locale.US, reboundTaperFraction)})")
        } else if (bgWentLow) {
            sb.append(" | rebound=watching")
        }
        if (activityLevel != ActivityMonitor.ActivityLevel.SEDENTARY)
            sb.append(" | activity=${activityLevel.label}(+${if (isMmol) "%.1f".format(Locale.US, activityTargetOffsetMmol) else "%.0f".format(Locale.US, activityOffsetMgdl)}${if (isMmol) "mmol" else "mg/dL"})")
        if (cgmWarmupReason.isNotEmpty()) sb.append(" | $cgmWarmupReason")
        sb.append(" | $tirSummary")

        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()
        val fallingFast    = delta < -FALLING_FAST_MGDL_PER_5MIN

        var smbOut = 0.0
        when {
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                sb.append(" | LGS_SUSPEND | BG=${fmt(currentBg, isMmol)} < lgs=${fmt(lgsThresholdMgdl, isMmol)}")
                setTempBasal(0.0, 30, oapsProfile, rT, currentTemp)
            }
            predictedMinSafety < lowGuardMgdl -> {
                sb.append(" | LOW_SUSPEND | pred_min=${fmt(predictedMinSafety, isMmol)} < low=${fmt(lowGuardMgdl, isMmol)}")
                setTempBasal(0.0, 30, oapsProfile, rT, currentTemp)
            }
            else -> {
                val smbAllowed = microBolusAllowed && !isTempTarget && bgAboveGuard > 0.0 && insulinReq > 0.0
                val correctionUnits = if (smbAllowed) {
                    insulinReq * (uamSmbFraction * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction
                } else 0.0

                val bolusStep      = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
                val rawSmb         = if (correctionUnits > 0.0) (Math.ceil(correctionUnits / bolusStep) * bolusStep) else 0.0
                val smbCap         = minOf(maxSmbU, iobHeadroom)
                val clampedSmb     = rawSmb.coerceAtMost(smbCap)
                val constrainedSmb = if (clampedSmb >= bolusStep) clampedSmb else 0.0

                // TBR correction — independent of smbAllowed. When SMBs are capped,
                // TBR picks up the slack by running a high temp basal. The remaining
                // undelivered correction is spread over TBR_WINDOW_HOURS (30 min).
                val tbrCorrectionU = if (iobOk && insulinReq > 0.0) insulinReq * aggressiveness else 0.0
                val remainingU     = (tbrCorrectionU - constrainedSmb).coerceAtLeast(0.0)

                val tbrRateRaw = when {
                    !iobOk           -> 0.0
                    remainingU > 0.0 -> (profileBasal + remainingU / TBR_WINDOW_HOURS)
                        .coerceAtMost(oapsProfile.max_basal)
                        .coerceAtMost(maxTbrU)
                    // Target respect: reduce basal when pred_min is below target
                    predictedMin < targetBg &&
                        (targetRespectEnabled || targetBg > (6.0 * MMOL_TO_MGDL)) -> {
                        val missingBgMgdl   = targetBg - predictedMin
                        val missingInsulinU = missingBgMgdl / dosingIsfMgdl
                        val reducedBasal    = profileBasal - (missingInsulinU / TBR_WINDOW_HOURS)
                        reducedBasal.coerceIn(0.0, profileBasal)
                    }
                    else -> profileBasal
                }

                val tbrRate = tbrRateRaw * reboundTaperFraction
                val reboundSmbAllowed = reboundTaperFraction >= REBOUND_SMB_GATE
                val finalSmb = if (reboundSmbAllowed) constrainedSmb else 0.0
                smbOut = finalSmb

                val trigger = when {
                    !smbAllowed && isTempTarget        -> "tempTarget"
                    !smbAllowed && !microBolusAllowed  -> "SMB disabled"
                    !smbAllowed && bgAboveGuard <= 0.0 -> "bg<=lowGuard"
                    !smbAllowed && insulinReq <= 0.0   -> "noInsulinReq"
                    !iobOk -> "maxIOB(${"%.2f".format(Locale.US, currentIob)}/${"%.2f".format(Locale.US, oapsProfile.max_iob)})"
                    else -> "predMinGap(${fmt(predictedMin, isMmol)}->${fmt(targetBg, isMmol)})"
                }
                val smbCapNote = if (rawSmb > finalSmb && finalSmb > 0.0)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, capped at ${"%.2f".format(Locale.US, smbCap)}U)"
                else if (rawSmb > 0.0 && finalSmb == 0.0 && !reboundSmbAllowed)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, blocked: rebound)"
                else ""

                sb.append(" | NORMAL")
                sb.append(" | targetBG=${fmt(targetBg, isMmol)}")
                sb.append(" | microBolus=$microBolusAllowed")
                sb.append(" | trigger=$trigger")
                sb.append(" | SMB final: ${"%.2f".format(Locale.US, finalSmb)}U$smbCapNote")
                sb.append(" | tbr=${"%.3f".format(Locale.US, tbrRate)}")

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
        val ticks        = kinetics.diaMinutes.toInt() / 5

        // ── Bi-Phasic Carb Model ─────────────────────────────────────────────
        // Problem: When IOB is very high (e.g. 14U), BGI is strongly negative.
        // ci = delta - bgi = small_delta - (large_negative) = very_large_positive.
        // Applying that large ci directly across hours causes "moonshot" predictions.
        //
        // Solution: Split ci into two components:
        //   1. Transient spike: the raw ci decayed over 60 min — represents the current
        //      acute carb absorption signal. Fades quickly so it doesn't compound.
        //   2. Sustained plateau: ci scaled DOWN by (60/duration) — represents the slow
        //      ongoing glucose release from protein/fat. This is intentionally conservative
        //      to prevent the IOB-inflated ci from projecting unrealistically far forward.
        //
        // Safety: ci is also clamped to CI_MAX_MGDL_PER_TICK regardless, preventing
        // sensor noise or extreme IOB-inflation from creating wild projections.
        val carbDuration = if (mealMode == MealMode.FASTING) 60.0 else carbAbs.absorptionMinutes
        val carbPeakRaw  = if (mealMode == MealMode.FASTING) 15.0 else carbAbs.peakMinutes
        val useLearnedPeak = carbAbs.sampleCount >= CARB_PEAK_MIN_SAMPLES
        val safePeak = if (useLearnedPeak) {
            carbPeakRaw.coerceIn(carbDuration * CARB_PEAK_MIN_FRAC, carbDuration * CARB_PEAK_MAX_FRAC)
        } else {
            carbDuration * CARB_PEAK_DEFAULT_FRAC
        }
        // Clamp ci to a physiologically-plausible maximum before splitting.
        // 5 mmol/5min = 90 mg/dL/5min is roughly the fastest observed gastric emptying.
        val ciClamped    = ci.coerceIn(-CI_MAX_MGDL_PER_TICK, CI_MAX_MGDL_PER_TICK)
        // Plateau component: scaled conservatively by time-stretch factor
        val plateauCi    = ciClamped * (60.0 / carbDuration).coerceAtMost(1.0)
        // Transient component: remainder — the "right now" signal, fades in 60 min
        val transientCi  = ciClamped - plateauCi

        // ── Insulin activity model ───────────────────────────────────────────
        // Use iobArray for near-term (most accurate), then synthetic triangular tail.
        val currentIob       = iobArray.firstOrNull()?.iob ?: 0.0
        val peakMins         = kinetics.peakMinutes.coerceIn(30.0, kinetics.diaMinutes * 0.4)
        val diaMins          = kinetics.diaMinutes
        val peakActivityPerMin = if (diaMins > 0) 2.0 * currentIob / diaMins else 0.0
        val useArrayUntilTick = minOf(iobArray.size, MODELED_TAIL_START_TICKS)

        for (tick in 1..ticks) {
            val minutes = tick * 5

            // ---------- INSULIN ACTIVITY ----------
            val activity = if (tick - 1 < useArrayUntilTick) {
                iobArray[tick - 1].activity
            } else {
                val shapeFraction = when {
                    minutes >= diaMins  -> 0.0
                    minutes <= peakMins -> minutes / peakMins
                    else                -> (diaMins - minutes) / (diaMins - peakMins)
                }
                peakActivityPerMin * shapeFraction
            }
            val iobDelta = -(activity * isfMgdl * 5.0)

            // ---------- CARB IMPACT (bi-phasic) ----------
            // Transient: decays completely by 60 min using shapeFactor
            val transientFade  = when {
                minutes >= carbDuration || minutes >= 60.0 -> 0.0
                minutes <= safePeak.coerceAtMost(60.0) -> 1.0
                else -> ((60.0 - minutes) / (60.0 - safePeak.coerceAtMost(60.0))).coerceAtLeast(0.0)
            }
            // Plateau: persists using full trapezoid shape across carbDuration
            val plateauFade = when {
                minutes >= carbDuration -> 0.0
                minutes <= safePeak     -> 1.0
                else -> ((carbDuration - minutes) / (carbDuration - safePeak)).coerceAtLeast(0.0)
            }
            val carbDelta = (transientCi * transientFade) + (plateauCi * plateauFade)

            bg += iobDelta + carbDelta
            predictions.add(bg)
        }
        return predictions
    }

    companion object {
        private const val MMOL_TO_MGDL               = 18.0
        private const val REBOUND_SMB_GATE           = 0.825
        private const val FALLING_FAST_MGDL_PER_5MIN = 2.0 * MMOL_TO_MGDL / 5.0
        private const val NEUTRAL_TEMP_EPSILON        = 1e-6
        /** TBR window in hours — remaining correction is spread over this window as extra basal.
         *  0.5 = 30 min, matching the TBR duration set by setTempBasal(). */
        private const val TBR_WINDOW_HOURS            = 0.5

        /** Insulin kinetics sample count below which the label in the debug string reads "Peak"
         *  (i.e. still running on profile defaults); at/above, reads "Learned pk". Purely cosmetic. */
        private const val PK_LEARNING_MIN_SAMPLES = 3

        // ── Trapezoid carb absorption model constants ──
        // These define the "plateau" shape: ci stays at 100% from t=0 until safePeak,
        // then tapers linearly to 0 at carbDuration.
        // A higher CARB_PEAK_DEFAULT_FRAC = longer plateau before taper starts.
        // For protein/fat-heavy meals (4-6h absorption): plateau should hold for ~65% of duration
        // before tapering, matching the physiology of slow gastric emptying.
        /** Minimum sample count in LearnedCarbAbsorption before using learned peakMinutes. */
        private const val CARB_PEAK_MIN_SAMPLES     = 3
        /** Clamp learned peak to at least this fraction of absorptionMinutes. */
        private const val CARB_PEAK_MIN_FRAC         = 0.25
        /** Clamp learned peak to at most this fraction of absorptionMinutes. */
        private const val CARB_PEAK_MAX_FRAC         = 0.80
        /** Default peak-to-duration ratio — 0.65 means plateau for 65% of duration,
         *  then taper. For 300m duration: plateau holds to 195m, tapers to 0 at 300m. */
        private const val CARB_PEAK_DEFAULT_FRAC     = 0.65
        /** After this many ticks, switch from AAPS's iobArray to the modeled triangular
         *  activity curve for insulin tail. 12 ticks = 60 min. */
        private const val MODELED_TAIL_START_TICKS   = 12
        /** Maximum ci per 5-min tick in mg/dL. Prevents high-IOB BGI inflation from creating
         *  moonshot predictions. 90 mg/dL/tick = 5 mmol/tick — physiological upper bound
         *  for fastest possible gastric emptying. */
        private const val CI_MAX_MGDL_PER_TICK        = 90.0
    }
}