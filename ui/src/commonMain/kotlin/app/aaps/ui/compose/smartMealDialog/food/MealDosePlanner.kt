package app.aaps.ui.compose.smartMealDialog.food

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The limits and shares a meal plan is made with. Percentages are 0 to 100.
 *
 * @param carbRatio        grams of carbs one unit covers, from the profile
 * @param factor           learned correction from `MealEstimateCalibration`: how this user's meals compare with estimates
 * @param maxPreBolusU     the most one pre-bolus may be
 * @param maxTotalU        the most all pre-boluses together may be
 */
data class MealPlanSettings(
    val pb1Pct: Int,
    val pb2Pct: Int,
    val pb3Pct: Int,
    val maxPreBolusU: Double,
    val maxTotalU: Double,
    val carbRatio: Double,
    val factor: Double,
    val bolusStep: Double
)

/**
 * A plan for the Smart Meal dialog. It is never given by itself: it only fills the dialog's fields.
 *
 * Pre-bolus 2 and 3 only fire later if BG is above target and not falling, so a meal that turns out
 * smaller than estimated does not get them. The loop covers anything above the plan from real BG.
 *
 * @param carbsU     insulin for the carbs, before shares and caps
 * @param fatProteinU insulin for fat and protein, before shares and caps
 * @param capped     the plan was cut to the max pre-bolus or max total
 */
data class MealPlan(
    val pb1U: Double,
    val pb2U: Double,
    val pb2DelayMin: Int,
    val pb3U: Double,
    val pb3DelayMin: Int,
    val durationMin: Int,
    val duraEnabled: Boolean,
    val pfAfter: Boolean,
    val carbsU: Double,
    val fatProteinU: Double,
    val capped: Boolean
)

/**
 * The meal mode that is running when more food is added to it.
 *
 * @param plannedTotalU  fixed doses this meal has given or still will give
 * @param pb2WaitingU    pre-bolus 2 that still waits to fire, 0 when there is none
 * @param pb3WaitingU    pre-bolus 3 that still waits to fire, 0 when there is none
 * @param minutesLeft    minutes until the mode ends
 * @param iobRoomU       how much IOB may still grow before max IOB
 * @param holdNow        BG is low or falling, so nothing is given now
 */
data class RunningMeal(
    val plannedTotalU: Double,
    val pb2WaitingU: Double,
    val pb3WaitingU: Double,
    val minutesLeft: Int,
    val iobRoomU: Double,
    val holdNow: Boolean
)

/**
 * A plan for food added to a running meal mode. Like [MealPlan] it only fills the dialog's fields.
 *
 * @param minutesLeft  how long the mode should still run, from now
 * @param capped       cut to the max pre-bolus or to what is left of the max total for the whole meal
 * @param heldForBg    the dose now was left out because BG is low or falling
 * @param cutForIob    the dose now was cut so IOB stays within max IOB
 */
data class MealAddition(
    val nowU: Double,
    val pb2AddU: Double,
    val pb3AddU: Double,
    val minutesLeft: Int,
    val duraEnabled: Boolean,
    val pfAfter: Boolean,
    val carbsU: Double,
    val fatProteinU: Double,
    val capped: Boolean,
    val heldForBg: Boolean,
    val cutForIob: Boolean
)

object MealDosePlanner {

    /** Pre-bolus 2, counted from the start of the mode. */
    const val PB2_DELAY_MIN = 30
    /** Pre-bolus 3, counted from when pre-bolus 2 fired, so about 90 min after the start. */
    const val PB3_DELAY_MIN = 60
    /** The fat and protein dose alone, when there is no pre-bolus 2 to count from. */
    const val SLOW_ONLY_DELAY_MIN = 90
    /** From this many fat-protein units, DURA is on and Protein/Fat follows the meal. */
    const val FPU_SLOW_MEAL = 2.0

