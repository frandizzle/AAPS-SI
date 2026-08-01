package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estimates glucose-equivalent grams "absorbed" during a meal/UAM mode activation, purely
 * from the residual between actual BG movement and what insulin alone should be doing —
 * the same deconvolution oref0/AutoISF use for carb-impact estimation (CI = actualDelta -
 * expectedBGI, grams = CI / CSF). This does NOT distinguish carbs from protein/fat — they're
 * indistinguishable from BG alone, so this is a single lumped "carb-equivalent" number, not a
 * macro breakdown.
 *
 * Purely observational: nothing here feeds back into dosing. The intent is to build a
 * validated log the user can compare against what they actually ate before any future work
 * considers using this signal (e.g. as a "COB confound" gate for mode ISF learning).
 */
data class CompletedMealEpisode(
    val startMs:        Long,
    val mode:            MealMode,
    val durationMs:      Long,
    val estimatedGrams:  Double
)

@Singleton
class MealAbsorptionTracker @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private var episodeStartMs   = 0L
    private var episodeMode:     MealMode? = null
    private var episodeGrams     = 0.0
    private var lastSeenActiveMs = 0L

    private val _history = mutableListOf<CompletedMealEpisode>()
    val history: List<CompletedMealEpisode> get() = _history

    init { restoreHistory() }

    companion object {
        private const val HISTORY_RETENTION_MS = 5L * 24 * 60 * 60 * 1000  // "a few days" for the UI
        private const val MAX_HISTORY_ENTRIES  = 60

        private const val K_START_MS = "startMs"
        private const val K_MODE     = "mode"
        private const val K_DUR_MS   = "durationMs"
        private const val K_GRAMS    = "estimatedGrams"
    }

    /**
     * Call once per loop cycle. Returns the just-finalized episode on the cycle a mode ends
     * (so the caller can append it to the CSV export), otherwise null.
     */
    fun onCycle(
        activeMode:     MealMode?,
        modeStartMs:    Long,
        deltaMgdl:      Double,
        activityPerMin: Double,
        isfMgdl:        Double,
        carbRatio:      Double,
        nowMs:          Long
    ): CompletedMealEpisode? {
        if (activeMode == null) {
            return if (episodeMode != null) finalizeEpisode() else null
        }

        var completed: CompletedMealEpisode? = null
        if (episodeMode == null || modeStartMs != episodeStartMs) {
            // Either nothing was being tracked, or this is a fresh activation (re-trigger)
            // replacing whatever we had — finalize the old one first if there was one.
            if (episodeMode != null) completed = finalizeEpisode()
            episodeStartMs = modeStartMs
            episodeMode    = activeMode
            episodeGrams   = 0.0
        }

        lastSeenActiveMs = nowMs
        episodeGrams += gramsThisCycle(deltaMgdl, activityPerMin, isfMgdl, carbRatio)
        return completed
    }

    private fun gramsThisCycle(deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double): Double {
        if (carbRatio <= 0.0 || isfMgdl <= 0.0) return 0.0
        val expectedBgi = -activityPerMin * isfMgdl * 5.0
        val ci = deltaMgdl - expectedBgi
        if (ci <= 0.0) return 0.0
        val csf = isfMgdl / carbRatio
        return ci / csf
    }

    private fun finalizeEpisode(): CompletedMealEpisode {
        val completed = CompletedMealEpisode(
            startMs       = episodeStartMs,
            mode          = episodeMode!!,
            durationMs    = (lastSeenActiveMs - episodeStartMs).coerceAtLeast(0L),
            estimatedGrams = episodeGrams
        )
        _history.add(completed)
        trimHistory()
        persistHistory()
        aapsLogger.debug(LTag.APS,
                         "MealAbsorptionTracker: ${completed.mode.label} finished — " +
                             "${completed.durationMs / 60_000}min, est. ${"%.1f".format(completed.estimatedGrams)}g")
        episodeMode  = null
        episodeStartMs = 0L
        episodeGrams = 0.0
        return completed
    }

    private fun trimHistory() {
        val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
        _history.removeAll { it.startMs < cutoff }
        if (_history.size > MAX_HISTORY_ENTRIES) {
            val excess = _history.size - MAX_HISTORY_ENTRIES
            repeat(excess) { _history.removeAt(0) }
        }
    }

    private fun persistHistory() {
        try {
            val arr = JSONArray()
            _history.forEach { e ->
                arr.put(
                    JSONObject()
                        .put(K_START_MS, e.startMs)
                        .put(K_MODE, e.mode.name)
                        .put(K_DUR_MS, e.durationMs)
                        .put(K_GRAMS, e.estimatedGrams)
                )
            }
            sp.edit { putString(StringKey.ApsSmartInsulinMealAbsorptionLog.key, arr.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MealAbsorptionTracker: persist failed: ${e.message}")
        }
    }

    private fun restoreHistory() {
        val raw = sp.getString(StringKey.ApsSmartInsulinMealAbsorptionLog.key, StringKey.ApsSmartInsulinMealAbsorptionLog.defaultValue)
        if (raw.isBlank()) return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val mode = try { MealMode.valueOf(obj.getString(K_MODE)) } catch (_: Exception) { continue }
                _history.add(
                    CompletedMealEpisode(
                        startMs        = obj.getLong(K_START_MS),
                        mode           = mode,
                        durationMs     = obj.getLong(K_DUR_MS),
                        estimatedGrams = obj.getDouble(K_GRAMS)
                    )
                )
            }
            trimHistory()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MealAbsorptionTracker: restore failed: ${e.message}")
        }
    }
}
