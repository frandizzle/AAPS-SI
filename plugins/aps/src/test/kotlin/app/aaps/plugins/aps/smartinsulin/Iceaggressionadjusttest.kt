package app.aaps.plugins.aps.smartInsulin.ice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IceAggressionAdjustTest {

    // ── Hard returns: disabled / null / invalid ─────────────────────────────

    @Test fun `disabled returns exactly 1_0 regardless of signal`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 50.0, confidence = 1.0, disabled = true, userWeight = 1.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    @Test fun `null ICE returns 1_0`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = null, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    @Test fun `NaN ICE returns 1_0 (defensive)`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = Double.NaN, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    @Test fun `Infinity ICE returns 1_0 (defensive)`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = Double.POSITIVE_INFINITY, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    @Test fun `userWeight zero returns 1_0 — user has opted out of ICE influence`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 50.0, confidence = 1.0, disabled = false, userWeight = 0.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    @Test fun `confidence zero returns 1_0`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 50.0, confidence = 0.0, disabled = false, userWeight = 1.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    // ── Positive ICE pushes aggression up ───────────────────────────────────

    @Test fun `saturated positive ICE with full confidence and full weight gives max boost`() {
        // ICE = saturation, confidence = 1, weight = 1 → 1 + 1*1 = 2.0
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 27.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(2.0, r, 0.0001)
    }

    @Test fun `half-saturated positive ICE gives proportional boost`() {
        // ICE = saturation/2 = 13.5, full conf/weight → 1 + 0.5*1 = 1.5
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 13.5, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(1.5, r, 0.001)
    }

    @Test fun `half confidence halves the effect`() {
        // ICE = saturation, conf = 0.5, weight = 1 → 1 + 1 * 0.5 = 1.5
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 27.0, confidence = 0.5, disabled = false, userWeight = 1.0
        )
        assertEquals(1.5, r, 0.001)
    }

    @Test fun `half weight halves the effect`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 27.0, confidence = 1.0, disabled = false, userWeight = 0.5
        )
        assertEquals(1.5, r, 0.001)
    }

    // ── Negative ICE backs off, asymmetrically ──────────────────────────────

    @Test fun `saturated negative ICE with negativeBoost 1_5 gives stronger reduction than symmetric`() {
        // ICE = -saturation, conf=1, weight=1, negBoost=1.5 → 1 + -1 * 1 * 1.5 = -0.5 → clamped 0.3
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = -27.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(0.3, r, 0.0001)  // clamped at floor
    }

    @Test fun `moderate negative ICE applies negativeBoost asymmetry`() {
        // ICE = -13.5 (half-saturation), conf=1, weight=1, negBoost=1.5
        // → 1 + -0.5 * 1 * 1.5 = 0.25 → clamped 0.3
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = -13.5, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(0.3, r, 0.0001)
    }

    @Test fun `mild negative ICE produces above-floor reduction`() {
        // ICE = -5, conf=1, weight=1, negBoost=1.5
        // normalised = -5/27 ≈ -0.185
        // adjust = 1 + -0.185 * 1 * 1.5 = 1 - 0.278 = 0.722
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = -5.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(0.722, r, 0.005)
    }

    @Test fun `asymmetry — same magnitude positive vs negative ICE produces different effect`() {
        val positive = computeIceAggressionAdjust(
            iceMgdlPerH = 10.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        val negative = computeIceAggressionAdjust(
            iceMgdlPerH = -10.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )

        val positiveBoost = positive - 1.0      // amount of boost above neutral
        val negativeBackoff = 1.0 - negative    // amount of cut below neutral

        assertTrue(negativeBackoff > positiveBoost,
                   "negative ICE should produce stronger reduction than equivalent positive ICE produces boost " +
                       "(boost=$positiveBoost cut=$negativeBackoff)")

        // Specifically: with negativeBoost=1.5, negativeBackoff should be exactly 1.5× positiveBoost
        assertEquals(1.5, negativeBackoff / positiveBoost, 0.001)
    }

    // ── Bounds enforced ────────────────────────────────────────────────────

    @Test fun `extremely high positive ICE clamps at upper bound`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 500.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(2.0, r, 0.0001)
    }

    @Test fun `extremely high userWeight cannot blow the upper bound`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 50.0, confidence = 1.0, disabled = false, userWeight = 10.0  // bad config
        )
        assertTrue(r <= 2.0, "must stay clamped, was $r")
    }

    @Test fun `extremely negative ICE clamps at lower bound`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = -500.0, confidence = 1.0, disabled = false, userWeight = 1.0
        )
        assertEquals(0.3, r, 0.0001)
    }

    // ── Bad config defensive paths ──────────────────────────────────────────

    @Test fun `negative saturation config returns 1_0 (no divide-by-zero hazard)`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 10.0, confidence = 1.0, disabled = false, userWeight = 1.0,
            saturationMgdlH = -1.0
        )
        assertEquals(1.0, r, 0.0001)
    }

    @Test fun `zero saturation config returns 1_0`() {
        val r = computeIceAggressionAdjust(
            iceMgdlPerH = 10.0, confidence = 1.0, disabled = false, userWeight = 1.0,
            saturationMgdlH = 0.0
        )
        assertEquals(1.0, r, 0.0001)
    }
}