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
 * Steps: −0.06 on an early low (×1.5 when it reverses a run of slow-return raises); up when a big
 * post-entry spike, or a peak well above target, still ended on target — graduated with the
 * overshoot from +0.03 to +0.06; up again when the episode ended over target and FLAT, which means
 * nothing was still bringing BG down. Still falling at the check → no change, which is where a
 * fat/protein tail lands. No change on a late low. Offsets railed ±0.9; the fraction itself rails
 * at 0.1..1.0.
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
        baseSig: Double = 0.8, exercise: Boolean = false, peakMins: Double = 55.0,
        boost: Boolean = false
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, bgMgdl = bg, targetMgdl = target,
        lowActive = low, deltaMgdl = delta, activityPerMin = activity,
        fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = nowMs, baseSignature = baseSig,
        exerciseSuspected = exercise, insulinPeakMins = peakMins, newPodBoost = boost
    )

    /** Ends the episode and runs the full 75-min settling tail quietly at [bg] — flat by default,
     *  or falling at [delta] mg/dL per 5 min. */
    private fun runQuietTail(fromMs: Long, bg: Double, target: Double = 100.0, delta: Double = 0.0): Long {
        var t = fromMs
        repeat(16) {  // 16 × 5min = 80min > 75min tail
            t += CYCLE_MS
            cycle(mode = null, startMs = 0L, nowMs = t, bg = bg, target = target, delta = delta)
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
    fun `ending high while still coming down leaves the entry fraction alone`() {
        // The fat/protein shape: over target at the check, but resolving on its own. Where BG
        // happens to be right now says nothing about the entry burst.
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)
        runQuietTail(peakMs, bg = 145.0, delta = -2.0)   // 0.11 mmol/5min, past the still-falling bar
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastReason(MealMode.UAM_LUNCH).contains("still falling"),
                   learner.lastReason(MealMode.UAM_LUNCH))
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
        assertEquals(-0.6, learner.offset(MealMode.UAM_DINNER), 1e-9)   // 10 × −0.06
        // What actually bounds it is the fraction: 0.8 configured − 0.6 = 0.2, still over the rail.
        assertEquals(0.2, learner.adjustedFraction(MealMode.UAM_DINNER, 0.8), 1e-9)
    }

    @Test
    fun `the fraction can now be learned all the way down to its floor`() {
        var t = BASE_MS
        repeat(20) {
            val start = t
            cycle(MealMode.UAM_DINNER, start, t, bg = 130.0)
            t += CYCLE_MS
            cycle(MealMode.UAM_DINNER, start, t, bg = 70.0, low = true)
            t += CYCLE_MS
            cycle(null, 0L, t, bg = 80.0)
            t += CYCLE_MS
        }
        assertEquals(0.1, learner.adjustedFraction(MealMode.UAM_DINNER, 0.8), 1e-9)
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
        // so entry at 8.0 (144) already hides most of the spike. Topping out at 10.0 (180) books
        // only 36mg/dl of excursion — under the 45 bar — but it is exactly the episode the entry
        // burst exists to prevent, and 180 is 81 above a 99 target.
        val peakMs = BASE_MS + 50 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 144.0, target = 99.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 180.0, target = 99.0)
        runQuietTail(peakMs, bg = 105.0, target = 99.0)
        // Graduated off the peak bar: 81/72 = 1.125× → 0.03 × 1.125 = 0.03375
        assertEquals(0.03375, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a peak 3_5 mmol over target is an ordinary unannounced meal`() {
        // 162 is 63 over a 99 target: past the old 3 mmol bar, under the 4 mmol one.
        val peakMs = BASE_MS + 50 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 126.0, target = 99.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 162.0, target = 99.0)
        runQuietTail(peakMs, bg = 105.0, target = 99.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
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
    fun `ending high and flat is never reported as a clean entry shape`() {
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)
        runQuietTail(peakMs, bg = 145.0)
        assertTrue(learner.lastReason(MealMode.UAM_LUNCH).contains("nothing still bringing it down"),
                   learner.lastReason(MealMode.UAM_LUNCH))
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
        // Ended high but still coming down — nothing is proven about either knob.
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0, baseSig = 1.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0, baseSig = 1.0)
        runQuietTail(peakMs, bg = 145.0, delta = -2.0)
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


    // ── Slow return: still over target with nothing bringing it down ─────────

    /** Entry, a rise to [peak], then the mode ends and the tail runs at [endBg] with [delta]. */
    private fun episode(mode: MealMode, peak: Double, endBg: Double, delta: Double = 0.0,
                        baseSig: Double = 0.8, start: Long = BASE_MS): Long {
        cycle(mode, start, start, bg = 100.0, baseSig = baseSig)
        cycle(mode, start, start + 45 * 60_000L, bg = peak, baseSig = baseSig)
        return runQuietTail(start + 45 * 60_000L, bg = endBg, delta = delta)
    }

    @Test
    fun `ending over target and flat raises the entry fraction`() {
        // The coffee morning: rose after entry, stalled above target, nothing still working on it.
        episode(MealMode.UAM_BREAKFAST, peak = 155.0, endBg = 125.0)
        assertEquals(0.03 * 25.0 / 18.0, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)
    }

    @Test
    fun `the slow-return raise is capped however far over target it stalled`() {
        episode(MealMode.UAM_BREAKFAST, peak = 220.0, endBg = 200.0)
        assertEquals(0.06, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)
    }

    @Test
    fun `a mode that barely rose after entry is not charged for ending high`() {
        // BG was already up and stayed there: nothing about the entry burst is proven.
        cycle(MealMode.UAM_BREAKFAST, BASE_MS, BASE_MS, bg = 130.0)
        cycle(MealMode.UAM_BREAKFAST, BASE_MS, BASE_MS + 45 * 60_000L, bg = 140.0)
        runQuietTail(BASE_MS + 45 * 60_000L, bg = 135.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)
        assertTrue(learner.lastReason(MealMode.UAM_BREAKFAST).contains("barely rose"),
                   learner.lastReason(MealMode.UAM_BREAKFAST))
    }

    @Test
    fun `a P over F takeover is judged during P over F, not after its whole tail`() {
        // The verdict lands while P/F is still running — well before the mode-end + 75min
        // deadline — but only once the entry insulin has peaked.
        val start = BASE_MS
        cycle(MealMode.UAM_BREAKFAST, start, start, bg = 100.0)
        cycle(MealMode.UAM_BREAKFAST, start, start + 45 * 60_000L, bg = 155.0)
        var t = start + 45 * 60_000L
        // Mode ends, three flat readings, then P/F takes over at 8.0mmol-ish (144).
        repeat(3) { t += CYCLE_MS; cycle(null, 0L, t, bg = 144.0) }
        repeat(6) { t += CYCLE_MS; cycle(MealMode.UAM_PROTEIN_FAT, t, t, bg = 144.0) }
        assertTrue(t - start < 45 * 60_000L + 75 * 60_000L, "must land before the tail deadline")
        assertEquals(0.06, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)   // 0.03 × 44/18, capped
        assertTrue(learner.lastReason(MealMode.UAM_BREAKFAST).contains("handed over to P/F"),
                   learner.lastReason(MealMode.UAM_BREAKFAST))
    }

    @Test
    fun `a P over F takeover while BG is still falling changes nothing`() {
        val start = BASE_MS
        cycle(MealMode.UAM_BREAKFAST, start, start, bg = 100.0)
        cycle(MealMode.UAM_BREAKFAST, start, start + 45 * 60_000L, bg = 155.0)
        var t = start + 45 * 60_000L
        repeat(3) { t += CYCLE_MS; cycle(null, 0L, t, bg = 144.0, delta = -3.0) }
        t += CYCLE_MS
        cycle(MealMode.UAM_PROTEIN_FAT, t, t, bg = 144.0, delta = -3.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)
    }

    @Test
    fun `a railed fraction hands a slow return to the ISF learner`() {
        episode(MealMode.UAM_LUNCH, peak = 155.0, endBg = 125.0, baseSig = 1.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
        assertNotNull(learner.consumeMagnitudeHandoff())
    }

    @Test
    fun `repeated slow-return raises say so once the run gets long`() {
        var t = BASE_MS
        repeat(3) { t = episode(MealMode.UAM_BREAKFAST, peak = 155.0, endBg = 125.0, start = t + CYCLE_MS) }
        assertTrue(learner.lastReason(MealMode.UAM_BREAKFAST).contains("in a row with no low"),
                   learner.lastReason(MealMode.UAM_BREAKFAST))
    }

    @Test
    fun `a low reverses a run of slow-return raises at a bigger step`() {
        var t = BASE_MS
        repeat(2) { t = episode(MealMode.UAM_BREAKFAST, peak = 155.0, endBg = 125.0, start = t + CYCLE_MS) }
        val raised = learner.offset(MealMode.UAM_BREAKFAST)
        val start = t + CYCLE_MS
        cycle(MealMode.UAM_BREAKFAST, start, start, bg = 130.0)
        cycle(MealMode.UAM_BREAKFAST, start, start + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, start + 2 * CYCLE_MS, bg = 80.0)
        assertEquals(raised - 0.09, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)  // 0.06 × 1.5
    }

    @Test
    fun `a low with no slow-return run behind it uses the normal step`() {
        val start = BASE_MS
        cycle(MealMode.UAM_BREAKFAST, start, start, bg = 130.0)
        cycle(MealMode.UAM_BREAKFAST, start, start + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, start + 2 * CYCLE_MS, bg = 80.0)
        assertEquals(-0.06, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)
    }


    // ── When the verdict may be delivered ────────────────────────────────────

    /**
     * A 45-minute breakfast that hands over to P/F the moment it ends. [handoverGapMin] is how
     * long P/F runs before the check is attempted.
     */
    private fun shortModeHandingOver(peakMins: Double, handoverMin: Int): Long {
        val start = BASE_MS
        cycle(MealMode.UAM_BREAKFAST, start, start, bg = 100.0, peakMins = peakMins)
        cycle(MealMode.UAM_BREAKFAST, start, start + 30 * 60_000L, bg = 155.0, peakMins = peakMins)
        var t = start + 45 * 60_000L
        cycle(null, 0L, t, bg = 144.0)                         // mode ends at 45min
        repeat(handoverMin / 5) {
            t += CYCLE_MS
            cycle(MealMode.UAM_PROTEIN_FAT, t, t, bg = 144.0)  // P/F running, BG flat and high
        }
        return t
    }

    @Test
    fun `a short mode is not judged while its entry insulin is still climbing`() {
        // 45-min mode, peak 55min: at 60min past entry the dose is nowhere near done acting.
        shortModeHandingOver(peakMins = 55.0, handoverMin = 15)
        assertEquals(0.0, learner.offset(MealMode.UAM_BREAKFAST), 1e-9,
                     "Flat BG before the insulin has peaked says nothing about the dose")
    }

    @Test
    fun `the verdict lands once the entry insulin has had its peak effect`() {
        // Same episode carried past entry + peak(55) + margin(15) = 70min.
        shortModeHandingOver(peakMins = 55.0, handoverMin = 45)
        assertEquals(0.06, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)   // 0.03 × 44/18, capped
        assertTrue(learner.lastReason(MealMode.UAM_BREAKFAST).contains("handed over to P/F"),
                   learner.lastReason(MealMode.UAM_BREAKFAST))
    }

    @Test
    fun `a faster learned peak brings the verdict forward, but never inside 75 minutes`() {
        // Peak 30min + 15 margin = 45min, floored at the 75-minute minimum after entry.
        shortModeHandingOver(peakMins = 30.0, handoverMin = 20)   // 65min after entry
        assertEquals(0.0, learner.offset(MealMode.UAM_BREAKFAST), 1e-9)
        shortModeHandingOver(peakMins = 30.0, handoverMin = 35)   // 80min after entry
        assertTrue(learner.offset(MealMode.UAM_BREAKFAST) > 0.0)
    }

    // ── new pod boost ────────────────────────────────────────────────────────

    @Test
    fun `an early low during a boosted UAM is not blamed on the entry fraction`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0, boost = true)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true)  // low ended the boost
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 80.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_DINNER), 1e-9)
        assertTrue(learner.lastReason(MealMode.UAM_DINNER).contains("new pod boost"),
                   learner.lastReason(MealMode.UAM_DINNER))
    }

    @Test
    fun `a boost that starts in the tail drops the waiting verdict`() {
        val peakMs = BASE_MS + 45 * 60_000L
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 100.0)
        cycle(MealMode.UAM_LUNCH, BASE_MS, peakMs, bg = 160.0)
        cycle(null, 0L, peakMs + CYCLE_MS, bg = 120.0, boost = true)
        runQuietTail(peakMs + CYCLE_MS, bg = 105.0)
        assertEquals(0.0, learner.offset(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `the next UAM after a boosted one is judged as normal`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, bg = 130.0, boost = true)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 100.0)
        val t = BASE_MS + 10 * CYCLE_MS
        cycle(MealMode.UAM_DINNER, t, t, bg = 130.0)
        cycle(MealMode.UAM_DINNER, t, t + CYCLE_MS, bg = 70.0, low = true)
        cycle(null, 0L, t + 2 * CYCLE_MS, bg = 80.0)
        assertEquals(-0.06, learner.offset(MealMode.UAM_DINNER), 1e-9)
    }
}
