package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive aggressiveness learner.
 *
 * Maintains TWO sample sets:
 *
 *  - [allSamples]     — full 24h rolling window, all modes. Used only for tirSummary display.
 *  - [fastingSamples] — fasting-only samples. Used exclusively for score calculation.
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
    private val allSamples     = ArrayDeque<BgSample>()  // all modes — display TIR only
    private val fastingSamples = ArrayDeque<BgSample>()  // fasting only — drives score
    private var score          = 1.0
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
            val max = preferences.get(DoubleKey.ApsSmartInsulinAggressionMax)
            return score.coerceIn(1.0 / max, max)
        }

    /**
     * TIR summary shows full 24h all-mode stats for display,
     * plus fasting-only stats so you can see the split.
     */
    val tirSummary: String
        get() {
            val all     = computeTir(allSamples)
            val fasting = computeTir(fastingSamples)
            val allStr  = all?.let { "${it.inRangePct.toInt()}%in/${it.highPct.toInt()}%hi/${it.lowPct.toInt()}%lo" }
                ?: "insufficient"
            val fastStr = fasting?.let { "f:${it.inRangePct.toInt()}%in/${it.highPct.toInt()}%hi/${it.lowPct.toInt()}%lo" }
                ?: "f:insufficient"
            return "tir=$allStr $fastStr score=${"%.2f".format(aggressiveness)}"
        }

    /**
     * Record current BG zone.
     * @param mealMode  Current meal mode — non-fasting samples excluded from score.
     */
    fun recordBg(bgMgdl: Double, lowThreshMgdl: Double, highThreshMgdl: Double, mealMode: MealMode) {
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

        pruneOldSamples(nowMs)

        if (nowMs - lastUpdateMs >= UPDATE_INTERVAL_MS) {
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

    private fun updateScore() {
        val stats = computeTir(fastingSamples) ?: run {
            aapsLogger.debug(LTag.APS, "AggressionLearner: insufficient fasting samples (${fastingSamples.size}/$MIN_SAMPLES_TO_LEARN), score held at $score")
            return
        }
        val max   = preferences.get(DoubleKey.ApsSmartInsulinAggressionMax)
        val floor = 1.0 / max
        val ceil  = max
        val prev  = score

        score = when {
            stats.lowPct > MAX_LOW_PCT ->
                (score - STEP_DOWN).coerceAtLeast(floor)
            stats.inRangePct >= TARGET_TIR_PCT && stats.highPct > 0 ->
                (score + STEP_UP).coerceAtMost(ceil)
            stats.highPct > MAX_HIGH_PCT ->
                (score + STEP_UP * 1.5).coerceAtMost(ceil)
            else ->
                score + (1.0 - score) * 0.05
        }

        if (score != prev) {
            aapsLogger.debug(LTag.APS,
                             "AggressionLearner: score %.3f→%.3f fasting tir=%.0f%% high=%.0f%% low=%.0f%% (n=${fastingSamples.size})".format(
                                 prev, score, stats.inRangePct, stats.highPct, stats.lowPct))
        }
    }

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
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset() {
        allSamples.clear()
        fastingSamples.clear()
        score        = 1.0
        lastUpdateMs = 0L
        preferences.put(StringKey.ApsSmartInsulinAggressionState, "")
        aapsLogger.debug(LTag.APS, "AggressionLearner: reset to 1.0")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveState() {
        try {
            val arr    = JSONArray()
            val toSave = if (allSamples.size > 288) allSamples.takeLast(288) else allSamples
            toSave.forEach { s ->
                arr.put(JSONObject().put(K_TS, s.timestampMs).put(K_ZONE, s.zone.name).put(K_FASTING, s.fasting))
            }
            preferences.put(
                StringKey.ApsSmartInsulinAggressionState,
                JSONObject().put(K_SCORE, score).put(K_LAST_UPDATE, lastUpdateMs).put(K_SAMPLES, arr).toString()
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
            score        = json.optDouble(K_SCORE, 1.0)
            lastUpdateMs = json.optLong(K_LAST_UPDATE, 0L)
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
            }
            aapsLogger.debug(LTag.APS,
                             "AggressionLearner: restored score=$score all=${allSamples.size} fasting=${fastingSamples.size}")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "AggressionLearner: restore failed: ${e.message}")
            score = 1.0
        }
    }
}