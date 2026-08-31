package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The blind spot that at-target auto-cancel exposed.
 *
 * A UAM/P-F mode that cancels the moment BG reaches target hands back a BG that looks fine and
 * an IOB tail that isn't. If BG then drifts to ~4.4 mmol it never crosses the low guard (4.0
 * mmol by default), so before these changes:
 *
 *   - [ModeIsfLearner] and [DuraStrengthLearner] saw no low at all, and the 75-min evaluation
 *     scored the episode "on target — no change";
 *   - and even a genuine sub-72 low could be missed, because the tail contamination check
 *     dropped the pending evaluation outright — and a fat tail is exactly what makes that check
 *     fire, on exactly the episodes most likely to end low.
 *
 * Covers the undershoot signal — the band above the low guard, sized by the caller — and the
 * non-skippable post-episode low watch.
 */
class ModeUndershootLearningTest {

    private lateinit var isf: ModeIsfLearner
    private lateinit var dura: DuraStrengthLearner

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    // With a 4.0 mmol low guard and a 5.5 mmol target the caller's band is 4.0–4.75 mmol.
    private val UNDERSHOOT_BG = 4.4 * 18.0
    private val LOW_BG        = 68.0

    @BeforeEach
    fun setUp() {
        isf  = ModeIsfLearner(FakePreferences(), FakeAAPSLogger(collect = false))
        dura = DuraStrengthLearner(FakePreferences(), FakeAAPSLogger(collect = false))
    }

    private fun isfCycle(
        mode: MealMode?, startMs: Long, nowMs: Long,
        bg: Double = 100.0, low: Boolean = false, undershoot: Boolean = false,
        dura: Double = 1.0, delta: Double = 0.0
    ) = isf.onCycle(
        activeModeNow = mode, modeStartMs = startMs, bgMgdl = bg, targetMgdl = 100.0,
        lowActive = low, duraMult = dura, deltaMgdl = delta, activityPerMin = 0.0,
        fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = nowMs, undershootActive = undershoot
    )

    private fun duraCycle(
        mode: MealMode?, startMs: Long, nowMs: Long,
        low: Boolean = false, undershoot: Boolean = false, duraMult: Double = 1.0
    ) = dura.onCycle(
        activeModeNow = mode, modeStartMs = startMs, lowActive = low, duraMult = duraMult,
        exerciseSuspected = false, nowMs = nowMs, undershootActive = undershoot
    )

    // ── ModeIsfLearner ───────────────────────────────────────────────────────

    @Test
    fun `a dip toward the low guard after the mode auto-cancels at target weakens the mode ISF`() {
        // P/F runs for half an hour and is cancelled at target — nothing low about it yet.
        var t = BASE_MS
        repeat(6) { isfCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, bg = 120.0); t += CYCLE_MS }
        isfCycle(null, 0L, t, bg = 100.0)   // cancelled at target, evaluation pending

        // 20 minutes later BG has drifted to 4.4 — above the low guard, so no other learner sees it.
        repeat(4) { t += CYCLE_MS; isfCycle(null, 0L, t, bg = UNDERSHOOT_BG, undershoot = true) }

