package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [DuraStrengthLearner] — DURA's strength (how fast it climbs) and ceiling (how far).
 * Low with DURA engaged (≥1.10×): ceiling cut to 1 + excess×0.85 from the peak reached, strength
 * ×0.95. Stuck ≥1mmol over target for an unbroken hour with nothing low after: held at the
 * configured floor → no change; held at the ceiling → ceiling loosened; still climbing → strength
 * ×1.03, up to the configured strength. Anything else → no change. A low always wins.
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
        pfWindow: PfWindow = PfWindow.NONE, boost: Boolean = false
    ) = learner.onCycle(
        activeModeNow = mode, modeStartMs = startMs, lowActive = low, duraMult = dura,
        exerciseSuspected = exercise, nowMs = nowMs, baseSignature = baseSig, pfWindow = pfWindow,
        newPodBoost = boost
    )

    @Test
    fun `a crash while DURA was engaged reduces its strength`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        assertEquals(0.90, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `a crash with DURA barely engaged is not attributed to DURA`() {
        // 1.05 is below the 1.10 "actually did something" threshold
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.05)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.05)
        assertEquals(1.0, learner.factor(MealMode.UAM_DINNER), 1e-9)
        assertEquals(DuraStrengthLearner.NO_CEILING, learner.ceiling(MealMode.UAM_DINNER))
    }

    @Test
    fun `a crash in the tail after a DURA episode still counts`() {
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS, dura = 1.40)
        cycle(null, 0L, BASE_MS + CYCLE_MS)                        // mode ends, watch window opens
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, low = true)         // crash lands in the tail
        assertEquals(0.90, learner.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
        assertEquals(1.20, learner.ceiling(MealMode.UAM_PROTEIN_FAT), 1e-9)   // 1 + 0.40×0.50
    }

    @Test
    fun `DURA engaged with no low and no long stall changes nothing`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.50)
        var t = BASE_MS
        repeat(17) {
            t += CYCLE_MS
            cycle(null, 0L, t)
        }
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9,
                     "No stall evidence, so no strengthening")
        assertEquals(DuraStrengthLearner.NO_CEILING, learner.ceiling(MealMode.UAM_LUNCH),
                     "No low, so no ceiling")
    }

    @Test
    fun `an exercise-flagged crash reduces at a smaller step`() {
        // strength 1.0 - (1.0-0.95)*0.4 = 0.98; ceiling step 1.0 - (1.0-0.85)*0.4 = 0.94
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30, exercise = true)
        assertEquals(0.96, learner.factor(MealMode.UAM_DINNER), 1e-9)
        assertEquals(1.0 + 0.30 * 0.80, learner.ceiling(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `repeated lows walk the ceiling down until DURA stops being the one pushing`() {
        // Each low halves the excess, from what was actually dosed: ×1.40 → ×1.20 → ×1.10 → ×1.05.
        // Once the ceiling holds DURA under the 1.10 engaged bar, DURA is no longer doing enough to
        // be blamed — later lows stop landing here at all and fall to the mode ISF learner at its
        // full step instead. Three cuts, and ×1.05 is also the lowest ceiling allowed.
        var t = BASE_MS
        repeat(30) {
            val start = t
            // What the plugin actually hands over: DURA's own ×1.40 held down to the ceiling.
            val dosed = minOf(1.40, learner.ceiling(MealMode.UAM_DINNER))
            cycle(MealMode.UAM_DINNER, start, t, dura = dosed)
            t += CYCLE_MS
            cycle(MealMode.UAM_DINNER, start, t, low = true, dura = dosed)
            t += CYCLE_MS
            cycle(null, 0L, t)
            t += CYCLE_MS
        }
        assertEquals(1.05, learner.ceiling(MealMode.UAM_DINNER), 1e-9)
        assertEquals(Math.pow(0.90, 3.0), learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `the ceiling never goes below its minimum`() {
        // Only reachable from a restored or hand-edited value — the engaged bar stops the walk
        // above it in normal use — but the floor on the floor still has to hold.
        sp.edit { putString("si_dura_strength_learner_state", """{"UAM_DINNER":{"factor":0.5,"n":3,"ceiling":1.01}}""") }
        val restored = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(1.05, restored.ceiling(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `changing the configured DURA strength resets the learned factor`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30, baseSig = 2.0)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30, baseSig = 2.0)
        assertEquals(0.90, learner.factor(MealMode.UAM_DINNER), 1e-9)

        val newStart = BASE_MS + 6 * 60 * 60_000L
        cycle(MealMode.UAM_DINNER, newStart, newStart, baseSig = 3.0)
        assertEquals(1.0, learner.factor(MealMode.UAM_DINNER), 1e-9)
        assertEquals(DuraStrengthLearner.NO_CEILING, learner.ceiling(MealMode.UAM_DINNER))
    }

    @Test
    fun `learned state survives persistence round-trip`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)

        val restored = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(0.90, restored.factor(MealMode.UAM_DINNER), 1e-9)
        assertEquals(1.15, restored.ceiling(MealMode.UAM_DINNER), 1e-9)
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
    fun `a clean landing no longer gives strength back blindly`() {
        // About right is about right. Creeping stronger on every good meal walked DURA back into
        // the next low, with lows as the only brake.
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        cleanDuraEpisode(MealMode.UAM_DINNER, BASE_MS + 10 * CYCLE_MS)
        assertEquals(0.90, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `an episode where DURA never engaged gives nothing back`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        assertEquals(0.90, learner.factor(MealMode.UAM_DINNER), 1e-9)

        // DURA below the 1.10 bar — this episode says nothing about DURA's strength either way,
        // so no watch window opens and the factor stays put.
        val next = BASE_MS + 10 * CYCLE_MS
        cleanDuraEpisode(MealMode.UAM_DINNER, next, dura = 1.02)
        assertEquals(0.90, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }

    // ── P/F per-window learning ──────────────────────────────────────────────

    @Test
    fun `P over F DURA windows learn independently`() {
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS, dura = 1.30, pfWindow = PfWindow.OVERNIGHT)
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30,
              pfWindow = PfWindow.OVERNIGHT)
        assertEquals(0.90, learner.factor(MealMode.UAM_PROTEIN_FAT, PfWindow.OVERNIGHT), 1e-9)
        assertEquals(1.0, learner.factor(MealMode.UAM_PROTEIN_FAT, PfWindow.DAY), 1e-9)
        assertEquals(DuraStrengthLearner.NO_CEILING, learner.ceiling(MealMode.UAM_PROTEIN_FAT, PfWindow.DAY))
    }

    @Test
    fun `pre-split P over F DURA state seeds every window`() {
        sp.edit { putString("si_dura_strength_learner_state", """{"UAM_PROTEIN_FAT":{"factor":0.53,"n":22}}""") }
        val migrated = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        PfWindow.PF_WINDOWS.forEach { w ->
            assertEquals(0.53, migrated.factor(MealMode.UAM_PROTEIN_FAT, w), 1e-9)
        }
    }

    // ── Ceiling ──────────────────────────────────────────────────────────────

    @Test
    fun `a low sets a ceiling below the peak DURA reached`() {
        // The lunch that prompted this: DURA got to ×1.44 and that was too much.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.44)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.44)
        assertEquals(1.22, learner.ceiling(MealMode.UAM_LUNCH), 1e-9)      // 1 + 0.44×0.50
    }

    @Test
    fun `a dip toward the guard cuts the ceiling at half the step`() {
        val start = BASE_MS
        cycle(MealMode.UAM_PROTEIN_FAT, start, start, dura = 1.31, pfWindow = PfWindow.DAY)
        learner.onCycle(
            activeModeNow = MealMode.UAM_PROTEIN_FAT, modeStartMs = start, lowActive = false, duraMult = 1.31,
            exerciseSuspected = false, nowMs = start + 30 * 60_000L, baseSignature = 2.0,
            undershootActive = true, pfWindow = PfWindow.DAY
        )
        // step = 1 - 0.15×0.5 = 0.925 → 1 + 0.31×0.925
        assertEquals(1.0 + 0.31 * 0.75, learner.ceiling(MealMode.UAM_PROTEIN_FAT, PfWindow.DAY), 1e-9)
    }

    @Test
    fun `a later low that never reached the ceiling cuts from its own peak`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.50)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.50)
        assertEquals(1.25, learner.ceiling(MealMode.UAM_DINNER), 1e-9)

        // Next dinner DURA only got to ×1.20 and it still went low — ×1.20 was the problem.
        val next = BASE_MS + 10 * CYCLE_MS
        cycle(MealMode.UAM_DINNER, next, next, dura = 1.20)
        cycle(MealMode.UAM_DINNER, next, next + CYCLE_MS, low = true, dura = 1.20)
        assertEquals(1.10, learner.ceiling(MealMode.UAM_DINNER), 1e-9)       // 1 + 0.20×0.50
    }

    @Test
    fun `a ceiling that is already tighter is kept`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.20)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.20)
        assertEquals(1.10, learner.ceiling(MealMode.UAM_DINNER), 1e-9)

        // A later reading of ×1.40 can only come from before the ceiling existed (or a restore) —
        // it must never loosen the ceiling.
        val next = BASE_MS + 10 * CYCLE_MS
        cycle(MealMode.UAM_DINNER, next, next, dura = 1.40)
        cycle(MealMode.UAM_DINNER, next, next + CYCLE_MS, low = true, dura = 1.40)
        assertEquals(1.10, learner.ceiling(MealMode.UAM_DINNER), 1e-9)
    }

    @Test
    fun `a clean landing leaves the ceiling alone`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.44)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.44)
        cleanDuraEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, dura = 1.15)
        assertEquals(1.22, learner.ceiling(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `existing state without a ceiling restores with none`() {
        // Upgrade path: strength factors learned before the ceiling existed keep their values and
        // start with no ceiling — the first low after the update sets one.
        sp.edit { putString("si_dura_strength_learner_state", """{"UAM_LUNCH":{"factor":0.64,"n":8}}""") }
        val restored = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(0.64, restored.factor(MealMode.UAM_LUNCH), 1e-9)
        assertEquals(DuraStrengthLearner.NO_CEILING, restored.ceiling(MealMode.UAM_LUNCH))
    }

    // ── Stuck elevated: DURA wasn't enough ──────────────────────────────────

    private val TARGET = 110.0

    /**
     * BG parked over target for [stuckMin] minutes with DURA's stuck timer running, then the mode
     * ends and the 105-min tail runs — low at the end if [lowAfter]. [bgAt] and [duraAt] take the
     * minute into the run, for shapes that aren't a flat line.
     */
    private fun stuckEpisode(
        mode: MealMode, startMs: Long, stuckMin: Int, dura: Double = 1.25,
        atCeiling: Boolean = false, atFloor: Boolean = false, lowAfter: Boolean = false,
        lowDuring: Boolean = false,
        bgAt: (Int) -> Double = { TARGET + 36.0 },
        duraAt: (Int) -> Double = { dura }
    ): Long {
        var t = startMs
        var m = 0
        while (m <= stuckMin) {
            learner.onCycle(
                activeModeNow = mode, modeStartMs = startMs, lowActive = false, duraMult = duraAt(m),
                exerciseSuspected = false, nowMs = t, baseSignature = 2.0,
                // The tracker has already been stuck its 10-minute minimum when the run opens.
                bgMgdl = bgAt(m), targetMgdl = TARGET, duraStuckMinutes = m + 10.0,
                duraAtCeiling = atCeiling, duraAtFloor = atFloor
            )
            t += CYCLE_MS; m += 5
        }
        if (lowDuring) {
            learner.onCycle(mode, startMs, lowActive = true, duraMult = dura, exerciseSuspected = false,
                            nowMs = t, baseSignature = 2.0, bgMgdl = 65.0, targetMgdl = TARGET)
            t += CYCLE_MS
        }
        cycle(null, 0L, t)                                   // mode ends
        t += if (lowAfter) 30 * 60_000L else 106 * 60_000L
        cycle(null, 0L, t, low = lowAfter)
        return t + CYCLE_MS
    }

    @Test
    fun `stuck an hour while DURA was still climbing raises strength`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.30)                 // an old cut first
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 60)
        assertEquals(0.90 * 1.03, learner.factor(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("still climbing"), learner.lastOutcome)
    }

    @Test
    fun `stuck just under an hour is not enough evidence`() {
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 55)
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `stuck an hour held at the ceiling loosens the ceiling, not the strength`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.44)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.44)   // ceiling 1.374
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 60, dura = 1.374, atCeiling = true)
        assertEquals(1.0 + 0.22 * 1.10, learner.ceiling(MealMode.UAM_LUNCH), 1e-9)
        assertEquals(0.90, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a ceiling cut near the bottom still loosens by a useful amount`() {
        // DURA held at ×1.09 is under the engaged bar — the stall alone must still open the watch.
        sp.edit { putString("si_dura_strength_learner_state", """{"UAM_LUNCH":{"factor":0.9,"n":9,"baseSig":2.0,"ceiling":1.09}}""") }
        learner = DuraStrengthLearner(sp, FakeAAPSLogger(collect = false))
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 60, dura = 1.09, atCeiling = true)
        assertEquals(1.11, learner.ceiling(MealMode.UAM_LUNCH), 1e-9)    // +0.02 minimum beats +10% of 0.09
    }

    @Test
    fun `stuck an hour held at the configured floor changes nothing and says so`() {
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 60, dura = 1.40, atFloor = true)
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
        assertEquals(DuraStrengthLearner.NO_CEILING, learner.ceiling(MealMode.UAM_LUNCH))
        assertTrue(learner.lastOutcome.contains("configured floor"), learner.lastOutcome)
    }

    @Test
    fun `stuck at full configured strength changes nothing and says so`() {
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 60)
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("full configured strength"), learner.lastOutcome)
    }

    @Test
    fun `a low after a long stall never strengthens — it cuts`() {
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 60, dura = 1.40, lowAfter = true)
        assertEquals(0.90, learner.factor(MealMode.UAM_LUNCH), 1e-9)
        assertEquals(1.20, learner.ceiling(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a low during the mode stops a long stall from strengthening later`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        val factorAfterCut = learner.factor(MealMode.UAM_LUNCH)
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 60, dura = 1.20, lowDuring = true)
        assertTrue(learner.factor(MealMode.UAM_LUNCH) <= factorAfterCut, "went low — must not have strengthened")
    }

    // ── What counts as a stall ───────────────────────────────────────────────

    @Test
    fun `an hour stuck with DURA barely nudging ISF is not evidence DURA wasn't enough`() {
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 60, dura = 1.05)
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("engaged only"), learner.lastOutcome)
    }

    @Test
    fun `DURA engaged for only the last part of the hour does not count`() {
        // Ramping up the whole time, engaged for the final 20 minutes.
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 60,
                     duraAt = { m -> if (m >= 40) 1.15 else 1.05 })
        assertEquals(0.90, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `DURA engaged for half a longer stall does count`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 80,
                     duraAt = { m -> if (m >= 45) 1.15 else 1.05 })
        assertEquals(0.90 * 1.03, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `a slow bleed down that never trips the tracker reset is not a stall`() {
        // −1 mg/dL per 5 min: well under the tracker's −3.6 single-cycle reset, and inside its
        // ±5% band the whole way, so it reads as stuck. −12 mg/dL over the hour — 10 between the
        // start and end 3-reading means — says it was already resolving.
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS, stuckMin = 60, bgAt = { m -> TARGET + 40.0 - m / 5.0 })
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
        assertTrue(learner.lastOutcome.contains("drifting down"), learner.lastOutcome)
    }

    @Test
    fun `a small net drift down within noise still counts as stuck`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        // −6 mg/dL across the hour — under the 0.5mmol allowance.
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 60, bgAt = { m -> TARGET + 36.0 - m / 10.0 })
        assertEquals(0.90 * 1.03, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `rising through the hour still counts — that is a stall getting worse`() {
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, dura = 1.30)
        cycle(MealMode.UAM_LUNCH, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        stuckEpisode(MealMode.UAM_LUNCH, BASE_MS + 10 * CYCLE_MS, stuckMin = 60, bgAt = { m -> TARGET + 30.0 + m / 5.0 })
        assertEquals(0.90 * 1.03, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    @Test
    fun `two shorter stalls do not add up to an hour`() {
        val start = BASE_MS
        var t = start
        fun run(minutes: Int) {
            var m = 0
            while (m <= minutes) {
                learner.onCycle(MealMode.UAM_LUNCH, start, lowActive = false, duraMult = 1.25, exerciseSuspected = false,
                                nowMs = t, baseSignature = 2.0, bgMgdl = TARGET + 36.0, targetMgdl = TARGET,
                                duraStuckMinutes = m + 10.0)
                t += CYCLE_MS; m += 5
            }
        }
        run(40)
        // Tracker resets — BG moved out of its band for a reading.
        learner.onCycle(MealMode.UAM_LUNCH, start, lowActive = false, duraMult = 1.25, exerciseSuspected = false,
                        nowMs = t, baseSignature = 2.0, bgMgdl = TARGET + 60.0, targetMgdl = TARGET, duraStuckMinutes = 0.0)
        t += CYCLE_MS
        run(40)
        cycle(null, 0L, t)
        cycle(null, 0L, t + 106 * 60_000L)
        assertEquals(1.0, learner.factor(MealMode.UAM_LUNCH), 1e-9)
    }

    // ── new pod boost ────────────────────────────────────────────────────────

    @Test
    fun `a crash during a boosted meal does not cut DURA, even after the low ended the boost`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30, boost = true)
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS + CYCLE_MS, low = true, dura = 1.30)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS)
        cycle(null, 0L, BASE_MS + 3 * CYCLE_MS, low = true)
        assertEquals(1.0, learner.factor(MealMode.UAM_DINNER), 1e-9)
        assertEquals(DuraStrengthLearner.NO_CEILING, learner.ceiling(MealMode.UAM_DINNER))
    }

    @Test
    fun `a boost that starts in the tail drops the tail watch`() {
        cycle(MealMode.UAM_PROTEIN_FAT, BASE_MS, BASE_MS, dura = 1.40)
        cycle(null, 0L, BASE_MS + CYCLE_MS, boost = true)
        cycle(null, 0L, BASE_MS + 2 * CYCLE_MS, low = true)
        assertEquals(1.0, learner.factor(MealMode.UAM_PROTEIN_FAT), 1e-9)
    }

    @Test
    fun `the next meal after a boosted one is judged as normal`() {
        cycle(MealMode.UAM_DINNER, BASE_MS, BASE_MS, dura = 1.30, boost = true)
        cycle(null, 0L, BASE_MS + CYCLE_MS)
        val t = BASE_MS + 10 * CYCLE_MS
        cycle(MealMode.UAM_DINNER, t, t, dura = 1.30)
        cycle(MealMode.UAM_DINNER, t, t + CYCLE_MS, low = true, dura = 1.30)
        assertEquals(0.90, learner.factor(MealMode.UAM_DINNER), 1e-9)
    }
}
