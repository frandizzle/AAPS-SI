package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import kotlin.math.min

/**
 * Which carbs go to a low. Food logged as a snack in a meal ([FoodLogEntry.mealSnack]) in the [WINDOW_MS] before a low
 * was most likely eaten for it, or did not come in as planned. Its carbs treat the low, so the loop must not give insulin
 * for them when BG comes back up. Food that came with its own insulin is the meal itself and is not moved.
 *
 * The meal's own food still to come does the same, up to [MEAL_FOOD_MAX_G]: see [takeForLow].
 */
object LowFoodAllocation {

    /** Food logged this long before the low counts. */
    const val WINDOW_MS = 45 * 60_000L

    /** At most this many grams of carbs go to one low, about one treatment. More is a meal. */
    const val MAX_G = 20.0

    /**
     * At most this many grams (carb equivalent) of a meal's food still to come go to one low, about one treatment. Snack
     * carbs moved to the same low ([carbsForLow]) count against it.
     */
    const val MEAL_FOOD_MAX_G = 15.0

    /**
     * How many grams of the meals' food still to come go to a low that starts now: what is left of [MEAL_FOOD_MAX_G]
     * after [snackG] of snack carbs, and no more than the food left in [stills]. Fat and protein count at
     * [fatProteinShare], as the forecast counts them.
     */
    fun mealFoodForLow(stills: List<StillToCome>, snackG: Double, fatProteinShare: Double): Double =
        min((MEAL_FOOD_MAX_G - snackG).coerceAtLeast(0.0), stills.sumOf { MealFoodForecast.foodLeftG(it, fatProteinShare) })

    /**
     * [stills] with [grams] taken off for a low: food still to come that will bring BG back up, so the loop must not give
     * insulin for it when BG comes back above the low guard. Carbs first, they bring BG up first; then fat and protein,
     * which count at [fatProteinShare]. Each part of the carbs loses the same share. 10 Nov: the last 6 g of a lunch were
     * still counted after a low at its tail.
     */
    fun takeForLow(stills: List<StillToCome>, grams: Double, fatProteinShare: Double): List<StillToCome> {
        var leftG = grams.coerceAtLeast(0.0)
        val share = fatProteinShare.coerceIn(0.0, 1.0)
        return stills.map { still ->
            if (leftG <= 0.0) return@map still
            val carbsTaken = min(still.carbsG, leftG).also { leftG -= it }
            val fatProteinTaken = if (share > 0.0) min(still.fatProteinG * share, leftG).also { leftG -= it } / share else 0.0
            if (carbsTaken <= 0.0 && fatProteinTaken <= 0.0) return@map still
            val carbsKept = if (still.carbsG > 0.0) (still.carbsG - carbsTaken) / still.carbsG else 0.0
            still.copy(
                carbsG = still.carbsG - carbsTaken,
                fatProteinG = (still.fatProteinG - fatProteinTaken).coerceAtLeast(0.0),
                carbParts = still.carbParts.map { it.copy(leftG = it.leftG * carbsKept) }
            )
        }
    }

    /**
     * The carbs of each of [entries] that go to the low that starts at [lowStartMs], the newest food first, up to
     * [MAX_G] in all. Food already used for a low is left alone.
     */
    fun carbsForLow(entries: List<FoodLogEntry>, lowStartMs: Long): List<Pair<FoodLogEntry, Double>> {
        var leftG = MAX_G
        return entries
            .filter { it.mealSnack && it.forLowG <= 0.0 && it.carbsG > 0.0 && it.timeMs >= lowStartMs - WINDOW_MS && it.timeMs < lowStartMs }
            .sortedByDescending { it.timeMs }
            .mapNotNull { entry ->
                val grams = min(entry.carbsG, leftG)
                if (grams <= 0.0) null else entry to grams.also { leftG -= it }
            }
    }
}
