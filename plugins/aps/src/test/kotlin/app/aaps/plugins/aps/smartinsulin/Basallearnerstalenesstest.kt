package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.math.abs
import kotlin.math.sign

/**
 * Staleness-attenuation tests for [BasalLearner].
 *
 * Contract being tested (mirror of the production code in BasalLearner.kt):
 *
 *   1. lastLearnMs == 0L (never learned, or save format predates the field)
 *      → no attenuation. The raw multiplier passes through untouched.
 *
 *   2. (now − lastLearnMs) ≤ STALENESS_THRESHOLD_MS (21 days)
 *      → no attenuation. Learner is fresh enough to trust its learned value.
 *
 *   3. (now − lastLearnMs) >  STALENESS_THRESHOLD_MS
 *      → halve the deviation from 1.0. attenuated = 1.0 + (raw − 1.0) × 0.5.
 *      Pulls a stale learner toward the safe default (the profile basal) but does
 *      not erase it — recovery is immediate as soon as fresh observations resume.
 *
 *   4. Attenuation never crosses 1.0 (the safe default). Sign of (raw − 1.0) is
 *      preserved; only magnitude is halved.
 *
 *   5. [reasonSummary] appends " STALE" when stale and not when fresh, so the
 *      diagnostic logs distinguish the two cases.
 *
 * ## Test strategy
 *
 * BasalLearner exposes no public seam for setting [lastLearnMs] directly. The
 * normal path that bumps it — onLoopCycle driving a successful learn step —
 * needs many minutes of BG samples passing tight gate conditions, which is
 * unsuitable for a unit test.
 *
 * Instead, we exploit the [restoreState] path: stage a JSON blob in the mocked
 * Preferences with a known globalMultiplier and a controlled lastLearnMs, then
 * construct the learner. Its init block consumes the JSON, and a single
 * [multiplierClamped] read observes the attenuation behaviour.
 *
 * ## Maintenance note
 *
 * [THRESHOLD_DAYS] below MUST match `STALENESS_THRESHOLD_DAYS` in BasalLearner.
 * If the production constant changes, update this file accordingly.
 */
class BasalLearnerStalenessTest {

    private val DAY_MS         = 24L * 60 * 60 * 1000L
    private val THRESHOLD_DAYS = 21L
    private val THRESHOLD_MS   = THRESHOLD_DAYS * DAY_MS

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Build a learner whose restoreState() consumes the supplied saved-state JSON. */
    private fun learnerWith(savedJson: String): BasalLearner {
        val prefs = mock<Preferences>()
        whenever(prefs.get(any<StringKey>())).thenReturn(savedJson)
        val logger = mock<AAPSLogger>()
        return BasalLearner(prefs, logger)
    }

    /** Build a learner with empty preferences — cold start, never learned. */
    private fun coldStartLearner(): BasalLearner = learnerWith("")

    /**
     * Construct a saved-state JSON with controllable global multiplier and lastLearnMs.
     * Day arrays are omitted so the day-blend factor is 0 and the global multiplier
     * cleanly dominates [BasalLearner.multiplierClamped] — keeps the test focused on
     * staleness, not on the global↔per-day blend.
     */
    private fun savedState(globalMult: Double, lastLearnMs: Long): String =
        JSONObject()
            .put("multiplier", globalMult)
            .put("lastLearnMs", lastLearnMs)
            .toString()

    // ── Contract 1: lastLearnMs == 0L → no attenuation ───────────────────────

    @Test
    fun `cold start (empty prefs) returns 1_0`() {
        val learner = coldStartLearner()
        assertEquals(1.0, learner.multiplierClamped, 1e-9)
    }

    @Test
    fun `cold start with corrupted non-1_0 multiplier but no lastLearnMs returns raw`() {
        // A save format predating lastLearnMs would have a learned multiplier but no
        // timestamp; optLong defaults the missing field to 0L. The contract says
        // lastLearnMs ≤ 0L is the "no information about freshness" signal, and we
        // must NOT attenuate — attenuating data that might be perfectly current is
        // a worse failure than failing to attenuate truly old data.
        val learner = learnerWith(
            JSONObject().put("multiplier", 1.20).toString()  // lastLearnMs missing → 0L
        )
        assertEquals(1.20, learner.multiplierClamped, 1e-6)
    }

    // ── Contract 2: fresh → no attenuation ───────────────────────────────────

    @Test
    fun `1 day old multiplier is returned unchanged`() {
        val now = System.currentTimeMillis()
        val learner = learnerWith(savedState(1.20, now - 1 * DAY_MS))
        assertEquals(1.20, learner.multiplierClamped, 1e-6)
    }

    @Test
    fun `just inside threshold (20 days old) returns raw multiplier`() {
        val now = System.currentTimeMillis()
        val learner = learnerWith(savedState(1.20, now - 20 * DAY_MS))
        assertEquals(1.20, learner.multiplierClamped, 1e-6)
    }

    // ── Contract 3: stale → halve deviation ──────────────────────────────────

    @Test
    fun `just past threshold (22 days old) halves above-1_0 deviation`() {
        val now = System.currentTimeMillis()
        val learner = learnerWith(savedState(1.20, now - 22 * DAY_MS))
        // 1.0 + (1.20 − 1.0) × 0.5 = 1.10
        assertEquals(1.10, learner.multiplierClamped, 1e-6)
    }

