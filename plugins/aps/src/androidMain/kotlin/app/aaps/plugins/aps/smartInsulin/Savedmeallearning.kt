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
 * Learns how much insulin each part of a meal needs: how much food BG showed compared with what the meal was
 * planned with, in three parts that match the three pre-boluses:
 *  - [Part.CARBS], the first [CARB_PHASE_MS]: pre-bolus 1
 *  - [Part.WAVE], the fat and protein wave after that: pre-bolus 2
 *  - [Part.TAIL], from [TAIL_AFTER_PB3_MS] after pre-bolus 3 is due: pre-bolus 3. Only a meal with a pre-bolus 3 has a tail.
 *
 * Every meal planned from food is watched. A saved meal (an entry in My foods) learns its own factors, one
 * bounded step per meal. Every meal also adds to the general factors, the median of the last [GENERAL_MEALS]
 * meals, for meals that have learned nothing of their own.
 *
 * The grams come from [MealAbsorptionTracker], the same as for `MealEstimateCalibrationImpl`, so the factors are
 * in the same unit as the plan: "this part acts like this many times what was planned".
 *
 * Rules for a saved meal:
 *  - A low in a part cuts that part by [LOW_STEP]; BG just above the low guard by [UNDERSHOOT_STEP]. A part too
 *    small to learn from passes its low on to the part before it.
 *  - Without a low, each part moves toward what BG showed, by at most [MAX_STEP]. After a low anywhere in the
 *    meal, nothing grows.
 *  - A meal that got other food, ended early, was replaced, had a second helping, exercise or the new pod boost
 *    is not learned from.
 *
 * A saved meal also learns its timing, from the last [RECENT_MEALS] meals:
 *  - when BG starts to rise after pre-bolus 1, to suggest how long to wait before eating;
 *  - when its fat and protein come in: the times by which a quarter, half and three quarters of the food after
 *    the first two hours had been absorbed. The dialog times pre-bolus 2 and 3 from these, so their insulin
 *    works when the food does. The timing does not change the factors.
 */
