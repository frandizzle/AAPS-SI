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
 * Retroactive credit assignment for hard lows.
 *
 * The failure this addresses: BG rises around 05:00, the loop doses for it, the dose is too much,
 * and BG bottoms out at 07:30. The hard-low penalty wrote only to hour 7 — the hour the low
 * SURFACED in — while hours 5 and 6, which actually delivered the insulin, kept their multipliers
 * untouched and did the same thing again the next morning. Hour 7 meanwhile accumulated a
 * correction for a mistake it did not make.
 *
 * The fix charges the low back across the preceding hours by the insulin activity curve, gated on
 * evidence that those hours were fasting and had insulin working. These tests pin the split, the
 * gates, and the fact that the observed hour still takes its own full penalty.
 */
class CircadianRetroLowAttributionTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val DOW      = 3
    private val TARGET   = 100.0
    private val GUARD    = 80.0

    @BeforeEach
    fun setUp() {
        learner = CircadianLearner(logger, FakePreferences())
    }

    private fun glucoseStatus(glucose: Double, shortAvgDelta: Double = 0.0): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(glucose)
            whenever(this.shortAvgDelta).thenReturn(shortAvgDelta)
            whenever(this.delta).thenReturn(shortAvgDelta)
            whenever(this.date).thenReturn(BASE_MS)
        }

    /**
     * Ordinary in-range fasting cycles for one hour, with insulin working — builds both the
     * day-bucket confidence the blended read needs and the insulin-presence record the
     * attribution gates on.
     */
    private fun runHour(
        hour:     Int,
        startMs:  Long,
        count:    Int      = 12,
        iob:      Double   = 1.2,
        activity: Double   = 0.02,
        mode:     MealMode = MealMode.FASTING,
        dow:      Int      = DOW
    ): Long {
        var t = startMs
        repeat(count) {
            learner.update(
                glucoseStatus  = glucoseStatus(glucose = 110.0),
                iobArray       = arrayOf(IobTotal(time = 0, iob = iob, activity = activity, basaliob = iob)),
                mealMode       = mode,
                cobG           = 0.0,
                profileIsfMgdl = 50.0,
                targetMgdl     = TARGET,
                lowGuardMgdl   = GUARD,
                hour = hour, dow = dow, minute = 30, nowMs = t
            )
            t += CYCLE_MS
        }
        return t
    }

    /** One cycle with BG under the low guard — fires the hard-low penalty. */
    private fun hardLowAt(hour: Int, minute: Int, nowMs: Long, dow: Int = DOW) {
        learner.update(
            glucoseStatus  = glucoseStatus(glucose = 70.0, shortAvgDelta = -3.0),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 1.0, activity = 0.02, basaliob = 1.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = TARGET,
            lowGuardMgdl   = GUARD,
            hour = hour, dow = dow, minute = minute, nowMs = nowMs
        )
    }

    private fun isf(hour: Int, dow: Int = DOW)   = learner.isfMultiplier(hour, dow, minute = 30)
    private fun basal(hour: Int, dow: Int = DOW) = learner.basalMultiplier(hour, dow, minute = 30)

    @Test
    fun `a low is charged back to the hours that dosed the insulin, not only the hour it surfaced in`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t); t = runHour(7, t)

        val isf5 = isf(5); val isf6 = isf(6)
        val bas5 = basal(5); val bas6 = basal(6)

        hardLowAt(hour = 7, minute = 30, nowMs = t)

        assertTrue(isf(5) < isf5 - 1e-6, "hour 5 ISF should be cut: $isf5 -> ${isf(5)}")
        assertTrue(isf(6) < isf6 - 1e-6, "hour 6 ISF should be cut: $isf6 -> ${isf(6)}")
        assertTrue(basal(5) < bas5 - 1e-6, "hour 5 basal should be cut: $bas5 -> ${basal(5)}")
        assertTrue(basal(6) < bas6 - 1e-6, "hour 6 basal should be cut: $bas6 -> ${basal(6)}")
        assertTrue(learner.lastRetroAttribution.contains("h=5"), learner.lastRetroAttribution)
        assertTrue(learner.lastRetroAttribution.contains("h=6"), learner.lastRetroAttribution)
    }

    @Test
    fun `the hour nearest the insulin peak takes the largest share`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t); t = runHour(7, t)

        val before5 = isf(5); val before6 = isf(6)
        hardLowAt(hour = 7, minute = 30, nowMs = t)

        // At the default 90-min peak, a 07:30 low sits ~2h after the 05:xx doses and ~1h after
        // the 06:xx ones — hour 5 carries marginally more of the activity mass, so it takes the
        // full cut and hour 6 scales just under it.
        val drop5 = before5 - isf(5)
        val drop6 = before6 - isf(6)
        assertTrue(drop5 > 0.0 && drop6 > 0.0, "both hours should move: $drop5 / $drop6")
        assertTrue(drop5 >= drop6 - 1e-9, "hour 5 should take at least hour 6's share: $drop5 vs $drop6")
    }

    @Test
    fun `the hour the low surfaced in still takes its own full penalty`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t); t = runHour(7, t)
        val before7 = isf(7)
        hardLowAt(hour = 7, minute = 30, nowMs = t)
        assertTrue(isf(7) < before7 - 1e-6, "observed hour must keep its own penalty: $before7 -> ${isf(7)}")
    }

    @Test
    fun `an hour with no insulin on board is not charged`() {
        var t = BASE_MS
        // Hour 5 ran dry — the loop was zero-temping. It cannot have caused this low, whatever
        // its lag says.
        t = runHour(5, t, iob = 0.0, activity = 0.0)
        t = runHour(6, t)
        t = runHour(7, t)

        val before5 = isf(5); val beforeBas5 = basal(5); val before6 = isf(6)
        hardLowAt(hour = 7, minute = 30, nowMs = t)

        assertEquals(before5, isf(5), 1e-9, "hour 5 had no insulin working — must not be charged")
        assertEquals(beforeBas5, basal(5), 1e-9)
        assertTrue(isf(6) < before6 - 1e-6, "hour 6 did dose and must still be charged")
    }

    @Test
    fun `an hour spent in a meal mode is not charged to the fasting profile`() {
        var t = BASE_MS
        // Insulin was on board at hour 5, but it was meal insulin — that belongs to the meal, not
        // to the fasting hour's basal and ISF.
        t = runHour(5, t, mode = MealMode.LUNCH)
        t = runHour(6, t)
        t = runHour(7, t)

        val before5 = isf(5); val before6 = isf(6)
        hardLowAt(hour = 7, minute = 30, nowMs = t)

        assertEquals(before5, isf(5), 1e-9, "meal-mode hour must not be charged to the fasting profile")
        assertTrue(isf(6) < before6 - 1e-6)
    }

    @Test
    fun `with no recorded history nothing is charged back`() {
        // Fresh start: the ring holds nothing, so no earlier hour has evidence and the fix fails
        // closed rather than blaming the clock.
        val before5 = isf(5); val before6 = isf(6)
        hardLowAt(hour = 7, minute = 30, nowMs = BASE_MS)
        assertEquals(before5, isf(5), 1e-9)
        assertEquals(before6, isf(6), 1e-9)
        assertTrue(learner.lastRetroAttribution.contains("nothing charged back") ||
                       learner.lastRetroAttribution.contains("nothing to attribute back"),
                   learner.lastRetroAttribution)
    }

    @Test
    fun `charging back crosses midnight into the previous day`() {
        val prevDow = (DOW + 6) % 7
        var t = BASE_MS
        t = runHour(22, t, dow = prevDow)
        t = runHour(23, t, dow = prevDow)

        val before22 = isf(22, prevDow); val before23 = isf(23, prevDow)
        hardLowAt(hour = 0, minute = 30, nowMs = t, dow = DOW)

        assertTrue(isf(23, prevDow) < before23 - 1e-6, "hour 23 of the previous day should be charged")
        assertTrue(isf(22, prevDow) < before22 - 1e-6, "hour 22 of the previous day should be charged")
    }

    // ── Guard boundary and persistence ───────────────────────────────────────

    /** One cycle at an arbitrary BG, for driving the guard boundary. */
    private fun cycleAt(bgMgdl: Double, hour: Int, minute: Int, nowMs: Long) {
        learner.update(
            glucoseStatus  = glucoseStatus(glucose = bgMgdl, shortAvgDelta = -3.0),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 1.0, activity = 0.02, basaliob = 1.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = TARGET,
            lowGuardMgdl   = GUARD,
            hour = hour, dow = DOW, minute = minute, nowMs = nowMs
        )
    }

    @Test
    fun `a BG level with the guard is not a hard low`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t); t = runHour(7, t)
        val before5 = isf(5); val before6 = isf(6)

        // 79.6 vs an 80.0 guard — inside the display step, so not a low and nothing is charged.
        cycleAt(GUARD - 0.4, hour = 7, minute = 30, nowMs = t)

        assertEquals(before5, isf(5), 1e-9)
        assertEquals(before6, isf(6), 1e-9)
    }

    @Test
    fun `a BG a full step below the guard is still a hard low`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t); t = runHour(7, t)
        val before5 = isf(5)

        cycleAt(GUARD - 2.0, hour = 7, minute = 30, nowMs = t)

        assertTrue(isf(5) < before5 - 1e-6, "still a low, still charged back: $before5 -> ${isf(5)}")
    }

    @Test
    fun `the insulin-presence evidence survives a restart`() {
        val prefs = FakePreferences()
        var learnerA = CircadianLearner(logger, prefs)
        learner = learnerA
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t); t = runHour(7, t)

        // Same stored state, fresh instance — a phone reboot mid-morning.
        learner = CircadianLearner(logger, prefs)
        val before5 = isf(5); val before6 = isf(6)
        hardLowAt(hour = 7, minute = 30, nowMs = t)

        assertTrue(isf(5) < before5 - 1e-6, "hour 5 should still be chargeable after a restart")
        assertTrue(isf(6) < before6 - 1e-6, "hour 6 should still be chargeable after a restart")
    }
}
