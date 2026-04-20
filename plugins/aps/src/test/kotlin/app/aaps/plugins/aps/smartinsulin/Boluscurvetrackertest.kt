package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BolusCurveTrackerTest {

    private val profileLearner: ProfileLearner = mock()
    private val preferences: Preferences       = mock()
    private val logger: AAPSLogger             = mock()

    private lateinit var sut: BolusCurveTracker

    private val baseTime = System.currentTimeMillis()

    @Before fun setUp() {
        sut = BolusCurveTracker(profileLearner, preferences, logger)
    }

    private fun gs(bg: Double, timestampMs: Long): GlucoseStatus = mock<GlucoseStatus>().also {
        whenever(it.glucose).thenReturn(bg)
        whenever(it.shortAvgDelta).thenReturn(0.0)
        whenever(it.date).thenReturn(timestampMs)
    }

    private fun iobArray(iob: Double): Array<IobTotal> =
        Array(1) { IobTotal(time = 0, iob = iob, activity = 0.0) }

    @Test fun `starts tracking on IOB spike`() {
        var t = baseTime
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(0.0)); t += 300_000L
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(1.0))
        
        assertTrue(sut.statusSummary().contains("tracker=kinetics"))
    }

    @Test fun `completes FASTING kinetics observation`() {
        var t = baseTime
        var bg = 120.0
        // Seed
        sut.onLoopCycle(gs(bg, t), MealMode.FASTING, iobArray(0.0)); t += 300_000L
        // Spike
        sut.onLoopCycle(gs(bg, t), MealMode.FASTING, iobArray(2.0)); t += 300_000L
        
        // Rise to peak IOB then decline
        sut.onLoopCycle(gs(bg, t), MealMode.FASTING, iobArray(2.5)); t += 300_000L
        sut.onLoopCycle(gs(bg, t), MealMode.FASTING, iobArray(2.2)); t += 300_000L // decline seen
        
        // Track nadir
        repeat(12) { // 60 mins
            bg -= 2.0
            sut.onLoopCycle(gs(bg, t), MealMode.FASTING, iobArray(1.5)); t += 300_000L
        }
        val nadirBg = bg
        
        // Recovery
        repeat(7) { // 35 mins (above MIN_CONFIRM_DELAY_MS)
            bg += 2.0
            sut.onLoopCycle(gs(bg, t), MealMode.FASTING, iobArray(1.0)); t += 300_000L
        }
        
        verify(profileLearner).observeInsulinKinetics(any(), any(), any())
    }

    @Test fun `completes MEAL absorption observation`() {
        var t = baseTime
        var bg = 100.0
        // Seed
        sut.onLoopCycle(gs(bg, t), MealMode.BREAKFAST, iobArray(0.0)); t += 300_000L
        // Spike
        sut.onLoopCycle(gs(bg, t), MealMode.BREAKFAST, iobArray(2.0)); t += 300_000L
        
        // BG Peak
        repeat(12) { // 60 mins
            bg += 5.0
            sut.onLoopCycle(gs(bg, t), MealMode.BREAKFAST, iobArray(2.5)); t += 300_000L
        }
        
        // Recovery
        repeat(12) { // 60 mins
            bg -= 3.0
            sut.onLoopCycle(gs(bg, t), MealMode.BREAKFAST, iobArray(1.5)); t += 300_000L
        }
        
        verify(profileLearner).observeCarbAbsorption(any(), any(), any(), any())
    }

    @Test fun `abandons on mode change`() {
        var t = baseTime
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(0.0)); t += 300_000L
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(1.0)); t += 300_000L
        
        sut.onLoopCycle(gs(100.0, t), MealMode.BREAKFAST, iobArray(1.0))
        
        assertTrue(sut.statusSummary().contains("tracker=idle"))
    }

    @Test fun `abandons on large IOB spike while tracking`() {
        var t = baseTime
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(0.0)); t += 300_000L
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(1.0)); t += 300_000L
        
        sut.onLoopCycle(gs(100.0, t), MealMode.FASTING, iobArray(2.5))
        
        assertTrue(sut.statusSummary().contains("tracker=idle"))
    }

    private fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("Expected true")
    }
}
