package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import org.json.JSONArray
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.min

/**
 * Estimates glucose-equivalent grams "absorbed" during a meal/UAM mode activation, from the
 * residual between actual BG movement and what's "expected" absent food — the same
 * deconvolution oref0/AutoISF use for carb-impact estimation (CI = actualDelta - expectedDelta,
 * grams = CI / CSF). The expected delta combines insulin's own predicted pull with a mild
 * "should be drifting back toward target" assumption (see TARGET_SEEK_BLOCKS), so a BG that's
 * simply stuck flat above target still counts as ongoing absorption rather than reading as zero
 * just because it isn't actively rising. This does NOT distinguish carbs from protein/fat —
 * they're indistinguishable from BG alone, so this is a single lumped "carb-equivalent" number,
 * not a macro breakdown.
 *
 * Purely observational: nothing here feeds back into dosing. The intent is to build a
 * validated log the user can compare against what they actually ate before any future work
 * considers using this signal (e.g. as a "COB confound" gate for mode ISF learning).
 */
data class CompletedMealEpisode(
    val startMs:        Long,
    val mode:            MealMode,
    val durationMs:      Long,
    val estimatedGrams:  Double,
    // Phase segmentation (observational): minutes and grams attributed to the rise
    // (absorption rate still climbing to its peak), plateau (holding near peak), and
    // tail (decayed below half peak). Feeds the CSV log for validating meal-shape
    // patterns per mode/size — not used for dosing.
    val riseMins:        Long   = 0L,
    val plateauMins:     Long   = 0L,
    val tailMins:        Long   = 0L,
    val riseGrams:       Double = 0.0,
    val plateauGrams:    Double = 0.0,
    val tailGrams:       Double = 0.0
)

data class InProgressEpisode(
    val mode:                MealMode,
    val startMs:             Long,
    val estimatedGramsSoFar: Double
)

