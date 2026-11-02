package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
import app.aaps.core.keys.StringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * A meal's food that has not come in yet, in grams of carb equivalent, scaled by the learned factor.
 *
 * @param mealG        the whole meal as estimated, scaled the same way
 * @param carbsUntilMs the carbs eaten so far should have come in by this time
 * @param fatProteinFromMs the fat and protein start to come in at this time
 * @param fatProteinMealG  all the meal's fat and protein, scaled the same way, for the rate it comes in at
 * @param fatProteinMin    minutes all of it takes to come in from [fatProteinFromMs], see `MealFoodForecast.fatProteinMinutes`
 */
data class StillToCome(
    val modeStartMs: Long,
    val windowEndMs: Long,
    val carbsG: Double,
    val fatProteinG: Double,
    val mealG: Double,
    val carbsUntilMs: Long,
    val fatProteinFromMs: Long = 0L,
    val fatProteinMealG: Double = 0.0,
    val fatProteinMin: Int = 0
)

/**
 * Compares each estimated meal with the grams [MealAbsorptionTracker] saw for it, and keeps the ratio.
 *
 * Both sides are in the same unit on purpose: the tracker turns insulin into grams with the profile's
 * carb ratio, and the meal planner turns grams back into insulin with the same ratio. So [factor] says
 * "this user's meals behave like this many times the estimate", whatever the carb ratio is set to.
 *
 * A meal is judged once its watch window is over plus [GRACE_MS], so a following Protein/Fat episode
 * has time to end and be counted too.
 */
