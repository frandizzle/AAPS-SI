package app.aaps.core.interfaces.smartInsulin

/**
 * The carbs of a meal that come in at one speed: [carbsG] grams over [minutes] from when they are eaten. A meal of
 * juice and pizza has a fast part and a slow part, so the loop expects a quick rise and then a long tail.
 */
data class CarbPart(val carbsG: Double, val minutes: Int)

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
     * @param learn         false for a meal that says nothing about the estimates, for example one given less
     *                      insulin after exercise. Its food still counts as food still to come.
     * @param carbParts     the carbs by how fast they come in; empty: all of [carbsG] at normal speed
     * @param estimatedG    the part of [carbEquivG] from a photo or description estimate. Only that part is scaled by
     *                      [factor], and only a meal that is all estimated teaches it. The rest is exact: scanned,
     *                      typed in or saved.
     * @param eatenMs       when the food is eaten: later than [modeStartMs] with "Eat in". Its carbs and fat and protein
     *                      are expected from then.
     */
    fun recordEstimate(
        modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, learn: Boolean = true,
        carbParts: List<CarbPart> = emptyList(), estimatedG: Double = carbEquivG, eatenMs: Long = modeStartMs
    )

    /**
     * Adds food eaten later in the same meal mode (a dessert) to that meal's estimate, and moves the end
     * of the watch window. Does nothing when the meal has no estimate: the tracker would then see more
     * food than was estimated.
     *
     * @param eatenMs  when the added food is eaten: its carbs are expected to come in from then, at their speeds
     * @param carbParts the added carbs by how fast they come in, see [recordEstimate]
     * @param estimatedG the part of [carbEquivG] from a photo or description estimate, see [recordEstimate]
     */
    fun addToEstimate(
        modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, eatenMs: Long,
        carbParts: List<CarbPart> = emptyList(), estimatedG: Double = carbEquivG
    )

    /**
     * Food logged without insulin (Log only) while the meal mode started at [modeStartMs] runs, for example a snack
     * left to the loop. It counts as that meal's food still to come, so the loop's forecast knows it is coming, but
     * the meal is not used to learn: the food came without its insulin. A meal mode without an estimate gets one.
     *
     * @param modeEndMs when the mode ends; the food is watched at least until its carbs should have come in
     * @param eatenMs   when the food is eaten
     * @param carbParts its carbs by how fast they come in, see [recordEstimate]
     * @param estimatedG the part of [carbEquivG] from a photo or description estimate, see [recordEstimate]
     */
    fun logFood(
        modeStartMs: Long, modeEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, eatenMs: Long,
        carbParts: List<CarbPart> = emptyList(), estimatedG: Double = carbEquivG
    )

    /** The meal got food that was not estimated, for example a dose typed by hand, so it is not used to learn. */
    fun dropEstimate(modeStartMs: Long)

    /**
     * The food of the meal started at [modeStartMs] that has not come in yet, as carb equivalent in grams: its
     * estimate, scaled by [factor], less what the meal tracker has seen so far. Like the carbs on board of a
     * bolus calculator. Its fat and protein also fade with time, so all of it is gone by the end of its time at
     * [nowMs]. Null when that meal has no estimate, so nothing is known about it.
     */
    fun foodStillToComeG(modeStartMs: Long, nowMs: Long): Double?

    /**
     * The food of all meals whose window is still open at [nowMs] that has not come in yet, as carb equivalent in
     * grams. A new meal weighs the insulin on board against it, so insulin given for that food is not taken as spare.
     */
    fun openMealsStillToComeG(nowMs: Long): Double

    companion object {

        /** Minutes carbs of normal speed take to come in, from when they are eaten. */
        const val DEFAULT_CARBS_MIN = 120
        /** Carbs that come in slower than expected may take at most this many times their carb time, as in Loop. */
        const val CARBS_STRETCH = 1.5
        /** Minutes after they are eaten that fat and protein start to raise BG. */
        const val FAT_PROTEIN_START_MIN = 45
        /** Fat and protein of one fat-protein unit (100 kcal) take this long to come in, from when they start... */
        const val FAT_PROTEIN_MIN_PER_FPU1 = 180
        /** ...half an hour more for each unit more... */
        const val FAT_PROTEIN_MIN_PER_FPU = 30
        /** ...and at most this long. */
        const val FAT_PROTEIN_MAX_MIN = 300

        /**
         * Minutes a meal's fat and protein take to come in, from when they start: 3 h for one fat-protein unit (100 kcal),
         * half an hour more for each unit more, at most 5 h. The loop's forecast and the length of a meal mode both use it.
         *
         * The usual fat and protein rule (Warsaw method) gives an hour more per unit, up to 8 h, and was used until 13 Nov.
         * For this user it was far too long: a lunch of 6 units (wrap and yoghurt) was over about 4.5 h after eating while
         * the list still had it coming until 8.75 h, and a dinner of 4.5 units was mostly in by 3.5 h. A meal that runs
         * longer still gets insulin: at the tail BG leads.
         *
         * @param timePct the user's setting "Fat and protein time": all of it is this share longer or shorter, 100 = as above
         */
        fun fatProteinMinutes(fpu: Double, timePct: Int = 100): Int =
            ((FAT_PROTEIN_MIN_PER_FPU1 + FAT_PROTEIN_MIN_PER_FPU * (fpu - 1.0).coerceAtLeast(0.0)).coerceAtMost(FAT_PROTEIN_MAX_MIN.toDouble()) *
                timePct.coerceAtLeast(1) / 100.0).toInt()
    }
}
