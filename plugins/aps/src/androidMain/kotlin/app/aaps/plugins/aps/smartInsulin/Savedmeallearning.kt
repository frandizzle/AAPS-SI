package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.smartInsulin.LearnedMeal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.SavedMealLearning
import app.aaps.core.keys.StringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learns each saved meal on its own: how much food BG showed compared with what the meal was planned
 * with, split into the carbs (the first [CARB_PHASE_MS]) and the fat and protein (after that).
 *
 * The grams come from [MealAbsorptionTracker], the same as for `MealEstimateCalibrationImpl`, so the
 * factors are in the same unit as the plan: "this meal acts like this many times what was planned".
 *
 * Rules, one bounded step per meal:
 *  - A low in a part of the meal cuts that part by [LOW_STEP]; BG just above the low guard by [UNDERSHOOT_STEP].
 *  - Without a low, each part moves toward what BG showed, by at most [MAX_STEP]. After a low anywhere in the
 *    meal, nothing grows.
 *  - A meal that got other food, ended early, was replaced, had a second helping, exercise or the new pod
 *    boost is not learned from.
 *
 * It also notes when BG starts to rise: a rise within [ATE_SOON_MS] of the start means the meal was probably
 * eaten without waiting after pre-bolus 1. That does not change the factors (the grams are the same either
 * way); it is only shown as a hint.
 */
