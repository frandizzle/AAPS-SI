package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MealModeDetectorTest {

    private val overrideManager: MealOverrideManager = mock()

    @Test
    fun `detect returns FASTING when override manager is null`() {
        assertEquals(MealMode.FASTING, MealModeDetector.detect(null))
    }

    @Test
    fun `detect returns FASTING when no override is active`() {
        whenever(overrideManager.activeMealMode).thenReturn(null)
        assertEquals(MealMode.FASTING, MealModeDetector.detect(overrideManager))
    }

    @Test
    fun `detect returns active mode from override manager`() {
        whenever(overrideManager.activeMealMode).thenReturn(MealMode.LUNCH)
        assertEquals(MealMode.LUNCH, MealModeDetector.detect(overrideManager))

        whenever(overrideManager.activeMealMode).thenReturn(MealMode.DINNER)
        assertEquals(MealMode.DINNER, MealModeDetector.detect(overrideManager))
    }

    // ── MealMode sanity checks ────────────────────────────────────────────────

    @Test
    fun `FASTING has highest learning weight`() {
        assertTrue(MealMode.FASTING.learningWeight > MealMode.LOW_CARB.learningWeight)
        assertTrue(MealMode.LOW_CARB.learningWeight > MealMode.LUNCH.learningWeight)
        assertTrue(MealMode.LUNCH.learningWeight > MealMode.EXTENDED.learningWeight)
    }

    @Test
    fun `EXTENDED mode has DIA learning disabled`() {
        assertEquals(false, MealMode.EXTENDED.diaLearningEnabled)
    }

    @Test
    fun `all non-EXTENDED modes have DIA learning enabled`() {
        listOf(MealMode.FASTING, MealMode.LOW_CARB, MealMode.BREAKFAST, MealMode.LUNCH, MealMode.DINNER).forEach {
            assertEquals(true, it.diaLearningEnabled)
        }
    }
}
