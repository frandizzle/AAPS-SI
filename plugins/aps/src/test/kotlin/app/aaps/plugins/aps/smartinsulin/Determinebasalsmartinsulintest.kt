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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

        override fun with(result: RT): APSResult         = this
        override suspend fun resultAsString(): String            = reason
        override suspend fun resultAsSpanned(): Spanned          = mock()
        override suspend fun resultAsHtmlString(): String        = ""
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

    @Before fun setUp() {
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

        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.date).thenReturn(System.currentTimeMillis())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun flatIobArray(iob: Double, activity: Double, size: Int = 60): Array<IobTotal> =
        Array(size) { t -> IobTotal(time = t * 60_000L, iob = iob, activity = activity) }

    private fun defaultLearned(mode: MealMode = MealMode.FASTING) =
        LearnedInsulinProfile.defaultFor(mode)

    private fun invoke(
        iobArray:          Array<IobTotal>      = flatIobArray(0.0, 0.0),
        learnedProfile:    LearnedInsulinProfile = defaultLearned(),
        lowGuardMmol:      Double               = 3.9,
        warnGuardMmol:     Double               = 4.5,
        microBolusAllowed: Boolean              = true,
        horizonMins:       Int                  = 60
    ): FakeAPSResult {
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
            dosingIsfMgdl            = 50.0,
            microBolusAllowed        = microBolusAllowed,
            inReboundWindow          = false,
            msSinceLastSuspend       = 3600_000L,
            currentTime              = System.currentTimeMillis(),
            isTempTarget             = false,
            profileTargetMgdl        = 100.0,
            dawnWindowStartHour      = 4,
            dawnWindowEndHour        = 7,
            dawnSmbReduction         = 0.5,
            bgWentLow                = false,
            activityLevel            = ActivityMonitor.ActivityLevel.SEDENTARY,
            activityTargetOffsetMmol = 0.0,
            cgmSmbFraction           = 1.0,
            cgmDeltaPlausible        = true,
            cgmWarmupReason          = "",
            uamSmbFraction           = 1.0,
            targetRespectEnabled     = false,
            reboundWindowMins        = horizonMins.toDouble(), // Matches legacy horizon input
            circCeil                 = 1.0,
            isMmol                   = true
        )
        return fakeResult
    }

    // ── SUSPEND zone ─────────────────────────────────────────────────────────

    @Test fun `SUSPEND when predicted BG drops below low guard`() {
        whenever(glucoseStatus.glucose).thenReturn(90.0)
        val r = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.05))
        assertEquals(0.0, r.rate,     0.001)
        assertEquals(30,  r.duration)
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

    @Test fun `CAUTION zone reduces basal to 50 percent or below`() {
        whenever(glucoseStatus.glucose).thenReturn(85.0)
        val r = invoke(iobArray = flatIobArray(iob = 1.0, activity = 0.015))
        assertTrue("Reduced basal should be <= 0.5 U/hr", r.rate <= 0.5)
        assertTrue("Reduced basal should be non-negative", r.rate >= 0.0)
        assertEquals(0.0, r.smb, 0.001)
        assertTrue(r.reason.contains("CAUTION"))
    }

    @Test fun `CAUTION zone sets 30-minute TBR`() {
        whenever(glucoseStatus.glucose).thenReturn(85.0)
        val r = invoke(iobArray = flatIobArray(iob = 1.0, activity = 0.015))
        assertEquals(30, r.duration)
        assertTrue(r.isTempBasalRequested)
    }

    // ── NORMAL zone ──────────────────────────────────────────────────────────

    @Test fun `NORMAL zone uses profile basal when stable at target`() {
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        val r = invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        assertEquals(1.0, r.rate, 0.001)
        assertEquals(0,   r.duration)
        assertFalse(r.isTempBasalRequested)
    }

    @Test fun `NORMAL zone allows SMB above target when microBolusAllowed`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), microBolusAllowed = true)
        assertTrue("SMB should be > 0 when above target", r.smb > 0.0)
        assertTrue("SMB should be < full correction",     r.smb < 0.8)
        assertTrue(r.reason.contains("NORMAL"))
    }

    @Test fun `NORMAL zone blocks SMB when microBolusAllowed is false`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), microBolusAllowed = false)
        assertEquals(0.0, r.smb, 0.001)
    }

    @Test fun `NORMAL zone blocks SMB when BG falling fast`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(-3.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertEquals(0.0, r.smb, 0.001)
    }

    @Test fun `SMB is capped by maxSMBBasalMinutes`() {
        // cap = 1.0 U/hr / 60 * 30 min = 0.5 U
        whenever(oapsProfile.current_basal).thenReturn(1.0)
        whenever(oapsProfile.maxSMBBasalMinutes).thenReturn(30)
        whenever(glucoseStatus.glucose).thenReturn(300.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(2.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0))
        assertTrue("SMB must be <= 0.5 U cap", r.smb <= 0.5)
    }

    // ── Prediction graph ─────────────────────────────────────────────────────

    @Test fun `predictionsAsGv is populated with correct count`() {
        val r = invoke(horizonMins = 45)
        assertEquals(45, r.predictionsAsGv.size)
    }

    @Test fun `predictions are cleared and repopulated each call`() {
        fakeResult.predictionsAsGv.add(
            GV(timestamp = 0L, value = 99.0, raw = null,
               trendArrow = TrendArrow.NONE, noise = null,
               sourceSensor = SourceSensor.UNKNOWN)
        )
        val r = invoke(horizonMins = 30)
        assertEquals(30, r.predictionsAsGv.size)
    }

    @Test fun `zero IOB and zero delta predicts near-flat BG`() {
        whenever(glucoseStatus.glucose).thenReturn(110.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), horizonMins = 30)
        assertEquals(110.0, r.predictionsAsGv.first().value, 1.0)
    }

    @Test fun `positive delta with no IOB predicts rising BG at t=1`() {
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(5.0)
        val r = invoke(iobArray = flatIobArray(0.0, 0.0), horizonMins = 10)
        assertTrue("Rising delta should push BG above 100", r.predictionsAsGv.first().value > 100.0)
    }
}