@SingleIn(AppScope::class)
class SavedMealLearningImpl @Inject constructor(
    private val sp:         KeyValueStore,
    private val aapsLogger: AAPSLogger
) : SavedMealLearning, UndoableLearner {

    enum class Part { CARBS, WAVE, TAIL }

    private class State(
        val factors: DoubleArray = DoubleArray(PARTS) { 1.0 },
        var meals: Int = 0,
        /** Minutes from the start until BG began to rise. */
        val rises: MutableList<Int> = mutableListOf(),
        /** Minutes from the start by which a quarter, half and three quarters of the fat and protein had come in. */
        val fpTimes: Array<MutableList<Int>> = Array(QUANTILES.size) { mutableListOf() }
    )

    private class Watch(
        val name:        String?,
        val startMs:     Long,
        val modeEndMs:   Long,
        var windowEndMs: Long,
        /** When the tail starts, 0 when the meal has no pre-bolus 3. */
        val tailFromMs:  Long,
        val planned:     DoubleArray,
        val seen:        DoubleArray = DoubleArray(PARTS),
        val low:         BooleanArray = BooleanArray(PARTS),
        val under:       BooleanArray = BooleanArray(PARTS),
        var episodeStartMs: Long = 0L,
        var episodeGrams: Double = 0.0,
        var startBgMgdl: Double = 0.0,
        var riseAfterMs: Long = -1L,
        var dropReason:  String = "",
        /** Grams seen after the carb part, in [BIN_MS] steps from the start, for the timing. */
        val bins:        DoubleArray = DoubleArray(MAX_BINS)
    )

    private val states = mutableMapOf<String, State>()
    private val watches = mutableListOf<Watch>()
    /** Seen ÷ planned of the last meals, one list for each part. */
    private val general = Array(PARTS) { mutableListOf<Double>() }

    /** Called with one line for the learning journal each time a meal is judged. */
    var onOutcome: ((String) -> Unit)? = null

    init {
        load()
    }

    override fun learned(name: String): LearnedMeal? = synchronized(this) {
        states[name]?.let { s ->
            fun med(list: List<Int>) = if (list.isEmpty()) null else median(list.map { it.toDouble() }).toInt()
            LearnedMeal(
                s.factors[0], s.factors[1], s.factors[2], s.meals,
                riseMin = med(s.rises), fpQuarterMin = med(s.fpTimes[0]), fpHalfMin = med(s.fpTimes[1]), fpThreeQuarterMin = med(s.fpTimes[2])
            )
        }
    }

    override fun general(): LearnedMeal? = synchronized(this) {
        val meals = general.maxOf { it.size }
        if (meals == 0) null
        else LearnedMeal(generalFactor(0), generalFactor(1), generalFactor(2), meals, minMeals = GENERAL_MIN_MEALS)
    }

    /** 1.0 until the part has [GENERAL_MIN_MEALS] meals: a tail is only seen on heavy meals. */
    private fun generalFactor(part: Int): Double =
        if (general[part].size < GENERAL_MIN_MEALS) 1.0 else median(general[part]).coerceIn(GENERAL_MIN_FACTOR, GENERAL_MAX_FACTOR)

    override fun recordMeal(name: String?, modeStartMs: Long, modeEndMs: Long, windowEndMs: Long, pb3DueMs: Long, carbsG: Double, waveG: Double, tailG: Double) {
        synchronized(this) {
            watches.removeAll { it.startMs == modeStartMs }
            val tailFrom = if (pb3DueMs > 0L && tailG > 0.0) pb3DueMs + TAIL_AFTER_PB3_MS else 0L
            watches.add(Watch(name, modeStartMs, modeEndMs, windowEndMs, tailFrom, doubleArrayOf(carbsG, waveG, if (tailFrom > 0L) tailG else 0.0)))
            while (watches.size > MAX_WATCHES) watches.removeAt(0)
        }
        save()
        aapsLogger.debug(LTag.APS, "SavedMealLearning: watching ${name ?: "a meal"}")
    }

    override fun dropMeal(modeStartMs: Long) {
        val changed = synchronized(this) { watches.filter { it.startMs == modeStartMs && it.dropReason.isEmpty() }.onEach { it.dropReason = "other food was added" }.isNotEmpty() }
        if (changed) save()
    }

    override fun rename(name: String, newName: String) {
        if (name == newName) return
        synchronized(this) {
            states.remove(name)?.let { states[newName] = it }
            watches.replaceAll { w -> if (w.name == name) w.copyAs(newName) else w }
        }
        save()
    }

    override fun forget(name: String) {
        synchronized(this) {
            states.remove(name)
            // Still counts for the general factors, just not for the meal.
            watches.replaceAll { w -> if (w.name == name) w.copyAs(null) else w }
        }
        save()
    }

    /**
     * True while a saved meal is being watched at [nowMs]. The mode ISF and DURA learners then sit the episode
     * out, so a spike is not corrected by two learners. Meals that are not saved leave them learning: the general
     * factors move slowly, over many meals.
     */
    fun watching(nowMs: Long): Boolean = synchronized(this) { watches.any { it.name != null && nowMs >= it.startMs && nowMs <= it.windowEndMs + LOW_WATCH_MS } }

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
                if (nowMs <= w.windowEndMs) {
                    watchMode(w, nowMs, activeMode, modeStartMs)
                    if (contaminated && w.dropReason.isEmpty()) w.dropReason = "second helping, exercise, pod boost or carbs"
                    if (w.dropReason.isNotEmpty()) continue
                    countGrams(w, nowMs, episodeStartMs, episodeGrams)
                    noteRise(w, nowMs, bgMgdl)
                }
                if (nowMs <= w.windowEndMs + LOW_WATCH_MS) {
                    val part = partAt(w, nowMs)
                    if (lowActive) w.low[part] = true
                    else if (undershootActive) w.under[part] = true
                }
            }
            watches.filter { nowMs > it.windowEndMs + LOW_WATCH_MS }.also { watches.removeAll(it) }
        }
        done.forEach { judge(it) }
        save()
    }

    private fun partAt(w: Watch, nowMs: Long): Int = when {
        nowMs - w.startMs < CARB_PHASE_MS       -> Part.CARBS.ordinal
        w.tailFromMs > 0L && nowMs >= w.tailFromMs -> Part.TAIL.ordinal
        else                                    -> Part.WAVE.ordinal
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
        w.seen[partAt(w, nowMs)] += added
        val sinceStart = nowMs - w.startMs
        if (sinceStart >= CARB_PHASE_MS) w.bins[(sinceStart / BIN_MS).toInt().coerceAtMost(MAX_BINS - 1)] += added
    }

    private fun noteRise(w: Watch, nowMs: Long, bgMgdl: Double) {
        if (bgMgdl <= 0.0) return
        if (w.startBgMgdl <= 0.0) w.startBgMgdl = bgMgdl
        if (w.riseAfterMs < 0L && nowMs - w.startMs <= RISE_LOOK_MS && bgMgdl >= w.startBgMgdl + RISE_MGDL) w.riseAfterMs = nowMs - w.startMs
    }

    private fun judge(w: Watch) {
        val label = w.name ?: "Meal"
        if (w.dropReason.isNotEmpty()) {
            note("$label: not learned from, ${w.dropReason}")
            return
        }
        // A part too small to learn from passes its low on to the part before it.
        val low = w.low.copyOf()
        val under = w.under.copyOf()
        for (p in PARTS - 1 downTo 1) if (w.planned[p] < MIN_PART_G) {
            low[p - 1] = low[p - 1] || low[p]; under[p - 1] = under[p - 1] || under[p]
        }
        val anyLow = low.any { it }
        val text = synchronized(this) {
            val parts = mutableListOf<String>()
            for (p in 0 until PARTS) {
                if (w.planned[p] < MIN_PART_G) continue
                val ratio = (w.seen[p] / w.planned[p]).coerceIn(MIN_RATIO, MAX_RATIO)
                // General: a low counts as less than this part's factor now, so lows pull the median down.
                val now = generalFactor(p)
                general[p].add(if (low[p]) minOf(ratio, now * (1.0 - LOW_STEP)) else ratio)
                while (general[p].size > GENERAL_MEALS) general[p].removeAt(0)
                val name = PART_NAMES[p]
                val seen = "%.0f/%.0f g".format(w.seen[p], w.planned[p]) + if (low[p]) " low" else ""
                if (w.name != null) {
                    val s = states.getOrPut(w.name) { State() }
                    val before = s.factors[p]
                    s.factors[p] = step(before, ratio, low[p], under[p], anyLow)
                    parts += "$name $seen, ×${"%.2f".format(before)}→${"%.2f".format(s.factors[p])}"
                } else parts += "$name $seen"
            }
            w.name?.let { n ->
                val s = states.getOrPut(n) { State() }
                s.meals++
                if (w.riseAfterMs >= 0L) s.rises.addRecent((w.riseAfterMs / 60_000L).toInt())
                fpTimes(w.bins)?.let { times ->
                    times.forEachIndexed { i, t -> s.fpTimes[i].addRecent(t) }
                    parts += "fat and protein in by ${times.joinToString("/")} min"
                }
                parts += "${s.meals} meals"
            }
            "$label: ${parts.joinToString("; ")}"
        }
        note(text)
    }

    private fun MutableList<Int>.addRecent(v: Int) {
        add(v)
        while (size > RECENT_MEALS) removeAt(0)
    }

    /**
     * Minutes from the start by which each of [QUANTILES] of the fat and protein had come in, or null when too
     * little came in after the carb part to say.
     */
    private fun fpTimes(bins: DoubleArray): List<Int>? {
        val total = bins.sum()
        if (total < MIN_PART_G) return null
        return QUANTILES.map { q ->
            var sum = 0.0
            var at = bins.size - 1
            for (i in bins.indices) {
                sum += bins[i]
                if (sum >= q * total) { at = i; break }
            }
            (at * BIN_MS / 60_000L).toInt()
        }
    }

    private fun note(text: String) {
        aapsLogger.debug(LTag.APS, "SavedMealLearning: $text")
        onOutcome?.invoke(text)
    }

    override val undoName = "savedMeal"

    override fun exportState(): String {
        save()
        return synchronized(this) { learnedJson().toString() }
    }

    /** Puts back what was learned. Meals being watched now are dropped: they ate on the bad site too. */
    override fun importState(state: String) {
        synchronized(this) {
            states.clear(); watches.clear(); general.forEach { it.clear() }
            try {
                readLearned(JSONObject(state))
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "SavedMealLearning: could not read restored state: ${e.message}")
                states.clear(); general.forEach { it.clear() }
            }
        }
        save()
    }

    /** Forgets everything learned. */
    fun reset() {
        synchronized(this) { states.clear(); watches.clear(); general.forEach { it.clear() } }
        save()
    }

    private fun Watch.copyAs(name: String?) =
        Watch(name, startMs, modeEndMs, windowEndMs, tailFromMs, planned, seen, low, under, episodeStartMs, episodeGrams, startBgMgdl, riseAfterMs, dropReason, bins)

    private fun learnedJson() = JSONObject()
        .put(K_MEALS, JSONObject().apply {
            states.forEach { (name, s) ->
                put(name, JSONObject().put("f", JSONArray(s.factors.toList())).put("n", s.meals).put("rise", JSONArray(s.rises))
                    .put("fpt", JSONArray().apply { s.fpTimes.forEach { put(JSONArray(it)) } }))
            }
        })
        .put(K_GENERAL, JSONArray().apply { general.forEach { put(JSONArray(it)) } })

    private fun readLearned(json: JSONObject) {
        json.optJSONObject(K_MEALS)?.let { meals ->
            meals.keys().forEach { name ->
                val o = meals.getJSONObject(name)
                // Before the three parts, a meal kept "c" for the carbs and one "f" for all of the fat and protein.
                val factors = o.optJSONArray("f")?.let { f -> DoubleArray(PARTS) { if (it < f.length()) f.getDouble(it) else 1.0 } }
                    ?: o.optDouble("f", 1.0).let { fp -> doubleArrayOf(o.optDouble("c", 1.0), fp, fp) }
                fun ints(a: JSONArray?) = MutableList(a?.length() ?: 0) { a!!.getInt(it) }
                val fpt = o.optJSONArray("fpt")
                states[name] = State(factors, o.getInt("n"), ints(o.optJSONArray("rise")), Array(QUANTILES.size) { ints(fpt?.optJSONArray(it)) })
            }
        }
        json.optJSONArray(K_GENERAL)?.let { g ->
            for (p in 0 until minOf(PARTS, g.length())) {
                val a = g.getJSONArray(p)
                for (i in 0 until a.length()) general[p].add(a.getDouble(i))
            }
        }
    }

    private fun DoubleArray.json() = JSONArray(toList())
    private fun BooleanArray.json() = JSONArray(toList())
    private fun JSONArray.doubles() = DoubleArray(PARTS) { if (it < length()) getDouble(it) else 0.0 }
    private fun JSONArray.booleans() = BooleanArray(PARTS) { it < length() && getBoolean(it) }

    private fun save() {
        val json = synchronized(this) {
            learnedJson().put(K_WATCHES, JSONArray().apply {
                watches.forEach { w ->
                    put(JSONObject()
                            .put("name", w.name ?: JSONObject.NULL).put("s", w.startMs).put("me", w.modeEndMs).put("e", w.windowEndMs).put("tf", w.tailFromMs)
                            .put("p", w.planned.json()).put("seen", w.seen.json()).put("low", w.low.json()).put("under", w.under.json())
                            .put("es", w.episodeStartMs).put("eg", w.episodeGrams).put("bg", w.startBgMgdl).put("rise", w.riseAfterMs).put("drop", w.dropReason)
                            .put("bins", JSONArray(w.bins.toList())))
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
            readLearned(json)
            json.optJSONArray(K_WATCHES)?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    // A watch saved before the three parts existed is dropped; it is at most one meal.
                    val planned = o.optJSONArray("p") ?: continue
                    watches.add(
                        Watch(
                            if (o.isNull("name")) null else o.getString("name"), o.getLong("s"), o.getLong("me"), o.getLong("e"), o.optLong("tf", 0L),
                            planned.doubles(), o.getJSONArray("seen").doubles(), o.getJSONArray("low").booleans(), o.getJSONArray("under").booleans(),
                            o.optLong("es", 0L), o.optDouble("eg", 0.0), o.optDouble("bg", 0.0), o.optLong("rise", -1L), o.optString("drop", ""),
                            o.optJSONArray("bins")?.let { b -> DoubleArray(MAX_BINS) { if (it < b.length()) b.getDouble(it) else 0.0 } } ?: DoubleArray(MAX_BINS)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SavedMealLearning: could not read saved state: ${e.message}")
            states.clear(); watches.clear(); general.forEach { it.clear() }
        }
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    companion object {

        private val PARTS = Part.entries.size
        private val PART_NAMES = listOf("carbs", "wave", "tail")

        /** The first part of a meal counts as its carbs; what comes after, as its fat and protein. */
        const val CARB_PHASE_MS = 120 * 60_000L
        /** The tail starts when pre-bolus 3 has had about an hour to work. */
        const val TAIL_AFTER_PB3_MS = 60 * 60_000L
        /** Lows are still charged to the meal this long after its window. */
        const val LOW_WATCH_MS = 60 * 60_000L
        const val LOW_STEP = 0.15
        const val UNDERSHOOT_STEP = 0.07
        /** The most one meal can move a saved meal's factor, up or down, without a low. */
        const val MAX_STEP = 0.12
        const val MIN_FACTOR = 0.6
        const val MAX_FACTOR = 1.5
        /** One odd meal (half of it left, a site problem) may not count as more than this. */
        const val MIN_RATIO = 0.3
        const val MAX_RATIO = 3.0
        /** The general factors use the last this many meals, and only from this many. */
        const val GENERAL_MEALS = 20
        const val GENERAL_MIN_MEALS = 3
        const val GENERAL_MIN_FACTOR = 0.7
        const val GENERAL_MAX_FACTOR = 1.5
        /** A part of the meal smaller than this is too noisy to learn from. */
        const val MIN_PART_G = 10.0
        /** The fat and protein timing: when a quarter, half and three quarters of it had come in. */
        val QUANTILES = listOf(0.25, 0.5, 0.75)
        const val BIN_MS = 5 * 60_000L
        /** 12 hours of 5 minute steps; later food all counts in the last one. */
        const val MAX_BINS = 144
        /** How much BG has to go up to count as the start of the rise, and how long to look for it. */
        const val RISE_MGDL = 18.0
        const val RISE_LOOK_MS = 60 * 60_000L
        const val RECENT_MEALS = 5
        /** A mode that stops this close to its planned end has not been ended early. */
        const val END_SLACK_MS = 10 * 60_000L
        const val MATCH_EARLY_MS = 60_000L
        private const val MAX_WATCHES = 5
        private const val K_MEALS = "meals"
        private const val K_GENERAL = "general"
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
