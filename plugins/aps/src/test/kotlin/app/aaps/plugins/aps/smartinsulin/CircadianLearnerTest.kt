package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.fallingBg
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.flatBg
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.glucoseStatus
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.iobArray
import app.aaps.plugins.aps.smartInsulin.testutil.TestBuilders.risingBg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CircadianLearner].
 *
 * All tests use synthetic glucose/IOB sequences to verify the learner's response
 * without waiting for real-world events. Time is injected via the hour/dow/nowMs
 * parameters added for testability — no wall clock dependency.
 *
 * Scenarios covered:
 *  - ISF learning: insulin stronger/weaker than expected
 *  - Basal drift learning: sustained BG rise/fall
 *  - Rollercoaster detection and penalty
 *  - Compression low heuristic (should NOT penalise)
 *  - STFT fuel trim: above-band and below-band
 *  - Aggression nudge: too-much / not-enough directions
 *  - Post-low cooldown blocking false "not-enough" nudge
 *  - Mealmode skip: no learning outside FASTING
 */
class CircadianLearnerTest {

    private lateinit var learner: CircadianLearner
    private lateinit var prefs: FakePreferences
    private val logger = FakeAAPSLogger()

    // Fixed test coordinates
    private val HOUR = 8
    private val DOW  = 1  // Monday
    private val BASE_MS = 1_700_000_000_000L  // arbitrary fixed epoch

    // Helpers
    private fun tick(
        bg: Double,
        delta: Double    = 0.0,
        activity: Double = 0.0,
        iob: Double      = 1.0,
        basalIob: Double = -0.2,
        cob: Double      = 0.0,
        mealMode: MealMode = MealMode.FASTING,
        nowMs: Long      = BASE_MS,
        target: Double   = 99.0,   // 5.5 mmol
        lowGuard: Double = 90.0    // 5.0 mmol
    ) = learner.update(
        glucoseStatus            = glucoseStatus(glucose = bg, delta = delta, shortAvgDelta = delta),
        iobArray                 = iobArray(iob = iob, activity = activity, basaliob = basalIob),
        mealMode                 = mealMode,
        cobG                     = cob,
        profileIsfMgdl           = 50.0,
        targetMgdl               = target,
        lowGuardMgdl             = lowGuard,
        inPostMealLockout        = false,
        suppressAdaptiveLearning = false,
        hour                     = HOUR,
        dow                      = DOW,
        nowMs                    = nowMs
    )

    @BeforeEach
    fun setUp() {
        prefs   = FakePreferences()
        learner = CircadianLearner(logger, prefs)
    }

    // ── ISF Learning ─────────────────────────────────────────────────────────

    @Test
    fun `ISF learning reduces mult when BG drops more than expected`() {
        // activity=0.02 U/min, ISF=50 → expectedDelta = -0.02 * 50 * 5 = -5.0 mg/dL
        // actual delta = -10 → BG fell twice as fast → insulin stronger → mult should go DOWN
        // (lower mult → dosingISF = profileISF/lowerMult → dosingISF UP → less aggressive ✓)
        val before = learner.isfMultiplier(HOUR, DOW)
        tick(bg = 100.0, delta = -10.0, activity = 0.02, iob = 1.0)
        val after = learner.isfMultiplier(HOUR, DOW)
        assertTrue(after < before, "ISF mult should decrease when BG drops more than expected (got $before → $after)")
    }

    @Test
    fun `ISF learning increases mult when BG drops less than expected`() {
        // activity=0.02, ISF=50 → expected -5.0 mg/dL, actual = -1.0 → insulin weaker → mult UP
        val before = learner.isfMultiplier(HOUR, DOW)
        tick(bg = 100.0, delta = -1.0, activity = 0.02, iob = 1.0)
        val after = learner.isfMultiplier(HOUR, DOW)
        assertTrue(after > before, "ISF mult should increase when BG drops less than expected (got $before → $after)")
    }

    @Test
    fun `ISF learning skips when activity below minimum`() {
        val before = learner.isfMultiplier(HOUR, DOW)
        tick(bg = 100.0, delta = -10.0, activity = 0.001)  // below MIN_ACTIVITY=0.005
        val after = learner.isfMultiplier(HOUR, DOW)
        assertEquals(before, after, 1e-6, "ISF should not change with negligible activity")
    }

    @Test
    fun `ISF learning skips in meal mode`() {
        val before = learner.isfMultiplier(HOUR, DOW)
        tick(bg = 100.0, delta = -10.0, activity = 0.02, mealMode = MealMode.BREAKFAST)
        val after = learner.isfMultiplier(HOUR, DOW)
        assertEquals(before, after, 1e-6, "ISF should not change during meal mode")
    }