    @Test
    fun `far past threshold (100 days old) halves below-1_0 deviation`() {
        val now = System.currentTimeMillis()
        val learner = learnerWith(savedState(0.80, now - 100 * DAY_MS))
        // 1.0 + (0.80 − 1.0) × 0.5 = 0.90
        assertEquals(0.90, learner.multiplierClamped, 1e-6)
    }

    @Test
    fun `neutral multiplier 1_0 is unaffected by staleness`() {
        val now = System.currentTimeMillis()
        val learner = learnerWith(savedState(1.0, now - 100 * DAY_MS))
        assertEquals(1.0, learner.multiplierClamped, 1e-9)
    }

    @Test
    fun `saturated multipliers attenuate cleanly within clamp bounds`() {
        val now = System.currentTimeMillis()
        // Max (1.5) stale → 1.0 + 0.5 × 0.5 = 1.25 (well within [0.7, 1.5])
        val highLearner = learnerWith(savedState(1.5, now - 100 * DAY_MS))
        assertEquals(1.25, highLearner.multiplierClamped, 1e-6)
        // Min (0.7) stale → 1.0 + (−0.3) × 0.5 = 0.85 (well within [0.7, 1.5])
        val lowLearner = learnerWith(savedState(0.7, now - 100 * DAY_MS))
        assertEquals(0.85, lowLearner.multiplierClamped, 1e-6)
    }

    // ── Contract 4: attenuation pulls toward 1.0, never past it ──────────────

    @Test
    fun `stale attenuation pulls toward 1_0 and preserves sign of deviation`() {
        // Property test: across a range of raw values, the attenuated value must be
        // (a) strictly closer to 1.0 than the raw and (b) on the same side of 1.0.
        // This guards against a sign error in the attenuation formula re-introducing
        // a class of bugs analogous to the historical ISF sign-inversion.
        val now = System.currentTimeMillis()
        val rawValues = listOf(0.70, 0.85, 0.92, 1.08, 1.15, 1.35, 1.50)
        for (raw in rawValues) {
            val learner = learnerWith(savedState(raw, now - 100 * DAY_MS))
            val attenuated = learner.multiplierClamped
            assertTrue(
                abs(attenuated - 1.0) < abs(raw - 1.0),
                "stale attenuation must move closer to 1.0: raw=$raw → attenuated=$attenuated"
            )
            assertEquals(
                sign(raw - 1.0),
                sign(attenuated - 1.0),
                "attenuation must NOT cross 1.0: raw=$raw → attenuated=$attenuated"
            )
        }
    }

    @Test
    fun `fresh-vs-stale produces different multiplier for non-neutral value`() {
        // Same raw multiplier, two ages — one fresh, one stale — must produce
        // different multiplierClamped values. Guards against the staleness path
        // becoming silently inert (e.g. if applyStalenessAttenuation became a no-op).
        val now = System.currentTimeMillis()
        val freshLearner = learnerWith(savedState(1.20, now - 5 * DAY_MS))
        val staleLearner = learnerWith(savedState(1.20, now - 30 * DAY_MS))
        assertNotEquals(
            freshLearner.multiplierClamped,
            staleLearner.multiplierClamped,
            "fresh and stale learners with the same raw multiplier must produce different multiplierClamped values"
        )
    }

    // ── Contract 5: reasonSummary diagnostic marker ──────────────────────────

    @Test
    fun `reasonSummary appends STALE only when stale`() {
        val now = System.currentTimeMillis()
        val freshLearner = learnerWith(savedState(1.20, now - 5  * DAY_MS))
        val staleLearner = learnerWith(savedState(1.20, now - 30 * DAY_MS))
        assertTrue(
            !freshLearner.reasonSummary.contains("STALE"),
            "fresh learner reasonSummary must NOT contain STALE — got: ${freshLearner.reasonSummary}"
        )
        assertTrue(
            staleLearner.reasonSummary.contains("STALE"),
            "stale learner reasonSummary must contain STALE — got: ${staleLearner.reasonSummary}"
        )
    }

    @Test
    fun `reasonSummary shows attenuated mult and raw global side by side when stale`() {
        // Format: "basal_x{attenuated}(g={raw_global}) STALE"
        // Reading both numbers in logs lets you see how much pull-toward-1.0 is happening.
        val now = System.currentTimeMillis()
        val learner = learnerWith(savedState(1.20, now - 30 * DAY_MS))
        val summary = learner.reasonSummary
        // attenuated = 1.10
        assertTrue(
            summary.contains("1.10"),
            "stale reasonSummary should show the attenuated mult 1.10 — got: $summary"
        )
        // raw global = 1.20 in g=... portion
        assertTrue(
            summary.contains("g=1.20"),
            "stale reasonSummary should show the raw global 1.20 in g=... — got: $summary"
        )
        assertTrue(
            summary.contains("STALE"),
            "stale reasonSummary should contain STALE marker — got: $summary"
        )
    }

    @Test
    fun `cold start reasonSummary contains no STALE marker (lastLearnMs == 0L)`() {
        // Specifically covers the "lastLearnMs == 0L" guard. Without the guard, a
        // huge negative age (now − 0L = now) would falsely trigger stale.
        val learner = coldStartLearner()
        val summary = learner.reasonSummary
        assertTrue(
            !summary.contains("STALE"),
            "cold-start reasonSummary must NOT contain STALE — got: $summary"
        )
    }
}