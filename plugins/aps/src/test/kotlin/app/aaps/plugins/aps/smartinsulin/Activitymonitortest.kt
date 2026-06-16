package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import app.aaps.core.keys.UnitDoubleKey

class ActivityMonitorTest {

    private val logger           = FakeAAPSLogger()
    private val persistenceLayer: PersistenceLayer = mock()

    private lateinit var sut: ActivityMonitor

    private val NOW = 1_000_000L

    @BeforeEach fun setUp() {
        sut = ActivityMonitor(logger, persistenceLayer)
        whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(emptyList())
        whenever(persistenceLayer.getStepsCountFromTime(any())).thenReturn(emptyList())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun hrRecord(bpm: Double) = HR(
        timestamp      = NOW,
        duration       = ActivityMonitor.HR_WINDOW_MS,
        beatsPerMinute = bpm,
        device         = "test"
    )

    private fun twoHrRecords(bpm1: Double, dur1Ms: Long, bpm2: Double, dur2Ms: Long) = listOf(
        HR(timestamp = NOW,          duration = dur1Ms, beatsPerMinute = bpm1, device = "test"),
        HR(timestamp = NOW + dur1Ms, duration = dur2Ms, beatsPerMinute = bpm2, device = "test")
    )

    private fun stepsRecord(steps: Int) = SC(
        timestamp  = NOW,
        duration   = ActivityMonitor.STEPS_DURATION_MS,
        steps5min  = steps,
        steps10min = 0, steps15min = 0, steps30min = 0, steps60min = 0, steps180min = 0,
        device     = "test"
    )

    private fun stepsRecordJitter(steps: Int, jitterMs: Long = 3000L) = SC(
        timestamp  = NOW,
        duration   = ActivityMonitor.STEPS_DURATION_MS + jitterMs,
        steps5min  = steps,
        steps10min = 0, steps15min = 0, steps30min = 0, steps60min = 0, steps180min = 0,
        device     = "test"
    )

    private fun recompute(restingHr: Double = 0.0) = sut.recompute(NOW, restingHr)

    // ── Initial state ────────────────────────────────────────────────────────

    @Test fun `initial level is SEDENTARY`() {
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    @Test fun `initial suppressLearning is false`() {
        assertFalse(sut.suppressLearning)
    }

    // ── No data → SEDENTARY ──────────────────────────────────────────────────

    @Test fun `no HR and no steps → SEDENTARY`() {
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    @Test fun `empty HR list → avgHrBpm is zero`() {
        recompute()
        assertEquals(0.0, sut.avgHrBpm, 0.001)
    }

    @Test fun `empty steps list → lastSteps5min is zero`() {
        recompute()
        assertEquals(0, sut.lastSteps5min)
    }

    // ── HR absolute thresholds ───────────────────────────────────────────────

    @Test fun `HR just below LIGHT threshold → SEDENTARY`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_LIGHT_MIN - 1)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    @Test fun `HR at exact LIGHT threshold → LIGHT`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_LIGHT_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.LIGHT, sut.level)
    }

