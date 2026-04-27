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

    @BeforeEach
    fun setUp() {
        prefs         = FakePreferences()
        profileLearner = mock()
        tracker       = BolusCurveTracker(profileLearner, prefs, logger)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Single tracker cycle. */
    private fun cycle(
        bg: Double,
        iob: Double,
        mode: MealMode = MealMode.FASTING,
        nowMs: Long    = BASE_MS,
        timestamp: Long = nowMs
    ) = tracker.onLoopCycle(
        glucoseStatus = glucoseStatus(glucose = bg, date = timestamp),
        mealMode      = mode,
        iobArray      = iobArray(iob = iob, activity = iob * 0.01),
        nowMs         = nowMs
    )

    /**
     * Drive a complete clean rebound: bolus spike → IOB peak → BG falls → nadir → recovery.
     * Returns the time at which the track closed.
     */
    private fun driveReboundTrack(
        startBg:   Double = 162.0,  // 9 mmol — above target pre-bolus
        nadirBg:   Double = 108.0,  // 6 mmol — dropped ~3 mmol
        recovBg:   Double = 125.0,  // 6.9 mmol — recovered enough
        iobPeak:   Double = 3.0,
        mode:      MealMode = MealMode.FASTING
    ): Long {
        var t = BASE_MS

        // Cycle 0: seed prevIob (first cycle always returns early)
        cycle(bg = startBg, iob = 0.5, mode = mode, nowMs = t); t += CYCLE_MS

        // Cycle 1: bolus spike — IOB jumps from 0.5 to iobPeak
        cycle(bg = startBg, iob = iobPeak, mode = mode, nowMs = t); t += CYCLE_MS

        // IOB peak then decline over 6 cycles
        for (i in 1..6) {
            cycle(bg = startBg - i * 5.0, iob = iobPeak * (1.0 - i * 0.08),
                  mode = mode, nowMs = t)
            t += CYCLE_MS
        }

        // BG nadir reached
        cycle(bg = nadirBg, iob = iobPeak * 0.4, mode = mode, nowMs = t); t += CYCLE_MS

        // Hold nadir for MIN_NADIR_DELAY (31 minutes)
        repeat(7) {
            cycle(bg = nadirBg, iob = iobPeak * 0.35, mode = mode, nowMs = t)
            t += CYCLE_MS
        }

        // Recovery — BG climbs back up past nadir + RECOVERY_MGDL (12 mg/dL)
        cycle(bg = recovBg, iob = iobPeak * 0.3, mode = mode, nowMs = t)
        t += CYCLE_MS

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
        val peakCaptor = argumentCaptor<Double?>()
        val diaCaptor  = argumentCaptor<Double>()
        verify(profileLearner, times(1)).observeBolusCurve(
            mode             = any(),
            observedPeakMins = peakCaptor.capture(),
            observedDiaMins  = diaCaptor.capture(),
            learningRate     = any()
        )
        val peak = peakCaptor.firstValue
        val dia  = diaCaptor.firstValue
        // Peak may be null (parabola analysis might not find a clear peak in synthetic data)
        // but DIA must be positive and plausible
        assertTrue(dia > 0.0, "DIA should be positive, got $dia")
        assertTrue(dia < 360.0, "DIA should be < 6h for a short synthetic track, got $dia")
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
    fun `small ride-along SMB does not pivot`() {
        var t = BASE_MS

        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS  // seed
        cycle(bg = 162.0, iob = 3.0, nowMs = t); t += CYCLE_MS  // first bolus (2.5U spike)

        // IOB peak, let decline begin
        cycle(bg = 155.0, iob = 2.8, nowMs = t); t += CYCLE_MS
        cycle(bg = 145.0, iob = 2.3, nowMs = t); t += CYCLE_MS

        // Small SMB: +0.2U on top of 2.3 existing IOB (ratio = 0.2/2.3 = 8.7% < 30%)
        cycle(bg = 140.0, iob = 2.5, nowMs = t); t += CYCLE_MS

        // Track should still be progressing — iobDeclineSeen from the first peak
        // and NOT reset to waiting_peak by the ride-along
        val logs = logger.debugMessages.filter { it.contains("ride-along") }
        assertTrue(logs.isNotEmpty(), "Small SMB should be logged as ride-along, not pivot")
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
    fun `DIA from flatline exit is bolus-to-nadir time not bolus-to-now`() {
        var t = BASE_MS

        cycle(bg = 162.0, iob = 0.5, nowMs = t); t += CYCLE_MS
        cycle(bg = 162.0, iob = 3.0, nowMs = t); t += CYCLE_MS

        // BG falls over 10 cycles (~50 min) to nadir at 108 mg/dL
        for (i in 1..10) {
            cycle(bg = 162.0 - i * 5.4, iob = 3.0 * (1.0 - i * 0.07), nowMs = t)
            t += CYCLE_MS
        }
        // Nadir established. Total time to nadir ≈ 12 cycles = 60 min

        // Now flatline for 32 cycles (~160 min) — way past the 2h elapsed threshold
        for (i in 0..31) {
            cycle(bg = 108.0 + (if (i % 2 == 0) 1.0 else -1.0),
                  iob = 0.5 - i * 0.01, nowMs = t)
            t += CYCLE_MS
        }

        val diaCaptor = argumentCaptor<Double>()
        verify(profileLearner).observeBolusCurve(
            mode             = any(),
            observedPeakMins = any(),
            observedDiaMins  = diaCaptor.capture(),
            learningRate     = any()
        )

        val observedDia = diaCaptor.firstValue
        // Nadir was established after ~12 cycles = ~60 min from track start.
        // DIA should be ~60 min, NOT ~220 min (which elapsed-to-now would give).
        assertTrue(observedDia < 120.0,
                   "Flatline DIA should be bolus→nadir time (~60 min), not bolus→now (~220 min), got $observedDia")
        assertTrue(observedDia > 30.0,
                   "Flatline DIA should be at least 30 min, got $observedDia")
    }
}
