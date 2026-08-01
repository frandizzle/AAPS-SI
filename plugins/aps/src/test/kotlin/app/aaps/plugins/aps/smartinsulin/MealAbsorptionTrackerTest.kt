package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [MealAbsorptionTracker] — the observation-only carb-equivalent estimator.
 * Verifies the CI/grams math, episode boundary detection (start/finalize/re-trigger),
 * and bounded history persistence. Nothing here feeds dosing.
 */
class MealAbsorptionTrackerTest {

    private lateinit var sp: FakePreferences
    private lateinit var tracker: MealAbsorptionTracker

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        tracker = MealAbsorptionTracker(sp, FakeAAPSLogger(collect = false))
    }

    @Test
    fun `no active mode never starts an episode`() {
        val result = tracker.onCycle(
            activeMode = null, modeStartMs = 0L, deltaMgdl = 5.0, activityPerMin = 0.02,
            isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS
        )
        assertNull(result)
        assertTrue(tracker.history.isEmpty())
    }

    @Test
    fun `accumulates grams across cycles using CI over CSF`() {
        // expectedBgi = -activity*isf*5 = -0.02*50*5 = -5.0 mg/dL
        // ci = delta - expectedBgi = 8.0 - (-5.0) = 13.0 mg/dL
        // csf = isf/carbRatio = 50/10 = 5.0 mg/dL per gram
        // grams this cycle = 13.0/5.0 = 2.6
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, deltaMgdl = 8.0, activityPerMin = 0.02,
                         isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, deltaMgdl = 8.0, activityPerMin = 0.02,
                         isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS + CYCLE_MS)

        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, deltaMgdl = 0.0, activityPerMin = 0.0,
                                         isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS + 2 * CYCLE_MS)

        requireNotNull(completed)
        assertEquals(MealMode.DINNER, completed.mode)
        assertEquals(CYCLE_MS, completed.durationMs)  // last SEEN active cycle was BASE_MS+CYCLE_MS
        assertEquals(2.6 * 2, completed.estimatedGrams, 1e-9)
        assertEquals(1, tracker.history.size)
    }

    @Test
    fun `negative or zero CI contributes zero grams, never goes negative`() {
        // expectedBgi = -0.05*50*5 = -12.5; delta=-20 -> ci = -20-(-12.5) = -7.5 (insulin outperforming model)
        tracker.onCycle(MealMode.LUNCH, modeStartMs = BASE_MS, deltaMgdl = -20.0, activityPerMin = 0.05,
                         isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, deltaMgdl = 0.0, activityPerMin = 0.0,
                                         isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals(0.0, completed.estimatedGrams, 1e-9)
    }

    @Test
    fun `a re-trigger with a new modeStartMs finalizes the old episode before starting fresh`() {
        tracker.onCycle(MealMode.UAM_DINNER, modeStartMs = BASE_MS, deltaMgdl = 8.0, activityPerMin = 0.02,
                         isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)

        val newStart = BASE_MS + 30 * 60_000L
        val completedOld = tracker.onCycle(MealMode.UAM_DINNER, modeStartMs = newStart, deltaMgdl = 8.0,
                                            activityPerMin = 0.02, isfMgdl = 50.0, carbRatio = 10.0, nowMs = newStart)

        requireNotNull(completedOld)
        assertEquals(BASE_MS, completedOld.startMs)
        assertEquals(1, tracker.history.size)

        // the new episode is now tracking newStart, not the old one
        val completedNew = tracker.onCycle(activeMode = null, modeStartMs = 0L, deltaMgdl = 0.0, activityPerMin = 0.0,
                                            isfMgdl = 50.0, carbRatio = 10.0, nowMs = newStart + CYCLE_MS)
        requireNotNull(completedNew)
        assertEquals(newStart, completedNew.startMs)
        assertEquals(2, tracker.history.size)
    }

    @Test
    fun `zero carb ratio or ISF safely yields zero grams instead of dividing by zero`() {
        tracker.onCycle(MealMode.UAM_SNACK, modeStartMs = BASE_MS, deltaMgdl = 8.0,
                         activityPerMin = 0.02, isfMgdl = 0.0, carbRatio = 0.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, deltaMgdl = 0.0, activityPerMin = 0.0,
                                         isfMgdl = 0.0, carbRatio = 0.0, nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals(0.0, completed.estimatedGrams, 1e-9)
    }

    @Test
    fun `restoring drops history entries older than the retention window`() {
        val staleMs = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000  // 10 days ago
        val arr = JSONArray().put(
            JSONObject()
                .put("startMs", staleMs)
                .put("mode", MealMode.DINNER.name)
                .put("durationMs", 3_600_000L)
                .put("estimatedGrams", 55.0)
        )
        sp.putString(app.aaps.core.keys.StringKey.ApsSmartInsulinMealAbsorptionLog.key, arr.toString())

        val restored = MealAbsorptionTracker(sp, FakeAAPSLogger(collect = false))
        assertTrue(restored.history.isEmpty(), "Entries older than the retention window should be dropped on restore")
    }
}
