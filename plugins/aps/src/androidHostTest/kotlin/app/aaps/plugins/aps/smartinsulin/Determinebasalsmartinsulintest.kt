package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import android.text.Spanned
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.profile.Profile
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DetermineBasalSmartInsulinTest {

    // ── Fake APSResult — avoids Mockito setter limitations ───────────────────

    private inner class FakeAPSResult : APSResult {
        override var date: Long                          = 0
        override var reason: String                      = ""
        override var rate: Double                        = 0.0
        override var percent: Int                        = 0
        override var duration: Int                       = 0
        override var smb: Double                         = 0.0
        override var usePercent: Boolean                 = false
        override var carbsReq: Int                       = 0
        override var carbsReqWithin: Int                 = 0
        override var deliverAt: Long                     = 0
        override var targetBG: Double                    = 0.0
        override var hasPredictions: Boolean             = false
        override var variableSens: Double?               = null
        override var isfMgdlForCarbs: Double?            = null
        override var scriptDebug: List<String>?          = null
        override val predictionsAsGv: MutableList<GV>   = mutableListOf()
        override val latestPredictionsTime: Long         = 0
        override val isChangeRequested: Boolean          = false
        override var isTempBasalRequested: Boolean       = false
        override val carbsRequiredText: String           = ""
        override var inputConstraints: Constraint<Double>?  = null
        override var rateConstraint: Constraint<Double>?    = null
        override var percentConstraint: Constraint<Int>?    = null
        override var smbConstraint: Constraint<Double>?     = null
        override var algorithm: APSResult.Algorithm      = APSResult.Algorithm.SMB
        override var autosensResult: AutosensResult?     = null
        override var iobData: Array<IobTotal>?           = null
        override var glucoseStatus: GlucoseStatus?       = null
        override var currentTemp: CurrentTemp?           = null
        override var oapsProfile: OapsProfile?           = null
        override var oapsProfileAutoIsf: OapsProfileAutoIsf? = null
        override var mealData: MealData?                 = null

        // Mirrors the real DetermineBasalResult.with() (implementation/.../DetermineBasalResult.kt) —
        // the original version here was a no-op (`= this`), so rate/duration/smb/reason/predictions
        // never actually got applied and every assertion silently checked the untouched defaults.
        override fun with(result: RT): APSResult = this.also {
            reason = result.reason.toString()
            if (result.rate != null && result.duration != null) {
                isTempBasalRequested = true
                rate = maxOf(0.0, result.rate ?: 0.0)
                duration = result.duration ?: 0
            }
            smb = result.units ?: 0.0
            targetBG = result.targetBG ?: 0.0
            // Simple 1:1 mirror of predBGs.IOB — ticks -> predictions, unlike the real class's
            // predictionsAsGv (which skips index 0); kept simple since these tests assert directly
            // on tick count and value.
            predictionsAsGv.clear()
            result.predBGs?.IOB?.forEach { v ->
                predictionsAsGv.add(
                    GV(timestamp = 0L, value = v.toDouble(), raw = 0.0, noise = 0.0,
                       trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.IOB_PREDICTION)
                )
            }
        }
        override fun resultAsString(): String            = reason
        override fun resultAsSpanned(): Spanned          = mock()
        override fun newAndClone(): APSResult            = FakeAPSResult()
        override fun json(): JSONObject?                 = null
        override fun predictions(): Predictions?         = null
        override fun rawData(): Any                      = ""
    }

    // ── Mocks ────────────────────────────────────────────────────────────────

    private val glucoseStatus: GlucoseStatus = mock()
    private val currentTemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = null)
    private val mealData: MealData           = mock()
    private val oapsProfile: OapsProfile     = mock()
    private val profile: Profile             = mock()

    private lateinit var fakeResult: FakeAPSResult
    private lateinit var sut: DetermineBasalSmartInsulin

    @BeforeEach fun setUp() {
        fakeResult = FakeAPSResult()
        sut = DetermineBasalSmartInsulin { fakeResult }

        whenever(oapsProfile.sens).thenReturn(50.0)
        whenever(oapsProfile.current_basal).thenReturn(1.0)
        whenever(oapsProfile.target_bg).thenReturn(100.0)
        whenever(oapsProfile.min_bg).thenReturn(90.0)
        whenever(oapsProfile.max_bg).thenReturn(120.0)
        whenever(oapsProfile.enableSMB_always).thenReturn(true)
        whenever(oapsProfile.maxSMBBasalMinutes).thenReturn(30)
        whenever(oapsProfile.enableUAM).thenReturn(false)
        whenever(oapsProfile.max_basal).thenReturn(5.0)
        whenever(oapsProfile.max_iob).thenReturn(5.0)
        whenever(oapsProfile.max_daily_basal).thenReturn(1.5)
        whenever(oapsProfile.max_daily_safety_multiplier).thenReturn(3.0)
        whenever(oapsProfile.current_basal_safety_multiplier).thenReturn(4.0)

        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.date).thenReturn(System.currentTimeMillis())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun flatIobArray(iob: Double, activity: Double, size: Int = 120): Array<IobTotal> =
        Array(size) { t -> IobTotal(time = t * 60_000L, iob = iob, activity = activity) }

    private fun defaultLearned(mode: MealMode = MealMode.FASTING) =
        LearnedInsulinProfile.defaultFor(mode)

    private fun invoke(
        iobArray:          Array<IobTotal>      = flatIobArray(0.0, 0.0),
        learnedProfile:    LearnedInsulinProfile = defaultLearned(),
        lowGuardMmol:      Double               = 3.9,
        warnGuardMmol:     Double               = 4.5,
        microBolusAllowed: Boolean              = true,
        bgWentLow:         Boolean              = false,
        inReboundWindow:   Boolean              = false,
        maxSmbU:           Double               = 2.0,
        // Matches SmartInsulinPlugin.SMB_DELIVERY_FRACTION — the fraction the real plugin always
        // passes for FASTING mode. determine_basal's own default (1.0, i.e. full correction) only
        // applies if a caller omits this, which the real plugin never does.
        uamSmbFraction:    Double               = 0.5,
        // 1.0 = keep the whole SMB, i.e. dawn reduction OFF.
        //
        // Not cosmetic. currentTime below is the real clock and inDawnWindow is derived from it,
        // so a 0.0 reduction silently zeroed the SMB in every FASTING test with a rising delta —
        // but only when the suite happened to run between 04:00 and 09:00 local. Tests that passed
        // all afternoon failed the next morning with nothing changed. Nothing here exercises dawn
        // behaviour, so the harness turns it off; a future dawn test can pass its own value.
        dawnSmbReduction:  Double               = 1.0,
        msSinceLastSuspend: Long                = 3600_000L
    ): FakeAPSResult {
        sut.determine_basal(
            glucoseStatus         = glucoseStatus,
            currentTemp           = currentTemp,
            iobArray              = iobArray,
            oapsProfile           = oapsProfile,
            mealData              = mealData,
            profileIsfMgdl        = 50.0,
            carbRatioGPerU        = 10.0,
            profile               = profile,
            learnedProfile        = learnedProfile,
            mealMode              = MealMode.FASTING,
            lowGuardMmol          = lowGuardMmol,
            warnGuardMmol         = warnGuardMmol,
            maxSmbU               = maxSmbU,
            maxTbrU               = 5.0,
            aggressiveness        = 1.0,
            tirSummary            = "100%",
            basalMultiplier       = 1.0,
            dosingIsfMgdl         = 50.0,
            microBolusAllowed     = microBolusAllowed,
            inReboundWindow       = inReboundWindow,
            msSinceLastSuspend    = msSinceLastSuspend,
            currentTime           = System.currentTimeMillis(),
            isTempTarget          = false,
            profileTargetMgdl     = 100.0,
            dawnWindowStartHour   = 4,
            dawnWindowEndHour     = 9,
            dawnSmbReduction      = dawnSmbReduction,
            bgWentLow             = bgWentLow,
            activityLevel         = ActivityMonitor.ActivityLevel.SEDENTARY,
            activityTargetOffsetMmol = 0.0,
            cgmSmbFraction        = 1.0,
            cgmDeltaPlausible     = true,
            cgmWarmupReason       = "",
            uamSmbFraction        = uamSmbFraction
        )
        return fakeResult
    }

    // ── SUSPEND zone ─────────────────────────────────────────────────────────

    @Test fun `SUSPEND when predicted BG drops below low guard`() {
        // Current BG (65) is already below lowGuard (3.9 mmol = 70.2 mg/dL), so predictedMinSafety
        // is guaranteed <= 65 regardless of the exact IOB-curve reprojection math — no need to
        // hand-derive a precise predicted trough from iob/activity to land reliably in SUSPEND.
        // Duration is a rounded 30/60/90 based on undershoot magnitude (suspendDurationMins) —
        // asserting it's a valid multiple rather than hardcoding one exact value.
        whenever(glucoseStatus.glucose).thenReturn(65.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.05))
        assertEquals(0.0, r.rate,     0.001)
        assertTrue(r.duration in setOf(30, 60, 90), "Suspend duration should be a valid rounded value, got ${r.duration}")
        assertTrue(r.isTempBasalRequested)
        assertEquals(0.0, r.smb,      0.001)
        assertTrue(r.reason.contains("SUSPEND"))
    }

    @Test fun `SUSPEND zone produces zero SMB`() {
        whenever(glucoseStatus.glucose).thenReturn(72.0)
        val r = invoke(iobArray = flatIobArray(iob = 1.5, activity = 0.04))
        assertEquals(0.0, r.smb, 0.001)
    }

    // ── CAUTION zone ─────────────────────────────────────────────────────────

    // Calibrated from an actual run: iob=0.3/activity=0.005 from BG=75 produced predictedMinSafety
    // ≈ 68.4 (a 6.6 mg/dL drop) — enough to slip below lowGuard (70.2) into SUSPEND. The
    // effective-age reprojection depends only on the activity/iob RATIO, so anchorU (and thus the
    // predicted drop) scales ~linearly with iob when that ratio is held fixed. Scaling both down
    // by ~0.27x (same ratio) targets a ~1.8 mg/dL drop instead, landing in CAUTION (confirmed by
    // the 30-min-TBR test below). The exact reduction fraction (warnFrac) is continuously
    // guard-relative — how far predictedMin sits between lowGuard and warnGuard — not a fixed 50%
    // constant, so this checks "meaningfully reduced from full profile basal" rather than an exact
    // percentage a caller can't reliably hit without executing the curve model.
    @Test fun `CAUTION zone reduces basal below full profile rate`() {
        whenever(glucoseStatus.glucose).thenReturn(78.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.08, activity = 0.0013))
        assertTrue(r.rate < 1.0, "CAUTION should reduce basal below the 1.0 U/hr profile rate, got ${r.rate}")
        assertTrue(r.rate >= 0.0, "Reduced basal should be non-negative")
        assertEquals(0.0, r.smb, 0.001)
        assertTrue(r.reason.contains("CAUTION"), "Expected CAUTION in reason, got: ${r.reason}")
    }

    @Test fun `CAUTION zone sets 30-minute TBR`() {
        whenever(glucoseStatus.glucose).thenReturn(78.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.08, activity = 0.0013))
        assertEquals(30, r.duration)
        assertTrue(r.isTempBasalRequested)
    }

    // ── NORMAL zone ──────────────────────────────────────────────────────────

    @Test fun `NORMAL zone uses profile basal when stable at target`() {
        // NORMAL zone always issues an explicit 30-min TBR at the computed rate — there's no
        // "skip, we're already at basal" shortcut in this code path (that only exists via
        // setTempBasal's skip_neutral_temps branch, which requires an active currentTemp AND
        // that preference enabled, neither of which apply here). The original assertions here
        // (duration=0, isTempBasalRequested=false) assumed a shortcut that doesn't exist.
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        assertEquals(1.0, r.rate, 0.001)
        assertEquals(30,  r.duration)
        assertTrue(r.isTempBasalRequested)
    }

    @Test fun `NORMAL zone allows SMB above target when microBolusAllowed`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), microBolusAllowed = true)
        assertTrue(r.smb > 0.0, "SMB should be > 0 when above target, got ${r.smb}")
        assertTrue(r.smb < 0.8, "SMB should be < full correction, got ${r.smb}")
        assertTrue(r.reason.contains("NORMAL"))
    }

    @Test fun `NORMAL zone blocks SMB when microBolusAllowed is false`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), microBolusAllowed = false)
        assertEquals(0.0, r.smb, 0.001)
    }

    @Test fun `NORMAL zone blocks SMB when BG falling fast`() {
        // There's no hardcoded "falling fast" SMB gate in the NORMAL branch itself (that only
        // exists via the SUSPEND zone's fallingIntoLow check, which needs delta < -36 mg/dL/5min —
        // the original -3.0 here never came close, on either field). What DOES zero out SMB here
        // is the ordinary insulinReq gate: ci = min(shortAvgDelta, delta) - bgi feeds the
        // prediction curve, and a large enough negative ci pulls predictedMin down to/below
        // target, making predMinGapMgdl (and so insulinReq) hit zero. -8.0 on both fields (ci
        // tapers over the first 60 min, cumulative factor 6.5) drops predictedMin by ~52 mg/dL —
        // from 140 to ~88, below the 100 target but still comfortably clear of the 81 warn guard.
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(-8.0)
        whenever(glucoseStatus.delta).thenReturn(-8.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertEquals(0.0, r.smb, 0.001)
    }

    @Test fun `SMB is capped by maxSmbU`() {
        // The actual SMB cap in this codebase is minOf(maxSmbU, iobHeadroom) — see
        // Determinebasalsmartinsulin.kt's smbCap calc. maxSMBBasalMinutes isn't referenced by
        // dosing math at all (unlike stock OpenAPS), so a large gap-to-target here should still
        // clamp down to the maxSmbU passed in, not to any current_basal/maxSMBBasalMinutes-derived value.
        whenever(glucoseStatus.glucose).thenReturn(300.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), maxSmbU = 0.5)
        assertTrue(r.smb <= 0.5001, "SMB must be <= the 0.5U maxSmbU cap, got ${r.smb}")
    }

    // ── Prediction graph ─────────────────────────────────────────────────────

    @Test fun `predictionsAsGv is populated with correct count`() {
        // default LearnedProfile has safeDiaMinutes=300 -> 300/5 = 60 ticks
        val r = invoke()
        assertEquals(60, r.predictionsAsGv.size)
    }

    @Test fun `predictions are cleared and repopulated each call`() {
        fakeResult.predictionsAsGv.add(
            GV(timestamp = 0L, value = 99.0, raw = null,
               trendArrow = TrendArrow.NONE, noise = null,
               sourceSensor = SourceSensor.UNKNOWN)
        )
        val r = invoke()
        assertEquals(60, r.predictionsAsGv.size)
    }

    @Test fun `zero IOB and zero delta predicts near-flat BG`() {
        whenever(glucoseStatus.glucose).thenReturn(110.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertEquals(110.0, r.predictionsAsGv.first().value, 1.0)
    }

    @Test fun `positive delta with no IOB predicts rising BG at t=1`() {
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        // ci = min(shortAvgDelta, delta) - bgi — both must be positive for a rising prediction,
        // since the curve takes the SLOWER of the two deltas as the safe carb-impact estimate.
        whenever(glucoseStatus.shortAvgDelta).thenReturn(5.0)
        whenever(glucoseStatus.delta).thenReturn(5.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertTrue(r.predictionsAsGv.first().value > 100.0, "Rising delta should push BG above 100")
    }

    // ── Safety clamps ────────────────────────────────────────────────────────

    @Test fun `a zero temp is still issued when skip_neutral_temps is on and profile basal is zero`() {
        // skip_neutral_temps drops a TBR whose rate equals the profile's current basal. On a pump
        // profile with a 0 U/h basal segment, a SUSPEND's rate of 0.0 equalled current_basal 0.0
        // and the zero temp was silently swallowed — the loop reported a suspend it never
        // commanded. A zero rate is never a "neutral" temp, whatever the profile says.
        whenever(oapsProfile.skip_neutral_temps).thenReturn(true)
        whenever(oapsProfile.current_basal).thenReturn(0.0)
        whenever(glucoseStatus.glucose).thenReturn(65.0)

        val r = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.05))

        assertTrue(r.reason.contains("SUSPEND"), "precondition: this scenario must reach a suspend branch")
        assertTrue(r.isTempBasalRequested, "The zero temp must actually be requested, not skipped as neutral")
        assertEquals(0.0, r.rate, 1e-9)
        assertTrue(r.duration > 0, "Suspend must carry a real duration, got ${r.duration}")
    }

    @Test fun `neutral temps are still skipped for a genuinely neutral non-zero rate`() {
        // Control for the test above: the neutral-temp optimisation must still work where it was
        // meant to — a normal-dosing rate landing exactly on profile basal.
        whenever(oapsProfile.skip_neutral_temps).thenReturn(true)
        whenever(oapsProfile.current_basal).thenReturn(1.0)
        whenever(glucoseStatus.glucose).thenReturn(100.0)

        val r = invoke(iobArray = flatIobArray(0.0, 0.0))

        assertFalse(r.isTempBasalRequested,
                    "A rate equal to profile basal should still be skipped, got rate=${r.rate}")
    }

    @Test fun `carb impact is clamped so a sensor artifact cannot inflate the prediction`() {
        // A sustained sensor artifact survives the min(shortAvgDelta, delta) blunting, and ci is
        // fed into every tick of the forward curve with a linear 60-min fade — total contribution
        // is ci * 6.5. Unclamped, a 200 mg/dL/5min jump would add ~1300 mg/dL of imaginary rise
        // to predictedMin and straight into insulinReq.
        whenever(glucoseStatus.glucose).thenReturn(150.0)
        whenever(glucoseStatus.delta).thenReturn(200.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(200.0)

        val r = invoke(iobArray = flatIobArray(0.0, 0.0))

        // CI_MAX_MGDL_PER_5MIN (27) * 6.5 ticks of faded contribution = 175.5 above the start BG.
        val peak = r.predictionsAsGv.maxOf { it.value }
        assertTrue(peak <= 150.0 + 27.0 * 6.5 + 1.0,
                   "Predicted peak must be bounded by the ci clamp, got $peak")
    }

    @Test fun `genuine carb impact below the clamp is passed through untouched`() {
        // Control: a real post-meal rise must not be truncated. 18 mg/dL/5min (1 mmol) is a brisk
        // but ordinary absorption rate and sits below CI_MAX_MGDL_PER_5MIN.
        whenever(glucoseStatus.glucose).thenReturn(150.0)
        whenever(glucoseStatus.delta).thenReturn(18.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(18.0)

        val r = invoke(iobArray = flatIobArray(0.0, 0.0))

        val peak = r.predictionsAsGv.maxOf { it.value }
        assertEquals(150.0 + 18.0 * 6.5, peak, 0.5,
                     "Sub-clamp carb impact should reach the prediction in full, got $peak")
    }

    @Test fun `a degenerate dosing ISF produces finite output`() {
        // dosingIsfMgdl is profile ISF scaled by learned multipliers and aggressiveness. Every
        // insulinReq-style calculation divides by it, so a near-zero value would divide into an
        // unbounded insulin request. Pins that the floor keeps every output finite.
        whenever(glucoseStatus.glucose).thenReturn(200.0)

        sut.determine_basal(
            glucoseStatus = glucoseStatus, currentTemp = currentTemp,
            iobArray = flatIobArray(0.0, 0.0), oapsProfile = oapsProfile, mealData = mealData,
            profileIsfMgdl = 50.0, carbRatioGPerU = 10.0,
            profile = profile, learnedProfile = defaultLearned(), mealMode = MealMode.FASTING,
            lowGuardMmol = 3.9, warnGuardMmol = 4.5, maxSmbU = 2.0, maxTbrU = 5.0,
            aggressiveness = 1.0, tirSummary = "100%", basalMultiplier = 1.0,
            dosingIsfMgdl = 0.0,
            microBolusAllowed = true, inReboundWindow = false, msSinceLastSuspend = 3600_000L,
            currentTime = System.currentTimeMillis(), isTempTarget = false, profileTargetMgdl = 100.0,
            dawnWindowStartHour = 4, dawnWindowEndHour = 9, dawnSmbReduction = 1.0,
            bgWentLow = false, activityLevel = ActivityMonitor.ActivityLevel.SEDENTARY,
            activityTargetOffsetMmol = 0.0, cgmSmbFraction = 1.0, cgmDeltaPlausible = true,
            cgmWarmupReason = "", uamSmbFraction = 0.5
        )

        assertTrue(fakeResult.rate.isFinite(), "TBR rate must be finite, got ${fakeResult.rate}")
        assertTrue(fakeResult.smb.isFinite(), "SMB must be finite, got ${fakeResult.smb}")
        assertTrue(fakeResult.smb <= 2.0 + 1e-9, "SMB must respect maxSmbU, got ${fakeResult.smb}")
        assertTrue(fakeResult.predictionsAsGv.all { it.value.isFinite() },
                   "Every predicted BG must be finite")
    }

    // ── Low guard / SMB gate boundary ────────────────────────────────────────

    /** Rising curve with negative IOB — the shape that produces insulinReq > 0 near the guard. */
    private fun recoveringFromLow(bgMgdl: Double) {
        whenever(glucoseStatus.glucose).thenReturn(bgMgdl)
        whenever(glucoseStatus.delta).thenReturn(12.6)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(12.6)
    }

    @Test fun `a BG displayed level with the low guard unlocks SMBs`() {
        // The real failure: guard 4.8 mmol is stored as 86.4 mg/dL, CGM reports a whole 86, and
        // both print as "4.8". SMBs were held for a 0.4 mg/dL gap nothing on screen could show.
        recoveringFromLow(86.0)
        val r = invoke(iobArray = flatIobArray(iob = -2.0, activity = -0.02),
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0, uamSmbFraction = 1.0)
        assertFalse(r.reason.contains("trigger=blocked"), r.reason)
        assertTrue(r.smb > 0.0, "SMB should be unlocked at the guard: ${r.reason}")
    }

    @Test fun `a BG genuinely below the low guard still blocks SMBs`() {
        // 84 vs 86.4 is 2.4 mg/dL under — a full display step below, and still blocked.
        recoveringFromLow(84.0)
        val r = invoke(iobArray = flatIobArray(iob = -2.0, activity = -0.02),
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0, uamSmbFraction = 1.0)
        assertTrue(r.reason.contains("trigger=blocked"), r.reason)
        assertEquals(0.0, r.smb, 0.001)
    }

    @Test fun `the tolerance does not reach the next reading down`() {
        // 85 mg/dL displays as 4.7, a step below the guard — it must stay blocked, or the
        // tolerance would be quietly lowering the guard rather than matching the display.
        recoveringFromLow(85.0)
        val r = invoke(iobArray = flatIobArray(iob = -2.0, activity = -0.02),
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0, uamSmbFraction = 1.0)
        assertTrue(r.reason.contains("trigger=blocked"), r.reason)
    }

    // ── Prediction with negative IOB ─────────────────────────────────────────

    // After a suspend IOB goes negative. The curve clamps activity at zero, but BGI was computed
    // from SIGNED activity — so negative IOB produced a positive BGI that was subtracted from the
    // observed rise and never added back, flattening (or inverting) the forecast on a recovering BG.

    @Test fun `negative IOB with a rising delta forecasts a rise, not a flat line`() {
        // The reported frame: BG 5.2 rising +0.2/5min, IOB -1.46U after a low-guard suspend.
        whenever(glucoseStatus.glucose).thenReturn(93.6)
        whenever(glucoseStatus.delta).thenReturn(3.6)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(3.6)
        val r = invoke(iobArray = flatIobArray(iob = -1.46, activity = -0.015),
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        val pred = r.predictionsAsGv.map { it.value }
        assertTrue(pred.size > 12, "expected a forecast, got ${pred.size} ticks")
        assertTrue(pred[11] > 93.6 + 5.0, "forecast should climb over the hour: ${pred.take(13)}")
    }

    @Test fun `negative IOB with a small rise no longer forecasts a fall under the guard`() {
        // BG 4.9 rising +0.1: previously BGI outweighed the rise, the curve fell to 4.5, and the
        // loop stayed suspended below a 4.8 guard on a BG that was climbing.
        whenever(glucoseStatus.glucose).thenReturn(88.2)
        whenever(glucoseStatus.delta).thenReturn(1.8)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(1.8)
        val r = invoke(iobArray = flatIobArray(iob = -1.43, activity = -0.015),
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        val pred = r.predictionsAsGv.map { it.value }
        assertTrue(pred.minOrNull()!! >= 88.0, "forecast must not dip below the current reading: ${pred.take(13)}")
    }

    @Test fun `withheld basal is not projected as an extra accelerating rise`() {
        // Guards the conservative choice: the forecast follows the observed trend and lets it fade,
        // rather than modelling negative IOB as a separate upward push stacked on top. ci decays to
        // zero over 12 ticks, so the whole climb is bounded by the trend alone: 3.6 x 78/12 = 23.4.
        whenever(glucoseStatus.glucose).thenReturn(93.6)
        whenever(glucoseStatus.delta).thenReturn(3.6)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(3.6)
        val r = invoke(iobArray = flatIobArray(iob = -1.46, activity = -0.015),
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        val pred = r.predictionsAsGv.map { it.value }
        assertTrue(pred.last() <= 93.6 + 23.4 + 1.0, "rise exceeds the observed trend: last=${pred.last()}")
    }

    @Test fun `positive IOB still pulls the forecast down`() {
        // Normal running is untouched: insulin acting on a flat BG forecasts a fall.
        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.02))
        val pred = r.predictionsAsGv.map { it.value }
        assertTrue(pred.last() < 120.0, "insulin on board should forecast a fall: last=${pred.last()}")
    }

    @Test fun `negative IOB forecasts exactly like IOB near zero`() {
        // What was asked for, stated directly: after a zero-temp or suspend, the forecast should
        // be the one you'd get with ~0 IOB, not a flattened version of it.
        whenever(glucoseStatus.glucose).thenReturn(93.6)
        whenever(glucoseStatus.delta).thenReturn(3.6)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(3.6)
        // Exactly zero, not 0.05: 0.05U is real positive insulin and the curve correctly pulls the
        // tail down by it (~2 mg/dL across the DIA). Zero is the like-for-like baseline.
        val nearZero = invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0)).predictionsAsGv.map { it.value }

        fakeResult = FakeAPSResult(); sut = DetermineBasalSmartInsulin { fakeResult }
        val negative = invoke(iobArray = flatIobArray(iob = -1.46, activity = -0.015)).predictionsAsGv.map { it.value }

        assertEquals(nearZero, negative, "negative IOB should forecast exactly like IOB ~0")
    }

    // ── SMB taper after a low ────────────────────────────────────────────────

    // SMBs used to stay blocked until 75% of the rebound window and then jump straight to full
    // size. They now ramp in from the gate to full size at the window's end. With the default
    // 60-min window the taper is 0.3 + 0.7 x (mins/60), so the gate (0.825) is at 45 min.

    /** Flat 10mmol, zero IOB — a steady SMB request of 1.60U against a 5.5 target and ISF 50. */
    private fun reboundSmbAt(minutes: Double): Double {
        freshSut()
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        return invoke(inReboundWindow = true, uamSmbFraction = 1.0,
                      msSinceLastSuspend = (minutes * 60_000).toLong()).smb
    }

    private fun freshSut() { fakeResult = FakeAPSResult(); sut = DetermineBasalSmartInsulin { fakeResult } }

    @Test fun `no SMBs before the gate, unchanged`() {
        assertEquals(0.0, reboundSmbAt(30.0), 1e-9)
        assertEquals(0.0, reboundSmbAt(45.0), 1e-9)   // exactly at the gate — ramp starts from zero
    }

    @Test fun `SMBs ramp in between the gate and the end of the window`() {
        val quarter = reboundSmbAt(48.75)   // 25% of the way from gate to end
        val half    = reboundSmbAt(52.5)    // 50%
        val full    = reboundSmbAt(60.0)
        assertTrue(quarter > 0.0, "should have started: $quarter")
        assertTrue(quarter < half, "should grow: $quarter -> $half")
        assertTrue(half < full, "should grow: $half -> $full")
        assertEquals(0.80, half, 1e-9)      // half of 1.60, on the 0.05 step
    }

    @Test fun `the taper never gives more than the old gate did`() {
        // The old rule gave the full SMB from the gate onward; tapered must stay at or under it.
        val full = reboundSmbAt(60.0)
        for (m in listOf(45.0, 47.0, 50.0, 53.0, 56.0, 59.0)) {
            assertTrue(reboundSmbAt(m) <= full + 1e-9, "taper exceeded full SMB at ${m}min")
        }
    }

    @Test fun `outside a rebound the SMB is untouched`() {
        freshSut()
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        val normal = invoke(inReboundWindow = false, uamSmbFraction = 1.0).smb
        assertEquals(reboundSmbAt(60.0), normal, 1e-9)
    }

    @Test fun `the reason string names the rebound taper, not the maxSMB cap`() {
        freshSut()
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        val r = invoke(inReboundWindow = true, uamSmbFraction = 1.0, msSinceLastSuspend = 3_150_000L)
        assertTrue(r.reason.contains("rebound taper"), r.reason)
        assertFalse(r.reason.contains("capped at"), r.reason)
    }
}
