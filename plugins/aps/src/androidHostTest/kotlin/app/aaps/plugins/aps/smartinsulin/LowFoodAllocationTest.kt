package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [LowFoodAllocation]: the carbs of snacks eaten just before a low go to the low. */
class LowFoodAllocationTest {

    private val low = 10 * HOUR

    private fun snack(minBefore: Int, carbsG: Double, name: String = "Yoghurt", mealSnack: Boolean = true, forLowG: Double = 0.0) =
        FoodLogEntry(low - minBefore * MIN, name, carbsG, 0.0, 30.0, 0.0, mealSnack = mealSnack, forLowG = forLowG)

    @Test
    fun `a snack eaten just before the low goes to it, all its carbs`() {
        val yoghurt = snack(18, 14.0)
        assertEquals(listOf(yoghurt to 14.0), LowFoodAllocation.carbsForLow(listOf(yoghurt), low))
    }

    @Test
    fun `at most 20 g go to one low, the newest food first`() {
        val older = snack(40, 15.0, "Banana")
        val newer = snack(10, 14.0, "Yoghurt")
        assertEquals(listOf(newer to 14.0, older to 6.0), LowFoodAllocation.carbsForLow(listOf(older, newer), low))
    }

    @Test
    fun `food from before the window, after the low starts, or with its own insulin is not moved`() {
        val tooEarly = snack(46, 14.0)
        val afterStart = FoodLogEntry(low, "Juice", 15.0, 0.0, 0.0, 0.0, mealSnack = true)
        val meal = snack(20, 42.0, "Sandwich", mealSnack = false)
        assertEquals(emptyList<Pair<FoodLogEntry, Double>>(), LowFoodAllocation.carbsForLow(listOf(tooEarly, afterStart, meal), low))
    }

    @Test
    fun `food already used for a low is not used again`() {
        assertEquals(emptyList<Pair<FoodLogEntry, Double>>(), LowFoodAllocation.carbsForLow(listOf(snack(10, 14.0, forLowG = 14.0)), low))
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
