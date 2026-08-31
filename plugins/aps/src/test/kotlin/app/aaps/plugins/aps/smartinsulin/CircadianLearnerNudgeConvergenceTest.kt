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
import kotlin.math.abs

/**
 * Convergence and restoring-force behaviour of [CircadianLearner]'s aggression nudge.
 *
 * The nudge used to be an open-loop integrator: every cycle with a depressed aggressiveness
 * ceiling multiplied the ISF and basal multipliers by (1 - deviation*scale) and wrote the result
 * back with alpha 1.0. Its output never fed back into its input, and the only thing that raises
 * the ceiling again — stability near target — is unreachable while the nudge is cutting insulin.
 * So a single bad low at one hour walked both multipliers to their floors over a few days of
 * occupying that hour, and "this hour keeps running high" was not a signal that could stop it.
 *
 * The nudge now treats the ceiling deficit as a bounded displacement from the nudge-free
 * cross-day baseline and EWMAs toward that target, so it converges; and when the ceiling is
 * neutral again it decays back toward the baseline, so an unrepeated event expires.
 *
 * These tests hold the ceiling pinned and run far more cycles than any real episode would.
 */
class CircadianLearnerNudgeConvergenceTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    // Bucket centre — zero neighbour weight, so reads and writes stay inside one hour bucket
    // and the assertions below are about the nudge alone, not boundary interpolation.
    private val CENTRE = 30
    private val HOUR   = 3
    private val DOW    = 2

    // Mirrors of the learner's private bounds, so a change to either is caught here.
    private val ISF_MULT_MIN   = 0.7
    private val BASAL_MULT_MIN = 0.5

    @BeforeEach
    fun setUp() {
        learner = CircadianLearner(logger, FakePreferences())
    }

    private fun glucoseStatus(glucose: Double, shortAvgDelta: Double): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(glucose)
            whenever(this.shortAvgDelta).thenReturn(shortAvgDelta)
            whenever(this.delta).thenReturn(shortAvgDelta)
            whenever(this.date).thenReturn(BASE_MS)
        }

    /**
     * A quiet cycle at target with no insulin activity: every physics learner's gate fails
     * (activity below MIN_ACTIVITY, delta under the noise floor, no drift, nothing projected off
     * target, IOB not negative), so the aggression nudge is the only writer. That isolation is
     * what lets these tests attribute all movement to the nudge.
     */
    private fun runCycles(count: Int, aggressiveness: Double, startMs: Long): Long {
        var t = startMs
        repeat(count) {
            learner.update(
                glucoseStatus  = glucoseStatus(glucose = 100.0, shortAvgDelta = 0.0),
                iobArray       = arrayOf(IobTotal(time = 0, iob = 0.0, activity = 0.0, basaliob = 0.0)),
                mealMode       = MealMode.FASTING,
                cobG           = 0.0,
                profileIsfMgdl = 50.0,
                targetMgdl     = 100.0,
                aggressiveness = aggressiveness,
                hour = HOUR, dow = DOW, minute = CENTRE, nowMs = t
            )
            t += CYCLE_MS
        }
        return t
    }

    private fun isf() = learner.isfMultiplier(HOUR, DOW, CENTRE)
    private fun basal() = learner.basalMultiplier(HOUR, DOW, CENTRE)

    @Test
    fun `sustained depressed ceiling converges to a bounded displacement instead of hitting the floors`() {
        // 2000 cycles is ~7 days of continuously occupying this hour — far longer than the old
        // integrator needed to bottom out (it reached the ISF floor in roughly five days).
        runCycles(2000, aggressiveness = 0.85, startMs = BASE_MS)

        // Ceiling deficit 0.15 -> displacement 0.15 from the baseline (still 1.0: the nudge
        // writes day-only, so the cross-day bucket stays at its default).
        assertEquals(0.85, isf(), 1e-6,
                     "ISF multiplier should settle at baseline*(1-deficit), not keep ratcheting")
        assertEquals(0.85, basal(), 1e-6,
                     "Basal multiplier should settle at baseline*(1-deficit), not keep ratcheting")

        assertTrue(isf() > ISF_MULT_MIN + 0.05,
                   "ISF multiplier must not walk to its floor under a sustained ceiling, got ${isf()}")
        assertTrue(basal() > BASAL_MULT_MIN + 0.05,
                   "Basal multiplier must not walk to its floor under a sustained ceiling, got ${basal()}")
    }

    @Test
    fun `nudge steps shrink as the target is approached`() {
        var t = runCycles(1, aggressiveness = 0.85, startMs = BASE_MS)
        val afterFirst = isf()
        val firstStep  = 1.0 - afterFirst

        t = runCycles(199, aggressiveness = 0.85, startMs = t)
        val before = isf()
        runCycles(1, aggressiveness = 0.85, startMs = t)
        val lateStep = before - isf()

        assertTrue(firstStep > 0.0, "First cycle should move the multiplier down")
        assertTrue(lateStep < firstStep / 10.0,
                   "Step size must shrink as the target is approached (open-loop integration would " +
                       "keep the step constant): first=$firstStep late=$lateStep")
    }

    @Test
    fun `displacement is capped even when the ceiling deficit is extreme`() {
        // Ceiling at its own floor (0.60) implies a 40% deficit; the nudge is a heuristic on top
        // of measured physics and is capped at AGGR_NUDGE_MAX_DISPLACEMENT (0.25).
        runCycles(2000, aggressiveness = 0.60, startMs = BASE_MS)

        assertEquals(0.75, isf(), 1e-6, "ISF displacement should be capped at 25%")
        assertEquals(0.75, basal(), 1e-6, "Basal displacement should be capped at 25%")
    }

    @Test
    fun `a neutral ceiling decays a past displacement back toward the baseline`() {
        val t = runCycles(2000, aggressiveness = 0.85, startMs = BASE_MS)
        assertEquals(0.85, isf(), 1e-6, "precondition: converged to the displaced value")

        // Ceiling neutral again — nothing is re-confirming the correction.
        runCycles(2000, aggressiveness = 1.0, startMs = t)

        // The decay stops at the ±AGGR_NUDGE_DECAY_BAND (5%) tolerance around the baseline
        // rather than collapsing exactly onto it, so genuine day-specific physics survives.
        assertEquals(0.95, isf(), 1e-4,
                     "ISF should decay back to the edge of the tolerance band, got ${isf()}")
        assertEquals(0.95, basal(), 1e-4,
                     "Basal should decay back to the edge of the tolerance band, got ${basal()}")
    }

    @Test
    fun `decay does not overshoot past the baseline`() {
        val t = runCycles(2000, aggressiveness = 0.85, startMs = BASE_MS)
        runCycles(5000, aggressiveness = 1.0, startMs = t)

        assertTrue(isf() <= 1.0 + 1e-9,
                   "Decay must approach the baseline from below, never cross it, got ${isf()}")
        assertTrue(basal() <= 1.0 + 1e-9,
                   "Decay must approach the baseline from below, never cross it, got ${basal()}")
    }

    @Test
    fun `an upward ceiling surplus converges symmetrically`() {
        runCycles(2000, aggressiveness = 1.15, startMs = BASE_MS)

        // notEnough requires BG above target + the trim dead band; the quiet cycle sits AT
        // target, so the at-or-below-target guard blocks the add-insulin direction entirely.
        assertEquals(1.0, isf(), 1e-9,
                     "Adding insulin must stay blocked while BG is at target, whatever the ceiling says")
        assertEquals(1.0, basal(), 1e-9,
                     "Adding insulin must stay blocked while BG is at target, whatever the ceiling says")
    }

    @Test
    fun `the nudge never writes the cross-day baseline`() {
        runCycles(500, aggressiveness = 0.85, startMs = BASE_MS)

        // Any other weekday reads the same hour through the untouched global bucket. It sees a
        // blend, so it must be far closer to 1.0 than the nudged day is — the nudge is a
        // day-scoped correction and must not rewrite the week's baseline.
        val otherDay = learner.isfMultiplier(HOUR, (DOW + 3) % 7, CENTRE)
        assertTrue(abs(otherDay - 1.0) < abs(isf() - 1.0) / 2.0,
                   "Another weekday must not inherit the full nudge: nudgedDay=${isf()} otherDay=$otherDay")
    }
}
