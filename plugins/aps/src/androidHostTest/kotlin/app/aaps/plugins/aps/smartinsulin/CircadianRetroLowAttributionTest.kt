package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        dow:      Int      = DOW,
        mealInsulin: Boolean = false
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
                mealInsulinDominant = mealInsulin,
                hour = hour, dow = dow, minute = 30, nowMs = t
            )
            t += CYCLE_MS
        }
        return t
    }

    /** One cycle with BG under the low guard — fires the hard-low penalty. */
    private fun hardLowAt(hour: Int, minute: Int, nowMs: Long, dow: Int = DOW, mealInsulin: Boolean = false) {
        learner.update(
            glucoseStatus  = glucoseStatus(glucose = 70.0, shortAvgDelta = -3.0),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 1.0, activity = 0.02, basaliob = 1.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = TARGET,
            lowGuardMgdl   = GUARD,
            mealInsulinDominant = mealInsulin,
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

    // ── a low from a meal mode's insulin is not the fasting hours' ───────────

    @Test
    fun `a low from a meal mode's insulin does not cut the fasting hour or charge earlier hours`() {
        // 4 Oct: P/F Night + DURA ended 22:52, learning back at 23:37, BG under the guard just after.
        var t = BASE_MS
        t = runHour(21, t); t = runHour(22, t); t = runHour(23, t, count = 6)
        val before = (21..23).associateWith { isf(it) to basal(it) }

        hardLowAt(hour = 23, minute = 45, nowMs = t, mealInsulin = true)

        // The penalty is a 10% cut; ordinary learning on the low cycle itself can move a hair.
        for (h in 21..23) {
            assertEquals(before.getValue(h).first, isf(h), before.getValue(h).first * 0.01, "hour $h ISF")
            assertEquals(before.getValue(h).second, basal(h), before.getValue(h).second * 0.01, "hour $h basal")
        }
        assertTrue(learner.lastRetroAttribution.contains("meal mode's insulin"), learner.lastRetroAttribution)
    }

    @Test
    fun `the same low from fasting insulin is still charged as before`() {
        var t = BASE_MS
        t = runHour(21, t); t = runHour(22, t); t = runHour(23, t, count = 6)
        val isf23 = isf(23)
        hardLowAt(hour = 23, minute = 45, nowMs = t, mealInsulin = false)
        assertTrue(isf(23) < isf23 - 1e-6, "hour 23 ISF should be cut: $isf23 -> ${isf(23)}")
    }

    @Test
    fun `insulin a meal mode left behind is not fasting insulin for a later charge-back`() {
        // Hour 22 only carried the meal's insulin; hour 23 was genuinely fasting. A later low
        // (no longer the meal's) charges hour 23 but leaves hour 22 alone.
        var t = BASE_MS
        t = runHour(22, t, mealInsulin = true)
        t = runHour(23, t)
        t = runHour(0, t, dow = (DOW + 1) % 7, count = 3)
        val isf22 = isf(22)
        hardLowAt(hour = 0, minute = 15, nowMs = t, dow = (DOW + 1) % 7)
        assertEquals(isf22, isf(22), 1e-9, "hour 22 carried only meal insulin")
        assertFalse(learner.lastRetroAttribution.contains("h=22"), learner.lastRetroAttribution)
    }

    // -- A rollercoaster that ends in a low --------------------------------------------------------

    /** One fasting cycle at [glucose] in hour 7, with insulin working. */
    private fun cycleAt(glucose: Double, minute: Int, nowMs: Long) {
        learner.update(
            glucoseStatus  = glucoseStatus(glucose = glucose),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 1.0, activity = 0.02, basaliob = 1.0)),
            mealMode       = MealMode.FASTING,
            cobG           = 0.0,
            profileIsfMgdl = 50.0,
            targetMgdl     = TARGET,
            lowGuardMgdl   = GUARD,
            hour = 7, dow = DOW, minute = minute, nowMs = nowMs
        )
    }

    /**
     * Low, then well over target, then back down: the second low comes 50 min after the first.
     * Under 45 min with nothing after it, the swing would pass as a sensor compression low.
     */
    private fun rollercoasterIntoLow(startMs: Long, peak: Double = 130.0): Long {
        var t = startMs
        hardLowAt(hour = 7, minute = 0, nowMs = t)
        for (m in listOf(5, 10, 15, 20)) { t += CYCLE_MS; cycleAt(100.0, m, t) }
        for (m in listOf(25, 30)) { t += CYCLE_MS; cycleAt(peak, m, t) }
        for (m in listOf(35, 40, 45)) { t += CYCLE_MS; cycleAt(100.0, m, t) }
        t += CYCLE_MS
        // The 30 min hard-low gate reads the wall clock, not nowMs: clear it so the second low
        // counts as a new event, as it would 50 min later in real life.
        learner.lastHardLowPenaltyMs = 0L
        return t
    }

    @Test
    fun `a rollercoaster that ends in a low still charges the low back to the hours that dosed it`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t)
        t = rollercoasterIntoLow(t)
        val isf5 = isf(5); val bas5 = basal(5); val isf7 = isf(7)

        hardLowAt(hour = 7, minute = 50, nowMs = t)

        assertEquals(1, learner.consecutiveRollercoasters, "the swing must be seen as a rollercoaster")
        assertTrue(isf(5) < isf5 - 1e-6, "hour 5 ISF should be cut: $isf5 -> ${isf(5)}")
        assertTrue(basal(5) < bas5 - 1e-6, "hour 5 basal should be cut: $bas5 -> ${basal(5)}")
        assertTrue(isf(7) < isf7 - 1e-6, "hour 7 takes its own hard-low cut: $isf7 -> ${isf(7)}")
    }

    @Test
    fun `the hard-low ceiling cut lands on top of the rollercoaster cut, not instead of it`() {
        // The same day twice: once with a swing well over target, once staying under it. Both end
        // in the same low. The rollercoaster day must end with the lower ceiling: its own cut plus
        // the hard-low cut. With the hard-low cut worked out from the ceiling read before the
        // rollercoaster cut, the two days came out the same.
        fun ceilingAfterLow(peak: Double): Double {
            learner = CircadianLearner(logger, FakePreferences())
            var t = BASE_MS
            t = runHour(5, t); t = runHour(6, t)
            t = rollercoasterIntoLow(t, peak)
            hardLowAt(hour = 7, minute = 50, nowMs = t)
            return learner.aggrCeiling(hour = 7, dow = DOW, minute = 50)
        }
        val plainLow = ceilingAfterLow(peak = 110.0)
        assertEquals(0, learner.consecutiveRollercoasters, "110 is under target + 18: no rollercoaster")
        val rollercoaster = ceilingAfterLow(peak = 130.0)
        assertEquals(1, learner.consecutiveRollercoasters)
        assertTrue(rollercoaster < plainLow - 0.02, "both cuts should apply: plain low $plainLow, rollercoaster $rollercoaster")
    }

    @Test
    fun `a rollercoaster that ends high gets no hard-low penalty`() {
        var t = BASE_MS
        t = runHour(5, t); t = runHour(6, t)
        cycleAt(130.0, 0, t)
        for (m in listOf(5, 10)) { t += CYCLE_MS; cycleAt(100.0, m, t) }
        t += CYCLE_MS; hardLowAt(hour = 7, minute = 15, nowMs = t)
        for (m in listOf(20, 25, 30, 35)) { t += CYCLE_MS; cycleAt(100.0, m, t) }
        val isf5 = isf(5); val bas5 = basal(5)

        t += CYCLE_MS
        cycleAt(130.0, 40, t)

        assertTrue(learner.consecutiveRollercoasters >= 1, "high, low, high is a rollercoaster ending high")
        assertEquals(isf5, isf(5), 1e-9, "nothing new charged back while BG is high")
        assertEquals(bas5, basal(5), 1e-9, "nothing new charged back while BG is high")
    }
}
