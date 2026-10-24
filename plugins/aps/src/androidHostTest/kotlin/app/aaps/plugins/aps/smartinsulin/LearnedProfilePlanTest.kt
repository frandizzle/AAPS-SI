package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.data.Block
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Turning the all-days learning into ISF and basal blocks for a new profile. */
class LearnedProfilePlanTest {

    // Profile ISF 2.0 mmol/U (36 mg/dL) and basal 1.1 U/h all day, as on the phone.
    private fun hours(
        isfMult: (Int) -> Double = { 0.7 },
        basalMult: (Int) -> Double = { 0.55 },
        conf: (Int) -> Double = { 1.0 }
    ) = (0 until 24).map { h ->
        LearnedProfilePlan.Hour(h, 36.0, 1.1, 36.0 / isfMult(h), 1.1 * basalMult(h), conf(h), conf(h))
    }

    private fun plan(hours: List<LearnedProfilePlan.Hour>) = LearnedProfilePlan.build(hours, isfStepMgdl = 1.8, basalStep = 0.05, basalMin = 0.05)

    @Test
    fun `a flat learning gives one block each, rounded to the steps`() {
        val p = plan(hours())
        assertEquals(1, p.isfSegments.size)
        assertEquals(1, p.basalSegments.size)
        // 36 / 0.7 = 51.4 mg/dL = 2.86 mmol, rounded to 0.1 mmol = 2.9 mmol.
        assertEquals(2.9, p.isfBlocks(mgdlProfile = false).single().amount, 1e-9)
        // 1.1 * 0.55 = 0.605, rounded to the 0.05 step.
        assertEquals(0.6, p.basalBlocks().single().amount, 1e-9)
        assertEquals(24 * 3_600_000L, p.basalBlocks().single().duration)
    }

    @Test
    fun `hours without enough data keep the profile value`() {
        val p = plan(hours(conf = { if (it < 6) 0.3 else 1.0 }))
        assertEquals(36.0, p.newIsfMgdl[0], 1e-9)
        assertEquals(1.1, p.newBasal[5], 1e-9)
        assertEquals(0.6, p.newBasal[6], 1e-9)
        assertEquals(18, p.isfHoursLearned)
        assertEquals(LearnedProfilePlan.Segment(0, 6, 1.1), p.basalSegments.first())
    }

    @Test
    fun `close hours merge and far hours do not`() {
        // 0-7 h: 1.0, 8-15 h: 1.04 (within 10 %), 16-23 h: 1.5 (far).
        val segs = LearnedProfilePlan.merge(DoubleArray(24) { h -> if (h < 8) 1.0 else if (h < 16) 1.04 else 1.5 }) { it }
        assertEquals(2, segs.size)
        assertEquals(0 to 16, segs[0].startHour to segs[0].hours)
        assertEquals(1.02, segs[0].value, 1e-9)
        assertEquals(16 to 8, segs[1].startHour to segs[1].hours)
        assertEquals(1.5, segs[1].value, 1e-9)
    }

    @Test
    fun `blocks that round to the same value are joined`() {
        val segs = LearnedProfilePlan.merge(DoubleArray(24) { h -> if (h < 12) 0.60 else 0.70 }) { 0.65 }
        assertEquals(listOf(LearnedProfilePlan.Segment(0, 24, 0.65)), segs)
    }

    @Test
    fun `basal never goes under the pump minimum`() {
        val p = LearnedProfilePlan.build(hours(basalMult = { 0.01 }), isfStepMgdl = 1.8, basalStep = 0.05, basalMin = 0.05)
        assertEquals(0.05, p.newBasal.min(), 1e-9)
    }

    @Test
    fun `daily totals and averages`() {
        val p = plan(hours())
        assertEquals(26.4, p.profileDailyBasal, 1e-9)
        assertEquals(14.4, p.newDailyBasal, 1e-9)
        assertEquals(36.0, p.profileAverageIsfMgdl, 1e-9)
        assertEquals(2.9 * 18.0, p.newAverageIsfMgdl, 1e-6)
    }

    @Test
    fun `full confidence needs every hour at 100 percent`() {
        assertTrue(plan(hours(conf = { 0.999 })).fullConfidence)
        assertFalse(plan(hours(conf = { if (it == 3) 0.98 else 1.0 })).fullConfidence)
    }

    @Test
    fun `largest change is the biggest move of any hour`() {
        assertEquals(0.4545, plan(hours()).largestChange, 1e-3)              // basal 1.1 → 0.6
        assertEquals(0.0, plan(hours(isfMult = { 1.0 }, basalMult = { 1.0 })).largestChange, 1e-9)
    }

    @Test
    fun `mg per dL profile gets mg per dL blocks`() {
        val p = LearnedProfilePlan.build(hours(), isfStepMgdl = 1.0, basalStep = 0.05, basalMin = 0.05)
        assertEquals(51.0, p.isfBlocks(mgdlProfile = true).single().amount, 1e-9)
    }

    @Test
    fun `hourly reads each block at the centre of the hour`() {
        val blocks = listOf(Block(2 * 3_600_000L, 2.0), Block(22 * 3_600_000L, 3.0))
        val h = LearnedProfilePlan.hourly(blocks, toMgdl = 18.0)
        assertEquals(36.0, h[0], 1e-9)
        assertEquals(36.0, h[1], 1e-9)
        assertEquals(54.0, h[2], 1e-9)
        assertEquals(54.0, h[23], 1e-9)
    }

    @Test
    fun `same schedule allows half a percent`() {
        val a = DoubleArray(24) { 36.0 }
        assertTrue(LearnedProfilePlan.sameSchedule(a, DoubleArray(24) { 36.1 }))
        assertFalse(LearnedProfilePlan.sameSchedule(a, DoubleArray(24) { if (it == 5) 37.0 else 36.0 }))
        assertTrue(LearnedProfilePlan.sameSchedule(DoubleArray(24), DoubleArray(24)))
    }

    @Test
    fun `new hourly arrays match the segments`() {
        val p = plan(hours(isfMult = { if (it < 12) 0.7 else 1.0 }))
        assertArrayEquals(DoubleArray(12) { 2.9 * 18.0 }, p.newIsfMgdl.copyOfRange(0, 12), 1e-6)
        assertArrayEquals(DoubleArray(12) { 36.0 }, p.newIsfMgdl.copyOfRange(12, 24), 1e-6)
    }
}
