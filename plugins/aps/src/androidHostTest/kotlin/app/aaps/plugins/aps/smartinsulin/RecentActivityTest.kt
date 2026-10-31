package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.AfterActivity
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.smartInsulin.ActivityMonitor.ActivityLevel
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [RecentActivityImpl]: minutes of exercise in the last hours, and the cut of meal insulin they suggest. */
class RecentActivityTest {

    private val sp = FakePreferences()
    private val recent = RecentActivityImpl(sp)

    /** [minutes] of [level] from [start], one loop cycle every 5 min. Returns the time after the last cycle. */
    private fun walk(start: Long, minutes: Int, level: ActivityLevel): Long {
        var t = start
        repeat(minutes / 5) { recent.onCycle(t, level); t += 5 * MIN }
        return t
    }

    @Test
    fun `an hour of brisk walking gives 15 percent`() {
        val end = walk(T0, 60, ActivityLevel.MODERATE)
        assertEquals(AfterActivity(60, 15), recent.afterActivity(end))
    }

    @Test
    fun `light activity and a short walk give nothing`() {
        var t = walk(T0, 120, ActivityLevel.LIGHT)
        t = walk(t, 25, ActivityLevel.MODERATE)
        assertNull(recent.afterActivity(t))
    }

    @Test
    fun `heavy exercise counts one and a half times`() {
        val end = walk(T0, 60, ActivityLevel.HEAVY)
        assertEquals(AfterActivity(90, 20), recent.afterActivity(end))
    }

    @Test
    fun `an extra loop run does not count twice`() {
        var t = T0
        repeat(5) {
            recent.onCycle(t, ActivityLevel.MODERATE)
            recent.onCycle(t + MIN, ActivityLevel.MODERATE)
            t += 5 * MIN
        }
        assertNull(recent.afterActivity(t))        // 25 min, each cycle counted once
        recent.onCycle(t, ActivityLevel.MODERATE)
        assertEquals(10, recent.afterActivity(t)!!.cutPct)
    }

    @Test
    fun `exercise more than 4 hours ago no longer counts`() {
        val end = walk(T0, 60, ActivityLevel.MODERATE)
        assertEquals(15, recent.afterActivity(end + 3 * HOUR)!!.cutPct)
        // Half of the walk is now older than 4 hours.
        assertEquals(10, recent.afterActivity(T0 + 4 * HOUR + 30 * MIN)!!.cutPct)
        assertNull(recent.afterActivity(end + 4 * HOUR))
    }

    @Test
    fun `the setting caps the cut, and 0 turns it off`() {
        val end = walk(T0, 120, ActivityLevel.MODERATE)
        sp.edit { putInt(IntKey.ApsSmartInsulinAfterActivityMaxPct.key, 10) }
        assertEquals(10, recent.afterActivity(end)!!.cutPct)
        sp.edit { putInt(IntKey.ApsSmartInsulinAfterActivityMaxPct.key, 0) }
        assertNull(recent.afterActivity(end))
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val T0 = 1_790_000_000_000L
    }
}
