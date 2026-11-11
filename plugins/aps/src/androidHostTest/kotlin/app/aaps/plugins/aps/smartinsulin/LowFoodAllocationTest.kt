package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [LowFoodAllocation]: the carbs of snacks eaten just before a low, and up to 15 g of the meal's food still to come, go to the low. */
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

    // ── The meal's own food still to come ─────────────────────────────────────

    private fun still(carbsG: Double, fatProteinG: Double, parts: List<CarbsPart> = emptyList()) =
        StillToCome(modeStartMs = low - 4 * HOUR, windowEndMs = low + HOUR, carbsG = carbsG, fatProteinG = fatProteinG,
                    mealG = 60.0, carbsUntilMs = low + HOUR, carbParts = parts)

    @Test
    fun `at the tail of a lunch all of the little food left goes to the low`() {
        // 10 Nov: 6 g left as the loop counts it, all fat and protein, at 70 %.
        val tail = still(carbsG = 0.0, fatProteinG = 6.0 / 0.7)
        val grams = LowFoodAllocation.mealFoodForLow(listOf(tail), snackG = 0.0, fatProteinShare = 0.7)
        assertEquals(6.0, grams, 1e-9)
        val left = LowFoodAllocation.takeForLow(listOf(tail), grams, 0.7).single()
        assertEquals(0.0, left.carbsG, 1e-9)
        assertEquals(0.0, left.fatProteinG, 1e-9)
    }

    @Test
    fun `at most 15 g of a meal go to one low, carbs first, and each part of the carbs loses the same share`() {
        val parts = listOf(CarbsPart(leftG = 10.0, mealG = 20.0, untilMs = low, minutes = 30), CarbsPart(leftG = 10.0, mealG = 20.0, untilMs = low, minutes = 90))
        val meal = still(carbsG = 20.0, fatProteinG = 20.0, parts = parts)
        val grams = LowFoodAllocation.mealFoodForLow(listOf(meal), snackG = 0.0, fatProteinShare = 0.7)
        assertEquals(LowFoodAllocation.MEAL_FOOD_MAX_G, grams, 1e-9)
        val left = LowFoodAllocation.takeForLow(listOf(meal), grams, 0.7).single()
        assertEquals(5.0, left.carbsG, 1e-9)
        assertEquals(20.0, left.fatProteinG, 1e-9)
        assertEquals(listOf(2.5, 2.5), left.carbParts.map { it.leftG })
    }

    @Test
    fun `fat and protein come after the carbs, at the share the forecast counts them`() {
        val meal = still(carbsG = 5.0, fatProteinG = 20.0)
        val left = LowFoodAllocation.takeForLow(listOf(meal), 12.0, 0.7).single()
        assertEquals(0.0, left.carbsG, 1e-9)
        // 7 g as the forecast counts it is 10 g of fat and protein.
        assertEquals(10.0, left.fatProteinG, 1e-9)
    }

    @Test
    fun `snack carbs given to the same low count against the meal food`() {
        val meal = still(carbsG = 20.0, fatProteinG = 0.0)
        assertEquals(5.0, LowFoodAllocation.mealFoodForLow(listOf(meal), snackG = 10.0, fatProteinShare = 0.7), 1e-9)
        assertEquals(0.0, LowFoodAllocation.mealFoodForLow(listOf(meal), snackG = 20.0, fatProteinShare = 0.7), 1e-9)
    }

    @Test
    fun `food added to the meal after the low gives its carbs to the low first`() {
        // 11 Nov: 15 g went to a low at the tail of a dinner (49 g of fat and protein left, as the loop counts them). A
        // yoghurt eaten 11 min later at 4.7 (7 g carbs, 6 g of fat and protein as carbs) joined the meal: its carbs went to
        // the low, then 8 g of the fat and protein.
        val dinner = still(carbsG = 0.0, fatProteinG = 49.0 / 0.9)
        val withYoghurt = dinner.copy(carbsG = 7.0, fatProteinG = dinner.fatProteinG + 6.0)
        val left = LowFoodAllocation.takeForLow(listOf(withYoghurt), 15.0, 0.9).single()
        assertEquals(0.0, left.carbsG, 1e-9)
        assertEquals(49.0 + 6.0 * 0.9 - 8.0, left.fatProteinG * 0.9, 1e-9)
    }

    @Test
    fun `the earlier meal loses food first, and nothing is taken past the grams`() {
        val earlier = still(carbsG = 4.0, fatProteinG = 0.0)
        val running = still(carbsG = 30.0, fatProteinG = 0.0)
        val left = LowFoodAllocation.takeForLow(listOf(earlier, running), 10.0, 0.7)
        assertEquals(listOf(0.0, 24.0), left.map { it.carbsG })
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