@SingleIn(AppScope::class)
class MealAbsorptionTracker @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private var episodeStartMs   = 0L
    private var episodeMode:     MealMode? = null
    private var episodeGrams     = 0.0
    private var lastSeenActiveMs = 0L

    // Phase segmentation state — smoothed absorption rate vs its running peak classifies
    // each cycle as RISE (new smoothed peak), PLATEAU (≥ half peak), or TAIL (below half).
    private var phaseSmoothedRate = 0.0
    private var phasePeakRate     = 0.0
    private var phaseSamples      = 0
    private var riseMins          = 0L
    private var plateauMins       = 0L
    private var tailMins          = 0L
    private var riseGrams         = 0.0
    private var plateauGrams      = 0.0
    private var tailGrams         = 0.0

    private val _history = mutableListOf<CompletedMealEpisode>()
    val history: List<CompletedMealEpisode> get() = _history

    /** Live snapshot of whatever episode is currently being tracked, or null if none is active. */
    val inProgress: InProgressEpisode?
        get() = episodeMode?.let { InProgressEpisode(mode = it, startMs = episodeStartMs, estimatedGramsSoFar = episodeGrams) }

    init { restoreHistory() }

    companion object {
        private const val HISTORY_RETENTION_MS = 5L * 24 * 60 * 60 * 1000  // "a few days" for the UI
        private const val MAX_HISTORY_ENTRIES  = 60
        // How many 5-min cycles BG is assumed to take closing the gap to target absent food —
        // 12 = 1 hour. Tunable: lower = counts "stuck above target" as carbs faster/harder,
        // higher = more conservative (closer to AutoISF's own 24-block/2h horizon).
        private const val TARGET_SEEK_BLOCKS = 12.0

        // Phase segmentation: EWMA smoothing on the per-cycle absorption rate, and the
        // fraction of the running peak below which the episode is considered in its tail.
        private const val PHASE_SMOOTH_KEEP      = 0.7
        private const val PHASE_PLATEAU_FRACTION = 0.5

        private const val K_START_MS = "startMs"
        private const val K_MODE     = "mode"
        private const val K_DUR_MS   = "durationMs"
        private const val K_GRAMS    = "estimatedGrams"
        private const val K_RISE_M   = "riseMins"
        private const val K_PLAT_M   = "plateauMins"
        private const val K_TAIL_M   = "tailMins"
        private const val K_RISE_G   = "riseGrams"
        private const val K_PLAT_G   = "plateauGrams"
        private const val K_TAIL_G   = "tailGrams"
    }

    /**
     * Call once per loop cycle. Returns the just-finalized episode on the cycle a mode ends
     * (so the caller can append it to the CSV export), otherwise null.
     */
    fun onCycle(
        activeMode:     MealMode?,
        modeStartMs:    Long,
        bgMgdl:         Double,
        targetMgdl:     Double,
        deltaMgdl:      Double,
        activityPerMin: Double,
        isfMgdl:        Double,
        carbRatio:      Double,
        nowMs:          Long,
        cycleMinutes:   Double = 5.0
    ): CompletedMealEpisode? {
        if (activeMode == null) {
            return if (episodeMode != null) finalizeEpisode(nowMs) else null
        }

        var completed: CompletedMealEpisode? = null
        if (episodeMode == null || modeStartMs != episodeStartMs) {
            // Either nothing was being tracked, or this is a fresh activation (re-trigger)
            // replacing whatever we had — finalize the old one first if there was one.
            if (episodeMode != null) completed = finalizeEpisode(nowMs)
            episodeStartMs = modeStartMs
            episodeMode    = activeMode
            episodeGrams   = 0.0
            resetPhaseState()
        }

        lastSeenActiveMs = nowMs
        val g = gramsThisCycle(bgMgdl, targetMgdl, deltaMgdl, activityPerMin, isfMgdl, carbRatio)
        episodeGrams += g

        // -- Phase segmentation (observational) ---------------------------------
        phaseSmoothedRate = if (phaseSamples == 0) g else PHASE_SMOOTH_KEEP * phaseSmoothedRate + (1.0 - PHASE_SMOOTH_KEEP) * g
        phaseSamples++
        val cycleMinsL = cycleMinutes.toLong()
        when {
            phaseSmoothedRate >= phasePeakRate -> {
                phasePeakRate = phaseSmoothedRate
                riseMins += cycleMinsL; riseGrams += g
            }
            phaseSmoothedRate >= PHASE_PLATEAU_FRACTION * phasePeakRate -> {
                plateauMins += cycleMinsL; plateauGrams += g
            }
            else -> {
                tailMins += cycleMinsL; tailGrams += g
            }
        }
        return completed
    }

    private fun resetPhaseState() {
        phaseSmoothedRate = 0.0; phasePeakRate = 0.0; phaseSamples = 0
        riseMins = 0L; plateauMins = 0L; tailMins = 0L
        riseGrams = 0.0; plateauGrams = 0.0; tailGrams = 0.0
    }

    private fun gramsThisCycle(bgMgdl: Double, targetMgdl: Double, deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double): Double {
        if (carbRatio <= 0.0 || isfMgdl <= 0.0) return 0.0
        val insulinPull = -activityPerMin * isfMgdl * 5.0
        // Also expect BG to drift back toward target over TARGET_SEEK_BLOCKS cycles, same idea
        // AutoISF's own expected-delta calc uses. Without this, a BG that's simply "stuck" (not
        // rising, not falling) above target reads as zero residual even though, absent food, it
        // should be heading back down given normal insulin coverage — so genuine ongoing
        // digestion was invisible whenever it wasn't actively pushing BG up further. Only applied
        // while a meal/UAM mode is active, where food is already the established explanation —
        // NOT during plain Fasting, where "stuck above target" is ambiguous with ISF being wrong.
        val targetSeekingPull = (targetMgdl - bgMgdl) / TARGET_SEEK_BLOCKS
        // The two terms are NOT independent additive processes — the target-seek heuristic is a
        // catch-all that already includes insulin's contribution. Summing them double-counted
        // expected decline whenever pre-bolus/SMB insulin was active above target, inflating the
        // grams estimate. Above target: expect whichever single mechanism predicts the steeper
        // decline (insulin model when it dominates, target-seek when insulin is quiet — the
        // stuck-BG case keeps working). Below target (seek term positive, expecting a rebound
        // rise toward target): keep the sum so recovery out of a dip isn't credited as carbs.
        val expectedDelta = if (targetSeekingPull < 0.0) min(insulinPull, targetSeekingPull)
        else insulinPull + targetSeekingPull
        val ci = deltaMgdl - expectedDelta
        if (ci <= 0.0) return 0.0
        val csf = isfMgdl / carbRatio
        return ci / csf
    }

    private fun finalizeEpisode(nowMs: Long): CompletedMealEpisode {
        val completed = CompletedMealEpisode(
            startMs       = episodeStartMs,
            mode          = episodeMode!!,
            durationMs    = (lastSeenActiveMs - episodeStartMs).coerceAtLeast(0L),
            estimatedGrams = episodeGrams,
            riseMins      = riseMins,
            plateauMins   = plateauMins,
            tailMins      = tailMins,
            riseGrams     = riseGrams,
            plateauGrams  = plateauGrams,
            tailGrams     = tailGrams
        )
        _history.add(completed)
        trimHistory(nowMs)
        persistHistory()
        aapsLogger.debug(LTag.APS,
                         "MealAbsorptionTracker: ${completed.mode.label} finished — " +
                             "${completed.durationMs / 60_000}min, est. ${"%.1f".format(completed.estimatedGrams)}g " +
                             "(rise ${riseMins}m/${"%.0f".format(riseGrams)}g, plateau ${plateauMins}m/${"%.0f".format(plateauGrams)}g, tail ${tailMins}m/${"%.0f".format(tailGrams)}g)")
        episodeMode  = null
        episodeStartMs = 0L
        episodeGrams = 0.0
        resetPhaseState()
        return completed
    }

    private fun trimHistory(nowMs: Long) {
        val cutoff = nowMs - HISTORY_RETENTION_MS
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
                        .put(K_RISE_M, e.riseMins)
                        .put(K_PLAT_M, e.plateauMins)
                        .put(K_TAIL_M, e.tailMins)
                        .put(K_RISE_G, e.riseGrams)
                        .put(K_PLAT_G, e.plateauGrams)
                        .put(K_TAIL_G, e.tailGrams)
                )
            }
            sp.edit { putString(StringNonKey.ApsSmartInsulinMealAbsorptionLog.key, arr.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MealAbsorptionTracker: persist failed: ${e.message}")
        }
    }

    private fun restoreHistory() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinMealAbsorptionLog.key, StringNonKey.ApsSmartInsulinMealAbsorptionLog.defaultValue)
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
                        estimatedGrams = obj.getDouble(K_GRAMS),
                        riseMins       = obj.optLong(K_RISE_M, 0L),
                        plateauMins    = obj.optLong(K_PLAT_M, 0L),
                        tailMins       = obj.optLong(K_TAIL_M, 0L),
                        riseGrams      = obj.optDouble(K_RISE_G, 0.0),
                        plateauGrams   = obj.optDouble(K_PLAT_G, 0.0),
                        tailGrams      = obj.optDouble(K_TAIL_G, 0.0)
                    )
                )
            }
            trimHistory(System.currentTimeMillis())
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MealAbsorptionTracker: restore failed: ${e.message}")
        }
    }
}
