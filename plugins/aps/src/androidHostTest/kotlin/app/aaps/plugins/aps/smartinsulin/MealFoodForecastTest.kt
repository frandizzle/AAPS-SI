package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [MealFoodForecast]: a meal's food still to come, as the loop's forecast counts it. */
class MealFoodForecastTest {

    /** A 4 h Dinner of 50 g carbs and 30 g fat and protein, started at [T0], nothing come in yet. */
    private val dinner = StillToCome(
        modeStartMs = T0, windowEndMs = T0 + 4 * HOUR, carbsG = 50.0, fatProteinG = 30.0, mealG = 80.0, carbsUntilMs = T0 + 2 * HOUR
    )

    @Test
    fun `waiting pre-boluses cover their own part first`() {
        // Pre-bolus 2 of 4 U at 5 g/U covers 20 g of the fat and protein; the rest of pre-bolus 1, 2 U, 10 g of carbs.
        val f = MealFoodForecast.foodToCome(dinner, T0, restU = 2.0, pbU = 4.0, carbRatio = 5.0)!!
        assertEquals(40.0, f.carbsG, 1e-9)
        assertEquals(10.0, f.fatProteinG, 1e-9)
        assertEquals(30.0, f.coveredG, 1e-9)
    }

    @Test
    fun `fat and protein count at the share Fill in plans`() {
        val f = MealFoodForecast.foodToCome(dinner, T0, 0.0, 0.0, 5.0, fatProteinShare = 0.7)!!
        assertEquals(50.0, f.carbsG, 1e-9)
        assertEquals(21.0, f.fatProteinG, 1e-9)
    }

    @Test
    fun `carbs count at the share Fill in plans`() {
        val f = MealFoodForecast.foodToCome(dinner, T0, 0.0, 0.0, 5.0, carbShare = 0.8)!!
        assertEquals(40.0, f.carbsG, 1e-9)
        assertEquals(30.0, f.fatProteinG, 1e-9)
    }

    @Test
    fun `the held back carbs count once BG rises, and never when it does not`() {
        val waiting = MealFoodForecast.foodToCome(dinner, T0, 0.0, 0.0, 5.0, carbShare = 0.8)!!
        assertEquals(10.0, waiting.heldBackCarbsG, 1e-9)
        val given = MealFoodForecast.foodToCome(dinner.copy(carbTopUp = CarbTopUp.GIVEN), T0, 0.0, 0.0, 5.0, carbShare = 0.8)!!
        assertEquals(50.0, given.carbsG, 1e-9)
        assertEquals(0.0, given.heldBackCarbsG, 1e-9)
        val dropped = MealFoodForecast.foodToCome(dinner.copy(carbTopUp = CarbTopUp.DROPPED), T0, 0.0, 0.0, 5.0, carbShare = 0.8)!!
        assertEquals(40.0, dropped.carbsG, 1e-9)
        assertEquals(0.0, dropped.heldBackCarbsG, 1e-9)
    }

    @Test
    fun `the held back carbs wait for a rise between 15 and 45 min`() {
        val w = CarbTopUp.WAITING
        // Rising, but too early.
        assertEquals(w, MealFoodForecast.carbTopUp(w, 10 * MIN, 120.0, 3.0, 2.0))
        // Flat, or rising from a low BG: still waiting.
        assertEquals(w, MealFoodForecast.carbTopUp(w, 20 * MIN, 120.0, 0.5, 0.5))
        assertEquals(w, MealFoodForecast.carbTopUp(w, 20 * MIN, 85.0, 3.0, 2.0))
        // Rising: given, and it stays given.
        assertEquals(CarbTopUp.GIVEN, MealFoodForecast.carbTopUp(w, 20 * MIN, 120.0, 3.0, 2.0))
        assertEquals(CarbTopUp.GIVEN, MealFoodForecast.carbTopUp(CarbTopUp.GIVEN, 90 * MIN, 100.0, -5.0, -5.0))
        // No rise by 45 min: dropped, also when BG rises later.
        assertEquals(CarbTopUp.DROPPED, MealFoodForecast.carbTopUp(w, 46 * MIN, 120.0, 3.0, 2.0))
        assertEquals(CarbTopUp.DROPPED, MealFoodForecast.carbTopUp(CarbTopUp.DROPPED, 60 * MIN, 120.0, 3.0, 2.0))
    }

    @Test
    fun `a pre-bolus bigger than its part covers the other part too`() {
        val f = MealFoodForecast.foodToCome(dinner, T0, restU = 0.0, pbU = 8.0, carbRatio = 5.0)!!
        assertEquals(0.0, f.fatProteinG, 1e-9)
        assertEquals(40.0, f.carbsG, 1e-9)
    }