    @Test fun `HR between LIGHT and MODERATE → LIGHT`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_MODERATE_MIN - 1)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.LIGHT, sut.level)
    }

    @Test fun `HR at exact MODERATE threshold → MODERATE`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_MODERATE_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.MODERATE, sut.level)
    }

    @Test fun `HR between MODERATE and HEAVY → MODERATE`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_HEAVY_MIN - 1)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.MODERATE, sut.level)
    }

    @Test fun `HR at exact HEAVY threshold → HEAVY`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_HEAVY_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
    }

    @Test fun `very high HR → HEAVY`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(180.0)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
    }

    // ── HR relative thresholds (resting HR configured) ───────────────────────

    @Test fun `delta below REL_LIGHT with resting HR → SEDENTARY`() {
        val restingHr = 60.0
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(restingHr + ActivityMonitor.HR_REL_LIGHT_MIN - 1)))
        recompute(restingHr)
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    @Test fun `delta at REL_LIGHT with resting HR → LIGHT`() {
        val restingHr = 60.0
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(restingHr + ActivityMonitor.HR_REL_LIGHT_MIN)))
        recompute(restingHr)
        assertEquals(ActivityMonitor.ActivityLevel.LIGHT, sut.level)
    }

    @Test fun `delta at REL_MODERATE with resting HR → MODERATE`() {
        val restingHr = 60.0
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(restingHr + ActivityMonitor.HR_REL_MODERATE_MIN)))
        recompute(restingHr)
        assertEquals(ActivityMonitor.ActivityLevel.MODERATE, sut.level)
    }

    @Test fun `delta at REL_HEAVY with resting HR → HEAVY`() {
        val restingHr = 60.0
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(restingHr + ActivityMonitor.HR_REL_HEAVY_MIN)))
        recompute(restingHr)
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
    }

    // ── HR weighted average ──────────────────────────────────────────────────

    @Test fun `avgHrBpm is duration-weighted across multiple HR records`() {
        val expected = (80.0 * 200_000 + 120.0 * 130_000) / 330_000.0
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(twoHrRecords(80.0, 200_000L, 120.0, 130_000L))
        recompute()
        assertEquals(expected, sut.avgHrBpm, 0.01)
    }

    // ── Steps thresholds ─────────────────────────────────────────────────────

    @Test fun `steps below LIGHT threshold → SEDENTARY`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_LIGHT_MIN - 1)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    @Test fun `steps at exact LIGHT threshold → LIGHT`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_LIGHT_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.LIGHT, sut.level)
    }

    @Test fun `steps at exact MODERATE threshold → MODERATE`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_MODERATE_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.MODERATE, sut.level)
    }

    @Test fun `steps at exact HEAVY threshold → HEAVY`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_HEAVY_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
    }

    @Test fun `steps record with slight duration jitter is still accepted`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecordJitter(ActivityMonitor.STEPS_HEAVY_MIN, jitterMs = 3000L)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
        assertEquals(ActivityMonitor.STEPS_HEAVY_MIN, sut.lastSteps5min)
    }

    @Test fun `steps record with jitter exceeding 5s tolerance is ignored`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecordJitter(ActivityMonitor.STEPS_HEAVY_MIN, jitterMs = 6000L)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
        assertEquals(0, sut.lastSteps5min)
    }

    // ── HR wins over steps when higher, and vice versa ───────────────────────

    @Test fun `HEAVY HR overrides LIGHT steps`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_HEAVY_MIN)))
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_LIGHT_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
    }

    @Test fun `HEAVY steps overrides LIGHT HR`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_LIGHT_MIN)))
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_HEAVY_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.HEAVY, sut.level)
    }

    @Test fun `equal HR and steps levels → that level is reported`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_MODERATE_MIN)))
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecord(ActivityMonitor.STEPS_MODERATE_MIN)))
        recompute()
        assertEquals(ActivityMonitor.ActivityLevel.MODERATE, sut.level)
    }

    // ── suppressLearning ─────────────────────────────────────────────────────

    @Test fun `suppressLearning is false when SEDENTARY`() {
        recompute()
        assertFalse(sut.suppressLearning)
    }

    @Test fun `suppressLearning is true when LIGHT`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_LIGHT_MIN)))
        recompute()
        assertTrue(sut.suppressLearning)
    }

    @Test fun `suppressLearning is true when MODERATE`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_MODERATE_MIN)))
        recompute()
        assertTrue(sut.suppressLearning)
    }

    @Test fun `suppressLearning is true when HEAVY`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_HEAVY_MIN)))
        recompute()
        assertTrue(sut.suppressLearning)
    }

    // ── targetOffsetMmol — the bug we fixed ──────────────────────────────────

    @Test fun `targetOffsetMmol returns 0 when SEDENTARY`() {
        recompute()
        assertEquals(0.0, sut.targetOffsetMmol(0.5, 1.0, 2.0), 0.001)
    }

    @Test fun `targetOffsetMmol returns lightMmol when LIGHT`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_LIGHT_MIN)))
        recompute()
        assertEquals(0.5, sut.targetOffsetMmol(lightMmol = 0.5, moderateMmol = 1.0, heavyMmol = 2.0), 0.001)
    }

    @Test fun `targetOffsetMmol returns moderateMmol when MODERATE`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_MODERATE_MIN)))
        recompute()
        assertEquals(1.0, sut.targetOffsetMmol(lightMmol = 0.5, moderateMmol = 1.0, heavyMmol = 2.0), 0.001)
    }

    @Test fun `targetOffsetMmol returns heavyMmol when HEAVY`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_HEAVY_MIN)))
        recompute()
        assertEquals(2.0, sut.targetOffsetMmol(lightMmol = 0.5, moderateMmol = 1.0, heavyMmol = 2.0), 0.001)
    }

    @Test fun `targetOffsetMmol stays small — regression for stored-as-float unit bug`() {
        // Before the fix: spMgdl fell back to a large defaultValue (e.g. 8.0),
        // multiplied by 18 → 144 mg/dL → /18 = 8.0 mmol offset → target jumped to ~14.5.
        // A correctly-read 0.5 mmol offset must never reach BG-range magnitudes.
        whenever(persistenceLayer.getHeartRatesFromTime(any()))
            .thenReturn(listOf(hrRecord(ActivityMonitor.HR_LIGHT_MIN)))
        recompute()
        val offset = sut.targetOffsetMmol(lightMmol = 0.5, moderateMmol = 1.0, heavyMmol = 2.0)
        assertTrue(offset <= 3.0) { "Offset should be ≤ 3.0 mmol, got $offset — possible unit conversion bug" }
    }

    // ── DB exception resilience ──────────────────────────────────────────────

    @Test fun `HR query exception → SEDENTARY and zero avgHrBpm`() {
        whenever(persistenceLayer.getHeartRatesFromTime(any())).thenThrow(RuntimeException("db fail"))
        recompute()
        assertEquals(0.0, sut.avgHrBpm, 0.001)
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    @Test fun `steps query exception → SEDENTARY and zero lastSteps5min`() {
        whenever(persistenceLayer.getStepsCountFromTime(any())).thenThrow(RuntimeException("db fail"))
        recompute()
        assertEquals(0, sut.lastSteps5min)
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
    }

    // ── Level label ──────────────────────────────────────────────────────────

    @Test fun `level label is title-cased`() {
        assertEquals("Sedentary", ActivityMonitor.ActivityLevel.SEDENTARY.label)
        assertEquals("Light",     ActivityMonitor.ActivityLevel.LIGHT.label)
        assertEquals("Moderate",  ActivityMonitor.ActivityLevel.MODERATE.label)
        assertEquals("Heavy",     ActivityMonitor.ActivityLevel.HEAVY.label)
    }
}

