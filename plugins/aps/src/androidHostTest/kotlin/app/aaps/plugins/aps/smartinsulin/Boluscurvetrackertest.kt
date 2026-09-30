package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.glucoseStatus
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.iobArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Integration tests for [BolusCurveTracker].
 *
 * Drives the tracker through synthetic bolus + BG sequences and asserts:
 *  - Track starts on IOB spike, not on background IOB
 *  - Clean rebound exit feeds correct peak + DIA to ProfileLearner
 *  - Flatline exit fires after ≥2h with stable BG near nadir
 *  - Pivot logic correctly restarts on large mid-track bolus
 *  - Abandon fires on mode change or timeout
 *  - Corrupt data (duplicate timestamps, zero IOB) handled gracefully
 *
 * Time is injected via the nowMs parameter — no wall clock dependency.
 * ProfileLearner is mocked so we can assert exact observeBolusCurve call arguments.
 */
class BolusCurveTrackerTest {

    private lateinit var tracker: BolusCurveTracker
    private lateinit var profileLearner: ProfileLearner
    private lateinit var prefs: FakePreferences
    private val logger = FakeAAPSLogger(collect = true)

    private val BASE_MS   = 1_700_000_000_000L
    private val CYCLE_MS  = 5 * 60_000L   // 5 min

    // Fixed peak the DIA solve is anchored against — see solveObservedDiaMins() in
    // BolusCurveTracker, which reads profileLearner.getProfile(trackMode).safePeakMinutes.
    private val STUBBED_PEAK_MINS = 75.0

