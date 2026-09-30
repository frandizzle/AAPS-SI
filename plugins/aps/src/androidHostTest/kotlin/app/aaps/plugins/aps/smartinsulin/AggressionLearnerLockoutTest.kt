package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The post-meal lockout's effect on [AggressionLearner].
 *
 * The lockout exists because a meal's tail keeps raising BG after the meal mode has already
 * reverted to FASTING. Those readings are not evidence about fasting insulin needs. But
 * suppressScoring only ever skipped the current cycle's scoring pass — the sample itself still
 * landed in the fasting pool and stayed there for the full 24-hour window, so the next hourly
 * pass counted the meal tail as fasting time-above-range and pushed aggressiveness UP. Exactly
 * the pollution the lockout was built to prevent, arriving one cycle later.
 *
 * The flag now travels with the sample, so a suppressed reading is excluded from every scoring
 * pass for as long as it is held.
 */
class AggressionLearnerLockoutTest {

    private lateinit var learner: AggressionLearner
    private lateinit var prefs: FakePreferences
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val HOUR_MS  = 60 * 60_000L

    private val LOW_MGDL  = 70.0
    private val HIGH_MGDL = 180.0

    @BeforeEach
    fun setUp() {
        prefs = FakePreferences()
        learner = AggressionLearner(prefs, logger)
    }

    private fun record(bgMgdl: Double, count: Int, suppressed: Boolean, startMs: Long): Long {
        var t = startMs
        repeat(count) {
            learner.recordBg(bgMgdl, LOW_MGDL, HIGH_MGDL, MealMode.FASTING, suppressed, t)
            t += CYCLE_MS
        }
        return t
    }

    @Test
    fun `suppressed highs do not raise the score`() {
        // A long run of post-meal-tail highs, every one of them taken inside the lockout.
        // recordBg is called with the lockout flag, so none of them may reach the scorer.
        val t = record(bgMgdl = 250.0, count = 60, suppressed = true, startMs = BASE_MS)

        // Force a scoring pass an hour later with one clean in-range sample. The pool now holds
        // 60 suppressed highs and one scorable reading, which is under MIN_SAMPLES_TO_LEARN, so
        // the learner must decline to score at all rather than act on the suppressed ones.
        learner.recordBg(110.0, LOW_MGDL, HIGH_MGDL, MealMode.FASTING, false, t + HOUR_MS)

        assertEquals(1.0, learner.aggressiveness, 1e-9,
                     "Lockout samples must be invisible to scoring — score moved to ${learner.aggressiveness}")
    }

    @Test
    fun `unsuppressed highs do raise the score`() {
        // The same data without the lockout flag. This is the control: it proves the previous
        // test passes because the samples were excluded, not because the path is inert.
        val t = record(bgMgdl = 250.0, count = 60, suppressed = false, startMs = BASE_MS)
        learner.recordBg(250.0, LOW_MGDL, HIGH_MGDL, MealMode.FASTING, false, t + HOUR_MS)

        assertTrue(learner.aggressiveness > 1.0,
                   "Genuine sustained fasting highs should still raise aggressiveness, got ${learner.aggressiveness}")
    }

    @Test
    fun `a suppressed sample stays excluded on later scoring passes too`() {
        // The original bug was not that the suppressed CYCLE scored — it was that the suppressed
        // SAMPLE was scored an hour later by a pass that was itself perfectly legitimate.
        var t = record(bgMgdl = 250.0, count = 40, suppressed = true, startMs = BASE_MS)

        // Then enough clean, in-range fasting samples to satisfy MIN_SAMPLES_TO_LEARN on their own.
        t = record(bgMgdl = 110.0, count = 40, suppressed = false, startMs = t)
        learner.recordBg(110.0, LOW_MGDL, HIGH_MGDL, MealMode.FASTING, false, t + HOUR_MS)

        // 40 in-range out of 40 scorable is 100% TIR with 0% high. If the 40 suppressed highs had
        // leaked in it would read 50% TIR / 50% high and step the score up instead.
        assertTrue(learner.aggressiveness <= 1.0,
                   "Clean in-range data must not be diluted by held lockout samples, got ${learner.aggressiveness}")
    }

    @Test
    fun `lows are still counted when not suppressed`() {
        // Direction check in the safety-critical direction: real fasting lows must always be able
        // to pull aggressiveness down.
        val t = record(bgMgdl = 60.0, count = 40, suppressed = false, startMs = BASE_MS)
        learner.recordBg(60.0, LOW_MGDL, HIGH_MGDL, MealMode.FASTING, false, t + HOUR_MS)

        assertTrue(learner.aggressiveness < 1.0,
                   "Fasting lows must reduce aggressiveness, got ${learner.aggressiveness}")
    }
}
