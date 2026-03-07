package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.inject.Provider

class DetermineBasalSmartInsulinTest {

    // ── Mocks ────────────────────────────────────────────────────────────────

    private val apsResult: APSResult           = mock()
    private val apsResultProvider: Provider<APSResult> = Provider { apsResult }
    private val glucoseStatus: GlucoseStatus   = mock()
    private val currentTemp: CurrentTemp       = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = null)
    private val mealData: MealData             = mock()
    private val oapsProfile: OapsProfile       = mock()
    private val profile: Profile               = mock()

    private lateinit var sut: DetermineBasalSmartInsulin

    // Captured result fields
    private var capturedRate      = 0.0
    private var capturedDuration  = 0
    private var capturedTbr       = false
    private var capturedSmb       = 0.0
    private var capturedReason    = ""
    private val capturedGvs       = mutableListOf<Any>()

    @Before fun setUp() {
        sut = DetermineBasalSmartInsulin(apsResultProvider)

        // Wire result mock to capture assignments
        whenever(apsResult.predictionsAsGv).thenReturn(capturedGvs as MutableList<app.aaps.core.data.model.GV>)
        whenever(apsResult.rate = any()).thenAnswer        { capturedRate     = it.arguments[0] as Double; Unit }
        whenever(apsResult.duration = any()).thenAnswer    { capturedDuration = it.arguments[0] as Int;    Unit }
        whenever(apsResult.isTempBasalRequested = any()).thenAnswer { capturedTbr = it.arguments[0] as Boolean; Unit }
        whenever(apsResult.smb = any()).thenAnswer         { capturedSmb      = it.arguments[0] as Double; Unit }
        whenever(apsResult.reason = any()).thenAnswer      { capturedReason   = it.arguments[0] as String; Unit }
        whenever(apsResult.targetBG = any()).thenAnswer    { Unit }
        whenever(apsResult.deliverAt = any()).thenAnswer   { Unit }
        whenever(apsResult.hasPredictions = any()).thenAnswer { Unit }

        // Default OapsProfile values
        whenever(oapsProfile.sens).thenReturn(50.0)           // ISF 50 mg/dL/U
        whenever(oapsProfile.current_basal).thenReturn(1.0)   // 1 U/hr
        whenever(oapsProfile.target_bg).thenReturn(100.0)
        whenever(oapsProfile.min_bg).thenReturn(90.0)
        whenever(oapsProfile.max_bg).thenReturn(120.0)
        whenever(oapsProfile.enableSMB_always).thenReturn(true)
        whenever(oapsProfile.maxSMBBasalMinutes).thenReturn(30)
        whenever(oapsProfile.enableUAM).thenReturn(false)

        // Default glucose: stable at 120
        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        whenever(glucoseStatus.delta).thenReturn(0.0)
        whenever(glucoseStatus.date).thenReturn(System.currentTimeMillis())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Build a flat iobArray: constant IOB and activity across all slots */
    private fun flatIobArray(iob: Double, activity: Double, size: Int = 60): Array<IobTotal> =
        Array(size) { t -> IobTotal(time = t * 60_000L, iob = iob, activity = activity) }

    /** Default learnedProfile — FASTING defaults */
    private fun defaultProfile(mode: MealMode = MealMode.FASTING): LearnedInsulinProfile =
        LearnedInsulinProfile.defaultFor(mode)

    private fun invoke(
        iobArray:      Array<IobTotal> = flatIobArray(0.0, 0.0),
        learnedProfile: LearnedInsulinProfile = defaultProfile(),
        lowGuardMmol:  Double = 3.9,
        warnGuardMmol: Double = 4.5,
        microBolusAllowed: Boolean = true,
        horizonMins:   Int = 60
    ): APSResult = sut.determine_basal(
        glucoseStatus         = glucoseStatus,
        currentTemp           = currentTemp,
        iobArray              = iobArray,
        oapsProfile           = oapsProfile,
        mealData              = mealData,
        profile               = profile,
        learnedProfile        = learnedProfile,
        mealMode              = MealMode.FASTING,
        predictionHorizonMins = horizonMins,
        lowGuardMmol          = lowGuardMmol,
        warnGuardMmol         = warnGuardMmol,
        microBolusAllowed     = microBolusAllowed,
        currentTime           = System.currentTimeMillis()
    )

    // ── SUSPEND zone ─────────────────────────────────────────────────────────

    @Test fun `SUSPEND when predicted BG drops below low guard`() {
        // High activity pushing BG down fast: 0.1 U/min * 50 ISF = 5 mg/dL drop per minute
        // BG 120 → after 5 min already at 95, will breach 70.2 (3.9 mmol) well within 60 min
        whenever(glucoseStatus.glucose).thenReturn(90.0)
        val result = invoke(iobArray = flatIobArray(iob = 2.0, activity = 0.05))
        assertEquals(0.0, capturedRate,     0.001)
        assertEquals(30,  capturedDuration)
        assertTrue(capturedTbr)
        assertEquals(0.0, capturedSmb, 0.001)
        assertTrue("Reason should mention SUSPEND", capturedReason.contains("SUSPEND"))
    }

    @Test fun `SUSPEND zone produces zero SMB`() {
        whenever(glucoseStatus.glucose).thenReturn(72.0)
        invoke(iobArray = flatIobArray(iob = 1.5, activity = 0.04))
        assertEquals(0.0, capturedSmb, 0.001)
    }

    // ── CAUTION zone ─────────────────────────────────────────────────────────

    @Test fun `CAUTION zone reduces basal below 50 percent`() {
        // BG heading toward warn zone but not quite to hard floor
        whenever(glucoseStatus.glucose).thenReturn(85.0)
        whenever(oapsProfile.current_basal).thenReturn(1.0)
        invoke(iobArray = flatIobArray(iob = 1.0, activity = 0.015))
        assertTrue("Reduced basal should be less than 50% of 1.0", capturedRate <= 0.5)
        assertTrue("Reduced basal should be non-negative",          capturedRate >= 0.0)
        assertEquals(0.0, capturedSmb, 0.001)
        assertTrue(capturedReason.contains("CAUTION"))
    }

    @Test fun `CAUTION zone produces 30-minute TBR`() {
        whenever(glucoseStatus.glucose).thenReturn(85.0)
        invoke(iobArray = flatIobArray(iob = 1.0, activity = 0.015))
        assertEquals(30, capturedDuration)
        assertTrue(capturedTbr)
    }

    // ── NORMAL zone ──────────────────────────────────────────────────────────

    @Test fun `NORMAL zone uses profile basal when stable and at target`() {
        // BG at target, no IOB, no delta — should just run profile basal
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        whenever(oapsProfile.current_basal).thenReturn(0.8)
        invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        assertEquals(0.8, capturedRate, 0.001)
        assertEquals(0, capturedDuration)
        assertTrue(!capturedTbr)
    }

    @Test fun `NORMAL zone allows SMB when BG above target and microBolusAllowed`() {
        // BG 140, target 100: correction = 40/50 = 0.8U, SMB = 30% = 0.24U
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.5)
        invoke(
            iobArray          = flatIobArray(iob = 0.0, activity = 0.0),
            microBolusAllowed = true
        )
        assertTrue("SMB should be > 0 when above target", capturedSmb > 0.0)
        assertTrue("SMB should be < full correction",      capturedSmb < 0.8)
        assertTrue(capturedReason.contains("NORMAL"))
    }

    @Test fun `NORMAL zone blocks SMB when microBolusAllowed is false`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        invoke(
            iobArray          = flatIobArray(iob = 0.0, activity = 0.0),
            microBolusAllowed = false
        )
        assertEquals(0.0, capturedSmb, 0.001)
    }

    @Test fun `NORMAL zone blocks SMB when BG falling fast`() {
        whenever(glucoseStatus.glucose).thenReturn(140.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(-3.0)  // falling 3 mg/dL/5min
        invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        assertEquals(0.0, capturedSmb, 0.001)
    }

    @Test fun `SMB is capped by maxSMBBasalMinutes constraint`() {
        // max = 1 U/hr / 60 * 30 min = 0.5 U cap
        whenever(oapsProfile.current_basal).thenReturn(1.0)
        whenever(oapsProfile.maxSMBBasalMinutes).thenReturn(30)
        // BG way above target to generate large correction
        whenever(glucoseStatus.glucose).thenReturn(300.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(2.0)
        invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0))
        assertTrue("SMB must be <= 0.5U (maxSMBBasalMinutes cap)", capturedSmb <= 0.5)
    }

    // ── Prediction graph ─────────────────────────────────────────────────────

    @Test fun `predictionsAsGv is populated with correct count`() {
        invoke(horizonMins = 45)
        assertEquals(45, capturedGvs.size)
    }

    @Test fun `predictions are cleared and repopulated on each call`() {
        capturedGvs.add(mock<app.aaps.core.data.model.GV>())  // pre-populate junk
        invoke(horizonMins = 30)
        assertEquals(30, capturedGvs.size)
    }

    // ── Prediction accuracy sanity ────────────────────────────────────────────

    @Test fun `zero IOB and zero delta predicts flat BG`() {
        whenever(glucoseStatus.glucose).thenReturn(110.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(0.0)
        // With zero activity the prediction should stay flat
        // (capturedGvs first entry should be close to 110)
        invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0), horizonMins = 30)
        val firstPredicted = (capturedGvs.firstOrNull() as? app.aaps.core.data.model.GV)?.value ?: 110.0
        assertEquals(110.0, firstPredicted, 1.0)
    }

    @Test fun `positive delta with no IOB predicts rising BG for first 15 minutes`() {
        whenever(glucoseStatus.glucose).thenReturn(100.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(5.0)  // rising 5 mg/dL/5min
        invoke(iobArray = flatIobArray(iob = 0.0, activity = 0.0), horizonMins = 10)
        // At t=1, delta momentum is near full: bg should be > 100
        val firstPredicted = (capturedGvs.firstOrNull() as? app.aaps.core.data.model.GV)?.value ?: 100.0
        assertTrue("Rising delta should push BG above 100 at t=1", firstPredicted > 100.0)
    }
}