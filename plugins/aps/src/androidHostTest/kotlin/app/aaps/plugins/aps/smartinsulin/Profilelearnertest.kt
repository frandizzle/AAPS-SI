package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.profile.ProfileFunction

class ProfileLearnerTest {

    private val logger: AAPSLogger = mock()
    private val sp: SP = mock()
    private val profileFunction: ProfileFunction = mock()
    private val activePlugin: ActivePlugin = mock()
    private lateinit var learner: ProfileLearner

    @BeforeEach fun setUp() {
        // Return empty string for all profile keys → triggers defaultFor() fallback
        whenever(sp.getString(any<String>(), any<String>())).thenAnswer { it.getArgument<String>(1) }
        learner = ProfileLearner(logger, sp, profileFunction, activePlugin)
        // Matches FALLBACK_PEAK_MINS (75) so existing "default peak" assertions stay valid. In 4.0
        // the loop hands over the configured insulin's DIA and peak each cycle.
        learner.updateInsulinDefaults(diaMins = LearnedInsulinProfile.FALLBACK_DIA_MINS, peakMins = 75.0)
    }

    // ── Default profiles ─────────────────────────────────────────────────────

    @Test fun `getProfile returns default when no data persisted`() {
        val profile = learner.getProfile(MealMode.FASTING)
        val default = LearnedInsulinProfile.defaultFor(MealMode.FASTING)
        assertEquals(default.peakMinutes, profile.peakMinutes, 0.001)
        assertEquals(default.diaMinutes,  profile.diaMinutes,  0.001)
        assertEquals(0, profile.sampleCount)
    }

    // ── EWMA convergence ─────────────────────────────────────────────────────

    @Test fun `single FASTING observation moves peak toward observed value`() {
        val before = learner.getProfile(MealMode.FASTING).peakMinutes
        learner.observeBolusCurve(
            mode             = MealMode.FASTING,
            observedPeakMins = 50.0,   // faster than the prior of 75 (FALLBACK_PEAK_MINS)
            observedDiaMins  = 220.0,
            learningRate     = 0.15,
        )
        val after = learner.getProfile(MealMode.FASTING).peakMinutes
        // Peak should have moved toward 50 from 75
        assertTrue(after < before, "Peak should decrease toward 50 (before=$before after=$after)")
        assertTrue(after > 50.0, "Peak should not jump all the way to 50")
    }

    @Test fun `repeated observations converge toward observed value`() {
        repeat(50) {
            learner.observeBolusCurve(
                mode             = MealMode.FASTING,
                observedPeakMins = 55.0,
                // Must be >= LearnedInsulinProfile.DIA_MIN_MINUTES (300) — the original 210.0 here
                // was always below the floor regardless of any DIA-solver changes, so the profile
                // could never actually converge to it; this test just never ran before.
                observedDiaMins  = 350.0,
                learningRate     = 0.15,
            )
        }
        val profile = learner.getProfile(MealMode.FASTING)
        // After 50 identical observations it should be very close to the target
        assertEquals(55.0, profile.peakMinutes, 2.0)
        assertEquals(350.0, profile.diaMinutes, 5.0)
    }

    @Test fun `sample count increments on each observation`() {
        repeat(3) {
            learner.observeBolusCurve(MealMode.LUNCH, 70.0, 260.0, 0.15)
        }
        assertEquals(3, learner.getProfile(MealMode.LUNCH).sampleCount)
    }

    // ── DIA learning gate ────────────────────────────────────────────────────

    @Test fun `EXTENDED mode does not update DIA`() {
        val diaBefore = learner.getProfile(MealMode.EXTENDED).diaMinutes
        learner.observeBolusCurve(
            mode             = MealMode.EXTENDED,
            observedPeakMins = 85.0,
            observedDiaMins  = 400.0,   // very different — should be ignored
            learningRate     = 0.15,
        )
        val diaAfter = learner.getProfile(MealMode.EXTENDED).diaMinutes
        assertEquals(diaBefore, diaAfter, 0.001, "DIA should not change in EXTENDED mode")
    }

