package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A meal's food that has not come in yet, from its estimate, as the loop's forecast sees it: like carbs on board,
 * but split by how fast it comes in. Grams of carb equivalent.
 *
 * @param carbsUntilMs      the carbs should have come in by this time
 * @param fatProteinUntilMs the fat and protein still to come are in by this time, at the meal's own rate
 * @param carbRatio         grams per unit, to turn the food into BG
 * @param coveredG          food the waiting pre-boluses will cover, already taken off; for the reason string
 * @param fatProteinFromMs  the fat and protein start to come in at this time; before it, the forecast leaves them out
 */
data class FoodToCome(
    val carbsG: Double,
    val carbsUntilMs: Long,
    val fatProteinG: Double,
    val fatProteinUntilMs: Long,
    val carbRatio: Double,
    val coveredG: Double = 0.0,
    val fatProteinFromMs: Long = 0L
) {

    val totalG: Double get() = carbsG + fatProteinG
}

/**
 * The food of a meal with an estimate, put into the loop's forecast, so the loop doses for food still coming
 * instead of predicting a low from the insulin on board alone. See `DetermineBasalSmartInsulin`.
 *
 * The carbs are counted as coming in evenly until [FoodToCome.carbsUntilMs] (by default [CARBS_MIN] after they were
 * eaten, sooner for fast food and later for slow food). Carbs that come in slower than that keep coming at the meal's
 * own rate, so the time is stretched, as Loop does, up to [CARBS_STRETCH] times the meal's time: the loop then doses
 * for them as they come instead of expecting all that is left at once. The fat and protein come in at one steady rate, the meal's
 * whole fat and protein over [fatProteinMinutes]: they raise BG slowly and evenly, so BG holds a little higher for
 * hours rather than spiking. Fat and protein that come in later than expected stay at that rate and finish later;
 * they are never squeezed into the time left. Fat and protein only start to raise BG
 * [FAT_PROTEIN_START_MIN] after they are eaten, so until then they are left out of the forecast: the loop does
 * not give insulin for them at the start of the meal. Food the waiting pre-boluses will cover is
 * taken off first, so the loop does not give insulin that a pre-bolus is about to give. Food that does not show
 * up fades out with the time left of the meal, so a smaller meal than estimated is not dosed for long.
 */
object MealFoodForecast {

    /** Minutes carbs of normal speed take to come in, from when they are eaten. A meal's own speed may change it. */
    const val CARBS_MIN = MealEstimateCalibration.DEFAULT_CARBS_MIN
    /** Carbs that come in slower than expected may take at most this many times the meal's carb time, as in Loop. */
    const val CARBS_STRETCH = 1.5
    /** Minutes after they are eaten that fat and protein start to raise BG. */
    const val FAT_PROTEIN_START_MIN = 45
    /** The food still to come may be at most this many times the meal's share of the time left. */
    const val FADE_SLACK = 1.5
    /** Less food than this is not worth forecasting. */
    const val MIN_FOOD_G = 2.0
    /** Fat and protein of one fat-protein unit (100 kcal) take this long to come in, from when they start... */
    const val FAT_PROTEIN_MIN_PER_FPU1 = 180
    /** ...one more hour for each unit more... */
    const val FAT_PROTEIN_MIN_PER_FPU = 60
    /** ...and at most this long, as for the largest meals of the usual fat and protein rule. */
    const val FAT_PROTEIN_MAX_MIN = 480
    /** Food is spread over at least this many 5-min steps: carbs 30 min, fat and protein 1 hour. */
    private const val MIN_CARB_STEPS = 6
    private const val MIN_FAT_PROTEIN_STEPS = 12
    private const val STEP_MS = 5 * 60_000L

    /**
     * The food still to come for the forecast, or null when there is too little.
     *
     * @param restU the rest of pre-bolus 1 still waiting: it covers carbs
     * @param pbU   pre-bolus 2 and 3 still waiting: they cover fat and protein. All the carbs count: the share of them
     *              pre-bolus 1 holds back (Planned meal: carbs in pre-bolus 1) is only room for timing at the start, and
     *              the loop gives the rest as the food comes in.
     * @param fatProteinShare the share of the fat and protein's carb equivalent that needs insulin, as Fill in plans it
     *                        (Planned meal: fat and protein insulin, 70 % by default)
     */
    fun foodToCome(still: StillToCome, nowMs: Long, restU: Double, pbU: Double, carbRatio: Double, fatProteinShare: Double = 1.0): FoodToCome? {
        if (carbRatio <= 0.0) return null
        val share = fatProteinShare.coerceIn(0.0, 1.0)
        var carbs = still.carbsG
        var fatProtein = still.fatProteinG * share
        // Each waiting pre-bolus covers its own part first; what is left of it covers the other part.
        var restG = restU.coerceAtLeast(0.0) * carbRatio
        var pbG = pbU.coerceAtLeast(0.0) * carbRatio
        val c1 = min(carbs, restG).also { carbs -= it; restG -= it }
        val f1 = min(fatProtein, pbG).also { fatProtein -= it; pbG -= it }
        val f2 = min(fatProtein, restG).also { fatProtein -= it }
        val c2 = min(carbs, pbG).also { carbs -= it }

        // Food that has not shown up by now fades with the time left of the meal.
        val spanMs = (still.windowEndMs - still.modeStartMs).coerceAtLeast(1L)
        val leftMs = (still.windowEndMs - nowMs).coerceAtLeast(0L)
        val maxG = still.mealG * leftMs / spanMs * FADE_SLACK
        val total = carbs + fatProtein
        if (total > maxG) {
            val share = if (total > 0.0) maxG / total else 0.0
            carbs *= share
            fatProtein *= share
        }
        if (carbs + fatProtein < MIN_FOOD_G) return null
        return FoodToCome(carbs, carbsUntil(still, nowMs), fatProtein, fatProteinUntil(still, share, fatProtein, nowMs), carbRatio, c1 + f1 + f2 + c2, still.fatProteinFromMs)
    }

