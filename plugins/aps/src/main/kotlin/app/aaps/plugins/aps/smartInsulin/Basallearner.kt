package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Learns a basal multiplier from overnight fasting BG drift.
 *
 * Strategy:
 *   During the learning window (default midnight–6am), when conditions are clean
 *   (COB=0, no recent bolus, BG stable enough to measure drift), we sample
 *   BG drift per hour and compare to the expected zero drift on correct basal.
 *
 *   Any observed drift → basal is wrong:
 *     - Rising BG overnight  → basal too low  → multiplier nudges up
 *     - Falling BG overnight → basal too high → multiplier nudges down
 *
 *   Drift is converted to a basal delta via:
 *     basalDelta (U/hr) = driftMgdlPerHr / ISF
 *
 *   The multiplier is EWMA-blended so it moves slowly and can't jump on a
 *   single noisy night.
 *
 * Safety:
 *   Multiplier is hard-clamped to [MIN_MULTIPLIER, MAX_MULTIPLIER].
 *   Learning is suppressed if BG is outside [LOW_BG_GATE, HIGH_BG_GATE] —
 *   we only want clean fasting signal, not post-hypo rebound or stress highs.
 *   Minimum [MIN_DRIFT_SAMPLES] samples required before an observation fires.
 */
@Singleton
class BasalLearner @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger:  AAPSLogger
) {
    // ── Rolling sample window ─────────────────────────────────────────────────
    private data class BgDriftSample(val timestampMs: Long, val bgMgdl: Double)

    private val window    = ArrayDeque<BgDriftSample>()
    private var multiplier = 1.0
    private var lastLearnMs = 0L

    init { restoreState() }

    companion object {
        // Learning window: hours of day (24h clock, inclusive)
        private const val WINDOW_START_HOUR     = 0    // midnight
        private const val WINDOW_END_HOUR       = 6    // 6am

        // Gate conditions
        private const val MIN_MINUTES_NO_BOLUS  = 180.0  // 3h bolus-free before sampling
        private const val LOW_BG_GATE_MGDL      = 72.0   // 4.0 mmol — don't learn if low
        private const val HIGH_BG_GATE_MGDL     = 180.0  // 10.0 mmol — don't learn if high
        private const val MAX_COB_G              = 5.0    // ignore if any meaningful COB

        // Drift calculation
        private const val SAMPLE_WINDOW_MS      = 60 * 60 * 1000L   // 1h drift window
        private const val MIN_DRIFT_SAMPLES     = 6                  // need ≥6 readings (~30min)
        private const val MAX_DRIFT_MGDL_PER_HR = 36.0              // 2 mmol/hr — ignore noisier nights
        private const val LEARN_INTERVAL_MS     = 60 * 60 * 1000L   // learn at most once per hour

        // EWMA
        private const val LEARNING_ALPHA        = 0.1   // slow — ~10 nights to fully adapt

        // Hard limits on multiplier
        private const val MIN_MULTIPLIER        = 0.7
        private const val MAX_MULTIPLIER        = 1.5

        // Persistence
        private const val K_MULTIPLIER          = "multiplier"
        private const val K_LAST_LEARN          = "lastLearnMs"
        private const val K_SAMPLES             = "samples"
        private const val K_TS                  = "ts"
        private const val K_BG                  = "bg"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Current learned basal multiplier, clamped to [MIN_MULTIPLIER, MAX_MULTIPLIER].
     * Apply to profileBasal before using in any calculation:
     *   effectiveBasal = profileBasal * basalLearner.multiplierClamped
     */
    val multiplierClamped: Double
        get() = multiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

    /**
     * One-liner for the reason string.
     * e.g. "basal×1.08(learned)"  or "basal×1.00(default)"
     */
    val reasonSummary: String
        get() = "basal×%.2f".format(Locale.US, multiplierClamped)

    /**
     * Record a BG sample and attempt a learning update if conditions are met.
     *
     * @param bgMgdl         Current BG in mg/dL
     * @param cobG           Current COB in grams
     * @param minsLastBolus  Minutes since last bolus (any type)
     * @param isfMgdl        Current ISF in mg/dL/U
     * @param profileBasalU  Current profile basal rate in U/hr
     */
    fun onLoopCycle(
        bgMgdl:         Double,
        cobG:           Double,
        minsLastBolus:  Double,
        isfMgdl:        Double,
        profileBasalU:  Double
    ) {
        val nowMs    = System.currentTimeMillis()
        val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        // Only collect samples during learning window
        if (hourOfDay < WINDOW_START_HOUR || hourOfDay >= WINDOW_END_HOUR) {
            // Outside window — prune stale samples and bail
            pruneWindow(nowMs)
            return
        }

        // Gate checks
        if (cobG > MAX_COB_G) return
        if (minsLastBolus < MIN_MINUTES_NO_BOLUS) return
        if (bgMgdl < LOW_BG_GATE_MGDL || bgMgdl > HIGH_BG_GATE_MGDL) return

        // Record sample
        window.addLast(BgDriftSample(nowMs, bgMgdl))
        pruneWindow(nowMs)

        // Attempt learning update at most once per hour
        if (nowMs - lastLearnMs < LEARN_INTERVAL_MS) return
        if (window.size < MIN_DRIFT_SAMPLES) return

        val oldest = window.first()
        val newest = window.last()
        val elapsedHrs = (newest.timestampMs - oldest.timestampMs) / 3_600_000.0
        if (elapsedHrs < 0.4) return   // need at least ~25 min of samples

        val driftMgdlPerHr = (newest.bgMgdl - oldest.bgMgdl) / elapsedHrs

        // Ignore very noisy nights
        if (abs(driftMgdlPerHr) > MAX_DRIFT_MGDL_PER_HR) {
            aapsLogger.debug(LTag.APS,
                             "BasalLearner: drift %.1f mg/dL/hr exceeds gate, skipping".format(Locale.US, driftMgdlPerHr))
            return
        }

        // Convert drift to a required basal correction
        // If BG is rising 18 mg/dL/hr and ISF=36 mg/dL/U, we need +0.5 U/hr more basal
        val basalDeltaU = driftMgdlPerHr / isfMgdl   // positive = need more basal

        // Express as a multiplier adjustment relative to profile basal
        val requiredMultiplier = if (profileBasalU > 0.0)
            (profileBasalU + basalDeltaU) / profileBasalU
        else 1.0

        val prevMultiplier = multiplier
        multiplier = ((1.0 - LEARNING_ALPHA) * multiplier + LEARNING_ALPHA * requiredMultiplier)
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

        lastLearnMs = nowMs
        saveState()

        aapsLogger.debug(LTag.APS,
                         "BasalLearner: drift=%.1f mg/dL/hr basalDelta=%.3f U/hr requiredMult=%.3f mult %.3f→%.3f"
                             .format(Locale.US, driftMgdlPerHr, basalDeltaU, requiredMultiplier, prevMultiplier, multiplier))
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun pruneWindow(nowMs: Long) {
        while (window.isNotEmpty() && nowMs - window.first().timestampMs > SAMPLE_WINDOW_MS) {
            window.removeFirst()
        }
    }

    private fun saveState() {
        try {
            val arr = JSONArray()
            window.forEach { s -> arr.put(JSONObject().put(K_TS, s.timestampMs).put(K_BG, s.bgMgdl)) }
            val json = JSONObject()
                .put(K_MULTIPLIER, multiplier)
                .put(K_LAST_LEARN, lastLearnMs)
                .put(K_SAMPLES, arr)
            preferences.put(StringKey.ApsSmartInsulinBasalState, json.toString())
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BasalLearner: save failed: ${e.message}")
        }
    }

    private fun restoreState() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinBasalState)
            if (raw.isBlank()) return
            val json    = JSONObject(raw)
            multiplier  = json.optDouble(K_MULTIPLIER, 1.0).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
            lastLearnMs = json.optLong(K_LAST_LEARN, 0L)
            val arr     = json.optJSONArray(K_SAMPLES) ?: return
            val nowMs   = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ts  = obj.getLong(K_TS)
                if (nowMs - ts <= SAMPLE_WINDOW_MS) {
                    window.addLast(BgDriftSample(ts, obj.getDouble(K_BG)))
                }
            }
            aapsLogger.debug(LTag.APS,
                             "BasalLearner: restored multiplier=%.3f samples=${window.size}".format(Locale.US, multiplier))
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BasalLearner: restore failed: ${e.message}")
            multiplier = 1.0
        }
    }
}