    @Test fun `EXTENDED mode does not update peak either — not in PEAK_LEARNING_MODES`() {
        // Peak learning is restricted to PEAK_LEARNING_MODES (FASTING, LOW_CARB) — only those
        // modes give a clean-enough curve to reliably observe peak action. EXTENDED is excluded
        // from both peak and DIA learning, not just DIA.
        val peakBefore = learner.getProfile(MealMode.EXTENDED).peakMinutes
        learner.observeBolusCurve(
            mode             = MealMode.EXTENDED,
            observedPeakMins = 60.0,   // different from default of 75 — should still be ignored
            observedDiaMins  = 350.0,
            learningRate     = 0.15,
        )
        val peakAfter = learner.getProfile(MealMode.EXTENDED).peakMinutes
        assertEquals(peakBefore, peakAfter, 0.001, "Peak should not change in EXTENDED mode either")
    }

    // ── Rejection of implausible observations ────────────────────────────────

    @Test fun `observation where peak is greater than or equal to DIA is rejected`() {
        val before = learner.getProfile(MealMode.FASTING)
        learner.observeBolusCurve(
            mode             = MealMode.FASTING,
            observedPeakMins = 200.0,  // peak > DIA — nonsensical
            observedDiaMins  = 150.0,
            learningRate     = 0.15,
        )
        val after = learner.getProfile(MealMode.FASTING)
        assertEquals(before.peakMinutes, after.peakMinutes, 0.001)
        assertEquals(before.sampleCount, after.sampleCount)
    }

    @Test fun `observation outside hard limits is clamped not rejected`() {
        // Peak of 5 mins is below PEAK_MIN (35) — should be clamped to 35
        learner.observeBolusCurve(
            mode             = MealMode.FASTING,
            observedPeakMins = 5.0,
            observedDiaMins  = 240.0,
            learningRate     = 0.15,
        )
        val after = learner.getProfile(MealMode.FASTING)
        // Peak should have moved toward 35 (clamped value), not 5
        assertTrue(after.peakMinutes >= LearnedInsulinProfile.PEAK_MIN_MINUTES, "Peak should not go below PEAK_MIN")
    }

    // ── Mode independence ────────────────────────────────────────────────────

    @Test fun `updating one mode does not affect another`() {
        val mealBefore = learner.getProfile(MealMode.LUNCH).peakMinutes
        learner.observeBolusCurve(MealMode.FASTING, 55.0, 220.0, 0.15)
        val mealAfter = learner.getProfile(MealMode.LUNCH).peakMinutes
        assertEquals(mealBefore, mealAfter, 0.001)
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    @Test fun `reset restores defaults and clears sample count`() {
        repeat(5) { learner.observeBolusCurve(MealMode.FASTING, 55.0, 210.0, 0.15) }
        assertTrue(learner.getProfile(MealMode.FASTING).sampleCount > 0)

        learner.resetProfile(MealMode.FASTING)

        val reset = learner.getProfile(MealMode.FASTING)
        assertEquals(0, reset.sampleCount)
        assertEquals(LearnedInsulinProfile.defaultFor(MealMode.FASTING).peakMinutes, reset.peakMinutes, 0.001)
    }

    // ── LearnedInsulinProfile helpers ────────────────────────────────────────

    @Test fun `isMature is false below 5 samples`() {
        val profile = LearnedInsulinProfile.defaultFor(MealMode.FASTING)
        assertFalse(profile.isMature)
    }

    @Test fun `normalizedConfidence saturates at 1`() {
        var p = LearnedInsulinProfile.defaultFor(MealMode.FASTING)
        repeat(50) {
            p = p.copy(sampleCount = p.sampleCount + 1)
        }
        assertEquals(1.0, p.normalizedConfidence, 0.001)
    }

    @Test fun `JSON round-trip preserves all fields`() {
        val original = LearnedInsulinProfile(
            mode          = MealMode.LUNCH,
            peakMinutes   = 72.3,
            diaMinutes    = 255.7,
            sampleCount   = 12,
            lastUpdatedMs = 1_700_000_000_000L
        )
        val restored = LearnedInsulinProfile.fromJson(original.toJson(), MealMode.LUNCH)
        assertEquals(original.mode,          restored.mode)
        assertEquals(original.peakMinutes,   restored.peakMinutes,   0.001)
        assertEquals(original.diaMinutes,    restored.diaMinutes,    0.001)
        assertEquals(original.sampleCount,   restored.sampleCount)
        assertEquals(original.lastUpdatedMs, restored.lastUpdatedMs)
    }
}
