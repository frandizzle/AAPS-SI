package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A low after a mode ends is charged by WHOSE insulin caused it, not by how long ago the mode was.
 *
 * The two cases that used to be indistinguishable:
 *   - mode leaves a lot on board, loop gives almost nothing, low lands late  → the mode's
 *   - mode ends, loop doses hard for an hour, that takes BG low              → the loop's
 */
class ModeInsulinShareTest {

    private lateinit var sp: FakePreferences
    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() { sp = FakePreferences() }

    // ── The share itself ─────────────────────────────────────────────────────

    @Test
    fun `mode left plenty and the loop gave almost nothing`() {
        assertEquals(0.94, ModeInsulinShare.share(3.0, 0.2), 0.01)
        assertTrue(ModeInsulinShare.chargeable(ModeInsulinShare.share(3.0, 0.2)))
    }

    @Test
    fun `the loop gave most of it`() {
        assertEquals(0.33, ModeInsulinShare.share(1.0, 2.0), 0.01)
        assertTrue(ModeInsulinShare.chargeable(ModeInsulinShare.share(1.0, 2.0)))
    }

    @Test
    fun `the loop gave nearly all of it — the mode is not charged`() {
        val share = ModeInsulinShare.share(0.3, 3.0)
        assertTrue(share < ModeInsulinShare.MIN_SHARE_TO_CHARGE, "share was $share")
    }

    @Test
    fun `no insulin anywhere is not chargeable`() {
        assertEquals(0.0, ModeInsulinShare.share(0.0, 0.0), 1e-9)
        assertTrue(!ModeInsulinShare.chargeable(0.0))
    }

    // ── What the learners do with it ─────────────────────────────────────────

    private fun modeIsf() = ModeIsfLearner(sp, FakeAAPSLogger(collect = false))

    /** Mode runs, ends, then a low lands [lowAfterMin] later with the given share. */
    private fun lowAfterMode(l: ModeIsfLearner, share: Double, lowAfterMin: Int, watchMs: Long = 180 * 60_000L) {
        l.onCycle(MealMode.LUNCH, BASE_MS, 150.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, BASE_MS,
                  modeInsulinShare = share, watchMs = watchMs)
        val endMs = BASE_MS + CYCLE_MS
        l.onCycle(null, 0L, 140.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, endMs,
                  modeInsulinShare = share, watchMs = watchMs)
        l.onCycle(null, 0L, 70.0, 100.0, true, 1.0, 0.0, 0.0, 50.0, 10.0,
                  endMs + lowAfterMin * 60_000L, modeInsulinShare = share, watchMs = watchMs)
    }

    @Test
    fun `a low two hours later is now learned from when the insulin was the mode's`() {
        // Past the old 105-minute watch entirely.
        val l = modeIsf()
        lowAfterMode(l, share = 0.94, lowAfterMin = 120)
        assertTrue(l.multiplier(MealMode.LUNCH) > 1.0, "should have weakened, got ${l.multiplier(MealMode.LUNCH)}")
        assertTrue(l.lastOutcome.contains("94% of the insulin"), l.lastOutcome)
    }

    @Test
    fun `the same low is charged in proportion when the loop gave most of the insulin`() {
        val mostlyMode = modeIsf().also { lowAfterMode(it, share = 0.94, lowAfterMin = 60) }
            .multiplier(MealMode.LUNCH)
        sp = FakePreferences()
        val mostlyLoop = modeIsf().also { lowAfterMode(it, share = 0.33, lowAfterMin = 60) }
            .multiplier(MealMode.LUNCH)
        assertTrue(mostlyLoop < mostlyMode,
                   "a third of the blame should weaken less: $mostlyLoop vs $mostlyMode")
        assertTrue(mostlyLoop > 1.0, "it still owns a third of it")
    }

    @Test
    fun `a low that was almost entirely the loop's own insulin does not touch the mode`() {
        val l = modeIsf()
        lowAfterMode(l, share = 0.09, lowAfterMin = 60)
        // The mode ended at 140, so it was strengthened then; the low leaves that alone.
        assertEquals(0.975, l.multiplier(MealMode.LUNCH), 1e-9)
        assertTrue(l.lastOutcome.contains("the loop's own insulin"), l.lastOutcome)
    }

    @Test
    fun `a low DURING the mode is unaffected by the share`() {
        // The share only ever applies to the post-mode watch; a low while the mode runs is its own.
        val l = modeIsf()
        l.onCycle(MealMode.LOW_CARB, BASE_MS, 150.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, BASE_MS,
                  modeInsulinShare = 0.05)
        l.onCycle(MealMode.LOW_CARB, BASE_MS, 70.0, 100.0, true, 1.0, 0.0, 0.0, 50.0, 10.0,
                  BASE_MS + CYCLE_MS, modeInsulinShare = 0.05)
        l.onCycle(null, 0L, 75.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0,
                  BASE_MS + 2 * CYCLE_MS, modeInsulinShare = 0.05)
        assertEquals(1.05, l.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `DURA is charged in proportion too`() {
        fun run(share: Double): Double {
            sp = FakePreferences()
            val d = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
            d.onCycle(MealMode.UAM_LUNCH, BASE_MS, false, 1.40, false, BASE_MS, 2.0)
            d.onCycle(null, 0L, false, 1.0, false, BASE_MS + CYCLE_MS, 2.0,
                      modeInsulinShare = share, watchMs = 180 * 60_000L)
            d.onCycle(null, 0L, true, 1.0, false, BASE_MS + 120 * 60_000L, 2.0,
                      modeInsulinShare = share, watchMs = 180 * 60_000L)
            return d.factor(MealMode.UAM_LUNCH)
        }
        val mostlyMode = run(0.94)
        val mostlyLoop = run(0.33)
        assertTrue(mostlyMode < mostlyLoop, "mode's own low should cut harder: $mostlyMode vs $mostlyLoop")
        assertEquals(1.0, run(0.05), 1e-9)   // not DURA's at all
    }
}
