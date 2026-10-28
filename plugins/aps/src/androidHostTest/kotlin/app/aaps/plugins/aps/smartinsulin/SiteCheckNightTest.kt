package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [SiteCheck]'s night check (00:00 to 06:00) and "This site was bad". Cycles are 5 minutes apart, from
 * 23:30 on a pod that is 30 h old, with a target of 5.5 mmol (99 mg/dL).
 */
class SiteCheckNightTest {

    private lateinit var sp: FakePreferences
    private lateinit var check: SiteCheck
    private var now = POD + 30 * HOUR
    private var minute = 23 * 60 + 30
    private var bg = 160.0

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        check = SiteCheck(sp)
    }

    private fun cycle(
        newBg: Double, insulinU: Double, mode: Boolean = false, quiet: Boolean = !mode, unreliable: Boolean = false, site: Long? = POD
    ) {
        val delta = newBg - bg
        bg = newBg
        now += STEP
        minute = (minute + 5) % (24 * 60)
        check.onCycle(
            now, bg, delta, 1.0, mode, if (mode) "Protein/Fat (UAM)" else "Fasting", site,
            if (unreliable) SiteCheck.DropReason.EXERCISE else null,
            minuteOfDay = minute, targetMgdl = TARGET, insulinAboveBasalU = insulinU, quietMode = quiet
        )
    }

    /**
     * From 23:30 to the first cycle after 06:00. BG goes in a straight line from [fromBg] to [toBg] over the
     * night, and the loop gives [totalU] above basal, spread evenly. Returns the judged night.
     */
    private fun night(fromBg: Double, toBg: Double, totalU: Double, block: (Int) -> Unit = {}): SiteCheck.Night? {
        repeat(6) { cycle(fromBg, 0.0) }                // 23:30 to 00:00
        val steps = 72                                    // 00:05 to 06:00
        for (i in 1..steps) {
            block(i)
            cycle(fromBg + (toBg - fromBg) * i / steps, totalU / steps)
        }
        return check.takeNight()
    }

    @Test
    fun `last night's bleeding site is suspect and asks at once`() {
        // 8.9 → 8.0 mmol with 6.1 U above basal, high all night.
        val n = night(160.0, 144.0, 6.1)
        assertNotNull(n)
        assertTrue(n!!.suspect)
        assertTrue(n.notify)
        assertEquals(0.15, n.fallPerUnitMmol!!, 0.02)
        assertEquals(1.0, n.highShare, 1e-9)
        assertEquals(1, check.status(now).suspects)
    }

    @Test
    fun `a night where the insulin worked is normal`() {
        // 8.9 → 5.6 mmol with 2.5 U above basal.
        val n = night(160.0, 100.0, 2.5)!!
        assertFalse(n.suspect)
        assertFalse(n.notify)
        assertEquals(0, check.status(now).suspects)
    }

    @Test
    fun `little insulin above basal is not suspect, even when BG stays high`() {
        assertFalse(night(160.0, 160.0, 3.0)!!.suspect)
    }

    @Test
    fun `a night that is high for less than half of it is not suspect`() {
        // 7.5 mmol until 02:25, then 6.1: 5 U above basal for a fall of 0.28 mmol per unit, but high only 40 %.
        repeat(6) { cycle(135.0, 0.0) }
        repeat(29) { cycle(135.0, 5.0 / 72) }
        repeat(43) { cycle(110.0, 5.0 / 72) }
        val n = check.takeNight()!!
        assertTrue(n.fallPerUnitMmol!! <= SiteCheck.NIGHT_FALL_PER_U_MAX, "${n.fallPerUnitMmol}")
        assertTrue(n.highShare < SiteCheck.NIGHT_HIGH_SHARE, "${n.highShare}")
        assertFalse(n.suspect)
    }

    @Test
    fun `the night is judged at 06_00 and listed on the card`() {
        repeat(6) { cycle(160.0, 0.0) }
        repeat(71) { cycle(155.0, 0.1) }                   // to 05:55
        assertNull(check.takeNight())
        assertNotNull(check.status(now).liveNight)
        cycle(155.0, 0.1)                                   // 06:00
        assertNotNull(check.takeNight())
        assertNull(check.status(now).liveNight)
        assertEquals(1, check.status(now).nights.size)
    }

    @Test
    fun `a night is not started after 00_15`() {
        minute = 25                                         // the first cycle is at 00:30
        repeat(80) { cycle(160.0, 0.1) }
        assertNull(check.takeNight())
        assertNull(check.status(now).liveNight)
    }

    @Test
    fun `P-F mode may run in the night`() {
        repeat(6) { cycle(160.0, 0.0) }
        repeat(72) { cycle(150.0, 0.1, mode = true, quiet = true) }
        assertNotNull(check.takeNight())
    }

    @Test
    fun `another meal mode in the night drops it and says why`() {
        var dropped: SiteCheck.Dropped? = null
        val n = night(160.0, 144.0, 6.1) { i ->
            if (i == 30) {
                cycle(150.0, 0.0, mode = true, quiet = false)
                dropped = check.status(now).lastDropped
            }
        }
        assertNull(n)
        assertEquals(SiteCheck.DropReason.MEAL_AT_NIGHT, dropped?.reason)
        assertEquals(SiteCheck.NIGHT_LABEL, dropped?.mode)
    }

    @Test
    fun `exercise in the night drops it`() {
        assertNull(night(160.0, 144.0, 6.1) { i -> if (i == 30) cycle(150.0, 0.0, unreliable = true) })
        assertEquals(SiteCheck.DropReason.EXERCISE, check.status(now).lastDropped?.reason)
    }

    @Test
    fun `a gap in the data drops the night`() {
        assertNull(night(160.0, 144.0, 6.1) { i -> if (i == 30) now += 30 * MIN })
        assertEquals(SiteCheck.DropReason.DATA_GAP, check.status(now).lastDropped?.reason)
    }

    @Test
    fun `no night on a pod younger than 12 h`() {
        now = POD + 2 * HOUR
        assertNull(night(160.0, 144.0, 6.1))
    }

    @Test
    fun `a restart in the night does not lose it`() {
        val n = night(160.0, 144.0, 6.1) { i -> if (i == 30) check = SiteCheck(sp) }
        assertNotNull(n)
        assertTrue(n!!.suspect)
        assertEquals(6.1, n.insulinAboveBasalU, 1e-6)
    }

    @Test
    fun `nights survive a restart and a new pod clears them`() {
        night(160.0, 144.0, 6.1)
        assertEquals(1, SiteCheck(sp).status(now).nights.size)
        cycle(150.0, 0.0, site = now)
        assertEquals(0, check.status(now).nights.size)
    }

    @Test
    fun `a suspect night is not asked about after site is fine`() {
        cycle(160.0, 0.0)
        check.markSiteFine()
        val n = night(160.0, 144.0, 6.1)!!
        assertTrue(n.suspect)
        assertFalse(n.notify)
    }

    // --- This site was bad ---

    @Test
    fun `a bad site is not asked about any more`() {
        cycle(160.0, 0.0)
        assertTrue(check.markSiteBad(POD))
        assertTrue(check.status(now).bad)
        val n = night(160.0, 144.0, 6.1)!!
        assertTrue(n.suspect)
        assertFalse(n.notify)
    }

    @Test
    fun `bad survives a restart and a new pod clears it`() {
        cycle(160.0, 0.0)
        check.markSiteBad(POD)
        assertTrue(SiteCheck(sp).status(now).bad)
        cycle(150.0, 0.0, site = now)
        assertFalse(check.status(now).bad)
        assertEquals(POD, check.status(now).previousPodMs)
    }

    @Test
    fun `a bad pod's meals do not join the baseline`() {
        assertTrue(check.importBaseline(BASELINE))
        meal()
        check.markSiteBad(POD)
        cycle(110.0, 0.0, site = now)
        assertEquals(30, check.status(now).baselineMeals)
        assertEquals(3, check.status(now).baselinePods)
    }

    @Test
    fun `the last pod's meals are taken out of the baseline again`() {
        assertTrue(check.importBaseline(BASELINE))
        meal()
        val newPod = now + STEP
        cycle(110.0, 0.0, site = newPod)
        assertEquals(31, check.status(now).baselineMeals)
        assertEquals(4, check.status(now).baselinePods)
        assertTrue(check.markSiteBad(POD))
        assertEquals(30, SiteCheck(sp).status(now).baselineMeals)
        assertEquals(3, check.status(now).baselinePods)
        // Twice changes nothing more.
        check.markSiteBad(POD)
        assertEquals(3, check.status(now).baselinePods)
    }

    @Test
    fun `an older pod cannot be marked`() {
        assertFalse(check.markSiteBad(POD - 3 * 24 * HOUR))
        assertFalse(check.markSiteBad(0L))
    }

    /** One normal meal on [POD], judged. */
    private fun meal() {
        var b = 110.0
        var iob = 0.5
        fun step(newBg: Double, newIob: Double, meal: Boolean = true): SiteCheck.Episode? {
            val delta = newBg - b
            b = newBg
            iob = newIob
            now += STEP
            return check.onCycle(now, b, delta, iob, meal, "Lunch (UAM)", POD, null)
        }
        repeat(6) { step(110.0, 0.5, meal = false) }
        repeat(9) { i -> step(110.0 + 8.0 * (i + 1), 0.5 + 0.6 * (i + 1)) }
        repeat(30) { step(b - 2.5, iob) ?: return@repeat; return }
        error("meal was not judged")
    }

    private companion object {

        val BASELINE = """{"pods": 3, "meals": [${(List(10) { "10.0" to "0.40" } + List(10) { "25.0" to "0.54" } + List(10) { "40.0" to "0.68" })
            .joinToString(",") { (r, f) -> """{"r": $r, "f": $f}""" }}]}"""

        const val TARGET = 99.0
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val STEP = 5 * MIN
        const val POD = 1_790_000_000_000L
    }
}
