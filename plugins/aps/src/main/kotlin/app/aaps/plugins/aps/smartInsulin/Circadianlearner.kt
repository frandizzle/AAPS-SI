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
     * @param profileBasalUh Profile basal rate U/h
     * @param targetMgdl     Current target BG
     */
    fun update(
        glucoseStatus:  GlucoseStatus,
        iobArray:       Array<IobTotal>,
        mealMode:       MealMode,
        cobG:           Double,
        profileIsfMgdl: Double,
        profileBasalUh: Double,
        targetMgdl:     Double
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

        // ── 1. ISF learning (deviation-based) ────────────────────────────────
        updateIsfLearner(hour, glucoseStatus, iobArray, profileIsfMgdl)

        // ── 2. Basal learning (fasting drift) ────────────────────────────────
        updateBasalLearner(hour, delta, iobArray, profileBasalUh)

        // ── 3. Aggressiveness ceiling learning ───────────────────────────────
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

        // Ratio: if actual < expected → ISF is too low (over-aggressive) → mult > 1
        // if actual > expected → ISF is too high (under-aggressive) → mult < 1
        val ratio = if (expectedDelta != 0.0) actualDelta / expectedDelta else 1.0
        // Clamp ratio to reasonable range before learning
        val clampedRatio = ratio.coerceIn(0.5, 2.0)

        // New ISF multiplier = current × ratio (>1 = need higher ISF = less aggressive)
        val newMult = (isfState.get(hour) * clampedRatio).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
        isfState = isfState.updated(hour, newMult, ISF_ALPHA)

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner ISF h=$hour expectedΔ=%.1f actualΔ=%.1f ratio=%.2f → mult=%.3f"
                             .format(expectedDelta, actualDelta, clampedRatio, isfState.get(hour)))
    }

    // ── Basal learner ─────────────────────────────────────────────────────────

    private fun updateBasalLearner(
        hour:           Int,
        delta:          Double,
        iobArray:       Array<IobTotal>,
        profileBasalUh: Double
    ) {
        val basalIob = iobArray.firstOrNull()?.basaliob ?: run {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: no iobArray"); return
        }
        if (abs(basalIob) > MAX_BASAL_IOB_FOR_LEARNING) {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: basalIob=${"%.2f".format(basalIob)} > $MAX_BASAL_IOB_FOR_LEARNING")
            return
        }
        if (abs(delta) < MIN_DELTA_FOR_BASAL) {
            aapsLogger.debug(LTag.APS, "CircadianLearner Basal skip: delta=${"%.2f".format(delta)} < $MIN_DELTA_FOR_BASAL")
            return
        }

        // Positive drift → basal too low → multiplier > 1
        // Negative drift → basal too high → multiplier < 1
        val adjustment = 1.0 + (delta / BASAL_DRIFT_SENSITIVITY)
        val newMult = (basalState.get(hour) * adjustment).coerceIn(BASAL_MULT_MIN, BASAL_MULT_MAX)
        basalState = basalState.updated(hour, newMult, BASAL_ALPHA)

        aapsLogger.debug(LTag.APS,
                         "CircadianLearner Basal h=$hour delta=%.2f → mult=%.3f"
                             .format(delta, basalState.get(hour)))
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

        // Basal learner
        private const val BASAL_ALPHA                  = 0.06
        private const val BASAL_MULT_MIN               = 0.5
        private const val BASAL_MULT_MAX               = 1.5
        private const val MAX_BASAL_IOB_FOR_LEARNING   = 0.3   // only learn when basal IOB is near zero
        private const val MIN_DELTA_FOR_BASAL          = 0.5   // mg/dL per 5min minimum drift to learn
        private const val BASAL_DRIFT_SENSITIVITY      = 10.0  // mg/dL drift that maps to 10% basal change

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