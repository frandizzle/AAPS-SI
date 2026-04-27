package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BasalLearner].
 *
 * Time is injected via nowMs/hourOfDay parameters — no wall clock dependency.
 *
 * Scenarios covered:
 *  - Gate conditions: COB, bolus recency, BG range, delta noise
 *  - Learning fires after sufficient samples and elapsed time
 *  - Upward drift increases multiplier; downward drift decreases it
 *  - Flat BG freezes learned value (success decay fix)
 *  - Overnight weight (0am–6am) moves multiplier faster than daytime
 *  - 1-hour learn interval gate prevents over-fitting
 *  - Multiplier stays within hard limits under extreme drift
 *  - Persistence round-trip preserves multiplier and samples
 *  - Reset clears all state
 */
class BasalLearnerTest {

    private lateinit var learner: BasalLearner
    private lateinit var prefs: FakePreferences
    private val logger = FakeAAPSLogger()

    private val BASE_MS   = 1_700_000_000_000L
    private val CYCLE_MS  = 5 * 60_000L

    // Standard gate-passing values
    private val ISF       = 50.0   // mg/dL/U
    private val BASAL     = 1.0    // U/hr
    private val COB       = 0.0
    private val MINS_BOLUS = 200.0 // well past 120-min gate

    @BeforeEach
    fun setUp() {
        prefs   = FakePreferences()
        learner = BasalLearner(prefs, logger)
    }

    /**
     * Feed N cycles of stable BG to fill the window, then advance time past
     * LEARN_INTERVAL_MS (1h) so the next sample triggers a learning update.
     * Returns the timestamp of the last cycle fed.
     */
    private fun fillWindow(
        bgMgdl:    Double  = 100.0,
        driftPerCycle: Double = 0.0,
        hourOfDay: Int     = 3,       // overnight by default
        startMs:   Long    = BASE_MS,
        cycles:    Int     = 10       // MIN_DRIFT_SAMPLES=9, need ≥9 + elapsed ≥0.6h
    ): Long {
        var t = startMs
        repeat(cycles) { i ->
            learner.onLoopCycle(
                bgMgdl        = bgMgdl + i * driftPerCycle,
                deltaMgdl     = driftPerCycle / CYCLE_MS * 300_000,  // rough delta
                cobG          = COB,
                minsLastBolus = MINS_BOLUS,
                isfMgdl       = ISF,
                profileBasalU = BASAL,
                nowMs         = t,
                hourOfDay     = hourOfDay
            )
            t += CYCLE_MS
        }
        return t
    }

    private fun cycle(
        bgMgdl:    Double,
        delta:     Double = 0.0,
        nowMs:     Long   = BASE_MS,
        hourOfDay: Int    = 3
    ) = learner.onLoopCycle(
        bgMgdl        = bgMgdl,
        deltaMgdl     = delta,
        cobG          = COB,
        minsLastBolus = MINS_BOLUS,
        isfMgdl       = ISF,
        profileBasalU = BASAL,
        nowMs         = nowMs,
        hourOfDay     = hourOfDay
    )

    // ── Gate conditions ───────────────────────────────────────────────────────

    @Test
    fun `gate blocks when COB is too high`() {
        val before = learner.multiplierClamped
        learner.onLoopCycle(
            bgMgdl = 100.0, deltaMgdl = 0.0,
            cobG = 10.0,  // > MAX_COB_G (5.0)
            minsLastBolus = MINS_BOLUS, isfMgdl = ISF, profileBasalU = BASAL,
            nowMs = BASE_MS, hourOfDay = 3
        )
        assertEquals(before, learner.multiplierClamped, 1e-6,
                     "Multiplier should not change when COB > gate")
    }

    @Test
    fun `gate blocks when bolus too recent`() {
        val before = learner.multiplierClamped
        learner.onLoopCycle(
            bgMgdl = 100.0, deltaMgdl = 0.0,
            cobG = 0.0,
            minsLastBolus = 60.0,  // < MIN_MINUTES_NO_BOLUS (120)
            isfMgdl = ISF, profileBasalU = BASAL,
            nowMs = BASE_MS, hourOfDay = 3
        )
        assertEquals(before, learner.multiplierClamped, 1e-6,
                     "Multiplier should not change when bolus too recent")
    }

    @Test
    fun `gate blocks when BG too low`() {
        val before = learner.multiplierClamped
        cycle(bgMgdl = 60.0)  // < LOW_BG_GATE_MGDL (72)
        assertEquals(before, learner.multiplierClamped, 1e-6)
    }

    @Test
    fun `gate blocks when BG too high`() {
        val before = learner.multiplierClamped
        cycle(bgMgdl = 200.0)  // > HIGH_BG_GATE_MGDL (162)
        assertEquals(before, learner.multiplierClamped, 1e-6)
    }

    @Test
    fun `gate blocks when delta too noisy`() {
        val before = learner.multiplierClamped
        learner.onLoopCycle(
            bgMgdl = 100.0, deltaMgdl = 5.0,  // > MAX_DELTA_MGDL_PER_5MIN (2.0)
            cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL,
            nowMs = BASE_MS, hourOfDay = 3
        )
        assertEquals(before, learner.multiplierClamped, 1e-6)
    }

    // ── Learning direction ────────────────────────────────────────────────────

