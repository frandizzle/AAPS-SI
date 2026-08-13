package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [ModeIsfLearner] — episode-outcome per-mode ISF learning.
 * Steps: strengthen ×0.975 (above target at eval, or DURA intervened),
 * weaken ×1.05 (low during episode or tail), skip on contamination. Rails 0.6–1.4.
 * Settling tail is 75 min → 15 five-minute cycles before the evaluation fires.
 */
class ModeIsfLearnerTest {

    private lateinit var sp: FakePreferences
    private lateinit var learner: ModeIsfLearner

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        learner = ModeIsfLearner(sp, FakeAAPSLogger(collect = false))
    }

    /** One loop cycle with sensible defaults; returns nowMs advanced by the caller. */
    private fun cycle(
        mode: MealMode?, startMs: Long, nowMs: Long,
        bg: Double = 100.0, target: Double = 100.0,
        low: Boolean = false, dura: Double = 1.0,
        delta: Double = 0.0, activity: Double = 0.0,
        baseSig: Double = 0.0, exercise: Boolean = false
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, bgMgdl = bg, targetMgdl = target,
        lowActive = low, duraMult = dura, deltaMgdl = delta, activityPerMin = activity,
        fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = nowMs, baseSignature = baseSig,
        exerciseSuspected = exercise
    )

    /** Runs the full 75-min settling tail quietly (flat BG at [bg]), ending past the deadline. */
    private fun runQuietTail(fromMs: Long, bg: Double, target: Double = 100.0): Long {
        var t = fromMs
        repeat(16) {  // 16 × 5min = 80min > 75min tail
            t += CYCLE_MS
            cycle(mode = null, startMs = 0L, nowMs = t, bg = bg, target = target)
        }
        return t
    }

    @Test
    fun `episode ending above target strengthens the mode multiplier by one step`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0)
        cycle(MealMode.DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 160.0)
        // mode ends; tail runs quietly with BG still 130 (target 100, margin 18 → strengthen)
        runQuietTail(BASE_MS + CYCLE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `episode ending near target leaves the multiplier unchanged`() {
        cycle(MealMode.LUNCH, BASE_MS, BASE_MS, bg = 140.0)
        runQuietTail(BASE_MS, bg = 105.0)  // within margin of target
        assertEquals(1.0, learner.multiplier(MealMode.LUNCH), 1e-9)
    }

    @Test
    fun `low during the episode weakens immediately at mode end without waiting for the tail`() {
        // LOW_CARB is a manually-activated mode — no UAM entry burst, so there's no entry-shape
        // learner to arbitrate with and every low is this learner's (magnitude) evidence.
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS, bg = 80.0, low = true)
        // single cycle after mode end — weaken should already have landed
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 90.0)
        assertEquals(1.05, learner.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `a low with an unexplained drop weakens at a reduced step, not the full one`() {
        // Exercise signature: the low arrived alongside BG falling faster than insulin explains.
        // Still weakens (safety direction, classifier can be wrong) but only 40% of the step:
        // 1.0 + 0.05*0.4 = 1.02 instead of 1.05.
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS, bg = 80.0, low = true, exercise = true)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 90.0)
        assertEquals(1.02, learner.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `a low in the settling tail with an unexplained drop also uses the reduced step`() {
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS, bg = 120.0)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 95.0)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 65.0, low = true, exercise = true)
        assertEquals(1.02, learner.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `early low after a UAM entry is skipped — owned by the entry-fraction learner`() {
        // ARBITRATION: a low within the 75min entry window is shape evidence, not magnitude.
        // UamEntryFractionLearner acts on it; this learner must stay put so one mistake
        // isn't corrected twice.
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 85.0)
        assertEquals(1.0, learner.multiplier(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `late low after a UAM entry still weakens — that one is magnitude evidence`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 150.0)
        // 80 min in — past the 75min entry-attribution window
        val lateMs = BASE_MS + 80 * 60_000L
        cycle(MealMode.UAM_DINNER, BASE_MS, lateMs, bg = 68.0, low = true)
        cycle(null, 0L, lateMs + CYCLE_MS, bg = 80.0)
        assertEquals(1.05, learner.multiplier(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `low during the settling tail weakens the mode`() {
        cycle(MealMode.BREAKFAST, BASE_MS, BASE_MS, bg = 120.0)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 95.0)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 65.0, low = true)
        assertEquals(1.05, learner.multiplier(MealMode.BREAKFAST), 1e-9)
    }

    @Test
    fun `DURA intervention strengthens even when BG ends on target`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 150.0, dura = 1.25)
        runQuietTail(BASE_MS, bg = 102.0)  // clean ending — but only because DURA rescued it
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `fresh absorption during the tail skips the evaluation entirely`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 150.0)
        // Tail with sustained rise: delta=10, activity=0 → ci=10 → 2g/cycle at csf=5.
        // After 5 cycles that's 10g > 8g contamination gate → evaluation voided.
        var t = BASE_MS
        repeat(6) {
            t += CYCLE_MS
            cycle(null, 0L, t, bg = 150.0 + it * 10, delta = 10.0)
        }
        // run past the would-be deadline quietly — nothing should fire
        runQuietTail(t, bg = 170.0)
        assertEquals(1.0, learner.multiplier(MealMode.DINNER), 1e-9,
                     "A contaminated tail (ate again) must not move the multiplier")
    }

    @Test
    fun `new activation during a pending tail voids the previous evaluation`() {
        cycle(MealMode.LUNCH, BASE_MS, BASE_MS, bg = 150.0)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 150.0)  // LUNCH pending eval
        val newStart = BASE_MS + 2 * CYCLE_MS
        cycle(MealMode.DINNER, newStart, newStart, bg = 150.0)  // new mode → LUNCH eval voided
        // end DINNER and let ITS tail complete above target — only DINNER should move
        runQuietTail(newStart, bg = 130.0)
        assertEquals(1.0, learner.multiplier(MealMode.LUNCH), 1e-9)
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `weaken steps are railed at the 1_4 maximum`() {
        var t = BASE_MS
        repeat(20) {
            cycle(MealMode.LUNCH, t, t, bg = 80.0, low = true)
            t += CYCLE_MS
            cycle(null, 0L, t, bg = 90.0)  // immediate weaken at each mode end
            t += CYCLE_MS
        }
        assertEquals(1.4, learner.multiplier(MealMode.LUNCH), 1e-9)
    }

    @Test
    fun `changing the mode's ISF override resets its learned multiplier`() {
        // Learn a strengthen against base signature 21.6 (user's 1.2 mmol override)
        cycle(MealMode.LUNCH, BASE_MS, BASE_MS, bg = 160.0, baseSig = 21.6)
        runQuietTail(BASE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9)

        // Next lunch starts with a different override (user changed 1.2 → 0.7 mmol = 12.6 mg/dL)
        val newStart = BASE_MS + 6 * 60 * 60_000L
        cycle(MealMode.LUNCH, newStart, newStart, bg = 120.0, baseSig = 12.6)
        assertEquals(1.0, learner.multiplier(MealMode.LUNCH), 1e-9,
                     "A changed base override must reset the learned multiplier — keeping it would double-apply the user's own correction")
    }

    @Test
    fun `unchanged override does not reset the learned multiplier between episodes`() {
        cycle(MealMode.LUNCH, BASE_MS, BASE_MS, bg = 160.0, baseSig = 21.6)
        runQuietTail(BASE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9)

        val newStart = BASE_MS + 6 * 60 * 60_000L
        cycle(MealMode.LUNCH, newStart, newStart, bg = 120.0, baseSig = 21.6)
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9,
                     "Same base override — learned state must carry forward")
    }

    @Test
    fun `learned state survives persistence round-trip`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0)
        runQuietTail(BASE_MS, bg = 130.0)  // strengthen → 0.975
        val restored = ModeIsfLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(0.975, restored.multiplier(MealMode.DINNER), 1e-9)
        assertTrue(restored.episodeCount(MealMode.DINNER) > 0, "Episode count should survive the restore too")
    }
}
