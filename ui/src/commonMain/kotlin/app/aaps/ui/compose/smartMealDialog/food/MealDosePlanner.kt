package app.aaps.ui.compose.smartMealDialog.food

import app.aaps.core.interfaces.smartInsulin.CarbPart
import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import kotlin.math.ceil
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
 * @param foodMatch        the loop gives part of the fat and protein insulin as the food comes in: pre-bolus 2 is
 *                         [MealDosePlanner.FOOD_MATCH_PB2_SHARE] of the dose, and there is no pre-bolus 3
 * @param fatProteinPreBoluses false for a meal the user gives no pre-bolus 2 (a morning coffee): its fat and protein are
 *                         left to the loop, and they do not make the mode longer
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
    val pb3DelayMin: Int = MealDosePlanner.PB3_DELAY_MIN,
    val fatProteinPreBoluses: Boolean = true,
    val foodMatch: Boolean = false
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
 * @param pb1RestU   the part of pre-bolus 1 above the max pre-bolus, given 15 to 45 min in only if BG rises; 0 when none.
 *                   See `MealOverrideManager.PB1_REST_FROM_MIN`.
 * @param cutForBalance pre-bolus 1 was cut because the insulin on board already covers part of the food, see [InsulinBalance]
 * @param withPb2     the same plan with pre-bolus 2, for a meal the user gives none
 *                    ([MealPlanSettings.fatProteinPreBoluses]) but that would get one; switching pre-bolus 2 on fills it in
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
    val capped: Boolean,
    val pb1RestU: Double = 0.0,
    val cutForBalance: Boolean = false,
    val withPb2: MealPlan? = null
) {

    /** The meal would get a pre-bolus 2, but the user gives it none. */
    val pb2LeftOut: Boolean get() = withPb2 != null
}

/**
 * The meal mode that is running when more food is added to it.
 *
 * @param plannedTotalU  fixed doses this meal has given or still will give
 * @param pb2WaitingU    pre-bolus 2 that still waits to fire, 0 when there is none
 * @param pb3WaitingU    pre-bolus 3 that still waits to fire, 0 when there is none
 * @param minutesLeft    minutes until the mode ends
 * @param iobRoomU       how much IOB may still grow before max IOB
 * @param holdNow        BG is low or falling, so nothing is given now
 * @param balance        what a bolus calculator weighs, to check the dose now against; null when the running meal's
 *                       food is not known (it has no estimate), and only the limits above apply
 */
data class RunningMeal(
    val plannedTotalU: Double,
    val pb2WaitingU: Double,
    val pb3WaitingU: Double,
    val minutesLeft: Int,
    val iobRoomU: Double,
    val holdNow: Boolean,
    val balance: InsulinBalance? = null
)

/**
 * BG, insulin and food on board, for the dose now, like Loop's bolus calculator: the dose brings the BG expected
 * once all of it has acted to target. BG values in mg/dL; the ISF is the profile's, not a meal mode's.
 *
 * @param trendMgdl        how far BG moves in the next 15 min if its trend goes on
 * @param iobU             insulin on board
 * @param waitingU         pre-boluses of the running meal still to come: insulin for its food that is not on board yet
 * @param foodStillToComeG food eaten before that has not come in yet, as carb equivalent in grams
 */
data class InsulinBalance(
    val bgMgdl: Double,
    val trendMgdl: Double,
    val targetMgdl: Double,
    val isfMgdl: Double,
    val iobU: Double,
    val waitingU: Double,
    val foodStillToComeG: Double
)

