package app.aaps.plugins.aps.smartInsulin.ice

import app.aaps.core.interfaces.logging.AAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for IceTracker + computeIceConfidence.
 *
 * ## Test strategy
 *
 * - Tracker tests build sample sequences manually with controlled timestamps so we
 *   can verify ICE math against hand-computed values.
 * - Confidence tests use computeIceConfidence directly on synthetic IceSample lists.
 * - All BG values in mg/dL throughout — consistent with internal AAPS convention.
 *
 * ## Reference scenarios
 *
 * "Steady fasting": flat BG, modest insulin activity → modeled drop, observed flat →
 *   ICE positive small (sensor noise + slight liver glucose production).
 *
 * "Real meal": rising BG faster than insulin can drop it → ICE strongly positive.
 *
 * "Exercise": falling BG faster than insulin alone explains → ICE negative.
 */
class IceTrackerTest {

    private val logger: AAPSLogger = mock()
    private lateinit var tracker: IceTracker

    @BeforeEach fun setUp() {
        tracker = IceTracker(logger)
    }

    // ── First-sample behaviour ──────────────────────────────────────────────

    @Test fun `first sample has no ICE — needs a previous to diff against`() {
        val s = tracker.recordCycle(
            timestampMs     = 1_000_000L,
            bgMgdl          = 120.0,
            activityUperMin = 0.0,
            isfMgdlPerU     = 50.0
        )
        assertNull(s.iceMgdlPerHour)
        assertNull(s.observedDeltaMgdl)
        assertNull(s.intervalMin)
        assertEquals(120.0, s.bgMgdl, 0.001)
    }

    // ── ICE math: flat BG, no insulin ───────────────────────────────────────

    @Test fun `flat BG no insulin produces zero ICE`() {
        tracker.recordCycle(0L,         120.0, 0.0, 50.0)
        val s = tracker.recordCycle(300_000L, 120.0, 0.0, 50.0)  // +5 min

        // observed = 0, modeled = -(0 × 50 × 5) = 0, ICE = 0
        assertEquals(0.0, s.iceMgdlPerHour ?: error("null ICE"), 0.001)
    }

    // ── ICE math: BG drop that matches insulin action exactly ───────────────

    @Test fun `BG drop matching insulin model produces zero ICE`() {
        // 0.01 U/min × 50 mg/dL/U × 5 min = 2.5 mg/dL expected drop
        tracker.recordCycle(0L,         120.0, 0.01, 50.0)
        val s = tracker.recordCycle(300_000L, 117.5, 0.01, 50.0)

        // observed = -2.5, modeled = -2.5, ICE = 0
        assertEquals(0.0, s.iceMgdlPerHour ?: error("null ICE"), 0.001)
    }

    // ── ICE math: meal rising faster than insulin ───────────────────────────

    @Test fun `BG rising despite insulin action produces strongly positive ICE`() {
        // Insulin should drop BG 2.5 mg/dL in 5 min, but it ROSE 10 mg/dL instead.
        // Net unmodeled rise: 10 - (-2.5) = 12.5 mg/dL in 5 min = 150 mg/dL/h
        tracker.recordCycle(0L,         120.0, 0.01, 50.0)
        val s = tracker.recordCycle(300_000L, 130.0, 0.01, 50.0)

        val ice = s.iceMgdlPerHour ?: error("null ICE")
        assertEquals(150.0, ice, 0.1)
        assertEquals(150.0 / 18.0, s.iceMmolPerHour ?: 0.0, 0.01)
    }

    // ── ICE math: BG falling faster than insulin (exercise) ─────────────────

    @Test fun `BG falling faster than insulin model produces negative ICE (exercise signature)`() {
        // Insulin should drop 2.5 mg/dL, actually dropped 12.5. Extra -10 over 5 min = -120 mg/dL/h
        tracker.recordCycle(0L,         120.0, 0.01, 50.0)
        val s = tracker.recordCycle(300_000L, 107.5, 0.01, 50.0)

        val ice = s.iceMgdlPerHour ?: error("null ICE")
        assertEquals(-120.0, ice, 0.1)
    }

