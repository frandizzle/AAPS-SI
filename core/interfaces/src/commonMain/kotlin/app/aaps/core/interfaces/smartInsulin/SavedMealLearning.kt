package app.aaps.core.interfaces.smartInsulin

/**
 * What one saved meal (an entry in My foods, for example "Turkey wrap") has learned about itself.
 *
 * @param carbFactor        multiply the carb insulin of this meal by this
 * @param fatProteinFactor  multiply the fat and protein insulin of this meal by this
 * @param meals             how many times the meal was judged
 * @param ateSoon           of the last few times, how often BG started to rise right after pre-bolus 1,
 *                          so the meal was probably eaten without waiting
 * @param recentMeals       how many of the last few times [ateSoon] is out of
 */
data class LearnedMeal(
    val carbFactor: Double,
    val fatProteinFactor: Double,
    val meals: Int,
    val ateSoon: Int,
    val recentMeals: Int
) {

    /** The factors are only used once the meal was judged this often. */
    val inUse: Boolean get() = meals >= MIN_MEALS

    companion object {

        const val MIN_MEALS = 2
    }
}

/**
 * Learns, for each saved meal, how much insulin its carbs and its fat and protein really need.
 *
 * A meal started from one saved entry is watched until it is over: how much food BG showed in the first
 * two hours is compared with its carbs, and after that with its fat and protein. A low cuts the part
 * of the meal it happened in. While a saved meal is watched, the mode ISF and DURA learners do not judge
 * the same episode, so one spike is not corrected twice. Implementation: `SavedMealLearningImpl` in
 * plugins/aps.
 */
interface SavedMealLearning {

    /** What [name] has learned, or null when it was never judged. */
    fun learned(name: String): LearnedMeal?

    /**
     * Starts watching a meal mode that was started from the saved meal [name].
     *
     * @param modeEndMs     when the meal mode itself is planned to end
     * @param windowEndMs   when to stop counting food: the mode plus a following Protein/Fat, if any
     * @param carbsG        carbs of the meal as planned, without fibre
     * @param fatProteinG   carb equivalent of the fat and protein as planned, in grams
     */
    fun recordMeal(name: String, modeStartMs: Long, modeEndMs: Long, windowEndMs: Long, carbsG: Double, fatProteinG: Double)

    /** The meal got other food, so it no longer says anything about the saved meal. */
    fun dropMeal(modeStartMs: Long)

    /** Keeps what was learned when the saved meal is renamed. */
    fun rename(name: String, newName: String)

    /** Forgets what [name] has learned, for example when it is deleted from My foods. */
    fun forget(name: String)
}