        // Reduced step: real evidence of over-dosing, milder outcome than a hypo.
        assertEquals(1.025, isf.multiplier(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `a dip toward the low guard during the episode weakens at the reduced step`() {
        var t = BASE_MS
        repeat(8) { isfCycle(MealMode.LOW_CARB, BASE_MS, t, bg = 110.0); t += CYCLE_MS }
        isfCycle(MealMode.LOW_CARB, BASE_MS, t, bg = UNDERSHOOT_BG, undershoot = true)
        isfCycle(null, 0L, t + CYCLE_MS, bg = UNDERSHOOT_BG)   // mode ends — verdict lands at once
        assertEquals(1.025, isf.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `a frank low still outranks an undershoot and takes the full step`() {
        var t = BASE_MS
        repeat(8) { isfCycle(MealMode.LOW_CARB, BASE_MS, t, bg = 110.0); t += CYCLE_MS }
        isfCycle(MealMode.LOW_CARB, BASE_MS, t, bg = LOW_BG, low = true)
        isfCycle(null, 0L, t + CYCLE_MS, bg = LOW_BG)
        assertEquals(1.05, isf.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `a mode started inside the undershoot band is not blamed for its first cycles`() {
        // Pre-bolus taken at 4.4 mmol: BG is in the warn band before the mode can have done
        // anything, so the first 25 minutes carry no evidence about the dose.
        var t = BASE_MS
        repeat(4) { isfCycle(MealMode.BREAKFAST, BASE_MS, t, bg = UNDERSHOOT_BG, undershoot = true); t += CYCLE_MS }
        repeat(20) { t += CYCLE_MS; isfCycle(null, 0L, t, bg = 140.0) }  // rose after carbs; tail is clean
        assertEquals(0.975, isf.multiplier(MealMode.BREAKFAST), 1e-9)    // strengthened, not weakened
    }

    @Test
    fun `a low in the tail still weakens after contamination voided the evaluation`() {
        var t = BASE_MS
        isfCycle(MealMode.DINNER, BASE_MS, t, bg = 150.0)
        t += CYCLE_MS
        isfCycle(null, 0L, t, bg = 110.0)                     // mode ends: evaluation + watch open

        // delta +20 with no insulin activity reads as ~4g absorbing per cycle; three cycles is
        // past TAIL_CONTAMINATION_G and voids the evaluation.
        repeat(3) { t += CYCLE_MS; isfCycle(null, 0L, t, bg = 110.0, delta = 20.0) }
        assertEquals(1.0, isf.multiplier(MealMode.DINNER), 1e-9)

        // The low the voided evaluation would have thrown away. Half step — the episode is no
        // longer clean, but the safety direction still has to land.
        t += CYCLE_MS
        isfCycle(null, 0L, t, bg = LOW_BG, low = true)
        assertEquals(1.025, isf.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `the watch stops once the mode's insulin is long gone`() {
        var t = BASE_MS
        isfCycle(MealMode.DINNER, BASE_MS, t, bg = 150.0)
        t += CYCLE_MS
        isfCycle(null, 0L, t, bg = 110.0)

        // 105-min watch, 75-min evaluation: run well past both with BG sitting near target.
        repeat(23) { t += CYCLE_MS; isfCycle(null, 0L, t, bg = 105.0) }
        val afterEval = isf.multiplier(MealMode.DINNER)

        // A low two hours after the mode ended belongs to whatever came next, not to this mode.
        t += CYCLE_MS
        isfCycle(null, 0L, t, bg = LOW_BG, low = true)
        assertEquals(afterEval, isf.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `a new activation takes over the watch rather than weakening twice for one low`() {
        var t = BASE_MS
        isfCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, bg = 130.0)
        t += CYCLE_MS
        isfCycle(null, 0L, t, bg = 100.0)                     // cancelled at target

        val secondStart = t + CYCLE_MS
        var t2 = secondStart
        repeat(8) { isfCycle(MealMode.UAM_PROTEIN_FAT, secondStart, t2, bg = 120.0); t2 += CYCLE_MS }
        isfCycle(MealMode.UAM_PROTEIN_FAT, secondStart, t2, bg = UNDERSHOOT_BG, undershoot = true)
        isfCycle(null, 0L, t2 + CYCLE_MS, bg = UNDERSHOOT_BG, undershoot = true)

        // One weaken, from the second episode's own tracking — not two.
        assertEquals(1.025, isf.multiplier(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    // ── Where the band sits ──────────────────────────────────────────────────

    private fun ceiling(lowGuardMmol: Double, targetMmol: Double = 5.5) =
        SmartInsulinPlugin.undershootCeilingMgdl(lowGuardMmol * 18.0, targetMmol * 18.0) / 18.0

    @Test
    fun `the band covers the lower half of the room between guard and target`() {
        assertEquals(4.75, ceiling(lowGuardMmol = 4.0), 1e-9)   // 1.5 mmol of room → 0.75
        assertEquals(5.1,  ceiling(lowGuardMmol = 4.7), 1e-9)   // 0.8 mmol of room → 0.4
    }

    @Test
    fun `a guard close to target gets a narrow band, not one that overshoots target`() {
        // The case a fixed offset gets wrong: guard 4.7 with target 5.5 has only 0.8 mmol of room,
        // and a flat +1 mmol would put the ceiling at 5.7 — above target, where nothing is wrong.
        val c = ceiling(lowGuardMmol = 4.7, targetMmol = 5.5)
        assertTrue(c > 4.7, "the band must not be empty when there is room for it")
        assertTrue(c < 5.5, "the band must never reach target: $c")
    }

    @Test
    fun `the band never reaches target at any pair of settings`() {
        var g = 3.0
        while (g <= 5.4) {
            var t = g + 0.1
            while (t <= 8.0) {
                assertTrue(ceiling(g, t) < t, "guard $g target $t produced a ceiling at or above target")
                t += 0.1
            }
            g += 0.1
        }
    }

    @Test
    fun `a very high target cannot stretch the band up into normal BG`() {
        assertEquals(5.7, ceiling(lowGuardMmol = 4.7, targetMmol = 8.0), 1e-9)  // capped at +1 mmol
    }

    @Test
    fun `a target at or below the guard collapses the band back to frank lows only`() {
        assertTrue(ceiling(lowGuardMmol = 4.5, targetMmol = 4.5) <= 4.5,
                   "with target at the low guard there is no room for an undershoot band")
    }

    // ── DuraStrengthLearner ──────────────────────────────────────────────────

    @Test
    fun `a dip toward the low guard in the tail reduces DURA strength at the reduced step`() {
        var t = BASE_MS
        repeat(6) { duraCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, duraMult = 1.4); t += CYCLE_MS }
        duraCycle(null, 0L, t)                                // cancelled at target — watch opens
        t += CYCLE_MS
        duraCycle(null, 0L, t, undershoot = true)
        assertEquals(0.925, dura.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `a dip toward the low guard during the episode reduces DURA strength`() {
        var t = BASE_MS
        repeat(8) { duraCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, duraMult = 1.4); t += CYCLE_MS }
        duraCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, duraMult = 1.4, undershoot = true)
        assertEquals(0.925, dura.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `a dip toward the low guard in the first cycles of a DURA episode is ignored`() {
        var t = BASE_MS
        repeat(3) { duraCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, duraMult = 1.4, undershoot = true); t += CYCLE_MS }
        assertEquals(1.0, dura.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `DURA that never engaged is not blamed for a dip toward the low guard`() {
        var t = BASE_MS
        repeat(8) { duraCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, duraMult = 1.0); t += CYCLE_MS }
        duraCycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, t, duraMult = 1.0, undershoot = true)
        duraCycle(null, 0L, t + CYCLE_MS, undershoot = true)
        assertEquals(1.0, dura.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }
}
