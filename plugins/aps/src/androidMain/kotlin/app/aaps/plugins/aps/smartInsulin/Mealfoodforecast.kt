package app.aaps.plugins.aps.smartInsulin

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A meal's food that has not come in yet, from its estimate, as the loop's forecast sees it: like carbs on board,
 * but split by how fast it comes in. Grams of carb equivalent.
 *
 * @param carbsUntilMs      the carbs should have come in by this time
 * @param fatProteinUntilMs the fat and protein should have come in by this time: the end of the meal's window
 * @param carbRatio         grams per unit, to turn the food into BG
 * @param coveredG          food the waiting pre-boluses will cover, already taken off; for the reason string
 */
data class FoodToCome(
    val carbsG: Double,
    val carbsUntilMs: Long,
    val fatProteinG: Double,
    val fatProteinUntilMs: Long,
    val carbRatio: Double,
    val coveredG: Double = 0.0
) {

    val totalG: Double get() = carbsG + fatProteinG
}

/**
 * The food of a meal with an estimate, put into the loop's forecast, so the loop doses for food still coming
 * instead of predicting a low from the insulin on board alone. See `DetermineBasalSmartInsulin`.
 *
 * The carbs are counted as coming in evenly until [FoodToCome.carbsUntilMs] ([CARBS_MIN] after they were eaten),
 * the fat and protein evenly until the end of the meal's window. Food the waiting pre-boluses will cover is
 * taken off first, so the loop does not give insulin that a pre-bolus is about to give. Food that does not show
 * up fades out with the time left of the meal, so a smaller meal than estimated is not dosed for long.
 */
object MealFoodForecast {

    /** Minutes carbs take to come in, from when they are eaten. */
    const val CARBS_MIN = 120
    /** The food still to come may be at most this many times the meal's share of the time left. */
    const val FADE_SLACK = 1.5
    /** Less food than this is not worth forecasting. */
    const val MIN_FOOD_G = 2.0
    /** Food is spread over at least this many 5-min steps: carbs 30 min, fat and protein 1 hour. */
    private const val MIN_CARB_STEPS = 6
    private const val MIN_FAT_PROTEIN_STEPS = 12
    private const val STEP_MS = 5 * 60_000L

    /**
     * The food still to come for the forecast, or null when there is too little.
     *
     * @param restU the rest of pre-bolus 1 still waiting: it covers carbs
     * @param pbU   pre-bolus 2 and 3 still waiting: they cover fat and protein
     */
    fun foodToCome(still: StillToCome, nowMs: Long, restU: Double, pbU: Double, carbRatio: Double): FoodToCome? {
        if (carbRatio <= 0.0) return null
        var carbs = still.carbsG
        var fatProtein = still.fatProteinG
        // Each waiting pre-bolus covers its own part first; what is left of it covers the other part.
        var restG = restU.coerceAtLeast(0.0) * carbRatio
        var pbG = pbU.coerceAtLeast(0.0) * carbRatio
        val c1 = min(carbs, restG).also { carbs -= it; restG -= it }
        val f1 = min(fatProtein, pbG).also { fatProtein -= it; pbG -= it }
        val f2 = min(fatProtein, restG).also { fatProtein -= it }
        val c2 = min(carbs, pbG).also { carbs -= it }

        // Food that has not shown up by now fades with the time left of the meal.
        val spanMs = (still.windowEndMs - still.modeStartMs).coerceAtLeast(1L)
        val leftMs = (still.windowEndMs - nowMs).coerceAtLeast(0L)
        val maxG = still.mealG * leftMs / spanMs * FADE_SLACK
        val total = carbs + fatProtein
        if (total > maxG) {
            val share = if (total > 0.0) maxG / total else 0.0
            carbs *= share
            fatProtein *= share
        }
        if (carbs + fatProtein < MIN_FOOD_G) return null
        return FoodToCome(carbs, still.carbsUntilMs, fatProtein, still.windowEndMs, carbRatio, c1 + f1 + f2 + c2)
    }

    /**
     * The BG rise [food] adds at each 5-min step ahead, mg/dL. The rise BG already shows is in the forecast on
     * its own, fading out ([ciAheadMgdl] is all of it still to come), so that much food is taken off first,
     * carbs before fat and protein, and the same grams are not counted twice.
     *
     * @param isfMgdl the same ISF the forecast uses for the insulin, so food and insulin are weighed alike
     */
    fun steps(food: FoodToCome, nowMs: Long, isfMgdl: Double, ciAheadMgdl: Double, count: Int): List<Double> {
        val csf = if (food.carbRatio > 0.0) isfMgdl / food.carbRatio else 0.0
        if (csf <= 0.0 || count <= 0) return List(max(count, 0)) { 0.0 }
        var seenG = ciAheadMgdl.coerceAtLeast(0.0) / csf
        val carbs = (food.carbsG - seenG).coerceAtLeast(0.0)
        seenG = (seenG - food.carbsG).coerceAtLeast(0.0)
        val fatProtein = (food.fatProteinG - seenG).coerceAtLeast(0.0)
        val carbSteps = max(MIN_CARB_STEPS, ceil((food.carbsUntilMs - nowMs).toDouble() / STEP_MS).toInt())
        val fatProteinSteps = max(MIN_FAT_PROTEIN_STEPS, ceil((food.fatProteinUntilMs - nowMs).toDouble() / STEP_MS).toInt())
        return List(count) { i ->
            val g = (if (i < carbSteps) carbs / carbSteps else 0.0) + (if (i < fatProteinSteps) fatProtein / fatProteinSteps else 0.0)
            g * csf
        }
    }
}
