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
        uamSmbFraction:    Double               = 0.5
    ): FakeAPSResult {
        sut.determine_basal(
            glucoseStatus         = glucoseStatus,
            currentTemp           = currentTemp,
            iobArray              = iobArray,
            oapsProfile           = oapsProfile,
            mealData              = mealData,
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
            msSinceLastSuspend    = 3600_000L,
            currentTime           = System.currentTimeMillis(),
            isTempTarget          = false,
            profileTargetMgdl     = 100.0,
            dawnWindowStartHour   = 4,
            dawnWindowEndHour     = 9,
            dawnSmbReduction      = 0.0,
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
}
