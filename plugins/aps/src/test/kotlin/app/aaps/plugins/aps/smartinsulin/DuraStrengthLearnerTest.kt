package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [DuraStrengthLearner] — crash-direction-only learning of DURA strength.
 * Reduces ×0.85 when DURA engaged (≥1.10×) and the episode went low; ×0.94 when that low
 * came with an unexplained drop (exercise). Never increases. Railed 0.3–1.0.
 */
class DuraStrengthLearnerTest {

    private lateinit var sp: FakePreferences
    private lateinit var learner: DuraStrengthLearner

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        learner = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
    }

    private fun cycle(
        mode: MealMode?, startMs: Long, nowMs: Long,
        low: Boolean = false, dura: Double = 1.0,
        exercise: Boolean = false, baseSig: Double = 2.0
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, lowActive = low, duraMult = dura,
        exerciseSuspected = exercise, nowMs = nowMs, baseSignature = baseSig
    )

    @Test
    fun `a crash while DURA was engaged reduces its strength`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        assertEquals(0.85, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `a crash with DURA barely engaged is not attributed to DURA`() {
        // 1.05 is below the 1.10 "actually did something" threshold
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.05)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.05)
        assertEquals(1.0, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `a crash in the tail after a DURA episode still counts`() {
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS, dura = 1.40)
        cycle(null, 0L, BASE_MS + CYCLE_MS)                        // mode ends, watch window opens
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, low = true)         // crash lands in the tail
        assertEquals(0.85, learner.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `staying stuck high never raises the strength`() {
        // DURA engaged hard, no low at all — ModeIsfLearner owns the "too weak" direction.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.50)
        var t = BASE_MS
        repeat(17) {
            t += CYCLE_MS
            cycle(null, 0L, t)
        }
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9,
                     "This learner must never increase DURA strength")
    }

    @Test
    fun `an exercise-flagged crash reduces at a smaller step`() {
        // 1.0 - (1.0-0.85)*0.4 = 0.94
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30, exercise = true)
        assertEquals(0.94, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `reduction is railed at the minimum factor`() {
        var t = BASE_MS
        repeat(15) {
            val start = t
            cycle(MealMode.UAM_DINNER, start, t, dura = 1.40)
            t += CYCLE_MS
            cycle(MealMode.UAM_DINNER, start, t, low = true, dura = 1.40)
            t += CYCLE_MS
            cycle(null, 0L, t)
            t += CYCLE_MS
        }
        assertEquals(0.3, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `changing the configured DURA strength resets the learned factor`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30, baseSig = 2.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30, baseSig = 2.0)
        assertEquals(0.85, learner.factor(MealMode.UAM_DINNER), 1e-9)

        val newStart = BASE_MS + 6 * 60 * 60_000L
        cycle(MealMode.UAM_DINNER, newStart, newStart, baseSig = 3.0)
        assertEquals(1.0, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `learned state survives persistence round-trip`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)

        val restored = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(0.85, restored.factor(MealMode.UAM_DINNER), 1e-9)
        assertEquals(1, restored.episodeCount(MealMode.UAM_DINNER))
    }

    @Test
    fun `null mode returns a neutral factor`() {
        assertEquals(1.0, learner.factor(null), 1e-9)
    }
}