/**
 * A plan for food added to a running meal mode. Like [MealPlan] it only fills the dialog's fields.
 *
 * @param minutesLeft  how long the mode should still run, from now
 * @param capped       cut to the max pre-bolus or to what is left of the max total for the whole meal
 * @param heldForBg    the dose now was left out because BG is low or falling
 * @param cutForIob    the dose now was cut so IOB stays within max IOB
 * @param cutForBalance the dose now was cut because the insulin on board already covers part of the food, see [InsulinBalance]
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
    val cutForIob: Boolean,
    val cutForBalance: Boolean = false
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
    /** From this many fat-protein units the meal is heavy, and pre-bolus 3 gives part of the fat and protein dose. */
    const val FPU_HEAVY_MEAL = 3.0
    /** With the loop matching insulin to the food, pre-bolus 2 gives this part of the fat and protein dose. */
    const val FOOD_MATCH_PB2_SHARE = 0.5

    /**
     * @param balance BG, insulin and earlier food now, to cut pre-bolus 1 when the insulin on board already covers part of
     *                the food. It only cuts, never adds: a BG above target is left to the loop. Null: no cut.
     * @param carbParts the carbs by speed, for how long the mode lasts; empty: all at normal speed
     */
    fun plan(totals: MealTotals, settings: MealPlanSettings, balance: InsulinBalance? = null, carbParts: List<CarbPart> = emptyList()): MealPlan {
        val ratio = settings.carbRatio.takeIf { it > 0.0 } ?: return empty(totals)
        val carbsU = totals.carbsG * settings.factor / ratio
        val (fatProteinU, fp2, fp3) = fatProteinDoses(totals.fpu, ratio, settings)
        val withPb2 = if (settings.fatProteinPreBoluses) null
        else plan(totals, settings.copy(fatProteinPreBoluses = true), balance, carbParts).takeIf { it.pb2U > 0.0 }

        var pb1 = carbsU * settings.carbPct / 100.0
        var pb2 = if (settings.fatProteinPreBoluses) fp2 else 0.0
        var pb3 = if (settings.fatProteinPreBoluses) fp3 else 0.0
        val wanted = pb1 + pb2 + pb3

        // Each pre-bolus within its own cap, then all together within the total. The first one is
        // kept whole the longest: insulin before the rise is what this whole plan is for. What pre-bolus 1
        // wants above the max pre-bolus is its rest, given a little later once BG shows the food coming in.
        var rest = min(max(0.0, pb1 - settings.maxPreBolusU), settings.maxPreBolusU)
        pb1 = min(pb1, settings.maxPreBolusU)
        pb2 = min(pb2, settings.maxPreBolusU)
        pb3 = min(pb3, settings.maxPreBolusU)
        var left = max(0.0, settings.maxTotalU)
        pb1 = min(pb1, left).also { left -= it }
        rest = min(rest, left).also { left -= it }
        pb2 = min(pb2, left).also { left -= it }
        pb3 = min(pb3, left)
        // Checked before rounding: rounding each pre-bolus down to the pump step is not a cut.
        val capped = pb1 + rest + pb2 + pb3 + settings.bolusStep < wanted

        // As a bolus calculator, for the carbs given now: the rest goes first, it would come later anyway.
        // Pre-bolus 2 and 3 are not cut, they check BG themselves before they fire.
        val balanceU = balance?.let { neededNow(it, totals.carbsG * settings.factor, ratio) }
        val cutForBalance = balanceU != null && pb1 + rest > 0.0 && balanceU < pb1 + rest - settings.bolusStep / 2
        if (cutForBalance) {
            val keep = max(0.0, balanceU!!)
            rest = min(rest, max(0.0, keep - pb1))
            pb1 = min(pb1, keep)
        }

        pb1 = floorToStep(pb1, settings.bolusStep)
        rest = floorToStep(rest, settings.bolusStep)
        pb2 = floorToStep(pb2, settings.bolusStep)
        pb3 = floorToStep(pb3, settings.bolusStep)
        // A small rest is left to the loop.
        if (rest < MealOverrideManager.PB1_REST_MIN_U) rest = 0.0

        // Pre-bolus 3 counts from pre-bolus 2, so without a pre-bolus 2 it would never fire.
        if (pb2 <= 0.0) pb3 = 0.0

        return MealPlan(
            pb1U = pb1,
            pb2U = pb2,
            pb2DelayMin = settings.pb2DelayMin,
            pb3U = pb3,
            pb3DelayMin = settings.pb3DelayMin,
            durationMin = durationFor(
                totals, carbParts, withFatProtein = settings.fatProteinPreBoluses,
                lastPreBolusMin = when {
                    pb3 > 0.0 -> settings.pb2DelayMin + settings.pb3DelayMin
                    pb2 > 0.0 -> settings.pb2DelayMin
                    else      -> 0
                }
            ),
            carbsU = carbsU,
            fatProteinU = fatProteinU,
            capped = capped,
            pb1RestU = rest,
            cutForBalance = cutForBalance,
            withPb2 = withPb2
        )
    }

    /**
     * Plans food added to a running meal, for example a dessert. IOB is not taken off: it belongs to the
     * first part of the meal, which the plan only partly covered. IOB and BG are used as limits instead.
     *
     * The carbs are given now. The fat and protein dose goes into pre-bolus 2 and 3 only while they still
     * wait, split as for a new meal when both wait. If they have fired, no new one is made. The mode runs at least
     * until the added food is in, from now, as for a new meal of it; it never gets shorter.
     *
     * @param carbParts the added carbs by speed; empty: all at normal speed
     */
    fun planAddition(totals: MealTotals, settings: MealPlanSettings, running: RunningMeal, carbParts: List<CarbPart> = emptyList()): MealAddition {
        val minutesLeft = max(running.minutesLeft, durationFor(totals, carbParts))
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

        // As a bolus calculator: insulin on board and still to come, against BG and all the food still to come.
        val balanceU = running.balance?.let { neededNow(it, totals.carbsG * settings.factor, ratio) }
        val cutForBalance = balanceU != null && now > 0.0 && balanceU < now - settings.bolusStep / 2
        if (cutForBalance) now = max(0.0, balanceU!!)

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
            cutForIob = cutForIob,
            cutForBalance = cutForBalance
        )
    }

    /**
     * The insulin needed now so BG ends at target once everything has acted: BG with its trend above target,
     * plus the food still to come and the new carbs, less the insulin on board and still to come. Can be
     * negative: there is already more insulin than the food needs.
     *
     * @param newCarbsG the carbs of the food added now, with the learned factor
     */
    fun neededNow(b: InsulinBalance, newCarbsG: Double, carbRatio: Double): Double {
        if (b.isfMgdl <= 0.0 || carbRatio <= 0.0) return Double.MAX_VALUE
        return (b.bgMgdl + b.trendMgdl - b.targetMgdl) / b.isfMgdl - b.iobU - b.waitingU + (b.foodStillToComeG + newCarbsG) / carbRatio
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
        if (settings.foodMatch) return Triple(waveU + tailU, (waveU + tailU) * share * FOOD_MATCH_PB2_SHARE, 0.0)
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

    /**
     * How long to wait after pre-bolus 1 before eating a fast meal (its carbs come in within [FAST_MEAL_MAX_MIN]):
     * fast carbs raise BG sooner than fast insulin starts to work. No wait with BG under target or falling, where
     * eating at once is the safe choice, or without a BG reading.
     *
     * @param trendMgdl how far BG moves in the next 15 min if its trend goes on
     */
    fun fastCarbsWait(carbsMin: Int, bgMgdl: Double?, targetMgdl: Double, trendMgdl: Double): Int? =
        if (carbsMin <= FAST_MEAL_MAX_MIN && bgMgdl != null && bgMgdl >= targetMgdl && trendMgdl >= 0.0) FAST_CARBS_WAIT_MIN else null

    /** A meal whose carbs come in within this many minutes is fast. */
    const val FAST_MEAL_MAX_MIN = 90
    const val FAST_CARBS_WAIT_MIN = 15

    const val MIN_LEARNED_PB2_MIN = 45
    const val MIN_PB3_GAP_MIN = 30
    const val MAX_DELAY_MIN = 120
    /** Fast insulin starts to lower BG about this long after a bolus. */
    const val INSULIN_START_MIN = 20
    const val MAX_WAIT_MIN = 20

    /** A meal mode lasts at least this long... */
    const val MIN_MODE_MIN = 90
    /** ...and at most this long: after that the loop's own fasting settings handle what is left. */
    const val MAX_MODE_MIN = MealOverrideManager.MAX_MODE_MIN
    /** It lasts at least this long after the last pre-bolus, so that pre-bolus has time to act within the meal. */
    const val AFTER_LAST_PRE_BOLUS_MIN = 60

    /**
     * How long the meal mode lasts: as long as its food takes to come in, so a fast snack is short and a big fatty meal
     * long. The mode has to cover all of it, since DURA and Protein/Fat are not turned on after it, and the loop only
     * sees the food while the mode runs.
     *
     * - Carbs: the slowest part's time, times [MealEstimateCalibration.CARBS_STRETCH], as late carbs may take that long.
     * - Fat and protein: from [FPU_PB2] fat-protein units, they start [MealEstimateCalibration.FAT_PROTEIN_START_MIN]
     *   after eating and then take [MealEstimateCalibration.fatProteinMinutes]. Less is left to the loop, as for
     *   pre-bolus 2, and so is all of it for a meal the user gives no pre-bolus 2 ([withFatProtein] false).
     * - At least [AFTER_LAST_PRE_BOLUS_MIN] after the last pre-bolus.
     *
     * Rounded up to 15 min, from [MIN_MODE_MIN] to [MAX_MODE_MIN].
     *
     * @param carbParts the carbs by speed; empty: all of [MealTotals.carbsG] at normal speed
     * @param lastPreBolusMin when the last pre-bolus fires, minutes from the start; 0 for none
     */
    fun durationFor(totals: MealTotals, carbParts: List<CarbPart>, lastPreBolusMin: Int = 0, withFatProtein: Boolean = true): Int {
        val parts = carbParts.ifEmpty { listOf(CarbPart(totals.carbsG, MealEstimateCalibration.DEFAULT_CARBS_MIN)) }.filter { it.carbsG > 0.0 }
        val carbsMin = parts.maxOfOrNull { ceil(it.minutes * MealEstimateCalibration.CARBS_STRETCH).toInt() } ?: 0
        val fatProteinMin = if (withFatProtein && totals.fpu >= FPU_PB2) MealEstimateCalibration.FAT_PROTEIN_START_MIN + MealEstimateCalibration.fatProteinMinutes(totals.fpu) else 0
        val preBolusMin = if (lastPreBolusMin > 0) lastPreBolusMin + AFTER_LAST_PRE_BOLUS_MIN else 0
        val min = maxOf(MIN_MODE_MIN, carbsMin, fatProteinMin, preBolusMin)
        return ((min + 14) / 15 * 15).coerceAtMost(MAX_MODE_MIN)
    }

    private fun empty(totals: MealTotals) =
        MealPlan(0.0, 0.0, PB2_DELAY_MIN, 0.0, PB3_DELAY_MIN, durationFor(totals, emptyList()), 0.0, 0.0, false)

    private fun floorToStep(u: Double, step: Double): Double =
        if (step <= 0.0) u else floor(u / step + 1e-9) * step
}
