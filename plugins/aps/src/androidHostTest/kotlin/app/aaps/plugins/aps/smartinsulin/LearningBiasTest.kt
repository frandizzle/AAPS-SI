package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import app.aaps.core.interfaces.smartInsulin.MealMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The one dial across the meal/UAM learners. The rule that matters most is the asymmetry: reactive
 * may only make the learners chase highs harder, never make them slower to back off after a low.
 */
class LearningBiasTest {

    private lateinit var sp: FakePreferences

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() { sp = FakePreferences() }

    private fun setBias(b: LearningBias) = sp.edit { putInt("si_learning_bias", b.ordinal) }

    /** A learner with nothing learned yet, at [b]. Each scenario gets its own preferences, or the
     *  previous scenario's learned state would be restored into the next one. */
    private fun freshModeLearner(b: LearningBias): ModeIsfLearner {
        sp = FakePreferences()
        setBias(b)
        return ModeIsfLearner(sp, FakeAAPSLogger(collect = false))
    }

    // ── The scales themselves ────────────────────────────────────────────────

    @Test
    fun `insulin-removing steps never shrink, however reactive it is set`() {
        LearningBias.entries.forEach {
            assertTrue(it.safetyScale >= 1.0, "${it.label} weakened the safety response")
        }
    }

    @Test
    fun `reactive adds more and conservative adds less`() {
        assertTrue(LearningBias.VERY_REACTIVE.strengthenScale > 1.0)
        assertTrue(LearningBias.VERY_CONSERVATIVE.strengthenScale < 1.0)
        assertEquals(1.0, LearningBias.NEUTRAL.strengthenScale, 1e-9)
    }

    @Test
    fun `reactive lowers the bars and conservative raises them`() {
        assertTrue(LearningBias.VERY_REACTIVE.barScale < 1.0)
        assertTrue(LearningBias.VERY_CONSERVATIVE.barScale > 1.0)
        assertEquals(1.0, LearningBias.NEUTRAL.barScale, 1e-9)
    }

    @Test
    fun `an out-of-range stored value falls back to neutral`() {
        sp = FakePreferences()
        sp.edit { putInt("si_learning_bias", 9) }
        assertEquals(LearningBias.NEUTRAL, LearningBias.from(sp))
    }

    // ── What it does to a real episode ───────────────────────────────────────

    private fun endedHighEpisode(l: ModeIsfLearner, endBg: Double = 130.0) {
        l.onCycle(MealMode.DINNER, BASE_MS, 160.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, BASE_MS)
        var t = BASE_MS
        repeat(16) {
            t += CYCLE_MS
            l.onCycle(null, 0L, endBg, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, t)
        }
    }

    private fun lowEpisode(l: ModeIsfLearner) {
        l.onCycle(MealMode.LOW_CARB, BASE_MS, 160.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, BASE_MS)
        l.onCycle(MealMode.LOW_CARB, BASE_MS, 70.0, 100.0, true, 1.0, 0.0, 0.0, 50.0, 10.0, BASE_MS + CYCLE_MS)
        l.onCycle(null, 0L, 75.0, 100.0, false, 1.0, 0.0, 0.0, 50.0, 10.0, BASE_MS + 2 * CYCLE_MS)
    }

    @Test
    fun `reactive strengthens harder on the same ended-high episode`() {
        val neutral = freshModeLearner(LearningBias.NEUTRAL).also { endedHighEpisode(it) }.multiplier(MealMode.DINNER)
        val reactive = freshModeLearner(LearningBias.VERY_REACTIVE).also { endedHighEpisode(it) }.multiplier(MealMode.DINNER)
        assertTrue(reactive < neutral, "reactive $reactive should add more than neutral $neutral")
        assertEquals(1.0 - 0.025 * 1.6, reactive, 1e-9)
    }

    @Test
    fun `conservative strengthens more gently`() {
        val v = freshModeLearner(LearningBias.VERY_CONSERVATIVE).also { endedHighEpisode(it) }.multiplier(MealMode.DINNER)
        assertEquals(1.0 - 0.025 * 0.6, v, 1e-9)
    }

    @Test
    fun `reactive does not soften the response to a low`() {
        val neutral = freshModeLearner(LearningBias.NEUTRAL).also { lowEpisode(it) }.multiplier(MealMode.LOW_CARB)
        val reactive = freshModeLearner(LearningBias.VERY_REACTIVE).also { lowEpisode(it) }.multiplier(MealMode.LOW_CARB)
        assertEquals(neutral, reactive, 1e-9, "a low must be corrected the same however reactive it is set")
    }

    @Test
    fun `conservative corrects a low harder than neutral`() {
        val neutral = freshModeLearner(LearningBias.NEUTRAL).also { lowEpisode(it) }.multiplier(MealMode.LOW_CARB)
        val conservative = freshModeLearner(LearningBias.VERY_CONSERVATIVE).also { lowEpisode(it) }.multiplier(MealMode.LOW_CARB)
        assertTrue(conservative > neutral, "conservative $conservative should back off more than $neutral")
    }

    @Test
    fun `reactive acts on a smaller finish above target`() {
        // 1.0mmol over target at neutral; reactive's bar is 0.7mmol, so a 0.8mmol finish counts.
        val neutral = freshModeLearner(LearningBias.NEUTRAL).also { endedHighEpisode(it, endBg = 114.0) }.multiplier(MealMode.DINNER)
        assertEquals(1.0, neutral, 1e-9)
        val reactive = freshModeLearner(LearningBias.VERY_REACTIVE).also { endedHighEpisode(it, endBg = 114.0) }.multiplier(MealMode.DINNER)
        assertTrue(reactive < 1.0, "reactive should have acted on the smaller miss, got $reactive")
    }

    @Test
    fun `the DURA stall bar shortens when reactive`() {
        // A 50-minute stall is under the neutral hour, but over the reactive 42min.
        fun stall(bias: LearningBias): Double {
            sp = FakePreferences()
            setBias(bias)
            val l = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
            l.onCycle(MealMode.UAM_LUNCH, BASE_MS, false, 1.30, false, BASE_MS, 2.0)
            l.onCycle(MealMode.UAM_LUNCH, BASE_MS, true, 1.30, false, BASE_MS + CYCLE_MS, 2.0)  // a low first, to cut it
            var t = BASE_MS + 10 * CYCLE_MS
            val start = t
            var m = 0
            while (m <= 50) {
                l.onCycle(MealMode.UAM_LUNCH, start, false, 1.25, false, t, 2.0,
                          bgMgdl = 146.0, targetMgdl = 110.0, duraStuckMinutes = m + 10.0)
                t += CYCLE_MS; m += 5
            }
            l.onCycle(null, 0L, false, 1.0, false, t, 2.0)
            l.onCycle(null, 0L, false, 1.0, false, t + 106 * 60_000L, 2.0)
            return l.factor(MealMode.UAM_LUNCH)
        }
        val neutral = stall(LearningBias.NEUTRAL)
        val reactive = stall(LearningBias.VERY_REACTIVE)
        assertTrue(reactive > neutral, "reactive $reactive should have counted the 50min stall, neutral $neutral")
    }
}
