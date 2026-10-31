package app.aaps.ui.compose.smartMealDialog.food

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The limits and shares a meal plan is made with. Percentages are 0 to 100.
 *
 * @param carbPct          how much of the carb insulin pre-bolus 1 gives
 * @param fatProteinPct    how much of the fat and protein insulin (Warsaw method) pre-bolus 2 and 3 give
 *                         together. The Warsaw method often counts high, so this is usually below 100.
 * @param pb3SharePct      on a heavy meal, the part of that fat and protein dose that pre-bolus 3 gives
 * @param carbRatio        grams of carbs one unit covers, from the profile
 * @param factor           learned correction for the carbs: from `MealEstimateCalibration` (how this user's meals
 *                         compare with estimates), or what a saved meal learned about itself
 * @param waveFactor       the same for the fat and protein wave (pre-bolus 2); null uses [factor]
 * @param tailFactor       the same for the tail of a heavy meal (pre-bolus 3); null uses the wave factor
 * @param pb2DelayMin      pre-bolus 2, counted from the start of the mode
 * @param pb3DelayMin      pre-bolus 3, counted from when pre-bolus 2 fired
 * @param maxPreBolusU     the most one pre-bolus may be
 * @param maxTotalU        the most all pre-boluses together may be
 */
data class MealPlanSettings(
    val carbPct: Int,
    val fatProteinPct: Int,
    val pb3SharePct: Int,
    val maxPreBolusU: Double,
    val maxTotalU: Double,
    val carbRatio: Double,
    val factor: Double,
    val bolusStep: Double,
    val waveFactor: Double? = null,
    val tailFactor: Double? = null,
    val pb2DelayMin: Int = MealDosePlanner.PB2_DELAY_MIN,
    val pb3DelayMin: Int = MealDosePlanner.PB3_DELAY_MIN
)

