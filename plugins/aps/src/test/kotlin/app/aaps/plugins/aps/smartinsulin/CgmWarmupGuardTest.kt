package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [CgmWarmupGuard]'s two responsibilities are separable, and the tests here pin that separation.
 *
 * The warmup behaviour (skip every 2nd/3rd SMB, suppress learning for the first 24h) is a
 * user-facing feature behind a preference. The implausible-delta block is not: a jump larger than
 * MAX_PLAUSIBLE_DELTA_MMOL between consecutive readings is not physiology at any sensor age, and
 * dosing on it is dangerous. That check used to sit AFTER the `enabled` early return, so turning
 * the warmup preference off also silently removed the only artifact guard in the plugin. It now
 * runs first and unconditionally.
 */
class CgmWarmupGuardTest {

    private lateinit var guard: CgmWarmupGuard
    private val logger = FakeAAPSLogger(collect = false)

    private val NOW = 1_700_000_000_000L
    private val HOUR_MS = 60 * 60_000L

    @BeforeEach
    fun setUp() {
        guard = CgmWarmupGuard(logger)
    }

    private fun evaluate(
        enabled:        Boolean,
        sensorAgeHours: Double,
        deltaMmol:      Double
    ) = guard.evaluate(
        enabled             = enabled,
        sensorInsertTimeMs  = NOW - (sensorAgeHours * HOUR_MS).toLong(),
        nowMs               = NOW,
        latestBgTimestampMs = NOW,
        deltaMmol           = deltaMmol,
        shortAvgDeltaMmol   = 0.0,
        longAvgDeltaMmol    = 0.0,
        noiseLevelRaw       = 0.0
    )

    @Test
    fun `implausible delta blocks SMBs with the guard disabled`() {
        val s = evaluate(enabled = false, sensorAgeHours = 200.0, deltaMmol = 5.0)

        assertFalse(s.deltaPlausible, "A 5 mmol/5min jump is an artifact at any age, guard on or off")
        assertFalse(s.allowSmb, "SMBs must be blocked on an implausible delta")
    }

    @Test
    fun `implausible delta blocks SMBs on a mature sensor with the guard enabled`() {
        val s = evaluate(enabled = true, sensorAgeHours = 200.0, deltaMmol = -4.5)

        assertFalse(s.deltaPlausible, "The block is not a warmup feature — it applies at every sensor age")
        assertFalse(s.allowSmb)
    }

    @Test
    fun `implausible delta during warmup keeps learning suppressed`() {
        val s = evaluate(enabled = true, sensorAgeHours = 3.0, deltaMmol = 5.0)

        assertFalse(s.deltaPlausible)
        assertTrue(s.suppressLearning, "A young sensor must still suppress learning on the artifact path")
        assertTrue(s.inWarmup, "The artifact early-return must not misreport a 3h-old sensor as out of warmup")
    }

    @Test
    fun `a plausible delta on a mature sensor is fully permissive`() {
        val s = evaluate(enabled = true, sensorAgeHours = 200.0, deltaMmol = 0.4)

        assertTrue(s.deltaPlausible)
        assertTrue(s.allowSmb)
        assertFalse(s.suppressLearning)
        assertFalse(s.inWarmup)
    }

    @Test
    fun `a disabled guard still suppresses learning during warmup`() {
        val s = evaluate(enabled = false, sensorAgeHours = 3.0, deltaMmol = 0.2)

        assertTrue(s.suppressLearning, "Learning suppression during warmup is independent of the SMB skipping")
        assertTrue(s.allowSmb, "With the guard disabled, a plausible reading should still dose normally")
    }

    @Test
    fun `a delta exactly at the plausibility limit is allowed`() {
        val s = evaluate(enabled = true, sensorAgeHours = 200.0,
                         deltaMmol = CgmWarmupGuard.MAX_PLAUSIBLE_DELTA_MMOL)

        assertTrue(s.deltaPlausible, "The limit itself is inclusive — only strictly larger jumps block")
    }
}
