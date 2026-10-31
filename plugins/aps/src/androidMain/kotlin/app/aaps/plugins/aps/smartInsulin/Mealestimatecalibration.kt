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
 */
data class StillToCome(
    val modeStartMs: Long,
    val windowEndMs: Long,
    val carbsG: Double,
    val fatProteinG: Double,
    val mealG: Double,
    val carbsUntilMs: Long
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
        val learn:       Boolean = true
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

    override fun recordEstimate(modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, learn: Boolean) {
        synchronized(this) {
            // A new estimate for the same meal mode replaces the old one.
            pending.removeAll { it.modeStartMs == modeStartMs }
            pending.add(Pending(modeStartMs, windowEndMs, carbEquivG, carbsG, proteinG, fatG, learn = learn))
            while (pending.size > MAX_PENDING) pending.removeAt(0)
        }
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: estimate ${"%.0f".format(carbEquivG)} g recorded")
    }

    override fun addToEstimate(modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double, eatenMs: Long) {
        val found = synchronized(this) {
            pending.firstOrNull { it.modeStartMs == modeStartMs }?.also {
                it.windowEndMs = maxOf(it.windowEndMs, windowEndMs)
                if (carbsG > 0.0) it.carbsUntilMs = maxOf(it.carbsUntilMs, eatenMs + MealFoodForecast.CARBS_MIN * 60_000L)
                it.carbEquivG += carbEquivG; it.carbsG += carbsG; it.proteinG += proteinG; it.fatG += fatG
            }
        }
        if (found == null) return
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: ${"%.0f".format(carbEquivG)} g added, meal now ${"%.0f".format(found.carbEquivG)} g")
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
            pending.firstOrNull { matches(it, episode.startMs) }
                ?.also { it.trackerG += episode.estimatedGrams; it.episodes++ }
        }
        if (found != null) save()
    }

    override fun foodStillToComeG(modeStartMs: Long): Double? {
        val p = synchronized(this) { pending.firstOrNull { it.modeStartMs == modeStartMs } } ?: return null
        return stillToCome(p).let { it.carbsG + it.fatProteinG }
    }

    /**
     * The food still to come of the estimated meal whose window holds [nowMs], for the loop's forecast; null when
     * there is none. A following Protein/Fat is inside the window, so the food goes on counting there.
     */
    fun stillToCome(nowMs: Long): StillToCome? {
        val p = synchronized(this) { pending.filter { nowMs >= it.modeStartMs && nowMs <= it.windowEndMs }.maxByOrNull { it.modeStartMs } } ?: return null
        return stillToCome(p)
    }

    /** What the tracker has seen is counted against the carbs first: they come in first. */
    private fun stillToCome(p: Pending): StillToCome {
        val f = factor
        val seen = p.trackerG + (live?.takeIf { matches(p, it.startMs) }?.estimatedGramsSoFar ?: 0.0)
        val carbs = p.carbsG * f
        val fatProtein = (p.carbEquivG - p.carbsG).coerceAtLeast(0.0) * f
        return StillToCome(
            modeStartMs = p.modeStartMs,
            windowEndMs = p.windowEndMs,
            carbsG = (carbs - seen).coerceAtLeast(0.0),
            fatProteinG = (fatProtein - (seen - carbs).coerceAtLeast(0.0)).coerceAtLeast(0.0),
            mealG = p.carbEquivG * f,
            carbsUntilMs = p.carbsUntilMs
        )
    }

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

    private fun judge(p: Pending) {
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
        csvLogger.logEstimate(p.modeStartMs, p.carbsG, p.proteinG, p.fatG, p.carbEquivG, p.trackerG, ratio, after)
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: estimate ${"%.0f".format(p.carbEquivG)} g, tracker ${"%.0f".format(p.trackerG)} g, " +
            "ratio ${"%.2f".format(ratio)}, factor ${"%.2f".format(after)}")
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
                                .put("t", it.trackerG).put("n", it.episodes).put("cu", it.carbsUntilMs).put("l", it.learn))
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
                                        o.optLong("cu", start + MealFoodForecast.CARBS_MIN * 60_000L), o.optBoolean("l", true)))
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
        private const val MAX_PENDING = 5
        private const val K_RATIOS = "ratios"
        private const val K_PENDING = "pending"
    }
}
