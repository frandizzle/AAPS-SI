package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.ActivityMonitor.ActivityLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [ActivityTail]: learning stays paused after activity only while BG is still dropping from it. */
class ActivityTailTest {

    private val T0 = 1_700_000_000_000L
    private val MIN = 60_000L

    /** Minutes after activity stopped that the pause lasted, for a run of cycles every 5 min. */
    private fun pausedMinutes(tail: ActivityTail, start: Long, dropping: (Int) -> Boolean): Int {
        var m = 0
        while (m <= 180) {
            tail.onCycle(start + m * MIN, ActivityLevel.SEDENTARY, dropping(m))
            if (!tail.paused) return m
            m += 5
        }
        return m
    }

    @Test fun `a sprint - paused while BG keeps dropping after you sit down, then learning resumes`() {
        val tail = ActivityTail()
        tail.onCycle(T0, ActivityLevel.HEAVY, false)
        tail.onCycle(T0 + 5 * MIN, ActivityLevel.HEAVY, false)
        assertFalse(tail.paused, "during activity the activity monitor pauses learning, not the tail")
        // Sat down at +5; BG keeps falling faster than insulin explains for 50 more minutes.
        val start = T0 + 5 * MIN
        assertEquals(55, pausedMinutes(tail, start) { it < 55 })
    }

    @Test fun `a short burst that did nothing to BG pauses only the minimum`() {
        val tail = ActivityTail()
        tail.onCycle(T0, ActivityLevel.LIGHT, false)
        assertEquals(15, pausedMinutes(tail, T0) { false })
    }

    @Test fun `the pause never runs past the cap for the hardest level seen`() {
        val heavy = ActivityTail().apply { onCycle(T0, ActivityLevel.LIGHT, false); onCycle(T0 + 5 * MIN, ActivityLevel.HEAVY, false) }
        assertEquals(95, pausedMinutes(heavy, T0 + 5 * MIN) { true })
        val moderate = ActivityTail().apply { onCycle(T0, ActivityLevel.MODERATE, false) }
        assertEquals(65, pausedMinutes(moderate, T0) { true })
        val light = ActivityTail().apply { onCycle(T0, ActivityLevel.LIGHT, false) }
        assertEquals(35, pausedMinutes(light, T0) { true })
    }

    @Test fun `once ended it stays ended until there is new activity`() {
        val tail = ActivityTail()
        tail.onCycle(T0, ActivityLevel.LIGHT, false)
        pausedMinutes(tail, T0) { false }
        tail.onCycle(T0 + 40 * MIN, ActivityLevel.SEDENTARY, true)
        assertFalse(tail.paused, "a later drop with no activity is not this tail's")
        tail.onCycle(T0 + 45 * MIN, ActivityLevel.MODERATE, false)
        tail.onCycle(T0 + 50 * MIN, ActivityLevel.SEDENTARY, true)
        assertTrue(tail.paused, "new activity starts a new tail")
    }

    @Test fun `no activity ever - never paused`() {
        val tail = ActivityTail()
        tail.onCycle(T0, ActivityLevel.SEDENTARY, true)
        assertFalse(tail.paused)
    }

    @Test fun `the drop tracker's still-dropping reading looks at the last 15 minutes only`() {
        val tracker = UnexplainedDropTracker()
        // 30 min of a hard unexplained drop, then 15 min of BG falling only as insulin explains.
        var t = T0
        repeat(6) { tracker.onCycle(deltaMgdl = -9.0, activityPerMin = 0.0, isfMgdl = 50.0, nowMs = t); t += 5 * MIN }
        assertTrue(tracker.stillDropping(t - 5 * MIN))
        repeat(3) { tracker.onCycle(deltaMgdl = -2.5, activityPerMin = 0.01, isfMgdl = 50.0, nowMs = t); t += 5 * MIN }
        assertFalse(tracker.stillDropping(t - 5 * MIN), "the drop is now explained by insulin")
        assertTrue(tracker.exerciseSuspected, "the 45-min view still remembers it - which is why the tail does not use it")
    }
}
