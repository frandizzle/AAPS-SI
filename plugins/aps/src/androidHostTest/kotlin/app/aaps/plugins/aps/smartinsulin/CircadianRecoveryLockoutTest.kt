package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * After BG dips below target, the climb back up must not make ISF or basal stronger.
 *
 * The guards used to check only the current reading. Once BG was back above target, the drift
 * signal and PredTrim still looked back over the dip and read the climb as "basal too weak".
 * Seen on a phone: 4.6 at 14:00 against a 5.5 target, zero temp, back to 6.0 by 15:00, and hour 14
 * got ISF ×0.777→0.788 and basal ×0.634→0.646.
 */
class CircadianRecoveryLockoutTest {

    private lateinit var learner: CircadianLearner
    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val DOW      = 3
    private val HOUR     = 14
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

    /** One quiet fasting cycle: no insulin acting, so any rise reads as a basal signal. */
    private fun cycle(bg: Double, delta: Double, t: Long) =
        learner.update(
            glucoseStatus  = glucoseStatus(bg, delta),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 0.0, activity = 0.0, basaliob = 0.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = TARGET,
            lowGuardMgdl   = 72.0,
            hour = HOUR, dow = DOW, minute = 30, nowMs = t
        )

    /** Rise from [fromBg] at [deltaPer5] for [cycles] cycles, starting at [startMs]. */
    private fun rise(fromBg: Double, deltaPer5: Double, cycles: Int, startMs: Long): Long {
        var t = startMs
        var bg = fromBg
        repeat(cycles) {
            cycle(bg, deltaPer5, t)
            bg += deltaPer5
            t += CYCLE_MS
        }
        return t
    }

    @Test
    fun `the climb back from a dip below target does not strengthen ISF or basal`() {
        val isfBefore   = learner.isfMultiplier(HOUR, DOW, 30)
        val basalBefore = learner.basalMultiplier(HOUR, DOW, 30)
        // 30 min flat at 4.6 (83 mg/dL), then back up to ~6.1 (110) over 45 min.
        var t = BASE_MS
        repeat(6) { cycle(83.0, 0.0, t); t += CYCLE_MS }
        rise(fromBg = 83.0, deltaPer5 = 3.0, cycles = 10, startMs = t)

        assertTrue(learner.isfMultiplier(HOUR, DOW, 30) <= isfBefore + 1e-9,
                   "ISF got stronger: $isfBefore → ${learner.isfMultiplier(HOUR, DOW, 30)}")
        assertTrue(learner.basalMultiplier(HOUR, DOW, 30) <= basalBefore + 1e-9,
                   "basal got stronger: $basalBefore → ${learner.basalMultiplier(HOUR, DOW, 30)}")
    }

    @Test
    fun `a rise above target with no recent dip can still strengthen basal`() {
        val basalBefore = learner.basalMultiplier(HOUR, DOW, 30)
        // Flat at target for an hour, then a slow climb that stays above target.
        var t = BASE_MS
        repeat(12) { cycle(TARGET, 0.0, t); t += CYCLE_MS }
        rise(fromBg = TARGET + 2.0, deltaPer5 = 3.0, cycles = 12, startMs = t)

        assertTrue(learner.basalMultiplier(HOUR, DOW, 30) > basalBefore,
                   "basal did not move: $basalBefore → ${learner.basalMultiplier(HOUR, DOW, 30)}")
    }
}
