package app.aaps.plugins.aps.smartInsulin.ice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for computeIceConfidence — the pure function that maps a buffer of IceSamples
 * into a 4-component confidence score (magnitude / persistence / consistency / cgmQuality).
 *
 * No IceTracker involved — these tests construct IceSample lists directly so each
 * scoring rule can be exercised in isolation.
 */
class IceConfidenceTest {

    /** Helper: build a sample with just the ICE value set. */
    private fun s(
        iceMgdlH: Double?,
        ts: Long = 0L,
        disabled: IceDisableReason? = null
    ) = IceSample(
        timestampMs       = ts,
        bgMgdl            = 120.0,
        activityUperMin   = 0.0,
        isfMgdlPerU       = 50.0,
        iceMgdlPerHour    = iceMgdlH,
        disabled          = disabled
    )

    // ── Empty / insufficient history ────────────────────────────────────────

    @Test fun `empty samples returns INSUFFICIENT_HISTORY`() {
        val c = computeIceConfidence(emptyList())
        assertEquals(0.0, c.score, 0.001)
        assertEquals(IceDisableReason.INSUFFICIENT_HISTORY, c.disabled)
    }

    @Test fun `samples with no ICE values returns INSUFFICIENT_HISTORY`() {
        val c = computeIceConfidence(listOf(s(null), s(null)))
        assertEquals(0.0, c.score, 0.001)
        assertEquals(IceDisableReason.INSUFFICIENT_HISTORY, c.disabled)
    }

    // ── Forced disable wins over everything ─────────────────────────────────

    @Test fun `forcedDisable returns zero score regardless of signal`() {
        val strong = (1..5).map { s(50.0, ts = it * 300_000L) }  // strong ICE for 5 cycles
        val c = computeIceConfidence(strong, forcedDisable = IceDisableReason.EXERCISE_TEMP_TARGET)
        assertEquals(0.0, c.score, 0.001)
        assertEquals(IceDisableReason.EXERCISE_TEMP_TARGET, c.disabled)
    }

    // ── Magnitude scoring ───────────────────────────────────────────────────

    @Test fun `ICE below floor scores zero magnitude`() {
        // Single cycle at sub-floor.
        val c = computeIceConfidence(listOf(s(2.0)))   // 2 < 5.4 floor
        assertEquals(0.0, c.magnitude, 0.001)
        assertEquals(0.0, c.score, 0.001)
    }

    @Test fun `ICE at strong threshold scores full magnitude`() {
        // 4 cycles to get persistence too, otherwise score is zero.
        val samples = (1..4).map { s(30.0, ts = it * 300_000L) }
        val c = computeIceConfidence(samples)
        assertEquals(1.0, c.magnitude, 0.01)
    }

    // ── Persistence scoring ─────────────────────────────────────────────────

    @Test fun `single strong sample has full magnitude but minimal persistence`() {
        val c = computeIceConfidence(listOf(s(50.0)))  // 1 cycle = 1/3 persistence
        assertTrue(c.magnitude > 0.9)
        assertTrue(c.persistence < 0.5, "persistence=${c.persistence} should be < 0.5 for 1 cycle")
    }

    @Test fun `three consecutive strong same-sign samples give full persistence`() {
        val samples = (1..3).map { s(30.0, ts = it * 300_000L) }
        val c = computeIceConfidence(samples)
        assertEquals(1.0, c.persistence, 0.001)
    }

    @Test fun `sign change breaks the persistence streak`() {
        val samples = listOf(
            s(-30.0, ts = 1 * 300_000L),  // negative
            s(-30.0, ts = 2 * 300_000L),  // negative
            s(  30.0, ts = 3 * 300_000L)  // now positive — streak resets, this is cycle 1
        )
        val c = computeIceConfidence(samples)
        // Current sample is positive; only ONE positive cycle exists.
        assertTrue(c.persistence < 0.5, "persistence=${c.persistence} should be < 0.5 (only 1 in streak)")
    }

    // ── Consistency scoring ─────────────────────────────────────────────────

    @Test fun `low-variance samples give high consistency`() {
        // ICE values all around 30 with small jitter
        val samples = listOf(28.0, 30.0, 31.0, 29.0, 30.5)
            .mapIndexed { i, ice -> s(ice, ts = (i + 1) * 300_000L) }
        val c = computeIceConfidence(samples)
        assertTrue(c.consistency > 0.7, "consistency=${c.consistency} should be > 0.7 for low-variance signal")
    }

    @Test fun `high-variance samples give low consistency`() {
        // ICE values all over the place
        val samples = listOf(10.0, 50.0, 20.0, 60.0, 15.0)
            .mapIndexed { i, ice -> s(ice, ts = (i + 1) * 300_000L) }
        val c = computeIceConfidence(samples)
        assertTrue(c.consistency < 0.5, "consistency=${c.consistency} should be < 0.5 for noisy signal")
    }

    // ── Composite scoring ──────────────────────────────────────────────────

    @Test fun `realistic meal scenario produces high overall confidence`() {
        // Steady-rising ICE matching a real meal: 4 consecutive cycles, low jitter, strong magnitude.
        val samples = listOf(15.0, 22.0, 28.0, 30.0)
            .mapIndexed { i, ice -> s(ice, ts = (i + 1) * 300_000L) }
        val c = computeIceConfidence(samples)
        assertTrue(c.score > 0.5, "realistic meal score=${c.score} should be > 0.5")
        assertNull(c.disabled, "active meal must not be marked disabled")
    }

    @Test fun `noise signal produces low overall confidence`() {
        // ICE bouncing around zero, never sustaining above floor.
        val samples = listOf(2.0, -1.0, 3.0, -2.0, 1.0)
            .mapIndexed { i, ice -> s(ice, ts = (i + 1) * 300_000L) }
        val c = computeIceConfidence(samples)
        assertTrue(c.score < 0.2, "noise score=${c.score} should be < 0.2")
    }

    @Test fun `CGM warmup sample zeros the cgmQuality and thus the score`() {
        val samples = (1..4).map { s(30.0, ts = it * 300_000L) }.toMutableList()
        // Last sample marked with CGM warmup
        samples[3] = samples[3].copy(disabled = IceDisableReason.CGM_WARMUP)
        val c = computeIceConfidence(samples)
        assertEquals(0.0, c.cgmQuality, 0.001)
        assertEquals(0.0, c.score, 0.001)
    }

    // ── Reason text is informative ──────────────────────────────────────────

    @Test fun `reasonText includes all four component values`() {
        val samples = (1..3).map { s(20.0, ts = it * 300_000L) }
        val c = computeIceConfidence(samples)
        assertTrue(c.reasonText.contains("mag="))
        assertTrue(c.reasonText.contains("persist="))
        assertTrue(c.reasonText.contains("consist="))
        assertTrue(c.reasonText.contains("cgm="))
    }
}