/**
 * A plan for the Smart Meal dialog. It is never given by itself: it only fills the dialog's fields.
 *
 * Each pre-bolus has one job, as when a meal is bolused by hand: pre-bolus 1 covers the carbs, pre-bolus 2
 * keeps IOB up for the fat and protein wave, and on a heavy meal pre-bolus 3 covers what is left of it.
 * Pre-bolus 2 and 3 only fire later if BG is above target and not falling, so a meal that turns out
 * smaller than estimated does not get them. The loop covers anything above the plan from real BG.
 *
 * DURA and Protein/Fat after the meal are not turned on: the pre-boluses and a mode long enough for the fat
 * and protein ([MealDosePlanner.durationFor]) cover the meal, and both on top would cover the tail twice.
 * The user can still switch them on.
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
    val carbsU: Double,
    val fatProteinU: Double,
    val capped: Boolean,
    val heldForBg: Boolean,
    val cutForIob: Boolean
)

object MealDosePlanner {

    /**
     * Pre-bolus 2, the fat and protein wave, counted from the start of the mode. Fast insulin works fully after
     * about an hour, so it is working as the wave starts, about 2 h in, without stacking on pre-bolus 1's peak.
     */
    const val PB2_DELAY_MIN = 60
    /** Pre-bolus 3, what is left of the wave, counted from when pre-bolus 2 fired, so about 2.5 h after the start. */
    const val PB3_DELAY_MIN = 90
    /** Below this many fat-protein units there is no pre-bolus 2: the loop covers so little by itself. */
    const val FPU_PB2 = 1.0
    /** Food added with this many fat-protein units keeps the mode on as long as a meal of it would. */
    const val FPU_SLOW_MEAL = 2.0
    /** From this many fat-protein units the meal is heavy, and pre-bolus 3 gives part of the fat and protein dose. */
    const val FPU_HEAVY_MEAL = 3.0

    fun plan(totals: MealTotals, settings: MealPlanSettings): MealPlan {
        val ratio = settings.carbRatio.takeIf { it > 0.0 } ?: return empty(totals)
        val carbsU = totals.carbsG * settings.factor / ratio
        val (fatProteinU, fp2, fp3) = fatProteinDoses(totals.fpu, ratio, settings)

        var pb1 = carbsU * settings.carbPct / 100.0
        var pb2 = fp2
        var pb3 = fp3
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

        // Pre-bolus 3 counts from pre-bolus 2, so without a pre-bolus 2 it would never fire.
        if (pb2 <= 0.0) pb3 = 0.0

        return MealPlan(
            pb1U = pb1,
            pb2U = pb2,
            pb2DelayMin = settings.pb2DelayMin,
            pb3U = pb3,
            pb3DelayMin = settings.pb3DelayMin,
            durationMin = durationFor(totals.fpu),
            carbsU = carbsU,
            fatProteinU = fatProteinU,
            capped = pb1 + pb2 + pb3 + settings.bolusStep < wanted
        )
    }

    /**
     * Plans food added to a running meal, for example a dessert. IOB is not taken off: it belongs to the
     * first part of the meal, which the plan only partly covered. IOB and BG are used as limits instead.
     *
     * The carbs are given now. The fat and protein dose goes into pre-bolus 2 and 3 only while they still
     * wait, split as for a new meal when both wait. If they have fired, no new one is made: for fat and
     * protein the mode runs longer, as long as a new meal with that much fat and protein would.
     */
    fun planAddition(totals: MealTotals, settings: MealPlanSettings, running: RunningMeal): MealAddition {
        val slow = totals.fpu >= FPU_SLOW_MEAL
        val minutesLeft = max(running.minutesLeft, if (slow) durationFor(totals.fpu) else ADD_MIN_LEFT_MIN)
        val ratio = settings.carbRatio.takeIf { it > 0.0 }
            ?: return MealAddition(0.0, 0.0, 0.0, minutesLeft, 0.0, 0.0, false, false, false)
        val carbsU = totals.carbsG * settings.factor / ratio
        val (fatProteinU, fp2, fp3) = fatProteinDoses(totals.fpu, ratio, settings)

        var now = carbsU * settings.carbPct / 100.0
        val pb2Waits = running.pb2WaitingU > 0.0
        val pb3Waits = running.pb3WaitingU > 0.0
        // A pre-bolus that has fired cannot take its part, so the one still waiting takes all of it.
        var pb2 = when {
            pb2Waits && pb3Waits -> fp2
            pb2Waits             -> fp2 + fp3
            else                 -> 0.0
        }
        var pb3 = when {
            pb3Waits && pb2Waits -> fp3
            pb3Waits             -> fp2 + fp3
            else                 -> 0.0
        }
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
            carbsU = carbsU,
            fatProteinU = fatProteinU,
            capped = capped,
            heldForBg = heldForBg,
            cutForIob = cutForIob
        )
    }

    /**
     * The insulin for the fat and protein (before the share given up front), and the doses for pre-bolus 2 and
     * pre-bolus 3, each with its own learned factor. A light meal gets no pre-bolus 2, a heavy one has the dose
     * split between the two.
     */
    private fun fatProteinDoses(fpu: Double, ratio: Double, settings: MealPlanSettings): Triple<Double, Double, Double> {
        val (waveG, tailG) = fatProteinGrams(fpu, settings.pb3SharePct)
        val wave = settings.waveFactor ?: settings.factor
        val tail = settings.tailFactor ?: wave
        val waveU = waveG * wave / ratio
        val tailU = tailG * tail / ratio
        if (fpu < FPU_PB2) return Triple(waveU + tailU, 0.0, 0.0)
        val share = settings.fatProteinPct / 100.0
        return Triple(waveU + tailU, waveU * share, tailU * share)
    }

    /**
     * The carb equivalent of the fat and protein, in grams, that pre-bolus 2 and pre-bolus 3 cover. Only a heavy
     * meal has a part for pre-bolus 3. The meal learner is told the same split, so it judges each part against
     * what that pre-bolus was for.
     */
    fun fatProteinGrams(fpu: Double, pb3SharePct: Int): Pair<Double, Double> {
        val grams = fpu * MealTotals.FPU_CARB_G
        if (fpu < FPU_HEAVY_MEAL) return grams to 0.0
        val tail = grams * pb3SharePct / 100.0
        return grams - tail to tail
    }

    /**
     * Pre-bolus 2 and 3 timed from when this meal's fat and protein really come in, so the insulin is at its
     * strongest then: pre-bolus 2 at the middle of it, or on a heavy meal pre-bolus 2 at the first quarter and
     * pre-bolus 3 at the third. Null when the meal has not learned its timing.
     *
     * Pre-bolus 2 is never earlier than [MIN_LEARNED_PB2_MIN], so it does not land on top of pre-bolus 1 at its
     * strongest; both stay within the dialog's limits.
     *
     * @param insulinPeakMin when the insulin works hardest after a bolus, from the profile
     * @param heavy          the meal has a pre-bolus 3
     * @return pre-bolus 2 minutes from the start, and pre-bolus 3 minutes after pre-bolus 2
     */
    fun learnedDelays(quarterMin: Int?, halfMin: Int?, threeQuarterMin: Int?, insulinPeakMin: Int, heavy: Boolean): Pair<Int, Int>? {
        fun round5(m: Int) = (m + 2) / 5 * 5
        return if (heavy) {
            if (quarterMin == null || threeQuarterMin == null) return null
            val pb2 = round5(quarterMin - insulinPeakMin).coerceIn(MIN_LEARNED_PB2_MIN, MAX_DELAY_MIN)
            val pb3 = round5(threeQuarterMin - insulinPeakMin - pb2).coerceIn(MIN_PB3_GAP_MIN, MAX_DELAY_MIN)
            pb2 to pb3
        } else {
            if (halfMin == null) return null
            round5(halfMin - insulinPeakMin).coerceIn(MIN_LEARNED_PB2_MIN, MAX_DELAY_MIN) to PB3_DELAY_MIN
        }
    }

    /**
     * How long to wait after pre-bolus 1 before eating, when BG usually starts to rise sooner than fast insulin
     * starts to work ([INSULIN_START_MIN]). Null when no wait is needed or the rise is not known.
     */
    fun suggestedWait(riseMin: Int?): Int? {
        riseMin ?: return null
        if (riseMin >= INSULIN_START_MIN) return null
        return ((INSULIN_START_MIN - riseMin + 4) / 5 * 5).coerceIn(5, MAX_WAIT_MIN)
    }

    const val MIN_LEARNED_PB2_MIN = 45
    const val MIN_PB3_GAP_MIN = 30
    const val MAX_DELAY_MIN = 120
    /** Fast insulin starts to lower BG about this long after a bolus. */
    const val INSULIN_START_MIN = 20
    const val MAX_WAIT_MIN = 20

    /** Food added to a running meal keeps the mode on for at least this long, so the loop still treats it as a meal. */
    const val ADD_MIN_LEFT_MIN = 120

    /**
     * Warsaw method: the more fat and protein, the longer the meal keeps acting. The mode has to cover all
     * of it, since DURA and Protein/Fat are not turned on after it. Kept to 6 h even for the heaviest meals:
     * after that the loop's own fasting settings handle what is left, and a long meal mode with a strong
     * meal ISF is a risk of its own.
     */
    fun durationFor(fpu: Double): Int = when {
        fpu < 2.0 -> 180
        fpu < 3.0 -> 240
        fpu < 4.0 -> 300
        else      -> 360
    }

    private fun empty(totals: MealTotals) =
        MealPlan(0.0, 0.0, PB2_DELAY_MIN, 0.0, PB3_DELAY_MIN, durationFor(totals.fpu), 0.0, 0.0, false)

    private fun floorToStep(u: Double, step: Double): Double =
        if (step <= 0.0) u else floor(u / step + 1e-9) * step
}