    @BeforeEach
    fun setUp() {
        prefs         = FakePreferences()
        profileLearner = mock()
        // BolusCurveTracker now reads the current learned peak to solve DIA from observed
        // BG-drop magnitude — stub a deterministic value so existing tests (which never
        // configured this) don't NPE, and so the new magnitude-solve test can compute its
        // expected value independently.
        whenever(profileLearner.getProfile(any())).thenAnswer { invocation ->
            val mode = invocation.getArgument<MealMode>(0)
            LearnedInsulinProfile.defaultFor(mode, STUBBED_PEAK_MINS, LearnedInsulinProfile.FALLBACK_DIA_MINS)
        }
        tracker       = BolusCurveTracker(profileLearner, prefs, logger)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Representative ISF (mg/dL per unit) used throughout — feeds the magnitude-based DIA solve
    // (trackedDoseU * isfMgdl = expected total BG-lowering effect of the tracked dose).
    private val ISF_MGDL = 50.0

    /** Single tracker cycle. */
    private fun cycle(
        bg: Double,
        iob: Double,
        mode: MealMode = MealMode.FASTING,
        nowMs: Long    = BASE_MS,
        timestamp: Long = nowMs,
        isfMgdl: Double = ISF_MGDL
    ) = tracker.onLoopCycle(
        glucoseStatus = glucoseStatus(glucose = bg, date = timestamp),
        mealMode      = mode,
        iobArray      = iobArray(iob = iob, activity = iob * 0.01),
        isfMgdl       = isfMgdl,
        nowMs         = nowMs
    )

    /**
     * Drive a complete clean rebound: bolus spike → IOB peak → BG falls → nadir → recovery.
     * Returns the time at which the track closed.
     */
    private fun driveReboundTrack(
        startBg:   Double = 162.0,
        nadirBg:   Double = 108.0,
        recovBg:   Double = 130.0,   // needs to be >nadirBg+12 (RECOVERY_MGDL) for 3 consecutive cycles
        iobPeak:   Double = 3.0,
        mode:      MealMode = MealMode.FASTING
    ): Long {
        var t = BASE_MS

        // Seed cycle
        cycle(bg = startBg, iob = 0.5, mode = mode, nowMs = t); t += CYCLE_MS

        // Bolus spike
        cycle(bg = startBg, iob = iobPeak, mode = mode, nowMs = t); t += CYCLE_MS

        // IOB peak then decline — BG falls toward nadir
        for (i in 1..8) {
            cycle(bg = startBg - i * (startBg - nadirBg) / 8.0,
                  iob = iobPeak * (1.0 - i * 0.08),
                  mode = mode, nowMs = t)
            t += CYCLE_MS
        }

        // BG at nadir
        cycle(bg = nadirBg, iob = iobPeak * 0.35, mode = mode, nowMs = t); t += CYCLE_MS

        // Hold nadir for >MIN_NADIR_DELAY_MS (30 min) — 7 cycles = 35 min
        repeat(7) {
            cycle(bg = nadirBg, iob = iobPeak * 0.30, mode = mode, nowMs = t)
            t += CYCLE_MS
        }

        // Need 3 consecutive recovery cycles so smoothedBg median = recovBg (not nadirBg)
        // After 3 cycles at recovBg, bgBuffer = [recovBg, recovBg, recovBg] → median = recovBg
        // recovBg=130, nadirBg=108 → gap=22 > dynamicRecovery=max(12, 130*0.06=7.8)=12 ✓
        repeat(3) {
            cycle(bg = recovBg, iob = iobPeak * 0.25, mode = mode, nowMs = t)
            t += CYCLE_MS
        }

        return t
    }

    // ── Track Lifecycle ───────────────────────────────────────────────────────

    @Test
    fun `tracker seeds prevIob on first cycle and does not trigger tracking`() {
        cycle(bg = 100.0, iob = 2.5, nowMs = BASE_MS)
        // No tracking started — first cycle seeds prevIob only
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
        assertTrue(tracker.statusSummary().contains("idle"),
                   "Tracker should be idle after first (seed) cycle")
    }

    @Test
    fun `tracker starts on meaningful IOB spike`() {
        cycle(bg = 100.0, iob = 0.5, nowMs = BASE_MS)  // seed
        cycle(bg = 100.0, iob = 2.0, nowMs = BASE_MS + CYCLE_MS)  // spike: +1.5U
        assertTrue(tracker.statusSummary().contains("tracker="),
                   "Tracker should be active after IOB spike")
        assertTrue(!tracker.statusSummary().contains("idle"),
                   "Tracker should not be idle after spike")
    }

    @Test
    fun `tracker does not start on gradual IOB accumulation below spike threshold`() {
        cycle(bg = 100.0, iob = 0.5, nowMs = BASE_MS)     // seed
        cycle(bg = 100.0, iob = 0.7, nowMs = BASE_MS + CYCLE_MS)  // +0.2 — below MIN_BOLUS_SPIKE (0.3)
        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
    }

    @Test
    fun `clean rebound exit calls observeBolusCurve with positive peak and dia`() {
        driveReboundTrack()
        val peakCaptor = argumentCaptor<Double>()
        val diaCaptor  = argumentCaptor<Double>()
        verify(profileLearner, times(1)).observeBolusCurve(
            mode             = any(),
            observedPeakMins = peakCaptor.capture(),
            observedDiaMins  = diaCaptor.capture(),
            learningRate     = any()
        )
        val peak = peakCaptor.firstValue
        val dia  = diaCaptor.firstValue
        // Peak may be null (parabola analysis might not find a clear peak in synthetic data).
        // DIA is now solved from BG-drop magnitude rather than raw elapsed-to-nadir time, so it
        // isn't bounded by the (short) synthetic track duration — it must land within the
        // learner's physiological search range instead.
        assertTrue(dia >= LearnedInsulinProfile.DIA_MIN_MINUTES, "DIA should be >= floor, got $dia")
        assertTrue(dia <= LearnedInsulinProfile.DIA_MAX_MINUTES, "DIA should be <= ceiling, got $dia")
        if (peak != null) {
            assertTrue(peak > 0.0, "Peak should be positive when non-null, got $peak")
            assertTrue(peak < dia, "Peak should be less than DIA, got peak=$peak dia=$dia")
        }
    }

    @Test
    fun `tracker resets after close-out`() {
        driveReboundTrack()
        verify(profileLearner, times(1)).observeBolusCurve(any(), any(), any(), any())
        assertTrue(tracker.statusSummary().contains("idle"),
                   "Tracker should be idle after close-out")
    }

    // ── Flatline Exit ─────────────────────────────────────────────────────────

    @Test
    fun `flatline exit fires after 2h stable BG near nadir in FASTING mode`() {
        var t = BASE_MS

        // Seed cycle
        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS

        // Bolus spike
        cycle(bg = 162.0, iob = 3.0, nowMs = t); t += CYCLE_MS

        // IOB declines over 8 cycles, BG falls to nadir (108 mg/dL / 6 mmol)
        for (i in 1..8) {
            cycle(bg = 162.0 - i * 6.0, iob = 3.0 * (1.0 - i * 0.08),
                  nowMs = t)
            t += CYCLE_MS
        }

        // BG reaches nadir and stays flat (well within 0.5 mmol variance)
        // Run 30 cycles at nadir — this spans 150 min total elapsed by the end
        // Flatline requires ≥120 min elapsed + ≥45 min stable window
        for (i in 0..29) {
            cycle(bg = 108.0 + (if (i % 2 == 0) 1.0 else -1.0),  // ±1 mg/dL wobble
                  iob = 0.8 - i * 0.01, nowMs = t)
            t += CYCLE_MS
        }

        verify(profileLearner, times(1)).observeBolusCurve(any(), any(), any(), any())
        assertTrue(tracker.statusSummary().contains("idle"),
                   "Tracker should be idle after flatline exit")
    }

    @Test
    fun `flatline exit does NOT fire when BG is below safe range (hypo-flat)`() {
        var t = BASE_MS

        cycle(bg = 90.0, iob = 0.5, nowMs = t); t += CYCLE_MS
        cycle(bg = 90.0, iob = 2.0, nowMs = t); t += CYCLE_MS  // spike

        // IOB declines, BG drops to hypo level (below FLATLINE_BG_MIN_MGDL=72)
        for (i in 1..8) {
            cycle(bg = 90.0 - i * 3.0, iob = 2.0 * (1.0 - i * 0.1), nowMs = t)
            t += CYCLE_MS
        }
        // Flatline at 60 mg/dL (below safe floor)
        for (i in 0..29) {
            cycle(bg = 60.0, iob = 0.3, nowMs = t); t += CYCLE_MS
        }

        // Should NOT have called observeBolusCurve via flatline
        // (might still call via rebound if BG happened to spike — use mode to check log)
        val logs = logger.debugMessages.filter { it.contains("FLATLINE_EXIT") }
        assertTrue(logs.isEmpty(), "Flatline exit should not fire when BG below safe floor")
    }

    @Test
    fun `flatline exit does NOT fire in meal mode`() {
        var t = BASE_MS

        cycle(bg = 180.0, iob = 0.5, mode = MealMode.DINNER, nowMs = t); t += CYCLE_MS
        cycle(bg = 180.0, iob = 3.0, mode = MealMode.DINNER, nowMs = t); t += CYCLE_MS

        for (i in 1..8) {
            cycle(bg = 180.0 - i * 3.0, iob = 3.0 * (1.0 - i * 0.08),
                  mode = MealMode.DINNER, nowMs = t); t += CYCLE_MS
        }
        for (i in 0..30) {
            cycle(bg = 135.0, iob = 0.5, mode = MealMode.DINNER, nowMs = t)
            t += CYCLE_MS
        }

        val logs = logger.debugMessages.filter { it.contains("FLATLINE_EXIT") }
        assertTrue(logs.isEmpty(), "Flatline exit should not fire in meal mode")
    }

    // ── Pivot Logic ───────────────────────────────────────────────────────────

    @Test
    fun `large mid-track bolus triggers pivot and restarts track`() {
        var t = BASE_MS

        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS  // seed
        cycle(bg = 162.0, iob = 2.0, nowMs = t); t += CYCLE_MS  // first bolus

        // Let IOB peak and start declining
        cycle(bg = 150.0, iob = 1.8, nowMs = t); t += CYCLE_MS
        cycle(bg = 140.0, iob = 1.5, nowMs = t); t += CYCLE_MS

        // New large bolus (spike from 1.5 to 4.0 = +2.5U, >30% of existing 1.5 IOB)
        cycle(bg = 140.0, iob = 4.0, nowMs = t); t += CYCLE_MS

        // After pivot, the status should reflect the new (higher) IOB baseline
        // and iobDeclineSeen should be false (reset)
        val status = tracker.statusSummary()
        assertTrue(status.contains("waiting_peak"),
                   "After pivot, tracker should be back to waiting_peak phase: $status")
    }

    @Test
    fun `small SMB below spike threshold does not pivot`() {
        var t = BASE_MS

        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS  // seed
        cycle(bg = 162.0, iob = 3.0, nowMs = t); t += CYCLE_MS  // first bolus (2.5U spike)

        // IOB peak, let decline begin
        cycle(bg = 155.0, iob = 2.8, nowMs = t); t += CYCLE_MS
        cycle(bg = 145.0, iob = 2.3, nowMs = t); t += CYCLE_MS

        // Tiny SMB: +0.2U — below MIN_BOLUS_SPIKE (0.3U) — not even significant
        // so neither pivot NOR ride-along fires; track continues normally
        cycle(bg = 140.0, iob = 2.5, nowMs = t); t += CYCLE_MS

        // Track should still be progressing — iobDeclineSeen from the first peak
        // Pivot would have reset to waiting_peak; without pivot we're still tracking_nadir
        val status = tracker.statusSummary()
        assertTrue(!status.contains("waiting_peak"),
                   "Small SMB should not trigger pivot back to waiting_peak, got: $status")
        assertTrue(!status.contains("idle"),
                   "Small SMB should not abandon the track, got: $status")
    }

    // ── Abandon Logic ─────────────────────────────────────────────────────────

    @Test
    fun `track abandoned when meal mode changes mid-track`() {
        var t = BASE_MS

        cycle(bg = 162.0, iob = 0.5, mode = MealMode.FASTING, nowMs = t); t += CYCLE_MS
        cycle(bg = 162.0, iob = 3.0, mode = MealMode.FASTING, nowMs = t); t += CYCLE_MS  // spike
        cycle(bg = 155.0, iob = 2.8, mode = MealMode.FASTING, nowMs = t); t += CYCLE_MS

        // Mode changes to DINNER mid-track — should abandon
        cycle(bg = 150.0, iob = 2.5, mode = MealMode.DINNER, nowMs = t)

        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
        assertTrue(tracker.statusSummary().contains("idle"),
                   "Tracker should be idle after mode-change abandon")
    }

    @Test
    fun `track abandoned at MAX_TRACK_DURATION without close-out`() {
        var t = BASE_MS

        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS
        cycle(bg = 162.0, iob = 3.0, nowMs = t); t += CYCLE_MS

        // Jump time past MAX_TRACK_DURATION (6 hours)
        t += 6 * 60 * 60_000L + CYCLE_MS
        cycle(bg = 140.0, iob = 0.5, nowMs = t)

        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
        assertTrue(tracker.statusSummary().contains("idle"),
                   "Tracker should be idle after timeout abandon")
    }

    // ── Edge Cases ────────────────────────────────────────────────────────────

    @Test
    fun `zero IOB cycles do not crash tracker`() {
        cycle(bg = 100.0, iob = 0.0, nowMs = BASE_MS)
        cycle(bg = 100.0, iob = 0.0, nowMs = BASE_MS + CYCLE_MS)
        // Should remain idle — no spike possible from zero
        assertTrue(tracker.statusSummary().contains("idle"))
    }

    @Test
    fun `FASTING track does not close before iobDeclineSeen`() {
        var t = BASE_MS
        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS  // seed
        cycle(bg = 162.0, iob = 3.0, nowMs = t); t += CYCLE_MS  // spike

        // IOB is still rising / at peak — NO decline yet
        cycle(bg = 155.0, iob = 3.1, nowMs = t); t += CYCLE_MS
        cycle(bg = 148.0, iob = 3.2, nowMs = t); t += CYCLE_MS

        verify(profileLearner, never()).observeBolusCurve(any(), any(), any(), any())
        val status = tracker.statusSummary()
        assertTrue(status.contains("waiting_peak"),
                   "Tracker should be waiting for IOB peak, got: $status")
    }

    @Test
    fun `flatline exit DIA increases with tracked dose size for the same BG-drop trajectory`() {
        // Rather than hand-deriving an exact expected DIA (fragile — it would require replaying
        // the 3-sample median smoothing filter by hand), this drives two runs with IDENTICAL BG
        // trajectories (so bgAtStart, bgNadir and elapsed-to-nadir all come out identical) and
        // varies only the tracked dose size. A bigger dose producing the SAME observed BG drop
        // means a smaller fraction of it was used — which should only ever solve to a DIA that's
        // >= the smaller dose's, never shorter. This exercises the actual mechanism
        // (solveObservedDiaMins → BolusCurveAnalysis.solveDiaFromObservedFraction) without
        // depending on precomputed smoothing-filter arithmetic.
        fun runWithDose(iobPeak: Double): Double {
            val localProfileLearner = mock<ProfileLearner>()
            whenever(localProfileLearner.getProfile(any())).thenAnswer { invocation ->
                val mode = invocation.getArgument<MealMode>(0)
                LearnedInsulinProfile.defaultFor(mode, STUBBED_PEAK_MINS, LearnedInsulinProfile.FALLBACK_DIA_MINS)
            }
            val localTracker = BolusCurveTracker(localProfileLearner, FakePreferences(), FakeAAPSLogger(collect = false))
            var t = BASE_MS

            fun localCycle(bg: Double, iob: Double, nowMs: Long) = localTracker.onLoopCycle(
                glucoseStatus = glucoseStatus(glucose = bg, date = nowMs),
                mealMode      = MealMode.FASTING,
                iobArray      = iobArray(iob = iob, activity = iob * 0.01),
                isfMgdl       = ISF_MGDL,
                nowMs         = nowMs
            )

            localCycle(162.0, 0.5, t); t += CYCLE_MS      // seed
            localCycle(162.0, iobPeak, t); t += CYCLE_MS  // spike — dose = iobPeak - 0.5

            for (i in 1..8) {
                localCycle(162.0 - i * 6.75, iobPeak * (1.0 - i * 0.08), t)
                t += CYCLE_MS
            }
            repeat(20) { localCycle(108.0, iobPeak * 0.2, t); t += CYCLE_MS }

            val diaCaptor = argumentCaptor<Double>()
            verify(localProfileLearner, times(1)).observeBolusCurve(
                mode             = any(),
                observedPeakMins = any(),
                observedDiaMins  = diaCaptor.capture(),
                learningRate     = any()
            )
            return diaCaptor.firstValue
        }

        val diaSmallDose = runWithDose(iobPeak = 2.0)  // dose ≈ 1.5U
        val diaLargeDose = runWithDose(iobPeak = 5.0)  // dose ≈ 4.5U — same BG trajectory

        assertTrue(diaSmallDose in LearnedInsulinProfile.DIA_MIN_MINUTES..LearnedInsulinProfile.DIA_MAX_MINUTES)
        assertTrue(diaLargeDose in LearnedInsulinProfile.DIA_MIN_MINUTES..LearnedInsulinProfile.DIA_MAX_MINUTES)
        assertTrue(diaLargeDose >= diaSmallDose,
                   "A bigger tracked dose producing the same BG drop implies more of it is still " +
                       "unspent, so the solved DIA should be >= the smaller dose's, got large=$diaLargeDose small=$diaSmallDose")
    }
}