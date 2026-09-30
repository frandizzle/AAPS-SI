package app.aaps.plugins.aps.smartInsulin

import kotlin.math.max
import kotlin.math.min

/**
 * oref's COB prediction, ported for SmartInsulin.
 *
 * The SI curve has always modelled insulin only: `predictBgCurve` decays the CURRENTLY OBSERVED
 * carb impact away over an hour and projects insulin activity against it. That is oref's `predCI`
 * with a fixed duration, and it is fine when nothing is known about what was eaten — which is the
 * UAM case the whole plugin is built around. It is wrong when carbs HAVE been entered, because it
 * cannot see the food that has not shown up in BG yet, so it under-predicts the rise and under-doses
 * the meal.
 *
 * This adds the half that needs COB. Two components, exactly as oref0 determine-basal builds them:
 *
 *   1. predCI — the carb impact happening RIGHT NOW, decaying linearly to zero over `cid`. The
 *      decay length is derived from how much COB is left rather than fixed: impact that has a lot
 *      of carbs behind it lasts longer.
 *   2. remainingCI — the carbs that have not started acting yet, spread as a TRIANGLE peaking in
 *      the middle of `remainingCATime`. This is the part a deviation-only model is blind to, and
 *      it is what lets the loop dose a meal before BG has moved.
 *
 * Everything hangs off the carb sensitivity factor:
 *
 *     csf = ISF / CR          mg/dL per gram
 *
 * With ISF 50 mg/dL/U and CR 10 g/U, one gram is worth 5 mg/dL. Deliberately the PROFILE's ISF and
 * CR, not the learned dosing ISF: csf describes the food, not how aggressively this loop wants to
 * treat it, and folding a learned multiplier in here would apply it twice — once in the prediction
 * and again when the prediction is turned into insulin.
 *
 * Autosens is not part of this. oref uses it to stretch remainingCATime when sensitivity has
 * shifted; SI has its own learners for that, and mixing the two would double-count.
 */
object OrefCarbCurve {

    /** oref's default remaining carb absorption time: 3 hours. */
    const val DEFAULT_REMAINING_CA_TIME_MINS = 180.0
    /** oref caps the carbs it will project forward, so one enormous entry can't run the forecast. */
    const val REMAINING_CARBS_CAP_G = 90.0
    private const val TICK_MINS = 5.0

    data class Result(
        /** Predicted BG per 5-minute tick with carbs included — the COB line on the graph. */
        val bgs: List<Double>,
        /** Where it settles: the lowest point of the curve from the peak onward, oref-style. */
        val eventualBg: Double,
        /** How long the impact seen right now is projected to last (minutes). */
        val carbImpactDurationMins: Double,
        /** Carbs not yet showing in BG at all, after the cap. */
        val remainingCarbsG: Double
    )

    /**
     * @param ci          observed carb impact per 5 min (delta minus BGI), as the SI core computes it
     * @param bgiPerTick  insulin's expected effect per tick, mg/dL, negative when insulin is working
     * @param isfMgdl     PROFILE ISF, mg/dL per U
     * @param carbRatio   PROFILE CR, grams per U
     */
    fun predict(
        startBg: Double,
        ci: Double,
        cobG: Double,
        isfMgdl: Double,
        carbRatio: Double,
        bgiPerTick: List<Double>,
        remainingCaTimeMins: Double = DEFAULT_REMAINING_CA_TIME_MINS
    ): Result {
        val ticks = bgiPerTick.size
        if (ticks == 0) return Result(emptyList(), startBg, 0.0, 0.0)
        // No usable profile numbers, or nothing on board: the carb half contributes nothing and the
        // curve is the insulin-only one the plugin already drew.
        if (isfMgdl <= 0.0 || carbRatio <= 0.0 || cobG <= 0.0)
            return insulinOnly(startBg, ci, bgiPerTick)

        val csf = isfMgdl / carbRatio

        // How long the CURRENT impact lasts. oref derives it from the COB behind it — impact of
        // 3 mg/dL/5min with 40g still to absorb has much further to run than the same impact with
        // 5g left — and caps it at half the remaining absorption time so it can't outrun the food.
        val cid = if (ci <= 0.0) 0.0
        else min(remainingCaTimeMins / TICK_MINS / 2.0, max(0.0, cobG * csf / ci))

        // Carbs already accounted for by that decaying impact: the area under the triangle it
        // traces out, converted back to grams. Whatever is left has not started acting yet.
        val totalCiMgdl = max(0.0, ci * cid / 2.0)
        val totalCaG    = if (csf > 0.0) totalCiMgdl / csf else 0.0
        val remainingCarbsG = min(REMAINING_CARBS_CAP_G, max(0.0, cobG - totalCaG))

        // Those remaining carbs are spread as a triangle peaking at the midpoint of
        // remainingCATime — rise, peak, fall — rather than dumped in at a constant rate.
        val remainingTicks = remainingCaTimeMins / TICK_MINS
        val halfTicks      = remainingTicks / 2.0
        val remainingCiPeak =
            if (halfTicks > 0.0) remainingCarbsG * csf / halfTicks else 0.0

        var bg = startBg
        val bgs = ArrayList<Double>(ticks)
        for (tick in 1..ticks) {
            val i = (tick - 1).toDouble()
            // Current impact, decaying to nothing over cid (doubled, as oref does, so it fades
            // over the whole window rather than stopping halfway).
            val predCi = if (cid <= 0.0) 0.0 else max(0.0, ci * (1.0 - i / max(cid * 2.0, 1.0)))
            // The not-yet-absorbed triangle.
            val remainingCi = when {
                remainingCiPeak <= 0.0 -> 0.0
                i <= halfTicks         -> remainingCiPeak * (i / halfTicks)
                i <= remainingTicks    -> remainingCiPeak * (1.0 - (i - halfTicks) / halfTicks)
                else                   -> 0.0
            }
            bg += bgiPerTick[tick - 1] + predCi + remainingCi
            bgs.add(bg)
        }
        return Result(bgs, eventualOf(bgs), cid * TICK_MINS, remainingCarbsG)
    }

    private fun insulinOnly(startBg: Double, ci: Double, bgiPerTick: List<Double>): Result {
        var bg = startBg
        val bgs = ArrayList<Double>(bgiPerTick.size)
        bgiPerTick.forEachIndexed { idx, bgi ->
            val predCi = max(0.0, ci * (1.0 - idx / 12.0))
            bg += bgi + predCi
            bgs.add(bg)
        }
        return Result(bgs, eventualOf(bgs), 0.0, 0.0)
    }

    /**
     * Where the curve is headed. Taken as the LOWEST point once the carb rise is over rather than
     * the last tick: a curve that peaks at 12 and comes back to 6 is a 6 for dosing purposes, and
     * taking the end of a truncated horizon instead would read a still-falling curve as its floor.
     */
    private fun eventualOf(bgs: List<Double>): Double {
        if (bgs.isEmpty()) return 0.0
        val peakIdx = bgs.indexOf(bgs.max())
        return bgs.drop(peakIdx).minOrNull() ?: bgs.last()
    }
}
