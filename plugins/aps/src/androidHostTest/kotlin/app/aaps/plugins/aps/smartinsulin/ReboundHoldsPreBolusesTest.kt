package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the recovery window after a low holds back the waiting pre-boluses. */
class ReboundHoldsPreBolusesTest {

    private val guard = 86.4 // 4.8 mmol

    private fun holds(inWindow: Boolean = true, meal: Boolean = true, minBg: Double, food: Boolean = false) =
        reboundHoldsPreBoluses(inWindow, meal, minBg, guard, food)

    @Test
    fun `nothing is held outside the window`() {
        assertFalse(holds(inWindow = false, meal = false, minBg = 60.0))
    }

    @Test
    fun `outside a meal mode any low holds`() {
        assertTrue(holds(meal = false, minBg = 86.0))
    }

    @Test
    fun `in a meal mode a shallow dip does not hold`() {
        // The lunch that started this: BG touched 4.8 while the food was still to come.
        assertFalse(holds(minBg = 86.4))
        // 4.5 mmol, 0.3 under the guard: still shallow.
        assertFalse(holds(minBg = 81.0))
    }

    @Test
    fun `in a meal mode a deeper low holds`() {
        assertTrue(holds(minBg = 80.9))
        assertTrue(holds(minBg = 65.0))
    }

    @Test
    fun `in a meal mode food eaten since the low holds, however shallow`() {
        assertTrue(holds(minBg = 86.4, food = true))
    }
}