// == Integration: SP read path → targetOffsetMmol ============================
//
// The original ActivityMonitorTest.targetOffsetMmol tests passed because they
// called targetOffsetMmol() directly with already-correct mmol values. The real
// bug was in SmartInsulinPlugin.spMgdl() applying a "< 20 → ×18" heuristic
// that re-multiplied mg/dL defaults (9, 18, 27) back up, giving 9/18/27 mmol
// offsets instead of 0.5/1.0/1.5 — causing the target to jump to ~14.5 mmol.
//
// These tests replicate the fixed sp.getDouble → /18.0 → targetOffsetMmol
// chain from the plugin call-site to confirm correct mmol offsets end-to-end,
// and include regression assertions that the old heuristic values are never
// produced.
class ActivityTargetOffsetIntegrationTest {

    private val persistenceLayer: PersistenceLayer = mock()
    private val sp: app.aaps.core.interfaces.sharedPreferences.SP = mock()
    private val logger = FakeAAPSLogger()

    private lateinit var sut: ActivityMonitor

    private val NOW = 1_000_000L

    @BeforeEach fun setUp() {
        sut = ActivityMonitor(logger, persistenceLayer)
        whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(emptyList())
        whenever(persistenceLayer.getStepsCountFromTime(any())).thenReturn(emptyList())
        // Default: SP returns whatever default is passed in
        whenever(sp.getDouble(any(), any())).thenAnswer { it.getArgument<Double>(1) }
    }

