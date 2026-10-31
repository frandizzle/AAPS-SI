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
        var episodes:    Int    = 0
    )

    private val pending = mutableListOf<Pending>()
    private val ratios = mutableListOf<Double>()

    init {
        load()
    }

    override val factor: Double
        get() = synchronized(this) {
            if (ratios.size < MIN_MEALS) 1.0 else median(ratios).coerceIn(MIN_FACTOR, MAX_FACTOR)
        }

    override val mealsUsed: Int get() = synchronized(this) { ratios.size }

    override fun recordEstimate(modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double) {
        synchronized(this) {
            // A new estimate for the same meal mode replaces the old one.
            pending.removeAll { it.modeStartMs == modeStartMs }
            pending.add(Pending(modeStartMs, windowEndMs, carbEquivG, carbsG, proteinG, fatG))
            while (pending.size > MAX_PENDING) pending.removeAt(0)
        }
        save()
        aapsLogger.debug(LTag.APS, "MealEstimateCalibration: estimate ${"%.0f".format(carbEquivG)} g recorded")
    }

    override fun addToEstimate(modeStartMs: Long, windowEndMs: Long, carbEquivG: Double, carbsG: Double, proteinG: Double, fatG: Double) {
        val found = synchronized(this) {
            pending.firstOrNull { it.modeStartMs == modeStartMs }?.also {
                it.windowEndMs = maxOf(it.windowEndMs, windowEndMs)
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
            pending.firstOrNull { episode.startMs >= it.modeStartMs - MATCH_EARLY_MS && episode.startMs <= it.windowEndMs }
                ?.also { it.trackerG += episode.estimatedGrams; it.episodes++ }
        }
        if (found != null) save()
    }

    /** Judges the estimated meals whose time is over. Call once per loop cycle. */
    fun onCycle(nowMs: Long) {
        val done = synchronized(this) {
            pending.filter { nowMs > it.windowEndMs + GRACE_MS }.also { pending.removeAll(it) }
        }
        if (done.isEmpty()) return
        done.forEach { judge(it) }
        save()
    }

    private fun judge(p: Pending) {
        if (p.episodes == 0 || p.carbEquivG < MIN_ESTIMATE_G) {
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
                                .put("t", it.trackerG).put("n", it.episodes))
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
                    pending.add(Pending(o.getLong("s"), o.getLong("e"), o.getDouble("ce"), o.getDouble("c"), o.getDouble("p"), o.getDouble("f"),
                                        o.optDouble("t", 0.0), o.optInt("n", 0)))
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