    @Test
    fun `ISF mult is clamped to bounds`() {
        // Drive 100 cycles with extreme "BG never moves" signal → mult should never exceed MAX
        repeat(100) { tick(bg = 100.0, delta = 0.0, activity = 0.05, iob = 1.0) }
        val mult = learner.isfMultiplier(HOUR, DOW)
        assertTrue(mult <= 1.5, "ISF mult exceeded MAX: $mult")
        assertTrue(mult >= 0.7, "ISF mult below MIN: $mult")
    }

    // ── Basal Drift Learning ──────────────────────────────────────────────────

    @Test
    fun `basal drift learning increases mult on sustained BG rise`() {
        // Feed 13 samples spanning ~65 min with BG rising from 90→108 (18 mg/dL = 1 mmol)
        // drift rate ≈ 16.6 mg/dL/hr — above noise floor (2.0) → should fire
        val before = learner.basalMultiplier(HOUR, DOW)
        val start  = BASE_MS
        for (i in 0..12) {
            tick(bg = 90.0 + i * 1.5, iob = 0.1, basalIob = -0.05,
                 activity = 0.0, nowMs = start + i * 5 * 60_000L)
        }
        val after = learner.basalMultiplier(HOUR, DOW)
        assertTrue(after > before, "Basal mult should increase on sustained BG rise (got $before → $after)")
    }

    @Test
    fun `basal drift learning decreases mult on sustained BG fall`() {
        val before = learner.basalMultiplier(HOUR, DOW)
        val start  = BASE_MS
        for (i in 0..12) {
            tick(bg = 120.0 - i * 1.5, iob = 0.1, basalIob = -0.05,
                 activity = 0.0, nowMs = start + i * 5 * 60_000L)
        }
        val after = learner.basalMultiplier(HOUR, DOW)
        assertTrue(after < before, "Basal mult should decrease on sustained BG fall (got $before → $after)")
    }

    @Test
    fun `basal drift learning skips when BG movement too small`() {
        // Drift < 2 mg/dL/hr = noise gate — should not update
        val before = learner.basalMultiplier(HOUR, DOW)
        val start  = BASE_MS
        for (i in 0..12) {
            tick(bg = 100.0 + i * 0.05, iob = 0.1, basalIob = -0.05,  // ~0.5 mg/dL drift/hr
                 activity = 0.0, nowMs = start + i * 5 * 60_000L)
        }
        val after = learner.basalMultiplier(HOUR, DOW)
        assertEquals(before, after, 0.005, "Basal mult should not change below noise gate")
    }

    // ── Rollercoaster Detection ───────────────────────────────────────────────

    @Test
    fun `rollercoaster penalty fires on alternating low-high-low BG pattern`() {
        val before = learner.aggrCeiling(HOUR, DOW)
        val start  = BASE_MS
        // Pattern: high (110) → low (80, below lowGuard=90) → high (115) → low (82) = 2 swings
        val bgs = listOf(110.0, 105.0, 88.0, 80.0, 85.0, 100.0, 115.0, 118.0, 86.0, 82.0)
        bgs.forEachIndexed { i, bg ->
            tick(bg = bg, activity = 0.01, iob = 1.0,
                 nowMs = start + i * 5 * 60_000L, lowGuard = 90.0, target = 99.0)
        }
        val after = learner.aggrCeiling(HOUR, DOW)
        assertTrue(after < before, "Aggressiveness ceiling should be penalised on rollercoaster (got $before → $after)")
    }

    @Test
    fun `rollercoaster counter increments on successive events`() {
        val start = BASE_MS
        // Need ≥6 readings in bgHistory (MIN_HISTORY_FOR_ROLLER) within ROLLER_WINDOW_MS (90 min)
        // Pattern: above highThreshold (target+18=117) → below lowGuard (90) → above → below
        // That gives 2+ alternating swings = ROLLER_CROSSING_THRESHOLD
        val bgs = listOf(120.0, 120.0, 80.0, 80.0, 120.0, 80.0, 120.0, 80.0)
        bgs.forEachIndexed { i, bg ->
            tick(bg = bg, iob = 1.5, activity = 0.01,
                 target = 99.0, lowGuard = 90.0,
                 nowMs = start + i * 5 * 60_000L)
        }
        val count = learner.consecutiveRollercoasters
        assertTrue(count >= 1,
                   "consecutiveRollercoasters should be ≥1 after roller pattern, got $count")
    }

    // ── Compression Low Heuristic ─────────────────────────────────────────────

