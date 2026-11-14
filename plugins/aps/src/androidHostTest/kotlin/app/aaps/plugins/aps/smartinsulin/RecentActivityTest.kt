package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.AfterActivity
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.smartInsulin.ActivityMonitor.ActivityLevel
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `a Golf or Gym session counts as moderate only while the steps show walking`() {
        val steps = RecentActivityImpl.SESSION_MIN_STEPS_5MIN
        assertEquals(ActivityLevel.MODERATE, RecentActivityImpl.levelWithSession(ActivityLevel.SEDENTARY, sessionActive = true, steps5min = steps))
        assertEquals(ActivityLevel.SEDENTARY, RecentActivityImpl.levelWithSession(ActivityLevel.SEDENTARY, sessionActive = true, steps5min = steps - 1))
        assertEquals(ActivityLevel.LIGHT, RecentActivityImpl.levelWithSession(ActivityLevel.LIGHT, sessionActive = false, steps5min = 500))
        assertEquals(ActivityLevel.HEAVY, RecentActivityImpl.levelWithSession(ActivityLevel.HEAVY, sessionActive = true, steps5min = 500))
    }

    @Test
    fun `a round of golf with a session gives the full cut at the lunch after it`() {
        // 14 Nov: Golf session 8:30 to 12:30 (T0 is 8:30), walking every other 5 min (400 steps), waiting in between (20).
        // The watch counted none of it: heart rate is off (resting set to 110) and stop-and-go steps are only light.
        var t = T0
        repeat(48) { i ->
            val steps = if (i % 2 == 0) 400 else 20
            val watch = if (i % 2 == 0) ActivityLevel.LIGHT else ActivityLevel.SEDENTARY
            recent.onCycle(t, RecentActivityImpl.levelWithSession(watch, sessionActive = true, steps5min = steps))
            t += 5 * MIN
        }
        // Lunch at 13:10: from 9:10 on, 20 walking stretches of 5 min are in the last 4 hours.
        t = walk(t, 40, ActivityLevel.SEDENTARY)
        assertEquals(AfterActivity(100, 20), recent.afterActivity(t))
    }

    /** 14 Nov: a Golf session from 8:30 (T0) to 12:30, walking every other 5 min. Returns the time after it. */
    private fun golfRound(): Long {
        var t = T0
        repeat(48) { i ->
            val walking = i % 2 == 0
            recent.onCycle(
                t, RecentActivityImpl.levelWithSession(ActivityLevel.SEDENTARY, sessionActive = true, steps5min = if (walking) 400 else 20),
                sessionWalking = walking
            )
            t += 5 * MIN
        }
        return t
    }

    @Test
    fun `after a Golf session the cut lasts into the evening, smaller with time, and is gone 12 hours after`() {
        golfRound()
        // 120 min of walking, the last at 12:20. Dinner at 6:21 pm, 6 h after it: 20 % x (12 h - 6 h 1 min) / 8 h = 15 %.
        val dinner = T0 + 9 * HOUR + 51 * MIN
        assertEquals(AfterActivity(120, 15, afterSession = true), recent.afterActivity(dinner))
        // Still in full 4 h after the session.
        assertEquals(AfterActivity(120, 20, afterSession = true), recent.afterActivity(T0 + 230 * MIN + 4 * HOUR))
        // Gone 12 h after it.
        assertNull(recent.afterActivity(T0 + 230 * MIN + 12 * HOUR))
        // The meal learners leave the evening meal out too.
        assertTrue(recent.exercised(dinner))
    }

    @Test
    fun `the cut after a session is kept over a restart of the app`() {
        golfRound()
        val restarted = RecentActivityImpl(sp)
        assertEquals(AfterActivity(120, 15, afterSession = true), restarted.afterActivity(T0 + 9 * HOUR + 51 * MIN))
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

    @Test
    fun `10 min of real exercise in the last 4 hours counts as exercised`() {
        var t = walk(T0, 60, ActivityLevel.LIGHT)
        t = walk(t, 5, ActivityLevel.MODERATE)
        assertFalse(recent.exercised(t))           // light does not count, 5 min is too short
        t = walk(t, 5, ActivityLevel.MODERATE)
        assertTrue(recent.exercised(t))
        assertTrue(recent.exercised(t + 3 * HOUR))
        assertFalse(recent.exercised(t + 4 * HOUR))
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val T0 = 1_790_000_000_000L
    }
}
