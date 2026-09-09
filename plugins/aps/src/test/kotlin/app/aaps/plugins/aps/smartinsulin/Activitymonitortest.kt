package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.shared.tests.rx.TestAapsSchedulers
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ActivityMonitorTest {

    private val logger           = FakeAAPSLogger()
    private val persistenceLayer: PersistenceLayer = mock()
    private val phoneStepCounter: PhoneStepCounter = mock()

    private lateinit var sut: ActivityMonitor

    private val NOW = 1_000_000L

    @BeforeEach fun setUp() {
        sut = ActivityMonitor(logger, persistenceLayer, TestAapsSchedulers(), phoneStepCounter)
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

    // ── Record age / staleness ───────────────────────────────────────────────

    // The stored timestamp comes from the WATCH while the query uses the PHONE's clock, so
    // transport delay and clock skew both push records out of range. A 5-min window turned that
    // into a silent zero; the age is now surfaced and the cutoff is separate from the lookup.

    private fun stepsRecordAged(steps: Int, ageMs: Long) = SC(
        timestamp  = NOW - ageMs,
        duration   = ActivityMonitor.STEPS_DURATION_MS,
        steps5min  = steps,
        steps10min = 0, steps15min = 0, steps30min = 0, steps60min = 0, steps180min = 0,
        device     = "test"
    )

    @Test fun `a record delivered late still counts`() {
        // 7 min old — dropped outright by the old 5-min window, which is what made steps appear
        // several loops after the walk.
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecordAged(400, 7 * 60_000L)))
        sut.recompute(NOW)
        assertEquals(400, sut.lastSteps5min)
        assertEquals(ActivityMonitor.ActivityLevel.MODERATE, sut.level)
        assertEquals(7 * 60_000L, sut.lastStepsAgeMs)
    }

    @Test fun `a record past the staleness cutoff stops counting but still reports its age`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecordAged(400, 12 * 60_000L)))
        sut.recompute(NOW)
        assertEquals(0, sut.lastSteps5min)
        assertEquals(ActivityMonitor.ActivityLevel.SEDENTARY, sut.level)
        assertEquals(12 * 60_000L, sut.lastStepsAgeMs)
    }

    @Test fun `no record at all reports a null age, not a zero one`() {
        whenever(persistenceLayer.getStepsCountFromTime(any())).thenReturn(emptyList())
        sut.recompute(NOW)
        assertEquals(0, sut.lastSteps5min)
        assertEquals(null, sut.lastStepsAgeMs)
    }

    @Test fun `a watch clock running ahead does not produce a negative age`() {
        whenever(persistenceLayer.getStepsCountFromTime(any()))
            .thenReturn(listOf(stepsRecordAged(400, -30_000L)))
        sut.recompute(NOW)
        assertEquals(400, sut.lastSteps5min)
        assertEquals(0L, sut.lastStepsAgeMs)
    }
}