    /**
     * When the carbs still to come are in: at the meal's own rate (all its carbs over [StillToCome.carbsMin]), but not
     * sooner than planned, and not later than [CARBS_STRETCH] times the meal's carb time. Without a rate, as planned.
     */
    internal fun carbsUntil(still: StillToCome, nowMs: Long): Long {
        if (still.carbsMin <= 0 || still.carbsMealG <= 0.0 || still.carbsG <= 0.0) return still.carbsUntilMs
        val carbMs = still.carbsMin * 60_000L
        val atRate = nowMs + (still.carbsG / still.carbsMealG * carbMs).toLong()
        val latest = still.carbsUntilMs + ((CARBS_STRETCH - 1.0) * carbMs).toLong()
        return min(latest, max(still.carbsUntilMs, atRate))
    }

    /**
     * Minutes a meal's fat and protein take to come in, from when they start: 3 h for one fat-protein unit (100 kcal),
     * an hour more for each unit more, at most 8 h. Close to the usual fat and protein rule (Warsaw method), without its
     * jumps.
     */
    fun fatProteinMinutes(fpu: Double): Int =
        (FAT_PROTEIN_MIN_PER_FPU1 + FAT_PROTEIN_MIN_PER_FPU * (fpu - 1.0).coerceAtLeast(0.0)).coerceAtMost(FAT_PROTEIN_MAX_MIN.toDouble()).toInt()

    /**
     * When the [leftG] of fat and protein still to come are in, at the meal's steady rate: all its fat and protein over
     * [StillToCome.fatProteinMin]. Without a rate (no duration known) the end of the meal's window, as before.
     */
    private fun fatProteinUntil(still: StillToCome, share: Double, leftG: Double, nowMs: Long): Long {
        val perStepG = still.fatProteinMealG * share / (still.fatProteinMin / 5.0)
        if (still.fatProteinMin <= 0 || perStepG <= 0.0 || leftG <= 0.0) return still.windowEndMs
        return max(nowMs, still.fatProteinFromMs) + ceil(leftG / perStepG).toLong() * STEP_MS
    }

    /**
     * The BG rise of several meals' food at each 5-min step ahead, mg/dL: [steps] of each, added up. The rise BG
     * already shows is taken off once, from the first food in [foods] first: put the oldest first, its food came first.
     */
    fun steps(foods: List<FoodToCome>, nowMs: Long, isfMgdl: Double, ciAheadMgdl: Double, count: Int): List<Double> {
        val total = DoubleArray(max(count, 0))
        var seenMgdl = ciAheadMgdl.coerceAtLeast(0.0)
        foods.forEach { f ->
            steps(f, nowMs, isfMgdl, seenMgdl, count).forEachIndexed { i, v -> total[i] += v }
            val csf = if (f.carbRatio > 0.0) isfMgdl / f.carbRatio else 0.0
            if (csf > 0.0) seenMgdl = (seenMgdl / csf - f.totalG).coerceAtLeast(0.0) * csf
        }
        return total.toList()
    }

    /**
     * The BG rise [food] adds at each 5-min step ahead, mg/dL. The rise BG already shows is in the forecast on
     * its own, fading out ([ciAheadMgdl] is all of it still to come), so that much food is taken off first,
     * carbs before fat and protein, and the same grams are not counted twice. Fat and protein that have not
     * started yet ([FoodToCome.fatProteinFromMs]) add nothing.
     *
     * @param isfMgdl the same ISF the forecast uses for the insulin, so food and insulin are weighed alike
     */
    fun steps(food: FoodToCome, nowMs: Long, isfMgdl: Double, ciAheadMgdl: Double, count: Int): List<Double> {
        val csf = if (food.carbRatio > 0.0) isfMgdl / food.carbRatio else 0.0
        if (csf <= 0.0 || count <= 0) return List(max(count, 0)) { 0.0 }
        var seenG = ciAheadMgdl.coerceAtLeast(0.0) / csf
        val carbs = (food.carbsG - seenG).coerceAtLeast(0.0)
        seenG = (seenG - food.carbsG).coerceAtLeast(0.0)
        val fatProtein = if (nowMs < food.fatProteinFromMs) 0.0 else (food.fatProteinG - seenG).coerceAtLeast(0.0)
        val carbSteps = max(MIN_CARB_STEPS, ceil((food.carbsUntilMs - nowMs).toDouble() / STEP_MS).toInt())
        val fatProteinSteps = max(MIN_FAT_PROTEIN_STEPS, ceil((food.fatProteinUntilMs - nowMs).toDouble() / STEP_MS).toInt())
        return List(count) { i ->
            val g = (if (i < carbSteps) carbs / carbSteps else 0.0) + (if (i < fatProteinSteps) fatProtein / fatProteinSteps else 0.0)
            g * csf
        }
    }
}
