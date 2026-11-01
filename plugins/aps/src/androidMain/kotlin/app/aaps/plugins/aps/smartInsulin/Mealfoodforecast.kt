package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A meal's food that has not come in yet, from its estimate, as the loop's forecast sees it: like carbs on board,
 * but split by how fast it comes in. Grams of carb equivalent.
 *
 * @param carbsUntilMs      the carbs should have come in by this time
 * @param fatProteinUntilMs the fat and protein should have come in by this time: the end of the meal's window
 * @param carbRatio         grams per unit, to turn the food into BG
 * @param coveredG          food the waiting pre-boluses will cover, already taken off; for the reason string
 * @param fatProteinFromMs  the fat and protein start to come in at this time; before it, the forecast leaves them out
 * @param heldBackCarbsG    carbs not counted yet: the part pre-bolus 1 holds back, counted once BG rises; for the reason string
 */
data class FoodToCome(
    val carbsG: Double,
    val carbsUntilMs: Long,
    val fatProteinG: Double,
    val fatProteinUntilMs: Long,
    val carbRatio: Double,
    val coveredG: Double = 0.0,
    val fatProteinFromMs: Long = 0L,
    val heldBackCarbsG: Double = 0.0
) {

    val totalG: Double get() = carbsG + fatProteinG
}

/**
 * The part of the carbs pre-bolus 1 holds back (Planned meal: carbs in pre-bolus 1). It is a guard against an
 * estimate that is too high, not insulin to skip: once BG rises, the food is real and the rest of the carbs count.
 */
enum class CarbTopUp {

    /** Not counted yet: waiting for BG to rise. */
    WAITING,
    /** BG rose: all the carbs count. */
    GIVEN,
    /** BG did not rise in time: the meal was probably smaller than estimated, the held back part never counts. */
    DROPPED
}

/**
 * The food of a meal with an estimate, put into the loop's forecast, so the loop doses for food still coming
 * instead of predicting a low from the insulin on board alone. See `DetermineBasalSmartInsulin`.
 *
 * The carbs are counted as coming in evenly until [FoodToCome.carbsUntilMs] (by default [CARBS_MIN] after they were
 * eaten, sooner for fast food and later for slow food),
 * the fat and protein evenly until the end of the meal's window. Fat and protein only start to raise BG
 * [FAT_PROTEIN_START_MIN] after they are eaten, so until then they are left out of the forecast: the loop does
 * not give insulin for them at the start of the meal. Food the waiting pre-boluses will cover is
 * taken off first, so the loop does not give insulin that a pre-bolus is about to give. Food that does not show
 * up fades out with the time left of the meal, so a smaller meal than estimated is not dosed for long.
 */
object MealFoodForecast {

    /** Minutes carbs of normal speed take to come in, from when they are eaten. A meal's own speed may change it. */
    const val CARBS_MIN = MealEstimateCalibration.DEFAULT_CARBS_MIN
    /** Minutes after they are eaten that fat and protein start to raise BG. */
    const val FAT_PROTEIN_START_MIN = 45
    /** The food still to come may be at most this many times the meal's share of the time left. */
    const val FADE_SLACK = 1.5
    /** Less food than this is not worth forecasting. */
    const val MIN_FOOD_G = 2.0
    /** Food is spread over at least this many 5-min steps: carbs 30 min, fat and protein 1 hour. */
    private const val MIN_CARB_STEPS = 6
    private const val MIN_FAT_PROTEIN_STEPS = 12
    private const val STEP_MS = 5 * 60_000L

    /**
     * The next state of the held back carbs, with the same rule as the rest of pre-bolus 1: from
     * [MealOverrideManager.PB1_REST_FROM_MIN] to [MealOverrideManager.PB1_REST_UNTIL_MIN] minutes after the start,
     * BG rising (the 5 min change at least [MealOverrideManager.PB1_REST_RISE_MGDL] and the 15 min average up) and not
     * low. Not risen by then: dropped.
     */
    fun carbTopUp(current: CarbTopUp, elapsedMs: Long, bgMgdl: Double, delta: Double, shortAvgDelta: Double): CarbTopUp = when {
        current != CarbTopUp.WAITING                                     -> current
        elapsedMs < MealOverrideManager.PB1_REST_FROM_MIN * 60_000L      -> CarbTopUp.WAITING
        elapsedMs > MealOverrideManager.PB1_REST_UNTIL_MIN * 60_000L     -> CarbTopUp.DROPPED
        delta >= MealOverrideManager.PB1_REST_RISE_MGDL && shortAvgDelta > 0.0 &&
            bgMgdl >= MealOverrideManager.MIN_BG_FOR_PB2_MGDL            -> CarbTopUp.GIVEN
        else                                                             -> CarbTopUp.WAITING
    }

    /**
     * The food still to come for the forecast, or null when there is too little.
     *
     * @param restU the rest of pre-bolus 1 still waiting: it covers carbs
     * @param pbU   pre-bolus 2 and 3 still waiting: they cover fat and protein
     * @param carbShare the share of the carbs that needs insulin, as Fill in plans it (Planned meal: carbs in pre-bolus 1,
     *                  80 % by default). The rest counts once BG rises ([StillToCome.carbTopUp]), so a too high
     *                  estimate is not dosed up front.
     * @param fatProteinShare the share of the fat and protein's carb equivalent that needs insulin, as Fill in plans it
     *                        (Planned meal: fat and protein insulin, 70 % by default)
     */
    fun foodToCome(still: StillToCome, nowMs: Long, restU: Double, pbU: Double, carbRatio: Double, carbShare: Double = 1.0, fatProteinShare: Double = 1.0): FoodToCome? {
        if (carbRatio <= 0.0) return null
        val share = if (still.carbTopUp == CarbTopUp.GIVEN) 1.0 else carbShare.coerceIn(0.0, 1.0)
        var carbs = still.carbsG * share
        val heldBackG = if (still.carbTopUp == CarbTopUp.WAITING) still.carbsG - carbs else 0.0
        var fatProtein = still.fatProteinG * fatProteinShare.coerceIn(0.0, 1.0)
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
        return FoodToCome(carbs, still.carbsUntilMs, fatProtein, still.windowEndMs, carbRatio, c1 + f1 + f2 + c2, still.fatProteinFromMs, heldBackG)
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
