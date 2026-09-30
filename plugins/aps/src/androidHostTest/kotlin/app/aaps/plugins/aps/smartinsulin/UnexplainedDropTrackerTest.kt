package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [UnexplainedDropTracker] — distinguishes "BG fell because insulin was too strong"
 * (explained) from "BG fell for reasons insulin can't account for" (exercise, missed food).
 * Window 45min, suspect threshold -27 mg/dL (~-1.5 mmol) of unexplained movement.
 */
class UnexplainedDropTrackerTest {

    private lateinit var tracker: UnexplainedDropTracker

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() {
        tracker = UnexplainedDropTracker()
    }

    @Test
    fun `a fall that matches insulin activity is fully explained`() {
        // expected = -0.04*50*5 = -10 mg/dL per cycle; actual delta also -10 → residual 0
        var t = BASE_MS
        repeat(6) {
            tracker.onCycle(deltaMgdl = -10.0, activityPerMin = 0.04, isfMgdl = 50.0, nowMs = t)
            t += CYCLE_MS
        }
        assertEquals(0.0, tracker.unexplainedDropMgdl, 1e-9)
        assertFalse(tracker.exerciseSuspected, "A fall the insulin model predicts is an ISF signal, not exercise")
    }

    @Test
    fun `a fall far steeper than insulin explains flags as suspected`() {
        // expected -10/cycle, actual -18/cycle → -8 unexplained per cycle; 4 cycles = -32 < -27
        var t = BASE_MS
        repeat(4) {
            tracker.onCycle(deltaMgdl = -18.0, activityPerMin = 0.04, isfMgdl = 50.0, nowMs = t)
            t += CYCLE_MS
        }
        assertEquals(-32.0, tracker.unexplainedDropMgdl, 1e-9)
        assertTrue(tracker.exerciseSuspected)
    }

    @Test
    fun `a small unexplained drop stays below the threshold`() {
        // -3 unexplained per cycle × 4 = -12, well short of -27
        var t = BASE_MS
        repeat(4) {
            tracker.onCycle(deltaMgdl = -13.0, activityPerMin = 0.04, isfMgdl = 50.0, nowMs = t)
            t += CYCLE_MS
        }
        assertFalse(tracker.exerciseSuspected, "Ordinary model error must not be mistaken for exercise")
    }

    @Test
    fun `a fall with no insulin on board at all is entirely unexplained`() {
        var t = BASE_MS
        repeat(4) {
            tracker.onCycle(deltaMgdl = -10.0, activityPerMin = 0.0, isfMgdl = 50.0, nowMs = t)
            t += CYCLE_MS
        }
        assertEquals(-40.0, tracker.unexplainedDropMgdl, 1e-9)
        assertTrue(tracker.exerciseSuspected)
    }

    @Test
    fun `samples older than the window drop out`() {
        var t = BASE_MS
        repeat(4) {
            tracker.onCycle(deltaMgdl = -18.0, activityPerMin = 0.04, isfMgdl = 50.0, nowMs = t)
            t += CYCLE_MS
        }
        assertTrue(tracker.exerciseSuspected)

        // Jump an hour ahead with a benign reading — the old samples age out of the 45min window
        tracker.onCycle(deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, nowMs = t + 60 * 60_000L)
        assertFalse(tracker.exerciseSuspected, "Stale samples must not keep the flag latched")
    }

    @Test
    fun `a rise is never treated as a suspected drop`() {
        var t = BASE_MS
        repeat(6) {
            tracker.onCycle(deltaMgdl = 12.0, activityPerMin = 0.02, isfMgdl = 50.0, nowMs = t)
            t += CYCLE_MS
        }
        assertFalse(tracker.exerciseSuspected)
    }
}
