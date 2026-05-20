package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import android.text.Spanned
import app.aaps.core.data.model.GV
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for DetermineBasalSmartInsulin.
 *
 * ## Design note: how these tests read results
 *
 * The algorithm builds an [RT] object, calls `result.with(rT)` to publish it,
 * and returns `result`. In production, `with(rT)` copies rT's computed fields
 * (rate, duration, smb, reason, etc.) into the APSResult's own fields. This
 * test fake captures the RT reference instead, so each test inspects what the
 * algorithm actually produced via `r.rT.{rate, units, duration, reason, ...}`.
 *
 * Two notes about this choice:
 *
 *  - It side-steps mirroring production's exact `with()` behaviour. We test
 *    the algorithm's outputs (rT) directly, which is what we actually care
 *    about for verifying that determine_basal computes the right values.
 *
 *  - RT fields are nullable (rate: Double?, duration: Int?, units: Double?).
 *    `null` is a legitimate value meaning "the algorithm didn't set this".
 *    Tests use `rT.X ?: <default>` to handle this, or fail-fast with `error()`
 *    when null indicates the algorithm bailed unexpectedly early.
 *
 *  - `rT.units` is `null` when no SMB was issued (`smbOut.takeIf { it > 0.0 }`
 *    in the algorithm), so 0.0 is the right default.
 *
 *  - `rT.duration` is `null` when setTempBasal returned without setting a temp
 *    (skip_neutral_temps branch). 0 is the right default.
 */
class DetermineBasalSmartInsulinTest {

    // ── Fake APSResult ───────────────────────────────────────────────────────
    //
    // Captures the RT passed to with() so tests can inspect what the algorithm
    // computed. Does NOT propagate rT.rate / rT.units / rT.duration / rT.reason
    // to this class's own setters — tests read r.rT.X directly.

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
        override var fuelTrim: Double?                   = null
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
        override var algorithm: APSResult.Algorithm      = APSResult.Algorithm.SI
        override var autosensResult: AutosensResult?     = null
        override var iobData: Array<IobTotal>?           = null
        override var glucoseStatus: GlucoseStatus?       = null
        override var currentTemp: CurrentTemp?           = null
        override var oapsProfile: OapsProfile?           = null
        override var oapsProfileAutoIsf: OapsProfileAutoIsf? = null
        override var mealData: MealData?                 = null

        /** The most recent RT passed to with() — source of truth for what the algorithm produced. */
        var lastRT: RT? = null
            private set

        override fun with(result: RT): APSResult {
            lastRT = result
            return this
        }

        override suspend fun resultAsString(): String     = reason
        override suspend fun resultAsSpanned(): Spanned   = mock()
        override suspend fun resultAsHtmlString(): String = ""
        override fun newAndClone(): APSResult             = FakeAPSResult()
        override fun json(): JSONObject?                  = null
        override fun predictions(): Predictions?          = null
        override fun rawData(): Any                       = ""
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

        // OapsProfile defaults
        whenever(oapsProfile.sens).thenReturn(50.0)
        whenever(oapsProfile.current_basal).thenReturn(1.0)
        whenever(oapsProfile.target_bg).thenReturn(100.0)
        whenever(oapsProfile.min_bg).thenReturn(90.0)
        whenever(oapsProfile.max_bg).thenReturn(120.0)
        whenever(oapsProfile.max_basal).thenReturn(5.0)
        whenever(oapsProfile.max_daily_basal).thenReturn(2.0)
        whenever(oapsProfile.max_iob).thenReturn(5.0)
        whenever(oapsProfile.max_daily_safety_multiplier).thenReturn(3.0)
        whenever(oapsProfile.current_basal_safety_multiplier).thenReturn(4.0)
        whenever(oapsProfile.bolus_increment).thenReturn(0.05)
        whenever(oapsProfile.enableSMB_always).thenReturn(true)
        whenever(oapsProfile.maxSMBBasalMinutes).thenReturn(30)
        whenever(oapsProfile.enableUAM).thenReturn(false)
        // Deterministic: don't skip neutral temps. Algorithm will issue a 30-min TBR at
        // profile basal even when no insulin demand exists. Functionally equivalent to
        // running profile basal — verifies algorithm output without depending on the
        // pump-driver's neutral-temp-skip optimization.
        whenever(oapsProfile.skip_neutral_temps).thenReturn(false)
        whenever(oapsProfile.lgsThreshold).thenReturn(0)

