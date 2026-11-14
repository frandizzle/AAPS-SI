package app.aaps.core.interfaces.smartInsulin

/**
 * Exposes SmartInsulin state to the Overview screen.
 *
 * Lives in core/interfaces so plugins/main (OverviewFragment) can inject it
 * without a direct compile dependency on plugins/aps.
 * Implementation: SmartInsulinPlugin in plugins/aps.
 */
interface SmartInsulinOverview {

    /**
     * Snapshot of all state needed to render the Overview info cell.
     * Computed once per invoke() cycle and cached — safe to read from UI thread.
     */
    data class OverviewState(
        /** "Meal: Lunch 177m" or "Meal: Fasting" */
        val modeLine: String,
        /** "PB2 active: 30m" or null if no PB2 pending */
        val pb2Line: String?,
        /** "PB3 active: 30m" or null if no PB3 pending */
        val pb3Line: String?,
        /** null = full learning active
         *  "limited" = meal mode (only DIA/peak learning)
         *  "off: <reason>" = fully suppressed
         *  "low: guard" = BG is under the low guard; "low: recovering 23m left" = back above it,
         *  recovery window running. Both take the place of the learning state while they last. */
        val learningState: String,
        /** The running meal with a food list, for the card that opens under the meal line; null when there is none. */
        val activeMeal: ActiveMeal? = null
    )

    /**
     * What the running meal's food has done so far, as the loop counts it. Grams of carbs; the fat and protein as grams of
     * carb equivalent at the share of it that gets insulin, as in the loop's "food left". Protein and fat are only apart in
     * what was eaten: once on the way the loop counts them together.
     *
     * @param mealLabel        the meal mode, for example "Dinner"
     * @param fatProteinInG    come in, or faded out with time, so far
     * @param carbsDoneMs      when the carbs should all be in; null when they are
     * @param fatProteinFromMs when the first fat and protein started to count; null when there is none
     * @param fatProteinEndMs  when the last of it fades out
     * @param coveredG         of what is left, food the pre-boluses still waiting cover
     * @param givenU           insulin given for the meal: pre-boluses, added, and the loop's SMBs since it started
     * @param waitingU         pre-bolus insulin still waiting
     * @param carbsComing      carbs still coming in: extra insulin counts a share of the fat and protein left
     */
    data class ActiveMeal(
        val mealLabel: String,
        val startMs: Long,
        val endMs: Long,
        val eatenCarbsG: Double,
        val eatenProteinG: Double,
        val eatenFatG: Double,
        val carbsTotalG: Double,
        val carbsInG: Double,
        val carbsDoneMs: Long?,
        val fatProteinTotalG: Double,
        val fatProteinInG: Double,
        val fatProteinFromMs: Long?,
        val fatProteinEndMs: Long?,
        val coveredG: Double,
        val givenU: Double,
        val loopSmbU: Double,
        val waitingU: Double,
        val plannedU: Double,
        val carbsComing: Boolean,
        val foods: List<ActiveMealFood>
    )

    /**
     * One food of the running meal. [fatProteinFromMs] and [fatProteinEndMs] are when its fat and protein start to count
     * and when they have faded out; null when it has almost none.
     */
    data class ActiveMealFood(
        val name: String,
        val eatenMs: Long,
        val carbsG: Double,
        val proteinG: Double,
        val fatG: Double,
        val fatProteinFromMs: Long?,
        val fatProteinEndMs: Long?
    )

    fun overviewState(): OverviewState
}