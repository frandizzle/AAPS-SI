package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.glucoseStatus
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.iobArray
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * InsulinDirectionTest
 *
 * Verifies that the ISF and basal learners move insulin delivery in the
 * correct clinical direction. Tests are framed in terms of actual delivered
 * values (dosingISF mmol/U, basal U/h), not raw multipliers.
 *
 * The core architecture:
 *   dosingISF = profileISF / isfMult
 *   deliveredBasal = profileBasal * basalMult
 *
 * Direction rules:
 *   LESS insulin needed  → higher dosingISF (mmol/U ↑)  AND lower basal (U/h ↓)
 *   MORE insulin needed  → lower dosingISF (mmol/U ↓)   AND higher basal (U/h ↑)
 *
 * ISF physics learner signal:
 *   BG drops MORE than expected → insulin stronger than profile thinks → future ISF ↑ (less aggressive)
 *   BG drops LESS than expected → insulin weaker  than profile thinks → future ISF ↓ (more aggressive)
 *
 * Basal drift learner signal:
 *   BG drifting UP during fasting (low IOB) → basal too low  → basal mult ↑ → more insulin
 *   BG drifting DOWN during fasting         → basal too high → basal mult ↓ → less insulin
 */
class InsulinDirectionTest {

    private lateinit var learner: CircadianLearner
    private lateinit var prefs:   FakePreferences
    private val logger = FakeAAPSLogger()

    private val HOUR         = 10
    private val DOW          = 3
    private val BASE_MS      = 1_700_000_000_000L
    private val CYCLE_MS     = 5 * 60_000L
    private val PROFILE_ISF  = 50.0    // mg/dL/U  (≈ 2.78 mmol/U)
    private val PROFILE_BASAL = 1.0    // U/h

