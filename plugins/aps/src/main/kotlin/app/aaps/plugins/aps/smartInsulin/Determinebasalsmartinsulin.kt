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
        // Epsilon comparison — exact double equality is fragile against any upstream arithmetic
        // drift (e.g. rate=1.0000000001 would silently bypass neutral-temp skip, causing
        // redundant TBR commands to the pump).
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
        circCeil:                 Double = 1.0,
        fuelTrimStrength:         Double = 0.0,
        isMmol:                   Boolean = true,
        // ── PDP (Persistent Deviation Prediction) ─────────────────────────────────────
        pdpEnabled:               Boolean = false,   // master switch
        pdpCiStrength:            Double  = 1.0,     // effective ci multiplier (base * learned)
        pdpFadeMins:              Double  = 120.0,   // how long ci persists in secondary curve
        pdpBlendWeight:           Double  = 0.0,     // 0.0=primary only, 1.0=secondary only
        fastingMaxIobU:           Double  = 0.0,     // 0.0 = disabled (use global max IOB)
        pdpSyntheticCi:           Double  = 0.0,     // >0 = stuck-high pathway active
        pdpRisingStrength:        Double  = 1.0      // rising pathway ci scale (0.5-1.5, separate from stuck-high)
    ): APSResult {

        val result = apsResultProvider.get()
        val rT = RT(
            algorithm = APSResult.Algorithm.SI,
            runningDynamicIsf = false,
            timestamp = currentTime,
            consoleLog = mutableListOf(),
            consoleError = mutableListOf(),
            fuelTrim = fuelTrimStrength * 100.0
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
        // lowGuard after a real low). Starts at 40% and tapers linearly back to 100% over
        // reboundWindowMins (configurable, default 60). Outside the window taper is always 1.0.
        val reboundMins = if (inReboundWindow) (msSinceLastSuspend / 60_000.0) else 0.0
        val rawTaper = (reboundMins / reboundWindowMins).coerceIn(0.0, 1.0)
        val reboundTaperFraction = if (inReboundWindow) 0.3 + (0.7 * rawTaper) else 1.0

        // Guard thresholds in mg/dL
        val lowGuardMgdl  = lowGuardMmol * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        // When a high temp target is active, raise the effective suspend/caution thresholds
        // to the temp target. Without this, pred_min=6.9 with TT=7.5 falls through to normal
        // dosing and only gets a weak proportional basal reduction instead of a suspend.
        val effectiveSuspendMgdl = if (highTempTargetActive) targetBg else lowGuardMgdl
        val effectiveCautionMgdl = if (highTempTargetActive)
            targetBg + (warnGuardMgdl - lowGuardMgdl)
        else
            warnGuardMgdl

        val systemDiaMins = (profile.iCfg?.dia ?: 6.0) * 60.0

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
            ticks         = learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / 5,
            systemDiaMins  = systemDiaMins
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

        // ── PDP: secondary prediction curve ──────────────────────────────────
        // Only computed during FASTING when pdpEnabled and blendWeight > 0.
        // Uses the same IOB activity curve but ci fades over pdpFadeMins (default 120min)
        // instead of 60min, scaled by pdpCiStrength — models sustained unexplained deviation
        // (stress, dawn phenomenon, protein/fat tail, illness) persisting longer than normal.
        // Safety: pdpBlendWeight=0 when mealMode != FASTING so meal modes are never affected.
        val effectivePdpBlend = if (pdpEnabled && mealMode == app.aaps.core.interfaces.smartInsulin.MealMode.FASTING) pdpBlendWeight else 0.0

        val pdpPredictedBg: List<Double>
        val pdpPredMin: Double
        val pdpPredMinSafety: Double
        if (effectivePdpBlend > 0.0) {
            // pdpSyntheticCi > 0 means stuck-high pathway is active.
            // Rising pathway: ci persists longer, scaled by ciStrength.
            // Stuck pathway: IOB activity divided by ciStrength (insulin resistance model).
            val isStuckHigh    = pdpSyntheticCi > 0.0
            // Rising: use pdpRisingStrength (0.5-1.5), not pdpCiStrength (1-10)
            // This prevents a high ciStrength for stuck-high from making rises overly aggressive
            val pdpEffectiveCi = if (isStuckHigh) 0.0 else ci * pdpRisingStrength
            pdpPredictedBg = predictBgCurvePdp(
                startBg        = currentBg,
                ci             = pdpEffectiveCi,
                fadeMins       = pdpFadeMins,
                iobArray       = iobArray,
                isfMgdl        = dosingIsfMgdl,
                learnedProfile = learnedProfile,
                ticks          = learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / 5,
                systemDiaMins  = systemDiaMins,
                stuckHighMode  = isStuckHigh,
                ciStrength     = pdpCiStrength
            )
            pdpPredMinSafety = pdpPredictedBg.minOrNull() ?: currentBg
            val pdpPredMinRaw = if (pdpPredictedBg.size > insulinPeakTicks)
                pdpPredictedBg.drop(insulinPeakTicks).minOrNull() ?: currentBg
            else
                pdpPredictedBg.minOrNull() ?: currentBg

            // ── Stuck-high floor: enforce the resistance belief directly ──────────
            // Even with the scaled-total-drop curve, with large IOB the secondary
            // post-peak minimum can still land below target — the physics accumulate
            // over many ticks. This undermines the core belief of stuck-high: "BG
            // will only fall 1/ciStrength as far as IOB predicts."
            //
            // Floor: pdpPredMin >= currentBg - primaryPostPeakDrop / ciStrength
            //   primaryPostPeakDrop = currentBg - predictedMin  (primary curve post-peak)
            //   ciStrength=3  → secondary can fall at most 1/3 of what primary falls
            //   ciStrength=10 → secondary can fall at most 1/10 → stays near currentBg
            //
            // Safety curve (pdpPredMinSafety) is untouched — safety gates always use
            // the conservative primary-only prediction. Only the dosing minimum is floored.
            pdpPredMin = if (isStuckHigh) {
                val primaryPostPeakDrop = (currentBg - predictedMin).coerceAtLeast(0.0)
                val maxSecondaryDrop    = primaryPostPeakDrop / pdpCiStrength.coerceAtLeast(1.0)
                val flooredMin          = currentBg - maxSecondaryDrop
                maxOf(pdpPredMinRaw, flooredMin)
            } else {
                pdpPredMinRaw  // rising pathway: no floor, curve is already above primary
            }
        } else {
            pdpPredictedBg   = emptyList()
            pdpPredMin       = predictedMin
            pdpPredMinSafety = predictedMinSafety
        }

        // Blended post-peak predMin — used ONLY for insulinReq calculation.
        // Safety gates (SUSPEND, CAUTION) always use PRIMARY unblended predictedMinSafety.
        // Reason: PDP secondary curve predicts BG stays high due to resistance, but at
        // 90% blend the secondary full-curve minimum can dip below lowGuard (IOB still
        // pulls BG down eventually), triggering false suspends. The safety system must
        // always see the most conservative (primary IOB-physics) prediction.
        val blendedPredMin       = predictedMin * (1.0 - effectivePdpBlend) + pdpPredMin * effectivePdpBlend
        // Safety minimum: NEVER blended — always primary IOB prediction
        val blendedPredMinSafety = predictedMinSafety  // unchanged: primary only for all safety gates

        // Expose PDP prediction to next-cycle accuracy scoring in SmartInsulinPlugin.
        // These are the t+5min values (first tick) — compared against actual BG next cycle.
        val pdpPredAt5  = pdpPredictedBg.firstOrNull() ?: currentBg
        val iobPredAt5  = predictedBg.firstOrNull() ?: currentBg

        // Populate rT.predBGs.IOB for the overview prediction graph
        val rawPrediction = mutableListOf<Int>()
        predictedBg.take(learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / 5)
            .forEach { rawPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
        rT.predBGs = app.aaps.core.interfaces.aps.Predictions()
        rT.predBGs?.IOB = rawPrediction

        // PDP curve — UAM slot renders as orange/yellow in AAPS overview graph,
        // clearly distinct from the primary IOB cyan line.
        // Also populate ZT as fallback in case enableUAM is false in OapsProfile.
        if (effectivePdpBlend > 0.0 && pdpPredictedBg.isNotEmpty()) {
            val rawPdpPrediction = mutableListOf<Int>()
            pdpPredictedBg.take(learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / 5)
                .forEach { rawPdpPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
            // UAM = orange line (visually distinct from cyan IOB line)
            rT.predBGs?.UAM = rawPdpPrediction
            // ZT fallback removed — ZT is cyan like IOB, defeats the purpose of distinction
        }

        // ── IOB / headroom ────────────────────────────────────────────────────
        // Fasting max IOB: caps IOB during FASTING to prevent over-stacking when PDP
        // drives more aggressive dosing. 0.0 = disabled (use global max IOB).
        val effectiveMaxIob = if (
            fastingMaxIobU > 0.0 &&
            mealMode == app.aaps.core.interfaces.smartInsulin.MealMode.FASTING
        ) minOf(oapsProfile.max_iob, fastingMaxIobU)
        else oapsProfile.max_iob

        val iobHeadroom  = (effectiveMaxIob - currentIob).coerceAtLeast(0.0)
        val iobOk        = currentIob < effectiveMaxIob
        val bgAboveGuard = currentBg - lowGuardMgdl

        // ── insulinReq ────────────────────────────────────────────────────────
        // Uses blended prediction for the gap, but blends the effective ISF for the
        // divisor. This is the key PDP mechanism for stuck-high:
        //
        // Primary ISF = dosingIsfMgdl (e.g. 36 mg/dL/U at ISF=2.0 mmol/U)
        // Secondary ISF = dosingIsfMgdl / ciStrength (models resistance: insulin less effective)
        //   ciStrength=1 → same ISF (no effect)
        //   ciStrength=2 → half ISF in formula → 2× insulinReq for same gap
        //   ciStrength=3 → 1/3 ISF in formula → 3× insulinReq for same gap
        // Blended: effectiveISF = primaryISF*(1-blend) + (primaryISF/ciStrength)*blend
        //
        // This works regardless of IOB level because it's based on the BG gap directly,
        // not on IOB activity magnitude. The prediction curve still shows the visual
        // separation (orange vs cyan) based on the resistance model.
        val predMinGapMgdl = (blendedPredMin - targetBg).coerceAtLeast(0.0)

        // The secondary curve now correctly lands at startBg - (primaryDrop / ciStrength),
        // so blendedPredMin is genuinely above target when BG is stuck-high.
        // No gap workaround needed — predMinGapMgdl is the real gap.
        val effectiveGapMgdl = predMinGapMgdl

        // ISF blend: still useful — models "insulin is less effective here so we need
        // more units per mmol gap." Works on the real gap produced by the fixed curve.
        //   effectiveISF = primaryISF*(1-blend) + (primaryISF/ciStrength)*blend
        //   ciStrength=2 → effectiveISF halved → 2× insulinReq for same gap
        val effectiveIsfMgdl = if (pdpEnabled && effectivePdpBlend > 0.0 && pdpSyntheticCi > 0.0) {
            val secondaryIsfMgdl = dosingIsfMgdl / pdpCiStrength.coerceAtLeast(1.0)
            dosingIsfMgdl * (1.0 - effectivePdpBlend) + secondaryIsfMgdl * effectivePdpBlend
        } else {
            dosingIsfMgdl
        }
        val insulinReq = effectiveGapMgdl / effectiveIsfMgdl

        // ── Reason string header ──────────────────────────────────────────────
        val sb = StringBuilder()
        // Pipe-separated compact format — each key piece separated by " | "
        sb.append("SI mode=${mealMode.label}")
        sb.append(" | BG=${fmt(currentBg, isMmol)}")
        sb.append(" | d=${fmt(delta, isMmol)}")
        sb.append(" | IOB=${"%.2f".format(Locale.US, currentIob)}/${"%.0f".format(Locale.US, oapsProfile.max_iob)}")
        sb.append(" | pred_min=${fmt(predictedMinSafety, isMmol)} lo=${fmt(lowGuardMgdl, isMmol)} warn=${fmt(warnGuardMgdl, isMmol)}")
        if (effectivePdpBlend > 0.0) {
            val isfStr = if (pdpSyntheticCi > 0.0) " ISF=${fmt(dosingIsfMgdl, isMmol)}→${fmt(effectiveIsfMgdl, isMmol)} gap=${fmt(predMinGapMgdl, isMmol)}" else ""
            sb.append(" | PDP(blend=${"%.2f".format(Locale.US, effectivePdpBlend)} ci×${"%.2f".format(Locale.US, pdpCiStrength)} fade=${pdpFadeMins.toInt()}m pdp_min=${fmt(pdpPredMin, isMmol)} blended=${fmt(blendedPredMin, isMmol)}$isfStr)")
        }
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
            // Defensive floor on profileBasal — prevents Inf/NaN from profileBasal=0
            // (pump-off, misconfigured profile, or near-zero basalMultiplier).
            // 0.01 U/hr is well below any realistic basal rate but non-zero.
            val effectiveBasal  = profileBasal.coerceAtLeast(0.01)
            val durationHours   = insulinReqU / effectiveBasal
            val durationMins    = (durationHours * 60.0).coerceIn(30.0, 90.0)
            return (Math.round(durationMins / 30.0) * 30).toInt().coerceIn(30, 90)
        }


        val lgsThresholdMgdl = (oapsProfile.lgsThreshold ?: 0).toDouble()

        val fallingFast    = delta < -FALLING_FAST_MGDL_PER_5MIN
        val fallingIntoLow = fallingFast && predictedAt30 < effectiveCautionMgdl

        var smbOut = 0.0

        when {
            // ── LGS hard suspend ─────────────────────────────────────────────
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                sb.append(" | LGS_SUSPEND | BG=${fmt(currentBg, isMmol)} < lgs=${fmt(lgsThresholdMgdl, isMmol)}")
                setTempBasal(0.0, suspendDurationMins(currentBg), oapsProfile, rT, currentTemp)
            }

            // ── Predictive suspend ───────────────────────────────────────────
            blendedPredMinSafety < effectiveSuspendMgdl || fallingIntoLow -> {
                val worstBg = if (fallingIntoLow) predictedAt30 else predictedMinSafety
                val suspendMins = suspendDurationMins(worstBg)
                val reason = when {
                    fallingIntoLow -> "SUSPEND fallingIntoLow pred30=${fmt(predictedAt30, isMmol)} delta=${String.format(Locale.US, "%.1f", delta)} dur=${suspendMins}m"
                    else           -> "SUSPEND pred_min=${fmt(blendedPredMinSafety, isMmol)} < ${if (highTempTargetActive) "tempTarget" else "lowGuard"}=${fmt(effectiveSuspendMgdl, isMmol)} dur=${suspendMins}m"
                }
                sb.append(" | $reason")
                setTempBasal(0.0, suspendMins, oapsProfile, rT, currentTemp)
            }

            // ── Caution zone ─────────────────────────────────────────────────
            blendedPredMinSafety < effectiveCautionMgdl -> {
                val guardGap   = effectiveCautionMgdl - blendedPredMinSafety
                val warnFrac   = 1.0 - (guardGap / (effectiveCautionMgdl - effectiveSuspendMgdl).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val cautionTbr = (profileBasal * warnFrac).coerceAtMost(profileBasal)
                // Apply rebound taper with a floor — the taper starts at 0.3 which would reduce
                // an already-scaled-down caution TBR to near zero while BG is heading toward the
                // warn guard. Floor at CAUTION_REBOUND_TAPER_FLOOR (0.5) so we always deliver at
                // least half the caution rate. Full suspend still fires above if pred_min < lowGuard.
                val cautionTaper = reboundTaperFraction.coerceAtLeast(CAUTION_REBOUND_TAPER_FLOOR)
                sb.append(" | CAUTION | pred_min=${fmt(blendedPredMinSafety, isMmol)} | warnGuard=${fmt(effectiveCautionMgdl, isMmol)}${if (highTempTargetActive) "(TT)" else ""} | tbrFrac=${"%.2f".format(Locale.US, warnFrac)} | tbr=${"%.3f".format(Locale.US, cautionTbr)}")
                setTempBasal(cautionTbr * cautionTaper, 30, oapsProfile, rT, currentTemp)
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
                    else        -> {
                        val pdpActive = pdpEnabled && effectivePdpBlend > 0.0
                        val label = if (pdpActive) "pdpMinGap" else "predMinGap"
                        "$label(${fmt(blendedPredMin, isMmol)}->${fmt(targetBg, isMmol)})"
                    }
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

                // Show unconstrained SMB if it was capped — helps user understand if maxSMB needs raising
                val smbCapNote = if (rawSmb > finalSmb && finalSmb > 0.0)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, capped at ${"%.2f".format(Locale.US, smbCap)}U)"
                else if (rawSmb > 0.0 && finalSmb == 0.0 && !reboundSmbAllowed)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, blocked: rebound)"
                else ""

                sb.append(" | NORMAL | targetBG=${fmt(targetBg, isMmol)} | microBolus=$microBolusAllowed | trigger=$trigger | SMB final: ${"%.2f".format(Locale.US, finalSmb)}U$smbCapNote | tbr=${"%.3f".format(Locale.US, tbrRate)}$reboundStr$activityStr$cgmBlockStr")
                smbOut = finalSmb
                setTempBasal(tbrRate, 30, oapsProfile, rT, currentTemp)
            }
        }

        // Store prediction snapshots for next-cycle PDP accuracy scoring
        lastIobPredAt5Mgdl = iobPredAt5
        lastPdpPredAt5Mgdl = pdpPredAt5
        lastPdpBlendWeight  = effectivePdpBlend

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

    // ── Last-cycle prediction snapshots — read by SmartInsulinPlugin for PDP accuracy scoring ──
    // Set on every determine_basal() call. SmartInsulinPlugin reads these on the NEXT cycle
    // to compare against actual BG and score PDP vs IOB accuracy.
    var lastIobPredAt5Mgdl: Double = 0.0
        private set
    var lastPdpPredAt5Mgdl: Double = 0.0
        private set
    var lastPdpBlendWeight: Double = 0.0
        private set

    // ── Prediction curve ──────────────────────────────────────────────────────
    private fun predictBgCurve(
        startBg:        Double,
        ci:             Double,
        iobArray:       Array<IobTotal>,
        isfMgdl:        Double,
        learnedProfile: LearnedInsulinProfile,
        ticks:          Int,
        systemDiaMins:  Double
    ): List<Double> {
        var bg           = startBg
        val predictions  = mutableListOf<Double>()
        for (tick in 1..ticks) {
            val activity   = getActivityAtMinute(tick * 5, iobArray, learnedProfile, systemDiaMins)
            val iobDelta   = -(activity * isfMgdl * 5.0)
            val predDev    = ci * (1.0 - minOf(1.0, (tick - 1) / (60.0 / 5.0)))
            bg += iobDelta + predDev
            predictions.add(bg)
        }
        return predictions
    }

    // ── PDP secondary prediction curve ───────────────────────────────────────
    // Models a "what if insulin is less effective than assumed" scenario.
    //
    // RISING pathway (stuckHighMode=false):
    //   ci persists over fadeMins (default 120min) instead of 60min, scaled by
    //   ciStrength — models UAM/carb tail or dawn phenomenon lasting longer.
    //   iobDelta is identical to the primary curve (same ISF assumption).
    //
    // STUCK-HIGH pathway (stuckHighMode=true):
    //   ci=0 (BG not rising, just not falling). Models insulin resistance by
    //   dividing iobDelta by ciStrength — each unit of insulin moves BG less:
    //     ciStrength=1 → same as primary (no resistance)
    //     ciStrength=2 → insulin half as effective (BG stays higher)
    //     ciStrength=3 → insulin one-third as effective (BG stays much higher)
    //   This naturally produces visible separation proportional to actual IOB
    //   activity — more IOB = more separation = more insulinReq adjustment.
    //   Fades linearly from resistance model → primary model over fadeMins,
    //   so the curves converge at the horizon.
    //
    // PRIMARY curve (predictBgCurve) is NEVER touched.
    private fun predictBgCurvePdp(
        startBg:       Double,
        ci:            Double,
        fadeMins:      Double,
        iobArray:      Array<IobTotal>,
        isfMgdl:       Double,
        learnedProfile: LearnedInsulinProfile,
        ticks:         Int,
        systemDiaMins: Double,
        stuckHighMode: Boolean = false,
        ciStrength:    Double  = 1.0
    ): List<Double> {
        val safeStrength = ciStrength.coerceAtLeast(1.0)
        val fadeTicks    = (fadeMins / 5.0).coerceAtLeast(1.0)

        if (stuckHighMode) {
            // ── Stuck-high: scale the TOTAL predicted drop, not per-tick iobDelta ────
            //
            // The old per-tick division approach was broken: even dividing iobDelta by
            // ciStrength=10 each tick, with large IOB the accumulated drop still drags
            // the secondary curve below target — the resistance slows the fall but can't
            // prevent it. The blended predMin ends up below target → gap=0 → no dose.
            //
            // Correct model: "BG is resistant — it will only fall X% as far as IOB predicts."
            //   primaryDrop   = how far IOB physics brings BG from startBg (e.g. 2.0 mmol)
            //   secondaryDrop = primaryDrop / ciStrength         (e.g. 0.67 at ciStrength=3)
            //   savedDrop     = primaryDrop - secondaryDrop      (e.g. 1.33 mmol lift)
            //
            // Each tick applies primary iobDelta PLUS a proportional upward lift that
            // fades to zero over fadeMins. At fadeTicks the curves converge (physics wins
            // at the long-term horizon). Before fadeTicks the secondary curve is held up.
            //
            //   ciStrength=1  → savedDrop=0    → identical to primary
            //   ciStrength=3  → secondary lands at startBg - primaryDrop/3
            //   ciStrength=10 → secondary barely moves from startBg

            // Pass 1: compute where the primary curve lands (total drop)
            var tempBg = startBg
            for (tick in 1..ticks) {
                val activity = getActivityAtMinute(tick * 5, iobArray, learnedProfile, systemDiaMins)
                tempBg += -(activity * isfMgdl * 5.0)
            }
            val primaryTotalDrop  = (startBg - tempBg).coerceAtLeast(0.0)  // always >= 0
            val secondaryTotalDrop = primaryTotalDrop / safeStrength
            val savedDrop          = primaryTotalDrop - secondaryTotalDrop  // lift = how much higher secondary lands

            // Pass 2: build secondary curve — primary iobDelta + proportional fade lift
            var bg = startBg
            val predictions = mutableListOf<Double>()
            for (tick in 1..ticks) {
                val activity        = getActivityAtMinute(tick * 5, iobArray, learnedProfile, systemDiaMins)
                val iobDeltaPrimary = -(activity * isfMgdl * 5.0)
                val fadeFrac        = (1.0 - minOf(1.0, (tick - 1) / fadeTicks))
                // Lift per tick: spread savedDrop across all ticks, weighted by fadeFrac
                // so the lift concentrates in the early portion and tapers off naturally.
                // Using fadeFrac-weighted distribution (not uniform) keeps curve smooth
                // and ensures the total lift sums to savedDrop when fadeFrac integrates to 1.
                val liftThisTick = if (fadeTicks > 0) (savedDrop / fadeTicks) * fadeFrac else 0.0
                bg += iobDeltaPrimary + liftThisTick
                predictions.add(bg)
            }
            return predictions

        } else {
            // ── Rising pathway: same IOB physics, ci term fades over fadeMins ────────
            // Rising says "unexplained deviation persists longer than normal" — ci term
            // holds BG up beyond what IOB alone would predict. This naturally produces
            // a higher predMin → bigger gap → more dosing. No change needed here.
            var bg = startBg
            val predictions = mutableListOf<Double>()
            for (tick in 1..ticks) {
                val activity        = getActivityAtMinute(tick * 5, iobArray, learnedProfile, systemDiaMins)
                val iobDeltaPrimary = -(activity * isfMgdl * 5.0)
                val fadeFrac        = (1.0 - minOf(1.0, (tick - 1) / fadeTicks))
                bg += iobDeltaPrimary + (ci * fadeFrac)
                predictions.add(bg)
            }
            return predictions
        }
    }


    private fun getActivityAtMinute(
        minutes:        Int,
        iobArray:       Array<IobTotal>,
        learnedProfile: LearnedInsulinProfile,
        systemDiaMins:  Double
    ): Double {
        val learnedDiaMins = learnedProfile.safeDiaMinutes
        // timeScale > 1 means learned insulin is FASTER (shorter DIA)
        // timeScale < 1 means learned insulin is SLOWER (longer DIA)
        val timeScale = systemDiaMins / learnedDiaMins

        val scaledMinutes = minutes * timeScale
        val idx = (scaledMinutes / 5.0).toInt()

        val baseActivity = if (idx < iobArray.size) {
            iobArray[idx].activity
        } else {
            // Exponential decay from the end of the array if we ran off
            val lastActivity = iobArray.lastOrNull()?.activity ?: 0.0
            val extraTicks = idx - iobArray.size + 1
            lastActivity * Math.exp(-extraTicks * 0.05)
        }

        // Multiply by timeScale to preserve AUC.
        // e.g. if DIA is half as long, activity at each point must be twice as high.
        return max(0.0, baseActivity * timeScale)
    }

    companion object {
        private const val MMOL_TO_MGDL           = 18.0
        private const val TBR_WINDOW_HOURS       = 0.5
        private const val REBOUND_SMB_GATE       = 0.825 // SMBs unlock at 75% of window: taper=0.3+(0.7×0.75)=0.825
        // delta is mg/dL per 5-min CGM cycle — threshold is 2.0 mmol in a single reading.
        // The previous formula divided by 5 which would give 0.4 mmol/min — wrong unit,
        // and far too sensitive (any moderate drop would qualify).
        private const val FALLING_FAST_MGDL_PER_5MIN = 2.0 * MMOL_TO_MGDL  // 36 mg/dL = 2.0 mmol per 5-min cycle
        private const val PEAK_LEARNING_MIN_SAMPLES  = 5
        private const val NEUTRAL_TEMP_EPSILON       = 1e-6  // floating-point tolerance for neutral-temp detection
        // Floor for rebound taper in caution zone — prevents delivering near-zero basal
        // while BG is already heading toward the warn guard.
        private const val CAUTION_REBOUND_TAPER_FLOOR = 0.5
    }
}