        // GlucoseStatus defaults
        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.date).thenReturn(System.currentTimeMillis())

        // Profile ISF (raw, un-multiplied) used as fallback by the ISF validator
        whenever(profile.getIsfMgdl(any())).thenReturn(40.0)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun flatIobArray(iob: Double, activity: Double, size: Int = 60): Array<IobTotal> =
        Array(size) { t -> IobTotal(time = t * 60_000L, iob = iob, activity = activity) }

    private fun defaultLearned(mode: MealMode = MealMode.FASTING) =
        LearnedInsulinProfile.defaultFor(mode)

    /** Access captured RT — fails fast with a clear message if with() was never called. */
    private val FakeAPSResult.rT: RT
        get() = lastRT ?: error("FakeAPSResult.with(rT) was never called — algorithm bailed before producing a result?")

    private fun invoke(
        iobArray:          Array<IobTotal>      = flatIobArray(0.0, 0.0),
        learnedProfile:    LearnedInsulinProfile = defaultLearned(),
        lowGuardMmol:      Double               = 3.9,
        warnGuardMmol:     Double               = 4.5,
        microBolusAllowed: Boolean              = true,
        horizonMins:       Int                  = 60,
        // New params for safety-change tests — defaults preserve original behaviour
        dosingIsfMgdl:     Double               = 50.0,
        dawnSmbReduction:  Double               = 0.5,
        cgmSmbFraction:    Double               = 1.0
    ): FakeAPSResult {
        // Fresh instance per invoke so tests that call invoke() multiple times get
        // independent captured-RT data. The sut's apsResultProvider lambda captures
        // `fakeResult` by reference, so reassigning here lets the next determine_basal
        // call pick up this new instance. Without this, the second invoke would
        // overwrite the first's lastRT, making before/after comparisons impossible.
        fakeResult = FakeAPSResult()
        sut.determine_basal(
            glucoseStatus            = glucoseStatus,
            currentTemp              = currentTemp,
            iobArray                 = iobArray,
            oapsProfile              = oapsProfile,
            mealData                 = mealData,
            profile                  = profile,
            learnedProfile           = learnedProfile,
            mealMode                 = MealMode.FASTING,
            lowGuardMmol             = lowGuardMmol,
            warnGuardMmol            = warnGuardMmol,
            maxSmbU                  = 2.0,
            maxTbrU                  = 5.0,
            aggressiveness           = 1.0,
            tirSummary               = "",
            basalMultiplier          = 1.0,
            dosingIsfMgdl            = dosingIsfMgdl,
            microBolusAllowed        = microBolusAllowed,
            inReboundWindow          = false,
            msSinceLastSuspend       = 3600_000L,
            currentTime              = System.currentTimeMillis(),
            isTempTarget             = false,
            profileTargetMgdl        = 100.0,
            dawnWindowStartHour      = 4,
            dawnWindowEndHour        = 7,
            dawnSmbReduction         = dawnSmbReduction,
            bgWentLow                = false,
            activityLevel            = ActivityMonitor.ActivityLevel.SEDENTARY,
            activityTargetOffsetMmol = 0.0,
            cgmSmbFraction           = cgmSmbFraction,
            cgmDeltaPlausible        = true,
            cgmWarmupReason          = "",
            uamSmbFraction           = 1.0,
            targetRespectEnabled     = false,
            reboundWindowMins        = horizonMins.toDouble(),
            circCeil                 = 1.0,
            isMmol                   = true
        )
        return fakeResult
    }

    // ── SUSPEND zone ─────────────────────────────────────────────────────────

    @Test fun `SUSPEND when predicted BG drops below low guard`() {
        whenever(glucoseStatus.glucose).thenReturn(90.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.05))
        val rT = r.rT
        val duration = rT.duration ?: 0
        assertEquals(0.0, rT.rate ?: 0.0, 0.001)
        // SUSPEND uses suspendDurationMins(worstBg) — dynamic in [30, 90] based on how
        // far below target BG is predicted to land. Aggressive activity (0.05 sustained)
        // crashes predictedMinSafety far enough to push the duration to its 90-min cap.
        assertTrue(duration in 30..90, "SUSPEND duration should be in [30, 90] minutes, was $duration")
        assertTrue(duration > 0, "duration > 0 means a temp basal was requested")
        assertEquals(0.0, rT.units ?: 0.0, 0.001)
        assertTrue(rT.reason.toString().contains("SUSPEND"), "reason should mention SUSPEND")
    }

    @Test fun `SUSPEND zone produces zero SMB`() {
        whenever(glucoseStatus.glucose).thenReturn(72.0)
        val r = invoke(iobArray = flatIobArray(iob = 1.5, activity = 0.04))
        assertEquals(0.0, r.rT.units ?: 0.0, 0.001)
    }

    // ── CAUTION zone ─────────────────────────────────────────────────────────

    @Test fun `CAUTION zone reduces basal to 50 percent or below`() {
        // BG sitting in the caution zone (between lowGuard 70.2 and warnGuard 81) with
        // no IOB activity → prediction stays flat at currentBg, predictedMinSafety=75
        // falls cleanly in the CAUTION band. Earlier inputs (BG=85, activity=0.015 sustained)
        // crashed BG below lowGuard and tripped SUSPEND instead.
        whenever(glucoseStatus.glucose).thenReturn(75.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        val rT  = r.rT
        val rate = rT.rate ?: 0.0
        // warnFrac = 1 - (81-75)/10.8 ≈ 0.444 → cautionTbr ≈ 0.444 U/hr
        assertTrue(rate <= 0.5, "Reduced basal should be <= 0.5 U/hr, was $rate")
        assertTrue(rate >= 0.0, "Reduced basal should be non-negative")
        assertEquals(0.0, rT.units ?: 0.0, 0.001)
        assertTrue(rT.reason.toString().contains("CAUTION"), "reason should mention CAUTION")
    }

    @Test fun `CAUTION zone sets 30-minute TBR`() {
        whenever(glucoseStatus.glucose).thenReturn(75.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        val duration = r.rT.duration ?: 0
        // CAUTION hardcodes 30-min duration in setTempBasal call (unlike SUSPEND which is dynamic)
        assertEquals(30, duration)
        assertTrue(duration > 0, "duration > 0 means a temp basal was requested")
    }

    // ── NORMAL zone ──────────────────────────────────────────────────────────

    @Test fun `NORMAL zone uses profile basal when stable at target`() {
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        val rT = r.rT
        // BG = target, no IOB, no insulinReq → tbrRate = profileBasal = 1.0
        // With skip_neutral_temps=false (setUp), setTempBasal issues a 30-min TBR at
        // profile basal. This is functionally equivalent to no temp basal at all — the
        // pump runs at 1.0 U/hr either way. The original test asserted duration=0,
        // expecting the skip_neutral_temps optimization; without that flag set, the
        // algorithm always issues the TBR. Both behaviours are correct for this scenario.
        assertEquals(1.0, rT.rate ?: 0.0, 0.001)
        assertEquals(30, rT.duration ?: 0)
    }

    @Test fun `NORMAL zone allows SMB above target when microBolusAllowed`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        // Leave delta and shortAvgDelta at the setUp default (0.0). With ci=0,
        // predictedMin = currentBg = 140 exactly. insulinReq = (140-100)/50 = 0.8 U.
        // correctionUnits = 0.8 × 0.9 = 0.72 → ceil to bolus_increment(0.05) = 0.75.
        // (Adding a positive delta would push predictedMin above 140 and round SMB
        // to exactly 0.80, hitting the test's "< full correction" boundary.)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), microBolusAllowed = true)
        val rT    = r.rT
        val units = rT.units ?: 0.0
        assertTrue(units > 0.0, "SMB should be > 0 when above target, was $units")
        assertTrue(units < 0.8, "SMB should be < full correction (0.8U), was $units")
        assertTrue(rT.reason.toString().contains("NORMAL"), "reason should mention NORMAL")
    }

    @Test fun `NORMAL zone blocks SMB when microBolusAllowed is false`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), microBolusAllowed = false)
        assertEquals(0.0, r.rT.units ?: 0.0, 0.001)
    }

    @Test fun `SMB blocked when BG falling fast`() {
        // FALLING_FAST_MGDL_PER_5MIN = 18 (1.0 mmol per 5-min cycle). The earlier
        // delta=-3 was a perfectly normal slow drift and tripped no special branch.
        // With delta=-20, fallingFast=true; combined with high BG falling toward target,
        // fallingIntoLow becomes true → SUSPEND fires → SMB is blocked.
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(-20.0)
        whenever(glucoseStatus.delta).thenReturn(-20.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertEquals(0.0, r.rT.units ?: 0.0, 0.001)
    }

    @Test fun `SMB is capped by maxSmbU`() {
        // The invoke() helper passes maxSmbU=2.0, which is the actual SMB cap in this
        // algorithm (oapsProfile.maxSMBBasalMinutes is unused — see algorithm comments).
        // Push BG very high to maximise insulinReq, then check SMB never exceeds 2.0.
        whenever(glucoseStatus.glucose).thenReturn(300.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(2.0)
        whenever(glucoseStatus.delta).thenReturn(2.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        val units = r.rT.units ?: 0.0
        assertTrue(units <= 2.0, "SMB must be <= maxSmbU cap (2.0), was $units")
    }

    // ── Prediction graph ─────────────────────────────────────────────────────
    //
    // Predictions live on rT.predBGs.IOB as a List<Int> (BG values clamped to
    // [39, 401]). Array length = predictionTicks = clamped DIA / 5 (72–96 entries),
    // NOT horizonMins. The original test asserted predictionsAsGv.size == horizonMins
    // which would have been wrong even with propagation wired — horizonMins drives
    // the rebound window, not the prediction curve length.

    @Test fun `predictions array is populated with DIA-derived length`() {
        val r = invoke()
        val preds = r.rT.predBGs?.IOB
        assertTrue((preds?.size ?: 0) > 0, "predBGs.IOB should be populated")
        // 6-8h DIA at 5-min ticks
        val size = preds?.size ?: 0
        assertTrue(size in 72..96, "predictionTicks should fall in [72, 96], was $size")
    }

    @Test fun `zero IOB and zero delta predicts near-flat BG`() {
        whenever(glucoseStatus.glucose).thenReturn(110.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        val firstPred = r.rT.predBGs?.IOB?.firstOrNull()?.toDouble() ?: 0.0
        assertEquals(110.0, firstPred, 1.0)
    }

    @Test fun `positive delta with no IOB predicts rising BG at t=1`() {
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        // Algorithm uses ci = min(shortAvgDelta, delta) - bgi. Need both set so the
        // min produces a non-zero value.
        whenever(glucoseStatus.shortAvgDelta).thenReturn(5.0)
        whenever(glucoseStatus.delta).thenReturn(5.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        val firstPred = r.rT.predBGs?.IOB?.firstOrNull()?.toDouble() ?: 0.0
        assertTrue(firstPred > 100.0, "Rising delta should push BG above 100, was $firstPred")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ║  SAFETY-CHANGE TESTS — added with the determine_basal rework          ║
    // ═══════════════════════════════════════════════════════════════════════

    // ── Item 1: ISF validator — pathological dosingIsfMgdl ───────────────────

    @Test fun `dosingIsfMgdl == 0_0 falls back to profile ISF, no infinity escapes`() {
        // Without the validator: insulinReq = gap / 0.0 = +Infinity, then both
        // SMB chunk and TBR rate would saturate at their hard caps — a catastrophic
        // over-deliver on a single broken input. After the validator: profile ISF
        // (40 mg/dL/U from setUp) is substituted, dosing proceeds normally.
        whenever(glucoseStatus.glucose).thenReturn(160.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = 0.0)
        val rT = r.rT
        val rate: Double = rT.rate ?: error("rT.rate was null — algorithm produced no rate")

        assertTrue(rate.isFinite(), "rate must be finite, was $rate")
        assertTrue((rT.units ?: 0.0).isFinite(), "smb (rT.units) must be finite")
        assertTrue(
            rT.consoleError?.any { it.contains("dosingIsfMgdl") && it.contains("invalid") } == true,
            "consoleError must report the fallback"
        )
        assertTrue(
            rT.consoleError?.any { it.contains("profile ISF") } == true,
            "consoleError must name profile ISF as the substitute"
        )
    }

    @Test fun `dosingIsfMgdl == NaN falls back, no NaN propagates to rate or smb`() {
        whenever(glucoseStatus.glucose).thenReturn(160.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = Double.NaN)
        val rT = r.rT
        val rate: Double = rT.rate ?: error("rT.rate was null — algorithm produced no rate")

        assertFalse(rate.isNaN(), "rate must NOT be NaN, was $rate")
        assertFalse((rT.units ?: 0.0).isNaN(), "smb (rT.units) must NOT be NaN")
        assertTrue(rate.isFinite(), "rate must be finite")
    }

    @Test fun `dosingIsfMgdl == positive infinity falls back, no infinity propagates`() {
        whenever(glucoseStatus.glucose).thenReturn(160.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = Double.POSITIVE_INFINITY)
        val rT = r.rT
        val rate: Double = rT.rate ?: error("rT.rate was null — algorithm produced no rate")

        assertTrue(rate.isFinite(), "rate must be finite")
        assertTrue((rT.units ?: 0.0).isFinite(), "smb (rT.units) must be finite")
    }

    @Test fun `dosingIsfMgdl negative falls back gracefully`() {
        whenever(glucoseStatus.glucose).thenReturn(160.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = -50.0)
        val rT = r.rT
        val rate: Double = rT.rate ?: error("rT.rate was null — algorithm produced no rate")

        assertTrue(rate.isFinite(), "rate must be finite")
        assertTrue(
            rT.consoleError?.any { it.contains("invalid") } == true,
            "consoleError reports the negative-ISF fallback"
        )
    }

    @Test fun `when dosingIsfMgdl AND profile ISF both invalid, uses absolute floor`() {
        whenever(profile.getIsfMgdl(any())).thenReturn(Double.NaN)
        whenever(glucoseStatus.glucose).thenReturn(160.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = 0.0)
        val rT = r.rT
        val rate: Double = rT.rate ?: error("rT.rate was null — algorithm produced no rate")

        assertTrue(rate.isFinite(), "rate must be finite")
        assertTrue(
            rT.consoleError?.any { it.contains("absolute floor") } == true,
            "consoleError must name the absolute floor"
        )
    }

    @Test fun `when profile getIsfMgdl throws, uses absolute floor without crashing`() {
        // The validator wraps profile.getIsfMgdl in try/catch — a malformed profile
        // must not crash the loop cycle. Verifies the last-resort path.
        whenever(profile.getIsfMgdl(any())).thenThrow(IllegalStateException("test: simulated broken profile"))
        whenever(glucoseStatus.glucose).thenReturn(160.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = 0.0)
        val rT = r.rT
        val rate: Double = rT.rate ?: error("rT.rate was null — algorithm produced no rate")

        assertTrue(rate.isFinite(), "rate must be finite")
        assertTrue(
            rT.consoleError?.any { it.contains("absolute floor") } == true,
            "consoleError must name the absolute floor"
        )
    }

    @Test fun `valid dosingIsfMgdl produces no fallback consoleError`() {
        // Negative case — validator must NOT fire on the normal happy path.
        // Without this, a regression that always-triggers the fallback would silently
        // route through the safe-default path forever.
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(dosingIsfMgdl = 50.0)
        val rT = r.rT

        assertFalse(
            rT.consoleError?.any { it.contains("dosingIsfMgdl") && it.contains("invalid") } == true,
            "happy path must not emit ISF-fallback consoleError"
        )
    }

    // ── Item 2: dawn/cgm fraction clamping ──────────────────────────────────

    @Test fun `cgmSmbFraction above 1_0 is clamped to 1_0`() {
        // Two invocations with everything identical except cgmSmbFraction. Without the
        // clamp, the (uam*aggr).coerceIn(0.1, 0.9) cap on SMB would be bypassed by
        // multiplying through the >1.0 cgmFraction. With the clamp, out-of-range
        // cgmSmbFraction produces the SAME SMB as the in-range 1.0 case.
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val baseline   = invoke(cgmSmbFraction = 1.0)
        val outOfRange = invoke(cgmSmbFraction = 2.0)

        val baselineSmb   = baseline.rT.units ?: 0.0
        val outOfRangeSmb = outOfRange.rT.units ?: 0.0

        assertEquals(
            baselineSmb, outOfRangeSmb, 0.001,
            "cgmSmbFraction=2.0 must clamp to 1.0 and produce the same SMB as cgmSmbFraction=1.0"
        )
        assertTrue(baselineSmb > 0.0, "baseline SMB should be > 0 so this test is actually exercising the path")
    }

    @Test fun `cgmSmbFraction extremely large value is still clamped to 1_0`() {
        // Defensive against someone entering a percentage like 50.0 thinking "50%".
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val baseline   = invoke(cgmSmbFraction = 1.0)
        val percentage = invoke(cgmSmbFraction = 50.0)

        val baselineSmb   = baseline.rT.units ?: 0.0
        val percentageSmb = percentage.rT.units ?: 0.0

        assertEquals(
            baselineSmb, percentageSmb, 0.001,
            "An accidental percentage value (50.0) must clamp to 1.0, not boost SMB 50x"
        )
    }

    @Test fun `cgmSmbFraction below 1_0 still scales SMB normally (clamp is not over-aggressive)`() {
        // Sanity check that the clamp doesn't break legitimate sub-1.0 reductions.
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val full = invoke(cgmSmbFraction = 1.0)
        val half = invoke(cgmSmbFraction = 0.5)

        val fullSmb = full.rT.units ?: 0.0
        val halfSmb = half.rT.units ?: 0.0

        assertTrue(fullSmb > 0.0, "full-fraction SMB should be > 0 to exercise this test")
        assertTrue(halfSmb < fullSmb * 0.75, "half-fraction SMB ($halfSmb) must be meaningfully less than full ($fullSmb)")
        assertTrue(halfSmb >= 0.0, "half-fraction SMB must still be non-negative")
    }

    @Test fun `cgmSmbFraction negative is clamped to 0_0`() {
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(cgmSmbFraction = -1.0)
        val smb = r.rT.units ?: 0.0
        assertEquals(0.0, smb, 0.001, "negative cgmFraction must clamp to 0.0 — zero SMB delivered")
    }

    // dawnFraction clamping uses the same coerceIn(0.0, 1.0) pattern but only fires
    // when inDawnWindow is true. Verifying it externally would need a fixed-time test
    // (the dawn check uses Calendar.getInstance() against the device clock at currentTime).
    // The cgmFraction tests above are sufficient as proof-of-pattern; a separate dawn-clamp
    // test would need time-injection wiring that doesn't exist on this code path yet.

    // ── Item 1 + 2 sanity: no infinity / NaN on stressed input ───────────────

    @Test fun `pathological inputs combined produce no infinity or NaN at any output field`() {
        // Belt-and-braces: combine all the defensive-clamp cases in one cycle. Even
        // with every "bad input" path firing simultaneously, the loop must produce
        // finite, sane numbers — never NaN, never Infinity.
        whenever(glucoseStatus.glucose).thenReturn(180.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        whenever(glucoseStatus.delta).thenReturn(0.5)

        val r = invoke(
            dosingIsfMgdl    = Double.NaN,    // forces ISF fallback
            cgmSmbFraction   = 2.0,           // forces cgm clamp
            dawnSmbReduction = 1.7            // (no-op outside dawn window, still set)
        )
        val rT = r.rT
        val rate: Double  = rT.rate ?: error("rT.rate was null — algorithm produced no rate")
        val units: Double = rT.units ?: 0.0
        val duration: Int = rT.duration ?: 0

        assertTrue(rate.isFinite(), "rate must be finite, was $rate")
        assertFalse(rate.isNaN(), "rate must not be NaN")
        assertTrue(units.isFinite(), "units must be finite, was $units")
        assertFalse(units.isNaN(), "units must not be NaN")
        assertTrue(duration >= 0, "duration must be non-negative")
    }
}