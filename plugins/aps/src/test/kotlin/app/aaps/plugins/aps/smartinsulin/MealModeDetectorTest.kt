package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealModeDetectorTest {

    @Test fun `default returns FASTING`() {
        assertEquals(MealMode.FASTING, MealModeDetector.detect(null))
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