@SingleIn(AppScope::class)
class MealEstimateCalibrationImpl @Inject constructor(
    private val sp:         KeyValueStore,
    private val csvLogger:  MealAbsorptionCsvLogger,
    private val aapsLogger: AAPSLogger
) : MealEstimateCalibration {

    private class Pending(
        val modeStartMs: Long,
        var windowEndMs: Long,
        var carbEquivG:  Double,
        var carbsG:      Double,
        var proteinG:    Double,
        var fatG:        Double,
        var trackerG:    Double = 0.0,
        var episodes:    Int    = 0,
        /** The carbs eaten so far should have come in by this time. */
        var carbsUntilMs: Long  = modeStartMs + MealFoodForecast.CARBS_MIN * 60_000L,
        var learn:       Boolean = true,
        /** The fat and protein start to come in at this time. */
        var fatProteinFromMs: Long = modeStartMs + MealFoodForecast.FAT_PROTEIN_START_MIN * 60_000L,
        /** There was real exercise during the meal or just before it: BG says less about how much was eaten. */
        var exercised: Boolean = false,
        /** Minutes the meal's first carbs take to come in, from how fast the foods are; for the log. */
        val carbsMin: Int = MealFoodForecast.CARBS_MIN,
        /**
         * Food the tracker saw beyond the estimate before more food was added: food nobody counted, such as a snack
         * logged for calories only. It is kept out of what was seen, so it does not cancel the food added after it.
         */
        var seenBeforeAddG: Double = 0.0,
        /** The part of [carbEquivG] that was estimated (photo or description). The rest is exact: a scan, typed in, saved. */
        var estimatedG: Double = carbEquivG
    )

    private val pending = mutableListOf<Pending>()
    /** The meal episode the tracker is watching now, with its grams so far; null when none. */
    @Volatile private var live: InProgressEpisode? = null
    private val ratios = mutableListOf<Double>()

    init {
        load()
    }

    override val factor: Double
        get() = synchronized(this) {
            if (ratios.size < MIN_MEALS) 1.0 else median(ratios).coerceIn(MIN_FACTOR, MAX_FACTOR)
        }

    override val mealsUsed: Int get() = synchronized(this) { ratios.size }

    override fun recordEstimate(
        modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, learn: Boolean, carbsMin: Int,
        estimatedG: Double
    ) {
        synchronized(this) {
            // A new estimate for the same meal mode replaces the old one.
            pending.removeAll { it.modeStartMs == modeStartMs }
            // An earlier meal with food still to come: the tracker cannot tell its tail from this meal, so
            // neither is compared with its estimate.
            val earlier = pending.filter { it.modeStartMs < modeStartMs && modeStartMs <= it.windowEndMs && stillToCome(it).let { c -> c.carbsG + c.fatProteinG } >= MealFoodForecast.MIN_FOOD_G }
            earlier.forEach { it.learn = false }
            pending.add(Pending(modeStartMs, windowEndMs, carbEquivG, carbsG, proteinG, fatG, learn = learn && earlier.isEmpty(),
                                carbsUntilMs = modeStartMs + carbsMin * 60_000L, carbsMin = carbsMin, estimatedG = estimatedG.coerceIn(0.0, carbEquivG)))
            while (pending.size > MAX_PENDING) pending.removeAt(0)
        }
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: estimate ${"%.0f".format(carbEquivG)} g recorded")
    }

    override fun addToEstimate(
        modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, eatenMs: Long, carbsMin: Int,
        estimatedG: Double
    ) {
        val found = synchronized(this) {
            pending.firstOrNull { it.modeStartMs == modeStartMs }?.also { add(it, windowEndMs, carbEquivG, carbsG, proteinG, fatG, eatenMs, carbsMin, estimatedG) }
        }
        if (found == null) return
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: ${"%.0f".format(carbEquivG)} g added, meal now ${"%.0f".format(found.carbEquivG)} g")
    }

    override fun logFood(
        modeStartMs: Long, modeEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, eatenMs: Long, carbsMin: Int,
        estimatedG: Double
    ) {
        // The food needs time to come in, also when the mode ends sooner.
        val windowEndMs = maxOf(modeEndMs, eatenMs + carbsMin * 60_000L)
        val meal = synchronized(this) {
            val p = pending.firstOrNull { it.modeStartMs == modeStartMs }
                ?: Pending(modeStartMs, windowEndMs, 0.0, 0.0, 0.0, 0.0, learn = false, carbsUntilMs = eatenMs, carbsMin = carbsMin,
                           fatProteinFromMs = eatenMs + MealFoodForecast.FAT_PROTEIN_START_MIN * 60_000L)
                    .also { pending.add(it); while (pending.size > MAX_PENDING) pending.removeAt(0) }
            add(p, windowEndMs, carbEquivG, carbsG, proteinG, fatG, eatenMs, carbsMin, estimatedG)
            // Food eaten without its insulin says nothing about how good the estimates are.
            p.learn = false
            p
        }
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: ${"%.0f".format(carbEquivG)} g logged in the meal at $modeStartMs, now ${"%.0f".format(meal.carbEquivG)} g, not for learning")
    }

    /** Adds food eaten at [eatenMs] to [p]: more food, a longer window, and carbs expected over [carbsMin] from when eaten. */
    private fun add(
        p: Pending, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, eatenMs: Long, carbsMin: Int,
        estimatedG: Double
    ) {
        // What the tracker saw beyond the meal so far came before this food was eaten, so it is not this food.
        val beyondG = seenG(p) - p.carbEquivG * factorFor(p)
        if (beyondG > 0.0) p.seenBeforeAddG += beyondG
        p.windowEndMs = maxOf(p.windowEndMs, windowEndMs)
        if (carbsG > 0.0) p.carbsUntilMs = maxOf(p.carbsUntilMs, eatenMs + carbsMin * 60_000L)
        // Fat and protein already on their way keep their start; the first fat and protein start when eaten.
        if (carbEquivG > carbsG && p.carbEquivG <= p.carbsG)
            p.fatProteinFromMs = eatenMs + MealFoodForecast.FAT_PROTEIN_START_MIN * 60_000L
        p.carbEquivG += carbEquivG; p.carbsG += carbsG; p.proteinG += proteinG; p.fatG += fatG
        p.estimatedG += estimatedG.coerceIn(0.0, carbEquivG)
    }

    /**
     * The user changed how much of a logged food was eaten, or removed it: the same change is made to the estimate of
     * the meal it was logged in, so the loop counts the food really eaten. Food logged outside a waiting meal changes
     * nothing. The changes are new minus old, in grams, so a smaller amount gives negative numbers.
     *
     * @param loggedMs when the food was logged
     */
    fun changeLoggedFood(loggedMs: Long, carbsG: Double, proteinG: Double, fatG: Double) {
        val carbEquivG = carbsG + (fatG * 9.0 + proteinG * 4.0) / 100.0 * FPU_CARB_G
        val found = synchronized(this) {
            pending.filter { matches(it, loggedMs) }.maxByOrNull { it.modeStartMs }?.also {
                val share = if (it.carbEquivG > 0.0) it.estimatedG / it.carbEquivG else 1.0
                it.carbEquivG = (it.carbEquivG + carbEquivG).coerceAtLeast(0.0)
                it.estimatedG = it.carbEquivG * share
                it.carbsG = (it.carbsG + carbsG).coerceAtLeast(0.0)
                it.proteinG = (it.proteinG + proteinG).coerceAtLeast(0.0)
                it.fatG = (it.fatG + fatG).coerceAtLeast(0.0)
            }
        }
        if (found == null) return
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: logged food changed by ${"%.0f".format(carbEquivG)} g, meal now ${"%.0f".format(found.carbEquivG)} g")
    }

    override fun dropEstimate(modeStartMs: Long) {
        val removed = synchronized(this) { pending.removeAll { it.modeStartMs == modeStartMs } }
        if (!removed) return
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: meal at $modeStartMs got food without an estimate, not used")
    }

    /** Adds a finished meal episode to the estimated meal it belongs to, if any. */
    fun onEpisode(episode: CompletedMealEpisode) {
        val found = synchronized(this) {
            owner(episode.startMs)?.also { it.trackerG += episode.estimatedGrams; it.episodes++ }
        }
        if (found != null) save()
    }

    override fun foodStillToComeG(modeStartMs: Long): Double? {
        val p = synchronized(this) { pending.firstOrNull { it.modeStartMs == modeStartMs } } ?: return null
        return stillToCome(p).let { it.carbsG + it.fatProteinG }
    }

    /**
     * The food still to come of the meals started before the one at [modeStartMs] whose window is still open at [nowMs]:
     * a snack, or a meal replaced by this one. Their food keeps coming in on its own timing, so the loop's forecast counts
     * it next to this meal's.
     */
    fun earlierToCome(modeStartMs: Long, nowMs: Long): List<StillToCome> =
        synchronized(this) { pending.filter { it.modeStartMs < modeStartMs && nowMs <= it.windowEndMs } }.map { stillToCome(it) }

    /**
     * The food still to come of the estimated meal started at [modeStartMs], for the loop's forecast; null when it has
     * no estimate or its window is over at [nowMs].
     */
    fun stillToCome(modeStartMs: Long, nowMs: Long): StillToCome? {
        val p = synchronized(this) { pending.firstOrNull { it.modeStartMs == modeStartMs && nowMs <= it.windowEndMs } } ?: return null
        return stillToCome(p)
    }

    /**
     * What the tracker has seen is counted against the carbs first: they come in first. For the running episode the
     * larger of its two counts is used: the BG rise it saw, or the food the insulin and BG account for. The second
     * keeps food that BG never showed (a flat BG with insulin working) from being counted as still to come.
     */
    private fun stillToCome(p: Pending): StillToCome {
        val f = factorFor(p)
        val seen = seenG(p)
        val carbs = p.carbsG * f
        val fatProtein = (p.carbEquivG - p.carbsG).coerceAtLeast(0.0) * f
        return StillToCome(
            modeStartMs = p.modeStartMs,
            windowEndMs = p.windowEndMs,
            carbsG = (carbs - seen).coerceAtLeast(0.0),
            fatProteinG = (fatProtein - (seen - carbs).coerceAtLeast(0.0)).coerceAtLeast(0.0),
            mealG = p.carbEquivG * f,
            carbsUntilMs = p.carbsUntilMs,
            fatProteinFromMs = p.fatProteinFromMs,
            fatProteinMealG = fatProtein,
            fatProteinMin = MealFoodForecast.fatProteinMinutes((p.carbEquivG - p.carbsG).coerceAtLeast(0.0) / FPU_CARB_G)
        )
    }

    /**
     * The learned factor for [p]: it is how far photo and description estimates are off, so only the estimated part of
     * the meal is scaled. Scanned, typed-in and saved food counts as it is.
     */
    private fun factorFor(p: Pending): Double =
        if (p.carbEquivG > 0.0) (p.carbEquivG - p.estimatedG + p.estimatedG * factor) / p.carbEquivG else factor

    /**
     * The food the tracker has seen of [p]: its finished episodes, and for the running one the larger of its two
     * counts. Less what it saw beyond the estimate before more food was added ([Pending.seenBeforeAddG]).
     */
    private fun seenG(p: Pending): Double =
        p.trackerG + (live?.takeIf { owner(it.startMs) === p }?.let { maxOf(it.estimatedGramsSoFar, it.balanceGramsSoFar) } ?: 0.0) - p.seenBeforeAddG

    /** The meal an episode starting at [episodeStartMs] belongs to: the latest one it falls in, so a meal after a snack gets its own episode. */
    private fun owner(episodeStartMs: Long): Pending? = synchronized(this) { pending.filter { matches(it, episodeStartMs) }.maxByOrNull { it.modeStartMs } }

    private fun matches(p: Pending, episodeStartMs: Long) = episodeStartMs >= p.modeStartMs - MATCH_EARLY_MS && episodeStartMs <= p.windowEndMs

    /**
     * Judges the estimated meals whose time is over. Call once per loop cycle.
     *
     * @param inProgress the episode the meal tracker is watching now, for [foodStillToComeG]
     */
    fun onCycle(nowMs: Long, inProgress: InProgressEpisode? = null) {
        live = inProgress
        val done = synchronized(this) {
            pending.filter { nowMs > it.windowEndMs + GRACE_MS }.also { pending.removeAll(it) }
        }
        if (done.isEmpty()) return
        done.forEach { judge(it) }
        save()
    }

    /**
     * Marks the meals running at [nowMs] as eaten around exercise. Exercise makes BG drop, which the meal tracker
     * would read as less food, so those meals do not teach the factor that the estimates are too high.
     */
    fun noteExercise(nowMs: Long) {
        val marked = synchronized(this) {
            pending.filter { !it.exercised && nowMs >= it.modeStartMs && nowMs <= it.windowEndMs }.onEach { it.exercised = true }
        }
        if (marked.isEmpty()) return
        save()
        marked.forEach { aapsLogger.debug(LTag.APS, "MealEstimateCalibration: meal at ${it.modeStartMs} had exercise, will not be used") }
    }

    private fun judge(p: Pending) {
        // Exact food says nothing about how far estimates are off.
        if (p.estimatedG < p.carbEquivG - MIN_FOOD_EXACT_G) {
            aapsLogger.debug(LTag.APS, "MealEstimateCalibration: meal at ${p.modeStartMs} not used (scanned, typed-in or saved food in it)")
            return
        }
        if (p.exercised) {
            aapsLogger.debug(LTag.APS, "MealEstimateCalibration: meal at ${p.modeStartMs} not used (exercise)")
            return
        }
        if (!p.learn || p.episodes == 0 || p.carbEquivG < MIN_ESTIMATE_G) {
            aapsLogger.debug(LTag.APS, "MealEstimateCalibration: meal at ${p.modeStartMs} not used (episodes=${p.episodes})")
            return
        }
        val ratio = (p.trackerG / p.carbEquivG).coerceIn(MIN_RATIO, MAX_RATIO)
        val after = synchronized(this) {
            ratios.add(ratio)
            while (ratios.size > MAX_MEALS) ratios.removeAt(0)
            factor
        }
        csvLogger.logEstimate(p.modeStartMs, p.carbsG, p.proteinG, p.fatG, p.carbEquivG, p.trackerG, ratio, after, p.carbsMin)
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: estimate ${"%.0f".format(p.carbEquivG)} g, tracker ${"%.0f".format(p.trackerG)} g, " +
            "ratio ${"%.2f".format(ratio)}, factor ${"%.2f".format(after)}")
    }

    /** Forgets the learned factor, and keeps the meals still running, so their food still counts. */
    fun resetFactor() {
        synchronized(this) { ratios.clear() }
        save()
    }

    /** Forgets everything learned. */
    fun reset() {
        synchronized(this) { pending.clear(); ratios.clear() }
        save()
    }

    private fun save() {
        val json = synchronized(this) {
            JSONObject()
                .put(K_RATIOS, JSONArray(ratios))
                .put(K_PENDING, JSONArray().apply {
                    pending.forEach {
                        put(JSONObject()
                                .put("s", it.modeStartMs).put("e", it.windowEndMs).put("ce", it.carbEquivG)
                                .put("c", it.carbsG).put("p", it.proteinG).put("f", it.fatG)
                                .put("t", it.trackerG).put("n", it.episodes).put("cu", it.carbsUntilMs).put("l", it.learn)
                                .put("fs", it.fatProteinFromMs).put("x", it.exercised).put("cm", it.carbsMin).put("sb", it.seenBeforeAddG).put("eg", it.estimatedG))
                    }
                })
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinMealEstimateCalibration.key, json.toString()) }
    }

    private fun load() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinMealEstimateCalibration.key, "")
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.optJSONArray(K_RATIOS)?.let { a -> for (i in 0 until a.length()) ratios.add(a.getDouble(i)) }
            json.optJSONArray(K_PENDING)?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val start = o.getLong("s")
                    pending.add(Pending(start, o.getLong("e"), o.getDouble("ce"), o.getDouble("c"), o.getDouble("p"), o.getDouble("f"),
                                        o.optDouble("t", 0.0), o.optInt("n", 0),
                                        o.optLong("cu", start + MealFoodForecast.CARBS_MIN * 60_000L), o.optBoolean("l", true),
                                        o.optLong("fs", start + MealFoodForecast.FAT_PROTEIN_START_MIN * 60_000L),
                                        o.optBoolean("x", false), o.optInt("cm", MealFoodForecast.CARBS_MIN),
                                        o.optDouble("sb", 0.0), o.optDouble("eg", o.getDouble("ce"))))
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MealEstimateCalibration: could not read saved state: ${e.message}")
            ratios.clear(); pending.clear()
        }
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    companion object {

        /** The factor stays 1.0 until this many meals are judged. */
        const val MIN_MEALS = 3
        /** The factor uses the last this many meals. */
        const val MAX_MEALS = 20
        const val MIN_FACTOR = 0.7
        /** Exact food up to this much (carb equivalent) still leaves a meal as estimated, for learning. */
        const val MIN_FOOD_EXACT_G = 2.0
        const val MAX_FACTOR = 1.5
        /** One very odd meal (forgot to eat half, a site problem) may not count as more than this. */
        const val MIN_RATIO = 0.3
        const val MAX_RATIO = 3.0
        /** Smaller estimates are too noisy to learn from. */
        const val MIN_ESTIMATE_G = 10.0
        /** Time after the watch window before a meal is judged, so its last episode has ended. */
        const val GRACE_MS = 2 * 60 * 60_000L
        /** An episode that starts this much before the mode still belongs to it (clock rounding). */
        const val MATCH_EARLY_MS = 60_000L
        /** One fat-protein unit (100 kcal from fat and protein) counts as this many grams of carbs, as in the meal planner. */
        const val FPU_CARB_G = 10.0
        private const val MAX_PENDING = 5
        private const val K_RATIOS = "ratios"
        private const val K_PENDING = "pending"
    }
}
