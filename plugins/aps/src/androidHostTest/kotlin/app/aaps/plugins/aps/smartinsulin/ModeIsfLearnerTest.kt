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
        baseSig: Double = 0.0, exercise: Boolean = false,
        undershoot: Boolean = false, railed: Boolean = false,
        pfWindow: PfWindow = PfWindow.NONE, learning: Boolean = true
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, bgMgdl = bg, targetMgdl = target,
        lowActive = low, duraMult = dura, deltaMgdl = delta, activityPerMin = activity,
        fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = nowMs, baseSignature = baseSig,
        exerciseSuspected = exercise, undershootActive = undershoot, entryShapeRailed = railed,
        pfWindow = pfWindow, learningEnabled = learning
    )

    /** Runs the full 75-min settling tail quietly (flat BG at [bg]), ending past the deadline. */
    private fun runQuietTail(fromMs: Long, bg: Double, target: Double = 100.0, delta: Double = 0.0): Long {
        var t = fromMs
        repeat(16) {  // 16 × 5min = 80min > 75min tail
            t += CYCLE_MS
            cycle(mode = null, startMs = 0L, nowMs = t, bg = bg, target = target, delta = delta)
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
    fun `needing DURA no longer strengthens the mode ISF`() {
        // The stall after the spike is DURA's to fix; strengthening the spike knob for it stacked
        // a stronger meal response on top of DURA and caused lows.
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 150.0, dura = 1.25)
        runQuietTail(BASE_MS, bg = 102.0)
        assertEquals(1.0, learner.multiplier(MealMode.DINNER), 1e-9)
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

    @Test
    fun `a shape handoff strengthens an episode this learner would otherwise ignore`() {
        // Big spike, clean landing: this learner's own test never fires (it only strengthens on
        // ending HIGH), and the entry learner is railed. Without the handoff the mode is stuck.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 160.0)
        val end = runQuietTail(BASE_MS + CYCLE_MS, bg = 100.0)
        assertEquals(1.0, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)

        learner.noteShapeRailed(MealMode.UAM_LUNCH, BASE_MS)
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(end > BASE_MS)
    }

    @Test
    fun `a shape handoff for an episode already learned from is ignored`() {
        // This episode ended high, so the multiplier already moved once for it. The handoff must
        // not take a second bite out of the same evidence.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 160.0)
        runQuietTail(BASE_MS + CYCLE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)

        learner.noteShapeRailed(MealMode.UAM_LUNCH, BASE_MS)
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9,
                     "One episode, one correction — the handoff is a fallback, not a second helping")
    }

    @Test
    fun `a shape handoff for a later episode still lands`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, bg = 160.0)
        runQuietTail(BASE_MS + CYCLE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)

        learner.noteShapeRailed(MealMode.UAM_LUNCH, BASE_MS + 6 * 60 * 60_000L)
        assertEquals(0.975 * 0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    // ── Late spike with the shape knob railed ────────────────────────────────

    // This learner judges where BG LANDS, never how far it went first. A meal that runs 4.7→9.7
    // and then settles just under target booked exactly the same weaken as one that never rose —
    // which walks a mode steadily weaker while its spikes get worse. When the entry fraction is
    // already at its ceiling there is no front-loading left to fix the timing with, so the spike
    // stops being read as over-dosing.

    /**
     * Runs one episode: rises to [peak], holds it there for [holdCycles] readings, then lands in
     * the undershoot band (or goes low) well past both the entry-attribution window and the
     * 25-min undershoot minimum, and ends.
     *
     * [holdCycles] is the knob that separates a spike the loop is handling from one it isn't:
     * 1 is a peak passing through, 7 is BG parked up there for half an hour.
     */
    private fun spikeThenLand(
        peak: Double, railed: Boolean, low: Boolean = false, exercise: Boolean = false,
        holdCycles: Int = 7
    ) {
        val start = BASE_MS
        var t = start
        cycle(mode = MealMode.UAM_LUNCH, startMs = start, nowMs = t, bg = 100.0, railed = railed)
        repeat(holdCycles) {
            t += CYCLE_MS
            cycle(mode = MealMode.UAM_LUNCH, startMs = start, nowMs = t, bg = peak, railed = railed)
        }
        // Past ENTRY_ATTRIBUTION_MS (75min) so a low counts as this learner's, and past the
        // 25-min undershoot minimum.
        repeat(18) { t += CYCLE_MS }
        cycle(mode = MealMode.UAM_LUNCH, startMs = start, nowMs = t,
              bg = if (low) 70.0 else 93.0, low = low, undershoot = !low,
              railed = railed, exercise = exercise)
        t += CYCLE_MS
        cycle(mode = null, startMs = 0L, nowMs = t, bg = if (low) 70.0 else 93.0)
    }

    @Test
    fun `a spike that settles just under target with the shape knob railed strengthens`() {
        spikeThenLand(peak = 160.0, railed = true)   // +60 over target, past the ~3mmol bar
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("late, not too much"), learner.lastOutcome)
    }

    @Test
    fun `the same spike still weakens while the fraction has headroom left`() {
        // Shape can still be fixed where it belongs, so this learner must not also act.
        spikeThenLand(peak = 160.0, railed = false)
        assertEquals(1.025, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `an undershoot with no spike still weakens even when railed`() {
        spikeThenLand(peak = 110.0, railed = true)   // +10 over target — nothing like a spike
        assertEquals(1.025, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a hard low after a late spike still weakens, at half the step`() {
        // Reaching the low guard means the total really was too much, whenever it arrived.
        // Strengthening from there would deepen the next one — only the step softens.
        spikeThenLand(peak = 160.0, railed = true, low = true)
        assertEquals(1.025, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("weakened"), learner.lastOutcome)
    }

    @Test
    fun `a hard low with no spike still takes the full weaken`() {
        spikeThenLand(peak = 110.0, railed = true, low = true)
        assertEquals(1.05, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a suspected-exercise undershoot after a spike is not read as a timing failure`() {
        // BG falling faster than insulin explains says nothing about front-loading.
        spikeThenLand(peak = 160.0, railed = true, exercise = true)
        assertEquals(1.01, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a spike that turns straight back is the system working, not under-dosing`() {
        // UAM cannot fire until BG is already climbing, so every episode spikes. One reading at
        // the peak and then a fall is late-but-sufficient insulin — strengthening on that would
        // ratchet the mode up meal after meal for doing its job.
        spikeThenLand(peak = 160.0, railed = true, holdCycles = 1)
        assertEquals(1.025, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a spike held just under the sustained window does not qualify`() {
        // 5 readings = 25min, under the 30min bar.
        spikeThenLand(peak = 160.0, railed = true, holdCycles = 5)
        assertEquals(1.025, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a normal peak held a long time still does not qualify`() {
        // 7.6mmol against a 5.6 target — the shape of a meal the loop is handling. It never
        // reaches the bar, so how long it sits there is irrelevant.
        spikeThenLand(peak = 137.0, railed = true, holdCycles = 12)
        assertEquals(1.025, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    // ── P/F per-window learning ──────────────────────────────────────────────

    // P/F is the one mode whose configured ISF is time-of-day dependent, and it is detected from a
    // stuck-high plateau, which skews overnight. One shared multiplier meant a correction earned
    // overnight was applied to daytime P/F too.

    /** A P/F episode in [window] that ends high enough to strengthen. */
    private fun pfEpisode(window: PfWindow, startMs: Long): Long {
        var t = startMs
        cycle(mode = MealMode.UAM_PROTEIN_FAT, startMs = startMs, nowMs = t, bg = 100.0, pfWindow = window)
        t += CYCLE_MS
        cycle(mode = null, startMs = 0L, nowMs = t, bg = 150.0)        // ends, tail opens
        repeat(16) { t += CYCLE_MS; cycle(mode = null, startMs = 0L, nowMs = t, bg = 150.0) }
        return t + CYCLE_MS
    }

    @Test
    fun `P over F windows learn independently`() {
        var t = BASE_MS
        t = pfEpisode(PfWindow.OVERNIGHT, t)
        t = pfEpisode(PfWindow.OVERNIGHT, t + 10 * CYCLE_MS)

        assertEquals(0.975 * 0.975, learner.multiplier(MealMode.UAM_PROTEIN_FAT, PfWindow.OVERNIGHT), 1e-9)
        assertEquals(1.0, learner.multiplier(MealMode.UAM_PROTEIN_FAT, PfWindow.DAY), 1e-9)
        assertEquals(1.0, learner.multiplier(MealMode.UAM_PROTEIN_FAT, PfWindow.NIGHT), 1e-9)
        assertEquals(2, learner.episodeCount(MealMode.UAM_PROTEIN_FAT, PfWindow.OVERNIGHT))
        assertEquals(0, learner.episodeCount(MealMode.UAM_PROTEIN_FAT, PfWindow.DAY))
    }

    @Test
    fun `a non-P over F mode is unaffected by the window split`() {
        // Everything else keys to its plain enum name, exactly as before.
        var t = BASE_MS
        cycle(mode = MealMode.UAM_LUNCH, startMs = BASE_MS, nowMs = t, bg = 100.0)
        t += CYCLE_MS
        repeat(17) { cycle(mode = null, startMs = 0L, nowMs = t, bg = 150.0); t += CYCLE_MS }
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `pre-split P over F state seeds every window`() {
        // A pooled multiplier learned before the split is the best starting estimate for each
        // window — throwing it away and restarting at 1.0 would discard real history.
        sp.edit { putString("si_mode_isf_learner_state", """{"UAM_PROTEIN_FAT":{"mult":1.164,"n":35}}""") }
        val migrated = ModeIsfLearner(sp, FakeAAPSLogger(collect = false))
        PfWindow.PF_WINDOWS.forEach { w ->
            assertEquals(1.164, migrated.multiplier(MealMode.UAM_PROTEIN_FAT, w), 1e-9)
            assertEquals(35, migrated.episodeCount(MealMode.UAM_PROTEIN_FAT, w))
        }
    }

    // -- DURA attribution ------------------------------------------------------
    //
    // A low that DURA drove is DURA's bill: DuraStrengthLearner cuts its strength 15% for the
    // same episode, so charging the mode's base ISF a full 5% on top under-doses the meal to
    // fix the tail.

    @Test
    fun `a low with DURA cranked is mostly charged to DURA`() {
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS, bg = 160.0, dura = 1.25)
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true, dura = 1.25)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 75.0)
        assertEquals(1.0175, learner.multiplier(MealMode.LOW_CARB), 1e-9)
        assertTrue(learner.lastOutcome.contains("DURA"), learner.lastOutcome)
    }

    @Test
    fun `a low with DURA barely engaged still takes the full weaken`() {
        // Below the intervention bar the tail was not what drove BG down.
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS, bg = 160.0, dura = 1.10)
        cycle(MealMode.LOW_CARB, BASE_MS, BASE_MS + CYCLE_MS, bg = 70.0, low = true, dura = 1.10)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 75.0)
        assertEquals(1.05, learner.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    @Test
    fun `a low in the tail after a DURA-driven episode is charged to DURA too`() {
        // The shape of the screenshot case: mode handles the spike, ends, DURA's insulin is
        // still working, BG bottoms out after the mode is gone.
        cycle(MealMode.LUNCH, BASE_MS, BASE_MS, bg = 165.0, dura = 1.30)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 120.0)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, bg = 68.0, low = true)
        assertEquals(1.0175, learner.multiplier(MealMode.LUNCH), 1e-9)
    }

    @Test
    fun `a DURA-driven undershoot compounds both reductions`() {
        val start = BASE_MS
        cycle(MealMode.LOW_CARB, start, start, bg = 160.0, dura = 1.25)
        // Past UNDERSHOOT_MIN_ELAPSED_MS (25min) so the dip counts as evidence.
        cycle(MealMode.LOW_CARB, start, start + 6 * CYCLE_MS, bg = 88.0, undershoot = true, dura = 1.25)
        cycle(null, 0L, start + 7 * CYCLE_MS, bg = 92.0)
        assertEquals(1.00875, learner.multiplier(MealMode.LOW_CARB), 1e-9)
    }

    // -- Spike phase vs stalled tail --------------------------------------------

    @Test
    fun `ending high with DURA working the tail is left to DURA`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0, dura = 1.25)
        runQuietTail(BASE_MS, bg = 130.0)
        assertEquals(1.0, learner.multiplier(MealMode.DINNER), 1e-9)
        assertTrue(learner.lastOutcome.contains("DURA's to fix"), learner.lastOutcome)
    }

    @Test
    fun `ending high without DURA still strengthens`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0, dura = 1.0)
        runQuietTail(BASE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)
    }

    /** Holds [bg] for [cycles] readings starting [offsetMin] minutes into a manual Lunch, then
     *  lands on target and runs the tail. */
    private fun heldPeak(bg: Double, cycles: Int, offsetMin: Int, mode: MealMode = MealMode.LUNCH, railed: Boolean = false) {
        val start = BASE_MS
        var t = start
        cycle(mode, start, t, bg = 110.0, railed = railed)
        t = start + offsetMin * 60_000L
        repeat(cycles) { cycle(mode, start, t, bg = bg, railed = railed); t += CYCLE_MS }
        cycle(mode, start, t, bg = 102.0, railed = railed)
        runQuietTail(t, bg = 102.0)
    }

    @Test
    fun `a spike held high early in a manual mode strengthens even on a clean landing`() {
        // 160 is +3.3mmol over a 100 target; 8 readings is 35 minutes held.
        heldPeak(bg = 160.0, cycles = 8, offsetMin = 10)
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("spike held"), learner.lastOutcome)
    }

    @Test
    fun `the same held level late in the episode is a stall, not a spike`() {
        // Starts 80 minutes in — past the spike phase. That is the fat/protein stall DURA is for.
        heldPeak(bg = 160.0, cycles = 8, offsetMin = 80)
        assertEquals(1.0, learner.multiplier(MealMode.LUNCH), 1e-9)
    }

    @Test
    fun `a UAM spike waits for the entry fraction while it has headroom`() {
        heldPeak(bg = 160.0, cycles = 8, offsetMin = 10, mode = MealMode.UAM_LUNCH, railed = false)
        assertEquals(1.0, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a UAM spike with the entry fraction railed charges the ISF`() {
        heldPeak(bg = 160.0, cycles = 8, offsetMin = 10, mode = MealMode.UAM_LUNCH, railed = true)
        assertEquals(0.975, learner.multiplier(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `P over F never strengthens on a held level — that is DURA's stall`() {
        heldPeak(bg = 160.0, cycles = 8, offsetMin = 10, mode = MealMode.UAM_PROTEIN_FAT)
        assertEquals(1.0, learner.multiplier(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }


    // -- Modes that start high ---------------------------------------------------
    //
    // Judge what the mode did, not the altitude it happened to be handed. The exemption is bounded
    // by physics: while BG is still coming down, no charge; the moment it flattens above target,
    // whoever is running takes it — so an under-treated high can't be passed along the chain.

    @Test
    fun `a mode fired at 9mmol that never rose is not charged for a spike`() {
        // 162 with a 110 target already clears the 3mmol spike bar standing still.
        val start = BASE_MS
        var t = start
        cycle(MealMode.LUNCH, start, t, bg = 162.0, railed = true)
        repeat(8) { t += CYCLE_MS; cycle(MealMode.LUNCH, start, t, bg = 163.0, railed = true) }
        t += CYCLE_MS
        cycle(MealMode.LUNCH, start, t, bg = 105.0, railed = true)
        runQuietTail(t, bg = 105.0)
        assertEquals(1.0, learner.multiplier(MealMode.LUNCH), 1e-9,
                     "no rise happened — this is someone else's high, not this mode's spike")
    }

    @Test
    fun `the same altitude reached by an actual rise is still charged`() {
        val start = BASE_MS
        var t = start
        cycle(MealMode.LUNCH, start, t, bg = 110.0, railed = true)
        repeat(8) { t += CYCLE_MS; cycle(MealMode.LUNCH, start, t, bg = 163.0, railed = true) }
        t += CYCLE_MS
        cycle(MealMode.LUNCH, start, t, bg = 105.0, railed = true)
        runQuietTail(t, bg = 105.0)
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9)
    }

    @Test
    fun `ending high while still working down an inherited high is not charged`() {
        val start = BASE_MS
        cycle(MealMode.LUNCH, start, start, bg = 165.0)          // opened at ~9.2mmol
        cycle(MealMode.LUNCH, start, start + CYCLE_MS, bg = 160.0)
        // Ends at 130: over target, but 35 below where it started and still falling.
        runQuietTail(start + CYCLE_MS, bg = 130.0, delta = -2.5)
        assertEquals(1.0, learner.multiplier(MealMode.LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("inherited high"), learner.lastOutcome)
    }

    @Test
    fun `an inherited high that stalls above target IS charged`() {
        // The bound that stops it being passed down the chain: stop coming down, and the mode
        // running at that moment owns it.
        val start = BASE_MS
        cycle(MealMode.LUNCH, start, start, bg = 165.0)
        cycle(MealMode.LUNCH, start, start + CYCLE_MS, bg = 160.0)
        runQuietTail(start + CYCLE_MS, bg = 130.0, delta = 0.0)   // flat at 7.2mmol
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9)
    }

    @Test
    fun `falling but not below where it started is still charged`() {
        // Drifting down inside its own excursion is not "working through an inherited high".
        val start = BASE_MS
        cycle(MealMode.LUNCH, start, start, bg = 130.0)
        cycle(MealMode.LUNCH, start, start + CYCLE_MS, bg = 175.0)
        runQuietTail(start + CYCLE_MS, bg = 140.0, delta = -2.5)
        assertEquals(0.975, learner.multiplier(MealMode.LUNCH), 1e-9)
    }


    // -- The on/off switch -------------------------------------------------------

    @Test
    fun `with learning off an episode that would have moved the multiplier does not`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0, learning = false)
        var t = BASE_MS
        repeat(16) { t += CYCLE_MS; cycle(null, 0L, t, bg = 130.0, learning = false) }
        assertEquals(1.0, learner.multiplier(MealMode.DINNER), 1e-9)
        assertEquals(0, learner.episodeCount(MealMode.DINNER))
    }

    @Test
    fun `what was already learned survives the switch being off`() {
        // Off freezes; it does not clear. The multiplier is still there to be dosed from.
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0)
        runQuietTail(BASE_MS, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)

        val t = BASE_MS + 10 * CYCLE_MS
        cycle(MealMode.DINNER, t, t, bg = 160.0, learning = false)
        cycle(null, 0L, t + CYCLE_MS, bg = 130.0, learning = false)
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `an episode in flight when the switch goes off is dropped, not judged later`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0)          // episode running
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 150.0, learning = false)   // switched off mid-tail
        assertTrue(learner.lastOutcome.contains("switched off"), learner.lastOutcome)
        // Switched back on, the old episode must not resurface and be scored.
        var t = BASE_MS + 2 * CYCLE_MS
        repeat(16) { t += CYCLE_MS; cycle(null, 0L, t, bg = 130.0) }
        assertEquals(1.0, learner.multiplier(MealMode.DINNER), 1e-9)
    }

    @Test
    fun `learning resumes on the next episode after the switch comes back on`() {
        cycle(MealMode.DINNER, BASE_MS, BASE_MS, bg = 160.0, learning = false)
        cycle(null, 0L, BASE_MS + CYCLE_MS, bg = 130.0, learning = false)
        val t = BASE_MS + 10 * CYCLE_MS
        cycle(MealMode.DINNER, t, t, bg = 160.0)
        runQuietTail(t, bg = 130.0)
        assertEquals(0.975, learner.multiplier(MealMode.DINNER), 1e-9)
    }
}
