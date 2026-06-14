package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
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
 * NOTE: basalIob gate removed. In a closed loop using oref, basalIob is almost
 * always negative (loop zero-temps frequently), so gating on it means the learner
 * almost never fires. Instead, we gate on a sustained quiet fasting window:
 *
 *   - COB negligible (< 5g)
 *   - No bolus in last [MIN_MINUTES_NO_BOLUS] minutes
 *   - BG in a reasonable range
 *   - BG delta quiet (not reacting to anything rapid)
 *   - Enough samples over a long enough window
 *
 * Net BG drift during that window is the signal — regardless of what the loop
 * was doing with TBRs. If BG drifted up even with loop suppressing basal,
 * profile basal is genuinely too low.
 *
 * Overnight observations still move the multiplier faster (higher weight).
 */
@Singleton
class BasalLearner @Inject constructor(
    private val sp: SP,
    private val aapsLogger:  AAPSLogger
) {
    private data class BgDriftSample(val timestampMs: Long, val bgMgdl: Double)
    private val window          = ArrayDeque<BgDriftSample>()
    private val dayMultipliers  = DoubleArray(7) { 1.0 }
    private val daySampleCount  = IntArray(7) { 0 }
    private var globalMultiplier = 1.0
    private var lastLearnMs     = 0L
    init { restoreState() }
    companion object {
        // Gate conditions
        private const val MIN_MINUTES_NO_BOLUS   = 180.0  // 3h — still safe, more daytime windows
        private const val LOW_BG_GATE_MGDL        = 72.0   // 4.0 mmol
        private const val HIGH_BG_GATE_MGDL       = 162.0  // 9.0 mmol — tighter than overnight gate
        private const val MAX_COB_G               = 5.0
        private const val MAX_DELTA_MGDL_PER_5MIN = 3.0    // BG must be reasonably quiet — <3 mg/dL movement
        // Drift calculation
        private const val SAMPLE_WINDOW_MS        = 90 * 60 * 1000L  // 90min rolling window
        private const val MIN_DRIFT_SAMPLES        = 9                // ≥9 readings (~45min)
        private const val MIN_ELAPSED_HRS          = 0.6             // need ≥36min of spread
        private const val MAX_DRIFT_MGDL_PER_HR    = 27.0            // 1.5 mmol/hr — tighter gate
        private const val LEARN_INTERVAL_MS        = 60 * 60 * 1000L // at most once per hour
        // Confidence weights
        private const val WEIGHT_OVERNIGHT         = 1.0   // midnight–6am
        private const val WEIGHT_DAYTIME           = 0.7   // all other hours — clean fasting signals deserve more weight
        // EWMA base alpha — multiplied by confidence weight per observation
        private const val BASE_ALPHA               = 0.15  // was 0.1 — slightly faster convergence
        // Hard limits
        private const val MIN_MULTIPLIER             = 0.7
        private const val MAX_MULTIPLIER             = 1.5
        private const val MIN_DAY_SAMPLES_FOR_BLEND  = 15  // samples on a given day before blending in
        val DAY_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
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
        get() {
            val dow   = currentDow()
            val blend = (daySampleCount[dow].toDouble() / MIN_DAY_SAMPLES_FOR_BLEND).coerceIn(0.0, 1.0)
            return (globalMultiplier * (1.0 - blend) + dayMultipliers[dow] * blend)
                .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }
    val reasonSummary: String
        get() = "basal_x%.2f(g=%.2f)".format(Locale.US, multiplierClamped, globalMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER))
    /**
     * @param bgMgdl              Current BG mg/dL
     * @param deltaMgdl           5-min BG delta mg/dL
     * @param cobG                Current COB grams
     * @param minsLastManualBolus Minutes since last MANUAL bolus (SMBs excluded — they fire
     *                            constantly during fasting and would permanently block learning)
     * @param isfMgdl             Current ISF mg/dL/U
     * @param profileBasalU       Profile basal U/hr (before multiplier)
     */
    fun onLoopCycle(
        bgMgdl:              Double,
        deltaMgdl:           Double,
        cobG:                Double,
        minsLastManualBolus: Double,
        isfMgdl:             Double,
        profileBasalU:       Double
    ) {
        val nowMs     = System.currentTimeMillis()
        val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        pruneWindow(nowMs)
        // ── Gate checks — all must pass to collect a sample ──────────────────
        if (cobG > MAX_COB_G) return
        if (minsLastManualBolus < MIN_MINUTES_NO_BOLUS) return
        if (bgMgdl < LOW_BG_GATE_MGDL || bgMgdl > HIGH_BG_GATE_MGDL) return
        if (abs(deltaMgdl) > MAX_DELTA_MGDL_PER_5MIN) return
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

        // ── Success Decay Fix ─────────────────────────────────────────────────
        // requiredMultiplier must be calculated relative to the CURRENT running multiplier,
        // not the raw profile value. If BG is flat (driftMgdlPerHr == 0), the current
        // multiplier is correct — EWMA should target itself and stay locked in place.
        // Old formula: (profileBasalU + basalDeltaU) / profileBasalU
        //   → flat BG always produced 1.0, pulling a hard-earned 1.3x back toward 1.0.
        // New formula: currentMult + (basalDeltaU / profileBasalU)
        //   → flat BG produces currentMult + 0 = currentMult, freezing the learned value.
        val basalDeltaU        = driftMgdlPerHr / isfMgdl
        val currentEffectiveMult = multiplierClamped
        val requiredMultiplier = if (profileBasalU > 0.0)
            currentEffectiveMult + (basalDeltaU / profileBasalU)
        else 1.0

        val dow = currentDow()
        // Update global multiplier — all observations contribute
        val prevGlobal = globalMultiplier
        globalMultiplier = ((1.0 - alpha) * globalMultiplier + alpha * requiredMultiplier)
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        // Update day-of-week multiplier
        val prevDay = dayMultipliers[dow]
        dayMultipliers[dow] = ((1.0 - alpha) * dayMultipliers[dow] + alpha * requiredMultiplier)
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        daySampleCount[dow] = (daySampleCount[dow] + 1).coerceAtMost(999)
        lastLearnMs = nowMs
        saveState()
        aapsLogger.debug(LTag.APS,
                         "BasalLearner: %s drift=%.1f mg/dL/hr δ=%.3f U/hr reqMult=%.3f α=%.2f global %.3f→%.3f day[%s] %.3f→%.3f"
                             .format(
                                 Locale.US,
                                 if (isOvernight) "overnight" else "daytime",
                                 driftMgdlPerHr, basalDeltaU, requiredMultiplier, alpha,
                                 prevGlobal, globalMultiplier,
                                 DAY_LABELS[dow], prevDay, dayMultipliers[dow]
                             ))
    }
    // ── Reset ─────────────────────────────────────────────────────────────────
    fun reset() {
        window.clear()
        for (i in 0..6) { dayMultipliers[i] = 1.0; daySampleCount[i] = 0 }
        globalMultiplier = 1.0
        lastLearnMs      = 0L
        sp.edit { putString(StringKey.ApsSmartInsulinBasalState.key, "") }
        aapsLogger.debug(LTag.APS, "BasalLearner: reset to 1.0")
    }
    // ── Persistence ───────────────────────────────────────────────────────────
    private fun currentDow(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
    private fun pruneWindow(nowMs: Long) {
        while (window.isNotEmpty() && nowMs - window.first().timestampMs > SAMPLE_WINDOW_MS) {
            window.removeFirst()
        }
    }
    private fun saveState() {
        try {
            val arr = JSONArray()
            window.forEach { s -> arr.put(JSONObject().put(K_TS, s.timestampMs).put(K_BG, s.bgMgdl)) }
            val dayArr = org.json.JSONArray()
            for (i in 0..6) dayArr.put(JSONObject().put("mult", dayMultipliers[i]).put("n", daySampleCount[i]))
            sp.edit {
                putString(
                    StringKey.ApsSmartInsulinBasalState.key,
                    JSONObject()
                        .put(K_MULTIPLIER, globalMultiplier)
                        .put(K_LAST_LEARN, lastLearnMs)
                        .put(K_SAMPLES, arr)
                        .put("dayMultipliers", dayArr)
                        .toString()
                )
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BasalLearner: save failed: ${e.message}")
        }
    }
    private fun restoreState() {
        try {
            val raw = sp.getString(StringKey.ApsSmartInsulinBasalState.key, StringKey.ApsSmartInsulinBasalState.defaultValue)
            if (raw.isNullOrBlank()) return
            val json    = JSONObject(raw)
            globalMultiplier = json.optDouble(K_MULTIPLIER, 1.0).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
            lastLearnMs      = json.optLong(K_LAST_LEARN, 0L)
            val dayArr = json.optJSONArray("dayMultipliers")
            if (dayArr != null) {
                for (i in 0..6) {
                    val obj = dayArr.optJSONObject(i) ?: continue
                    dayMultipliers[i]  = obj.optDouble("mult", 1.0).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
                    daySampleCount[i]  = obj.optInt("n", 0)
                }
            }
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
                             "BasalLearner: restored global=%.3f samples=${window.size}".format(Locale.US, globalMultiplier))
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BasalLearner: restore failed: ${e.message}")
            globalMultiplier = 1.0
        }
    }
}