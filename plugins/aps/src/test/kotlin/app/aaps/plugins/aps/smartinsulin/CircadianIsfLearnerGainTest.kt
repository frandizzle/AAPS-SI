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
 * Gain, admission floor and signal weighting on [CircadianLearner]'s ISF learner.
 *
 * normDeviation is a dimensionless ratio — "BG moved 60% more than the IOB predicted" — and it
 * used to be added straight onto a multiplier that lives in [0.7, 1.5], with no gain constant and
 * an admission floor of 1 mg/dL of expected movement. At that floor, ordinary shortAvgDelta noise
 * of ±1–2 mg/dL is the whole numerator, so normDeviation routinely pinned to its clamp from noise
 * alone and each such sample moved the multiplier by ~4%, twelve times an hour.
 *
 * Three changes, one test each below: an explicit ratio→offset gain, an admission floor high
 * enough that the denominator is a real insulin action, and a linear signal-strength weight
 * between the floor and full confidence.
 *
 * Direction, stated once: the multiplier and the delivered ISF are inverses. dosingISF =
 * profileISF / mult, so a multiplier moving DOWN produces a LARGER ISF number, which means less
 * insulin per unit of BG above target — the retreat direction.
 */
class CircadianIsfLearnerGainTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS = 1_700_000_000_000L
    private val CENTRE  = 30
    private val HOUR    = 8
    private val DOW     = 1
    private val ISF     = 50.0

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

    /** One cycle. expectedDelta = -activity * ISF * 5, so activity sets the signal strength. */
    private fun cycle(bg: Double, shortAvgDelta: Double, activity: Double, lowGuardMgdl: Double = 90.0) {
        learner.update(
            glucoseStatus  = glucoseStatus(bg, shortAvgDelta),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 1.0, activity = activity, basaliob = 0.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = ISF,
            targetMgdl     = 100.0,
            lowGuardMgdl   = lowGuardMgdl,
            aggressiveness = 1.0,
            hour = HOUR, dow = DOW, minute = CENTRE, nowMs = BASE_MS
        )
    }

    private fun isf() = learner.isfMultiplier(HOUR, DOW, CENTRE)

    @Test
    fun `a weak expected signal is not admitted at all`() {
        // activity 0.01 at ISF 50 -> expectedDelta = -2.5 mg/dL, under MIN_EXPECTED_DELTA_MGDL.
        // This is the regime that used to produce ±1.0 normDeviation out of pure sensor noise:
        // a 2 mg/dL noise excursion against a 2.5 mg/dL denominator.
        cycle(bg = 140.0, shortAvgDelta = -0.5, activity = 0.01)

        assertEquals(1.0, isf(), 1e-12,
                     "A sub-floor expected delta must not train the learner at all, got ${isf()}")
    }

    @Test
    fun `a strong expected signal is admitted`() {
        // activity 0.04 -> expectedDelta = -10 mg/dL, comfortably above the floor.
        cycle(bg = 140.0, shortAvgDelta = -2.0, activity = 0.04)

        assertTrue(abs(isf() - 1.0) > 1e-9, "A real insulin action should train the learner")
    }

    @Test
    fun `BG falling faster than IOB predicts moves the multiplier down`() {
        // expectedDelta = -10.0; actual -20.0 means insulin is working harder than modelled.
        // deviation = -10 -> normDeviation negative -> multiplier down -> dosingISF number UP ->
        // less insulin. This is the safety direction and the one the clamp allows furthest.
        cycle(bg = 140.0, shortAvgDelta = -20.0, activity = 0.04)

        assertTrue(isf() < 1.0,
                   "Faster-than-predicted fall must reduce the multiplier (raising delivered ISF), got ${isf()}")
    }

    @Test
    fun `BG falling slower than IOB predicts moves the multiplier up`() {
        // expectedDelta = -10.0; actual -2.0 means insulin looks weaker than modelled.
        // BG is above target so the below-target guard does not zero the positive direction.
        cycle(bg = 140.0, shortAvgDelta = -2.0, activity = 0.04)

        assertTrue(isf() > 1.0,
                   "Slower-than-predicted fall must raise the multiplier (lowering delivered ISF), got ${isf()}")
    }

    @Test
    fun `the up direction is blocked while BG is below target`() {
        // Same weak-looking insulin signal, but BG is under target. A flattening BG on a decaying
        // tail reads as "insulin weaker than expected"; acting on it while already low is exactly
        // backwards. Only the up direction is blocked — retreating stays available.
        //
        // lowGuardMgdl is dropped to 70 so this stays a pure below-target scenario: at the default
        // 90 guard, BG 95 also sits inside the soft-low approach band and BG 80 trips the hard-low
        // penalty, either of which writes the multiplier for reasons that have nothing to do with
        // the guard under test.
        cycle(bg = 95.0, shortAvgDelta = -2.0, activity = 0.04, lowGuardMgdl = 70.0)

        assertEquals(1.0, isf(), 1e-12,
                     "Increasing aggressiveness below target must be blocked, got ${isf()}")
    }

    @Test
    fun `a barely-admissible sample moves the multiplier less than a strong one`() {
        // Both samples produce the SAME normDeviation (clamped to its +0.5 ceiling), so any
        // difference in the resulting movement comes purely from the signal-strength weight.
        val weakLearner = CircadianLearner(logger, FakePreferences())
        val strongLearner = CircadianLearner(logger, FakePreferences())

        fun run(l: CircadianLearner, activity: Double) {
            // shortAvgDelta 0.0 against a falling expectation -> deviation = +|expectedDelta| ->
            // normDeviation = +1.0 before the clamp, so both land on the same clamped +0.5.
            l.update(
                glucoseStatus  = glucoseStatus(140.0, 0.0),
                iobArray       = arrayOf(IobTotal(time = 0, iob = 1.0, activity = activity, basaliob = 0.0)),
                mealMode       = MealMode.FASTING,
                cobG           = 0.0,
                profileIsfMgdl = ISF,
                targetMgdl     = 100.0,
                aggressiveness = 1.0,
                hour = HOUR, dow = DOW, minute = CENTRE, nowMs = BASE_MS
            )
        }

        run(weakLearner, activity = 0.021)   // expectedDelta ≈ -5.25, just over the floor
        run(strongLearner, activity = 0.060) // expectedDelta = -15.0, past full weight

        val weakMove   = weakLearner.isfMultiplier(HOUR, DOW, CENTRE) - 1.0
        val strongMove = strongLearner.isfMultiplier(HOUR, DOW, CENTRE) - 1.0

        assertTrue(weakMove > 0.0 && strongMove > 0.0, "precondition: both should move upward")
        assertTrue(weakMove < strongMove * 0.7,
                   "A barely-admissible sample must contribute proportionally less: " +
                       "weak=$weakMove strong=$strongMove")
    }

    @Test
    fun `a single strong sample cannot propose the whole multiplier range`() {
        // The clamped normDeviation ceiling is +0.5. Without a gain, one sample's proposed target
        // was current + 0.5 — half the entire [0.7, 1.5] range from a single reading. With the
        // gain it proposes a fifth of that, and the EWMA then applies a fraction of it.
        cycle(bg = 140.0, shortAvgDelta = 0.0, activity = 0.06)

        assertTrue(isf() - 1.0 < 0.02,
                   "One sample must not move the delivered multiplier more than a couple of percent, got ${isf()}")
    }
}
