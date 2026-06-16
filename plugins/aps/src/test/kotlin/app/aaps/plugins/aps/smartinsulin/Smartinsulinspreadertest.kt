package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests the full chain:  sp.getDouble → spMgdl() → /18.0 → targetOffsetMmol()
 *
 * This is the chain that had the conversion bug. These tests will fail if
 * anyone reintroduces a heuristic inside spMgdl() — e.g. "if value < 20, × 18"
 * which was producing 9.0 mmol instead of 0.5 mmol and setting temp targets
 * to ~14.5 mmol.
 */
@ExtendWith(MockitoExtension::class)
class SmartInsulinSpReaderTest {

    private val sp: SP = mock()
    private val persistenceLayer: PersistenceLayer = mock()
    private val logger = FakeAAPSLogger()

    private lateinit var monitor: ActivityMonitor

    private val NOW = 1_000_000L

    @BeforeEach fun setUp() {
        monitor = ActivityMonitor(logger, persistenceLayer)
        whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(emptyList())
        whenever(persistenceLayer.getStepsCountFromTime(any())).thenReturn(emptyList())
        // Default: SP returns whatever default value is passed in
        whenever(sp.getDouble(any<String>(), any<Double>())).thenAnswer { it.getArgument<Double>(1) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun setSpValue(key: UnitDoubleKey, mgdl: Double) {
        whenever(sp.getDouble(org.mockito.kotlin.eq(key.key), any<Double>())).thenReturn(mgdl)
    }

    private fun setActivityLevel(level: ActivityMonitor.ActivityLevel) {
        val bpm = when (level) {
            ActivityMonitor.ActivityLevel.SEDENTARY -> null
            ActivityMonitor.ActivityLevel.LIGHT     -> ActivityMonitor.HR_LIGHT_MIN
            ActivityMonitor.ActivityLevel.MODERATE  -> ActivityMonitor.HR_MODERATE_MIN
            ActivityMonitor.ActivityLevel.HEAVY     -> ActivityMonitor.HR_HEAVY_MIN
        }
        if (bpm == null) {
            whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(emptyList())
        } else {
            val hr = HR(timestamp = NOW, duration = ActivityMonitor.HR_WINDOW_MS, beatsPerMinute = bpm, device = "test")
            whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(listOf(hr))
        }
        monitor.recompute(NOW)
    }

    // ── spMgdl: direct read, no heuristic ───────────────────────────────────

    @Test fun `spMgdl returns SP value directly without any conversion`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityLightTarget, 9.0)
        assertEquals(9.0, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinActivityLightTarget), 0.001)
    }

    @Test fun `spMgdl returns default when SP has no stored value`() {
        // SP mock returns the default (9.0) when no override is set
        assertEquals(
            UnitDoubleKey.ApsSmartInsulinActivityLightTarget.defaultValue,
            spMgdl(sp, UnitDoubleKey.ApsSmartInsulinActivityLightTarget),
            0.001
        )
    }

    @Test fun `spMgdl does NOT multiply small values by 18 (regression for old heuristic)`() {
        // Old bug: 9.0 < 20 → × 18 = 162.0. Confirm we get 9.0, not 162.0.
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityLightTarget, 9.0)
        val result = spMgdl(sp, UnitDoubleKey.ApsSmartInsulinActivityLightTarget)
        assertTrue(result < 20.0) { "spMgdl returned $result — old heuristic would produce 162.0" }
    }

    // ── activityOffsetMmol: full chain with default SP values ────────────────

    @Test fun `SEDENTARY produces 0 offset regardless of SP values`() {
        setActivityLevel(ActivityMonitor.ActivityLevel.SEDENTARY)
        assertEquals(0.0, activityOffsetMmol(sp, monitor), 0.001)
    }

    @Test fun `LIGHT default 9 mgdl in SP produces 0_5 mmol offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityLightTarget, 9.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.LIGHT)
        // 9.0 mg/dL ÷ 18 = 0.5 mmol  →  target = 5.5 + 0.5 = 6.0 mmol
        assertEquals(0.5, activityOffsetMmol(sp, monitor), 0.001)
    }

    @Test fun `MODERATE default 18 mgdl in SP produces 1_0 mmol offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget, 18.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.MODERATE)
        // 18.0 mg/dL ÷ 18 = 1.0 mmol  →  target = 5.5 + 1.0 = 6.5 mmol
        assertEquals(1.0, activityOffsetMmol(sp, monitor), 0.001)
    }

    @Test fun `HEAVY default 27 mgdl in SP produces 1_5 mmol offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget, 27.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.HEAVY)
        // 27.0 mg/dL ÷ 18 = 1.5 mmol  →  target = 5.5 + 1.5 = 7.0 mmol
        assertEquals(1.5, activityOffsetMmol(sp, monitor), 0.001)
    }

    // ── Regression: old heuristic values must NEVER be produced ─────────────

    @Test fun `LIGHT 9 mgdl does NOT produce 9_0 mmol offset (old bug was 5_5 + 9_0 = 14_5 mmol target)`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityLightTarget, 9.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.LIGHT)
        val offset = activityOffsetMmol(sp, monitor)
        assertTrue(offset < 2.0) { "Got $offset mmol — old heuristic bug produced 9.0 mmol offset (14.5 mmol target)" }
    }

    @Test fun `MODERATE 18 mgdl does NOT produce 18_0 mmol offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget, 18.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.MODERATE)
        val offset = activityOffsetMmol(sp, monitor)
        assertTrue(offset < 2.0) { "Got $offset mmol — old heuristic bug produced 18.0 mmol offset" }
    }

    @Test fun `HEAVY 27 mgdl does NOT produce 27_0 mmol offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget, 27.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.HEAVY)
        val offset = activityOffsetMmol(sp, monitor)
        assertTrue(offset < 3.0) { "Got $offset mmol — old heuristic bug produced 27.0 mmol offset" }
    }

    // ── User-edited values ───────────────────────────────────────────────────

    @Test fun `LIGHT user-set 1_0 mmol stored as 18 mgdl produces correct offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityLightTarget, 18.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.LIGHT)
        assertEquals(1.0, activityOffsetMmol(sp, monitor), 0.001)
    }

    @Test fun `HEAVY user-set 2_0 mmol stored as 36 mgdl produces correct offset`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget, 36.0)
        setActivityLevel(ActivityMonitor.ActivityLevel.HEAVY)
        assertEquals(2.0, activityOffsetMmol(sp, monitor), 0.001)
    }

    // ── Guard keys: LowGuard and WarnGuard ───────────────────────────────────
    // These are critical safety thresholds. They are stored as mg/dL by
    // SmartInsulinUnitPreference and read via spMgdl() — same path as activity
    // targets. Confirm they pass through as mg/dL without any conversion.

    @Test fun `LowGuard default 72 mgdl passes through spMgdl unchanged`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinLowGuard, 72.0)
        // Used directly as mg/dL in the plugin — must NOT be multiplied by 18
        assertEquals(72.0, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinLowGuard), 0.001)
    }

    @Test fun `WarnGuard default 86 mgdl passes through spMgdl unchanged`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinWarnGuard, 86.0)
        assertEquals(86.0, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinWarnGuard), 0.001)
    }

    @Test fun `LowGuard converts correctly to mmol for display (72 mgdl = 4_0 mmol)`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinLowGuard, 72.0)
        // Plugin uses spMgdl(LowGuard) / 18.0 for mmol display
        assertEquals(4.0, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinLowGuard) / 18.0, 0.001)
    }

    @Test fun `WarnGuard converts correctly to mmol for display (86 mgdl = 4_8 mmol)`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinWarnGuard, 86.0)
        assertEquals(4.8, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinWarnGuard) / 18.0, 0.01)
    }

    @Test fun `LowGuard user-set 3_9 mmol stored as 70 mgdl passes through correctly`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinLowGuard, 70.0)
        assertEquals(70.0, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinLowGuard), 0.001)
        assertEquals(3.9, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinLowGuard) / 18.0, 0.01)
    }

    @Test fun `LowGuard does NOT get multiplied by 18 (would produce 1296 mgdl — dangerously wrong)`() {
        setSpValue(UnitDoubleKey.ApsSmartInsulinLowGuard, 72.0)
        val result = spMgdl(sp, UnitDoubleKey.ApsSmartInsulinLowGuard)
        // If heuristic ran: 72 > 20 so it would pass through anyway — but confirm
        // a low user value like 54 (3.0 mmol) also isn't corrupted
        assertTrue(result < 400.0) { "Got $result — heuristic corruption would produce values like 972 mg/dL" }
    }

    @Test fun `LowGuard small value 54 mgdl (3_0 mmol) is NOT multiplied by 18`() {
        // 54 mg/dL = 3.0 mmol — a valid low guard value
        // Old heuristic: 54 > 20 so passes through anyway, but belt-and-braces check
        setSpValue(UnitDoubleKey.ApsSmartInsulinLowGuard, 54.0)
        assertEquals(54.0, spMgdl(sp, UnitDoubleKey.ApsSmartInsulinLowGuard), 0.001)
    }
}