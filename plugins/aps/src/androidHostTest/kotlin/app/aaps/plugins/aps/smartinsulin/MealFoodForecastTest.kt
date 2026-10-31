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

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val T0 = 1_790_000_000_000L
    }
}
