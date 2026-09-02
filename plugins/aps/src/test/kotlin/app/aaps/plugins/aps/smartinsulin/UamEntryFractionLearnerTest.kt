package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [UamEntryFractionLearner] — the SHAPE knob (how front-loaded the UAM entry burst is),
 * as distinct from [ModeIsfLearner]'s MAGNITUDE knob.
 *
 * Steps: −0.06 on an early low; up when a big post-entry spike, or a peak well above target,
 * still ended on target — graduated with the overshoot from +0.03 to +0.06. No change when the
 * episode ended high or the low came late. Offsets railed ±0.25.
 * Entry attribution window and settling tail are both 75 min → 16 five-minute cycles.
 */
class UamEntryFractionLearnerTest {

    private lateinit var sp: FakePreferences
    private lateinit var learner: UamEntryFractionLearner

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        learner = UamEntryFractionLearner(sp, FakeAAPSLogger(collect = false))
    }

    private fun cycle(
        mode: MealMode?, startMs: Long, nowMs: Long,
        bg: Double = 100.0, target: Double = 100.0,
        low: Boolean = false, delta: Double = 0.0, activity: Double = 0.0,
        baseSig: Double = 0.8, exercise: Boolean = false
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, bgMgdl = bg, targetMgdl = target,
        lowActive = low, deltaMgdl = delta, activityPerMin = activity,
        fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = nowMs, baseSignature = baseSig,
        exerciseSuspected = exercise
    )

    /** Ends the episode and runs the full 75-min settling tail quietly at [bg]. */
    private fun runQuietTail(fromMs: Long, bg: Double, target: Double = 100.0): Long {
        var t = fromMs
        repeat(16) {  // 16 × 5min = 80min > 75min tail
            t += CYCLE_MS
            cycle(mode = null, startMs = 0L, nowMs = t, bg = bg, target = target)
        }
        return t
    }

    @Test
    fun `low soon after entry reduces the entry fraction`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0)  // mode ends → immediate weaken
        assertEquals(-0.06, learner.offset(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `an early low with an unexplained drop reduces the fraction at a smaller step`() {
        // Walk during the entry window: still reduces (safety direction) but 40% of the step,
        // -0.024 instead of -0.06, so exercise doesn't ratchet the entry burst down over time.
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true, exercise = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0)
        assertEquals(-0.024, learner.offset(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `late-peaking spike that still ends on target raises the entry fraction`() {
        // Peak 45min after entry — by then the entry SMB is contributing, so a bigger one
        // could plausibly have blunted the rise. That's a genuine shape problem.
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)  // excursion 60 ≥ 45
        runQuietTail(peakMs, bg = 105.0)                        // within the on-target margin
        // Graduated: 60/45 = 1.33× the bar → 0.03 × 1.33 = 0.04
        assertEquals(0.04, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `early-peaking spike is fast carbs, not a shape problem — entry fraction unchanged`() {
        // The high-GI case: BG tops out 10min after entry, long before a subcutaneous entry
        // SMB could act. Strengthening here wouldn't have prevented the spike — it would just
        // land after the glucose cleared and cause a late low (which ModeIsfLearner would then
        // "fix" by weakening ISF, leaving the two learners fighting).
        val peakMs = BASE_MS + 10 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 180.0)  // huge excursion, but too early
        runQuietTail(peakMs, bg = 105.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9,
                     "A spike that beat the entry insulin must not drive front-loading up")
    }

    @Test
    fun `big spike that ends HIGH is a magnitude problem — entry fraction unchanged`() {
        val peakMs = BASE_MS + 45 * 60_000L  // late peak, so only the ended-high gate can block
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)
        runQuietTail(peakMs, bg = 145.0)  // well above target+margin
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9,
                     "Ending high is ModeIsfLearner's signal — entry shape must not also move")
    }

    @Test
    fun `modest excursion ending on target leaves the entry fraction alone`() {
        cycle(MealMode.UAM_SNACK, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_SNACK, BASE_MS, BASE_MS + CYCLE_MS, bg = 120.0)  // excursion 20 < 45
        runQuietTail(BASE_MS + CYCLE_MS, bg = 102.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_SNACK), 1e-9)
    }

    @Test
    fun `a late low in the tail is left to the ISF learner — entry fraction unchanged`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 160.0)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 120.0)          // pending eval opens
        cycle(null, 0L, BASE_MS + 3 * CYCLE_MS, bg = 65.0, low = true)  // late low → skip
        runQuietTail(BASE_MS + 4 * CYCLE_MS, bg = 100.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `P slash F is not an entry mode and is never learned`() {
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `adjustedFraction applies the learned offset and clamps to sane bounds`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0)  // offset now -0.06

        assertEquals(0.74, learner.adjustedFraction(MealMode.UAM_DINNER, 0.8), 1e-9)
        // clamped at the floor, never below 0.1
        assertEquals(0.1, learner.adjustedFraction(MealMode.UAM_DINNER, 0.12), 1e-9)
        // non-entry modes pass through untouched
        assertEquals(0.8, learner.adjustedFraction(MealMode.DINNER, 0.8), 1e-9)
    }

    @Test
    fun `weaken steps are railed at the minimum offset`() {
        var t = BASE_MS
        repeat(10) {
            val start = t  // keep startMs stable across the episode's cycles
            cycle(MealMode.UAM_DINNER, start, t, bg = 130.0)
            t += CYCLE_MS
            cycle(MealMode.UAM_DINNER, start, t, bg = 70.0, low = true)
            t += CYCLE_MS
            cycle(null, 0L, t, bg = 80.0)
            t += CYCLE_MS
        }
        assertEquals(-0.25, learner.offset(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `changing the configured entry fraction resets the learned offset`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0, baseSig = 0.8)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true, baseSig = 0.8)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0, baseSig = 0.8)
        assertEquals(-0.06, learner.offset(MealMode.UAM_DINNER), 1e-9)

        val newStart = BASE_MS + 6 * 60 * 60_000L
        cycle(MealMode.UAM_DINNER, newStart, newStart, bg = 120.0, baseSig = 0.6)
        assertEquals(0.0, learner.offset(MealMode.UAM_DINNER), 1e-9,
                     "A changed configured fraction must reset the learned offset")
    }

    @Test
    fun `learned state survives persistence round-trip`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0)

        val restored = UamEntryFractionLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(-0.06, restored.offset(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `a peak well above target raises the fraction even when the rise from entry was modest`() {
        // The case the relative bar misses: UAM can't fire until BG has been climbing ~15min,
        // so entry at 7.0 (126) already hides most of the spike. Topping out at 9.0 (162) books
        // only 36mg/dl of excursion — under the 45 bar — but it is exactly the episode the entry
        // burst exists to prevent, and 162 is 63 above a 99 target.
        val peakMs = BASE_MS + 50 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 126.0, target = 99.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 162.0, target = 99.0)
        runQuietTail(peakMs, bg = 105.0, target = 99.0)
        // Graduated off the peak bar: 63/54 = 1.17× → 0.03 × 1.17 = 0.035
        assertEquals(0.035, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a mode that fired high and barely moved is not a front-loading problem`() {
        // Peak is far above target, but the entry burst had almost nothing to blunt — that's
        // late detection or magnitude, and a bigger first SMB doesn't fix either.
        val peakMs = BASE_MS + 50 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 160.0, target = 99.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 178.0, target = 99.0)  // excursion 18 < 27
        runQuietTail(peakMs, bg = 105.0, target = 99.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `the strengthen step scales with the overshoot and is capped at the weaken step`() {
        // 3× the excursion bar would be 0.09; the cap holds it to 0.06 so front-loading never
        // ratchets up faster than an early low pulls it back down.
        val peakMs = BASE_MS + 50 * 60_000L
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, peakMs, bg = 235.0)  // excursion 135 = 3× the bar
        runQuietTail(peakMs, bg = 105.0)
        assertEquals(0.06, learner.offset(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `a plateau at the ceiling counts as a late peak, not fast carbs`() {
        // BG tops out 20min after entry and then just sits there for the rest of the entry
        // window. Dating the peak to its first touch would call this fast carbs; sitting at the
        // ceiling is the opposite — insulin that never caught up.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + 20 * 60_000L, bg = 160.0)
        var t = BASE_MS + 20 * 60_000L
        repeat(9) {  // out to 65min after entry, drifting within the plateau tolerance
            t += CYCLE_MS
            cycle(MealMode.UAM_LUNCH, BASE_MS, t, bg = 158.0)
        }
        runQuietTail(t, bg = 105.0)
        assertEquals(0.04, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a real descent from the peak leaves the peak time behind`() {
        // Same early top-out, but BG genuinely comes down afterwards — the entry insulin did
        // act, the carbs simply beat it. Still the fast-carb branch.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + 20 * 60_000L, bg = 160.0)
        var t = BASE_MS + 20 * 60_000L
        var bg = 160.0
        repeat(9) {
            t += CYCLE_MS
            bg -= 5.0
            cycle(MealMode.UAM_LUNCH, BASE_MS, t, bg = bg)
        }
        runQuietTail(t, bg = 105.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `ending high is reported as a magnitude signal, not as shape OK`() {
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)
        runQuietTail(peakMs, bg = 145.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastReason(MealMode.UAM_LUNCH).contains("left to mode ISF"),
                   "An ended-high episode must not be reported as a clean entry shape")
    }

    @Test
    fun `the per-mode reason survives persistence`() {
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)
        runQuietTail(peakMs, bg = 105.0)

        val restored = UamEntryFractionLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(learner.lastReason(MealMode.UAM_LUNCH), restored.lastReason(MealMode.UAM_LUNCH))
        assertTrue(restored.lastReason(MealMode.UAM_LUNCH).isNotEmpty())
    }

    @Test
    fun `a railed entry fraction hands the evidence to the ISF learner instead of moving`() {
        // Configured at the ceiling: the first SMBs already carry the whole computed
        // requirement, so there is no more shape to give and the offset must not pretend
        // otherwise. The episode is real evidence, so it goes to magnitude.
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0, baseSig = 1.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0, baseSig = 1.0)
        runQuietTail(peakMs, bg = 105.0)

        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9,
                     "A railed fraction must not accumulate dead offset")
        val handoff = learner.consumeMagnitudeHandoff()
        assertEquals(MealMode.UAM_LUNCH, handoff?.first)
        assertEquals(BASE_MS, handoff?.second)
        assertTrue(learner.lastReason(MealMode.UAM_LUNCH).contains("handed to mode ISF"))
    }

    @Test
    fun `the handoff is consumed once`() {
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0, baseSig = 1.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0, baseSig = 1.0)
        runQuietTail(peakMs, bg = 105.0)

        assertNotNull(learner.consumeMagnitudeHandoff())
        assertNull(learner.consumeMagnitudeHandoff(), "A handoff must never be forwarded twice")
    }

    @Test
    fun `a fraction with headroom moves itself and hands nothing over`() {
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0, baseSig = 0.8)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0, baseSig = 0.8)
        runQuietTail(peakMs, bg = 105.0)

        assertEquals(0.04, learner.offset(MealMode.UAM_LUNCH), 1e-9)
        assertNull(learner.consumeMagnitudeHandoff())
    }

    @Test
    fun `a railed fraction still hands nothing over when the episode was not a shape problem`() {
        // Ended high — magnitude owns it through its own test, not through the handoff.
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0, baseSig = 1.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0, baseSig = 1.0)
        runQuietTail(peakMs, bg = 145.0)
        assertNull(learner.consumeMagnitudeHandoff())
    }

    @Test
    fun `a railed fraction can still be pulled back down by an early low`() {
        // The rail is one-directional: it blocks strengthening, never the safety direction.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 130.0, baseSig = 1.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true, baseSig = 1.0)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0, baseSig = 1.0)
        assertEquals(-0.06, learner.offset(MealMode.UAM_LUNCH), 1e-9)
        assertEquals(0.94, learner.adjustedFraction(MealMode.UAM_LUNCH, 1.0), 1e-9)
    }
}
