package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [DuraStrengthLearner] — strongly asymmetric learning of DURA strength.
 * Reduces ×0.85 when DURA engaged (≥1.10×) and the episode went low; ×0.94 when that low
 * came with an unexplained drop (exercise); creeps back ×1.03 when a DURA episode lands
 * cleanly. Never strengthens past the configured value. Railed 0.3–1.0.
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
        exercise: Boolean = false, baseSig: Double = 2.0,
        pfWindow: PfWindow = PfWindow.NONE
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, lowActive = low, duraMult = dura,
        exerciseSuspected = exercise, nowMs = nowMs, baseSignature = baseSig, pfWindow = pfWindow
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

    // ── Recovery from an over-correction ─────────────────────────────────────

    /** DURA engages, the mode ends, and the full tail passes with nothing going low. */
    private fun cleanDuraEpisode(mode: MealMode, startMs: Long, dura: Double = 1.30): Long {
        cycle(mode, startMs, startMs, dura = dura)
        var t = startMs + CYCLE_MS
        cycle(null, 0L, t)                       // mode ends, watch window opens
        t += 106 * 60_000L                       // past TAIL_MS (105min) with no low
        cycle(null, 0L, t)
        return t + CYCLE_MS
    }

    @Test
    fun `a clean DURA episode gives some strength back`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        assertEquals(0.85, learner.factor(MealMode.UAM_DINNER), 1e-9)

        val next = BASE_MS + 10 * CYCLE_MS
        cleanDuraEpisode(MealMode.UAM_DINNER, next)
        assertEquals(0.8755, learner.factor(MealMode.UAM_DINNER), 1e-9)   // 0.85 × 1.03
    }

    @Test
    fun `recovery never exceeds the configured strength`() {
        var t = BASE_MS
        repeat(4) { t = cleanDuraEpisode(MealMode.UAM_LUNCH, t + 10 * CYCLE_MS) }
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `five clean episodes unwind one crash`() {
        // The asymmetry that keeps the safety direction ahead: -15% a crash, +3% a clean landing.
        cycle(MealMode.UAM_SNACK, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_SNACK, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        var t = BASE_MS + 10 * CYCLE_MS
        repeat(5) { t = cleanDuraEpisode(MealMode.UAM_SNACK, t + 10 * CYCLE_MS) }
        assertEquals(0.985, learner.factor(MealMode.UAM_SNACK), 1e-3)     // 0.85 × 1.03^5 ≈ 0.985
    }

    @Test
    fun `an episode where DURA never engaged gives nothing back`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        assertEquals(0.85, learner.factor(MealMode.UAM_DINNER), 1e-9)

        // DURA below the 1.10 bar — this episode says nothing about DURA's strength either way,
        // so no watch window opens and the factor stays put.
        val next = BASE_MS + 10 * CYCLE_MS
        cleanDuraEpisode(MealMode.UAM_DINNER, next, dura = 1.02)
        assertEquals(0.85, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    // ── P/F per-window learning ──────────────────────────────────────────────

    @Test
    fun `P over F DURA windows learn independently`() {
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS, dura = 1.30, pfWindow = PfWindow.OVERNIGHT)
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30,
              pfWindow = PfWindow.OVERNIGHT)
        assertEquals(0.85, learner.factor(MealMode.UAM_PROTEIN_FAT, PfWindow.OVERNIGHT), 1e-9)
        assertEquals(1.0, learner.factor(MealMode.UAM_PROTEIN_FAT, PfWindow.DAY), 1e-9)
    }

    @Test
    fun `pre-split P over F DURA state seeds every window`() {
        sp.edit { putString("si_dura_strength_learner_state", """{"UAM_PROTEIN_FAT":{"factor":0.53,"n":22}}""") }
        val migrated = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        PfWindow.PF_WINDOWS.forEach { w ->
            assertEquals(0.53, migrated.factor(MealMode.UAM_PROTEIN_FAT, w), 1e-9)
        }
    }
}
