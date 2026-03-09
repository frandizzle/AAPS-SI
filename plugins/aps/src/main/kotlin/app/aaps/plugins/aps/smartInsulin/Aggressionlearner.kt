package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
 * Watches BG zone distribution over a rolling 24-hour window and adjusts
 * an aggressiveness score that scales SMB delivery fraction and TBR correction.
 *
 * Score range: [1/aggressionMax .. aggressionMax]
 *   - 1.0 = neutral (default SMB fraction, default TBR correction)
 *   - > 1.0 = more aggressive (bigger SMBs, higher TBR) — when spending too much time high
 *   - < 1.0 = more conservative (smaller SMBs, lower TBR) — when spending too much time low
 *
 * The user-configured aggressionMax dial acts as a hard ceiling/floor.
 * Setting it to 1.0 disables learning entirely (score stays at 1.0).
 *
 * Learning is asymmetric: lows pull the score down faster than highs push it up,
 * because the cost of a low is higher than the cost of a high.
 */
@Singleton
class AggressionLearner @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger:  AAPSLogger
) {
    // ── BG sample record ─────────────────────────────────────────────────────
    private data class BgSample(val timestampMs: Long, val zone: Zone)

    private enum class Zone { LOW, IN_RANGE, HIGH }

    // ── State ─────────────────────────────────────────────────────────────────
    private val samples = ArrayDeque<BgSample>()   // rolling 24h window
    private var score   = 1.0                       // current aggressiveness score
    private var lastUpdateMs = 0L

    init { restoreState() }

    companion object {
        private const val WINDOW_MS            = 24 * 60 * 60 * 1000L  // 24h rolling window
        private const val UPDATE_INTERVAL_MS   = 60 * 60 * 1000L       // recalculate score hourly
        private const val MIN_SAMPLES_TO_LEARN = 24                    // need at least 2h of data

        // Target TIR thresholds — score nudges toward these
        private const val TARGET_TIR_PCT       = 70.0  // aim for ≥70% time in range
        private const val MAX_LOW_PCT          = 4.0   // tolerate ≤4% time low
        private const val MAX_HIGH_PCT         = 26.0  // tolerate ≤26% time high

        // Learning step sizes per hour
        private const val STEP_UP              = 0.02  // nudge up when too high (cautious)
        private const val STEP_DOWN            = 0.05  // nudge down when too low (aggressive)

        // JSON persistence keys
        private const val K_SCORE        = "score"
        private const val K_LAST_UPDATE  = "lastUpdateMs"
        private const val K_SAMPLES      = "samples"
        private const val K_TS           = "ts"
        private const val K_ZONE         = "zone"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Current aggressiveness score, clamped to [1/max .. max].
     * Use this to scale SMB delivery fraction and TBR correction.
     */
    val aggressiveness: Double
        get() {
            val max = preferences.get(DoubleKey.ApsSmartInsulinAggressionMax)
            return score.coerceIn(1.0 / max, max)
        }

    /**
     * Current rolling TIR stats as a human-readable string for the reason output.
     */
    val tirSummary: String
        get() {
            val stats = computeTir() ?: return "tir=insufficient_data"
            return "tir=%.0f%%in/%.0f%%hi/%.0f%%lo score=%.2f".format(
                stats.inRangePct, stats.highPct, stats.lowPct, aggressiveness
            )
        }

    /**
     * Record current BG zone. Call every loop cycle.
     */
    fun recordBg(bgMgdl: Double, lowThreshMgdl: Double, highThreshMgdl: Double) {
        val zone = when {
            bgMgdl < lowThreshMgdl  -> Zone.LOW
            bgMgdl > highThreshMgdl -> Zone.HIGH
            else                    -> Zone.IN_RANGE
        }
        val nowMs = System.currentTimeMillis()
        samples.addLast(BgSample(nowMs, zone))
        pruneOldSamples(nowMs)

        // Recalculate score hourly
        if (nowMs - lastUpdateMs >= UPDATE_INTERVAL_MS) {
            updateScore()
            lastUpdateMs = nowMs
            saveState()
        }
    }

    /**
     * Force a score recalculation and persist. Call after settings change.
     */
    fun recalculate() {
        updateScore()
        saveState()
    }

    // ── Score update ──────────────────────────────────────────────────────────

    private fun updateScore() {
        val stats = computeTir() ?: return
        val max   = preferences.get(DoubleKey.ApsSmartInsulinAggressionMax)
        val floor = 1.0 / max
        val ceil  = max

        val prevScore = score

        score = when {
            // Too many lows — back off hard and fast
            stats.lowPct > MAX_LOW_PCT -> (score - STEP_DOWN).coerceAtLeast(floor)

            // Great TIR but still some highs — nudge up gently
            stats.inRangePct >= TARGET_TIR_PCT && stats.highPct > 0 ->
                (score + STEP_UP).coerceAtMost(ceil)

            // Too much time high — push up more firmly
            stats.highPct > MAX_HIGH_PCT ->
                (score + STEP_UP * 1.5).coerceAtMost(ceil)

            // In a good place — drift back toward neutral slowly
            else -> score + (1.0 - score) * 0.05
        }

        if (score != prevScore) {
            aapsLogger.debug(LTag.APS,
                             "AggressionLearner: score %.3f->%.3f tir=%.0f%% high=%.0f%% low=%.0f%%".format(
                                 prevScore, score,
                                 stats.inRangePct, stats.highPct, stats.lowPct
                             )
            )
        }
    }

    // ── TIR calculation ───────────────────────────────────────────────────────

    private data class TirStats(val inRangePct: Double, val highPct: Double, val lowPct: Double)

    private fun computeTir(): TirStats? {
        if (samples.size < MIN_SAMPLES_TO_LEARN) return null
        val total    = samples.size.toDouble()
        val inRange  = samples.count { it.zone == Zone.IN_RANGE }
        val high     = samples.count { it.zone == Zone.HIGH }
        val low      = samples.count { it.zone == Zone.LOW }
        return TirStats(
            inRangePct = inRange / total * 100.0,
            highPct    = high    / total * 100.0,
            lowPct     = low     / total * 100.0
        )
    }

    private fun pruneOldSamples(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().timestampMs > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset() {
        samples.clear()
        score        = 1.0
        lastUpdateMs = 0L
        preferences.put(StringKey.ApsSmartInsulinAggressionState, "")
        aapsLogger.debug(LTag.APS, "AggressionLearner: reset to 1.0")
    }

    private fun saveState() {
        try {
            val arr = JSONArray()
            // Only persist last 288 samples (24h at 5-min intervals) to keep size manageable
            val toSave = if (samples.size > 288) samples.takeLast(288) else samples
            toSave.forEach { s ->
                arr.put(JSONObject().put(K_TS, s.timestampMs).put(K_ZONE, s.zone.name))
            }
            val json = JSONObject()
                .put(K_SCORE, score)
                .put(K_LAST_UPDATE, lastUpdateMs)
                .put(K_SAMPLES, arr)
            preferences.put(StringKey.ApsSmartInsulinAggressionState, json.toString())
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
                val obj = arr.getJSONObject(i)
                val ts  = obj.getLong(K_TS)
                if (nowMs - ts <= WINDOW_MS) {
                    samples.addLast(BgSample(ts, Zone.valueOf(obj.getString(K_ZONE))))
                }
            }
            aapsLogger.debug(LTag.APS,
                             "AggressionLearner: restored score=$score samples=${samples.size}")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "AggressionLearner: restore failed: ${e.message}")
            score = 1.0
        }
    }
}