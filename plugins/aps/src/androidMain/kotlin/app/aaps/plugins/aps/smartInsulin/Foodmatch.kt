package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The third line of a meal with fat and protein: what the food really does, next to what was logged and what the insulin
 * does. Pre-bolus 2 is only half of the planned dose. The rest comes as small top-ups while the food shows up, so
 * insulin not given yet can still be held back when the logged food was too much, and more can follow when it was too little.
 *
 * Each cycle the food coming in over the last [LOOK_BACK_MS] is compared with the insulin that will be working
 * [LEAD_MIN] minutes from now, and a part of the gap is given. Pure, so it can be tested on its own.
 */
object FoodMatch {

    /** How far back the food coming in is measured. */
    const val LOOK_BACK_MS = 30 * 60_000L
    /** Too few readings say nothing about the food. */
    const val MIN_SAMPLES = 3
    /** The insulin of a dose given now works best about this long from now. */
    const val LEAD_MIN = 60
    /** The part of the gap given each cycle: the gap is seen again 5 minutes later. */
    const val GAIN = 0.15
    /** The most one top-up may be, up to [ABOVE_TARGET_MGDL] above target. */
    const val MAX_TOP_UP_U = 0.3
    /**
     * The most one top-up may be from [HIGH_ABOVE_TARGET_MGDL] above target. Between the two it grows in a straight line.
     * At 0.3 U the loop can catch up by at most 3.6 U/h, and each top-up works an hour later: on 9 Nov twelve top-ups in
     * a row were all at the cap while BG went from 8 to 11.8.
     */
    const val MAX_TOP_UP_HIGH_U = 0.6
    /** Pre-bolus 2, pre-bolus 3 and top-ups together stop at this many times the planned fat and protein insulin. */
    const val TOTAL_SHARE = 1.5
    /** At least this far above target, in mg/dL, BG itself shows that more insulin is wanted. The same as for pieces led by BG. */
    const val ABOVE_TARGET_MGDL = MealOverrideManager.CLEARLY_ABOVE_TARGET_MGDL
    /** This far above target, in mg/dL (3 mmol/L), a top-up may be [MAX_TOP_UP_HIGH_U]. */
    const val HIGH_ABOVE_TARGET_MGDL = MealOverrideManager.FULL_ABOVE_TARGET_MGDL
    /** Closer to target, only a rise of at least this much in 5 min, in mg/dL, allows a top-up. A flat BG reading wobbles by less. */
    const val RISING_MGDL = MealOverrideManager.RISING_MGDL

    private const val LEAD_STEPS = LEAD_MIN / 5
    private const val STEP_EPSILON = 1e-6

    /** @param foodMgdl what is not insulin in the BG change of a 5-min step: the change, plus what the insulin took off */
    data class Sample(val timeMs: Long, val foodMgdl: Double)

    /**
     * @param doseU          the top-up
     * @param foodMgdl       the rise expected from food [LEAD_MIN] from now, per 5 min
     * @param insulinMgdl    what the insulin will take off then, per 5 min
     */
    data class TopUp(val doseU: Double, val foodMgdl: Double, val insulinMgdl: Double)

    /** What is left of [plannedU] x [TOTAL_SHARE] after the pre-boluses for fat and protein and the top-ups so far. */
    fun roomU(plannedU: Double, preBolusU: Double, givenU: Double): Double =
        max(0.0, plannedU * TOTAL_SHARE - preBolusU - givenU)

    /** The most one top-up may be at this BG: [MAX_TOP_UP_U] near target, [MAX_TOP_UP_HIGH_U] well above it. */
    fun maxTopUpU(bgMgdl: Double, targetMgdl: Double): Double {
        val above = bgMgdl - targetMgdl
        val part = ((above - ABOVE_TARGET_MGDL) / (HIGH_ABOVE_TARGET_MGDL - ABOVE_TARGET_MGDL)).coerceIn(0.0, 1.0)
        return MAX_TOP_UP_U + (MAX_TOP_UP_HIGH_U - MAX_TOP_UP_U) * part
    }

    /**
     * Whether BG asks for a top-up at all. Near target and flat it does not: the logged fat and protein are only an estimate,
     * and at 5.7 with a 5.5 target a flat BG once gave the largest top-up every 5 minutes for over half an hour (6 Nov).
     * The same rule holds for all the food in the forecast, see `FAT_PROTEIN_ALL_FOOD_ABOVE_TARGET_MGDL`.
     *
     * "At target and rising" is for the start and the middle of a meal, when a rise is the food coming in. Once the carbs
     * are done ([carbsDone]) only BG clearly above target counts: at the tail a small rise near target is the last of the
     * fat and protein, and the food list is least sure then. At the end of a lunch, BG 5.7 to 6.1 rising 0.1 to 0.2, the
     * loop gave 1.25 U for 21 g still to come, the food stopped 15 min later, and BG fell with 2 U on board (11 Nov).
     */
    fun bgAllows(bgMgdl: Double, targetMgdl: Double, deltaMgdl: Double, carbsDone: Boolean = false): Boolean =
        bgMgdl >= targetMgdl + ABOVE_TARGET_MGDL || (!carbsDone && bgMgdl >= targetMgdl && deltaMgdl >= RISING_MGDL)

    /**
     * The top-up for this cycle, or null when none is needed.
     *
     * @param samples           the food seen, oldest first
     * @param foodLeftG         the logged food still to come, in grams of carb equivalent. It limits what is expected also
     *                          while BG rises: the fat and protein on the list fade with time, so late in a meal the top-ups
     *                          fade with them. Until 13 Nov a rise counted food past the list, and top-ups stayed as large as
     *                          at the height of the meal until its end: two dinners in a row went low after it.
     * @param csfMgdlPerG       what one gram raises BG
     * @param insulinAtLead     insulin action [LEAD_MIN] from now, U/min, of all insulin on board
     * @param unitAtLead        action of one unit given now, [LEAD_MIN] from now, per min
     * @param roomU             see [roomU]
     * @param maxTopUpU         the most this top-up may be, see [maxTopUpU]
     */
    fun topUp(
        samples: List<Sample>, nowMs: Long, foodLeftG: Double, csfMgdlPerG: Double,
        insulinAtLead: Double, unitAtLead: Double, isfMgdl: Double, bolusStep: Double, roomU: Double,
        maxTopUpU: Double = MAX_TOP_UP_U
    ): TopUp? {
        val recent = samples.filter { nowMs - it.timeMs <= LOOK_BACK_MS }
        if (recent.size < MIN_SAMPLES || unitAtLead <= 0.0 || isfMgdl <= 0.0 || bolusStep <= 0.0) return null
        val rate = max(0.0, recent.sumOf { it.foodMgdl } / recent.size)
        if (rate <= 0.0) return null
        val left = max(0.0, foodLeftG) * csfMgdlPerG
        val food = min(rate, left / (2 * LEAD_STEPS))
        val insulin = max(0.0, insulinAtLead) * isfMgdl * 5.0
        val gap = food - insulin
        if (gap <= 0.0) return null
        val wanted = GAIN * gap / (unitAtLead * isfMgdl * 5.0)
        val limit = min(min(wanted, maxTopUpU), roomU)
        val dose = min(floor(limit / bolusStep + STEP_EPSILON) * bolusStep, limit)
        return if (dose >= bolusStep) TopUp(dose, food, insulin) else null
    }
}
