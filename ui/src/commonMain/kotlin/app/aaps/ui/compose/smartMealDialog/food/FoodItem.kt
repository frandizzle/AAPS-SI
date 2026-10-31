package app.aaps.ui.compose.smartMealDialog.food

import androidx.compose.runtime.Immutable

/** Where a food came from. [SAVED] is anything added from My foods, so a new estimate does not replace it. */
enum class FoodSource { ESTIMATE, BARCODE, MANUAL, SAVED }

/**
 * One food in a planned meal, from an estimate or a scanned barcode.
 *
 * Nutrition is kept per 100 g and the amount as grams, so the totals are worked out here and not by the
 * estimating model, and changing [quantity] scales everything the same way.
 *
 * @param baseGrams   one portion as estimated, or one serving of a scanned product (100 g when the
 *                    product gives no serving size)
 * @param portion     what [baseGrams] is, in words, for example "1 cup cooked" or "30 g". May be empty.
 * @param quantity    how many of [baseGrams] were eaten
 * @param carbsPer100 available carbohydrate, without fibre
 */
@Immutable
data class FoodItem(
    val name: String,
    val baseGrams: Double,
    val portion: String,
    val quantity: Double = 1.0,
    val carbsPer100: Double,
    val fibrePer100: Double,
    val proteinPer100: Double,
    val fatPer100: Double,
    val source: FoodSource
) {

    val grams: Double get() = baseGrams * quantity
    val carbsG: Double get() = carbsPer100 * grams / 100.0
    val fibreG: Double get() = fibrePer100 * grams / 100.0
    val proteinG: Double get() = proteinPer100 * grams / 100.0
    val fatG: Double get() = fatPer100 * grams / 100.0
}

/** What a meal adds up to. */
@Immutable
data class MealTotals(val carbsG: Double, val fibreG: Double, val proteinG: Double, val fatG: Double) {

    /**
     * Fat-protein units (Warsaw method): 100 kcal from fat and protein is one unit, and one unit acts
     * like 10 g of carbs spread over several hours.
     */
    val fpu: Double get() = (fatG * 9.0 + proteinG * 4.0) / 100.0

    /** Carbs plus the carb equivalent of fat and protein. */
    val carbEquivG: Double get() = carbsG + fpu * FPU_CARB_G

    companion object {

        const val FPU_CARB_G = 10.0
    }
}

fun List<FoodItem>.totals(): MealTotals =
    MealTotals(sumOf { it.carbsG }, sumOf { it.fibreG }, sumOf { it.proteinG }, sumOf { it.fatG })

/**
 * A food typed in by hand: what [grams] of it hold, as on a label or a recipe. Null when the numbers
 * cannot be right: no weight, a value below 0, or more carbs, protein and fat than the weight itself.
 */
fun manualFood(name: String, portion: String, grams: Double, carbsG: Double, proteinG: Double, fatG: Double): FoodItem? {
    val clean = name.trim()
    if (clean.isEmpty() || grams <= 0.0 || carbsG < 0.0 || proteinG < 0.0 || fatG < 0.0) return null
    if (carbsG + proteinG + fatG > grams) return null
    return FoodItem(
        name = clean,
        baseGrams = grams,
        portion = portion.trim(),
        carbsPer100 = carbsG * 100.0 / grams,
        fibrePer100 = 0.0,
        proteinPer100 = proteinG * 100.0 / grams,
        fatPer100 = fatG * 100.0 / grams,
        source = FoodSource.MANUAL
    )
}
