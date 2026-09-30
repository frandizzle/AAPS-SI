package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [DuraIsfTracker] — the meal-mode DURA_ISF stuck-BG strengthening tracker
 * (adapted from openAPS AutoISF's DURA_ISF).
 */
class DuraIsfTrackerTest {

    private lateinit var tracker: DuraIsfTracker

    @BeforeEach
    fun setUp() {
        tracker = DuraIsfTracker()
    }

    @Test
    fun `multiplier stays 1_0 while stuck duration is under the 10 minute floor`() {
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // anchor set, 0min
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // 5min stuck
        assertEquals(1.0, tracker.multiplier(targetMgdl = 100.0), 1e-9)
    }

    @Test
    fun `multiplier strengthens once stuck at least 10 minutes above target`() {
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // anchor set, 0min
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // 5min stuck
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // 10min stuck

        // stuckHours=10/60, avgWeight=1.0/100, mult = 1 + (10/60)*(1/100)*(150-100)
        val expected = 1.0 + (10.0 / 60.0) * (1.0 / 100.0) * 50.0
        assertEquals(expected, tracker.multiplier(targetMgdl = 100.0), 1e-9)
    }

    @Test
    fun `multiplier is bypassed when the stuck average is at or below target`() {
        tracker.onCycle(bgMgdl = 90.0, active = true, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 90.0, active = true, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 90.0, active = true, cycleMinutes = 5.0)
        assertEquals(1.0, tracker.multiplier(targetMgdl = 100.0), 1e-9)
    }

    @Test
    fun `breaking outside the band resets stuck duration`() {
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // 10min stuck, multiplier > 1

        // 200 is outside 150's +-5% band (142.5..157.5) -> resets
        tracker.onCycle(bgMgdl = 200.0, active = true, cycleMinutes = 5.0)
        assertEquals(1.0, tracker.multiplier(targetMgdl = 100.0), 1e-9,
                     "A jump outside the stuck band should reset accumulated duration")
    }

    @Test
    fun `active=false resets the tracker`() {
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 150.0, active = true, cycleMinutes = 5.0) // 10min stuck

        tracker.onCycle(bgMgdl = 150.0, active = false, cycleMinutes = 5.0) // DURA disabled / mode ended
        assertEquals(1.0, tracker.multiplier(targetMgdl = 100.0), 1e-9)
    }

    @Test
    fun `a -0_2 mmol per-cycle drop resets immediately even though it stays inside the band`() {
        // Build up 10min stuck at 160 mg/dL — well within band on its own.
        tracker.onCycle(bgMgdl = 160.0, active = true, deltaMgdl = 0.0, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 160.0, active = true, deltaMgdl = 0.0, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 160.0, active = true, deltaMgdl = 0.0, cycleMinutes = 5.0) // 10min stuck
        assertEquals(1.0 + (10.0 / 60.0) * (1.0 / 100.0) * 60.0, tracker.multiplier(targetMgdl = 100.0), 1e-9)

        // -3.6 mg/dL (~-0.2 mmol) in one cycle — comfortably inside 160's +-5% band (152..168),
        // so the old band-only logic would NOT have reset here. The explicit delta cutoff should.
        tracker.onCycle(bgMgdl = 156.4, active = true, deltaMgdl = -3.6, cycleMinutes = 5.0)
        assertEquals(1.0, tracker.multiplier(targetMgdl = 100.0), 1e-9,
                     "A -0.2 mmol/cycle drop should reset immediately, not just erode the band")
    }

    @Test
    fun `a gentle -0_1 mmol per-cycle drop does not trigger the delta reset on its own`() {
        tracker.onCycle(bgMgdl = 160.0, active = true, deltaMgdl = 0.0, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 160.0, active = true, deltaMgdl = 0.0, cycleMinutes = 5.0)
        tracker.onCycle(bgMgdl = 160.0, active = true, deltaMgdl = 0.0, cycleMinutes = 5.0) // 10min stuck

        // -1.8 mg/dL (~-0.1 mmol) is above the -3.6 mg/dL reset threshold, and still inside band
        tracker.onCycle(bgMgdl = 158.2, active = true, deltaMgdl = -1.8, cycleMinutes = 5.0)
        assertTrue(tracker.multiplier(targetMgdl = 100.0) > 1.0,
                   "A gentle -0.1 mmol/cycle drop shouldn't trip the fast-reset cutoff")
    }
}
