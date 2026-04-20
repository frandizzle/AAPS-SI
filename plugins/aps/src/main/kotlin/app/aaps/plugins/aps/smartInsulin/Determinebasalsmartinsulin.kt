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
                val clampedSmb     = (Math.ceil(correctionUnits / bolusStep) * bolusStep).coerceAtMost(minOf(maxSmbU, iobHeadroom))
                val constrainedSmb = if (clampedSmb >= bolusStep) clampedSmb else 0.0

                val reducedBasal = if (predictedMin < targetBg) {
                    (profileBasal - (targetBg - predictedMin) / dosingIsfMgdl / 0.5).coerceIn(0.0, profileBasal)
                } else profileBasal

                val tbrRate = reducedBasal * reboundTaperFraction
                val reboundSmbAllowed = reboundTaperFraction >= REBOUND_SMB_GATE
                smbOut = if (reboundSmbAllowed) constrainedSmb else 0.0

                // Build trigger string describing why this SMB was (or wasn't) sized as it was
                val trigger = when {
                    !smbAllowed && isTempTarget        -> "tempTarget"
                    !smbAllowed && !microBolusAllowed  -> "SMB disabled"
                    !smbAllowed && bgAboveGuard <= 0.0 -> "bg<=lowGuard"
                    !smbAllowed && insulinReq <= 0.0   -> "noInsulinReq"
                    else -> "predMinGap(${fmt(predictedMin, isMmol)}->${fmt(targetBg, isMmol)})"
                }
                val smbCapNote = if (correctionUnits > 0.0 && smbOut < correctionUnits && reboundSmbAllowed)
                    " (wanted ${"%.2f".format(Locale.US, correctionUnits)}U, capped at ${"%.2f".format(Locale.US, smbOut)}U)"
                else if (correctionUnits > 0.0 && smbOut == 0.0 && !reboundSmbAllowed)
                    " (wanted ${"%.2f".format(Locale.US, correctionUnits)}U, blocked: rebound)"
                else ""

                sb.append(" | NORMAL")
                sb.append(" | targetBG=${fmt(targetBg, isMmol)}")
                sb.append(" | microBolus=$microBolusAllowed")
                sb.append(" | trigger=$trigger")
                sb.append(" | SMB final: ${"%.2f".format(Locale.US, smbOut)}U$smbCapNote")
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
        // Predict forward for the duration of insulin action
        val ticks = kinetics.diaMinutes.toInt() / 5

        for (tick in 1..ticks) {
            val minutes = tick * 5

            // ---------- INSULIN ACTIVITY (with exponential tail) ----------
            val activity = if (tick - 1 < iobArray.size) {
                iobArray[tick - 1].activity
            } else {
                // Fallback: exponential decay beyond the iobArray
                val lastActivity = iobArray.lastOrNull()?.activity ?: 0.0
                val extraTicks = (tick - 1) - iobArray.size + 1
                max(0.0, lastActivity * Math.exp(-extraTicks * 0.05))
            }
            val iobDelta = -(activity * isfMgdl * 5.0)

            // ---------- CARB IMPACT (right-triangle with learned peak) ----------
            // ci = currently observed carb impact rate (mg/dL per 5-min tick).
            // It's measured from THIS cycle's actual rise. To project forward, we model
            // how that rate will evolve as carb absorption progresses.
            //
            // Shape model: a triangle anchored at carbAbs.peakMinutes
            //   - From t=0 (now) to peak: rate stays at ci (we're somewhere in the rising
            //     or peak phase — the loop can't tell exactly where)
            //   - From peak to duration: rate declines linearly from ci → 0
            //
            // Why not use AUC preservation? `ci` is already a rate, not an integral. Dividing
            // it by `(60/duration)` (as a previous version did) artificially shrinks the
            // current observed rate to "spread it over a longer window" — but the rate IS
            // the rate. If you're actively rising at +15 mg/dL/5min right now, that's the
            // current rate; how long it persists is a question of the curve shape, not its
            // height. Shrinking it would predict a flat BG curve while you're actively rising,
            // which is wrong.
            //
            // ── Safety mitigations (gate learned peak against mis-learned values) ──
            // 1. Require minimum sample count before trusting learned peak
            // 2. Clamp peak to a sensible fraction of duration (0.15 .. 0.5)
            val carbDuration = if (mealMode == MealMode.FASTING) 60.0 else carbAbs.absorptionMinutes
            val carbPeakRaw  = if (mealMode == MealMode.FASTING) 15.0 else carbAbs.peakMinutes
            val useLearnedPeak = carbAbs.sampleCount >= CARB_PEAK_MIN_SAMPLES
            val safePeak = if (useLearnedPeak) {
                carbPeakRaw.coerceIn(carbDuration * CARB_PEAK_MIN_FRAC, carbDuration * CARB_PEAK_MAX_FRAC)
            } else {
                carbDuration * CARB_PEAK_DEFAULT_FRAC  // safe default if learning not yet trusted
            }

            // Shape factor: 1.0 from now until peak, then tapers linearly to 0 at duration.
            // ci itself supplies the magnitude — shape only governs how it evolves over time.
            val shapeFactor = when {
                minutes >= carbDuration -> 0.0
                minutes <= safePeak     -> 1.0   // sustained at current observed rate through peak
                else -> {
                    // Linear taper from 1.0 at safePeak to 0.0 at carbDuration
                    ((carbDuration - minutes) / (carbDuration - safePeak)).coerceAtLeast(0.0)
                }
            }
            val carbDelta = ci * shapeFactor

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

        /** Insulin kinetics sample count below which the label in the debug string reads "Peak"
         *  (i.e. still running on profile defaults); at/above, reads "Learned pk". Purely cosmetic. */
        private const val PK_LEARNING_MIN_SAMPLES = 3

        // ── Right-triangle carb absorption model constants ──
        // Peak-at-t=0 flat triangle was replaced with a right-triangle anchored at
        // carbAbs.peakMinutes. These constants gate the learned peak against mis-learned values.
        /** Minimum sample count in LearnedCarbAbsorption before using learned peakMinutes.
         *  Below this, fall back to CARB_PEAK_DEFAULT_FRAC of duration. */
        private const val CARB_PEAK_MIN_SAMPLES = 3
        /** Clamp learned peak to at least this fraction of absorptionMinutes (prevents
         *  peak-too-early which would cause early-phase insulin stacking). */
        private const val CARB_PEAK_MIN_FRAC    = 0.15
        /** Clamp learned peak to at most this fraction of absorptionMinutes (prevents
         *  peak-too-late which would make the model too similar to a flat distribution). */
        private const val CARB_PEAK_MAX_FRAC    = 0.50
        /** Default peak-to-duration ratio when sample count is too low to trust learned peak.
         *  0.33 matches the default peak (60) / duration (180). */
        private const val CARB_PEAK_DEFAULT_FRAC = 0.33
    }
}