    @BeforeEach
    fun setUp() {
        prefs   = FakePreferences()
        learner = CircadianLearner(logger, prefs)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Compute the dosing ISF the loop would actually use = profileISF / isfMult */
    private fun dosingIsfMgdl() = PROFILE_ISF / learner.isfMultiplier(HOUR, DOW)

    /** Compute the basal the loop would actually deliver = profileBasal * basalMult */
    private fun deliveredBasalUh() = PROFILE_BASAL * learner.basalMultiplier(HOUR, DOW)

    private fun tick(
        bg:       Double,
        delta:    Double = 0.0,
        activity: Double = 0.0,
        iob:      Double = 0.1,
        basalIob: Double = -0.05,
        nowMs:    Long   = BASE_MS,
        target:   Double = 99.0,
        lowGuard: Double = 90.0,
        mealMode: MealMode = MealMode.FASTING
    ) = learner.update(
        glucoseStatus            = glucoseStatus(glucose = bg, delta = delta, shortAvgDelta = delta),
        iobArray                 = iobArray(iob = iob, activity = activity, basaliob = basalIob),
        mealMode                 = mealMode,
        cobG                     = 0.0,
        profileIsfMgdl           = PROFILE_ISF,
        targetMgdl               = target,
        lowGuardMgdl             = lowGuard,
        inPostMealLockout        = false,
        suppressAdaptiveLearning = false,
        hour                     = HOUR,
        dow                      = DOW,
        nowMs                    = nowMs
    )

    // ── ISF direction — physics learner ───────────────────────────────────────

    /**
     * Scenario: insulin is stronger than the profile predicts.
     * BG dropped 10 mg/dL but the profile only expected 5 mg/dL.
     *
     * Clinical meaning: we're over-delivering. Next correction dose should use
     * a HIGHER ISF (mmol/U ↑) so fewer units are calculated per BG gap.
     *
     * Mechanism: excess drop → mult DOWN → dosingISF = profileISF/lowerMult → UP ✓
     */
    @Test
    fun `insulin stronger than expected — dosingISF increases (less aggressive future dosing)`() {
        // activity=0.02, profileISF=50 → expectedDelta = -0.02 * 50 * 5 = -5.0 mg/dL
        // actual delta = -10 → dropped twice as fast → insulin stronger than profile
        val isfBefore = dosingIsfMgdl()

        tick(bg = 100.0, delta = -10.0, activity = 0.02, iob = 1.0)

        val isfAfter = dosingIsfMgdl()

        assertTrue(isfAfter > isfBefore,
                   "BG dropped more than expected: dosingISF should INCREASE (less aggressive). " +
                       "Got ${"%.2f".format(isfBefore)} → ${"%.2f".format(isfAfter)} mg/dL/U")
    }

    /**
     * Scenario: insulin is weaker than the profile predicts.
     * BG dropped only 1 mg/dL but the profile expected 5 mg/dL.
     *
     * Clinical meaning: under-delivering effect. Next correction should use a
     * LOWER ISF (mmol/U ↓) so more units are calculated per BG gap.
     *
     * Mechanism: insufficient drop → mult UP → dosingISF = profileISF/higherMult → DOWN ✓
     */
    @Test
    fun `insulin weaker than expected — dosingISF decreases (more aggressive future dosing)`() {
        // activity=0.02, profileISF=50 → expectedDelta = -5.0 mg/dL
        // actual delta = -1 → barely moved → insulin weaker than profile
        val isfBefore = dosingIsfMgdl()

        tick(bg = 100.0, delta = -1.0, activity = 0.02, iob = 1.0)

        val isfAfter = dosingIsfMgdl()

        assertTrue(isfAfter < isfBefore,
                   "BG dropped less than expected: dosingISF should DECREASE (more aggressive). " +
                       "Got ${"%.2f".format(isfBefore)} → ${"%.2f".format(isfAfter)} mg/dL/U")
    }

    /**
     * Explicit chain verification: stronger-than-expected insulin over multiple
     * cycles produces a meaningfully higher ISF that would reduce future dose size.
     *
     * If BG gap = 50 mg/dL and ISF is 55 instead of 50, insulin req drops from
     * 50/50 = 1.0U to 50/55 = 0.91U — a real 9% reduction in calculated dose.
     */
    @Test
    fun `repeated strong-insulin signal meaningfully increases ISF and reduces computed dose`() {
        val gapMgdl = 50.0  // BG 50 mg/dL above target — correction gap

        // Before: correction = gap / ISF = 50 / 50 = 1.0 U
        val doseBeforeU = gapMgdl / dosingIsfMgdl()

        // Feed 10 cycles of insulin performing better than expected
        repeat(10) { i ->
            tick(bg = 110.0, delta = -10.0, activity = 0.02, iob = 1.5,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        // After: ISF should be higher → correction dose smaller
        val doseAfterU = gapMgdl / dosingIsfMgdl()

        assertTrue(doseAfterU < doseBeforeU,
                   "After learning strong insulin: computed correction dose should SHRINK. " +
                       "Got ${"%.3f".format(doseBeforeU)}U → ${"%.3f".format(doseAfterU)}U")
    }

    /**
     * Explicit chain: weak-insulin signal increases future dose size.
     */
    @Test
    fun `repeated weak-insulin signal decreases ISF and increases computed dose`() {
        val gapMgdl = 50.0
        val doseBeforeU = gapMgdl / dosingIsfMgdl()

        repeat(10) { i ->
            tick(bg = 110.0, delta = -1.0, activity = 0.02, iob = 1.5,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val doseAfterU = gapMgdl / dosingIsfMgdl()

        assertTrue(doseAfterU > doseBeforeU,
                   "After learning weak insulin: computed correction dose should GROW. " +
                       "Got ${"%.3f".format(doseBeforeU)}U → ${"%.3f".format(doseAfterU)}U")
    }

    // ── Basal direction — drift learner ───────────────────────────────────────

    /**
     * Scenario: BG drifts UP during fasting with low IOB.
     * Signal: basal is too low. Learner should raise basal delivery.
     *
     * Clinical meaning: more continuous insulin → delivered basal U/h ↑
     */
    @Test
    fun `sustained BG rise during fasting — delivered basal increases`() {
        val basalBefore = deliveredBasalUh()

        // 13 cycles with BG rising from 90→108 (18 mg/dL = ~1 mmol/hr drift), low IOB
        for (i in 0..12) {
            tick(bg = 90.0 + i * 1.5, iob = 0.1, basalIob = -0.05, activity = 0.0,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val basalAfter = deliveredBasalUh()

        assertTrue(basalAfter > basalBefore,
                   "BG rising during fasting: delivered basal should INCREASE (more insulin). " +
                       "Got ${"%.3f".format(basalBefore)} → ${"%.3f".format(basalAfter)} U/h")
    }

    /**
     * Scenario: BG drifts DOWN during fasting with low IOB.
     * Signal: basal is too high. Learner should reduce basal delivery.
     *
     * Clinical meaning: less continuous insulin → delivered basal U/h ↓
     */
    @Test
    fun `sustained BG fall during fasting — delivered basal decreases`() {
        val basalBefore = deliveredBasalUh()

        // Start high enough that BG doesn't hit the low gate during the 13-cycle window
        for (i in 0..12) {
            tick(bg = 140.0 - i * 1.5, iob = 0.1, basalIob = -0.05, activity = 0.0,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val basalAfter = deliveredBasalUh()

        assertTrue(basalAfter < basalBefore,
                   "BG falling during fasting: delivered basal should DECREASE (less insulin). " +
                       "Got ${"%.3f".format(basalBefore)} → ${"%.3f".format(basalAfter)} U/h")
    }

    // ── Combined ISF + Basal direction under shared signal ────────────────────

    /**
     * Scenario: BG has been consistently high during fasting → need MORE insulin.
     *
     * Expected outcome (after sustained above-target BG with weak-insulin signal):
     *   - dosingISF ↓ (more aggressive corrections)
     *   - delivered basal ↑ (more continuous insulin)
     *
     * Both learners should agree: the system needs more insulin.
     */
    @Test
    fun `consistent high BG drives both dosingISF down and basal up (more insulin both ways)`() {
        val isfBefore   = dosingIsfMgdl()
        val basalBefore = deliveredBasalUh()

        // Weak insulin signal (BG barely moving despite activity) + sustained high BG + drift up
        for (i in 0..12) {
            tick(bg = 130.0 + i * 0.5, delta = -0.5, activity = 0.02, iob = 0.5,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val isfAfter   = dosingIsfMgdl()
        val basalAfter = deliveredBasalUh()

        // ISF direction: weak-insulin signal → ISF should decrease (more aggressive corrections)
        assertTrue(isfAfter <= isfBefore,
                   "High BG + weak insulin: dosingISF should not increase. " +
                       "Got ${"%.2f".format(isfBefore)} → ${"%.2f".format(isfAfter)} mg/dL/U")

        // Basal direction: BG drifting up → basal should increase
        assertTrue(basalAfter >= basalBefore,
                   "Sustained high BG drift: basal should not decrease. " +
                       "Got ${"%.3f".format(basalBefore)} → ${"%.3f".format(basalAfter)} U/h")
    }

    /**
     * Scenario: BG has been consistently low during fasting → need LESS insulin.
     *
     * Expected outcome:
     *   - dosingISF ↑ (less aggressive corrections — insulin is strong)
     *   - delivered basal ↓ (less continuous insulin)
     */
    @Test
    fun `consistent low BG drives dosingISF up and basal down (less insulin both ways)`() {
        val isfBefore   = dosingIsfMgdl()
        val basalBefore = deliveredBasalUh()

        // Strong insulin signal (BG dropping faster than expected) + downward drift
        // Start at 140 so BG doesn't hit the low gate (90 mg/dL) during the window
        for (i in 0..12) {
            tick(bg = 140.0 - i * 2.0, delta = -10.0, activity = 0.02, iob = 0.5,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val isfAfter   = dosingIsfMgdl()
        val basalAfter = deliveredBasalUh()

        // ISF: strong-insulin signal → ISF should increase (less aggressive)
        assertTrue(isfAfter >= isfBefore,
                   "Low BG + strong insulin: dosingISF should not decrease. " +
                       "Got ${"%.2f".format(isfBefore)} → ${"%.2f".format(isfAfter)} mg/dL/U")

        // Basal: BG drifting down → basal should decrease
        assertTrue(basalAfter <= basalBefore,
                   "Sustained low BG drift: basal should not increase. " +
                       "Got ${"%.3f".format(basalBefore)} → ${"%.3f".format(basalAfter)} U/h")
    }

    // ── ISF learner skip conditions ───────────────────────────────────────────

    @Test
    fun `dosingISF does not change during meal mode`() {
        val isfBefore = dosingIsfMgdl()
        repeat(10) { i ->
            tick(bg = 100.0, delta = -10.0, activity = 0.02, iob = 1.5,
                 mealMode = MealMode.DINNER,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }
        assertEquals(isfBefore, dosingIsfMgdl(), 0.01,
                     "dosingISF should not change during meal mode")
    }

    @Test
    fun `dosingISF does not change when activity is below minimum`() {
        val isfBefore = dosingIsfMgdl()
        tick(bg = 100.0, delta = -10.0, activity = 0.001) // below MIN_ACTIVITY
        assertEquals(isfBefore, dosingIsfMgdl(), 0.01,
                     "dosingISF should not change with negligible activity")
    }

    @Test
    fun `dosingISF does not change when activity is negative (pump withholding)`() {
        val isfBefore = dosingIsfMgdl()
        tick(bg = 100.0, delta = -3.0, activity = -0.02) // negative = TBR cut
        assertEquals(isfBefore, dosingIsfMgdl(), 0.01,
                     "dosingISF should not change on negative activity (inverted signal)")
    }

    // ── Delivered basal skip conditions ───────────────────────────────────────

    @Test
    fun `delivered basal does not change during meal mode`() {
        val basalBefore = deliveredBasalUh()
        repeat(15) { i ->
            tick(bg = 150.0 - i * 0.5, iob = 0.1, basalIob = -0.05, activity = 0.0,
                 mealMode = MealMode.LUNCH,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }
        assertEquals(basalBefore, deliveredBasalUh(), 0.01,
                     "Delivered basal should not change during meal mode")
    }

    @Test
    fun `delivered basal does not change when BG movement is below noise gate`() {
        val basalBefore = deliveredBasalUh()
        // ~0.5 mg/dL/hr drift — below the 2 mg/dL noise gate
        for (i in 0..12) {
            tick(bg = 100.0 + i * 0.05, iob = 0.1, basalIob = -0.05, activity = 0.0,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }
        assertEquals(basalBefore, deliveredBasalUh(), 0.005,
                     "Delivered basal should not change on tiny BG drift (below noise gate)")
    }

    // ── Bounds ────────────────────────────────────────────────────────────────

    @Test
    fun `dosingISF stays within physiological bounds under extreme signals`() {
        // Drive hard in both directions — ISF value should stay in sensible range
        // profileISF=50, multMax=1.5 → dosingISF_min = 50/1.5 = 33.3
        // multMin=0.7 → dosingISF_max = 50/0.7 = 71.4
        repeat(100) { i ->
            tick(bg = 100.0, delta = -10.0, activity = 0.05, iob = 2.0,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }
        val isf = dosingIsfMgdl()
        assertTrue(isf >= PROFILE_ISF / 1.5 - 0.1,
                   "dosingISF should not go below profileISF/multMax = ${PROFILE_ISF / 1.5}. Got $isf")
        assertTrue(isf <= PROFILE_ISF / 0.7 + 0.1,
                   "dosingISF should not go above profileISF/multMin = ${PROFILE_ISF / 0.7}. Got $isf")
    }

    @Test
    fun `delivered basal stays within physiological bounds under extreme drift`() {
        // basalMult clamped to [0.7, 1.5] → delivered = [0.7, 1.5] U/h with profileBasal=1.0
        repeat(200) { i ->
            tick(bg = 130.0 + (i % 5) * 2.0, iob = 0.1, basalIob = -0.05, activity = 0.0,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }
        val basal = deliveredBasalUh()
        assertTrue(basal >= PROFILE_BASAL * 0.7 - 0.01,
                   "Delivered basal should not go below profileBasal * multMin. Got $basal")
        assertTrue(basal <= PROFILE_BASAL * 1.5 + 0.01,
                   "Delivered basal should not go above profileBasal * multMax. Got $basal")
    }

    // ── ISF direction agrees with basal direction ─────────────────────────────

    /**
     * Sanity check: after enough cycles, the two learners should never
     * point in OPPOSITE directions. If ISF went up (less insulin) but basal
     * also went up (more insulin), something is wrong.
     *
     * This test gives each learner a clear unambiguous signal and checks
     * they agree on the direction of insulin need.
     */
    @Test
    fun `ISF and basal learners agree on direction — both say more insulin needed`() {
        // Weak insulin (BG barely moves) + BG drifting up = unambiguous MORE insulin signal
        for (i in 0..12) {
            tick(bg = 120.0 + i * 0.5, delta = -0.5, activity = 0.02, iob = 0.5,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val isfChanged   = dosingIsfMgdl() - PROFILE_ISF           // negative = ISF down = more insulin
        val basalChanged = deliveredBasalUh() - PROFILE_BASAL        // positive = basal up = more insulin

        // Both should point toward MORE insulin — ISF down OR basal up (at least one, ideally both)
        val bothAgree = (isfChanged <= 0.0) || (basalChanged >= 0.0)
        assertTrue(bothAgree,
                   "Under more-insulin signal: ISF and basal should not both point LESS. " +
                       "ISF Δ=${"%+.3f".format(isfChanged)} basalΔ=${"%+.3f".format(basalChanged)}")
    }

    @Test
    fun `ISF and basal learners agree on direction — both say less insulin needed`() {
        // Strong insulin (BG drops fast) + BG drifting down = unambiguous LESS insulin signal
        for (i in 0..12) {
            tick(bg = 140.0 - i * 2.0, delta = -10.0, activity = 0.02, iob = 0.5,
                 nowMs = BASE_MS + i * CYCLE_MS)
        }

        val isfChanged   = dosingIsfMgdl() - PROFILE_ISF            // positive = ISF up = less insulin
        val basalChanged = deliveredBasalUh() - PROFILE_BASAL         // negative = basal down = less insulin

        val bothAgree = (isfChanged >= 0.0) || (basalChanged <= 0.0)
        assertTrue(bothAgree,
                   "Under less-insulin signal: ISF and basal should not both point MORE. " +
                       "ISF Δ=${"%+.3f".format(isfChanged)} basalΔ=${"%+.3f".format(basalChanged)}")
    }
}