@SingleIn(AppScope::class)
class SavedMealLearningImpl @Inject constructor(
    private val sp:         KeyValueStore,
    private val aapsLogger: AAPSLogger
) : SavedMealLearning {

    private class State(var carbFactor: Double = 1.0, var fatProteinFactor: Double = 1.0, var meals: Int = 0, val soon: MutableList<Boolean> = mutableListOf())

    private class Watch(
        val name:        String,
        val startMs:     Long,
        val modeEndMs:   Long,
        var windowEndMs: Long,
        val carbsG:      Double,
        val fatProteinG: Double,
        var carbSeenG:   Double = 0.0,
        var fpSeenG:     Double = 0.0,
        var episodeStartMs: Long = 0L,
        var episodeGrams: Double = 0.0,
        var startBgMgdl: Double = 0.0,
        var riseAfterMs: Long = -1L,
        var carbLow:     Boolean = false,
        var fpLow:       Boolean = false,
        var carbUnder:   Boolean = false,
        var fpUnder:     Boolean = false,
        var dropReason:  String = ""
    )

    private val states = mutableMapOf<String, State>()
    private val watches = mutableListOf<Watch>()

    /** Called with one line for the learning journal each time a meal is judged. */
    var onOutcome: ((String) -> Unit)? = null

    init {
        load()
    }

    override fun learned(name: String): LearnedMeal? = synchronized(this) {
        states[name]?.let { LearnedMeal(it.carbFactor, it.fatProteinFactor, it.meals, it.soon.count { s -> s }, it.soon.size) }
    }

    override fun recordMeal(name: String, modeStartMs: Long, modeEndMs: Long, windowEndMs: Long, carbsG: Double, fatProteinG: Double) {
        synchronized(this) {
            watches.removeAll { it.startMs == modeStartMs }
            watches.add(Watch(name, modeStartMs, modeEndMs, windowEndMs, carbsG, fatProteinG))
            while (watches.size > MAX_WATCHES) watches.removeAt(0)
        }
        save()
        aapsLogger.debug(LTag.APS, "SavedMealLearning: watching $name")
    }

    override fun dropMeal(modeStartMs: Long) {
        val changed = synchronized(this) { watches.filter { it.startMs == modeStartMs && it.dropReason.isEmpty() }.onEach { it.dropReason = "other food was added" }.isNotEmpty() }
        if (changed) save()
    }

    override fun rename(name: String, newName: String) {
        if (name == newName) return
        synchronized(this) {
            states.remove(name)?.let { states[newName] = it }
            watches.replaceAll { w -> if (w.name == name) copyWatch(w, newName) else w }
        }
        save()
    }

    override fun forget(name: String) {
        synchronized(this) {
            states.remove(name)
            watches.removeAll { it.name == name }
        }
        save()
    }

    /**
     * True while a saved meal is being watched at [nowMs]. The mode ISF and DURA learners then sit the episode
     * out, so a spike is not corrected by two learners.
     */
    fun watching(nowMs: Long): Boolean = synchronized(this) { watches.any { nowMs >= it.startMs && nowMs <= it.windowEndMs + LOW_WATCH_MS } }

    /**
     * Call once per loop cycle.
     *
     * @param activeMode          the meal mode running now, or null
     * @param modeStartMs         when it started
     * @param episodeStartMs      start of the meal tracker's episode now, 0 when there is none
     * @param episodeGrams        grams the tracker has seen in that episode so far
     * @param lowActive           BG is low, or in the rebound after a low
     * @param undershootActive    BG is just above the low guard
     * @param contaminated        a second helping, exercise, the new pod boost or entered carbs: not learned from
     */
    fun onCycle(
        nowMs: Long,
        activeMode: MealMode?,
        modeStartMs: Long,
        episodeStartMs: Long,
        episodeGrams: Double,
        bgMgdl: Double,
        lowActive: Boolean,
        undershootActive: Boolean,
        contaminated: Boolean
    ) {
        val done = synchronized(this) {
            for (w in watches) {
                if (nowMs < w.startMs || w.dropReason.isNotEmpty()) continue
                val inWindow = nowMs <= w.windowEndMs
                if (inWindow) {
                    watchMode(w, nowMs, activeMode, modeStartMs)
                    if (contaminated && w.dropReason.isEmpty()) w.dropReason = "second helping, exercise, pod boost or carbs"
                    if (w.dropReason.isNotEmpty()) continue
                    countGrams(w, nowMs, episodeStartMs, episodeGrams)
                    noteRise(w, nowMs, bgMgdl)
                }
                if (nowMs <= w.windowEndMs + LOW_WATCH_MS) {
                    val carbPart = nowMs - w.startMs < CARB_PHASE_MS
                    when {
                        lowActive && carbPart        -> w.carbLow = true
                        lowActive                    -> w.fpLow = true
                        undershootActive && carbPart -> w.carbUnder = true
                        undershootActive             -> w.fpUnder = true
                    }
                }
            }
            watches.filter { nowMs > it.windowEndMs + LOW_WATCH_MS }.also { watches.removeAll(it) }
        }
        done.forEach { judge(it) }
        save()
    }

    /** The meal must still be this mode, or the Protein/Fat that follows it. */
    private fun watchMode(w: Watch, nowMs: Long, activeMode: MealMode?, modeStartMs: Long) {
        val beforeEnd = nowMs < w.modeEndMs - END_SLACK_MS
        val ours = activeMode != null && modeStartMs == w.startMs
        when {
            ours                                                  -> Unit
            beforeEnd && activeMode == null                       -> w.dropReason = "the mode was ended early"
            beforeEnd                                             -> w.dropReason = "another mode replaced it"
            activeMode == null || activeMode == MealMode.UAM_PROTEIN_FAT -> Unit
            // A new meal after this one ended: stop counting here and judge what was seen.
            else                                                  -> w.windowEndMs = nowMs
        }
    }

    private fun countGrams(w: Watch, nowMs: Long, episodeStartMs: Long, episodeGrams: Double) {
        // An episode from before this meal (the tracker still closing it) is not ours.
        if (episodeStartMs == 0L || episodeStartMs < w.startMs - MATCH_EARLY_MS) return
        val added = if (episodeStartMs == w.episodeStartMs) episodeGrams - w.episodeGrams else episodeGrams
        w.episodeStartMs = episodeStartMs
        w.episodeGrams = episodeGrams
        if (added <= 0.0) return
        if (nowMs - w.startMs < CARB_PHASE_MS) w.carbSeenG += added else w.fpSeenG += added
    }

    private fun noteRise(w: Watch, nowMs: Long, bgMgdl: Double) {
        if (bgMgdl <= 0.0) return
        if (w.startBgMgdl <= 0.0) w.startBgMgdl = bgMgdl
        if (w.riseAfterMs < 0L && nowMs - w.startMs <= RISE_LOOK_MS && bgMgdl >= w.startBgMgdl + RISE_MGDL) w.riseAfterMs = nowMs - w.startMs
    }

    private fun judge(w: Watch) {
        if (w.dropReason.isNotEmpty()) {
            note("${w.name}: not learned from, ${w.dropReason}")
            return
        }
        val text = synchronized(this) {
            val s = states.getOrPut(w.name) { State() }
            val anyLow = w.carbLow || w.fpLow
            // Without fat and protein to blame, a late low is the carbs' too.
            val hasFp = w.fatProteinG >= MIN_PART_G
            val carbLow = w.carbLow || (!hasFp && w.fpLow)
            val carbUnder = w.carbUnder || (!hasFp && w.fpUnder)
            val carbBefore = s.carbFactor
            val fpBefore = s.fatProteinFactor
            if (w.carbsG >= MIN_PART_G) s.carbFactor = step(s.carbFactor, w.carbSeenG / w.carbsG, carbLow, carbUnder, anyLow)
            if (hasFp) s.fatProteinFactor = step(s.fatProteinFactor, w.fpSeenG / w.fatProteinG, w.fpLow, w.fpUnder, anyLow)
            s.meals++
            if (w.riseAfterMs >= 0L) {
                s.soon.add(w.riseAfterMs <= ATE_SOON_MS)
                while (s.soon.size > RECENT_MEALS) s.soon.removeAt(0)
            }
            "${w.name}: carbs ${"%.0f".format(w.carbSeenG)}/${"%.0f".format(w.carbsG)} g${if (w.carbLow) " low" else ""}, ×${"%.2f".format(carbBefore)}→${"%.2f".format(s.carbFactor)}; " +
                "fat/protein ${"%.0f".format(w.fpSeenG)}/${"%.0f".format(w.fatProteinG)} g${if (w.fpLow) " low" else ""}, ×${"%.2f".format(fpBefore)}→${"%.2f".format(s.fatProteinFactor)} (${s.meals} meals)"
        }
        note(text)
    }

    private fun note(text: String) {
        aapsLogger.debug(LTag.APS, "SavedMealLearning: $text")
        onOutcome?.invoke(text)
    }

    /** Forgets everything learned. */
    fun reset() {
        synchronized(this) { states.clear(); watches.clear() }
        save()
    }

    private fun copyWatch(w: Watch, name: String) = Watch(
        name, w.startMs, w.modeEndMs, w.windowEndMs, w.carbsG, w.fatProteinG, w.carbSeenG, w.fpSeenG, w.episodeStartMs, w.episodeGrams,
        w.startBgMgdl, w.riseAfterMs, w.carbLow, w.fpLow, w.carbUnder, w.fpUnder, w.dropReason
    )

    private fun save() {
        val json = synchronized(this) {
            JSONObject()
                .put(K_MEALS, JSONObject().apply {
                    states.forEach { (name, s) ->
                        put(name, JSONObject().put("c", s.carbFactor).put("f", s.fatProteinFactor).put("n", s.meals).put("soon", JSONArray(s.soon)))
                    }
                })
                .put(K_WATCHES, JSONArray().apply {
                    watches.forEach { w ->
                        put(JSONObject()
                                .put("name", w.name).put("s", w.startMs).put("me", w.modeEndMs).put("e", w.windowEndMs)
                                .put("c", w.carbsG).put("f", w.fatProteinG).put("cs", w.carbSeenG).put("fs", w.fpSeenG)
                                .put("es", w.episodeStartMs).put("eg", w.episodeGrams).put("bg", w.startBgMgdl).put("rise", w.riseAfterMs)
                                .put("cl", w.carbLow).put("fl", w.fpLow).put("cu", w.carbUnder).put("fu", w.fpUnder).put("drop", w.dropReason))
                    }
                })
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinSavedMealLearning.key, json.toString()) }
    }

    private fun load() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinSavedMealLearning.key, "")
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.optJSONObject(K_MEALS)?.let { meals ->
                meals.keys().forEach { name ->
                    val o = meals.getJSONObject(name)
                    val soon = mutableListOf<Boolean>()
                    o.optJSONArray("soon")?.let { a -> for (i in 0 until a.length()) soon.add(a.getBoolean(i)) }
                    states[name] = State(o.getDouble("c"), o.getDouble("f"), o.getInt("n"), soon)
                }
            }
            json.optJSONArray(K_WATCHES)?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    watches.add(
                        Watch(
                            o.getString("name"), o.getLong("s"), o.getLong("me"), o.getLong("e"), o.getDouble("c"), o.getDouble("f"),
                            o.optDouble("cs", 0.0), o.optDouble("fs", 0.0), o.optLong("es", 0L), o.optDouble("eg", 0.0),
                            o.optDouble("bg", 0.0), o.optLong("rise", -1L), o.optBoolean("cl"), o.optBoolean("fl"),
                            o.optBoolean("cu"), o.optBoolean("fu"), o.optString("drop", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SavedMealLearning: could not read saved state: ${e.message}")
            states.clear(); watches.clear()
        }
    }

    companion object {

        /** The first part of a meal counts as its carbs; what comes after, as its fat and protein. */
        const val CARB_PHASE_MS = 120 * 60_000L
        /** Lows are still charged to the meal this long after its window. */
        const val LOW_WATCH_MS = 60 * 60_000L
        const val LOW_STEP = 0.15
        const val UNDERSHOOT_STEP = 0.07
        /** The most one meal can move a factor, up or down, without a low. */
        const val MAX_STEP = 0.12
        const val MIN_FACTOR = 0.6
        const val MAX_FACTOR = 1.5
        /** A part of the meal smaller than this is too noisy to learn from. */
        const val MIN_PART_G = 10.0
        /** A rise this soon after the start means the meal was eaten without waiting. */
        const val ATE_SOON_MS = 15 * 60_000L
        /** How much BG has to go up to count as the start of the rise, and how long to look for it. */
        const val RISE_MGDL = 18.0
        const val RISE_LOOK_MS = 60 * 60_000L
        const val RECENT_MEALS = 5
        /** A mode that stops this close to its planned end has not been ended early. */
        const val END_SLACK_MS = 10 * 60_000L
        const val MATCH_EARLY_MS = 60_000L
        private const val MAX_WATCHES = 5
        private const val K_MEALS = "meals"
        private const val K_WATCHES = "watches"

        /**
         * One bounded step. A low cuts; without one the factor moves toward what was seen. After a low
         * anywhere in the meal, nothing grows.
         */
        fun step(factor: Double, seenRatio: Double, low: Boolean, undershoot: Boolean, anyLow: Boolean): Double {
            val next = when {
                low        -> factor * (1.0 - LOW_STEP)
                undershoot -> factor * (1.0 - UNDERSHOOT_STEP)
                else       -> {
                    val wanted = seenRatio.coerceIn(factor * (1.0 - MAX_STEP), factor * (1.0 + MAX_STEP))
                    if (anyLow) minOf(wanted, factor) else wanted
                }
            }
            return next.coerceIn(MIN_FACTOR, MAX_FACTOR)
        }
    }
}
