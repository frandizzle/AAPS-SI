package app.aaps.core.interfaces.smartInsulin

/**
 * Learns how a meal estimate (from a photo, a description or a barcode) compares with what the meal
 * really needed, so the insulin planned from the next estimate fits this user.
 *
 * After each estimated meal, the grams the meal tracker saw are compared with the estimated grams.
 * [factor] is the median of that ratio over the last meals. Implementation: `MealEstimateCalibrationImpl`
 * in plugins/aps.
 */
interface MealEstimateCalibration {

    /** Multiply estimated grams by this before turning them into insulin. 1.0 until meals are learned. */
    val factor: Double

    /** How many meals [factor] is based on. */
    val mealsUsed: Int

    /**
     * Remembers an estimated meal, to be compared with the meal tracker once the meal is over.
     *
     * @param modeStartMs   start of the meal mode that was just activated
     * @param windowEndMs   end of the time to watch: the meal mode plus a following Protein/Fat, if any
     * @param carbEquivG    estimated carbs plus the carb equivalent of fat and protein, in grams
     * @param carbsG        estimated carbs, without fibre
     * @param proteinG      estimated protein
     * @param fatG          estimated fat
     */
    fun recordEstimate(modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double)

    /**
     * Adds food eaten later in the same meal mode (a dessert) to that meal's estimate, and moves the end
     * of the watch window. Does nothing when the meal has no estimate: the tracker would then see more
     * food than was estimated.
     */
    fun addToEstimate(modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double)

    /** The meal got food that was not estimated, for example a dose typed by hand, so it is not used to learn. */
    fun dropEstimate(modeStartMs: Long)

    /**
     * The food of the meal started at [modeStartMs] that has not come in yet, as carb equivalent in grams: its
     * estimate, scaled by [factor], less what the meal tracker has seen so far. Like the carbs on board of a
     * bolus calculator. Null when that meal has no estimate, so nothing is known about it.
     */
    fun foodStillToComeG(modeStartMs: Long): Double?
}
