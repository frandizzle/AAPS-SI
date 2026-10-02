package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [bgBelowGuard]: a low is when the BG shown on screen is lower than the guard. */
class BgBelowGuardTest {

    private val guard48 = 4.8 * 18.0   // 86.4 mg/dL, as a 4.8 mmol guard is stored

    @Test fun `mmol - everything that shows as 4_7 is below a 4_8 guard`() {
        // 4.65..4.75 mmol shows as 4.7. Smoothed values have decimals: 85.4 used to slip through.
        listOf(83.7, 84.0, 85.0, 85.4, 85.49).forEach {
            assertTrue(bgBelowGuard(it, guard48, mmol = true), "$it mg/dL (${it / 18}) should be below")
        }
    }

    @Test fun `mmol - everything that shows as 4_8 is not below a 4_8 guard`() {
        listOf(85.5, 86.0, 86.4, 87.0).forEach {
            assertFalse(bgBelowGuard(it, guard48, mmol = true), "$it mg/dL (${it / 18}) should not be below")
        }
    }

    @Test fun `mmol - other guards`() {
        assertTrue(bgBelowGuard(3.9 * 18 - 1.0, 4.0 * 18, mmol = true))   // shows 3.9
        assertFalse(bgBelowGuard(4.0 * 18, 4.0 * 18, mmol = true))        // shows 4.0
        assertTrue(bgBelowGuard(5.4 * 18, 5.5 * 18, mmol = true))         // shows 5.4
    }

    @Test fun `mg_dL - shows lower than the guard is below`() {
        assertTrue(bgBelowGuard(85.0, 86.0, mmol = false))
        assertTrue(bgBelowGuard(85.4, 86.0, mmol = false))    // shows 85
        assertFalse(bgBelowGuard(85.5, 86.0, mmol = false))   // shows 86
        assertFalse(bgBelowGuard(86.0, 86.0, mmol = false))
    }
}
