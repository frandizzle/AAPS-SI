package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive aggressiveness learner.
 *
 * Maintains TWO sample sets:
 *
 *  - [fastingSamples] — fasting-only samples. Drives score calculation + fasting TIR display.
 *  - [mealSamples]    — meal-mode samples. Display TIR only — post-meal highs don't affect scoring.
 *  - [allSamples]     — all modes combined. Used only for pruning/persistence, not displayed.
 *
 * Why separate? A post-dinner spike at 6pm looks like "too much time high" to a naive
 * TIR-based score, pushing aggressiveness up. Next day at 6pm (pre-dinner, still fasting)
 * that inflated score causes over-dosing. By scoring only on fasting samples, meal-related
 * highs don't pollute the aggressiveness signal. Meal bolus tuning belongs in BolusCurveTracker.
 *
 * Score range: [1/aggressionMax .. aggressionMax]
 *   - 1.0 = neutral
 *   - > 1.0 = more aggressive — fasting BG spending too much time high
 *   - < 1.0 = more conservative — fasting BG spending too much time low
 *
 * Learning is asymmetric: lows pull score down faster than highs push it up.
 */
@Singleton
class AggressionLearner @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger:  AAPSLogger
) {
    private data class BgSample(val timestampMs: Long, val zone: Zone, val fasting: Boolean)

    private enum class Zone { LOW, IN_RANGE, HIGH }

    // ── State ─────────────────────────────────────────────────────────────────
    // allSamples is used ONLY for persistence — on save it's serialised, on restore
    // it rebuilds fastingSamples and mealSamples from the fasting flag. No score or
    // TIR is ever computed from allSamples directly.
    private val allSamples     = ArrayDeque<BgSample>()
    private val fastingSamples = ArrayDeque<BgSample>()  // fasting only — drives score + display
    private val mealSamples    = ArrayDeque<BgSample>()  // meal modes only — display TIR only

    // Day-of-week aware scores: [0=Sun..6=Sat] + global fallback
    private val dayScores      = DoubleArray(7) { 1.0 }
    private val daySampleCount = IntArray(7) { 0 }
    private var globalScore    = 1.0
    private var lastUpdateMs   = 0L

    init { restoreState() }

    companion object {
        private const val WINDOW_MS            = 24 * 60 * 60 * 1000L
        private const val UPDATE_INTERVAL_MS   = 60 * 60 * 1000L
        private const val MIN_SAMPLES_TO_LEARN = 24   // ~2h of fasting data

        private const val TARGET_TIR_PCT       = 70.0
        private const val MAX_LOW_PCT          = 4.0
        private const val MAX_HIGH_PCT         = 26.0

        private const val STEP_UP              = 0.02
        private const val STEP_DOWN            = 0.05
        private const val MIN_DAY_SAMPLES_FOR_BLEND = 20  // samples on a given day before blending in
        val DAY_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        private const val K_SCORE        = "score"
        private const val K_LAST_UPDATE  = "lastUpdateMs"
        private const val K_SAMPLES      = "samples"
        private const val K_TS           = "ts"
        private const val K_ZONE         = "zone"
        private const val K_FASTING      = "fasting"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    val aggressiveness: Double
        get() {
            val max   = preferences.get(DoubleKey.ApsSmartInsulinAggressionMax)
            val dow   = currentDow()
            val blend = (daySampleCount[dow].toDouble() / MIN_DAY_SAMPLES_FOR_BLEND).coerceIn(0.0, 1.0)
            val score = globalScore * (1.0 - blend) + dayScores[dow] * blend
            return score.coerceIn(1.0 / max, max)
        }

    /**
     * TIR summary shows fasting and meal TIR separately for display.
     * Fasting TIR also drives the aggressiveness score.
     * Meal TIR is display-only — post-meal highs are expected and don't affect scoring.
     */
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

    /**
     * Record current BG zone.
     * @param mealMode         Current meal mode — non-fasting samples excluded from score.
     * @param suppressScoring  If true, sample is recorded for TIR display but does not
     *                         update the aggressiveness score. Use during activity or other
     *                         events where lows/highs are not caused by insulin dosing.
     */
    fun recordBg(bgMgdl: Double, lowThreshMgdl: Double, highThreshMgdl: Double, mealMode: MealMode, suppressScoring: Boolean = false) {
        val zone = when {
            bgMgdl < lowThreshMgdl  -> Zone.LOW
            bgMgdl > highThreshMgdl -> Zone.HIGH
            else                    -> Zone.IN_RANGE
        }
        val nowMs   = System.currentTimeMillis()
        val isFasting = mealMode == MealMode.FASTING
        val sample  = BgSample(nowMs, zone, isFasting)

        allSamples.addLast(sample)
        if (isFasting) fastingSamples.addLast(sample)
        else mealSamples.addLast(sample)

        pruneOldSamples(nowMs)

        if (!suppressScoring && nowMs - lastUpdateMs >= UPDATE_INTERVAL_MS) {
            updateScore()
            lastUpdateMs = nowMs
            saveState()
        }
    }

    fun recalculate() {
        updateScore()
        saveState()
    }

    // ── Score update — fasting samples only ───────────────────────────────────

    /** Shared step logic for both global and day-of-week score updates */
    private fun stepScore(current: Double, stats: TirStats, floor: Double, ceil: Double): Double = when {
        stats.lowPct > MAX_LOW_PCT                              -> (current - STEP_DOWN).coerceAtLeast(floor)
        stats.inRangePct >= TARGET_TIR_PCT && stats.highPct > 0 -> (current + STEP_UP).coerceAtMost(ceil)
        stats.highPct > MAX_HIGH_PCT                            -> (current + STEP_UP * 1.5).coerceAtMost(ceil)
        else                                                    -> current + (1.0 - current) * 0.05
    }

    private fun updateScore() {
        val stats = computeTir(fastingSamples) ?: run {
            aapsLogger.debug(LTag.APS, "AggressionLearner: insufficient fasting samples (${fastingSamples.size}/$MIN_SAMPLES_TO_LEARN), global held at $globalScore")
            return
        }
        val max   = preferences.get(DoubleKey.ApsSmartInsulinAggressionMax)
        val floor = 1.0 / max
        val ceil  = max
        val dow   = currentDow()

        // Update global score — uses all fasting samples regardless of day
        val prevGlobal = globalScore
        globalScore = stepScore(globalScore, stats, floor, ceil)

        // Update today's day score — uses only today's fasting samples
        // Precompute day boundaries to avoid Calendar allocation per sample
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L
        val todayStats = computeTir(ArrayDeque(fastingSamples.filter { isSameDay(it.timestampMs, dayStart, dayEnd) }))
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

    /** True if sample's day-of-week matches [dow] — uses precomputed boundaries to avoid Calendar allocation per sample */
    private fun isSameDay(timestampMs: Long, dayStartMs: Long, dayEndMs: Long): Boolean =
        timestampMs in dayStartMs until dayEndMs

    private fun currentDow(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

    // ── TIR calculation ───────────────────────────────────────────────────────

    private data class TirStats(val inRangePct: Double, val highPct: Double, val lowPct: Double)

    private fun computeTir(sampleSet: ArrayDeque<BgSample>): TirStats? {
        if (sampleSet.size < MIN_SAMPLES_TO_LEARN) return null
        val total   = sampleSet.size.toDouble()
        val inRange = sampleSet.count { it.zone == Zone.IN_RANGE }
        val high    = sampleSet.count { it.zone == Zone.HIGH }
        val low     = sampleSet.count { it.zone == Zone.LOW }
        return TirStats(inRange / total * 100.0, high / total * 100.0, low / total * 100.0)
    }

    private fun pruneOldSamples(nowMs: Long) {
        while (allSamples.isNotEmpty()     && nowMs - allSamples.first().timestampMs     > WINDOW_MS) allSamples.removeFirst()
        while (fastingSamples.isNotEmpty() && nowMs - fastingSamples.first().timestampMs > WINDOW_MS) fastingSamples.removeFirst()
        while (mealSamples.isNotEmpty()    && nowMs - mealSamples.first().timestampMs    > WINDOW_MS) mealSamples.removeFirst()
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset() {
        allSamples.clear()
        fastingSamples.clear()
        mealSamples.clear()
        for (i in 0..6) { dayScores[i] = 1.0; daySampleCount[i] = 0 }
        globalScore  = 1.0
        lastUpdateMs = 0L
        preferences.put(StringKey.ApsSmartInsulinAggressionState, "")
        aapsLogger.debug(LTag.APS, "AggressionLearner: reset to 1.0")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveState() {
        try {
            val arr    = JSONArray()
            // Save all samples (capped at 288 = 24h at 5 min intervals)
            val toSave = if (allSamples.size > 288) allSamples.takeLast(288) else allSamples
            toSave.forEach { s ->
                arr.put(JSONObject().put(K_TS, s.timestampMs).put(K_ZONE, s.zone.name).put(K_FASTING, s.fasting))
            }
            val dayArr = org.json.JSONArray()
            for (i in 0..6) dayArr.put(JSONObject().put("score", dayScores[i]).put("n", daySampleCount[i]))
            preferences.put(
                StringKey.ApsSmartInsulinAggressionState,
                JSONObject().put(K_SCORE, globalScore).put(K_LAST_UPDATE, lastUpdateMs)
                    .put(K_SAMPLES, arr).put("dayScores", dayArr).toString()
            )
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "AggressionLearner: save failed: ${e.message}")
        }
    }

    private fun restoreState() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinAggressionState)
            if (raw.isBlank()) return
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
                val isFasting = obj.optBoolean(K_FASTING, true)  // legacy: assume fasting if missing
                val sample   = BgSample(ts, zone, isFasting)
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