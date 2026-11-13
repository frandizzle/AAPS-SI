package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
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
    fun `all the carbs count, not only the share pre-bolus 1 gives`() {
        val f = MealFoodForecast.foodToCome(dinner, T0, 0.0, 0.0, 5.0, fatProteinShare = 0.7)!!
        assertEquals(50.0, f.carbsG, 1e-9)
        assertEquals(21.0, f.fatProteinG, 1e-9)
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
    fun `carbs that come in slower keep their rate, up to one and a half times their time`() {
        // 50 g over 2 hours: 25 g an hour.
        val part = CarbsPart(leftG = 50.0, mealG = 50.0, untilMs = T0 + 2 * HOUR, minutes = 120)
        // On time: half of it in after an hour, the rest by the 2 hours.
        assertEquals(T0 + 2 * HOUR, MealFoodForecast.carbsUntil(part.copy(leftG = 25.0), T0 + HOUR))
        // None of it in after an hour: the 50 g take 2 hours more at that rate, so they are in by 3 hours, not 2.
        val late = MealFoodForecast.foodToCome(dinner.copy(carbParts = listOf(part)), T0 + HOUR, 0.0, 0.0, 5.0)!!
        assertEquals(T0 + 3 * HOUR, late.carbsUntilMs)
        // Never later than 3 hours: 30 g left at 2.5 hours are in by then.
        assertEquals(T0 + 3 * HOUR, MealFoodForecast.carbsUntil(part.copy(leftG = 30.0), T0 + 2 * HOUR + 30 * MIN))
        // Without a known time, as planned.
        assertEquals(T0 + 2 * HOUR, MealFoodForecast.carbsUntil(part.copy(minutes = 0), T0 + HOUR))
    }

    @Test
    fun `food not eaten yet starts to come in when it is eaten`() {
        // "Eat in" 20 min: 30 g over 60 min, eaten at 20 min, in by 80 min.
        val part = CarbsPart(leftG = 30.0, mealG = 30.0, untilMs = T0 + 80 * MIN, minutes = 60, fromMs = T0 + 20 * MIN)
        val f = FoodToCome(30.0, T0 + 80 * MIN, 0.0, T0 + 80 * MIN, 5.0, carbParts = listOf(part))
        val steps = MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 24)
        // Nothing in the first 20 min, then 30 g over the 12 steps of the hour after.
        (0 until 4).forEach { assertEquals(0.0, steps[it], 1e-9) }
        assertEquals(30.0 / 12 * 10.0, steps[4], 1e-9)
        assertEquals(300.0, steps.sum(), 1e-6)
        // Its rate counts from when it is eaten, not from now.
        assertEquals(T0 + 80 * MIN, MealFoodForecast.carbsUntil(part, T0))
    }

    @Test
    fun `fast and slow food in one meal give a quick rise and then a long tail`() {
        // 20 g of juice over 75 min and 40 g of pizza carbs over 3 hours, nothing in yet.
        val parts = listOf(CarbsPart(20.0, 20.0, T0 + 75 * MIN, 75), CarbsPart(40.0, 40.0, T0 + 3 * HOUR, 180))
        val meal = dinner.copy(carbsG = 60.0, fatProteinG = 0.0, mealG = 60.0, carbsUntilMs = T0 + 3 * HOUR, carbParts = parts)
        val f = MealFoodForecast.foodToCome(meal, T0, 0.0, 0.0, 5.0)!!
        val steps = MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 48)
        // 10 mg/dL per gram: the juice over 15 steps and the pizza over 36 at first, then the pizza alone.
        assertEquals((20.0 / 15 + 40.0 / 36) * 10.0, steps[0], 1e-9)
        assertEquals(40.0 / 36 * 10.0, steps[20], 1e-9)
        assertEquals(600.0, steps.sum(), 1e-6)
        // A rise of 20 g already showing is the juice.
        val seen = MealFoodForecast.steps(f, T0, isfMgdl = 50.0, ciAheadMgdl = 200.0, count = 48)
        assertEquals(40.0 / 36 * 10.0, seen[0], 1e-9)
    }

    @Test
    fun `all the food still to come counts for the rise limit, also what pre-boluses cover`() {
        // A protein lunch: 1 g of carbs left and 20 g of fat and protein, at 70 %.
        val lunch = dinner.copy(carbsG = 1.0, fatProteinG = 20.0)
        assertEquals(15.0, MealFoodForecast.foodLeftG(lunch, 0.7), 1e-9)
        // The forecast's share, with the pre-boluses' part taken off, is too little to forecast; the food is still coming.
        assertNull(MealFoodForecast.foodToCome(lunch, T0, 0.0, 3.0, 5.0, 0.7))
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

    @Test
    fun `fat and protein take longer the more there is`() {
        assertEquals(180, MealEstimateCalibration.fatProteinMinutes(0.5))
        assertEquals(180, MealEstimateCalibration.fatProteinMinutes(1.0))
        assertEquals(210, MealEstimateCalibration.fatProteinMinutes(2.0))
        assertEquals(240, MealEstimateCalibration.fatProteinMinutes(3.0))
        // 13 Nov lunch, about 6 units: 5 h, not 8 h.
        assertEquals(300, MealEstimateCalibration.fatProteinMinutes(6.0))
        assertEquals(300, MealEstimateCalibration.fatProteinMinutes(10.0))
        // The user's "Fat and protein time" makes all of it shorter or longer.
        assertEquals(240, MealEstimateCalibration.fatProteinMinutes(6.0, timePct = 80))
        assertEquals(360, MealEstimateCalibration.fatProteinMinutes(6.0, timePct = 120))
        assertEquals(90, MealEstimateCalibration.fatProteinMinutes(1.0, timePct = 50))
    }

    @Test
    fun `fat and protein come in at the meal's steady rate, not squeezed into the time left`() {
        // 30 g of fat and protein over 4 h: 0.625 g every 5 min. A 3 h window, 2 h in, nothing shown yet: the fade
        // leaves 30 g x 1/3 x 1.5 = 15 g. Before, those 15 g were squeezed into the last hour, twice the rate, so the
        // forecast climbed steeply. Now they come in at the same 0.625 g and finish an hour after the window.
        val steak = StillToCome(
            modeStartMs = T0, windowEndMs = T0 + 3 * HOUR, carbsG = 0.0, fatProteinG = 30.0, mealG = 30.0, carbsUntilMs = T0,
            fatProteinFromMs = T0, fatProteinMealG = 30.0, fatProteinMin = 240
        )
        val now = T0 + 2 * HOUR
        val f = MealFoodForecast.foodToCome(steak, now, 0.0, 0.0, 5.0)!!
        assertEquals(15.0, f.fatProteinG, 1e-9)
        assertEquals(now + 2 * HOUR, f.fatProteinUntilMs)
        val steps = MealFoodForecast.steps(f, now, isfMgdl = 50.0, ciAheadMgdl = 0.0, count = 48)
        assertEquals(0.625 * 10.0, steps[0], 1e-9)
        assertEquals(0.625 * 10.0, steps[23], 1e-9)
        assertEquals(0.0, steps[24], 1e-9)
    }

    @Test
    fun `the share Fill in plans sets the rate too`() {
        // At 70 %, 30 g over 4 h is 21 g: 0.4375 g every 5 min, all 21 g in over the 4 h.
        val steak = StillToCome(
            modeStartMs = T0, windowEndMs = T0 + 6 * HOUR, carbsG = 0.0, fatProteinG = 30.0, mealG = 30.0, carbsUntilMs = T0,
            fatProteinFromMs = T0, fatProteinMealG = 30.0, fatProteinMin = 240
        )
        val f = MealFoodForecast.foodToCome(steak, T0, 0.0, 0.0, 5.0, fatProteinShare = 0.7)!!
        assertEquals(T0 + 4 * HOUR, f.fatProteinUntilMs)
        assertEquals(0.4375 * 10.0, MealFoodForecast.steps(f, T0, 50.0, 0.0, 48)[0], 1e-9)
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val T0 = 1_790_000_000_000L
    }
}
