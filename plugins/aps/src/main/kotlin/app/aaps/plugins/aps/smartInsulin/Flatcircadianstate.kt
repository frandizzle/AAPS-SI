package app.aaps.plugins.aps.smartInsulin

import org.json.JSONObject
import java.util.Calendar

/**
 * Flat 24-hour circadian state — replaces [DayOfWeekCircadianState].
 *
 * Previous architecture: 7 day-specific [CircadianState] arrays + 1 global,
 * giving 168 learning cells updated at most 12 times/week each.
 *
 * Problem: data starvation. With ISF_ALPHA=0.08, a cell receiving 3–4 clean
 * fasting samples per week corrects only ~20–30% of the true error per week.
 * The AggressionLearner's 24h TIR window heals in 24 hours, creating a temporal
 * mismatch — aggression returns to 1.0 before Circadian has fixed the root cause.
 *
 * Solution: collapse to 24 cells updated every day. Each hour now receives 7×
 * more samples, converging in days rather than weeks. Day-specific exceptions
 * (exercise, sleep-ins) are already handled by real-time modules (ActivityMonitor,
 * UAM, temp targets) — CircadianLearner only needs a solid 24h hormonal baseline.
 *
 * Migration: on first load of old 7-day JSON, each hour's flat value is computed
 * as the confidence-weighted average across all 8 buckets (7 days + global),
 * preserving accumulated learning history rather than discarding it.
 *
 * API is intentionally identical to [DayOfWeekCircadianState] — [get], [updated],
 * [getConfidence] all accept a [dayOfWeek] parameter that is silently ignored,
 * so all call sites in [CircadianLearner] require zero changes.
 */
data class FlatCircadianState(
    val state: CircadianState = CircadianState()
) {
    // ── Read ──────────────────────────────────────────────────────────────────

    /** [dayOfWeek] ignored — all days share the same 24-hour baseline */
    fun get(dayOfWeek: Int, hour: Int): Double = state.get(hour)

    /** [dayOfWeek] ignored */
    fun getConfidence(dayOfWeek: Int, hour: Int): Double = state.getConfidence(hour)

    // ── Write ─────────────────────────────────────────────────────────────────

    /** [dayOfWeek] ignored — every observation updates the shared baseline */
    fun updated(dayOfWeek: Int, hour: Int, newValue: Double, alpha: Double): FlatCircadianState =
        FlatCircadianState(state.updated(hour, newValue, alpha))

    // ── Serialisation ─────────────────────────────────────────────────────────

    fun toJson(): JSONObject {
        val vArr = org.json.JSONArray()
        val cArr = org.json.JSONArray()
        for (i in 0..23) { vArr.put(state.values[i]); cArr.put(state.confidence[i]) }
        return JSONObject().put("values", vArr).put("confidence", cArr)
    }

    companion object {
        val DAY_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        fun currentDayOfWeek(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

        /** Deserialise flat format (new) */
        fun fromJson(json: JSONObject): FlatCircadianState {
            val vArr   = json.getJSONArray("values")
            val cArr   = json.getJSONArray("confidence")
            val values = DoubleArray(24) { vArr.getDouble(it) }
            val conf   = DoubleArray(24) { cArr.getDouble(it) }
            return FlatCircadianState(CircadianState(values, conf))
        }

        /**
         * Migrate from old 7-day JSON format (has "global" + "days" keys).
         * For each hour: confidence-weighted average of all 8 buckets that
         * had non-zero confidence, preserving accumulated learning history.
         * Confidence of merged result = avg bucket confidence, capped at 1.0.
         * Returns null if json is not old format.
         */
        fun migrateFromDayOfWeek(json: JSONObject): FlatCircadianState? {
            if (!json.has("global") || !json.has("days")) return null

            val values     = DoubleArray(24) { 1.0 }
            val confidence = DoubleArray(24) { 0.0 }

            for (hour in 0..23) {
                var weightedSum = 0.0
                var totalWeight = 0.0

                // global bucket
                val globalObj  = json.getJSONObject("global")
                val globalVal  = globalObj.getJSONArray("values").getDouble(hour)
                val globalConf = globalObj.getJSONArray("confidence").getDouble(hour)
                if (globalConf > 0.0) {
                    weightedSum += globalVal * globalConf
                    totalWeight += globalConf
                }

                // 7 day buckets
                val daysArr = json.getJSONArray("days")
                for (d in 0..6) {
                    val dayObj  = daysArr.getJSONObject(d)
                    val dayVal  = dayObj.getJSONArray("values").getDouble(hour)
                    val dayConf = dayObj.getJSONArray("confidence").getDouble(hour)
                    if (dayConf > 0.0) {
                        weightedSum += dayVal * dayConf
                        totalWeight += dayConf
                    }
                }

                if (totalWeight > 0.0) {
                    values[hour]     = weightedSum / totalWeight
                    // Scale confidence: 8 fully-confident buckets = 1.0
                    confidence[hour] = (totalWeight / 8.0).coerceAtMost(1.0)
                }
                // else: no data → stays at default 1.0 value / 0.0 confidence
            }

            return FlatCircadianState(CircadianState(values, confidence))
        }
    }
}