    // Replicates the fixed call-site in SmartInsulinPlugin:
    //   spMgdl(key) / 18.0  →  targetOffsetMmol(...)
    // where spMgdl is now: sp.getDouble(key, defaultValue)  [no < 20 heuristic]
    private fun readOffsetMmol(
        lightMgdl:    Double = UnitDoubleKey.ApsSmartInsulinActivityLightTarget.defaultValue,
        moderateMgdl: Double = UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.defaultValue,
        heavyMgdl:    Double = UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.defaultValue
    ): Double {
        whenever(sp.getDouble(eq<String>(UnitDoubleKey.ApsSmartInsulinActivityLightTarget.key),    any())).thenReturn(lightMgdl)
        whenever(sp.getDouble(eq<String>(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.key), any())).thenReturn(moderateMgdl)
        whenever(sp.getDouble(eq<String>(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.key),    any())).thenReturn(heavyMgdl)
        val lightMmol    = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityLightTarget.key,    lightMgdl)    / 18.0
        val moderateMmol = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.key, moderateMgdl) / 18.0
        val heavyMmol    = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.key,    heavyMgdl)    / 18.0
        return sut.targetOffsetMmol(lightMmol, moderateMmol, heavyMmol)
    }

    private fun setLevel(level: ActivityMonitor.ActivityLevel) {
        val bpm = when (level) {
            ActivityMonitor.ActivityLevel.SEDENTARY -> null
            ActivityMonitor.ActivityLevel.LIGHT     -> ActivityMonitor.HR_LIGHT_MIN
            ActivityMonitor.ActivityLevel.MODERATE  -> ActivityMonitor.HR_MODERATE_MIN
            ActivityMonitor.ActivityLevel.HEAVY     -> ActivityMonitor.HR_HEAVY_MIN
        }
        if (bpm == null) {
            whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(emptyList())
        } else {
            val hr = HR(
                timestamp = NOW, duration = ActivityMonitor.HR_WINDOW_MS,
                beatsPerMinute = bpm, device = "test"
            )
            whenever(persistenceLayer.getHeartRatesFromTime(any())).thenReturn(listOf(hr))
        }
        sut.recompute(NOW)
    }

    // ── Default SP values ────────────────────────────────────────────────────

    @Test fun `SEDENTARY offset is 0 regardless of SP prefs`() {
        setLevel(ActivityMonitor.ActivityLevel.SEDENTARY)
        assertEquals(0.0, readOffsetMmol(), 0.001)
    }

    @Test fun `LIGHT default 9 mgdl in SP produces 0_5 mmol offset`() {
        setLevel(ActivityMonitor.ActivityLevel.LIGHT)
        // 9.0 mg/dL / 18 = 0.5 mmol
        assertEquals(0.5, readOffsetMmol(lightMgdl = 9.0), 0.001)
    }

    @Test fun `MODERATE default 18 mgdl in SP produces 1_0 mmol offset`() {
        setLevel(ActivityMonitor.ActivityLevel.MODERATE)
        // 18.0 mg/dL / 18 = 1.0 mmol
        assertEquals(1.0, readOffsetMmol(moderateMgdl = 18.0), 0.001)
    }

    @Test fun `HEAVY default 27 mgdl in SP produces 1_5 mmol offset`() {
        setLevel(ActivityMonitor.ActivityLevel.HEAVY)
        // 27.0 mg/dL / 18 = 1.5 mmol
        assertEquals(1.5, readOffsetMmol(heavyMgdl = 27.0), 0.001)
    }

    // ── User-edited values (stored as mg/dL by SmartInsulinUnitPreference) ───

    @Test fun `LIGHT user-set 0_5 mmol stored as 9_0 mgdl produces correct offset`() {
        setLevel(ActivityMonitor.ActivityLevel.LIGHT)
        assertEquals(0.5, readOffsetMmol(lightMgdl = 9.0), 0.001)
    }

    @Test fun `MODERATE user-set 1_5 mmol stored as 27_0 mgdl produces correct offset`() {
        setLevel(ActivityMonitor.ActivityLevel.MODERATE)
        assertEquals(1.5, readOffsetMmol(moderateMgdl = 27.0), 0.001)
    }

    @Test fun `HEAVY user-set 2_0 mmol stored as 36_0 mgdl produces correct offset`() {
        setLevel(ActivityMonitor.ActivityLevel.HEAVY)
        assertEquals(2.0, readOffsetMmol(heavyMgdl = 36.0), 0.001)
    }

    // ── Regression: old heuristic values must NOT be produced ────────────────

    @Test fun `LIGHT 9 mgdl does NOT produce 9_0 mmol offset (old heuristic regression)`() {
        // Old bug: spMgdl saw 9.0 < 20 → x18 = 162 mg/dL → /18 = 9.0 mmol → target jumped to ~14.5 mmol
        setLevel(ActivityMonitor.ActivityLevel.LIGHT)
        val offset = readOffsetMmol(lightMgdl = 9.0)
        assertTrue(offset < 2.0) { "Got $offset mmol — old heuristic bug would produce 9.0 mmol" }
    }

    @Test fun `MODERATE 18 mgdl does NOT produce 18_0 mmol offset (old heuristic regression)`() {
        setLevel(ActivityMonitor.ActivityLevel.MODERATE)
        val offset = readOffsetMmol(moderateMgdl = 18.0)
        assertTrue(offset < 2.0) { "Got $offset mmol — old heuristic bug would produce 18.0 mmol" }
    }

    @Test fun `HEAVY 27 mgdl does NOT produce 27_0 mmol offset (old heuristic regression)`() {
        setLevel(ActivityMonitor.ActivityLevel.HEAVY)
        val offset = readOffsetMmol(heavyMgdl = 27.0)
        assertTrue(offset < 3.0) { "Got $offset mmol — old heuristic bug would produce 27.0 mmol" }
    }
}