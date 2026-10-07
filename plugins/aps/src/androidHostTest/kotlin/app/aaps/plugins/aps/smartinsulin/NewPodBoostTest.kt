package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [NewPodBoost]: stronger ISF and basal on a new pod, fading out, stopping on a low. */
class NewPodBoostTest {

    private val POD = 1_700_000_000_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN

    private lateinit var sp: FakePreferences
    private lateinit var boost: NewPodBoost
    private val events = mutableListOf<String>()

    @BeforeEach fun setUp() {
        sp = FakePreferences()
        boost = NewPodBoost(sp).apply { onEvent = { events += it } }
        events.clear()
    }

    private fun at(t: Long, enabled: Boolean = true, pct: Int = 30, hours: Int = 12,
                   below: Boolean = false, low: Boolean = false, site: Long? = POD) =
        boost.evaluate(t, site, enabled, pct, hours, below, low)

    @Test fun `starts at the full strength at the pod change and fades evenly to nothing`() {
        assertEquals(0.30, at(POD).extra, 1e-9)
        assertEquals(0.15, at(POD + 6 * HOUR).extra, 1e-9)
        assertEquals(0.075, at(POD + 9 * HOUR).extra, 1e-9)
        assertFalse(at(POD + 12 * HOUR).active)
        assertTrue(events.first().startsWith("New pod: ISF and basal +30%"), events.first())
        assertEquals("New pod boost finished.", events.last())
    }

    @Test fun `multiplier is one plus the boost - basal times it, ISF divided by it`() {
        assertEquals(1.30, at(POD).multiplier, 1e-9)
    }

    @Test fun `off, no pod, no strength or no duration means no boost`() {
        assertFalse(at(POD, enabled = false).active)
        assertFalse(at(POD, site = null).active)
        assertFalse(at(POD, pct = 0).active)
        assertFalse(at(POD, hours = 0).active)
    }

    @Test fun `switched on part way through still boosts what is left of the window`() {
        val s = at(POD + 3 * HOUR)
        assertTrue(s.active)
        assertEquals(0.225, s.extra, 1e-9)
    }

    @Test fun `under target pauses it - nothing extra - and it picks up again above target`() {
        at(POD)
        val paused = at(POD + HOUR, below = true)
        assertFalse(paused.active)
        assertTrue(paused.paused, "still in the window: learning stays paused")
        assertEquals(1.0, paused.multiplier, 1e-9)
        val back = at(POD + 2 * HOUR)
        assertTrue(back.active, "a pod changed at target must not lose its boost")
        assertEquals(0.25, back.extra, 1e-9)
    }

    @Test fun `a low ends it for the rest of that pod`() {
        at(POD)
        assertFalse(at(POD + HOUR, low = true).active)
        assertTrue(events.last().startsWith("New pod boost ended early: BG went low"), events.last())
        assertFalse(at(POD + 2 * HOUR).active, "does not come back for this pod")
    }

    @Test fun `ended for a pod stays ended after a restart`() {
        at(POD); at(POD + HOUR, low = true)
        val restarted = NewPodBoost(sp)
        assertFalse(restarted.evaluate(POD + 2 * HOUR, POD, true, 30, 12, false, false).active)
    }

    @Test fun `the next pod gets its own boost`() {
        at(POD); at(POD + HOUR, low = true)
        val next = POD + 3 * 24 * HOUR
        assertTrue(at(next, site = next).active)
    }

    @Test fun `stopped by the user ends it for this pod`() {
        at(POD)
        boost.endNow()
        assertFalse(boost.state.active)
        assertEquals("New pod boost stopped by you.", events.last())
        assertFalse(at(POD + HOUR).active)
    }

    // ── meals and UAM ────────────────────────────────────────────────────────

    @Test fun `in a meal half the boost is used by default, still fading on schedule`() {
        at(POD)
        assertEquals(0.15, boost.forMeal(inMeal = true, percent = 50).extra, 1e-9)
        assertEquals(50, boost.mealPercent)
        at(POD + 6 * HOUR)
        val meal = boost.forMeal(inMeal = true, percent = 50)
        assertEquals(0.075, meal.extra, 1e-9)
        assertEquals(1.075, meal.multiplier, 1e-9)
        assertEquals(boost.state.minutesLeft, meal.minutesLeft)
    }

    @Test fun `0 percent means no boost in a meal, 100 percent the full boost`() {
        at(POD + 6 * HOUR)
        assertEquals(1.0, boost.forMeal(inMeal = true, percent = 0).multiplier, 1e-9)
        assertEquals(0, boost.mealPercent)
        assertEquals(0.15, boost.forMeal(inMeal = true, percent = 100).extra, 1e-9)
    }

    @Test fun `outside a meal the full boost is used, and the meal cut does not stick`() {
        at(POD)
        boost.forMeal(inMeal = true, percent = 50)
        at(POD + 6 * HOUR)
        val fasting = boost.forMeal(inMeal = false, percent = 50)
        assertEquals(0.15, fasting.extra, 1e-9)
        assertEquals(null, boost.mealPercent)
        assertEquals(0.15, boost.dosing.extra, 1e-9)
    }

    @Test fun `a meal does not change the boost itself or stop it from ending on a low`() {
        at(POD)
        boost.forMeal(inMeal = true, percent = 50)
        assertEquals(0.30, boost.state.extra, 1e-9)
        assertFalse(at(POD + HOUR, low = true).active)
        assertFalse(boost.forMeal(inMeal = true, percent = 50).active)
        assertFalse(at(POD + 2 * HOUR).active)
    }

    @Test fun `paused under target stays paused in a meal`() {
        at(POD)
        at(POD + HOUR, below = true)
        val meal = boost.forMeal(inMeal = true, percent = 50)
        assertFalse(meal.active)
        assertEquals(1.0, meal.multiplier, 1e-9)
    }

    @Test fun `stopping it by hand also clears the meal dose`() {
        at(POD)
        boost.forMeal(inMeal = true, percent = 50)
        boost.endNow()
        assertFalse(boost.dosing.active)
        assertEquals(null, boost.mealPercent)
    }

    // ── did the boost really add insulin (what marks a meal as touched) ──────

    @Test fun `a boost in use with insulin going in added insulin`() {
        val s = at(POD)
        assertTrue(NewPodBoost.addedInsulin(s, smbU = 0.0, basalUPerHour = 0.9))
        assertTrue(NewPodBoost.addedInsulin(s, smbU = 0.3, basalUPerHour = 0.0))
    }

    @Test fun `a zero temp with no SMB adds nothing, boost or not`() {
        // Changing a pod while BG is already crashing: the low guard holds insulin at zero.
        assertFalse(NewPodBoost.addedInsulin(at(POD), smbU = 0.0, basalUPerHour = 0.0))
    }

    @Test fun `a boost paused under target or switched off for meals adds nothing`() {
        at(POD)
        assertFalse(NewPodBoost.addedInsulin(at(POD + HOUR, below = true), smbU = 0.5, basalUPerHour = 1.0))
        at(POD + 2 * HOUR)
        val meal = boost.forMeal(inMeal = true, percent = 0)
        assertFalse(NewPodBoost.addedInsulin(meal, smbU = 0.5, basalUPerHour = 1.0))
    }

    @Test fun `no boost adds nothing`() {
        assertFalse(NewPodBoost.addedInsulin(at(POD, enabled = false), smbU = 0.5, basalUPerHour = 1.0))
    }
}