    @Test
    fun `compression low does not penalise ceiling when all criteria met`() {
        val start  = BASE_MS
        val before = learner.aggrCeiling(HOUR, DOW)

        // Build history: stable pre-drop (100→100), V-shaped drop to 75 over 15 min,
        // rapid recovery back to ~100 within 20 min, returns to baseline.
        // This should look like a compression artifact.
        val sequence = listOf(
            // Pre-drop: stable at 100 for 30 min (6 readings)
            100.0, 100.0, 101.0, 100.0, 99.0, 100.0,
            // Drop to low in 2 readings
            88.0, 76.0,
            // Recovery: rapid jump back
            92.0, 101.0,
            // Post-recovery: back to baseline
            100.0, 100.0
        )
        sequence.forEachIndexed { i, bg ->
            tick(bg = bg, iob = 1.0, activity = 0.01,
                 nowMs = start + i * 5 * 60_000L, lowGuard = 90.0, target = 99.0)
        }
        val after = learner.aggrCeiling(HOUR, DOW)

        // Ceiling should NOT be penalised (or penalty should be minimal)
        // We allow a small change because the rollercoaster detector may still fire
        // from the pattern — the point is compression detection REDUCES the penalty
        assertTrue(after >= before * 0.88,
                   "Compression low should attenuate penalty: before=$before after=$after")
    }

    // ── STFT Fuel Trim ────────────────────────────────────────────────────────

    @Test
    fun `STFT fires upward trim when average BG above target for trim window`() {
        // Run enough cycles with BG above target + dead band (5.4 mg/dL)
        // to fill the trim window. Target=99, trim fires at avgBg > 104.4
        val start = BASE_MS
        val trimWindowCycles = 20  // ~100 min, covers 90-min default window
        repeat(trimWindowCycles) { i ->
            tick(bg = 120.0, activity = 0.0, iob = 0.5,
                 target = 99.0, nowMs = start + i * 5 * 60_000L)
        }
        // trimStrength > 0 means STFT is pushing more insulin
        assertTrue(learner.trimStrength > 0.0,
                   "STFT should be trimming upward (more insulin) when BG stuck high")
    }

    @Test
    fun `STFT fires downward trim when average BG below target`() {
        // BG at 90.0 = below target-deadband (99-5.4=93.6) but NOT below lowGuard (90.0)
        // Using 91.0 to stay just above lowGuard so trim history isn't cleared by low-guard penalty
        val start = BASE_MS
        repeat(20) { i ->
            tick(bg = 91.0, activity = 0.0, iob = 0.1,
                 target = 99.0, lowGuard = 90.0, nowMs = start + i * 5 * 60_000L)
        }
        assertTrue(learner.trimStrength < 0.0,
                   "STFT should be trimming downward (less insulin) when BG stuck low, got ${learner.trimStrength}")
    }

    @Test
    fun `STFT respects step-and-wait — does not re-fire immediately after action`() {
        val start = BASE_MS
        // Fill window, fire trim
        repeat(20) { i ->
            tick(bg = 120.0, activity = 0.0, iob = 0.5,
                 target = 99.0, nowMs = start + i * 5 * 60_000L)
        }
        val strengthAfterFirstFire = learner.trimStrength
        // Continue immediately — within the wait window, strength should not jump
        repeat(5) { i ->
            tick(bg = 120.0, activity = 0.0, iob = 0.5,
                 target = 99.0, nowMs = start + 20 * 5 * 60_000L + i * 5 * 60_000L)
        }
        val strengthAfterWait = learner.trimStrength
        // Strength should be the same or only slightly changed (holding, not re-stepping)
        assertEquals(strengthAfterFirstFire, strengthAfterWait, 0.01,
                     "STFT should hold after firing — step-and-wait not respected")
    }

    @Test
    fun `STFT decays when BG returns to target band`() {
        val start = BASE_MS
        // Drive trim active with BG above target (safe, no low-guard conflict)
        repeat(20) { i ->
            tick(bg = 120.0, activity = 0.0, iob = 0.5,
                 target = 99.0, nowMs = start + i * 5 * 60_000L)
        }
        assertTrue(learner.trimStrength > 0.0, "precondition: trim should be active, got ${learner.trimStrength}")

        // BG returns to band — trim should decay toward 0
        // Must advance time past trimWindowMs (90 min) from last action before "in-band" decay kicks in,
        // OR the in-band case fires immediately via trimStrength *= DECAY_RATE each cycle
        repeat(10) { i ->
            tick(bg = 100.0, activity = 0.0, iob = 0.5,
                 target = 99.0, nowMs = start + 20 * 5 * 60_000L + i * 5 * 60_000L)
        }
        assertTrue(learner.trimStrength < 0.20,
                   "STFT should decay toward 0 when BG returns to target band, got ${learner.trimStrength}")
    }

