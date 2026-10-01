package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import app.aaps.core.keys.StringNonKey
import org.json.JSONObject
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

    private lateinit var sp: FakePreferences

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        learner = CircadianLearner(FakeAAPSLogger(collect = false), sp)
    }

    /** Today's buckets for hours 5-6 already pulled weaker than the weekly average (as a sustained
     *  low does), so everything that pulls back toward the average or across hours pushes UP. */
    private fun seedWeakenedMorning() {
        val values = DoubleArray(24) { 1.0 }.also { it[5] = 0.85; it[6] = 0.80 }
        val day = CircadianState(values, DoubleArray(24) { 0.9 })
        val state = DayOfWeekCircadianState(Array(7) { if (it == DOW) day else CircadianState(DoubleArray(24) { 1.0 }, DoubleArray(24) { 0.9 }) },
                                            CircadianState(DoubleArray(24) { 1.0 }, DoubleArray(24) { 0.9 }))
        val json = JSONObject().apply {
            put("isf", state.toJson()); put("basal", state.toJson()); put("aggr", DayOfWeekCircadianState().toJson())
        }
        sp.putString(StringNonKey.ApsSmartInsulinCircadianState.key, json.toString())
        learner = CircadianLearner(FakeAAPSLogger(collect = false), sp)
    }

    private fun glucoseStatus(bg: Double, delta: Double): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(bg)
            whenever(this.shortAvgDelta).thenReturn(delta)
            whenever(this.delta).thenReturn(delta)
            whenever(this.date).thenReturn(BASE_MS)
        }

    /** One quiet fasting cycle: no insulin acting, so any rise reads as a basal signal. */
    private fun cycle(bg: Double, delta: Double, t: Long, hour: Int = HOUR, minute: Int = 30) =
        learner.update(
            glucoseStatus  = glucoseStatus(bg, delta),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 0.0, activity = 0.0, basaliob = 0.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = TARGET,
            lowGuardMgdl   = 72.0,
            hour = hour, dow = DOW, minute = minute, nowMs = t
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

    @Test
    fun `a long dip across an hour boundary, then a rise above target, strengthens no hour`() {
        // Friday morning on a phone: ~4.8 from 05:00 to 06:30, then up to ~6.5 by 07:00. Today's
        // buckets were already weaker than the weekly average, so the pull back toward it pushed
        // hours 5 and 6 stronger while BG sat below target.
        seedWeakenedMorning()
        val isf = (4..7).associateWith { learner.isfMultiplier(it, DOW, 30) }
        val bas = (4..7).associateWith { learner.basalMultiplier(it, DOW, 30) }
        var t = BASE_MS
        var clock = 5 * 60   // minutes since midnight
        fun step(bg: Double, delta: Double) {
            cycle(bg, delta, t, hour = clock / 60, minute = clock % 60)
            t += CYCLE_MS; clock += 5
            for (h in 4..7) {
                assertTrue(learner.isfMultiplier(h, DOW, 30) <= isf.getValue(h) + 1e-9,
                           "at ${clock / 60}:${clock % 60} hour $h ISF got stronger: ${isf.getValue(h)} → ${learner.isfMultiplier(h, DOW, 30)}")
                assertTrue(learner.basalMultiplier(h, DOW, 30) <= bas.getValue(h) + 1e-9,
                           "at ${clock / 60}:${clock % 60} hour $h basal got stronger: ${bas.getValue(h)} → ${learner.basalMultiplier(h, DOW, 30)}")
            }
        }
        repeat(19) { step(86.0, 0.0) }                 // 05:00 - 06:30 below target
        var bg = 86.0
        repeat(12) { bg += 2.5; step(bg, 2.5) }        // 06:35 - 07:30 up to ~117
    }
}
