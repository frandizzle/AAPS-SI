package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
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

    private fun makeGlucoseStatus(bg: Double, timestampMs: Long): GlucoseStatus = mock<GlucoseStatus>().also {
        whenever(it.glucose).thenReturn(bg)
        whenever(it.shortAvgDelta).thenReturn(0.0)
        whenever(it.date).thenReturn(timestampMs)
    }

    private fun makeIobArray(iob: Double, activity: Double): Array<IobTotal> =
        Array(60) { t -> IobTotal(time = t * 60_000L, iob = iob, activity = activity) }

    /**
     * Simulate a complete bolus event:
     * - [rampUp]   cycles with rising IOB (window opens)
     * - [active]   cycles with full IOB and BG dropping
     * - [tail]     cycles with falling IOB and stable BG (window closes at end)
     *
     * All samples are spaced 1 minute apart.
     */
    private fun simulateCompleteEvent(
        mode:          MealMode = MealMode.FASTING,
        startBg:       Double = 150.0,
        dropPerCycle:  Double = 3.0,
        rampUp:        Int = 3,
        active:        Int = 20,
        tail:          Int = 5
    ) {
        var bg = startBg
        var timeMs = baseTime

        // Opening: IOB above threshold
        repeat(rampUp) {
            sut.onLoopCycle(makeGlucoseStatus(bg, timeMs), mode, makeIobArray(0.5, 0.008))
            bg -= dropPerCycle
            timeMs += 60_000L
        }
        // Active: maximum IOB + highest activity (peak should be detected here)
        repeat(active) {
            sut.onLoopCycle(makeGlucoseStatus(bg, timeMs), mode, makeIobArray(1.5, 0.02))
            bg -= dropPerCycle
            timeMs += 60_000L
        }
        // Tail: IOB fading back below threshold — window closes on last cycle
        repeat(tail - 1) {
            sut.onLoopCycle(makeGlucoseStatus(bg, timeMs), mode, makeIobArray(0.08, 0.002))
            timeMs += 60_000L
        }
        // Final cycle drops IOB below threshold — triggers fit attempt
        sut.onLoopCycle(makeGlucoseStatus(bg, timeMs), mode, makeIobArray(0.04, 0.001))
    }

    // ── Window lifecycle ─────────────────────────────────────────────────────

    @Test fun `no learning call when IOB never rises above threshold`() {
        repeat(10) { t ->
            sut.onLoopCycle(
                makeGlucoseStatus(100.0, baseTime + t * 60_000L),
                MealMode.FASTING,
                makeIobArray(iob = 0.05, activity = 0.0)
            )
        }
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    @Test fun `complete event with sufficient drop triggers observeBolusCurve`() {
        simulateCompleteEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner, times(1)).observeBolusCurve(any(), any(), any(), any())
    }

    @Test fun `event with insufficient BG drop is discarded`() {
        // Only 5 mg/dL drop across entire event — below 10 mg/dL threshold
        simulateCompleteEvent(startBg = 110.0, dropPerCycle = 0.2, active = 10)
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    @Test fun `too few samples discards the event`() {
        // Only 3 cycles total — below MIN_WINDOW_SAMPLES (6)
        val timeMs = baseTime
        sut.onLoopCycle(makeGlucoseStatus(140.0, timeMs),           MealMode.FASTING, makeIobArray(1.0, 0.02))
        sut.onLoopCycle(makeGlucoseStatus(135.0, timeMs + 60_000),  MealMode.FASTING, makeIobArray(1.0, 0.02))
        // Drop IOB — closes window with only 3 samples
        sut.onLoopCycle(makeGlucoseStatus(130.0, timeMs + 120_000), MealMode.FASTING, makeIobArray(0.04, 0.001))
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    // ── Mode is passed through ────────────────────────────────────────────────

    @Test fun `correct MealMode is passed to profileLearner`() {
        val captor = org.mockito.kotlin.argumentCaptor<MealMode>()
        simulateCompleteEvent(mode = MealMode.MEAL, startBg = 180.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(captor.capture(), any(), any(), any())
        assertEquals(MealMode.MEAL, captor.firstValue)
    }

    // ── Window resets after close ─────────────────────────────────────────────

    @Test fun `two sequential bolus events each trigger exactly one learn call`() {
        simulateCompleteEvent(startBg = 160.0, dropPerCycle = 4.0)
        simulateCompleteEvent(startBg = 155.0, dropPerCycle = 4.0)
        verify(profileLearner, times(2)).observeBolusCurve(any(), any(), any(), any())
    }

    // ── Learning rate from preferences ────────────────────────────────────────

    @Test fun `learning rate from preferences is forwarded to profileLearner`() {
        whenever(preferences.get(DoubleKey.ApsSmartInsulinLearningRate)).thenReturn(0.25)
        val rateCaptor = org.mockito.kotlin.argumentCaptor<Double>()
        simulateCompleteEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(any(), any(), any(), rateCaptor.capture())
        assertEquals(0.25, rateCaptor.firstValue, 0.001)
    }

    // ── Peak mins are positive ────────────────────────────────────────────────

    @Test fun `observed peak minutes are positive`() {
        val peakCaptor = org.mockito.kotlin.argumentCaptor<Double>()
        simulateCompleteEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(any(), peakCaptor.capture(), any(), any())
        assertTrue("Peak minutes must be > 0", peakCaptor.firstValue > 0.0)
    }

    // ── DIA > peak ────────────────────────────────────────────────────────────

    @Test fun `observed DIA is greater than observed peak`() {
        val peakCaptor = org.mockito.kotlin.argumentCaptor<Double>()
        val diaCaptor  = org.mockito.kotlin.argumentCaptor<Double>()
        simulateCompleteEvent(startBg = 160.0, dropPerCycle = 4.0)
        verify(profileLearner).observeBolusCurve(any(), peakCaptor.capture(), diaCaptor.capture(), any())
        assertTrue("DIA must be > peak", diaCaptor.firstValue > peakCaptor.firstValue)
    }
}