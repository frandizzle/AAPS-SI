package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey

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
 * Verifies the CI/grams math (insulin-pull term + target-seeking term), episode boundary
 * detection (start/finalize/re-trigger), and bounded history persistence. Nothing here feeds
 * dosing.
 *
 * Most tests use bgMgdl == targetMgdl to neutralize the target-seeking term and isolate the
 * insulin-pull math; the dedicated "stuck above target" tests exercise the target-seeking term
 * on its own.
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
            activeMode = null, modeStartMs = 0L, bgMgdl = 140.0, targetMgdl = 100.0,
            deltaMgdl = 5.0, activityPerMin = 0.02, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS
        )
        assertNull(result)
        assertTrue(tracker.history.isEmpty())
    }

    @Test
    fun `accumulates grams across cycles using CI over CSF`() {
        // bgMgdl == targetMgdl neutralizes the target-seeking term, isolating insulin-pull math.
        // expectedDelta = -activity*isf*5 = -0.02*50*5 = -5.0 mg/dL
        // ci = delta - expectedDelta = 8.0 - (-5.0) = 13.0 mg/dL
        // csf = isf/carbRatio = 50/10 = 5.0 mg/dL per gram
        // grams this cycle = 13.0/5.0 = 2.6
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, bgMgdl = 100.0, targetMgdl = 100.0,
                         deltaMgdl = 8.0, activityPerMin = 0.02, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, bgMgdl = 100.0, targetMgdl = 100.0,
                         deltaMgdl = 8.0, activityPerMin = 0.02, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS + CYCLE_MS)

        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 100.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0,
                                         nowMs = BASE_MS + 2 * CYCLE_MS)

        requireNotNull(completed)
        assertEquals(MealMode.DINNER, completed.mode)
        assertEquals(CYCLE_MS, completed.durationMs)  // last SEEN active cycle was BASE_MS+CYCLE_MS
        assertEquals(2.6 * 2, completed.estimatedGrams, 1e-9)
        assertEquals(1, tracker.history.size)
    }

    @Test
    fun `negative or zero CI contributes zero grams, never goes negative`() {
        // expectedDelta = -0.05*50*5 = -12.5; delta=-20 -> ci = -20-(-12.5) = -7.5 (insulin outperforming model)
        tracker.onCycle(MealMode.LUNCH, modeStartMs = BASE_MS, bgMgdl = 100.0, targetMgdl = 100.0,
                         deltaMgdl = -20.0, activityPerMin = 0.05, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 100.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0,
                                         nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals(0.0, completed.estimatedGrams, 1e-9)
    }

    @Test
    fun `a re-trigger with a new modeStartMs finalizes the old episode before starting fresh`() {
        tracker.onCycle(MealMode.UAM_DINNER, modeStartMs = BASE_MS, bgMgdl = 100.0, targetMgdl = 100.0,
                         deltaMgdl = 8.0, activityPerMin = 0.02, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)

        val newStart = BASE_MS + 30 * 60_000L
        val completedOld = tracker.onCycle(MealMode.UAM_DINNER, modeStartMs = newStart, bgMgdl = 100.0, targetMgdl = 100.0,
                                            deltaMgdl = 8.0, activityPerMin = 0.02, isfMgdl = 50.0, carbRatio = 10.0, nowMs = newStart)

        requireNotNull(completedOld)
        assertEquals(BASE_MS, completedOld.startMs)
        assertEquals(1, tracker.history.size)

        // the new episode is now tracking newStart, not the old one
        val completedNew = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 100.0, targetMgdl = 100.0,
                                            deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0,
                                            nowMs = newStart + CYCLE_MS)
        requireNotNull(completedNew)
        assertEquals(newStart, completedNew.startMs)
        assertEquals(2, tracker.history.size)
    }

    @Test
    fun `zero carb ratio or ISF safely yields zero grams instead of dividing by zero`() {
        tracker.onCycle(MealMode.UAM_SNACK, modeStartMs = BASE_MS, bgMgdl = 140.0, targetMgdl = 100.0,
                         deltaMgdl = 8.0, activityPerMin = 0.02, isfMgdl = 0.0, carbRatio = 0.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 140.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 0.0, carbRatio = 0.0,
                                         nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals(0.0, completed.estimatedGrams, 1e-9)
    }

    @Test
    fun `stuck flat above target with no insulin activity still counts as ongoing absorption`() {
        // The exact scenario this fixes: BG stuck at 180 mg/dL (target 100), completely flat
        // (delta=0), with no active insulin (activity=0). Before the target-seeking term this
        // read as zero residual purely because nothing was actively rising or being corrected.
        // targetSeekingPull = (100-180)/12 = -6.6667 mg/dL/cycle
        // ci = 0 - (-6.6667) = 6.6667; csf = 50/10 = 5.0 -> grams = 1.33333
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, bgMgdl = 180.0, targetMgdl = 100.0,
                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 180.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0,
                                         nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals((80.0 / 12.0) / 5.0, completed.estimatedGrams, 1e-9)
        assertTrue(completed.estimatedGrams > 0.0, "Stuck-above-target should register as ongoing absorption, not zero")
    }

    @Test
    fun `at or below target, stuck flat correctly registers zero — no phantom absorption`() {
        // bg == target -> targetSeekingPull = 0, and with zero activity/delta there's no
        // insulin-pull residual either, so this should stay exactly zero.
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, bgMgdl = 100.0, targetMgdl = 100.0,
                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 100.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0,
                                         nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals(0.0, completed.estimatedGrams, 1e-9)
    }

    @Test
    fun `pre-bolused decline above target is not double-counted as absorption`() {
        // Above target with strong insulin activity, BG falling exactly as the insulin model
        // predicts: insulinPull = -0.04*50*5 = -10, targetSeek = (100-160)/12 = -5, delta = -10.
        // The old summed formulation expected -15 → ci = +5 → ~1g/cycle of phantom grams for a
        // perfectly-covered decline. With min(): expected = -10 → ci = 0 → zero grams.
        tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, bgMgdl = 160.0, targetMgdl = 100.0,
                         deltaMgdl = -10.0, activityPerMin = 0.04, isfMgdl = 50.0, carbRatio = 10.0, nowMs = BASE_MS)
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 150.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0,
                                         nowMs = BASE_MS + CYCLE_MS)
        requireNotNull(completed)
        assertEquals(0.0, completed.estimatedGrams, 1e-9,
                     "A decline fully explained by insulin must not book phantom grams from the target-seek term")
    }

    @Test
    fun `phase segmentation splits an episode into rise then plateau then tail`() {
        // bg == target throughout → grams = delta/csf = delta/5 per cycle (activity 0).
        // Rate pattern: climbing (5, 10), holding (5), then near-zero — smoothed EWMA (keep 0.7)
        // makes new peaks on the first two cycles (RISE), holds ≥ half peak next (PLATEAU),
        // then decays below half peak (TAIL).
        var t = BASE_MS
        val deltas = listOf(5.0, 10.0, 5.0, 0.5, 0.0, 0.0, 0.0)
        deltas.forEach { d ->
            tracker.onCycle(MealMode.DINNER, modeStartMs = BASE_MS, bgMgdl = 100.0, targetMgdl = 100.0,
                             deltaMgdl = d, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0, nowMs = t)
            t += CYCLE_MS
        }
        val completed = tracker.onCycle(activeMode = null, modeStartMs = 0L, bgMgdl = 100.0, targetMgdl = 100.0,
                                         deltaMgdl = 0.0, activityPerMin = 0.0, isfMgdl = 50.0, carbRatio = 10.0, nowMs = t)
        requireNotNull(completed)
        // Hand-traced smoothed rates (g = delta/5 → 1.0, 2.0, 1.0, 0.1, 0, 0, 0):
        // s = 1.00(rise), 1.30(rise), 1.21(plateau), 0.877(plateau), 0.614(tail), 0.43(tail),
        // 0.30(tail) against peak 1.30 / half-peak 0.65.
        assertEquals(10L, completed.riseMins,    "First two climbing cycles should classify as rise")
        assertEquals(10L, completed.plateauMins, "Cycles holding ≥ half the peak rate should classify as plateau")
        assertEquals(15L, completed.tailMins,    "Decayed cycles below half peak should classify as tail")
        assertEquals(completed.estimatedGrams,
                     completed.riseGrams + completed.plateauGrams + completed.tailGrams, 1e-9,
                     "Phase gram buckets must sum to the episode total")
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
        sp.putString(app.aaps.core.keys.StringNonKey.ApsSmartInsulinMealAbsorptionLog.key, arr.toString())

        val restored = MealAbsorptionTracker(sp, FakeAAPSLogger(collect = false))
        assertTrue(restored.history.isEmpty(), "Entries older than the retention window should be dropped on restore")
    }
}
