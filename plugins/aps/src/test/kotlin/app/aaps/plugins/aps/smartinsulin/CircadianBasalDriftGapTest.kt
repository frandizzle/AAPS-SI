package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The basal drift window must never span a cycle the learner skipped.
 *
 * Drift is a difference across the window: BG movement minus the insulin effect integrated
 * alongside it. A skipped cycle adds neither, so insulin that acted during the skip is missing
 * from the compensation while the BG movement it caused is not — and the residual reads as
 * unexplained, which is the "raise basal" direction. Carbs logged without a meal mode (the COB
 * skip) are exactly that case: the samples either side of the meal were both clean fasting, so
 * nothing else caught it.
 */
class CircadianBasalDriftGapTest {

    private lateinit var learner: CircadianLearner
    private val logger = FakeAAPSLogger(collect = false)

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val BUCKET_CENTRE = 30

    @BeforeEach
    fun setUp() { learner = CircadianLearner(logger, FakePreferences()) }

    private fun glucoseStatus(glucose: Double): GlucoseStatus =
        mock<GlucoseStatus>().apply {
            whenever(this.glucose).thenReturn(glucose)
            whenever(this.shortAvgDelta).thenReturn(0.0)   // under Signal 0's noise gate
            whenever(this.delta).thenReturn(0.0)
            whenever(this.date).thenReturn(BASE_MS)
        }

    /** One quiet fasting cycle: no activity, no IOB, so only the drift signal can ever fire. */
    private fun cycle(bg: Double, nowMs: Long, cobG: Double = 0.0) =
        learner.update(
            glucoseStatus  = glucoseStatus(bg),
            iobArray       = arrayOf(IobTotal(time = 0, iob = 0.0, activity = 0.0, basaliob = 0.0)),
            mealMode       = MealMode.FASTING,
            cobG           = cobG,
            profileIsfMgdl = 50.0,
            targetMgdl     = 100.0,
            aggressiveness = 1.0,
            hour = 0, dow = 0, minute = BUCKET_CENTRE, nowMs = nowMs
        )

    private fun basal() = learner.basalMultiplier(0, 0, BUCKET_CENTRE)

    @Test
    fun `twelve uninterrupted samples let the drift signal fire`() {
        // Control for the test below: 12 samples rising 0.3 mg/dL per cycle is ~3.9 mg/dL/hr,
        // clear of the 2.0 noise floor.
        var t = BASE_MS
        for (i in 0..11) { cycle(bg = 100.0 + i * 0.3, nowMs = t); t += CYCLE_MS }
        assertTrue(basal() > 1.0, "drift should have fired on the 12th sample, basal=${basal()}")
    }

    @Test
    fun `a COB cycle empties the window so drift cannot difference across it`() {
        var t = BASE_MS
        for (i in 0..10) { cycle(bg = 100.0 + i * 0.3, nowMs = t); t += CYCLE_MS }
        // Carbs on board, no meal mode — the learner skips this cycle entirely.
        cycle(bg = 103.0, nowMs = t, cobG = 10.0); t += CYCLE_MS
        cycle(bg = 103.3, nowMs = t)
        assertEquals(1.0, basal(), 1e-9,
                     "the window should have been dropped at the skip, leaving one sample — nothing to fire on")
    }

    @Test
    fun `learning resumes normally once the skip is over`() {
        var t = BASE_MS
        cycle(bg = 100.0, nowMs = t, cobG = 10.0); t += CYCLE_MS
        for (i in 0..11) { cycle(bg = 100.0 + i * 0.3, nowMs = t); t += CYCLE_MS }
        assertTrue(basal() > 1.0, "a full clean window after the skip should still fire, basal=${basal()}")
    }
}
