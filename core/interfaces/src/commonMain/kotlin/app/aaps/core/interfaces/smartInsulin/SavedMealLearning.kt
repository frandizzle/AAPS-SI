package app.aaps.core.interfaces.smartInsulin

/**
 * What a meal has learned about the insulin each of its parts needs, as factors on the plan.
 *
 * @param carbFactor   for the carbs, given by pre-bolus 1
 * @param waveFactor   for the fat and protein wave, given by pre-bolus 2
 * @param tailFactor   for what is left of the wave on a heavy meal, given by pre-bolus 3
 * @param meals        how many times the meal was judged
 * @param ateSoon      of the last few times, how often BG started to rise right after pre-bolus 1, so the meal
 *                     was probably eaten without waiting
 * @param recentMeals  how many of the last few times [ateSoon] is out of
 * @param minMeals     the factors are used from this many meals
 */
data class LearnedMeal(
    val carbFactor: Double,
    val waveFactor: Double,
    val tailFactor: Double,
    val meals: Int,
    val ateSoon: Int,
    val recentMeals: Int,
    val minMeals: Int = MIN_MEALS
) {

    val inUse: Boolean get() = meals >= minMeals

    companion object {

        /** A saved meal uses what it learned from this many meals. */
        const val MIN_MEALS = 2
    }
}

/**
 * Learns how much insulin each part of a meal needs: the carbs (pre-bolus 1), the fat and protein wave
 * (pre-bolus 2), and on a heavy meal the tail (pre-bolus 3).
 *
 * Every meal planned from food is watched until it is over, and the food BG showed in each part is compared
 * with what was planned. A low cuts the part it happened in. A saved meal (an entry in My foods, for example
 * "Turkey wrap") learns its own factors; every meal also adds to [general], for meals that have learned nothing
 * of their own. While a saved meal is watched, the mode ISF and DURA learners do not judge the same episode,
 * so one spike is not corrected twice. Implementation: `SavedMealLearningImpl` in plugins/aps.
 */
interface SavedMealLearning {

    /** What the saved meal [name] has learned, or null when it was never judged. */
    fun learned(name: String): LearnedMeal?

    /** What all meals together have learned, or null before the first one. */
    fun general(): LearnedMeal?

    /**
     * Starts watching a meal mode that was started with food.
     *
     * @param name          the saved meal it is, or null for any other food
     * @param modeEndMs     when the meal mode itself is planned to end
     * @param windowEndMs   when to stop counting food: the mode plus a following Protein/Fat, if any
     * @param pb3DueMs      when pre-bolus 3 is planned, 0 when there is none
     * @param carbsG        carbs as planned, without fibre
     * @param waveG         carb equivalent of the fat and protein that pre-bolus 2 covers, in grams
     * @param tailG         carb equivalent of the fat and protein that pre-bolus 3 covers, in grams
     */
    fun recordMeal(name: String?, modeStartMs: Long, modeEndMs: Long, windowEndMs: Long, pb3DueMs: Long, carbsG: Double, waveG: Double, tailG: Double)

    /** The meal got other food, so it no longer says anything about what was planned. */
    fun dropMeal(modeStartMs: Long)

    /** Keeps what was learned when the saved meal is renamed. */
    fun rename(name: String, newName: String)

    /** Forgets what [name] has learned, for example when it is deleted from My foods. */
    fun forget(name: String)
}
