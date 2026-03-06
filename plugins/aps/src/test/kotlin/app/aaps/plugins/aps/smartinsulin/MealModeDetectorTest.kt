package app.aaps.plugins.aps.smartInsulin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MealModeDetector] using the scalar convenience overload
 * so we don't need AAPS interface mocks.
 */
class MealModeDetectorTest {

    private val now = System.currentTimeMillis()

    // ── FASTING ──────────────────────────────────────────────────────────────

    @Test fun `zero COB and no recent carbs returns FASTING`() {
        val mode = MealModeDetector.detect(
            cobGrams       = 0.0,
            lastCarbTimeMs = 0L,
            deltaMMol      = 0.0
        )
        assertEquals(MealMode.FASTING, mode)
    }

    @Test fun `old carb time beyond window returns FASTING`() {
        val twoHoursAgo = now - 2 * 60 * 60_000L
        val mode = MealModeDetector.detect(
            cobGrams       = 0.0,
            lastCarbTimeMs = twoHoursAgo,
            deltaMMol      = 0.0
        )
        assertEquals(MealMode.FASTING, mode)
    }

    // ── LOW_CARB ─────────────────────────────────────────────────────────────

    @Test fun `small COB below threshold returns LOW_CARB when enabled`() {
        val mode = MealModeDetector.detect(
            cobGrams         = 8.0,
            lastCarbTimeMs   = now - 20 * 60_000L,
            deltaMMol        = 0.1,
            lowCarbThreshold = 20,
            lowCarbEnabled   = true
        )
        assertEquals(MealMode.LOW_CARB, mode)
    }

    @Test fun `small COB returns MEAL when low carb mode disabled`() {
        val mode = MealModeDetector.detect(
            cobGrams         = 8.0,
            lastCarbTimeMs   = now - 20 * 60_000L,
            deltaMMol        = 0.1,
            lowCarbThreshold = 20,
            lowCarbEnabled   = false
        )
        // Falls through to FASTING since COB < MEAL threshold (10g)
        assertEquals(MealMode.FASTING, mode)
    }

    @Test fun `recent carbs within window but fully absorbed returns LOW_CARB`() {
        val thirtyMinsAgo = now - 30 * 60_000L
        val mode = MealModeDetector.detect(
            cobGrams         = 2.0,   // nearly absorbed
            lastCarbTimeMs   = thirtyMinsAgo,
            deltaMMol        = 0.0,
            lowCarbThreshold = 20,
            lowCarbEnabled   = true
        )
        assertEquals(MealMode.LOW_CARB, mode)
    }

    // ── MEAL ─────────────────────────────────────────────────────────────────

    @Test fun `COB at meal threshold returns MEAL`() {
        val mode = MealModeDetector.detect(
            cobGrams       = 10.0,
            lastCarbTimeMs = now - 30 * 60_000L,
            deltaMMol      = 0.1
        )
        assertEquals(MealMode.MEAL, mode)
    }

    @Test fun `large COB with flat delta returns MEAL not EXTENDED`() {
        val mode = MealModeDetector.detect(
            cobGrams       = 35.0,
            lastCarbTimeMs = now - 20 * 60_000L,
            deltaMMol      = 0.1   // below EXTENDED_DELTA_THRESHOLD
        )
        assertEquals(MealMode.MEAL, mode)
    }

    // ── EXTENDED ─────────────────────────────────────────────────────────────

    @Test fun `large COB with sustained rise returns EXTENDED`() {
        val mode = MealModeDetector.detect(
            cobGrams       = 35.0,
            lastCarbTimeMs = now - 30 * 60_000L,
            deltaMMol      = 0.4   // above EXTENDED_DELTA_THRESHOLD
        )
        assertEquals(MealMode.EXTENDED, mode)
    }

    @Test fun `COB just below extended threshold does not return EXTENDED`() {
        val mode = MealModeDetector.detect(
            cobGrams       = 29.9,
            lastCarbTimeMs = now - 15 * 60_000L,
            deltaMMol      = 0.5
        )
        assertEquals(MealMode.MEAL, mode)
    }

    // ── Learning weight sanity ────────────────────────────────────────────────

    @Test fun `FASTING has highest learning weight`() {
        assert(MealMode.FASTING.learningWeight > MealMode.LOW_CARB.learningWeight)
        assert(MealMode.LOW_CARB.learningWeight > MealMode.MEAL.learningWeight)
        assert(MealMode.MEAL.learningWeight > MealMode.EXTENDED.learningWeight)
    }

    @Test fun `EXTENDED has DIA learning disabled`() {
        assertEquals(false, MealMode.EXTENDED.diaLearningEnabled)
    }

    @Test fun `all other modes have DIA learning enabled`() {
        listOf(MealMode.FASTING, MealMode.LOW_CARB, MealMode.MEAL).forEach {
            assertEquals(true, it.diaLearningEnabled)
        }
    }
}
