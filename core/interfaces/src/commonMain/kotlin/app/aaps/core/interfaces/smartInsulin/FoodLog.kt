package app.aaps.core.interfaces.smartInsulin

/**
 * One thing eaten, as logged from the Smart Meal dialog.
 *
 * @param timeMs when it was logged; also tells entries apart
 * @param name   what was eaten, in the user's words or from the estimate
 */
data class FoodLogEntry(
    val timeMs: Long,
    val name: String,
    val carbsG: Double,
    val fibreG: Double,
    val proteinG: Double,
    val fatG: Double
) {

    /** Energy as on food labels: 4 kcal per gram of carbs and protein, 2 per gram of fibre, 9 per gram of fat. */
    val kcal: Double get() = carbsG * 4.0 + fibreG * 2.0 + proteinG * 4.0 + fatG * 9.0
}

/**
 * The food eaten each day, for counting calories against a daily goal. Kept on the phone for
 * [DAYS_KEPT] days. Implementation: `FoodLogImpl` in plugins/aps.
 */
interface FoodLog {

    fun add(entry: FoodLogEntry)

    /** What was logged from [fromMs] (included) to [toMs] (not included), oldest first. */
    fun entries(fromMs: Long, toMs: Long): List<FoodLogEntry>

    /** Removes the entry logged at [timeMs], for example one logged twice. */
    fun delete(timeMs: Long)

    companion object {

        const val DAYS_KEPT = 30

        /** One entry above this is probably a mistake in the amounts, so the user is asked to check it. */
        const val CHECK_KCAL = 2500.0
    }
}
