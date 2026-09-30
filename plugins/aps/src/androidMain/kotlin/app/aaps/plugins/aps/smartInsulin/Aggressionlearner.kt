package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive aggressiveness learner.
 */
@Singleton
class AggressionLearner @Inject constructor(
    private val sp:          SP,
    private val aapsLogger:  AAPSLogger
) {
    /**
     * @param scorable false when the sample was taken while learning was suppressed — post-meal
     *   lockout, CGM warmup, or a detected activity window. Such samples are still kept for
     *   display and history but are excluded from every TIR calculation that moves a score.
     */
    private data class BgSample(
        val timestampMs: Long,
        val zone:        Zone,
        val fasting:     Boolean,
        val scorable:    Boolean = true
    )
    private enum class Zone { LOW, IN_RANGE, HIGH }

    // ── State ─────────────────────────────────────────────────────────────────
    private val allSamples     = ArrayDeque<BgSample>()
    private val fastingSamples = ArrayDeque<BgSample>()
    private val mealSamples    = ArrayDeque<BgSample>()

    private val dayScores      = DoubleArray(7) { 1.0 }
    private val daySampleCount = IntArray(7) { 0 }
    private var globalScore    = 1.0
    private var lastUpdateMs   = 0L
    private var lastSaveMs     = 0L

    init { restoreState() }

    companion object {
        private const val WINDOW_MS            = 24 * 60 * 60 * 1000L
        private const val UPDATE_INTERVAL_MS   = 60 * 60 * 1000L
        // Independent of UPDATE_INTERVAL_MS: the 24h sample window is worth persisting far more
        // often than the score is worth recomputing.
        private const val SAVE_INTERVAL_MS     = 15 * 60 * 1000L
        private const val MIN_SAMPLES_TO_LEARN = 24

        private const val TARGET_TIR_PCT       = 70.0
        private const val MAX_LOW_PCT          = 4.0
        private const val MAX_HIGH_PCT         = 26.0

        private const val STEP_UP              = 0.02
        private const val STEP_DOWN            = 0.05
        private const val MIN_DAY_SAMPLES_FOR_BLEND = 20
        val DAY_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        private const val K_SCORE        = "score"
        private const val K_LAST_UPDATE  = "lastUpdateMs"
        private const val K_SAMPLES      = "samples"
        private const val K_TS           = "ts"
        private const val K_ZONE         = "zone"
        private const val K_FASTING      = "fasting"
        private const val K_SCORABLE     = "scorable"
    }

    val aggressiveness: Double
        get() {
            val max   = sp.getDouble(DoubleKey.ApsSmartInsulinAggressionMax.key, DoubleKey.ApsSmartInsulinAggressionMax.defaultValue)
            val dow   = currentDow()
            val blend = (daySampleCount[dow].toDouble() / MIN_DAY_SAMPLES_FOR_BLEND).coerceIn(0.0, 1.0)
            val score = globalScore * (1.0 - blend) + dayScores[dow] * blend
            return score.coerceIn(1.0 / max, max)
        }

    val tirSummary: String
        get() {
            val fasting = computeTir(fastingSamples)
            val meal    = computeTir(mealSamples)
            val fastStr = fasting?.let { "Fasting:${it.inRangePct.toInt()}%in/${it.highPct.toInt()}%hi/${it.lowPct.toInt()}%lo" }
                ?: "Fasting:insufficient"
            val mealStr = meal?.let { "Meal:${it.inRangePct.toInt()}%in/${it.highPct.toInt()}%hi/${it.lowPct.toInt()}%lo" }
                ?: "Meal:insufficient"
            return "tir=$fastStr $mealStr global=${"%.2f".format(globalScore)} today=${"%.2f".format(dayScores[currentDow()])}"
        }

    fun recordBg(
        bgMgdl: Double,
        lowThreshMgdl: Double,
        highThreshMgdl: Double,
        mealMode: MealMode,
        suppressScoring: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val zone = when {
            bgMgdl < lowThreshMgdl  -> Zone.LOW
            bgMgdl > highThreshMgdl -> Zone.HIGH
            else                    -> Zone.IN_RANGE
        }
        val isFasting = mealMode == MealMode.FASTING
        // suppressScoring has to travel WITH the sample, not just skip this cycle's scoring pass.
        // The post-meal lockout sets mealMode back to FASTING while a meal's tail is still
        // raising BG, so those readings were landing in fastingSamples permanently and the next
        // hourly pass scored them as fasting highs — pushing aggressiveness UP on exactly the
        // data the lockout exists to keep out. Tagging the sample instead means the lockout
        // actually excludes it for the full 24h the window holds it.
        val sample  = BgSample(nowMs, zone, isFasting, scorable = !suppressScoring)

        allSamples.addLast(sample)
        if (isFasting) fastingSamples.addLast(sample)
        else mealSamples.addLast(sample)

        pruneOldSamples(nowMs)

        if (!suppressScoring && nowMs - lastUpdateMs >= UPDATE_INTERVAL_MS) {
            updateScore()
            lastUpdateMs = nowMs
            lastSaveMs   = nowMs
            saveState()
        } else if (nowMs - lastSaveMs >= SAVE_INTERVAL_MS) {
            // Samples used to reach disk only when a score update fired (hourly), so a restart
            // discarded up to an hour of the 24h window. Persist on a shorter cadence of its own.
            lastSaveMs = nowMs
            saveState()
        }
    }

    fun recalculate() {
        updateScore()
        saveState()
    }

    private data class TirStats(val inRangePct: Double, val highPct: Double, val lowPct: Double)

    private fun stepScore(current: Double, stats: TirStats, floor: Double, ceil: Double): Double = when {
        stats.lowPct > MAX_LOW_PCT                              -> (current - STEP_DOWN).coerceAtLeast(floor)
        stats.inRangePct >= TARGET_TIR_PCT && stats.highPct > 0 -> (current + STEP_UP).coerceAtMost(ceil)
        stats.highPct > MAX_HIGH_PCT                            -> (current + STEP_UP * 1.5).coerceAtMost(ceil)
        else                                                    -> {
            val decayAlpha = if (current < 1.0) 0.10 else 0.03
            current + (1.0 - current) * decayAlpha
        }
    }

    private fun updateScore() {
        val stats = computeTir(fastingSamples) ?: run {
            aapsLogger.debug(LTag.APS, "AggressionLearner: insufficient fasting samples (${fastingSamples.size}/$MIN_SAMPLES_TO_LEARN), global held at $globalScore")
            return
        }
        val max   = sp.getDouble(DoubleKey.ApsSmartInsulinAggressionMax.key, DoubleKey.ApsSmartInsulinAggressionMax.defaultValue)
        val floor = 1.0 / max
        val ceil  = max
        val dow   = currentDow()

        val prevGlobal = globalScore
        globalScore = stepScore(globalScore, stats, floor, ceil)

        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L
        val todayStats = computeTir(fastingSamples.filter { isSameDay(it.timestampMs, dayStart, dayEnd) })
        if (todayStats != null) {
            val prev = dayScores[dow]
            dayScores[dow] = stepScore(dayScores[dow], todayStats, floor, ceil)
            daySampleCount[dow] = (daySampleCount[dow] + 1).coerceAtMost(999)
            if (dayScores[dow] != prev)
                aapsLogger.debug(LTag.APS,
                                 "AggressionLearner: day[${DAY_LABELS[dow]}] score %.3f→%.3f tir=%.0f%% high=%.0f%% low=%.0f%% (n=${daySampleCount[dow]})".format(
                                     prev, dayScores[dow], todayStats.inRangePct, todayStats.highPct, todayStats.lowPct))
        }

        if (globalScore != prevGlobal)
            aapsLogger.debug(LTag.APS,
                             "AggressionLearner: global score %.3f→%.3f fasting tir=%.0f%% high=%.0f%% low=%.0f%% (n=${fastingSamples.size})".format(
                                 prevGlobal, globalScore, stats.inRangePct, stats.highPct, stats.lowPct))
    }

    private fun isSameDay(timestampMs: Long, dayStartMs: Long, dayEndMs: Long): Boolean =
        timestampMs in dayStartMs until dayEndMs

    private fun currentDow(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

    /** TIR over the scorable samples only — see [BgSample.scorable]. */
    private fun computeTir(sampleSet: Collection<BgSample>): TirStats? {
        val scored  = sampleSet.filter { it.scorable }
        if (scored.size < MIN_SAMPLES_TO_LEARN) return null
        val total   = scored.size.toDouble()
        val inRange = scored.count { it.zone == Zone.IN_RANGE }
        val high    = scored.count { it.zone == Zone.HIGH }
        val low     = scored.count { it.zone == Zone.LOW }
        return TirStats(inRange / total * 100.0, high / total * 100.0, low / total * 100.0)
    }

    private fun pruneOldSamples(nowMs: Long) {
        while (allSamples.isNotEmpty()     && nowMs - allSamples.first().timestampMs     > WINDOW_MS) allSamples.removeFirst()
        while (fastingSamples.isNotEmpty() && nowMs - fastingSamples.first().timestampMs > WINDOW_MS) fastingSamples.removeFirst()
        while (mealSamples.isNotEmpty()    && nowMs - mealSamples.first().timestampMs    > WINDOW_MS) mealSamples.removeFirst()
    }

    fun reset() {
        allSamples.clear()
        fastingSamples.clear()
        mealSamples.clear()
        for (i in 0..6) { dayScores[i] = 1.0; daySampleCount[i] = 0 }
        globalScore  = 1.0
        lastUpdateMs = 0L
        sp.edit { putString(StringKey.ApsSmartInsulinAggressionState.key, "") }
        aapsLogger.debug(LTag.APS, "AggressionLearner: reset to 1.0")
    }

    private fun saveState() {
        try {
            val arr    = JSONArray()
            val toSave = if (allSamples.size > 288) allSamples.takeLast(288) else allSamples
            toSave.forEach { s ->
                arr.put(JSONObject().put(K_TS, s.timestampMs).put(K_ZONE, s.zone.name).put(K_FASTING, s.fasting).put(K_SCORABLE, s.scorable))
            }
            val dayArr = org.json.JSONArray()
            for (i in 0..6) dayArr.put(JSONObject().put("score", dayScores[i]).put("n", daySampleCount[i]))
            sp.edit {
                putString(
                    StringKey.ApsSmartInsulinAggressionState.key,
                    JSONObject().put(K_SCORE, globalScore).put(K_LAST_UPDATE, lastUpdateMs)
                        .put(K_SAMPLES, arr).put("dayScores", dayArr).toString()
                )
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "AggressionLearner: save failed: ${e.message}")
        }
    }

    private fun restoreState() {
        try {
            val raw = sp.getString(StringKey.ApsSmartInsulinAggressionState.key, StringKey.ApsSmartInsulinAggressionState.defaultValue)
            if (raw.isNullOrBlank()) return
            val json     = JSONObject(raw)
            globalScore  = json.optDouble(K_SCORE, 1.0)
            lastUpdateMs = json.optLong(K_LAST_UPDATE, 0L)
            val dayArr   = json.optJSONArray("dayScores")
            if (dayArr != null) {
                for (i in 0..6) {
                    val obj = dayArr.optJSONObject(i) ?: continue
                    dayScores[i]      = obj.optDouble("score", 1.0)
                    daySampleCount[i] = obj.optInt("n", 0)
                }
            }
            val arr      = json.optJSONArray(K_SAMPLES) ?: return
            val nowMs    = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val obj      = arr.getJSONObject(i)
                val ts       = obj.getLong(K_TS)
                if (nowMs - ts > WINDOW_MS) continue
                val zone     = Zone.valueOf(obj.getString(K_ZONE))
                val isFasting = obj.optBoolean(K_FASTING, true)
                // Absent in state written before scorable existed — those samples were already
                // being scored, so defaulting to true preserves the old behaviour on upgrade.
                val scorable = obj.optBoolean(K_SCORABLE, true)
                val sample   = BgSample(ts, zone, isFasting, scorable)
                allSamples.addLast(sample)
                if (isFasting) fastingSamples.addLast(sample)
                else mealSamples.addLast(sample)
            }
            aapsLogger.debug(LTag.APS,
                             "AggressionLearner: restored globalScore=$globalScore all=${allSamples.size} fasting=${fastingSamples.size} meal=${mealSamples.size}")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "AggressionLearner: restore failed: ${e.message}")
            globalScore = 1.0
        }
    }
}