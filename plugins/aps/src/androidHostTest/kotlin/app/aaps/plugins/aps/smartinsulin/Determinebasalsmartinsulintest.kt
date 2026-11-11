package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import android.text.Spanned
import kotlinx.serialization.json.JsonObject
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
        override suspend fun isChangeRequested(): Boolean = false
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
        /** The forecast with the food still to come, drawn as the COB line; null when it is not drawn. */
        var cobLine: List<Int>?                          = null
        /** The forecast with the fat and protein at the rate BG shows, drawn in the UAM slot (yellow); null when not drawn. */
        var uamLine: List<Int>?                          = null

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
            cobLine = result.predBGs?.COB
            uamLine = result.predBGs?.UAM
            predictionsAsGv.clear()
            result.predBGs?.IOB?.forEach { v ->
                predictionsAsGv.add(
                    GV(timestamp = 0L, value = v.toDouble(), raw = 0.0, noise = 0.0,
                       trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.IOB_PREDICTION)
                )
            }
        }
        override suspend fun resultAsString(): String    = reason
        override fun newAndClone(): APSResult            = FakeAPSResult()
        override fun json(): JsonObject?                 = null
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

    /**
     * iobArray as AAPS would build it for one dose: [iob] and [activity] now, then decaying along the
     * harness's insulin curve (peak 75, DIA 300). The forecast reads every tick, as stock oref does,
     * so a flat line here would mean insulin acting at full strength for 4 hours.
     * Negative or zero values stay flat; the forecast counts negative action as zero anyway.
     */
    private fun flatIobArray(iob: Double, activity: Double, size: Int = 48): Array<IobTotal> {
        if (iob <= 0.0 || activity <= 0.0)
            return Array(size) { t -> IobTotal(time = t * 300_000L, iob = iob, activity = activity) }
        val peak = LearnedInsulinProfile.FALLBACK_PEAK_MINS
        val dia  = LearnedInsulinProfile.FALLBACK_DIA_MINS
        val age  = InsulinActivityCurve.effectiveAgeMinutes(activity, iob, peak, dia)
        val units = iob / InsulinActivityCurve.iobFraction(age, peak, dia).coerceAtLeast(0.02)
        return Array(size) { t ->
            IobTotal(time = t * 300_000L,
                     iob = units * InsulinActivityCurve.iobFraction(age + t * 5.0, peak, dia),
                     activity = if (t == 0) activity else units * InsulinActivityCurve.activityFraction(age + t * 5.0, peak, dia))
        }
    }

    private fun defaultLearned(mode: MealMode = MealMode.FASTING) =
        LearnedInsulinProfile.defaultFor(mode)

    /**
     * Runs the loop once. It returns the same result object every time: copy out what a test needs (smb, rate, a min of
     * the predictions) before the next call, or two results compare as equal because they are one object.
     */
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
        msSinceLastSuspend: Long                = 3600_000L,
        riseTurn:          RiseTurnGuard.Result? = null,
        mealMode:          MealMode             = MealMode.FASTING,
        foodFadeMins:      Int                  = 60,
        foodToCome:        FoodToCome?          = null,
        foodListG:         Double?              = null,
        preBolusThisCycleU: Double              = 0.0,
        minsSincePreBolus: Int?                 = null,
        foodMatchTopUp:    FoodMatch.TopUp?     = null
    ): FakeAPSResult {
        sut.determine_basal(
            glucoseStatus         = glucoseStatus,
            currentTemp           = currentTemp,
            iobArray              = iobArray,
            oapsProfile           = oapsProfile,
            mealData              = mealData,
            profile               = profile,
            learnedProfile        = learnedProfile,
            mealMode              = mealMode,
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
            uamSmbFraction        = uamSmbFraction,
            riseTurn              = riseTurn,
            foodFadeMins          = foodFadeMins,
            foodToCome            = foodToCome,
            foodListG             = foodListG,
            carbRatio             = 5.0,
            preBolusThisCycleU = preBolusThisCycleU,
            minsSincePreBolus = minsSincePreBolus,
            foodMatchTopUp = foodMatchTopUp
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

    @Test fun `CAUTION zone over max IOB keeps its reduced basal`() {
        // Below-profile basal adds no IOB, so max IOB has nothing to say about it.
        whenever(glucoseStatus.glucose).thenReturn(78.0)
        whenever(oapsProfile.max_iob).thenReturn(0.05)
        val r = invoke(iobArray = flatIobArray(iob = 0.08, activity = 0.0013))
        assertTrue(r.reason.contains("CAUTION"), "Expected CAUTION in reason, got: ${r.reason}")
        assertTrue(r.rate > 0.0 && r.rate < 1.0, "Expected the reduced caution rate, got ${r.rate}")
    }

    // ── Max IOB as a red line ────────────────────────────────────────────────

    /** Extra insulin this cycle would add: the SMB plus the above-basal part of a 30-min temp. */
    private fun extraU(r: FakeAPSResult) = r.smb + ((r.rate - 1.0).coerceAtLeast(0.0) * 0.5)

    @Test fun `high BG at max IOB gets profile basal and no SMB`() {
        whenever(glucoseStatus.glucose).thenReturn(250.0)
        whenever(oapsProfile.max_iob).thenReturn(3.0)
        val r = invoke(iobArray = flatIobArray(iob = 3.0, activity = 0.0))
        assertEquals(0.0, r.smb, 0.001)
        assertEquals(1.0, r.rate, 0.001)
    }

    @Test fun `high BG over max IOB gets no extra insulin and no zero temp`() {
        whenever(glucoseStatus.glucose).thenReturn(250.0)
        whenever(oapsProfile.max_iob).thenReturn(3.0)
        val r = invoke(iobArray = flatIobArray(iob = 3.2, activity = 0.0))
        assertEquals(0.0, r.smb, 0.001)
        // No extra insulin, but not a hard stop either. It may sit below profile when the forecast
        // itself dips under target (here 3.2 U on board does), never above it.
        assertTrue(r.rate > 0.0 && r.rate <= 1.0 + 0.001, "Expected 0 < rate <= profile basal, got ${r.rate}")
    }

    @Test fun `SMB and temp together fill the headroom but never pass max IOB`() {
        whenever(glucoseStatus.glucose).thenReturn(250.0)
        whenever(oapsProfile.max_iob).thenReturn(3.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.8, activity = 0.0))
        val extra = extraU(r)
        assertTrue(extra <= 0.2 + 0.001, "SMB ${r.smb} + temp ${r.rate} adds ${extra}U, over the 0.2U headroom")
        assertTrue(extra >= 0.2 - 0.06, "Should use the headroom (one pump step of slack), added only ${extra}U")
    }

    @Test fun `with SMBs off the temp alone fills the headroom`() {
        whenever(glucoseStatus.glucose).thenReturn(250.0)
        whenever(oapsProfile.max_iob).thenReturn(3.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.9, activity = 0.0), microBolusAllowed = false)
        assertEquals(0.0, r.smb, 0.001)
        assertEquals(1.0 + 0.1 / 0.5, r.rate, 0.01)
        assertTrue(r.reason.contains("capped by maxIOB"), "Expected the cap note, got: ${r.reason}")
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

    @Test fun `a cycle in which a pre-bolus went in gives no SMB and nothing above basal`() {
        // High and above target: without the pre-bolus this gets an SMB and a high temp.
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        val without = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertTrue(extraU(without) > 0.0, "should give extra without a pre-bolus, got ${extraU(without)}")
        // The pre-bolus is not in IOB yet, so the loop must not dose as if it were not there.
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), preBolusThisCycleU = 3.0)
        assertEquals(0.0, r.smb, 0.001)
        assertTrue(r.rate <= 1.0 + 1e-9, "rate should be at most basal, got ${r.rate}")
        assertTrue(r.reason.contains("pre-bolus 3.00U this cycle: no extra"))
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
        // One per iobArray step, as stock oref: AAPS builds 48 (4 hours of 5 minutes).
        val r = invoke()
        assertEquals(48, r.predictionsAsGv.size)
    }

    @Test fun `predictions are cleared and repopulated each call`() {
        fakeResult.predictionsAsGv.add(
            GV(timestamp = 0L, value = 99.0, raw = null,
               trendArrow = TrendArrow.NONE, noise = null,
               sourceSensor = SourceSensor.UNKNOWN)
        )
        val r = invoke()
        assertEquals(48, r.predictionsAsGv.size)
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
        // Stock oref fades ci from the first step: 18 * (11/12 + 10/12 + ... + 1/12) = 18 * 5.5.
        assertEquals(150.0 + 18.0 * 5.5, peak, 0.5,
                     "Sub-clamp carb impact should reach the prediction in full, got $peak")
    }

    // ── Food fade time ───────────────────────────────────────────────────────

    private fun foodComingIn(deltaMgdl: Double) {
        whenever(glucoseStatus.glucose).thenReturn(150.0)
        whenever(glucoseStatus.delta).thenReturn(deltaMgdl)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(deltaMgdl)
    }

    @Test fun `a longer food fade in a meal mode counts more food still to come`() {
        foodComingIn(18.0)
        val r = invoke(mealMode = MealMode.UAM_DINNER, foodFadeMins = 120)
        // 120 min = 24 ticks: 18 * (23/24 + 22/24 + ... + 0/24) = 18 * 11.5.
        assertEquals(150.0 + 18.0 * 11.5, r.predictionsAsGv.maxOf { it.value }, 0.5)
        assertTrue(r.reason.contains("fade=120m"), r.reason)
        assertTrue(r.reason.contains("food=1.00/5m"), r.reason)
    }

    @Test fun `the default food fade in a meal mode is the old 60 min`() {
        foodComingIn(18.0)
        val r = invoke(mealMode = MealMode.UAM_DINNER)
        assertEquals(150.0 + 18.0 * 5.5, r.predictionsAsGv.maxOf { it.value }, 0.5)
        assertTrue(r.reason.contains("fade=60m"), r.reason)
    }

    @Test fun `Fasting ignores the food fade time and does not show it`() {
        foodComingIn(18.0)
        val r = invoke(mealMode = MealMode.FASTING, foodFadeMins = 120)
        assertEquals(150.0 + 18.0 * 5.5, r.predictionsAsGv.maxOf { it.value }, 0.5)
        assertTrue(!r.reason.contains("fade="), r.reason)
    }

    @Test fun `a fall in a meal mode still fades over 60 min`() {
        // No food coming in: a longer fade must not stretch a predicted fall.
        foodComingIn(-9.0)
        val r = invoke(mealMode = MealMode.UAM_DINNER, foodFadeMins = 120)
        assertEquals(150.0 - 9.0 * 5.5, r.predictionsAsGv.minOf { it.value }, 0.5)
        assertTrue(r.reason.contains("fade=60m"), r.reason)
    }

    // ── Food still to come ───────────────────────────────────────────────────

    /** A Dinner with 30 g of carbs still to come within the hour, and 30 g of fat and protein over 3 hours. */
    private fun dinnerFood(now: Long = System.currentTimeMillis()) =
        FoodToCome(carbsG = 30.0, carbsUntilMs = now + 60 * 60_000L, fatProteinG = 30.0, fatProteinUntilMs = now + 3 * 60 * 60_000L, carbRatio = 5.0)

    /** Tonight: BG 9.1 flat in a Dinner, 4.3 U on board and still acting. */
    private fun midDinner(bg: Double = 164.0, delta: Double = 0.0) {
        whenever(oapsProfile.max_iob).thenReturn(12.0)
        whenever(glucoseStatus.glucose).thenReturn(bg)
        whenever(glucoseStatus.delta).thenReturn(delta)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(delta)
    }

    @Test fun `mid-meal, insulin alone forecasts a low, and the food still to come does not`() {
        midDinner()
        val iob = flatIobArray(iob = 4.3, activity = 0.04)
        val alone = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        assertTrue(alone.reason.contains("SUSPEND"), alone.reason)

        val withFood = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = dinnerFood())
        assertFalse(withFood.reason.contains("SUSPEND"), withFood.reason)
        // "over 60m", or 59 when a minute turns between building the food and reading it on a busy machine.
        assertTrue(Regex("""food left=60g\(carbs 30 over (59|60)m fp 30\)""").containsMatchIn(withFood.reason), withFood.reason)
        // With carbs still to come the forecast with the food is drawn.
        assertTrue(withFood.cobLine != null, withFood.reason)
        assertTrue(withFood.reason.contains("NORMAL"), withFood.reason)
    }

    @Test fun `a falling BG says the food is not coming in, and the low guard works as without it`() {
        midDinner(delta = -5.0)
        val r = invoke(iobArray = flatIobArray(iob = 4.3, activity = 0.04), mealMode = MealMode.DINNER,
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = dinnerFood())
        assertTrue(r.reason.contains("not used: BG falling"), r.reason)
        assertTrue(r.reason.contains("SUSPEND"), r.reason)
    }

    @Test fun `under target and not rising, the food does not keep the low guard off`() {
        // 10 Nov, 15:27: lunch tail, BG 5.4 and falling slowly with a 5.5 target, 0.9 U on board, 6 g of fat and
        // protein still to come. The food lifted the forecast over the low guard and full basal ran for 30 min.
        midDinner(bg = 97.0, delta = -1.8)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 0.92, activity = 0.012)
        val tail = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 6.0, fatProteinUntilMs = now + 2 * 60 * 60_000L, carbRatio = 4.0)
        // Read before the next call: the test result object is shared.
        val aloneMin = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0).predictionsAsGv.minOf { it.value }
        val r = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = tail)
        assertTrue(r.reason.contains("not used: carbs done, BG under target and not rising"), r.reason)
        assertTrue(r.reason.contains("SUSPEND"), r.reason)
        assertEquals(0.0, r.rate, 1e-9)
        assertEquals(aloneMin, r.predictionsAsGv.minOf { it.value }, 1e-9)
        assertEquals(0.0, r.smb, 1e-9)
    }

    @Test fun `under target and flat at the start of a meal, the carbs still to come keep basal running`() {
        // BG 5.4 flat just after pre-bolus 1, with the carbs still to come: the food is real and just eaten, and the
        // pre-bolus counts on basal. Insulin alone would suspend.
        midDinner(bg = 97.0)
        val iob = flatIobArray(iob = 4.3, activity = 0.04)
        val alone = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        assertTrue(alone.reason.contains("SUSPEND"), alone.reason)
        val r = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = dinnerFood())
        assertFalse(r.reason.contains("not used"), r.reason)
        assertFalse(r.reason.contains("SUSPEND"), r.reason)
        assertTrue(r.rate > 0.0, r.reason)
        assertEquals(0.0, r.smb, 1e-9)
    }

    @Test fun `at the tail, under target and rising, the food counts for the low guard again`() {
        midDinner(bg = 95.0, delta = 2.0)
        val now = System.currentTimeMillis()
        val tail = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 20.0, fatProteinUntilMs = now + 2 * 60 * 60_000L, carbRatio = 4.0)
        val r = invoke(iobArray = flatIobArray(iob = 1.0, activity = 0.01), mealMode = MealMode.DINNER,
                       lowGuardMmol = 4.0, warnGuardMmol = 4.5, foodToCome = tail)
        assertTrue(r.reason.contains("low guard only"), r.reason)
        assertFalse(r.reason.contains("not used"), r.reason)
        assertEquals(0.0, r.smb, 1e-9)
    }

    @Test fun `food still to come gives more insulin above target than the insulin alone would`() {
        // Clearly above target: the insulin on board alone brings BG down to target, so it asks for nothing.
        midDinner(bg = 120.0)
        val iob = flatIobArray(iob = 1.5, activity = 0.015)
        val alone = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 5.0)
        assertEquals(0.0, alone.smb, 1e-9)
        val withFood = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 5.0, foodToCome = dinnerFood())
        assertTrue(withFood.smb > 0.0, withFood.reason)
    }

    @Test fun `just above target and flat, food still to come gives no extra insulin until BG rises`() {
        // 9 Nov, 00:02: BG 6.2 flat with a 5.5 target, 1 U on board, a protein snack of 28 g (as carbs) still to
        // come. The food in the forecast gave 3.2 U in 35 min before any of it showed.
        midDinner(bg = 112.0)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 0.97, activity = 0.017)
        val snack = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 28.0, fatProteinUntilMs = now + 4 * 60 * 60_000L, carbRatio = 4.0)
        val flat = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = snack)
        val flatReason = flat.reason
        assertEquals(0.0, flat.smb, 1e-9)
        assertTrue(flatReason.contains("low guard only"), flatReason)
        // The food still keeps basal from being cut and stops a false suspend.
        assertFalse(flatReason.contains("SUSPEND"), flatReason)
        // With carbs still to come, a rise near target is the food coming in, and the food counts.
        midDinner(bg = 112.0, delta = 3.0)
        val withCarbs = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = snack.copy(carbsG = 10.0, carbsUntilMs = now + 60 * 60_000L))
        assertFalse(withCarbs.reason.contains("low guard only"), withCarbs.reason)
        // With only fat and protein left, the same rise near target still gives nothing: 11 Nov, the end of a lunch,
        // BG 5.7 to 6.1 rising, 1.25 U for 21 g still to come, and the food stopped 15 min later.
        val tail = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = snack)
        assertTrue(tail.reason.contains("low guard only: carbs done, BG leads"), tail.reason)
        // Read before the next call: the test result object is shared.
        val tailSmb = tail.smb
        // Only what the rise itself asks for, as with no food list at all.
        val noFood = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0)
        assertEquals(noFood.smb, tailSmb, 1e-9)
    }

    @Test fun `once the carbs are done, the food list gives no extra insulin, also well above target`() {
        // 11 Nov, about 22:00: a dinner with 7 g of carbs, 5 hours in, just after a low. The list still had 44 g of fat
        // and protein coming; from 6.5 the forecast would have dosed for the 17 g due in the next 2 hours.
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 0.7, activity = 0.016)
        val tail = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 44.0, fatProteinUntilMs = now + 5 * 60 * 60_000L, carbRatio = 5.0)
        for (bg in listOf(120.0, 160.0)) {
            midDinner(bg = bg, delta = 2.0)
            val r = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = tail)
            assertTrue(r.reason.contains("low guard only: carbs done, BG leads"), r.reason)
            assertFalse(r.reason.contains("all food"), r.reason)
            // The estimate is not drawn as a rise the loop does not dose for: it climbed to 12 that evening.
            assertEquals(null, r.cobLine)
            assertTrue(r.reason.contains("food left=44g(carbs 0 fp 44)"), r.reason)
            // Read before the next call: the test result object is shared.
            val tailSmb = r.smb
            // Extra insulin is what BG itself asks for, as with no food list. (Basal may differ: the low guard sees the
            // fat and protein BG shows.)
            val noFood = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0)
            assertEquals(noFood.smb, tailSmb, 1e-9)
        }
        // With carbs still to come it is the meal itself, and the food counts.
        midDinner(bg = 160.0, delta = 2.0)
        val withCarbs = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = tail.copy(carbsG = 15.0, carbsUntilMs = now + 60 * 60_000L))
        assertFalse(withCarbs.reason.contains("low guard only"), withCarbs.reason)
    }

    @Test fun `extra insulin is for the food due in the next 2 hours, not for food hours away`() {
        midDinner(bg = 130.0)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 3.0, activity = 0.03)
        val soon = FoodToCome(carbsG = 40.0, carbsUntilMs = now + 60 * 60_000L, fatProteinG = 0.0, fatProteinUntilMs = now, carbRatio = 5.0)
        val far = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 40.0, fatProteinUntilMs = now + 12 * 60 * 60_000L, carbRatio = 5.0)
        // invoke returns the same result object each time, so read each result before the next call.
        val forSoon = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = soon)
        val soonSmb = forSoon.smb
        assertTrue(soonSmb > 0.5, forSoon.reason)
        // The same 40 g spread over 12 hours: only a sixth is due in the next 2 hours. It lands about at
        // target, so next to nothing extra.
        val forFar = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = far)
        assertTrue(forFar.smb <= 0.1, forFar.reason)
    }

    @Test fun `a steep rise from a small fast food is not carried on past the food left`() {
        // Morning coffee: BG 7.5 rising 0.8 mmol per 5 min, the milk nearly all in. Carried on over the hour, that rise
        // is far more than 4 g of milk can still add (4 g x 10 mg/dL per g x 1.5 = 60 mg/dL).
        midDinner(bg = 135.0, delta = 14.4)
        val iob = flatIobArray(iob = 1.5, activity = 0.01)
        val noList = invoke(iobArray = iob, mealMode = MealMode.BREAKFAST, maxSmbU = 4.0)
        val noListSmb = noList.smb
        assertTrue(noListSmb > 0.0, noList.reason)
        val coffee = invoke(iobArray = iob, mealMode = MealMode.BREAKFAST, maxSmbU = 4.0, foodListG = 4.0)
        val coffeeSmb = coffee.smb
        assertTrue(coffee.reason.contains("food left"), coffee.reason)
        assertTrue(coffeeSmb < noListSmb, "without a food list $noListSmb, with 4 g left $coffeeSmb")
        // A big meal still to come does not limit the same rise.
        val meal = invoke(iobArray = iob, mealMode = MealMode.BREAKFAST, maxSmbU = 4.0, foodListG = 60.0)
        assertFalse(meal.reason.contains("food left"), meal.reason)
        assertEquals(noListSmb, meal.smb, 1e-9)
    }

    @Test fun `the yellow line draws the fat and protein at the rate BG shows, never more than the list`() {
        // 11 Nov, 22:32: a dinner 4 hours in, carbs done, 40 g of fat and protein on the list. The whole estimate climbed to
        // 12 and over; the yellow line follows BG instead.
        val now = System.currentTimeMillis()
        // No insulin working, so a flat BG shows no food at all.
        val iob = flatIobArray(iob = 0.3, activity = 0.0)
        val tail = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 40.0, fatProteinUntilMs = now + 4 * 60 * 60_000L, carbRatio = 5.0)
        // Flat: BG shows none of it, and the yellow line is the blue one.
        midDinner(bg = 106.0, delta = 0.0)
        val flat = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = tail)
        assertTrue(flat.reason.contains("seen 0%"), flat.reason)
        assertEquals(null, flat.cobLine)
        assertEquals(flat.predictionsAsGv.map { it.value.toInt() }, flat.uamLine!!.take(flat.predictionsAsGv.size))
        // Rising: the yellow line climbs above the blue one.
        midDinner(bg = 106.0, delta = 2.0)
        val rising = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = tail)
        assertTrue(rising.uamLine!!.last() > rising.predictionsAsGv.last().value, rising.reason)
        // Rising faster than the list says: no more than the list.
        midDinner(bg = 106.0, delta = 15.0)
        val steep = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = tail)
        assertTrue(steep.reason.contains("seen 100%"), steep.reason)
    }

    private fun predMin(reason: String) = Regex("""pred_min=([-\d.]+)""").find(reason)!!.groupValues[1].toDouble()

    @Test fun `at the tail the low guard uses the yellow line, so a flat BG has basal cut for the insulin alone`() {
        // 11 Nov, 21:47 to 22:12: the tail of a dinner, BG flat above target, 44 g of fat and protein on the list that BG
        // was not showing. The whole estimate kept basal running.
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 1.5, activity = 0.006)
        val tail = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 44.0, fatProteinUntilMs = now + 5 * 60 * 60_000L, carbRatio = 5.0)
        // BG drifting down at just the rate the insulin pushes it: no food is showing. (Flat, with insulin working, would
        // mean food is coming in.)
        // The insulin's 5-min effect with the ISF these tests give the loop, 50 mg/dL/U: -1.5 mg/dL.
        midDinner(bg = 108.0, delta = -0.006 * 50.0 * 5.0)
        // The test result object is shared between calls: read what is needed from each before the next.
        val noFood = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        val noFoodMin = predMin(noFood.reason)
        val noFoodRate = noFood.rate
        val flat = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = tail)
        val flatReason = flat.reason
        assertTrue(flatReason.contains("seen 0%"), flatReason)
        assertEquals(noFoodMin, predMin(flatReason), 1e-9)
        assertEquals(noFoodRate, flat.rate, 1e-9)
        // While carbs are still to come, all the food of the list keeps the low guard off, as before.
        val midMeal = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0,
                             foodToCome = tail.copy(carbsG = 15.0, carbsUntilMs = now + 60 * 60_000L))
        assertTrue(predMin(midMeal.reason) > noFoodMin, "mid-meal ${midMeal.reason}, no food $noFoodMin")
    }

    @Test fun `at the tail a rise BG shows counts for the low guard, but not as extra insulin`() {
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 1.5, activity = 0.006)
        val tail = FoodToCome(carbsG = 0.0, carbsUntilMs = now, fatProteinG = 44.0, fatProteinUntilMs = now + 5 * 60 * 60_000L, carbRatio = 5.0)
        midDinner(bg = 108.0, delta = 2.0)
        val noFood = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, lowGuardMmol = 4.8, warnGuardMmol = 5.0)
        val noFoodSmb = noFood.smb
        val blueMin = noFood.predictionsAsGv.minOf { it.value }
        val rising = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = tail)
        val yellow = rising.uamLine!!
        // The yellow line sees the fat and protein BG shows, and it is what the low guard uses.
        assertTrue(yellow.min() > blueMin, "yellow ${yellow.min()}, blue $blueMin: ${rising.reason}")
        assertEquals(yellow.min() / 18.0, predMin(rising.reason), 0.1)
        // Extra insulin is still only what BG asks for, as with no food list.
        assertEquals(noFoodSmb, rising.smb, 1e-9)
    }

    @Test fun `with half the carbs still to come, extra insulin counts half the estimate of the fat and protein`() {
        // The blend: the estimate of the fat and protein hands over to BG as the carbs come in, with no step at the end.
        midDinner(bg = 160.0, delta = 2.0)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 2.0, activity = 0.02)
        val until = now + 60 * 60_000L
        // 15 g of 30 g of carbs still to come, 40 g of fat and protein on the list.
        val halfway = FoodToCome(carbsG = 15.0, carbsUntilMs = until, fatProteinG = 40.0, fatProteinUntilMs = now + 4 * 60 * 60_000L,
                                 carbRatio = 5.0, carbParts = listOf(CarbsPart(leftG = 15.0, mealG = 30.0, untilMs = until, minutes = 120)))
        val halfwaySmb = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = halfway).smb
        val halfwayReason = fakeResult.reason
        assertTrue(halfwayReason.contains("fp estimate 50%"), halfwayReason)
        // The same as all the carbs still to come with half the fat and protein.
        val asHalf = halfway.copy(fatProteinG = 20.0, carbParts = listOf(CarbsPart(leftG = 15.0, mealG = 15.0, untilMs = until, minutes = 120)))
        val asHalfSmb = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = asHalf).smb
        assertEquals(asHalfSmb, halfwaySmb, 1e-9)
        // And less than the whole estimate would give.
        val wholeSmb = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0,
                              foodToCome = halfway.copy(carbParts = listOf(CarbsPart(leftG = 15.0, mealG = 15.0, untilMs = until, minutes = 120)))).smb
        assertTrue(halfwaySmb < wholeSmb, "halfway $halfwaySmb, whole estimate $wholeSmb")
    }

    @Test fun `mid-meal the orange line has the carbs and the yellow line adds the fat and protein`() {
        midDinner(bg = 140.0, delta = 3.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.02), mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = dinnerFood())
        val orange = r.cobLine!!
        val yellow = r.uamLine!!
        assertEquals(orange.size, yellow.size)
        assertTrue(orange.indices.all { yellow[it] >= orange[it] }, "yellow $yellow, orange $orange")
    }

    @Test fun `all the food catches up slowly, and not in the first hour after a pre-bolus`() {
        // 11 Nov, 14:12: lunch, BG 7.8 rising, 7.5 U on board 15 min after a 4 U pre-bolus 2, 29 g of carbs and 31 g of
        // fat and protein still to come. Weighing all of it gave 1.55 U, then 1.10 U, with high temps: 3.7 U in 15 min.
        midDinner(bg = 140.0, delta = 3.6)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 7.48, activity = 0.0487)
        val lunch = FoodToCome(carbsG = 29.0, carbsUntilMs = now + 2 * 60 * 60_000L, fatProteinG = 31.0, fatProteinUntilMs = now + 5 * 60 * 60_000L,
                               carbRatio = 5.0, fatProteinFromMs = now - 15 * 60_000L)
        val soon = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = lunch, minsSincePreBolus = 15)
        assertTrue(soon.reason.contains("not in the first hour after a pre-bolus"), soon.reason)
        assertFalse(soon.reason.contains("all food: fp coming"), soon.reason)
        // An hour on, all the food counts again, but adds at most a top-up's size each cycle.
        val later = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = lunch, minsSincePreBolus = 75)
        assertTrue(later.reason.contains("all food: fp coming"), later.reason)
        val capU = FoodMatch.maxTopUpU(140.0, 100.0)
        val extraU = later.smb + (later.rate - soon.rate).coerceAtLeast(0.0) * 0.5 - soon.smb
        assertTrue(extraU <= capU + 0.05 + 1e-9, "all food added $extraU U, cap $capU U: ${later.reason}")
    }

    @Test fun `a slow rise clearly above target is not cut when the food list has run out`() {
        // 10 Nov, 23:27: turkey dinner 5 h 20 min in, BG 9.0 rising 0.1 mmol per 5 min, 1.7 U on board, the food list
        // says nothing is left. The rise was cut to nothing and the loop suspended.
        midDinner(bg = 162.0, delta = 2.0)
        val iob = flatIobArray(iob = 1.68, activity = 0.0253)
        // Read before the next call: the test result object is shared.
        val noListLine = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0).predictionsAsGv.map { it.value }
        val ranOut = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodListG = 0.0)
        assertFalse(ranOut.reason.contains("food left"), ranOut.reason)
        assertEquals(noListLine, ranOut.predictionsAsGv.map { it.value })
        // Near target the list still limits the same slow rise.
        midDinner(bg = 110.0, delta = 2.0)
        val nearTarget = invoke(iobArray = iob, mealMode = MealMode.DINNER, lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodListG = 0.0)
        assertTrue(nearTarget.reason.contains("food left"), nearTarget.reason)
    }

    @Test fun `a heavy steak dinner is dosed for the next 2 hours of it now, the rest later`() {
        // 60 g of fat and protein (as carbs) over a 6 h meal: about 20 g is due in the next 2 hours. Carbs still to come:
        // the meal is still going, so the list counts (once the carbs are done BG leads instead).
        midDinner(bg = 130.0)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 3.0, activity = 0.03)
        val steak = FoodToCome(carbsG = 5.0, carbsUntilMs = now + 30 * 60_000L, fatProteinG = 60.0, fatProteinUntilMs = now + 6 * 60 * 60_000L, carbRatio = 5.0)
        val forSteak = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = steak)
        val steakSmb = forSteak.smb
        assertTrue(steakSmb > 0.0, forSteak.reason)
        // The same 60 g all due within 2 hours asks for more now.
        val fast = steak.copy(fatProteinUntilMs = now + 2 * 60 * 60_000L)
        val forFast = invoke(iobArray = iob, mealMode = MealMode.DINNER, maxSmbU = 4.0, foodToCome = fast)
        assertTrue(forFast.smb > steakSmb, "steak $steakSmb, all within 2 h ${forFast.smb}")
    }

    @Test fun `fat and protein coming in with a lot still to come weigh all the food, so BG does not sit high`() {
        // 5 Nov, a steak added to lunch: BG 8.0 flat, 3.67 U on board, 9 g of carbs and 27 g of fat and protein
        // still to come, the fat and protein coming in since an hour. The next 2 hours of food against all the
        // insulin asked for nothing, and the forecast with all the food sat at 8 for hours.
        midDinner(bg = 144.0)
        val now = System.currentTimeMillis()
        val iob = flatIobArray(iob = 3.67, activity = 0.0285)
        val steak = FoodToCome(carbsG = 9.0, carbsUntilMs = now + 3 * 60 * 60_000L, fatProteinG = 27.0, fatProteinUntilMs = now + 4 * 60 * 60_000L,
                               carbRatio = 5.0, fatProteinFromMs = now - 60 * 60_000L)
        val coming = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = steak)
        val comingSmb = coming.smb
        val comingRate = coming.rate
        assertTrue(coming.reason.contains("all food: fp coming"), coming.reason)
        assertTrue(comingSmb > 0.0 || comingRate > 1.0, coming.reason)
        // Before the fat and protein start, only the next 2 hours of food count, as before.
        val notYet = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = steak.copy(fatProteinFromMs = now + 30 * 60_000L))
        assertFalse(notYet.reason.contains("all food"), notYet.reason)
        // A little fat and protein left is not enough.
        val little = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = steak.copy(fatProteinG = 5.0))
        assertFalse(little.reason.contains("all food"), little.reason)
        // Just above target (5.8 with a 5.5 target, that evening): the fat and protein are only an estimate, so
        // only the next 2 hours of food count, as before.
        midDinner(bg = 104.0)
        val nearTarget = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = steak)
        assertFalse(nearTarget.reason.contains("all food"), nearTarget.reason)
        // A falling BG says the food is not coming in: nothing extra.
        midDinner(bg = 144.0, delta = -5.0)
        val falling = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = steak)
        assertFalse(falling.reason.contains("all food"), falling.reason)
        assertEquals(0.0, falling.smb, 1e-9)
    }

    @Test fun `a food match top-up is given when the forecast asks for nothing, and only in normal dosing`() {
        midDinner(bg = 110.0)
        val iob = flatIobArray(iob = 4.0, activity = 0.03)
        val topUp = FoodMatch.TopUp(doseU = 0.15, foodMgdl = 10.0, insulinMgdl = 5.0)
        val without = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0)
        assertEquals(0.0, without.smb, 1e-9)
        val with = invoke(iobArray = iob, mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodMatchTopUp = topUp)
        assertEquals(if (with.reason.contains("NORMAL")) 0.15 else 0.0, with.smb, 1e-9)
        assertTrue(with.reason.contains("food match=0.15U"), with.reason)
        // Max IOB stays the limit.
        midDinner(bg = 144.0)
        val full = invoke(iobArray = flatIobArray(iob = 50.0, activity = 0.03), mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodMatchTopUp = topUp)
        assertEquals(0.0, full.smb, 1e-9)
    }

    @Test fun `no extra insulin for carbs a pre-bolus just given already covers`() {
        // Lunch on 1 Nov: BG 6.1 flat, pre-bolus 1 of 3.65 U given 4 min ago, 4.1 U on board, 22 g of carbs to
        // come, counted at the 80 % Fill in plans. The pre-bolus has done only a third of its work in the next
        // hour, but it covers the carbs: the loop must not give insulin for them again.
        midDinner(bg = 110.0)
        val now = System.currentTimeMillis()
        val lunch = FoodToCome(carbsG = 22.0 * 0.8, carbsUntilMs = now + 2 * 60 * 60_000L, fatProteinG = 0.0, fatProteinUntilMs = now, carbRatio = 5.0)
        val r = invoke(iobArray = flatIobArray(iob = 4.1, activity = 0.0076), mealMode = MealMode.LUNCH, maxSmbU = 4.0, foodToCome = lunch)
        assertEquals(0.0, r.smb, 1e-9)
        assertTrue(r.rate <= 1.0 + 1e-9, r.reason)
    }

    @Test fun `Fasting never uses food still to come`() {
        midDinner()
        val r = invoke(iobArray = flatIobArray(iob = 4.3, activity = 0.04), mealMode = MealMode.FASTING,
                       lowGuardMmol = 4.8, warnGuardMmol = 5.0, foodToCome = dinnerFood())
        assertTrue(r.reason.contains("SUSPEND"), r.reason)
    }

    @Test fun `a degenerate dosing ISF produces finite output`() {
        // dosingIsfMgdl is profile ISF scaled by learned multipliers and aggressiveness. Every
        // insulinReq-style calculation divides by it, so a near-zero value would divide into an
        // unbounded insulin request. Pins that the floor keeps every output finite.
        whenever(glucoseStatus.glucose).thenReturn(200.0)

        sut.determine_basal(
            glucoseStatus = glucoseStatus, currentTemp = currentTemp,
            iobArray = flatIobArray(0.0, 0.0), oapsProfile = oapsProfile, mealData = mealData,
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

    // ── rise slowing: the raw CGM step when smoothing lags at the top of a rise ──

    /** 2 Oct 8:31 as the loop saw it: 10.4 mmol, UKF delta +0.8, raw step +0.2, some IOB working. */
    private fun topOfRise(riseTurn: RiseTurnGuard.Result?): FakeAPSResult {
        fakeResult = FakeAPSResult()
        whenever(glucoseStatus.glucose).thenReturn(187.0)
        whenever(glucoseStatus.delta).thenReturn(14.4)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(19.0)
        return invoke(iobArray = flatIobArray(1.5, 0.004), riseTurn = riseTurn)
    }

    private fun turn(level: RiseTurnGuard.Level, deltaMgdl: Double, rawMgdl: Double = 3.6) =
        RiseTurnGuard.Result(deltaMgdl, level, rawMgdl)

    @Test fun `rise slowing - no guard result doses exactly as before`() {
        val before = topOfRise(null)
        val none = topOfRise(turn(RiseTurnGuard.Level.NONE, 14.4))
        assertEquals(before.smb, none.smb, 1e-9)
        assertEquals(before.rate, none.rate, 1e-9)
        assertEquals(before.predictionsAsGv.map { it.value }, none.predictionsAsGv.map { it.value })
        assertFalse(none.reason.contains("riseSlowing"))
    }

    @Test fun `rise slowing - the raw step gives less insulin than the smoothed delta`() {
        val smoothed = topOfRise(null)
        val raw = topOfRise(turn(RiseTurnGuard.Level.RAW, 3.6))
        assertTrue(smoothed.smb > 0.0, "the smoothed case should be dosing, got ${smoothed.smb}")
        assertTrue(raw.smb < smoothed.smb, "SMB ${raw.smb} should be below ${smoothed.smb}")
        assertTrue(raw.predictionsAsGv.maxOf { it.value } < smoothed.predictionsAsGv.maxOf { it.value })
        assertTrue(raw.reason.contains("riseSlowing(RAW)"), raw.reason)
    }

    @Test fun `rise slowing - halfway sits between the two`() {
        val smoothed = topOfRise(null).smb
        val half = topOfRise(turn(RiseTurnGuard.Level.HALF, 9.0)).smb
        val raw = topOfRise(turn(RiseTurnGuard.Level.RAW, 3.6)).smb
        assertTrue(half <= smoothed && half >= raw, "expected $raw <= $half <= $smoothed")
    }

    @Test fun `rise slowing - a result above the smoothed delta is never used`() {
        // The guard never returns one; this checks determine_basal would not act on it if it did.
        val before = topOfRise(null)
        val bad = topOfRise(turn(RiseTurnGuard.Level.RAW, 40.0))
        assertEquals(before.smb, bad.smb, 1e-9)
        assertEquals(before.rate, bad.rate, 1e-9)
    }

    @Test fun `rise slowing - a raw step that has turned down gives no SMB and a lower temp, not a zero temp`() {
        val smoothed = topOfRise(null)
        val falling = topOfRise(turn(RiseTurnGuard.Level.RAW, -5.4, rawMgdl = -5.4))
        assertTrue(falling.reason.contains("NORMAL"), falling.reason)
        assertEquals(0.0, falling.smb, 1e-9)
        assertTrue(falling.rate > 0.0 && falling.rate < smoothed.rate, "rate ${falling.rate} vs ${smoothed.rate}")
    }

    // ── the forecast's insulin side is stock oref: AAPS's own iobArray ───────

    /** iobArray for doses ([units] to [ageMin]) on the given curve, 48 ticks like AAPS builds it. */
    private fun iobArrayOf(peak: Double, dia: Double, vararg doses: Pair<Double, Double>): Array<IobTotal> =
        Array(48) { tick ->
            var iob = 0.0; var act = 0.0
            doses.forEach { (units, ageMin) ->
                iob += units * InsulinActivityCurve.iobFraction(ageMin + tick * 5.0, peak, dia)
                act += units * InsulinActivityCurve.activityFraction(ageMin + tick * 5.0, peak, dia)
            }
            IobTotal(time = tick * 300_000L, iob = iob, activity = act)
        }

    /**
     * 2 Oct 18:26 as the log suggests it: net IOB 1.1 U while insulin acts at ~0.024 U/min - working
     * bolus insulin with negative basal insulin after zero temps.
     */
    private fun mismatched(): Array<IobTotal> {
        val working = iobArrayOf(55.0, 540.0, 4.0 to 75.0)
        return Array(working.size) { i ->
            IobTotal(time = i * 300_000L, iob = if (i == 0) 1.1 else working[i].iob - 2.9, activity = working[i].activity)
        }
    }

    private fun landing(iobArray: Array<IobTotal>): FakeAPSResult {
        fakeResult = FakeAPSResult()
        whenever(glucoseStatus.glucose).thenReturn(103.0)
        whenever(glucoseStatus.delta).thenReturn(-1.8)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(-1.8)
        return invoke(iobArray = iobArray, learnedProfile = LearnedInsulinProfile.defaultFor(MealMode.FASTING, 55.0, 540.0))
    }

    @Test fun `insulin acting hard on a small net IOB is not forecast as a rise`() {
        val r = landing(mismatched())
        val min = r.predictionsAsGv.minOf { it.value }
        assertTrue(min < 99.0, "insulin still working must take the forecast below target, got min ${min / 18}")
        assertEquals(0.0, r.smb, 1e-9, r.reason)
    }

    @Test fun `the forecast is stock oref - BG plus each step's AAPS insulin action plus fading ci`() {
        val arr = iobArrayOf(55.0, 540.0, 3.0 to 60.0)
        val r = landing(arr)
        // ci = min(delta, shortAvgDelta) - bgi now, bgi = -(activity * ISF * 5); ISF 50 in this harness
        val ci = -1.8 + arr[0].activity * 50.0 * 5.0
        var bg = 103.0
        val expected = arr.mapIndexed { i, t ->
            bg += -(t.activity * 50.0 * 5.0) + ci * (1.0 - minOf(1.0, (i + 1) / 12.0)); bg
        }
        val got = r.predictionsAsGv.map { it.value }
        assertEquals(expected.size, got.size)
        expected.zip(got).forEachIndexed { i, (e, g) -> assertEquals(e.coerceIn(39.0, 401.0).toInt().toDouble(), g, 1e-9, "tick $i") }
    }

    @Test fun `the reason shows what the forecast was built from`() {
        val r = landing(iobArrayOf(55.0, 540.0, 3.0 to 60.0))
        assertTrue(Regex("act=\\d\\.\\d{4}").containsMatchIn(r.reason), r.reason)
    }
}
