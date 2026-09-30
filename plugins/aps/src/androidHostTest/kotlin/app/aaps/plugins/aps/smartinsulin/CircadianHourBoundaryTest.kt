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
 * Hour-boundary continuity for the 24-bucket circadian state.
 *
 * The failure this addresses: learning earned late in an hour used to live entirely in that
 * hour's bucket and be dropped the instant the clock ticked over. A correction that reduced
 * insulin at 17:50 because BG was heading low was fully in force at 17:59 and completely gone at
 * 18:00, handing the loop back an uninformed — and by comparison far more aggressive —
 * multiplier in the middle of the episode that produced the correction.
 *
 * Two mechanisms fix it, and both are tested here:
 *  - reads interpolate between adjacent buckets by minute-of-hour, and
 *  - writes bleed into the adjacent bucket with the same weight.
 */
class CircadianHourBoundaryTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val DOW      = 4

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

    /** Quiet at-target cycles with a depressed ceiling: the aggression nudge is the only writer. */
    private fun nudgeAt(hour: Int, minute: Int, count: Int, startMs: Long): Long {
        var t = startMs
        repeat(count) {
            learner.update(
                glucoseStatus  = glucoseStatus(glucose = 100.0, shortAvgDelta = 0.0),
                iobArray       = arrayOf(IobTotal(time = 0, iob = 0.0, activity = 0.0, basaliob = 0.0)),
                mealMode       = MealMode.FASTING,
                cobG           = 0.0,
                profileIsfMgdl = 50.0,
                targetMgdl     = 100.0,
                aggressiveness = 0.85,
                hour = hour, dow = DOW, minute = minute, nowMs = t
            )
            t += CYCLE_MS
        }
        return t
    }

    @Test
    fun `crossing an hour boundary does not step the multiplier`() {
        // An episode running through the last ten minutes of hour 17.
        nudgeAt(hour = 17, minute = 55, count = 300, startMs = BASE_MS)

        val lastMinuteOf17 = learner.isfMultiplier(17, DOW, 59)
        val firstMinuteOf18 = learner.isfMultiplier(18, DOW, 0)

        // One minute of wall clock may only move the effective multiplier by a sliver. Before
        // interpolation this pair was v17 vs v18 — the entire learned correction, discarded in
        // one tick.
        val jump = abs(firstMinuteOf18 - lastMinuteOf17)
        assertTrue(jump < 0.005,
                   "Multiplier must be continuous across the hour boundary: 17:59=$lastMinuteOf17 " +
                       "18:00=$firstMinuteOf18 jump=$jump")
    }

    @Test
    fun `learning late in an hour is already partly in force in the next hour`() {
        nudgeAt(hour = 17, minute = 55, count = 300, startMs = BASE_MS)

        // Bucket 18 read at its own centre: it never had a cycle of its own, so anything below
        // 1.0 here came from the neighbour bleed on the write side.
        val bucket18 = learner.isfMultiplier(18, DOW, 30)
        val bucket17 = learner.isfMultiplier(17, DOW, 30)

        assertTrue(bucket18 < 0.999,
                   "Hour 18 should have inherited part of hour 17's late-hour learning, got $bucket18")
        assertTrue(bucket18 > bucket17,
                   "The neighbour bleed must be a minority contribution, not a copy: " +
                       "h17=$bucket17 h18=$bucket18")
    }

    @Test
    fun `an episode early in an hour bleeds backwards into the previous hour`() {
        nudgeAt(hour = 9, minute = 5, count = 300, startMs = BASE_MS)

        val previous = learner.isfMultiplier(8, DOW, 30)
        assertTrue(previous < 0.999,
                   "Minutes before :30 lean on the PREVIOUS hour, so hour 8 should have moved too, got $previous")
    }

    @Test
    fun `midnight wraps — hour 23 leans into hour 0`() {
        nudgeAt(hour = 23, minute = 55, count = 300, startMs = BASE_MS)

        val hour0 = learner.isfMultiplier(0, DOW, 30)
        assertTrue(hour0 < 0.999,
                   "Bucket 0 is hour 23's forward neighbour across midnight, got $hour0")
    }

    @Test
    fun `an hour's own centre is unaffected by its neighbours`() {
        // Nothing has been written anywhere, so every bucket reads its default.
        assertEquals(1.0, learner.isfMultiplier(12, DOW, 30), 1e-12,
                     "Minute 30 is the bucket centre — zero neighbour weight")
    }

    @Test
    fun `mid-hour reads sit between the two adjacent buckets`() {
        nudgeAt(hour = 6, minute = 30, count = 400, startMs = BASE_MS)

        val h6 = learner.isfMultiplier(6, DOW, 30)
        val h7 = learner.isfMultiplier(7, DOW, 30)
        val boundary = learner.isfMultiplier(6, DOW, 59)

        assertTrue(h6 < boundary && boundary < h7,
                   "A read near the boundary must interpolate between the two buckets: " +
                       "h6=$h6 boundary=$boundary h7=$h7")
    }
}