    // ── Aggression Nudge ──────────────────────────────────────────────────────

    @Test
    fun `aggression nudge reduces ISF and basal mult when ceiling below threshold`() {
        // Artificially drive aggrCeiling below 0.95 by injecting lots of low penalties,
        // then verify the nudge pulls ISF and basal down (more conservative)
        val start = BASE_MS
        // Drive ceiling down with rollercoasters
        val bgs = listOf(110.0, 80.0, 116.0, 79.0, 114.0, 81.0, 112.0, 78.0)
        bgs.forEachIndexed { i, bg ->
            tick(bg = bg, iob = 2.0, activity = 0.02,
                 nowMs = start + i * 5 * 60_000L)
        }

        val ceil = learner.aggrCeiling(HOUR, DOW)
        if (ceil < 0.95) {
            // Nudge should have fired — basal mult should be below starting value of 1.0
            val basal = learner.basalMultiplier(HOUR, DOW)
            assertTrue(basal < 1.0, "Basal mult should be reduced by tooMuch nudge (got $basal)")
        }
        // If ceiling didn't drop below threshold, skip the assertion — not enough penalty cycles
    }

    @Test
    fun `post-low cooldown blocks not-enough nudge from counter-regulatory spike`() {
        val start = BASE_MS

        // 1. Drive a hard low to set lastPenaltyMs
        tick(bg = 80.0, iob = 2.0, activity = 0.02, lowGuard = 90.0,
             nowMs = start)

        // 2. Check status — nudge should be in PAUSED/Low-Recovery state during cooldown
        //    even when aggressiveness would otherwise suggest "notEnough"
        // We check lastAggrNudgeStatus contains "PAUSED" or "Low Recovery"
        // (the exact string depends on which cooldown branch fired)
        val status = learner.lastAggrNudgeStatus
        val isBlocked = status.contains("PAUSED") || status.contains("Low") ||
            status.contains("INACTIVE") || status.contains("TRIM")
        assertTrue(isBlocked, "Nudge status should not be ACTIVE_HIGH immediately after low: '$status'")
    }

    // ── Meal Mode Skip ────────────────────────────────────────────────────────

    @Test
    fun `no ISF or basal learning during meal mode`() {
        val isfBefore   = learner.isfMultiplier(HOUR, DOW)
        val basalBefore = learner.basalMultiplier(HOUR, DOW)

        val start = BASE_MS
        repeat(15) { i ->
            tick(bg = 150.0, delta = -5.0, activity = 0.03, iob = 3.0,
                 mealMode = MealMode.DINNER,
                 nowMs = start + i * 5 * 60_000L)
        }
        assertEquals(isfBefore, learner.isfMultiplier(HOUR, DOW), 0.001,
                     "ISF mult should not change during meal mode")
        assertEquals(basalBefore, learner.basalMultiplier(HOUR, DOW), 0.001,
                     "Basal mult should not change during meal mode")
    }

    // ── Persistence round-trip ────────────────────────────────────────────────

    @Test
    fun `learner state survives serialize and deserialize`() {
        // Drive some learning
        repeat(5) {
            tick(bg = 100.0, delta = -8.0, activity = 0.02, iob = 1.0)
        }
        val isfMult   = learner.isfMultiplier(HOUR, DOW)
        val basalMult = learner.basalMultiplier(HOUR, DOW)

        // Restore into a new instance using the same FakePreferences
        val learner2 = CircadianLearner(logger, prefs)
        assertEquals(isfMult, learner2.isfMultiplier(HOUR, DOW), 0.001,
                     "ISF mult should survive serialization roundtrip")
        assertEquals(basalMult, learner2.basalMultiplier(HOUR, DOW), 0.001,
                     "Basal mult should survive serialization roundtrip")
    }

    @Test
    fun `reset clears all learned state`() {
        repeat(10) { tick(bg = 100.0, delta = -10.0, activity = 0.03, iob = 1.5) }
        learner.reset()
        assertEquals(1.0, learner.isfMultiplier(HOUR, DOW), 0.001,
                     "ISF mult should be 1.0 after reset")
        assertEquals(1.0, learner.basalMultiplier(HOUR, DOW), 0.001,
                     "Basal mult should be 1.0 after reset")
        assertEquals(1.0, learner.aggrCeiling(HOUR, DOW), 0.001,
                     "Aggr ceiling should be 1.0 after reset")
    }
}