    // ── Gap handling ────────────────────────────────────────────────────────

    @Test fun `interval longer than max-gap discards the delta`() {
        tracker.recordCycle(0L,           120.0, 0.0, 50.0)
        // 11 min gap (> MAX_GAP_MIN of 10)
        val s = tracker.recordCycle(660_000L, 140.0, 0.0, 50.0)

        // Sample recorded but ICE not computed — gap too big to trust.
        assertNotNull(s.intervalMin)
        assertTrue(s.intervalMin!! > 10.0)
        assertNull(s.iceMgdlPerHour, "ICE must not be computed across a >10-min gap")
        assertNull(s.observedDeltaMgdl)
    }

    @Test fun `interval shorter than min-gap discards the delta (duplicate)`() {
        tracker.recordCycle(0L,        120.0, 0.0, 50.0)
        val s = tracker.recordCycle(30_000L, 120.5, 0.0, 50.0)  // 0.5 min — duplicate-ish

        assertNull(s.iceMgdlPerHour)
    }

    @Test fun `after a gap, next clean interval produces ICE again`() {
        tracker.recordCycle(0L,            120.0, 0.0, 50.0)
        tracker.recordCycle(900_000L,      140.0, 0.0, 50.0)  // gap — no ICE
        val s = tracker.recordCycle(1_200_000L, 145.0, 0.0, 50.0)  // 5 min after gap — clean

        // observed = +5, modeled = 0, ICE = 60 mg/dL/h
        assertEquals(60.0, s.iceMgdlPerHour ?: error("null ICE"), 0.1)
    }

    // ── Disable reasons carry through ───────────────────────────────────────

    @Test fun `disable reason on sample is recorded`() {
        tracker.recordCycle(0L,         120.0, 0.0, 50.0)
        val s = tracker.recordCycle(
            timestampMs     = 300_000L,
            bgMgdl          = 130.0,
            activityUperMin = 0.0,
            isfMgdlPerU     = 50.0,
            disableReason   = IceDisableReason.EXERCISE_TEMP_TARGET
        )
        assertEquals(IceDisableReason.EXERCISE_TEMP_TARGET, s.disabled)
        // ICE is STILL computed — buffer is intact for when ICE re-enables.
        assertNotNull(s.iceMgdlPerHour)
        // But confidence will be zero.
        val snap = tracker.snapshot.value
        assertNotNull(snap)
        assertEquals(0.0, snap!!.confidence.score, 0.001)
        assertEquals(IceDisableReason.EXERCISE_TEMP_TARGET, snap.confidence.disabled)
    }

    // ── Buffer eviction ─────────────────────────────────────────────────────

    @Test fun `buffer evicts oldest when over capacity`() {
        // Push 80 samples — well past the 72-sample cap.
        for (i in 0 until 80) {
            tracker.recordCycle(i.toLong() * 300_000L, 120.0, 0.0, 50.0)
        }
        assertTrue(tracker.fullBuffer().size <= 72, "buffer must not grow beyond cap")
        // Last sample should still be the most recent.
        assertEquals(79L * 300_000L, tracker.fullBuffer().last().timestampMs)
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    @Test fun `reset clears buffer and snapshot`() {
        tracker.recordCycle(0L,         120.0, 0.0, 50.0)
        tracker.recordCycle(300_000L,   130.0, 0.0, 50.0)
        assertTrue(tracker.fullBuffer().isNotEmpty())

        tracker.reset()

        assertTrue(tracker.fullBuffer().isEmpty())
        assertNull(tracker.snapshot.value)
    }

    // ── Snapshot publishing ─────────────────────────────────────────────────

    @Test fun `snapshot publishes after each recordCycle`() {
        assertNull(tracker.snapshot.value)
        tracker.recordCycle(0L, 120.0, 0.0, 50.0)
        assertNotNull(tracker.snapshot.value)
        tracker.recordCycle(300_000L, 130.0, 0.0, 50.0)
        // Snapshot updated — currentIceMgdlH non-null for the second sample.
        assertNotNull(tracker.snapshot.value!!.currentIceMgdlH)
    }
}