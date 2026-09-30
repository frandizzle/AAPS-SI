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
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
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
@SingleIn(AppScope::class)
class DetermineBasalSmartInsulin @Inject constructor(
    private val apsResultProvider: () -> APSResult
) {

    private fun fmt(mgdl: Double, isMmol: Boolean): String =
        if (isMmol) String.format(Locale.US, "%.1f", mgdl / MMOL_TO_MGDL)
        else        String.format(Locale.US, "%.0f", mgdl)

    // ISF needs one more decimal than BG/delta/target so mmol overrides like 0.85 don't get rounded away in the reason string.
    private fun fmtIsf(mgdl: Double, isMmol: Boolean): String =
        if (isMmol) String.format(Locale.US, "%.2f", mgdl / MMOL_TO_MGDL)
        else        String.format(Locale.US, "%.0f", mgdl)

    private fun setTempBasal(rate: Double, duration: Int, profile: OapsProfile, rT: RT, currentTemp: CurrentTemp) {
        val maxSafe = min(profile.max_basal,
                          min(profile.max_daily_safety_multiplier * profile.max_daily_basal,
                              profile.current_basal_safety_multiplier * profile.current_basal))
        val r = rate.coerceIn(0.0, maxSafe)
        // Epsilon comparison — exact double equality is fragile against any upstream arithmetic
        // drift (e.g. rate=1.0000000001 would silently bypass neutral-temp skip, causing
        // redundant TBR commands to the pump).
        // r > 0.0 guard: a zero temp is never a "neutral" temp, whatever the profile's current
        // basal happens to be. On a pump profile with a 0 U/h segment, r == current_basal == 0.0
        // matched this condition and the SUSPEND/LGS branches' zero temp was silently dropped —
        // the loop reported a suspend it had not actually commanded.
        if (profile.skip_neutral_temps && r > 0.0 && abs(r - profile.current_basal) < NEUTRAL_TEMP_EPSILON) {
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
        /** PROFILE ISF and CR — the food's own conversion, before any learned multiplier. */
        profileIsfMgdl:           Double,
        carbRatioGPerU:           Double,
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
        reboundWindowMins:        Double = 60.0,
        circCeil:                 Double = 1.0,
        fuelTrimStrength:         Double = 0.0,
        isMmol:                   Boolean = true,
        duraStatusText:           String = "",  // "" when DURA_ISF isn't currently strengthening ISF
        /** Washout taper from a declared activity/stress session: 1.0 = untouched, 0.3 = floor.
         *  Compounds with the rebound taper — two independent reasons to hold insulin back both
         *  apply, and the smaller one wins by construction. */
        sessionInsulinFraction:   Double = 1.0,
        sessionStatusText:        String = ""
    ): APSResult {

        val result = apsResultProvider()
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
        // reboundWindowMins (configurable, default 60). Bypassed when in meal modes.
        val effectiveRebound = inReboundWindow && mealMode == MealMode.FASTING
        val reboundMins = if (effectiveRebound) (msSinceLastSuspend / 60_000.0) else 0.0
        val rawTaper = (reboundMins / reboundWindowMins).coerceIn(0.0, 1.0)
        val reboundTaperFraction = if (effectiveRebound) 0.3 + (0.7 * rawTaper) else 1.0

        // Guard thresholds in mg/dL
        val lowGuardMgdl  = lowGuardMmol * MMOL_TO_MGDL
        val warnGuardMgdl = warnGuardMmol * MMOL_TO_MGDL

        // When a high temp target is active, raise effective suspend/caution thresholds to the
        // temp target. Without this, pred_min=6.9 with TT=7.5 falls through to normal dosing
        // and only gets a weak proportional basal reduction instead of a suspend.
        val effectiveSuspendMgdl = if (highTempTargetActive) targetBg else lowGuardMgdl
        val effectiveCautionMgdl = if (highTempTargetActive)
            targetBg + (warnGuardMgdl - lowGuardMgdl)
        else
            warnGuardMgdl

        // ── Build prediction curve ────────────────────────────────────────────
        // ci = observed delta minus expected BGI — positive means carbs/UAM pushing BG up
        //
        // BGI uses the same non-negative activity predictBgCurve does, and it has to. ci is
        // "whatever the observed delta is NOT explained by insulin", on the understanding that the
        // curve adds insulin's effect back tick by tick. But the curve clamps activity at zero —
        // it deliberately does not project negative IOB forward as a rising force, since that IOB
        // is basal withheld after a low rather than insulin acting — while this line used SIGNED
        // activity. With IOB negative, activity is negative, BGI came out POSITIVE, and was
        // subtracted from the observed rise; the curve then never gave it back.
        //
        // So after any suspend the observed rise was cancelled out of the forecast. BG climbing
        // +0.2/5min with IOB -1.46U projected a dead-flat line — or, at +0.1, a FALLING one, which
        // held pred_min under the low guard and kept the loop suspended on a recovering BG. Low
        // guard itself never touched the prediction; it produced the negative IOB this mishandled.
        //
        // Clamping here rather than un-clamping the curve is the conservative choice: the forecast
        // now follows the observed trend and lets it fade over the hour, instead of adding a
        // separate, accelerating push from withheld basal on top — which would have dosed hard
        // straight out of a low. Positive activity is untouched, so normal running is identical.
        val activityNow = iobArray.firstOrNull()?.activity ?: 0.0
        val bgi = -(max(0.0, activityNow) * dosingIsfMgdl * 5.0)
        // Upper clamp on carb impact. min(shortAvgDelta, delta) already blunts a single-reading
        // spike, but a sustained sensor artifact survives that and ci is fed into every tick of
        // the forward curve — so a bad sensor could inject well over 100 mg/dL of imaginary rise
        // into predictedMin and straight into insulinReq. CI_MAX_MGDL_PER_5MIN is above any real
        // absorption rate (1.5 mmol/L per 5 min), so it never truncates genuine carb impact.
        // Only the positive side is capped: a large negative ci predicts a fall and produces
        // restraint, which is the safe direction and must not be limited.
        val ci  = (min(glucoseStatus.shortAvgDelta, glucoseStatus.delta) - bgi).coerceAtMost(CI_MAX_MGDL_PER_5MIN)

        // Every insulinReq-style division below uses this rather than dosingIsfMgdl directly.
        // dosingIsfMgdl is profileISF scaled by learned multipliers and aggressiveness; if any of
        // those ever produced a near-zero value the division would explode into an unbounded
        // insulin request. The floor is far below any usable ISF, so it never binds in practice.
        val safeIsfMgdl = dosingIsfMgdl.coerceAtLeast(MIN_DOSING_ISF_MGDL)

        val predictedBg = predictBgCurve(
            startBg       = currentBg,
            ci            = ci,
            iobArray      = iobArray,
            isfMgdl       = dosingIsfMgdl,
            learnedProfile = learnedProfile,
            // Bounds match LearnedInsulinProfile's own DIA range — was hardcoded to (360, 480)
            // independent of that range, which silently re-capped the prediction curve (and thus
            // pred_min / low-guard decisions) at 8h even after DIA learning was fixed to reach
            // the ~9h community reference value. Single source of truth now.
            ticks         = learnedProfile.safeDiaMinutes.toInt()
                .coerceIn(LearnedInsulinProfile.DIA_MIN_MINUTES.toInt(), LearnedInsulinProfile.DIA_MAX_MINUTES.toInt()) / 5
        )

        // ── Carbs on board: oref's COB curve takes over the forecast ──────────
        // The curve above models insulin against the carb impact ALREADY VISIBLE in BG, which is
        // all there is to go on when nothing was entered. With carbs entered there is more to go
        // on: the food that has not shown up yet. OrefCarbCurve adds it, so the forecast (and
        // therefore the dose) leads the meal instead of chasing it.
        //
        // profileIsfMgdl and the profile's CR, NOT dosingIsfMgdl: csf describes the food, and the
        // learned multipliers are applied when this forecast is turned into insulin below. Using
        // the learned ISF here would apply them twice.
        val carbsOnBoard = mealData.mealCOB
        val carbCurve = if (carbsOnBoard > 0.0 && carbRatioGPerU > 0.0)
            OrefCarbCurve.predict(
                startBg    = currentBg,
                ci         = ci,
                cobG       = carbsOnBoard,
                isfMgdl    = profileIsfMgdl,
                carbRatio  = carbRatioGPerU,
                bgiPerTick = insulinBgiPerTick(iobArray, dosingIsfMgdl, learnedProfile, predictedBg.size)
            )
        else null
        // Everything downstream — suspend decisions, SMB sizing, the low guard — reads these, so
        // carbs change the dose through the same single path the rest of the plugin already uses.
        val forecast = carbCurve?.bgs ?: predictedBg

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
        val predictedMinSafety = forecast.minOrNull() ?: currentBg
        // Post-peak min for DOSING — avoids suppressing SMBs on early descending curve
        val predictedMin = if (forecast.size > insulinPeakTicks)
            forecast.drop(insulinPeakTicks).minOrNull() ?: currentBg
        else
            forecast.minOrNull() ?: currentBg

        val predictedAt30 = if (forecast.size > 5)  forecast[5]  else forecast.lastOrNull() ?: currentBg
        val predictedAt60 = if (forecast.size > 11) forecast[11] else forecast.lastOrNull() ?: currentBg

        // Populate rT.predBGs.IOB for the overview prediction graph
        val rawPrediction = mutableListOf<Int>()
        predictedBg.take(learnedProfile.safeDiaMinutes.toInt()
                              .coerceIn(LearnedInsulinProfile.DIA_MIN_MINUTES.toInt(), LearnedInsulinProfile.DIA_MAX_MINUTES.toInt()) / 5)
            .forEach { rawPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
        rT.predBGs = app.aaps.core.interfaces.aps.Predictions()
        rT.predBGs?.IOB = rawPrediction
        // The COB line, drawn alongside IOB exactly as stock AAPS does when carbs are active.
        carbCurve?.let { c ->
            rT.predBGs?.COB = c.bgs.take(rawPrediction.size).map { it.coerceIn(39.0, 401.0).toInt() }
        }

        // ── IOB / headroom ────────────────────────────────────────────────────
        val iobHeadroom  = (oapsProfile.max_iob - currentIob).coerceAtLeast(0.0)
        val iobOk        = currentIob < oapsProfile.max_iob
        val bgAboveGuard = currentBg - lowGuardMgdl

        /**
         * Whether BG has cleared the low guard, for the SMB gate.
         *
         * Deliberately not `bgAboveGuard > 0.0`, for two reasons:
         *
         *  - Sitting AT the guard is not being BELOW it. The guard marks where dosing should stop;
         *    a BG level with it has not gone under.
         *  - The comparison ran at a finer resolution than anything on screen. The guard is entered
         *    in mmol and stored as its mg/dL conversion (4.8 -> 86.4) while CGM values arrive as
         *    whole mg/dL, so a log line reading "BG=4.8 lo=4.8" is really 86 vs 86.4 — and SMBs were
         *    held for a 0.4 mg/dL gap smaller than the 0.1 mmol display step (1.8 mg/dL) and smaller
         *    than the sensor resolves. Nothing in the reason string could show why.
         *
         * [bgBelowGuard] is the shared definition — the same one the rebound tracking, the learners'
         * lowActive flag and the circadian hard-low penalty use, so a BG cannot clear the guard here
         * and count as a low there. The guard itself does not move, and everything below it is
         * untouched: the predictive SUSPEND, LGS and the caution zone all still fire on their own
         * thresholds.
         */
        val bgClearsGuard = !bgBelowGuard(currentBg, lowGuardMgdl)

        // Stock OpenAPS-style insulinReq: how much insulin is needed to bring
        // predictedMin to target. predictedMin already has existing IOB baked in,
        // so this naturally self-limits — no iobSufficient gate needed.
        val predMinGapMgdl = (predictedMin - targetBg).coerceAtLeast(0.0)
        val insulinReq     = predMinGapMgdl / safeIsfMgdl

        // ── Reason string header ──────────────────────────────────────────────
        val sb = StringBuilder()
        // Pipe-separated compact format — each key piece separated by " | "
        sb.append("SI mode=${mealMode.label}")
        sb.append(" | BG=${fmt(currentBg, isMmol)}")
        sb.append(" | d=${fmt(delta, isMmol)}")
        sb.append(" | IOB=${"%.2f".format(Locale.US, currentIob)}/${"%.0f".format(Locale.US, oapsProfile.max_iob)}")
        sb.append(" | pred_min=${fmt(predictedMinSafety, isMmol)} lo=${fmt(lowGuardMgdl, isMmol)} warn=${fmt(warnGuardMgdl, isMmol)}")
        sb.append(" | target=${fmt(targetBg, isMmol)}${if (isTempTarget) "(tmp)" else ""}")
        sb.append(" | ISF=${fmtIsf(dosingIsfMgdl, isMmol)}")
        if (duraStatusText.isNotEmpty()) sb.append(" | $duraStatusText")
        sb.append(" | basal=${"%.3f".format(Locale.US, profileBasal)}(x${"%.2f".format(Locale.US, basalMultiplier)})")
        val pkLabel = if (learnedProfile.sampleCount < PEAK_LEARNING_MIN_SAMPLES) "Peak" else "Learned pk"
        sb.append(" | ${pkLabel}=${learnedProfile.safePeakMinutes.toInt()}m DIA=${learnedProfile.safeDiaMinutes.toInt()}m")
        sb.append(" | aggr=${"%.2f".format(Locale.US, aggressiveness)}")
        // The pref is the fraction of SMB KEPT, not the amount removed — print it that way.
        if (inDawnWindow) sb.append(" | dawn(smb ${"%.0f".format(Locale.US, dawnSmbReduction * 100)}%)")
        if (highTempTargetActive) sb.append(" | highTT=smbOff")
        if (inReboundWindow) {
            val reboundMinsLeft = (reboundWindowMins - reboundMins).coerceAtLeast(0.0)
            sb.append(" | rebound(${reboundMins.toInt()}min left=${reboundMinsLeft.toInt()}min taper=${"%.2f".format(Locale.US, reboundTaperFraction)})")
        } else if (bgWentLow) {
            sb.append(" | rebound=watching")
        }
        if (sessionStatusText.isNotEmpty()) sb.append(" | $sessionStatusText")
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
            val insulinReqU     = bgUndershoot / safeIsfMgdl
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
        val fallingIntoLow = fallingFast && predictedAt30 < warnGuardMgdl

        var smbOut = 0.0

        when {
            // ── LGS hard suspend ─────────────────────────────────────────────
            lgsThresholdMgdl > 0 && currentBg < lgsThresholdMgdl -> {
                sb.append(" | LGS_SUSPEND | BG=${fmt(currentBg, isMmol)} < lgs=${fmt(lgsThresholdMgdl, isMmol)}")
                setTempBasal(0.0, suspendDurationMins(currentBg), oapsProfile, rT, currentTemp)
            }

            // ── Predictive suspend ───────────────────────────────────────────
            predictedMinSafety < effectiveSuspendMgdl || fallingIntoLow -> {
                val worstBg = if (fallingIntoLow) predictedAt30 else predictedMinSafety
                val suspendMins = suspendDurationMins(worstBg)
                val reason = when {
                    fallingIntoLow -> "SUSPEND fallingIntoLow pred30=${fmt(predictedAt30, isMmol)} delta=${String.format(Locale.US, "%.1f", delta)} dur=${suspendMins}m"
                    else           -> "SUSPEND pred_min=${fmt(predictedMinSafety, isMmol)} < ${if (highTempTargetActive) "tempTarget" else "lowGuard"}=${fmt(effectiveSuspendMgdl, isMmol)} dur=${suspendMins}m"
                }
                sb.append(" | $reason")
                setTempBasal(0.0, suspendMins, oapsProfile, rT, currentTemp)
            }

            // ── Caution zone ─────────────────────────────────────────────────
            predictedMinSafety < effectiveCautionMgdl -> {
                val guardGap   = effectiveCautionMgdl - predictedMinSafety
                val warnFrac   = 1.0 - (guardGap / (effectiveCautionMgdl - effectiveSuspendMgdl).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val cautionTbr = (profileBasal * warnFrac).coerceAtMost(profileBasal)
                // Apply rebound taper with a floor — the taper starts at 0.3 which would reduce
                // an already-scaled-down caution TBR to near zero while BG is heading toward the
                // warn guard. Floor at CAUTION_REBOUND_TAPER_FLOOR (0.5) so we always deliver at
                // least half the caution rate. Full suspend still fires above if pred_min < lowGuard.
                val cautionTaper = reboundTaperFraction.coerceAtLeast(CAUTION_REBOUND_TAPER_FLOOR)
                sb.append(" | CAUTION | pred_min=${fmt(predictedMinSafety, isMmol)} | warnGuard=${fmt(effectiveCautionMgdl, isMmol)}${if (highTempTargetActive) "(TT)" else ""} | tbrFrac=${"%.2f".format(Locale.US, warnFrac)} | tbr=${"%.3f".format(Locale.US, cautionTbr)}")
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
                    bgClearsGuard &&
                    insulinReq > 0.0

                val correctionUnits = if (smbAllowed) {
                    // The plugin passes exactly the right fraction for this cycle —
                    // either the UAM entry fraction or SMB_DELIVERY_FRACTION (0.5).
                    // Use it directly. coerceIn(0.1, 0.9) is the OpenAPS safety cap.
                    insulinReq * (uamSmbFraction * aggressiveness).coerceIn(0.1, 1.0) * dawnFraction * cgmFraction
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
                    // Uses ISF math so small gaps → tiny reduction, large gaps → zero basal.
                    // This used to be gated behind `targetRespectEnabled || targetBg > 6.0 mmol`,
                    // but the plugin is the only caller and passes the flag true unconditionally,
                    // so the target threshold has never been reachable. Removed rather than left
                    // in place looking like a live configuration option.
                    predictedMin < targetBg -> {
                        val missingBgMgdl   = targetBg - predictedMin
                        val missingInsulinU = missingBgMgdl / safeIsfMgdl
                        val reducedBasal    = profileBasal - (missingInsulinU / TBR_WINDOW_HOURS)
                        reducedBasal.coerceIn(0.0, profileBasal)
                    }
                    else             -> profileBasal
                }
                val sessionFrac = sessionInsulinFraction.coerceIn(0.0, 1.0)
                val tbrRate = tbrRateRaw * reboundTaperFraction * sessionFrac

                // SMBs taper back in after a low the way TBR does, rather than switching from nothing
                // to full size the moment the gate opens.
                //
                // They used to stay blocked until the taper reached REBOUND_SMB_GATE (75% of the
                // window) and then jump straight to 100%. That cliff lands exactly where it hurts:
                // rescue carbs are still absorbing, the forecast now follows that rise instead of
                // having negative IOB damp it, and the first SMB after the gate was sized off a full
                // hour's projected climb. Now nothing is given before the gate — unchanged — and
                // from there SMBs ramp linearly to full size at the end of the window. At every
                // point in the window this delivers no more than the old gate did.
                val smbTaperFraction = ((reboundTaperFraction - REBOUND_SMB_GATE) / (1.0 - REBOUND_SMB_GATE))
                    .coerceIn(0.0, 1.0)
                val reboundSmbAllowed = smbTaperFraction > 0.0
                // A session washout scales SMBs by the same fraction it scales the TBR, so the two
                // tapers can't disagree about how much insulin this cycle is allowed to deliver.
                val smbScaleFraction = smbTaperFraction * sessionFrac
                val finalSmb = when {
                    // Outside a rebound and outside a washout the fraction is exactly 1: return the
                    // SMB untouched, so normal running cannot be shifted by rounding.
                    smbScaleFraction >= 1.0 -> constrainedSmb
                    else -> {
                        // Round DOWN to the pump step, so a taper can never round an SMB up past its
                        // share. The epsilon absorbs binary-fraction error (0.15 / 0.05 = 2.9999…).
                        val tapered = Math.floor(constrainedSmb * smbScaleFraction / bolusStep + 1e-6) * bolusStep
                        if (tapered >= bolusStep) tapered else 0.0
                    }
                }

                val trigger = when {
                    !iobOk      -> "maxIOB(${String.format(Locale.US, "%.2f", currentIob)}/${String.format(Locale.US, "%.2f", oapsProfile.max_iob)})"
                    !smbAllowed -> "blocked"
                    else        -> "predMinGap(${fmt(predictedMin, isMmol)}->${fmt(targetBg, isMmol)})"
                }

                val reboundStr = when {
                    inReboundWindow -> {
                        val minsLeft = (reboundWindowMins - reboundMins).coerceAtLeast(0.0)
                        val smbState = when {
                            !reboundSmbAllowed      -> "smbBlocked"
                            smbTaperFraction < 1.0  -> "smb×${(smbTaperFraction * 100).toInt()}%"
                            else                    -> "smbAllowed"
                        }
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
                // Checked in this order so a rebound reduction is not mislabelled as the maxSMB cap.
                val smbCapNote = if (rawSmb > 0.0 && finalSmb == 0.0 && !reboundSmbAllowed)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, blocked: rebound)"
                else if (rawSmb > finalSmb && sessionFrac < 1.0 && smbTaperFraction >= 1.0)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, session washout ${(sessionFrac * 100).toInt()}%)"
                else if (rawSmb > finalSmb && smbTaperFraction < 1.0)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, rebound taper ${(smbTaperFraction * 100).toInt()}%)"
                else if (rawSmb > finalSmb && finalSmb > 0.0)
                    " (wanted ${"%.2f".format(Locale.US, rawSmb)}U, capped at ${"%.2f".format(Locale.US, smbCap)}U)"
                else ""

                sb.append(" | NORMAL | targetBG=${fmt(targetBg, isMmol)} | microBolus=$microBolusAllowed | trigger=$trigger | SMB final: ${"%.2f".format(Locale.US, finalSmb)}U$smbCapNote | tbr=${"%.3f".format(Locale.US, tbrRate)}$reboundStr$activityStr$cgmBlockStr")
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
    /**
     * Insulin's expected effect per 5-minute tick, mg/dL and negative while insulin works — the
     * same anchored, learned-curve maths [predictBgCurve] uses, exposed so the carb curve applies
     * insulin identically. Two different insulin models across two forecasts would be a bug
     * waiting to happen.
     */
    private fun insulinBgiPerTick(
        iobArray: Array<IobTotal>,
        isfMgdl: Double,
        learnedProfile: LearnedInsulinProfile,
        ticks: Int
    ): List<Double> {
        val peak = learnedProfile.safePeakMinutes
        val dia  = learnedProfile.safeDiaMinutes
        val iobNow      = iobArray.firstOrNull()?.iob ?: 0.0
        val activityNow = iobArray.firstOrNull()?.activity ?: 0.0
        val effAgeMins  = InsulinActivityCurve.effectiveAgeMinutes(activityNow, iobNow, peak, dia)
        val iobFrac     = InsulinActivityCurve.iobFraction(effAgeMins, peak, dia).coerceAtLeast(IOB_FRACTION_FLOOR)
        val anchorU     = iobNow / iobFrac
        return (1..ticks).map { tick ->
            val activity = max(0.0, anchorU * InsulinActivityCurve.activityFraction(effAgeMins + tick * 5.0, peak, dia))
            -(activity * isfMgdl * 5.0)
        }
    }

    private fun predictBgCurve(
        startBg:        Double,
        ci:             Double,
        iobArray:       Array<IobTotal>,
        isfMgdl:        Double,
        learnedProfile: LearnedInsulinProfile,
        ticks:          Int
    ): List<Double> {
        var bg          = startBg
        val predictions = mutableListOf<Double>()

        val peak = learnedProfile.safePeakMinutes
        val dia  = learnedProfile.safeDiaMinutes

        // Shape the forward insulin-activity curve with the LEARNED peak AND DIA as independent
        // parameters (see InsulinActivityCurve), but anchor its MAGNITUDE to the real insulin on
        // board. iobArray[0] is "now", built by AAPS from actual bolus/SMB history — we keep that
        // real quantity and only re-time how it acts.
        //
        // Current IOB is a mixture of boluses at different ages. We collapse it to a single
        // effective age (the age whose activity/IOB ratio matches what is observed right now) so
        // the learned curve can reshape the forward decay WITHOUT needing per-bolus history. This
        // reproduces current activity exactly at tick 0 and conserves total forward activity ==
        // current IOB, while respecting that aged insulin is past its peak — so it never re-peaks
        // already-decaying insulin the way a fresh-curve-from-zero model would.
        val iobNow       = iobArray.firstOrNull()?.iob ?: 0.0
        val activityNow  = iobArray.firstOrNull()?.activity ?: 0.0
        val effAgeMins   = InsulinActivityCurve.effectiveAgeMinutes(activityNow, iobNow, peak, dia)
        val iobFracAtAge = InsulinActivityCurve.iobFraction(effAgeMins, peak, dia)
            .coerceAtLeast(IOB_FRACTION_FLOOR)
        val anchorU      = iobNow / iobFracAtAge

        for (tick in 1..ticks) {
            val futureMins = tick * 5.0
            val activity   = max(0.0, anchorU * InsulinActivityCurve.activityFraction(effAgeMins + futureMins, peak, dia))
            val iobDelta   = -(activity * isfMgdl * 5.0)
            val predDev    = ci * (1.0 - minOf(1.0, (tick - 1) / (60.0 / 5.0)))
            bg += iobDelta + predDev
            predictions.add(bg)
        }
        return predictions
    }

    companion object {
        private const val MMOL_TO_MGDL           = 18.0
        private const val TBR_WINDOW_HOURS       = 0.5
        private const val REBOUND_SMB_GATE       = 0.825 // SMBs start ramping in at 75% of window (taper=0.3+0.7×0.75), full size by its end
        // delta is mg/dL per 5-min CGM cycle — threshold is 2.0 mmol in a single reading.
        private const val FALLING_FAST_MGDL_PER_5MIN = 2.0 * MMOL_TO_MGDL  // 36 mg/dL = 2.0 mmol per 5-min cycle
        private const val PEAK_LEARNING_MIN_SAMPLES  = 5
        private const val NEUTRAL_TEMP_EPSILON       = 1e-6  // floating-point tolerance for neutral-temp detection
        // Floor for rebound taper in caution zone — prevents delivering near-zero basal
        // while BG is already heading toward the warn guard.
        private const val CAUTION_REBOUND_TAPER_FLOOR = 0.5
        // Floor on the learned-curve IOB fraction at the effective age, so anchoring current
        // IOB to a near-spent curve (iobFraction → 0) can't blow up the magnitude. At this
        // point remaining activity is tiny anyway, so the floor is a safe numerical guard.
        private const val IOB_FRACTION_FLOOR          = 0.02
        private const val CI_MAX_MGDL_PER_5MIN        = 1.5 * MMOL_TO_MGDL  // 27 mg/dL per 5 min
        // ~0.5 mmol/L per unit — an ISF this strong is outside any real profile; this exists
        // purely so a corrupt multiplier can never turn a division into an unbounded dose.
        private const val MIN_DOSING_ISF_MGDL         = 9.0

    }
}