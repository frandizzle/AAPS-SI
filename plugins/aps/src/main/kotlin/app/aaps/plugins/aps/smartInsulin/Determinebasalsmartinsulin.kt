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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

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

    /**
     * Appends ` | label=value` to the running reason string. Use this for any new fields
     * to keep the log format consistent and reduce risk of malformed delimiters when
     * editing complex conditional log sections. Not retrofitting all existing sb.append
     * calls — they work and changing them would be churn without value.
     */
    private fun StringBuilder.appendField(label: String, value: String): StringBuilder {
        append(" | ").append(label).append('=').append(value)
        return this
    }

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
        pdpRisingStrength:        Double  = 1.0,     // rising pathway ci scale (0.5-1.5, separate from stuck-high)
        // ── ICE (Insulin Counteraction Effect) — observation-driven ci blending ───────
        // When iceMgdlPerH is provided and iceBlendWeight > 0, the per-cycle ci that
        // feeds predictBgCurve is blended with the time-smoothed ICE measurement.
        // This makes the prediction line lean on observed-vs-modeled momentum when
        // the IceTracker reports high confidence. With defaults (null/0.0) behaviour
        // is bit-for-bit identical to before ICE existed.
        iceMgdlPerH:              Double? = null,    // current ICE in mg/dL/h from IceTracker, null = no signal
        iceBlendWeight:           Double  = 0.0,     // effective weight (confidence × userWeight), 0.0–1.0
        // ── ICE forward prediction line ───────────────────────────────────────────────
        // Future expected ICE rates at 5-min ticks (index 0 = first tick after now).
        // Used to populate predBGs.COB — a dedicated visualization line showing what
        // the meal alone (announced + observed momentum) is expected to do to BG over
        // the next ~2h. Insulin is NOT subtracted from this line so the user sees the
        // raw meal effect against the cyan blended-IOB prediction line.
        // Empty list = no ICE line rendered (legacy behaviour).
        iceFutureMgdlPerH:        List<Double> = emptyList(),
        // ── ICE mode (ice-step28) ────────────────────────────────────────────────────
        // Routes the prediction line to the COB slot (orange) for announced meals or
        // the UAM slot (yellow) for unannounced rises. NONE = no ICE projection slot.
        // Currently consumed only by the plugin's chart-slot router; determine_basal
        // accepts it for compatibility with the plugin's call signature. Default NONE
        // keeps any legacy callers working.
        iceMode:                  app.aaps.plugins.aps.smartInsulin.ice.IceMode = app.aaps.plugins.aps.smartInsulin.ice.IceMode.NONE
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

        // Empty iobArray should never happen — IOB calculator in SmartInsulinPlugin pre-fills
        // DIA-length entries even at zero IOB. If we get here with an empty array something is
        // genuinely wrong upstream. Crash the loop is worse than abort-this-cycle, so log and
        // bail. consoleError surfaces in the AAPS APS result tab so the upstream bug is visible.
        if (iobArray.isEmpty()) {
            rT.consoleError?.add("SmartInsulin: iobArray is empty — aborting cycle (upstream IOB calc failure?)")
            result.with(rT)
            return result
        }

        // ── Validate the ISF arriving from upstream ──────────────────────────
        // dosingIsfMgdl is the multiplier-blended ISF computed in SmartInsulinPlugin.
        // It is THE central divisor for insulinReq, suspendDuration, and the target-
        // respect basal calc. If upstream returns 0 / negative / NaN / Inf (e.g. an
        // isfMult corruption, division-by-zero in the blend, malformed profile), the
        // raw value would produce catastrophic dosing:
        //   • dosingIsfMgdl == 0   → insulinReq = +Infinity → max SMB + max TBR
        //   • dosingIsfMgdl == NaN → tbrRate = NaN → undefined pump behaviour
        //   • dosingIsfMgdl < 0    → silently disables SMB / TBR escalation
        //
        // Three-layer fallback:
        //   1. Use dosingIsfMgdl if finite and positive (the normal path)
        //   2. Else fall back to the user's raw profile ISF (un-multiplied)
        //   3. Else fall back to ABSOLUTE_FALLBACK_ISF_MGDL — chosen high (50 mg/dL/U
        //      ≈ 2.8 mmol/U) because higher ISF = smaller insulinReq = LESS insulin,
        //      the safe direction when we have no reliable sensitivity at all.
        //
        // Substituted everywhere downstream — not just at the divisor sites — because
        // if the ISF is broken, every downstream computation built on it (BGI, the
        // prediction curve, PDP secondary curve, display strings) would also be
        // corrupted. Validating once at the boundary keeps the whole function
        // operating on a known-good value.
        val effectiveDosingIsfMgdl: Double = if (dosingIsfMgdl.isFinite() && dosingIsfMgdl > 0.0) {
            dosingIsfMgdl
        } else {
            val profileIsf = try {
                profile.getIsfMgdl("DetermineBasalSmartInsulin")
            } catch (e: Exception) {
                Double.NaN
            }
            val (fallback, source) = if (profileIsf.isFinite() && profileIsf > 0.0) {
                profileIsf to "profile ISF"
            } else {
                ABSOLUTE_FALLBACK_ISF_MGDL to "absolute floor"
            }
            rT.consoleError?.add(
                ("SmartInsulin: dosingIsfMgdl=%.3f invalid (not finite or non-positive) " +
                    "— falling back to %s=%.1f mg/dL/U")
                    .format(Locale.US, dosingIsfMgdl, source, fallback)
            )
            fallback
        }

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
        // SMB-delivery multipliers — both must stay in [0, 1]. They can only REDUCE
        // delivery, never boost it. The (uam*aggr).coerceIn(0.1, 0.9) cap at the SMB
        // calc site runs BEFORE these are applied, so without the clamp a misconfigured
        // dawnSmbReduction (e.g. 1.5 thinking "150% during dawn", or a percentage like
        // 50.0) would multiply through and bypass the upstream safety cap. Same on
        // cgmSmbFraction — both come from user prefs / upstream calcs that could be wrong.
        val dawnFraction = (if (inDawnWindow) dawnSmbReduction else 1.0).coerceIn(0.0, 1.0)
        val cgmFraction  = (if (!cgmDeltaPlausible) 0.0 else cgmSmbFraction).coerceIn(0.0, 1.0)

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
        // iobArray[0] is safe — explicit isEmpty() guard above ensures the array has entries.
        val bgi = -(iobArray[0].activity * effectiveDosingIsfMgdl * 5.0)
        val ci  = min(glucoseStatus.shortAvgDelta, glucoseStatus.delta) - bgi

        // ── ICE blending: shift ci toward time-smoothed observed counteraction ─────
        // rawCi is a one-cycle measurement (noisy). When the IceTracker reports a
        // confident, persistent ICE signal, blend its smoothed value in.
        //
        //   effectiveCi = ci × (1 − w) + iceCi × w     where w = iceBlendWeight
        //
        // iceCi is the per-tick equivalent of iceMgdlPerH (× 5 min / 60 min).
        // Defaults give w=0 → effectiveCi = ci → identical to legacy behaviour.
        //
        // **Special case for announced-meal-only signal**: When ci is 0 (user
        // didn't enter carbs into AAPS — only announced via ICE), the weighted
        // average dilutes iceCi to (iceCi × w). For an announced meal at 50%
        // user-weight, that halves the meal's effect on the prediction line,
        // causing the loop to under-dose. When ci is 0 and ICE is the only
        // signal, use iceCi at minimum 50% strength regardless of userWeight —
        // the announcement itself is consent to act on it.
        val effectiveCi: Double = if (iceMgdlPerH != null && iceMgdlPerH.isFinite() && iceBlendWeight > 0.0) {
            val iceCi = iceMgdlPerH * (TICK_MINUTES.toDouble() / 60.0)
            val w     = iceBlendWeight.coerceIn(0.0, 1.0)
            if (ci > 0.0) {
                // Both signals present — weighted blend
                ci * (1.0 - w) + iceCi * w
            } else {
                // ICE is the only signal — apply with a 50% floor so a
                // configured-low userWeight doesn't silently neutralize ICE
                iceCi * w.coerceAtLeast(0.5)
            }
        } else ci

        // Prediction-curve length: clamp DIA between 6h and 8h, convert to 5-min ticks.
        // Used by primary curve, PDP secondary curve, and graph-population (predBGs).
        // Single source of truth — bounds changes only need to happen here.
        val predictionTicks = learnedProfile.safeDiaMinutes.toInt().coerceIn(360, 480) / TICK_MINUTES

        val predictedBg = predictBgCurve(
            startBg       = currentBg,
            ci            = effectiveCi,
            iobArray      = iobArray,
            isfMgdl       = effectiveDosingIsfMgdl,
            learnedProfile = learnedProfile,
            ticks         = predictionTicks,
            systemDiaMins  = systemDiaMins
        )

        // ── Display-only "fasting" prediction (cyan line on chart) ────────────────
        // The cyan IOB line on the chart should show pure insulin-effect — i.e.,
        // "if the meal didn't happen, what would BG do?" This is what users
        // historically read as the "fasting prediction." When ICE is engaged in
        // effectiveCi above, the cyan line would bend up against the meal, which
        // is mathematically correct for safety reasoning but visually confusing
        // (cyan and orange end up looking similar — both meal-aware — defeating
        // the purpose of having two lines).
        //
        // So compute a parallel prediction with ci=rawCi (no ICE blend) for the
        // chart only. predictedBg above keeps ICE for safety/internal use.
        val predictedBgDisplay: List<Double> =
            if (iceBlendWeight > 0.0 && effectiveCi != ci) {
                predictBgCurve(
                    startBg       = currentBg,
                    ci            = ci,                  // raw — no ICE
                    iobArray      = iobArray,
                    isfMgdl       = effectiveDosingIsfMgdl,
                    learnedProfile = learnedProfile,
                    ticks         = predictionTicks,
                    systemDiaMins  = systemDiaMins
                )
            } else predictedBg  // no ICE blend — display and internal are the same

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
        // predictedMinSafety is computed LATER, after iobAwareIceProjection is built,
        // so it can blend the ICE-aware curve in by iceBlendWeight. Pre-step27 it was
        // a single-line `predictedBg.minOrNull()`; restored ice-step45 after step42's
        // collapse-to-dosingMetric incorrectly masked descents during near-finished meals
        // (peak-blended scalar treated currentBg as a "peak" when projection only descended).
        // Post-peak min for DOSING — avoids suppressing SMBs on early descending curve
        val predictedMin = if (predictedBg.size > insulinPeakTicks)
            predictedBg.drop(insulinPeakTicks).minOrNull() ?: currentBg
        else
            predictedBg.minOrNull() ?: currentBg

        val predictedAt30 = predictedBg.getOrNull(TICKS_AT_30MIN_INDEX) ?: predictedBg.lastOrNull() ?: currentBg
        val predictedAt60 = predictedBg.getOrNull(TICKS_AT_60MIN_INDEX) ?: predictedBg.lastOrNull() ?: currentBg

        // ── PDP: secondary prediction curve ──────────────────────────────────
        // Only computed during FASTING when pdpEnabled and blendWeight > 0.
        // Uses the same IOB activity curve but ci fades over pdpFadeMins (default 120min)
        // instead of 60min, scaled by pdpCiStrength — models sustained unexplained deviation
        // (stress, dawn phenomenon, protein/fat tail, illness) persisting longer than normal.
        // Safety: pdpBlendWeight=0 when mealMode != FASTING so meal modes are never affected.
        val effectivePdpBlend = if (pdpEnabled && mealMode == MealMode.FASTING) pdpBlendWeight else 0.0

        val pdpPredictedBg: List<Double>
        val pdpPredMin: Double
        if (effectivePdpBlend > 0.0) {
            // pdpSyntheticCi > 0 = stuck-high. pdpSyntheticCi = 0 = rising or inactive.
            val isStuckHigh    = pdpSyntheticCi > 0.0
            // Rising: use pdpRisingStrength (0.5-1.5), not pdpCiStrength (1-10)
            // This prevents a high ciStrength for stuck-high from making rises overly aggressive.
            // Clamp ci to ≥ 0 — the rising pathway models BG climbing (ci pushes BG up). If ci
            // goes negative on a transient (CGM stall, brief flat after a rise), feeding it through
            // multiplied by pdpRisingStrength would drag the secondary curve below primary. Not
            // a safety issue (safety gates use primary only), but semantically incoherent for a
            // "rising" pathway, and makes the secondary curve strictly ≥ primary on minimum.
            val pdpEffectiveCi = if (isStuckHigh) 0.0 else maxOf(0.0, ci) * pdpRisingStrength
            // Stuck-high: secondary curve uses ISF / ciStrength.
            // Lower ISF = insulin less effective = BG doesn't fall as far = higher predMin.
            // This is the correct physical model: if ciStrength=3, each unit of insulin
            // only moves BG 1/3 as far, so the prediction line lands much higher.
            // Rising pathway keeps primary ISF — ci term lifts it, not ISF scaling.
            val pdpIsfMgdl = if (isStuckHigh)
                effectiveDosingIsfMgdl / pdpCiStrength.coerceAtLeast(1.0)
            else
                effectiveDosingIsfMgdl
            pdpPredictedBg = predictBgCurvePdp(
                startBg        = currentBg,
                ci             = pdpEffectiveCi,
                fadeMins       = pdpFadeMins,
                iobArray       = iobArray,
                isfMgdl        = pdpIsfMgdl,
                learnedProfile = learnedProfile,
                ticks          = predictionTicks,
                systemDiaMins  = systemDiaMins
            )

            // Graph curve stored for display. Safety minimum is primary-only (unchanged).
            // pdpPredMin = post-peak minimum of the secondary curve.
            // Stuck-high: curve ran with ISF/ciStrength → falls slower → lands higher.
            // Rising: ci term holds BG up → lands higher.
            // Either way: just take the curve minimum.
            pdpPredMin = if (pdpPredictedBg.size > insulinPeakTicks)
                pdpPredictedBg.drop(insulinPeakTicks).minOrNull() ?: currentBg
            else
                pdpPredictedBg.minOrNull() ?: currentBg
        } else {
            pdpPredictedBg   = emptyList()
            pdpPredMin       = predictedMin
        }

        // Blended post-peak predMin — used for insulinReq calculation.
        // Safety gates (SUSPEND, CAUTION) always use the primary predictedMinSafety
        // directly. Reason: PDP secondary curve predicts BG stays high due to resistance,
        // but at 90% blend the secondary full-curve minimum can dip below lowGuard
        // (IOB still pulls BG down eventually), triggering false suspends. The safety
        // system must always see the most conservative (primary IOB-physics) prediction.
        //
        // (Previously assigned to a `blendedPredMinSafety` alias of predictedMinSafety
        // whose name implied blending — confusing because no blending actually happened.
        // Removed to make the architectural property impossible to miss: safety reads
        // predictedMinSafety directly; all blending is in blendedPredMin, which is
        // dosing-only.)
        val blendedPredMin = predictedMin * (1.0 - effectivePdpBlend) + pdpPredMin * effectivePdpBlend

        // ── ICE peak weighting in the dosing decision ─────────────────────────────
        // Without this: insulinReq is driven by blendedPredMin alone, which means
        // the loop only doses against where BG eventually SETTLES. During an
        // announced meal, IOB suppresses pred_min early (often above warn even
        // mid-meal), so insulinReq stays small and the loop under-doses against
        // the actual MEAL PEAK that BG is heading toward.
        //
        // With this: when iceBlendWeight > 0, the dosing target is a weighted
        // blend of an IOB-AWARE meal projection peak (ice_max) and the conventional
        // blendedPredMin.
        //
        // **Why IOB-aware** (not pure no-insulin projection):
        // A no-insulin projection (orange line ignoring IOB entirely) would never
        // converge — even with 5U IOB heading for hypo, it would still say
        // "meal projects to 10 mmol, dose more!" The IOB-aware version subtracts
        // the existing insulin's per-tick activity from the meal effect, so:
        //   • Before dosing: meal pressure dominates → ice_max high → dose hard
        //   • After dosing: IOB drops the projection → ice_max ≈ target → coast
        //   • Over-dosed: IOB outweighs meal → ice_max dips → safety blocks SMBs
        //
        // The orange chart line and the dosing metric use the SAME projection, so
        // what the user sees on the chart is what the loop is dosing toward.
        // No further insulin is assumed in the projection — only existing IOB.
        //
        // The blend weight is the same iceBlendWeight that controls effectiveCi
        // blending — one slider, consistent semantics:
        //   • 0.0: dose against trough only (current behavior, conservative)
        //   • 0.5: dose against midpoint of (IOB-aware ice_max, trough)
        //   • 1.0: dose against IOB-aware ice_max (most aggressive — pre-empts meal)
        //
        // Safety: predictedMinSafety < warnGuard / lowGuard gates still apply.
        // SMBs are blocked if actual trough heads below warn.
        val iobAwareIceProjection: List<Double> = if (iceFutureMgdlPerH.isNotEmpty()) {
            val out = ArrayList<Double>(iceFutureMgdlPerH.size)
            var bg = currentBg
            iceFutureMgdlPerH.take(predictionTicks).forEachIndexed { i, mgdlPerH ->
                val iceAddMgdl = mgdlPerH * (5.0 / 60.0)
                // Existing IOB activity at this tick — same formula as `bgi` at the
                // top of this function (activity * ISF * 5_min). When the iobArray
                // doesn't extend this far forward, treat insulin effect as exhausted.
                val iobDropMgdl = if (i < iobArray.size)
                    iobArray[i].activity * effectiveDosingIsfMgdl * 5.0
                else 0.0
                bg += iceAddMgdl - iobDropMgdl
                out.add(bg.coerceIn(39.0, 401.0))
            }
            out
        } else emptyList()
        val iceOnlyMax: Double = iobAwareIceProjection.maxOrNull() ?: currentBg
        val dosingMetric: Double = if (iceBlendWeight > 0.0)
            iceBlendWeight * iceOnlyMax + (1.0 - iceBlendWeight) * blendedPredMin
        else
            blendedPredMin

        // ── ICE-aware safety floor (restored ice-step45) ─────────────────────────
        // The dosing metric above respects iceBlendWeight by blending two SCALARS
        // (iceOnlyMax peak, blendedPredMin trough). That's correct for dose magnitude
        // — it captures "how high do we expect BG to peak vs. how deep does the
        // trough land". But it's wrong for SAFETY:
        //
        //   When a meal is mostly absorbed and IOB dominates, iobAwareIceProjection
        //   only descends — its maxOrNull() collapses to ~currentBg (the first tick),
        //   which is NOT a peak. The blend math then says "trust currentBg, ignore
        //   the descent", and SUSPEND/CAUTION never fire even as BG plunges through
        //   the floor. Travis hit this with BG 10.7, 10.68U IOB, meal at 151m/299m,
        //   1g carbs remaining: ice_max=10.0 (false peak), metric=6.8 (false safe).
        //
        // The correct construction (pre-step42, restored here) is to blend the two
        // CURVES per-tick by iceBlendWeight, then take the trough of the blended
        // curve. This gives a single number that physically represents "the lowest
        // BG along a curve weighted by ICE trust" — which is exactly what SUSPEND
        // wants to read. At iceBlendWeight = 0, it collapses to predictedBg.min() —
        // identical to pre-step27 behaviour. At iceBlendWeight = 1.0, it follows the
        // IOB-aware ICE curve all the way to its (floored) trough.
        //
        // dosingMetric stays unchanged for insulinReq / iceActive / SMB-trigger
        // display. Only the SUSPEND/CAUTION gates and the main "pred_min=" display
        // switch to predictedMinSafety below.
        val predictedMinSafety: Double =
            if (iceBlendWeight > 0.0 && iobAwareIceProjection.isNotEmpty()) {
                val n = minOf(predictedBg.size, iobAwareIceProjection.size)
                var minBg = Double.POSITIVE_INFINITY
                for (i in 0 until n) {
                    val blended = predictedBg[i] * (1.0 - iceBlendWeight) +
                        iobAwareIceProjection[i] * iceBlendWeight
                    if (blended < minBg) minBg = blended
                }
                if (minBg.isFinite()) minBg
                else (predictedBg.minOrNull() ?: currentBg)
            } else {
                predictedBg.minOrNull() ?: currentBg
            }

        // Expose PDP prediction to next-cycle accuracy scoring in SmartInsulinPlugin.
        // These are the t+5min values (first tick) — compared against actual BG next cycle.
        val pdpPredAt5  = pdpPredictedBg.firstOrNull() ?: currentBg
        val iobPredAt5  = predictedBg.firstOrNull() ?: currentBg

        // Populate rT.predBGs.IOB for the overview prediction graph.
        // Uses predictedBgDisplay (no ICE blend) so the cyan line shows the pure
        // "what insulin alone does" curve — the historical "fasting prediction"
        // semantic. Internal safety calculations still read predictedBg (ICE-aware).
        val rawPrediction = mutableListOf<Int>()
        predictedBgDisplay.take(predictionTicks)
            .forEach { rawPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
        rT.predBGs = app.aaps.core.interfaces.aps.Predictions()
        rT.predBGs?.IOB = rawPrediction

        // PDP curve — UAM slot renders as orange/yellow in AAPS overview graph,
        // clearly distinct from the primary IOB cyan line.
        // Also populate ZT as fallback in case enableUAM is false in OapsProfile.
        if (effectivePdpBlend > 0.0 && pdpPredictedBg.isNotEmpty()) {
            val rawPdpPrediction = mutableListOf<Int>()
            pdpPredictedBg.take(predictionTicks)
                .forEach { rawPdpPrediction.add(it.coerceIn(39.0, 401.0).toInt()) }
            // UAM = orange line (visually distinct from cyan IOB line)
            rT.predBGs?.UAM = rawPdpPrediction
            // ZT fallback removed — ZT is cyan like IOB, defeats the purpose of distinction
        }

        // ── ICE forward prediction line ───────────────────────────────────────────────
        // Same projection used for the dosing metric (iobAwareIceProjection above).
        // Shows the meal effect minus the existing IOB's contribution — i.e. "if the
        // loop coasts on current IOB through the meal, here's where BG goes."
        // This makes the chart line semantically aligned with what the loop is dosing
        // toward (ice_max), so what the user sees is what the loop is acting on.
        //
        // **Slot selection**: AAPS chart renders UAM and IOB slots unconditionally. The COB
        // slot is gated on AAPS having tracked carbs > 0 (which isn't the case for announced
        // meals — they bypass the AAPS COB system). So we prefer UAM when PDP isn't using
        // it, and fall back to COB only as a secondary (where rendering may not happen).
        if (iobAwareIceProjection.isNotEmpty()) {
            val icePrediction = iobAwareIceProjection.map { it.toInt() }
            // If PDP didn't claim UAM this cycle, use it for ICE — guarantees rendering.
            // PDP only takes UAM when effectivePdpBlend > 0 AND pdpPredictedBg is non-empty.
            if (rT.predBGs?.UAM.isNullOrEmpty()) {
                rT.predBGs?.UAM = icePrediction
            }
            // Always also write to COB as a secondary — works on builds that do render the
            // slot, no harm on builds that don't.
            rT.predBGs?.COB = icePrediction
        }

        // ── IOB / headroom ────────────────────────────────────────────────────
        // Fasting max IOB: caps IOB during FASTING to prevent over-stacking when PDP
        // drives more aggressive dosing. 0.0 = disabled (use global max IOB).
        val effectiveMaxIob = if (
            fastingMaxIobU > 0.0 &&
            mealMode == MealMode.FASTING
        ) minOf(oapsProfile.max_iob, fastingMaxIobU)
        else oapsProfile.max_iob

        val iobHeadroom  = (effectiveMaxIob - currentIob).coerceAtLeast(0.0)
        val iobOk        = currentIob < effectiveMaxIob
        val bgAboveGuard = currentBg - lowGuardMgdl

        // ── insulinReq ────────────────────────────────────────────────────────
        // Uses the dosing metric (blendedPredMin when ICE inactive, or the
        // ICE-weighted peak/trough blend when ICE is engaged — see dosingMetric
        // computation above). When ICE is driving, this lets the loop dose
        // against the projected peak so it pre-empts the meal rise instead of
        // waiting for the trough to fall before responding.
        val predMinGapMgdl = (dosingMetric - targetBg).coerceAtLeast(0.0)
        val insulinReq     = predMinGapMgdl / effectiveDosingIsfMgdl

        // iceActive: true when ICE is materially shifting the dosing target above
        // pure blendedPredMin. Used in two places:
        //   1. Trigger label — shows "ice_max(...)" instead of "predMinGap(...)"
        //   2. SMB/TBR sizing — when ICE is driving the dose, bypass the aggression
        //      multiplier. Rationale: the dose came from macros → ice_max → gap →
        //      insulinReq, which already encodes the right amount of insulin for
        //      the announced food. Multiplying by aggression on top double-counts
        //      and over-doses. Aggression was a hack for observation-only UAM
        //      where dose intent wasn't otherwise quantifiable; with announced
        //      meals we have the right number directly.
        // The 0.5 mg/dL floor (~0.03 mmol) avoids triggering on rounding noise
        // when dosingMetric and blendedPredMin happen to coincide.
        val iceActive = iceBlendWeight > 0.0 && dosingMetric > blendedPredMin + 0.5

        // ── Reason string header ──────────────────────────────────────────────
        val sb = StringBuilder()
        // Pipe-separated compact format — each key piece separated by " | "
        sb.append("SI mode=${mealMode.label}")
        sb.append(" | BG=${fmt(currentBg, isMmol)}")
        sb.append(" | d=${fmt(delta, isMmol)}")
        sb.append(" | IOB=${"%.2f".format(Locale.US, currentIob)}/${"%.0f".format(Locale.US, oapsProfile.max_iob)}")
        sb.append(" | pred_min=${fmt(predictedMinSafety, isMmol)} lo=${fmt(lowGuardMgdl, isMmol)} warn=${fmt(warnGuardMgdl, isMmol)}")
        // ICE peak-blended dosing diagnostic — only when ICE is actively shifting
        // the dosing target away from pure pred_min. Shows the ICE-only peak
        // (orange UAM line on the chart) and the resulting weighted metric, so
        // the user can see why insulinReq is higher than pred_min alone would
        // suggest. ice_max is invariant to insulin dosing — only meal absorption
        // brings it down over time.
        if (iceBlendWeight > 0.0) {
            sb.append(" | ICE-dose: ice_max=${fmt(iceOnlyMax, isMmol)}" +
                          " → metric=${fmt(dosingMetric, isMmol)}" +
                          " (blend=${"%.2f".format(Locale.US, iceBlendWeight)})")
        }
        if (effectivePdpBlend > 0.0) {
            // For stuck-high: secondary curve used pdpIsfMgdl = effectiveDosingIsfMgdl/ciStrength
            // Show primary→secondary ISF so the log reflects what the curve actually used
            val pdpIsfDisplay = if (pdpSyntheticCi > 0.0)
                effectiveDosingIsfMgdl / pdpCiStrength.coerceAtLeast(1.0)
            else
                effectiveDosingIsfMgdl  // rising: same ISF, ci term does the work
            // Show as "secISF=0.38mmol (÷5)" so it's clear this is the secondary
            // curve ISF, not the dosing ISF, and why it's that value
            val isfStr = if (pdpSyntheticCi > 0.0)
                " secISF=${fmt(pdpIsfDisplay, isMmol)}${if (isMmol) "mmol/U" else "mg/dL/U"} gap=${fmt(predMinGapMgdl, isMmol)}"
            else ""
            sb.append(" | PDP(blend=${"%.2f".format(Locale.US, effectivePdpBlend)} ci×${"%.2f".format(Locale.US, pdpCiStrength)} fade=${pdpFadeMins.toInt()}m pdp_min=${fmt(pdpPredMin, isMmol)} blended=${fmt(blendedPredMin, isMmol)}$isfStr)")
        }
        sb.append(" | target=${fmt(targetBg, isMmol)}${if (isTempTarget) "(tmp)" else ""}")
        sb.append(" | ISF=${fmt(effectiveDosingIsfMgdl, isMmol)}")
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
            val insulinReqU     = bgUndershoot / effectiveDosingIsfMgdl
            // Defensive floor on profileBasal — prevents Inf/NaN from profileBasal=0
            // (pump-off, misconfigured profile, or near-zero basalMultiplier).
            // 0.01 U/hr is well below any realistic basal rate but non-zero.
            val effectiveBasal  = profileBasal.coerceAtLeast(0.01)
            val durationHours   = insulinReqU / effectiveBasal
            val durationMins    = (durationHours * 60.0).coerceIn(30.0, 90.0)
            return ((durationMins / 30.0).roundToLong() * 30).toInt().coerceIn(30, 90)
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
            // Uses predictedMinSafety — the ICE-aware per-tick curve blend min.
            // Restored ice-step45: step42's switch to dosingMetric (a scalar
            // peak-blend) masked descents when meals were nearly absorbed and
            // IOB dominated — the "peak" degenerated to currentBg and SUSPEND
            // wouldn't fire even as BG plunged. predictedMinSafety reflects
            // the actual trough of the blended curve, so SUSPEND fires when
            // the projection genuinely heads below lowGuard.
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
                    bgAboveGuard > 0.0 &&
                    insulinReq > 0.0

                val correctionUnits = if (smbAllowed) {
                    // The plugin passes exactly the right fraction for this cycle —
                    // either the UAM entry fraction or SMB_DELIVERY_FRACTION (0.5).
                    // Use it directly. coerceIn(0.1, 0.9) is the OpenAPS safety cap.
                    //
                    // ICE-driven dosing bypasses aggression: insulinReq from the
                    // macro → ice_max pipeline already reflects the announced food's
                    // dose requirement. Multiplying by aggression would double-count.
                    // ice-step42: ALSO bypass aggression when any food is on board
                    // (announced meal OR AAPS-system COB). The macro pipeline is the
                    // source of truth for meal-driven dosing; layering aggression on
                    // top is a second variable that doesn't need to be there. Only
                    // non-meal paths (pure fasting / dawn / observation-only UAM)
                    // keep aggression — those don't have macro-grounded dose intent
                    // and may need the extra responsiveness.
                    val hasFoodOnBoard = mealMode != MealMode.FASTING || mealData.mealCOB > 0.0
                    val effectiveAggression = if (iceActive || hasFoodOnBoard) 1.0 else aggressiveness
                    insulinReq * (uamSmbFraction * effectiveAggression).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction
                } else 0.0

                val bolusStep      = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
                val rawSmb         = if (correctionUnits > 0.0) (ceil(correctionUnits / bolusStep) * bolusStep) else 0.0
                val smbCap         = minOf(maxSmbU, iobHeadroom)
                val clampedSmb     = rawSmb.coerceAtMost(smbCap)
                val constrainedSmb = if (clampedSmb >= bolusStep) clampedSmb else 0.0

                // TBR correction is independent of smbAllowed — high temp target blocks
                // SMBs but still needs elevated TBR to bring predMin to the temp target.
                // insulinReq is already computed against targetBg (which IS the temp target
                // when active), so TBR naturally aims for 6.5 not 5.5.
                // Same aggression-bypass as SMB above: ICE-driven OR food-on-board.
                val tbrHasFoodOnBoard = mealMode != MealMode.FASTING || mealData.mealCOB > 0.0
                val tbrAggression  = if (iceActive || tbrHasFoodOnBoard) 1.0 else aggressiveness
                val tbrCorrectionU  = if (iobOk && insulinReq > 0.0) insulinReq * tbrAggression else 0.0
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
                        val missingInsulinU = missingBgMgdl / effectiveDosingIsfMgdl
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
                        // iceActive defined earlier (just below dosingMetric); reused here.
                        // When the meal expires, iobAwareIceProjection collapses to
                        // ~currentBg, dosingMetric ≈ blendedPredMin, iceActive=false,
                        // and the label naturally reverts to predMinGap.
                        val label = when {
                            iceActive   -> "ice_max"
                            pdpActive   -> "pdpMinGap"
                            else        -> "predMinGap"
                        }
                        val displayValue = if (iceActive) dosingMetric else blendedPredMin
                        "$label(${fmt(displayValue, isMmol)}->${fmt(targetBg, isMmol)})"
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

        // Store prediction snapshot for next-cycle PDP accuracy scoring.
        // Single atomic write — reader on a different thread always sees a matched tuple.
        predictionSnapshot = PredictionSnapshot(
            iobPredAt5Mgdl = iobPredAt5,
            pdpPredAt5Mgdl = pdpPredAt5,
            pdpBlendWeight = effectivePdpBlend
        )

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
    //
    // Thread-safety: a single @Volatile data-class reference replaces three loose primitives.
    // Atomic publication of the whole tuple prevents torn reads (e.g. reader seeing a new
    // IOB pred paired with the previous cycle's PDP pred, which would corrupt accuracy
    // scoring). Read all three via `predictionSnapshot` to guarantee they're a matched set.
    data class PredictionSnapshot(
        val iobPredAt5Mgdl: Double,
        val pdpPredAt5Mgdl: Double,
        val pdpBlendWeight: Double
    )

    @Volatile
    var predictionSnapshot: PredictionSnapshot = PredictionSnapshot(0.0, 0.0, 0.0)
        private set

    // Compatibility getters — keep existing call sites in SmartInsulinPlugin working.
    // For correctness when reading multiple fields, prefer `predictionSnapshot` directly
    // so all three come from the same cycle.
    val lastIobPredAt5Mgdl: Double get() = predictionSnapshot.iobPredAt5Mgdl
    val lastPdpPredAt5Mgdl: Double get() = predictionSnapshot.pdpPredAt5Mgdl
    val lastPdpBlendWeight: Double get() = predictionSnapshot.pdpBlendWeight

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
    // Secondary curve for PDP — caller passes the appropriate ISF:
    //   stuck-high: isfMgdl = dosingIsfMgdl / ciStrength  → curve falls slower → higher predMin
    //   rising:     isfMgdl = dosingIsfMgdl               → ci term holds BG up
    // No stuckHighMode branch needed — ISF selection in the caller is the whole mechanism.
    private fun predictBgCurvePdp(
        startBg:        Double,
        ci:             Double,
        fadeMins:       Double,
        iobArray:       Array<IobTotal>,
        isfMgdl:        Double,
        learnedProfile: LearnedInsulinProfile,
        ticks:          Int,
        systemDiaMins:  Double
    ): List<Double> {
        val fadeTicks   = (fadeMins / 5.0).coerceAtLeast(1.0)
        var bg          = startBg
        val predictions = mutableListOf<Double>()
        for (tick in 1..ticks) {
            val activity = getActivityAtMinute(tick * 5, iobArray, learnedProfile, systemDiaMins)
            val iobDelta = -(activity * isfMgdl * 5.0)
            val predDev  = ci * (1.0 - minOf(1.0, (tick - 1) / fadeTicks))
            bg += iobDelta + predDev
            predictions.add(bg)
        }
        return predictions
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
            lastActivity * exp(-extraTicks * 0.05)
        }

        // Multiply by timeScale to preserve AUC.
        // e.g. if DIA is half as long, activity at each point must be twice as high.
        return max(0.0, baseActivity * timeScale)
    }

    companion object {
        private const val MMOL_TO_MGDL           = 18.0
        private const val TBR_WINDOW_HOURS       = 0.5
        private const val REBOUND_SMB_GATE       = 0.825 // SMBs unlock at 75% of window: taper=0.3+(0.7×0.75)=0.825
        // Tick size for prediction curves. All BG forecasts step in 5-min increments to align
        // with CGM cadence. Changing this requires re-deriving all *_TICK constants below.
        private const val TICK_MINUTES               = 5
        private const val TICKS_AT_30MIN_INDEX       = 5    // tick 6 = t+30min; list is 0-indexed → index 5
        private const val TICKS_AT_60MIN_INDEX       = 11   // tick 12 = t+60min; list is 0-indexed → index 11
        // delta is mg/dL per 5-min CGM cycle — threshold is 1.0 mmol in a single reading.
        // The previous formula divided by 5 which would give 0.4 mmol/min — wrong unit,
        // and far too sensitive. 2.0 mmol/5min was the previous correction but rarely fires
        // in practice (catches only CGM artifacts). 1.0 mmol/5min (18 mg/dL) is the
        // middle ground that catches genuine fast drops without false-positives.
        private const val FALLING_FAST_MGDL_PER_5MIN = 1.0 * MMOL_TO_MGDL  // 18 mg/dL = 1.0 mmol per 5-min cycle
        private const val PEAK_LEARNING_MIN_SAMPLES  = 5
        private const val NEUTRAL_TEMP_EPSILON       = 1e-6  // floating-point tolerance for neutral-temp detection
        // Floor for rebound taper in caution zone — prevents delivering near-zero basal
        // while BG is already heading toward the warn guard.
        private const val CAUTION_REBOUND_TAPER_FLOOR = 0.5
        // Last-resort ISF when BOTH dosingIsfMgdl and profile ISF are invalid (not finite
        // or non-positive). 50 mg/dL/U ≈ 2.8 mmol/U — high end of typical adult T1D.
        //
        // Direction-of-safety rationale: higher ISF means smaller insulinReq for the same
        // BG gap, which means LESS insulin delivered. Under total uncertainty we want the
        // conservative (under-delivery) failure mode rather than the aggressive (over-
        // delivery) one. Under-response leaves the user mildly high until upstream
        // recovers — recoverable. Over-delivery on broken inputs is not.
        //
        // This branch should never actually fire — dosingIsfMgdl and profile ISF would
        // have to be simultaneously invalid, which requires a deep upstream failure plus
        // a corrupted profile. If you see this fallback used in console errors, that's
        // a signal something is seriously wrong upstream that needs investigating.
        private const val ABSOLUTE_FALLBACK_ISF_MGDL = 50.0
    }
}