package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Learns a basal multiplier from fasting BG drift — any time of day.
 *
 * Signal quality gates replace the overnight time window. An observation only
 * fires when all of the following are true:
 *
 *   - COB is negligible (< 5g) — no active carb absorption
 *   - No bolus in last [MIN_MINUTES_NO_BOLUS] minutes — no recent correction
 *   - IOB is close to basal-only IOB — no meal/correction bolus still active
 *   - BG is within a reasonable range — not in a hypo or stress response
 *   - BG delta is quiet — BG not moving fast due to a non-basal cause
 *   - Enough samples accumulated in the current window
 *   - Measured drift is below the noise gate
 *
 * Observations are confidence-weighted:
 *   - Overnight (midnight–6am): weight = 1.0 — highest quality signal
 *   - Daytime fasting:          weight = 0.5 — noisier, moves multiplier slower
 *
 * This means the multiplier still converges primarily on overnight data, but
 * daytime fasting periods (e.g. pre-breakfast, late afternoon) contribute too.
 */
@Singleton
class BasalLearner @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger:  AAPSLogger
) {
    private data class BgDriftSample(val timestampMs: Long, val bgMgdl: Double)

    private val window     = ArrayDeque<BgDriftSample>()
    private var multiplier = 1.0
    private var lastLearnMs = 0L

    init { restoreState() }

    companion object {
        // Gate conditions
        private const val MIN_MINUTES_NO_BOLUS   = 240.0  // 4h — longer than overnight to catch daytime
        private const val LOW_BG_GATE_MGDL        = 72.0   // 4.0 mmol
        private const val HIGH_BG_GATE_MGDL       = 162.0  // 9.0 mmol — tighter than overnight gate
        private const val MAX_COB_G               = 5.0
        private const val MAX_DELTA_MGDL_PER_5MIN = 2.0    // BG must be quiet — <2 mg/dL movement
        private const val MAX_IOB_ABOVE_BASAL_U   = 0.5    // IOB must be close to basal-only IOB

        // Drift calculation
        private const val SAMPLE_WINDOW_MS        = 90 * 60 * 1000L  // 90min rolling window
        private const val MIN_DRIFT_SAMPLES        = 9                // ≥9 readings (~45min)
        private const val MIN_ELAPSED_HRS          = 0.6             // need ≥36min of spread
        private const val MAX_DRIFT_MGDL_PER_HR    = 27.0            // 1.5 mmol/hr — tighter gate
        private const val LEARN_INTERVAL_MS        = 60 * 60 * 1000L // at most once per hour

        // Confidence weights
        private const val WEIGHT_OVERNIGHT         = 1.0   // midnight–6am
        private const val WEIGHT_DAYTIME           = 0.5   // all other hours

        // EWMA base alpha — multiplied by confidence weight per observation
        private const val BASE_ALPHA               = 0.1

        // Hard limits
        private const val MIN_MULTIPLIER           = 0.7
        private const val MAX_MULTIPLIER           = 1.5

        // Overnight hours
        private const val OVERNIGHT_START_HOUR     = 0
        private const val OVERNIGHT_END_HOUR       = 6

        // Persistence
        private const val K_MULTIPLIER             = "multiplier"
        private const val K_LAST_LEARN             = "lastLearnMs"
        private const val K_SAMPLES                = "samples"
        private const val K_TS                     = "ts"
        private const val K_BG                     = "bg"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    val multiplierClamped: Double
        get() = multiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

    val reasonSummary: String
        get() = "basal_x%.2f".format(Locale.US, multiplierClamped)

    /**
     * @param bgMgdl         Current BG mg/dL
     * @param deltaMgdl      5-min BG delta mg/dL
     * @param cobG           Current COB grams
     * @param minsLastBolus  Minutes since last bolus
     * @param basalOnlyIobU  Expected IOB if only basal were running (U) — used to detect lingering bolus IOB
     * @param currentIobU    Actual current IOB (U)
     * @param isfMgdl        Current ISF mg/dL/U
     * @param profileBasalU  Profile basal U/hr (before multiplier)
     */
    fun onLoopCycle(
        bgMgdl:        Double,
        deltaMgdl:     Double,
        cobG:          Double,
        minsLastBolus: Double,
        basalOnlyIobU: Double,
        currentIobU:   Double,
        isfMgdl:       Double,
        profileBasalU: Double
    ) {
        val nowMs     = System.currentTimeMillis()
        val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        pruneWindow(nowMs)

        // ── Gate checks — all must pass to collect a sample ──────────────────
        if (cobG > MAX_COB_G) return
        if (minsLastBolus < MIN_MINUTES_NO_BOLUS) return
        if (bgMgdl < LOW_BG_GATE_MGDL || bgMgdl > HIGH_BG_GATE_MGDL) return
        if (abs(deltaMgdl) > MAX_DELTA_MGDL_PER_5MIN) return
        if ((currentIobU - basalOnlyIobU) > MAX_IOB_ABOVE_BASAL_U) return

        // Sample passes all gates — collect it
        window.addLast(BgDriftSample(nowMs, bgMgdl))

        // ── Attempt a learning update ─────────────────────────────────────────
        if (nowMs - lastLearnMs < LEARN_INTERVAL_MS) return
        if (window.size < MIN_DRIFT_SAMPLES) return

        val oldest     = window.first()
        val newest     = window.last()
        val elapsedHrs = (newest.timestampMs - oldest.timestampMs) / 3_600_000.0
        if (elapsedHrs < MIN_ELAPSED_HRS) return

        val driftMgdlPerHr = (newest.bgMgdl - oldest.bgMgdl) / elapsedHrs
        if (abs(driftMgdlPerHr) > MAX_DRIFT_MGDL_PER_HR) {
            aapsLogger.debug(LTag.APS,
                             "BasalLearner: drift %.1f mg/dL/hr exceeds gate, skipping".format(Locale.US, driftMgdlPerHr))
            return
        }

        // Weight: overnight observations are higher quality — move multiplier faster
        val isOvernight    = hourOfDay in OVERNIGHT_START_HOUR until OVERNIGHT_END_HOUR
        val weight         = if (isOvernight) WEIGHT_OVERNIGHT else WEIGHT_DAYTIME
        val alpha          = BASE_ALPHA * weight

        val basalDeltaU        = driftMgdlPerHr / isfMgdl
        val requiredMultiplier = if (profileBasalU > 0.0)
            (profileBasalU + basalDeltaU) / profileBasalU
        else 1.0

        val prevMultiplier = multiplier
        multiplier = ((1.0 - alpha) * multiplier + alpha * requiredMultiplier)
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

        lastLearnMs = nowMs
        saveState()

        aapsLogger.debug(LTag.APS,
                         "BasalLearner: %s drift=%.1f mg/dL/hr δ=%.3f U/hr reqMult=%.3f α=%.2f mult %.3f→%.3f"
                             .format(
                                 Locale.US,
                                 if (isOvernight) "overnight" else "daytime",
                                 driftMgdlPerHr, basalDeltaU, requiredMultiplier, alpha,
                                 prevMultiplier, multiplier
                             ))
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
            preferences.put(
                StringKey.ApsSmartInsulinBasalState,
                JSONObject()
                    .put(K_MULTIPLIER, multiplier)
                    .put(K_LAST_LEARN, lastLearnMs)
                    .put(K_SAMPLES, arr)
                    .toString()
            )
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