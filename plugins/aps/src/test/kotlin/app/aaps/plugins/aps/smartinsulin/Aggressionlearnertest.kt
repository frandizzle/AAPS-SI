package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AggressionLearner].
 *
 * Time injected via nowMs parameter — no wall clock dependency.
 *
 * Scenarios covered:
 *  - Fasting samples drive score; meal samples are recorded but don't affect score
 *  - Sustained time high increases aggressiveness score
 *  - Sustained time low decreases aggressiveness score
 *  - Suppress scoring flag records sample without updating score
 *  - 1-hour update interval gate prevents over-fitting
 *  - Score stays within [1/max .. max] hard limits
 *  - Score decays asymmetrically toward 1.0 when BG is in range
 *  - Persistence round-trip preserves score
 *  - Reset clears all state
 */
class AggressionLearnerTest {

    private lateinit var learner: AggressionLearner
    private lateinit var prefs: FakePreferences
    private val logger = FakeAAPSLogger()

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val LOW_THRESH  = 70.0   // 3.9 mmol
    private val HIGH_THRESH = 180.0  // 10.0 mmol
    private val AGGR_MAX    = 1.3

    @BeforeEach
    fun setUp() {
        prefs = FakePreferences()

        // Standard AAPS Preferences interface method
        prefs.put(DoubleKey.ApsSmartInsulinAggressionMax, AGGR_MAX)

        learner = AggressionLearner(prefs, logger)
    }

    private fun record(
        bgMgdl: Double,
        mode: MealMode = MealMode.FASTING,
        nowMs: Long = BASE_MS,
        suppress: Boolean = false
    ) = learner.recordBg(
        bgMgdl         = bgMgdl,
        lowThreshMgdl  = LOW_THRESH,
        highThreshMgdl = HIGH_THRESH,
        mealMode       = mode,
        suppressScoring = suppress,
        nowMs          = nowMs
    )