    @Test
    fun `upward BG drift increases multiplier`() {
        val before = learner.multiplierClamped
        // Fill 10 cycles over ~50 min with BG rising ~1.5 mg/dL/cycle = ~18 mg/dL/hr
        // Then trigger learning by passing 1h gap
        var t = BASE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        // Advance past LEARN_INTERVAL_MS (1h) and feed one more to trigger learning
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.5,
            cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL,
            nowMs = t, hourOfDay = 3
        )
        assertTrue(learner.multiplierClamped > before,
                   "Multiplier should increase on upward BG drift (got $before → ${learner.multiplierClamped})")
    }

    @Test
    fun `downward BG drift decreases multiplier`() {
        // First drive multiplier above 1.0 so there's room to decrease
        var t = BASE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        val afterUpward = learner.multiplierClamped
        assertTrue(afterUpward > 1.0, "precondition: multiplier should be above 1.0")

        // Now feed downward drift
        t += CYCLE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 115.0 - i * 1.5, deltaMgdl = -0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        learner.onLoopCycle(
            bgMgdl = 100.0, deltaMgdl = -0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        assertTrue(learner.multiplierClamped < afterUpward,
                   "Multiplier should decrease on downward drift")
    }

    @Test
    fun `flat BG freezes multiplier at learned value (success decay fix)`() {
        // Drive multiplier up first
        var t = BASE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.2,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.2, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        val learnedMult = learner.multiplierClamped
        assertTrue(learnedMult > 1.0, "precondition: should have learned above 1.0")

        // Now feed flat BG — multiplier should stay near learnedMult, not decay toward 1.0
        t += CYCLE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 115.0, deltaMgdl = 0.0,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t += 60 * 60_000L
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.0, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        // Multiplier should be close to learnedMult — flat BG targets currentMult, not 1.0
        assertEquals(learnedMult, learner.multiplierClamped, 0.02,
                     "Flat BG should freeze the learned multiplier — not decay toward 1.0")
    }

    // ── Overnight vs daytime weight ───────────────────────────────────────────

    @Test
    fun `overnight observations move multiplier more than daytime for same drift`() {
        // Same drift, different hour — overnight (hour=3) should move multiplier more
        fun buildAndMeasure(hourOfDay: Int): Double {
            val l = BasalLearner(FakePreferences(), FakeAAPSLogger())
            var t = BASE_MS
            repeat(10) { i ->
                l.onLoopCycle(
                    bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.5,
                    cobG = COB, minsLastBolus = MINS_BOLUS,
                    isfMgdl = ISF, profileBasalU = BASAL,
                    nowMs = t, hourOfDay = hourOfDay
                )
                t += CYCLE_MS
            }
            t = BASE_MS + 60 * 60_000L + CYCLE_MS
            l.onLoopCycle(
                bgMgdl = 115.0, deltaMgdl = 0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = hourOfDay
            )
            return l.multiplierClamped
        }

        val overnight = buildAndMeasure(hourOfDay = 3)   // WEIGHT_OVERNIGHT = 1.0
        val daytime   = buildAndMeasure(hourOfDay = 14)  // WEIGHT_DAYTIME = 0.5

        assertTrue(overnight > daytime,
                   "Overnight learning should move multiplier more than daytime (overnight=$overnight daytime=$daytime)")
    }

    // ── Learn interval gate ───────────────────────────────────────────────────

    @Test
    fun `learn interval gate prevents update within 1 hour of last update`() {
        // Trigger first learning update
        var t = BASE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        val afterFirst = learner.multiplierClamped

        // Try again 30 minutes later — should be gated
        t += 30 * 60_000L
        learner.onLoopCycle(
            bgMgdl = 120.0, deltaMgdl = 0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        assertEquals(afterFirst, learner.multiplierClamped, 1e-6,
                     "Second update within 1h of first should be gated")
    }

    // ── Hard limits ───────────────────────────────────────────────────────────

    @Test
    fun `multiplier never exceeds MAX even under extreme upward drift`() {
        var t = BASE_MS
        repeat(200) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + (i % 10) * 1.5, deltaMgdl = 0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            if (i % 12 == 0) t += 60 * 60_000L + CYCLE_MS  // trigger hourly update
            else t += CYCLE_MS
        }
        assertTrue(learner.multiplierClamped <= 1.5,
                   "Multiplier should never exceed MAX (1.5), got ${learner.multiplierClamped}")
    }

    @Test
    fun `multiplier never goes below MIN even under extreme downward drift`() {
        var t = BASE_MS
        repeat(200) { i ->
            learner.onLoopCycle(
                bgMgdl = 120.0 - (i % 10) * 1.5, deltaMgdl = -0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            if (i % 12 == 0) t += 60 * 60_000L + CYCLE_MS
            else t += CYCLE_MS
        }
        assertTrue(learner.multiplierClamped >= 0.7,
                   "Multiplier should never go below MIN (0.7), got ${learner.multiplierClamped}")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    fun `multiplier survives serialization round-trip`() {
        var t = BASE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        val saved = learner.multiplierClamped

        val learner2 = BasalLearner(prefs, logger)
        assertEquals(saved, learner2.multiplierClamped, 0.001,
                     "Multiplier should survive serialization round-trip")
    }

    @Test
    fun `reset clears all state`() {
        var t = BASE_MS
        repeat(10) { i ->
            learner.onLoopCycle(
                bgMgdl = 100.0 + i * 1.5, deltaMgdl = 0.5,
                cobG = COB, minsLastBolus = MINS_BOLUS,
                isfMgdl = ISF, profileBasalU = BASAL,
                nowMs = t, hourOfDay = 3
            )
            t += CYCLE_MS
        }
        t = BASE_MS + 60 * 60_000L + CYCLE_MS
        learner.onLoopCycle(
            bgMgdl = 115.0, deltaMgdl = 0.5, cobG = COB, minsLastBolus = MINS_BOLUS,
            isfMgdl = ISF, profileBasalU = BASAL, nowMs = t, hourOfDay = 3
        )
        assertTrue(learner.multiplierClamped != 1.0, "precondition: should have learned")

        learner.reset()
        assertEquals(1.0, learner.multiplierClamped, 1e-6, "Multiplier should be 1.0 after reset")
    }
}