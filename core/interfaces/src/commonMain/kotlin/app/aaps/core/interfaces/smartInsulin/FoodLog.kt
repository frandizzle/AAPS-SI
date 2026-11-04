package app.aaps.core.interfaces.smartInsulin

/**
 * One thing eaten, as logged from the Smart Meal dialog.
 *
 * @param timeMs when it was logged; also tells entries apart
 * @param name   what was eaten, in the user's words or from the estimate
 * @param items  each food of it, as the meal listed them; empty for entries logged before foods were kept
 */
data class FoodLogEntry(
    val timeMs: Long,
    val name: String,
    val carbsG: Double,
    val fibreG: Double,
    val proteinG: Double,
    val fatG: Double,
    val items: List<FoodLogItem> = emptyList(),
    /** Logged with "Log only" while a meal ran: the loop counts it as food of that meal, and no insulin was given with it. */
    val mealSnack: Boolean = false,
    /** Grams of its carbs that treated a low. The loop gives no insulin for them. */
    val forLowG: Double = 0.0
) {

    /** Energy as on food labels: 4 kcal per gram of carbs and protein, 2 per gram of fibre, 9 per gram of fat. */
    val kcal: Double get() = carbsG * 4.0 + fibreG * 2.0 + proteinG * 4.0 + fatG * 9.0

    /** This entry with all of it [factor] times as much: 0.5 for half. */
    fun times(factor: Double): FoodLogEntry = withAmounts(List(items.size.coerceAtLeast(1)) { factor })

    /**
     * This entry with each food [factors] times as much, one factor per food in [items] (a missing one is 1). The totals
     * are then the foods added up. An entry without foods is all of it the first factor times as much.
     */
    fun withAmounts(factors: List<Double>): FoodLogEntry {
        if (items.isEmpty()) {
            val f = factors.firstOrNull() ?: 1.0
            return copy(carbsG = carbsG * f, fibreG = fibreG * f, proteinG = proteinG * f, fatG = fatG * f)
        }
        val changed = items.mapIndexed { i, item -> item.times(factors.getOrElse(i) { 1.0 }) }
        return copy(
            items = changed, carbsG = changed.sumOf { it.carbsG }, fibreG = changed.sumOf { it.fibreG },
            proteinG = changed.sumOf { it.proteinG }, fatG = changed.sumOf { it.fatG }
        )
    }
}

/** One food of a [FoodLogEntry], with its weight and what is in it, in grams. */
data class FoodLogItem(
    val name: String,
    val grams: Double,
    val carbsG: Double,
    val fibreG: Double,
    val proteinG: Double,
    val fatG: Double
) {

    /** As [FoodLogEntry.kcal]. */
    val kcal: Double get() = carbsG * 4.0 + fibreG * 2.0 + proteinG * 4.0 + fatG * 9.0

    /** This food [factor] times as much. */
    fun times(factor: Double): FoodLogItem =
        copy(grams = grams * factor, carbsG = carbsG * factor, fibreG = fibreG * factor, proteinG = proteinG * factor, fatG = fatG * factor)
}

/**
 * The food eaten each day, for counting calories against a daily goal. Kept on the phone for
 * [DAYS_KEPT] days. Implementation: `FoodLogImpl` in plugins/aps.
 */
interface FoodLog {

    fun add(entry: FoodLogEntry)

    /** What was logged from [fromMs] (included) to [toMs] (not included), oldest first. */
    fun entries(fromMs: Long, toMs: Long): List<FoodLogEntry>

    /** Replaces the entry logged at the same time, for example with a smaller amount eaten. */
    fun update(entry: FoodLogEntry)

    /** Removes the entry logged at [timeMs], for example one logged twice. */
    fun delete(timeMs: Long)

    companion object {

        const val DAYS_KEPT = 30

        /** One entry above this is probably a mistake in the amounts, so the user is asked to check it. */
        const val CHECK_KCAL = 2500.0
    }
}
