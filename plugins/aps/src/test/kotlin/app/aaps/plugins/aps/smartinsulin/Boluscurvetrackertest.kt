package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BolusCurveTrackerTest {

    private val profileLearner: ProfileLearner = mock()
    private val preferences: Preferences       = mock()
    private val logger: AAPSLogger             = mock()

    private lateinit var sut: BolusCurveTracker

    private val baseTime = System.currentTimeMillis()

    @Before fun setUp() {
        whenever(preferences.get(DoubleKey.ApsSmartInsulinLearningRate)).thenReturn(0.15)
        sut = BolusCurveTracker(profileLearner, preferences, logger)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun gs(bg: Double, timestampMs: Long): GlucoseStatus = mock<GlucoseStatus>().also {
        whenever(it.glucose).thenReturn(bg)
        whenever(it.shortAvgDelta).thenReturn(0.0)
        whenever(it.date).thenReturn(timestampMs)
    }

    private fun iob(iob: Double, activity: Double): Array<IobTotal> =
        Array(60) { t -> IobTotal(time = t * 60_000L, iob = iob, activity = activity) }

    /**
     * Simulate a full bolus event:
     * [rampUp] cycles above threshold → [active] cycles at peak → [tail] cycles fading to close.
     */
    private fun simulateEvent(
        mode:         MealMode = MealMode.FASTING,
        startBg:      Double   = 150.0,
        dropPerCycle: Double   = 3.0,
        rampUp:       Int      = 3,
        active:       Int      = 20,
        tail:         Int      = 5
    ) {
        var bg    = startBg
        var t     = baseTime

        repeat(rampUp) {
            sut.onLoopCycle(gs(bg, t), mode, iob(0.5, 0.008))
            bg -= dropPerCycle; t += 60_000L
        }
        repeat(active) {
            sut.onLoopCycle(gs(bg, t), mode, iob(1.5, 0.02))
            bg -= dropPerCycle; t += 60_000L
        }
        repeat(tail - 1) {
            sut.onLoopCycle(gs(bg, t), mode, iob(0.08, 0.002))
            t += 60_000L
        }
        // final cycle drops IOB below 0.1 threshold → closes window
        sut.onLoopCycle(gs(bg, t), mode, iob(0.04, 0.001))
    }

    // ── Window lifecycle ─────────────────────────────────────────────────────

    @Test fun `no learning call when IOB never reaches threshold`() {
        repeat(10) { i ->
            sut.onLoopCycle(gs(100.0, baseTime + i * 60_000L), MealMode.FASTING, iob(0.05, 0.0))
        }
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    @Test fun `complete event with sufficient drop triggers one learn call`() {
        simulateEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner, times(1)).observeBolusCurve(any(), any(), any(), any())
    }

    @Test fun `event with insufficient BG drop is discarded`() {
        simulateEvent(startBg = 110.0, dropPerCycle = 0.2, active = 10)
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    @Test fun `too few samples discards the event`() {
        var t = baseTime
        sut.onLoopCycle(gs(140.0, t),           MealMode.FASTING, iob(1.0, 0.02)); t += 60_000L
        sut.onLoopCycle(gs(135.0, t),           MealMode.FASTING, iob(1.0, 0.02)); t += 60_000L
        sut.onLoopCycle(gs(130.0, t),           MealMode.FASTING, iob(0.04, 0.001))
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    // ── Mode passthrough ─────────────────────────────────────────────────────

    @Test fun `correct MealMode is forwarded to profileLearner`() {
        val modeCaptor = argumentCaptor<MealMode>()
        simulateEvent(mode = MealMode.MEAL, startBg = 180.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(modeCaptor.capture(), any(), any(), any())
        assertEquals(MealMode.MEAL, modeCaptor.firstValue)
    }

    // ── Sequential events ─────────────────────────────────────────────────────

    @Test fun `two sequential events each produce one learn call`() {
        simulateEvent(startBg = 160.0, dropPerCycle = 4.0)
        simulateEvent(startBg = 155.0, dropPerCycle = 4.0)
        verify(profileLearner, times(2)).observeBolusCurve(any(), any(), any(), any())
    }

    // ── Learning rate ─────────────────────────────────────────────────────────

    @Test fun `learning rate from preferences is forwarded`() {
        whenever(preferences.get(DoubleKey.ApsSmartInsulinLearningRate)).thenReturn(0.25)
        val rateCaptor = argumentCaptor<Double>()
        simulateEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(any(), any(), any(), rateCaptor.capture())
        assertEquals(0.25, rateCaptor.firstValue, 0.001)
    }

    // ── Peak and DIA sanity ───────────────────────────────────────────────────

    @Test fun `observed peak minutes are positive`() {
        val peakCaptor = argumentCaptor<Double>()
        simulateEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(any(), peakCaptor.capture(), any(), any())
        assertTrue("Peak minutes must be > 0", peakCaptor.firstValue > 0.0)
    }

    @Test fun `observed DIA is greater than observed peak`() {
        val peakCaptor = argumentCaptor<Double>()
        val diaCaptor  = argumentCaptor<Double>()
        simulateEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(any(), peakCaptor.capture(), diaCaptor.capture(), any())
        assertTrue("DIA must be > peak", diaCaptor.firstValue > peakCaptor.firstValue)
    }
}