    @Test
    fun `food that does not show up fades with the time left`() {
        // 3 h in, a quarter of the window is left: at most 80 g x 1/4 x 1.5 = 30 g still counts.
        val f = MealFoodForecast.foodToCome(dinner, T0 + 3 * HOUR, 0.0, 0.0, 5.0)!!
        assertEquals(30.0, f.totalG, 1e-9)
        assertEquals(50.0 / 80.0, f.carbsG / f.totalG, 1e-9)
        // At the end of the window nothing is left.
        assertNull(MealFoodForecast.foodToCome(dinner, T0 + 4 * HOUR, 0.0, 0.0, 5.0))
    }

    @Test
    fun `carbs come in over their 2 hours, fat and protein over the whole window`() {
        val f = MealFoodForecast.foodToCome(dinner, T0, 0.0, 0.0, 5.0)!!
        // ISF 50 mg/dL/U at 5 g/U: 10 mg/dL per gram.
        val steps = MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 48)
        assertEquals((50.0 / 24 + 30.0 / 48) * 10.0, steps[0], 1e-9)
        assertEquals(30.0 / 48 * 10.0, steps[24], 1e-9)
        assertEquals(800.0, steps.sum(), 1e-6)       // all 80 g within the 4 h forecast
    }

    @Test
    fun `fast carbs come in sooner and slow carbs later`() {
        val carbsOnly = dinner.copy(fatProteinG = 0.0, mealG = 50.0)
        fun firstHour(carbsMin: Int): Double {
            val still = carbsOnly.copy(carbsUntilMs = T0 + carbsMin * MIN)
            val f = MealFoodForecast.foodToCome(still, T0, 0.0, 0.0, 5.0)!!
            return MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 48).take(12).sum()
        }
        // 50 g at 10 mg/dL per gram: fast 60 of 75 min, normal 60 of 120, slow 60 of 180.
        assertEquals(400.0, firstHour(75), 1e-6)
        assertEquals(250.0, firstHour(120), 1e-6)
        assertEquals(500.0 / 3, firstHour(180), 1e-6)
    }

    @Test
    fun `two meals' food adds up, and the rise BG shows is taken off once, from the older first`() {
        val snack = FoodToCome(20.0, T0 + HOUR, 0.0, T0 + HOUR, 5.0)
        val meal = FoodToCome(50.0, T0 + 2 * HOUR, 0.0, T0 + 2 * HOUR, 5.0)
        // ISF 50 at 5 g/U: 10 mg/dL per gram. Both: 700 mg/dL.
        assertEquals(700.0, MealFoodForecast.steps(listOf(snack, meal), T0, 50.0, 0.0, 48).sum(), 1e-6)
        // A rise of 30 g already showing: all 20 g of the snack, then 10 g of the meal.
        val steps = MealFoodForecast.steps(listOf(snack, meal), T0, 50.0, 300.0, 48)
        assertEquals(400.0, steps.sum(), 1e-6)
        assertEquals(40.0 / 24 * 10.0, steps[0], 1e-9)
    }

    @Test
    fun `the rise BG already shows is not counted twice`() {
        val f = MealFoodForecast.foodToCome(dinner, T0, 0.0, 0.0, 5.0)!!
        // The seen rise brings 600 mg/dL = 60 g: all 50 g of carbs and 10 g of the fat and protein.
        val steps = MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 600.0, count = 48)
        assertEquals(200.0, steps.sum(), 1e-6)
        assertEquals(20.0 / 48 * 10.0, steps[0], 1e-9)
    }

    @Test
    fun `late carbs are still spread over at least half an hour`() {
        val f = MealFoodForecast.foodToCome(dinner, T0 + 2 * HOUR + 30 * MIN, 0.0, 0.0, 5.0)!!
        val steps = MealFoodForecast.steps(f, T0 + 2 * HOUR + 30 * MIN, 50.0, 0.0, 48)
        assertEquals(f.carbsG / 6 * 10.0 + f.fatProteinG / 18 * 10.0, steps[0], 1e-9)
    }

    @Test
    fun `fat and protein add nothing before they start, then come in as usual`() {
        val f = MealFoodForecast.foodToCome(dinner.copy(fatProteinFromMs = T0 + 45 * MIN), T0, 0.0, 0.0, 5.0)!!
        assertEquals(30.0, f.fatProteinG, 1e-9)      // still to come, only not yet
        val before = MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 48)
        assertEquals(500.0, before.sum(), 1e-6)       // only the 50 g of carbs
        val after = MealFoodForecast.steps(f, T0 + 45 * MIN, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 48)
        assertEquals(30.0 / 39 * 10.0, after[30], 1e-9)  // spread from 45 min to the end of the window
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val T0 = 1_790_000_000_000L
    }
}