    fun plan(totals: MealTotals, settings: MealPlanSettings): MealPlan {
        val ratio = settings.carbRatio.takeIf { it > 0.0 } ?: return empty(totals)
        val carbsU = totals.carbsG * settings.factor / ratio
        val fatProteinU = totals.fpu * MealTotals.FPU_CARB_G * settings.factor / ratio

        var pb1 = carbsU * settings.pb1Pct / 100.0
        var pb2 = carbsU * settings.pb2Pct / 100.0
        var pb3 = fatProteinU * settings.pb3Pct / 100.0
        val wanted = pb1 + pb2 + pb3

        // Each pre-bolus within its own cap, then all together within the total. The first one is
        // kept whole the longest: insulin before the rise is what this whole plan is for.
        pb1 = min(pb1, settings.maxPreBolusU)
        pb2 = min(pb2, settings.maxPreBolusU)
        pb3 = min(pb3, settings.maxPreBolusU)
        var left = max(0.0, settings.maxTotalU)
        pb1 = min(pb1, left).also { left -= it }
        pb2 = min(pb2, left).also { left -= it }
        pb3 = min(pb3, left)

        pb1 = floorToStep(pb1, settings.bolusStep)
        pb2 = floorToStep(pb2, settings.bolusStep)
        pb3 = floorToStep(pb3, settings.bolusStep)

        // Pre-bolus 3 counts from pre-bolus 2. Without a pre-bolus 2, the fat and protein dose takes its place.
        var pb2Delay = PB2_DELAY_MIN
        if (pb2 <= 0.0 && pb3 > 0.0) {
            pb2 = pb3; pb3 = 0.0; pb2Delay = SLOW_ONLY_DELAY_MIN
        }

        val slow = totals.fpu >= FPU_SLOW_MEAL
        return MealPlan(
            pb1U = pb1,
            pb2U = pb2,
            pb2DelayMin = pb2Delay,
            pb3U = pb3,
            pb3DelayMin = PB3_DELAY_MIN,
            durationMin = durationFor(totals.fpu),
            duraEnabled = slow,
            pfAfter = slow,
            carbsU = carbsU,
            fatProteinU = fatProteinU,
            capped = pb1 + pb2 + pb3 + settings.bolusStep < wanted
        )
    }

    /**
     * Plans food added to a running meal, for example a dessert. IOB is not taken off: it belongs to the
     * first part of the meal, which the plan only partly covered. IOB and BG are used as limits instead.
     *
     * The share for later goes into pre-bolus 2 and 3 only while they still wait. If they have fired, no
     * new one is made: the loop is still in meal mode and covers the carbs, and for fat and protein the
     * mode runs longer, with DURA and Protein/Fat after it.
     */
    fun planAddition(totals: MealTotals, settings: MealPlanSettings, running: RunningMeal): MealAddition {
        val slow = totals.fpu >= FPU_SLOW_MEAL
        val minutesLeft = max(running.minutesLeft, if (slow) durationFor(totals.fpu) else ADD_MIN_LEFT_MIN)
        val ratio = settings.carbRatio.takeIf { it > 0.0 }
            ?: return MealAddition(0.0, 0.0, 0.0, minutesLeft, false, false, 0.0, 0.0, false, false, false)
        val carbsU = totals.carbsG * settings.factor / ratio
        val fatProteinU = totals.fpu * MealTotals.FPU_CARB_G * settings.factor / ratio

        var now = carbsU * settings.pb1Pct / 100.0
        var pb2 = if (running.pb2WaitingU > 0.0) carbsU * settings.pb2Pct / 100.0 else 0.0
        var pb3 = if (running.pb3WaitingU > 0.0) fatProteinU * settings.pb3Pct / 100.0 else 0.0
        val wanted = now + pb2 + pb3

        // One pre-bolus may not grow past the max pre-bolus, and the whole meal not past the max total.
        now = min(now, settings.maxPreBolusU)
        pb2 = min(pb2, max(0.0, settings.maxPreBolusU - running.pb2WaitingU))
        pb3 = min(pb3, max(0.0, settings.maxPreBolusU - running.pb3WaitingU))
        var left = max(0.0, settings.maxTotalU - running.plannedTotalU)
        now = min(now, left).also { left -= it }
        pb2 = min(pb2, left).also { left -= it }
        pb3 = min(pb3, left)
        val capped = now + pb2 + pb3 + settings.bolusStep < wanted

        val heldForBg = running.holdNow && now > 0.0
        if (heldForBg) now = 0.0
        val cutForIob = now > running.iobRoomU
        if (cutForIob) now = max(0.0, running.iobRoomU)

        return MealAddition(
            nowU = floorToStep(now, settings.bolusStep),
            pb2AddU = floorToStep(pb2, settings.bolusStep),
            pb3AddU = floorToStep(pb3, settings.bolusStep),
            minutesLeft = minutesLeft,
            duraEnabled = slow,
            pfAfter = slow,
            carbsU = carbsU,
            fatProteinU = fatProteinU,
            capped = capped,
            heldForBg = heldForBg,
            cutForIob = cutForIob
        )
    }

    /** Food added to a running meal keeps the mode on for at least this long, so the loop still treats it as a meal. */
    const val ADD_MIN_LEFT_MIN = 120

    /** Warsaw method: the more fat and protein, the longer the meal keeps acting. */
    fun durationFor(fpu: Double): Int = when {
        fpu < 2.0 -> 180
        fpu < 3.0 -> 240
        else      -> 300
    }

    private fun empty(totals: MealTotals) =
        MealPlan(0.0, 0.0, PB2_DELAY_MIN, 0.0, PB3_DELAY_MIN, durationFor(totals.fpu), false, false, 0.0, 0.0, false)

    private fun floorToStep(u: Double, step: Double): Double =
        if (step <= 0.0) u else floor(u / step + 1e-9) * step
}
