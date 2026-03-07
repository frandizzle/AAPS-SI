package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealModeDetectorTest {

    // ── FASTING ───────────────────────────────────────────────────────────────

    @Test fun `zero COB and flat delta returns FASTING`() {
        assertEquals(MealMode.FASTING, MealModeDetector.detect(cob = 0.0, shortAvgDelta = 0.0))
    }

    @Test fun `small COB below low-carb threshold returns FASTING`() {
        assertEquals(MealMode.FASTING, MealModeDetector.detect(cob = 2.0, shortAvgDelta = 0.0))
    }

    // ── LOW_CARB ──────────────────────────────────────────────────────────────

    @Test fun `COB at low-carb threshold returns LOW_CARB`() {
        assertEquals(MealMode.LOW_CARB, MealModeDetector.detect(cob = 5.0, shortAvgDelta = 0.0))
    }

    @Test fun `small COB with mild rise returns LOW_CARB`() {
        assertEquals(MealMode.LOW_CARB, MealModeDetector.detect(cob = 3.0, shortAvgDelta = 2.5))
    }

    @Test fun `zero COB with mild rise returns LOW_CARB`() {
        assertEquals(MealMode.LOW_CARB, MealModeDetector.detect(cob = 0.0, shortAvgDelta = 2.0))
    }

    // ── LUNCH (generic meal) ──────────────────────────────────────────────────

    @Test fun `COB at meal threshold returns LUNCH`() {
        assertEquals(MealMode.LUNCH, MealModeDetector.detect(cob = 15.0, shortAvgDelta = 0.5))
    }

    @Test fun `large COB with slow delta returns LUNCH not EXTENDED`() {
        // slow delta → EXTENDED requires shortAvgDelta < 3.0 AND COB >= 30
        // COB=35 but delta=3.5 (above slow threshold) → still LUNCH
        assertEquals(MealMode.LUNCH, MealModeDetector.detect(cob = 35.0, shortAvgDelta = 3.5))
    }

    // ── EXTENDED ─────────────────────────────────────────────────────────────

    @Test fun `large COB with slow delta returns EXTENDED`() {
        // COB >= 30 AND delta < 3.0 → EXTENDED (flat BG despite high COB = slow absorption)
        assertEquals(MealMode.EXTENDED, MealModeDetector.detect(cob = 35.0, shortAvgDelta = 1.0))
    }

    @Test fun `COB just below extended threshold does not return EXTENDED`() {
        val mode = MealModeDetector.detect(cob = 29.9, shortAvgDelta = 1.0)
        assertEquals(MealMode.LUNCH, mode)
    }

    // ── MealMode sanity checks ────────────────────────────────────────────────

    @Test fun `FASTING has highest learning weight`() {
        assertTrue(MealMode.FASTING.learningWeight  > MealMode.LOW_CARB.learningWeight)
        assertTrue(MealMode.LOW_CARB.learningWeight > MealMode.LUNCH.learningWeight)
        assertTrue(MealMode.LUNCH.learningWeight    > MealMode.EXTENDED.learningWeight)
    }

    @Test fun `EXTENDED has DIA learning disabled`() {
        assertEquals(false, MealMode.EXTENDED.diaLearningEnabled)
    }

    @Test fun `all non-EXTENDED modes have DIA learning enabled`() {
        listOf(MealMode.FASTING, MealMode.LOW_CARB, MealMode.BREAKFAST, MealMode.LUNCH, MealMode.DINNER).forEach {
            assertEquals(true, it.diaLearningEnabled)
        }
    }
}