    /**
     * Feed enough fasting in-range samples to satisfy MIN_SAMPLES_TO_LEARN (24),
     * then advance past UPDATE_INTERVAL_MS (1h) to trigger a score update.
     */
    private fun seedInRange(startMs: Long = BASE_MS): Long {
        var t = startMs
        repeat(30) {
            record(bgMgdl = 100.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        // Advance past 1h update gate and trigger one more
        t = startMs + 60 * 60_000L + CYCLE_MS
        record(bgMgdl = 100.0, mode = MealMode.FASTING, nowMs = t)
        return t
    }

    // ── Fasting vs meal separation ────────────────────────────────────────────

    @Test
    fun `meal samples do not affect aggressiveness score`() {
        val before = learner.aggressiveness

        // Feed 40 high-BG meal-mode samples — should not move score
        var t = BASE_MS
        repeat(40) {
            record(bgMgdl = 250.0, mode = MealMode.DINNER, nowMs = t)
            t += CYCLE_MS
        }
        // Advance past update interval
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        record(bgMgdl = 250.0, mode = MealMode.DINNER, nowMs = t)

        assertEquals(before, learner.aggressiveness, 1e-6,
                     "Meal-mode samples should not affect aggressiveness score")
    }

    @Test
    fun `fasting samples drive score upward when BG stays high`() {
        val before = learner.aggressiveness

        // Seed enough in-range samples to enable scoring
        var t = seedInRange()

        // Now feed sustained high fasting BG
        repeat(30) {
            record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)

        assertTrue(learner.aggressiveness > before,
                   "Score should increase when fasting BG is consistently high (got $before → ${learner.aggressiveness})")
    }

    @Test
    fun `fasting samples drive score downward when BG stays low`() {
        // First push score above 1.0
        var t = seedInRange()
        repeat(30) {
            record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
        val afterHigh = learner.aggressiveness
        assertTrue(afterHigh > 1.0, "precondition: score should be above 1.0")

        // Now feed sustained lows
        repeat(30) {
            record(bgMgdl = 60.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        record(bgMgdl = 60.0, mode = MealMode.FASTING, nowMs = t)

        assertTrue(learner.aggressiveness < afterHigh,
                   "Score should decrease when fasting BG is consistently low")
    }

    // ── Suppress scoring ──────────────────────────────────────────────────────

    @Test
    fun `suppress scoring records sample without updating score`() {
        seedInRange()
        val scoreAfterSeed = learner.aggressiveness

        // Feed high BG with suppress=true — score should not move
        var t = BASE_MS + 2 * 60 * 60_000L
        repeat(30) {
            record(bgMgdl = 250.0, mode = MealMode.FASTING, suppress = true, nowMs = t)
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        record(bgMgdl = 250.0, mode = MealMode.FASTING, suppress = true, nowMs = t)

        assertEquals(scoreAfterSeed, learner.aggressiveness, 1e-6,
                     "Suppressed samples should not update score")
    }

    // ── Update interval gate ──────────────────────────────────────────────────

    @Test
    fun `score does not update within 1 hour of last update`() {
        seedInRange()
        val scoreAfterSeed = learner.aggressiveness

        // Feed high BG 30 min after seed — gate should block update
        val t = BASE_MS + 90 * 60_000L  // 30 min after the 1h mark
        repeat(5) { i ->
            record(bgMgdl = 250.0, mode = MealMode.FASTING, nowMs = t + i * CYCLE_MS)
        }

        assertEquals(scoreAfterSeed, learner.aggressiveness, 1e-6,
                     "Score should not update within 1h of last update")
    }

    // ── Score bounds ──────────────────────────────────────────────────────────

    @Test
    fun `score never exceeds aggressionMax`() {
        var t = BASE_MS
        repeat(300) { i ->
            record(bgMgdl = 250.0, mode = MealMode.FASTING, nowMs = t)
            if (i % 12 == 0) t += 60 * 60_000L + CYCLE_MS
            else t += CYCLE_MS
        }
        assertTrue(learner.aggressiveness <= AGGR_MAX,
                   "Score should never exceed aggressionMax ($AGGR_MAX), got ${learner.aggressiveness}")
    }

    @Test
    fun `score never goes below 1 over aggressionMax`() {
        var t = BASE_MS
        repeat(300) { i ->
            record(bgMgdl = 50.0, mode = MealMode.FASTING, nowMs = t)
            if (i % 12 == 0) t += 60 * 60_000L + CYCLE_MS
            else t += CYCLE_MS
        }
        val floor = 1.0 / AGGR_MAX
        assertTrue(learner.aggressiveness >= floor,
                   "Score should never go below floor (${floor}), got ${learner.aggressiveness}")
    }

    // ── Asymmetric decay ──────────────────────────────────────────────────────

    @Test
    fun `score above 1 decays toward neutral slowly when BG in range`() {
        var t = seedInRange()

        // 1. Seed high score
        repeat(30) {
            record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        val aggressive = learner.aggressiveness

        // 2. Fast-forward completely past the 24h window
        t += 24 * 60 * 60_000L

        // 3. Feed in-range BG for 4 HOURS (48 cycles).
        // 15 cycles was not enough to pass the computeTir minimum sample gate!
        repeat(48) {
            record(bgMgdl = 100.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }

        // Added values to the error message so we can see exactly what it's doing if it ever fails
        assertTrue(learner.aggressiveness < aggressive,
                   "Score should have moved toward 1.0 (was $aggressive, now ${learner.aggressiveness})")

        assertTrue(learner.aggressiveness > 1.0,
                   "Score > 1.0 should decay slowly — should still be above 1.0 after updates")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    fun `score survives serialization round-trip`() {
        var t = seedInRange()
        repeat(30) {
            record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
        val saved = learner.aggressiveness

        val learner2 = AggressionLearner(prefs, logger)
        assertEquals(saved, learner2.aggressiveness, 0.001,
                     "Score should survive serialization round-trip")
    }

    @Test
    fun `reset clears all state`() {
        var t = seedInRange()
        repeat(30) {
            record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        record(bgMgdl = 220.0, mode = MealMode.FASTING, nowMs = t)
        assertTrue(learner.aggressiveness != 1.0, "precondition: score should have moved")

        learner.reset()
        assertEquals(1.0, learner.aggressiveness, 1e-6,
                     "Score should be 1.0 after reset")
    }
}