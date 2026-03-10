package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sign

/**
 * CircadianLearner — three time-of-day aware learning systems:
 *
 *  1. ISF multiplier    — learns deviation patterns per hour → scales dosingISF up/down
 *  2. Basal multiplier  — learns fasting BG drift per hour  → replaces flat BasalLearner
 *  3. Aggression ceiling — detects rollercoasters + soft-low-approach per hour → caps aggressiveness
 *
 * All three use 24-bucket EWMA. Only update during FASTING mode with zero COB.
 * Persisted as JSON in SharedPreferences.
 */
@Singleton
class CircadianLearner @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var isfState:   CircadianState = CircadianState(DoubleArray(24) { 1.0 })
    private var basalState: CircadianState = CircadianState(DoubleArray(24) { 1.0 })
    private var aggrState:  CircadianState = CircadianState(DoubleArray(24) { 1.0 })

    // Rollercoaster detection — ring buffer of recent (timestamp, bg) pairs
    private val bgHistory: ArrayDeque<Pair<Long, Double>> = ArrayDeque(MAX_HISTORY)

    init { restore() }

    // ── Public outputs ────────────────────────────────────────────────────────

    /** ISF multiplier for current hour (0.7–1.5). >1.0 = less aggressive ISF */
    fun isfMultiplier(hour: Int = currentHour()): Double =
        blend(isfState.get(hour), 1.0, isfState.getConfidence(hour))
            .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)

    /** Basal multiplier for current hour (0.5–1.5) */
    fun basalMultiplier(hour: Int = currentHour()): Double =
        blend(basalState.get(hour), 1.0, basalState.getConfidence(hour))
            .coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)

    /** Aggressiveness ceiling for current hour (0.6–1.2).
     *  Global aggressiveness should be clamped to min(globalAggr, aggrCeiling) */
    fun aggrCeiling(hour: Int = currentHour()): Double =
        blend(aggrState.get(hour), 1.0, aggrState.getConfidence(hour))
            .coerceIn(AGGR_CEIL_MIN, AGGR_CEIL_MAX)

    // ── Core update — called every loop cycle ─────────────────────────────────

    /**
     * @param glucoseStatus  Current BG/delta from loop
     * @param iobArray       Current IOB array
     * @param mealMode       Active meal mode — only update during FASTING
     * @param cobG           Current COB — skip learning if > 0
     * @param profileIsfMgdl Profile ISF in mg/dL (used for deviation calc)
     * @param targetMgdl     Current target BG
     */
    fun update(
        glucoseStatus:          GlucoseStatus,
        iobArray:               Array<IobTotal>,
        mealMode:               MealMode,
        cobG:                   Double,
        profileIsfMgdl:         Double,
        targetMgdl:             Double,
        suppressAdaptiveLearning: Boolean = false   // true = skip ISF/basal updates, keep rollercoaster protection
    ) {
        val hour = currentHour()
        val bg   = glucoseStatus.glucose
        val delta  = glucoseStatus.shortAvgDelta
        val now    = System.currentTimeMillis()
        val iob    = iobArray.firstOrNull()?.iob      ?: 0.0
        val activity = iobArray.firstOrNull()?.activity ?: 0.0
        val basalIob = iobArray.firstOrNull()?.basaliob ?: 0.0
        val rollercoaster = if (bgHistory.size >= MIN_HISTORY_FOR_ROLLER) detectRollercoaster(targetMgdl) else false

        // Guard skip reason logged before early return
        val skipReason = when {
            mealMode != MealMode.FASTING -> "skip: mode=$mealMode"
            cobG > COB_THRESHOLD_G       -> "skip: cob=${"%.1f".format(cobG)}g"
            else                         -> null
        }

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner h=$hour bg=${"%.1f".format(bg)} δ=${"%.2f".format(delta)} " +
                             "iob=${"%.2f".format(iob)} activity=${"%.4f".format(activity)} basalIob=${"%.2f".format(basalIob)} " +
                             "cob=${"%.1f".format(cobG)} mode=$mealMode target=${"%.0f".format(targetMgdl)} " +
                             "roller=$rollercoaster histSize=${bgHistory.size} " +
                             "→ ISF×${"%.3f".format(isfMultiplier(hour))} basal×${"%.3f".format(basalMultiplier(hour))} aggrCeil=${"%.3f".format(aggrCeiling(hour))}" +
                             (skipReason?.let { " | $it" } ?: ""))

        if (skipReason != null) return

        // Maintain BG history for rollercoaster detection
        bgHistory.addLast(now to bg)
        // Prune entries older than detection window
        while (bgHistory.isNotEmpty() && now - bgHistory.first().first > ROLLER_WINDOW_MS)
            bgHistory.removeFirst()

        // ── 1. ISF learning — skip during CGM warmup (unreliable data) ─────
        if (!suppressAdaptiveLearning) updateIsfLearner(hour, glucoseStatus, iobArray, profileIsfMgdl)
        else aapsLogger.debug(LTag.APS, "CircadianLearner ISF: suppressed (CGM warmup)")

        // ── 2. Basal learning — skip during CGM warmup ───────────────────────
        if (!suppressAdaptiveLearning) updateBasalLearner(hour, bg, now)
        else aapsLogger.debug(LTag.APS, "CircadianLearner Basal: suppressed (CGM warmup)")

        // ── 3. Aggressiveness ceiling — ALWAYS runs (rollercoaster protection) ─
        // Rollercoaster and soft-low penalties must fire even on a new sensor —
        // a real rapid rise/crash is dangerous regardless of sensor age.
        updateAggrLearner(hour, bg, delta, targetMgdl, iobArray)

        persist()
    }

    // ── ISF learner ───────────────────────────────────────────────────────────

    private fun updateIsfLearner(
        hour:           Int,
        glucoseStatus:  GlucoseStatus,
        iobArray:       Array<IobTotal>,
        profileIsfMgdl: Double
    ) {
        val activity = iobArray.firstOrNull()?.activity ?: run {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: no iobArray"); return
        }
        if (abs(activity) < MIN_ACTIVITY) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: activity=${"%.5f".format(activity)} < $MIN_ACTIVITY")
            return
        }

        // Expected delta from IOB activity: bgi = -(activity × ISF × 5min)
        val expectedDelta = -(activity * profileIsfMgdl * 5.0)
        val actualDelta   = glucoseStatus.shortAvgDelta

        if (abs(expectedDelta) < MIN_EXPECTED_DELTA_MGDL) {
            aapsLogger.debug(LTag.APS, "CircadianLearner ISF skip: expectedΔ=${"%.2f".format(expectedDelta)} < $MIN_EXPECTED_DELTA_MGDL")
            return
        }

        // ISF convention: higher ISF number = less aggressive (each unit moves BG further per unit).
        //
        // We learn a deviation: how much did BG actually move vs how much did IOB predict?
        // Work in absolute magnitudes to avoid sign confusion — then determine direction separately.
        //
        // expectedDelta = -(activity × profileISF × 5)  [negative when insulin active]
        // If BG moved LESS than expected → insulin weaker than profile predicts → real ISF is HIGHER
        //   → mult should go UP so dosingISF = profileISF / mult goes DOWN... WAIT.
        //
        // APPLICATION convention (fixed below in plugin):
        //   dosingIsfMgdl = profileISF / circIsfMult
        //   circIsfMult > 1.0 → dosingISF goes DOWN → less insulin (insulin weaker than expected)
        //   circIsfMult < 1.0 → dosingISF goes UP   → more insulin (insulin stronger than expected)
        //
        // So here: if insulin was WEAKER than expected → mult should go UP (> 1.0)
        //          if insulin was STRONGER than expected → mult should go DOWN (< 1.0)
        //
        // deviation = actualDelta - expectedDelta (signed)
        // Both negative when BG falling. expectedDelta=-3.6, actualDelta=-1.5:
        //   deviation = -1.5 - (-3.6) = +2.1  → BG fell less than expected → insulin weaker → mult UP
        // expectedDelta=-3.6, actualDelta=-5.0:
        //   deviation = -5.0 - (-3.6) = -1.4  → BG fell more than expected → insulin stronger → mult DOWN
        // expectedDelta=-1.8, actualDelta=+2.3 (BG rising against IOB):
        //   deviation = +2.3 - (-1.8) = +4.1  → massive under-response → mult UP (large step)
        //
        // Normalise deviation by expectedDelta magnitude to get a fractional adjustment:
        //   normDeviation = deviation / abs(expectedDelta)
        //   +1.0 means actual was 100% weaker than expected → mult target = current * 2.0
        //   -0.5 means actual was 50% stronger → mult target = current * 0.5
        val deviation     = actualDelta - expectedDelta
        val normDeviation = (deviation / abs(expectedDelta)).coerceIn(-1.0, 2.0)
        // Target multiplier: positive deviation → mult > 1 → dosingISF/mult goes lower → less insulin
        val multTarget    = (isfState.get(hour) + normDeviation).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        val newMult       = (isfState.get(hour) * (1.0 - ISF_ALPHA) + multTarget * ISF_ALPHA)
            .coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        isfState = isfState.updated(hour, newMult, ISF_ALPHA)

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF h=$hour expectedΔ=%.1f actualΔ=%.1f dev=%.2f normDev=%.2f target=%.3f → mult=%.3f"
                             .format(expectedDelta, actualDelta, deviation, normDeviation, multTarget, isfState.get(hour)))
    }

    // ── Basal learner ─────────────────────────────────────────────────────────
    //
    // In a closed loop, basalIob is almost always negative (loop zero-temps frequently).
    // Gating on basalIob is therefore wrong — it would almost never fire.
    //
    // Instead: use a sustained BG drift window. Collect (timestamp, bg) pairs during
    // quiet fasting periods (low COB, no bolus — already gated upstream in update()).
    // Once enough samples accumulate over a long enough window, the net drift tells us
    // whether profile basal is too high or too low — regardless of what the loop did
    // to achieve it. If BG drifted up even with loop suppressing basal, profile basal
    // is genuinely too low. If BG stayed flat with loop running normally, it's fine.

    private val basalDriftWindow: ArrayDeque<Pair<Long, Double>> = ArrayDeque(BASAL_WINDOW_MAX)

    private fun updateBasalLearner(
        hour:  Int,
        bg:    Double,
        now:   Long
    ) {
        // Collect sample into drift window
        basalDriftWindow.addLast(now to bg)
        // Prune samples older than the window
        while (basalDriftWindow.isNotEmpty() && now - basalDriftWindow.first().first > BASAL_DRIFT_WINDOW_MS)
            basalDriftWindow.removeFirst()

        if (basalDriftWindow.size < BASAL_MIN_SAMPLES) {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: only ${basalDriftWindow.size}/${BASAL_MIN_SAMPLES} samples")
            return
        }

        val oldest     = basalDriftWindow.first()
        val newest     = basalDriftWindow.last()
        val elapsedHrs = (newest.first - oldest.first) / 3_600_000.0
        if (elapsedHrs < BASAL_MIN_ELAPSED_HRS) {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: elapsed=${"%.2f".format(elapsedHrs)}h < $BASAL_MIN_ELAPSED_HRS")
            return
        }

        val driftMgdlPerHr = (newest.second - oldest.second) / elapsedHrs

        // Noise gate — ignore tiny drift, could be CGM noise
        if (abs(driftMgdlPerHr) < BASAL_MIN_DRIFT_MGDL_HR) {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr < noise gate")
            return
        }
        // Sanity gate — ignore huge drift, something else is going on
        if (abs(driftMgdlPerHr) > BASAL_MAX_DRIFT_MGDL_HR) {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr > sanity gate")
            basalDriftWindow.clear()  // stale window, start fresh
            return
        }

        // Positive drift → BG rising despite loop → profile basal too low → mult > 1
        // Negative drift → BG falling → profile basal too high → mult < 1
        val adjustment = 1.0 + (driftMgdlPerHr / BASAL_DRIFT_SENSITIVITY)
        val newMult    = (basalState.get(hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        basalState = basalState.updated(hour, newMult, BASAL_ALPHA)

        // Clear window after a learning event so next update is from fresh data
        basalDriftWindow.clear()

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner Basal h=$hour drift=${"%.2f".format(driftMgdlPerHr)} mg/dL/hr → mult=${"%.3f".format(basalState.get(hour))}")
    }

    // ── Aggressiveness ceiling learner ────────────────────────────────────────

    private fun updateAggrLearner(
        hour:      Int,
        bg:        Double,
        delta:     Double,
        targetMgdl:Double,
        iobArray:  Array<IobTotal>
    ) {
        val currentCeil = aggrState.get(hour)

        // ── Penalty signal 1: Rollercoaster ──────────────────────────────────
        val rollercoaster = detectRollercoaster(targetMgdl)
        if (rollercoaster) {
            val penalised = (currentCeil * AGGR_PENALTY_ROLLER).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(hour, penalised, AGGR_ALPHA_PENALTY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour ROLLERCOASTER detected → ceil=%.3f"
                                 .format(aggrState.get(hour)))
            return
        }

        // ── Penalty signal 2: Soft low approach ──────────────────────────────
        // BG heading toward warn guard with meaningful negative delta and IOB still on board
        val iob = iobArray.firstOrNull()?.iob ?: 0.0
        val approachingLow = bg < SOFT_LOW_BG_MGDL && delta < SOFT_LOW_DELTA_MGDL && iob > SOFT_LOW_MIN_IOB
        if (approachingLow) {
            val penalised = (currentCeil * AGGR_PENALTY_SOFT_LOW).coerceAtLeast(AGGR_CEIL_MIN)
            aggrState = aggrState.updated(hour, penalised, AGGR_ALPHA_PENALTY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour SOFT_LOW_APPROACH bg=$bg delta=$delta → ceil=%.3f"
                                 .format(aggrState.get(hour)))
            return
        }

        // ── Recovery signal: good outcome ────────────────────────────────────
        // BG stable near target → gently recover ceiling toward 1.0
        val stableNearTarget = abs(bg - targetMgdl) < STABLE_BAND_MGDL && abs(delta) < STABLE_DELTA_MGDL
        if (stableNearTarget && currentCeil < 1.0) {
            val recovered = (currentCeil + AGGR_RECOVERY_STEP).coerceAtMost(AGGR_CEIL_MAX)
            aggrState = aggrState.updated(hour, recovered, AGGR_ALPHA_RECOVERY)
            aapsLogger.debug(LTag.APS,
                             "CircadianLearner Aggr h=$hour STABLE_RECOVERY → ceil=%.3f"
                                 .format(aggrState.get(hour)))
        }
    }

    // ── Rollercoaster detection ───────────────────────────────────────────────

    /**
     * Returns true if BG has crossed the target band 2+ times within the detection window.
     * Zero-crossing count on (bg - target) sign changes.
     */
    private fun detectRollercoaster(targetMgdl: Double): Boolean {
        if (bgHistory.size < MIN_HISTORY_FOR_ROLLER) return false
        var crossings = 0
        var lastSign  = 0
        for ((_, bg) in bgHistory) {
            val s = (bg - targetMgdl).sign.toInt()
            if (s != 0 && lastSign != 0 && s != lastSign) crossings++
            if (s != 0) lastSign = s
        }
        return crossings >= ROLLER_CROSSING_THRESHOLD
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persist() {
        try {
            val json = JSONObject().apply {
                put("isf",   stateToJson(isfState))
                put("basal", stateToJson(basalState))
                put("aggr",  stateToJson(aggrState))
            }
            preferences.put(StringKey.ApsSmartInsulinCircadianState, json.toString())
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner persist failed: ${e.message}")
        }
    }

    private fun restore() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinCircadianState)
            if (raw.isBlank()) return
            val json = JSONObject(raw)
            isfState   = jsonToState(json.getJSONObject("isf"))
            basalState = jsonToState(json.getJSONObject("basal"))
            aggrState  = jsonToState(json.getJSONObject("aggr"))
            aapsLogger.debug(LTag.APS, "CircadianLearner restored")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "CircadianLearner restore failed: ${e.message}")
        }
    }

    private fun stateToJson(s: CircadianState): JSONObject {
        val vArr = JSONArray()
        val cArr = JSONArray()
        for (i in 0..23) { vArr.put(s.values[i]); cArr.put(s.confidence[i]) }
        return JSONObject().apply {
            put("values",     vArr)
            put("confidence", cArr)
        }
    }

    private fun jsonToState(obj: JSONObject): CircadianState {
        val vArr = obj.getJSONArray("values")
        val cArr = obj.getJSONArray("confidence")
        val values     = DoubleArray(24) { vArr.getDouble(it) }
        val confidence = DoubleArray(24) { cArr.getDouble(it) }
        return CircadianState(values, confidence)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset() {
        isfState   = CircadianState()
        basalState = CircadianState()
        aggrState  = CircadianState()
        bgHistory.clear()
        preferences.put(StringKey.ApsSmartInsulinCircadianState, "")
        aapsLogger.debug(LTag.APS, "CircadianLearner reset")
    }

    fun resetBasal() {
        basalState = CircadianState()
        persist()
        aapsLogger.debug(LTag.APS, "CircadianLearner basal state reset")
    }

    fun resetAggr() {
        aggrState = CircadianState()
        bgHistory.clear()
        persist()
        aapsLogger.debug(LTag.APS, "CircadianLearner aggr state reset")
    }

    // ── Status summary for tab UI ─────────────────────────────────────────────

    /** Average confidence across ISF/basal/aggr for a given hour, as 0–100 */
    fun confidencePct(hour: Int): Double {
        val h = hour.coerceIn(0, 23)
        return ((isfState.getConfidence(h) + basalState.getConfidence(h) + aggrState.getConfidence(h)) / 3.0) * 100.0
    }

    fun statusSummary(hour: Int = currentHour()): String =
        "h=$hour ISF×%.2f basal×%.2f aggrCeil=%.2f (conf isf=%.0f%% basal=%.0f%% aggr=%.0f%%)"
            .format(
                isfMultiplier(hour), basalMultiplier(hour), aggrCeiling(hour),
                isfState.getConfidence(hour) * 100,
                basalState.getConfidence(hour) * 100,
                aggrState.getConfidence(hour) * 100
            )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** Blend learned value toward default (1.0) based on confidence */
    private fun blend(learned: Double, default: Double, confidence: Double) =
        default + (learned - default) * confidence

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        // ISF learner
        private const val ISF_ALPHA              = 0.08   // slow EWMA — each sample moves ~8%
        private const val ISF_MULT_MIN           = 0.7
        private const val ISF_MULT_MAX           = 1.5
        private const val MIN_ACTIVITY           = 0.005  // min IOB activity to learn from
        private const val MIN_EXPECTED_DELTA_MGDL = 1.0

        // Basal learner — drift window approach (basalIob gate removed, always negative in closed loop)
        private const val BASAL_ALPHA              = 0.06
        private const val BASAL_MULT_MIN           = 0.5
        private const val BASAL_MULT_MAX           = 1.5
        private const val BASAL_DRIFT_WINDOW_MS    = 90 * 60 * 1000L  // 90 min window to measure drift
        private const val BASAL_MIN_SAMPLES        = 12               // ~60 min of readings
        private const val BASAL_MIN_ELAPSED_HRS    = 0.5              // at least 30 min spread
        private const val BASAL_MIN_DRIFT_MGDL_HR  = 2.0             // < 2 mg/dL/hr = noise, ignore
        private const val BASAL_MAX_DRIFT_MGDL_HR  = 27.0            // > 1.5 mmol/hr = something else going on
        private const val BASAL_DRIFT_SENSITIVITY  = 18.0            // 18 mg/dL/hr drift → 1.0 multiplier adjustment (1 mmol/L/hr)
        private const val BASAL_WINDOW_MAX         = 30              // ring buffer max size

        // Aggressiveness ceiling
        private const val AGGR_ALPHA_PENALTY    = 0.25   // penalty applies quickly
        private const val AGGR_ALPHA_RECOVERY   = 0.04   // recovery is slow
        private const val AGGR_PENALTY_ROLLER   = 0.85   // 15% cut on rollercoaster
        private const val AGGR_PENALTY_SOFT_LOW = 0.90   // 10% cut on soft low approach
        private const val AGGR_RECOVERY_STEP    = 0.01   // +1% per stable cycle
        private const val AGGR_CEIL_MIN         = 0.60
        private const val AGGR_CEIL_MAX         = 1.20
        private const val STABLE_BAND_MGDL      = 18.0   // ±1 mmol = stable
        private const val STABLE_DELTA_MGDL     = 1.5    // mg/dL per 5min = flat
        private const val SOFT_LOW_BG_MGDL      = 90.0   // ~5.0 mmol
        private const val SOFT_LOW_DELTA_MGDL   = -1.5   // falling at least this fast
        private const val SOFT_LOW_MIN_IOB      = 0.3    // must have meaningful IOB

        // Rollercoaster detection
        private const val ROLLER_WINDOW_MS          = 90 * 60 * 1000L   // 90 min window
        private const val ROLLER_CROSSING_THRESHOLD = 2                  // 2+ crossings = rollercoaster
        private const val MIN_HISTORY_FOR_ROLLER    = 6                  // need ≥6 readings (~30 min)

        // General
        private const val COB_THRESHOLD_G = 5.0   // ignore cycles with active carbs
        private const val MAX_HISTORY     = 30     // ring buffer size
    }
}