package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Signal 0 (CyclicDelta) — the per-cycle basal read for quiet, insulin-free stretches.
 *
 * It used to fire on a SINGLE reading with a full-scale ask from ordinary drift: −3 mg/dL/5min
 * moved a fresh bucket's basal 4.5% and its ISF 0.8% in one cycle, more than the drift signal
 * moves in an hour. Now it needs the same story three readings running, and one cycle is bounded
 * to about 2%.
 */
class CircadianSignal0Test {

    private lateinit var learner: CircadianLearner
    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val DOW      = 3
    private val HOUR     = 10
    private val TARGET   = 99.0

    @BeforeEach
    fun setUp() { learner = CircadianLearner(FakeAAPSLogger(collect = false), FakePreferences()) }

    private fun glucoseStatus(bg: Double, delta: Double): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(bg)
            whenever(this.shortAvgDelta).thenReturn(delta)
            whenever(this.delta).thenReturn(delta)
            whenever(this.date).thenReturn(BASE_MS)
        }

    /** Quiet fasting cycles: no insulin activity, no IOB — Signal 0's own regime. */
    private fun quiet(bg: Double, delta: Double, cycles: Int, startMs: Long = BASE_MS): Long {
        var t = startMs
        repeat(cycles) {
            learner.update(
                glucoseStatus  = glucoseStatus(bg, delta),
                iobArray       = arrayOf(IobTotal(time = 0, iob = 0.0, activity = 0.0, basaliob = 0.0)),
                mealMode       = MealMode.FASTING,
                cobG           = 0.0,
                profileIsfMgdl = 50.0,
                targetMgdl     = TARGET,
                hour = HOUR, dow = DOW, minute = 30, nowMs = t
            )
            t += CYCLE_MS
        }
        return t
    }

    private fun basal() = learner.basalMultiplier(HOUR, DOW, minute = 30)
    private fun isf()   = learner.isfMultiplier(HOUR, DOW, minute = 30)

    @Test
    fun `one reading no longer moves anything`() {
        quiet(bg = 121.0, delta = -3.0, cycles = 1)
        assertEquals(1.0, basal(), 1e-9)
        assertEquals(1.0, isf(), 1e-9)
    }

    @Test
    fun `two readings still do not`() {
        quiet(bg = 121.0, delta = -3.0, cycles = 2)
        assertEquals(1.0, basal(), 1e-9)
    }

    @Test
    fun `three readings of the same story do move it`() {
        quiet(bg = 121.0, delta = -3.0, cycles = 3)
        assertTrue(basal() < 1.0, "should have learned by the third, got ${basal()}")
    }

    @Test
    fun `the move a real drift asks for is now a nudge, not a lurch`() {
        // The screenshot case: −3 mg/dL/5min used to land basal on 0.955 from one cycle.
        quiet(bg = 121.0, delta = -3.0, cycles = 3)
        assertTrue(basal() > 0.98, "one firing should be ~2% at most, got ${basal()}")
        assertTrue(isf() > 0.995, "and the ISF cross-nudge a fraction of that, got ${isf()}")
    }

    @Test
    fun `an alternating delta never builds a run`() {
        var t = BASE_MS
        repeat(6) {
            t = quiet(bg = 121.0, delta = if (it % 2 == 0) -3.0 else 3.0, cycles = 1, startMs = t)
        }
        // Asserted on the signal rather than the multiplier: by the sixth quiet cycle predTrim has
        // enough window to project, and it is entitled to act. Signal 0 must not have.
        assertTrue(!learner.lastBasalSignal.contains("CyclicDelta"),
                   "noise that changes its mind is not a signal: ${learner.lastBasalSignal}")
    }

    @Test
    fun `a quiet cycle in the middle breaks the run`() {
        quiet(bg = 121.0, delta = -3.0, cycles = 2)
        quiet(bg = 121.0, delta = -0.2, cycles = 1, startMs = BASE_MS + 2 * CYCLE_MS)   // under the noise gate
        quiet(bg = 121.0, delta = -3.0, cycles = 2, startMs = BASE_MS + 3 * CYCLE_MS)
        assertEquals(1.0, basal(), 1e-9)
    }

    @Test
    fun `insulin on board breaks the run too`() {
        quiet(bg = 121.0, delta = -3.0, cycles = 2)
        // A cycle with real IOB is not a clean basal read.
        learner.update(
            glucoseStatus  = glucoseStatus(121.0, -3.0),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 3.05, activity = 0.02, basaliob = 0.0)),
            mealMode       = MealMode.FASTING, cobG = 0.0, profileIsfMgdl = 50.0, targetMgdl = TARGET,
            hour = HOUR, dow = DOW, minute = 30, nowMs = BASE_MS + 2 * CYCLE_MS
        )
        quiet(bg = 121.0, delta = -3.0, cycles = 2, startMs = BASE_MS + 3 * CYCLE_MS)
        assertTrue(!learner.lastBasalSignal.contains("CyclicDelta"),
                   "the run restarted, so nothing should have fired: ${learner.lastBasalSignal}")
    }

    @Test
    fun `a big sustained fall is capped per cycle`() {
        // −30 mg/dL/5min would once have asked for the full rail in one go.
        quiet(bg = 150.0, delta = -30.0, cycles = 3)
        assertTrue(basal() > 0.97, "a single firing must stay bounded, got ${basal()}")
    }

    @Test
    fun `a sustained rise above target still raises basal`() {
        quiet(bg = 160.0, delta = 3.0, cycles = 3)
        assertTrue(basal() > 1.0, "the up direction still works, got ${basal()}")
    }
}
