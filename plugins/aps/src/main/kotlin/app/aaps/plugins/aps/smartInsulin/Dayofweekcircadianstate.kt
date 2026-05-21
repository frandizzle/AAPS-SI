package app.aaps.plugins.aps.smartInsulin

import org.json.JSONObject
import java.util.Calendar

/**
 * 7-day aware wrapper around [CircadianState].
 *
 * Holds 7 per-day [CircadianState] instances (indexed 0=Sun..6=Sat, matching
 * [Calendar.DAY_OF_WEEK] - 1) plus one [global] state that accumulates every
 * observation regardless of day.
 *
 * ## Read path
 * [get] and [getConfidence] return a blend:
 *   - If the day-specific bucket has confidence ≥ [DAY_CONFIDENCE_THRESHOLD],
 *     return the day-specific value.
 *   - Otherwise blend toward the global value proportionally to how much
 *     confidence the day bucket has built up relative to the threshold.
 *     At zero day-confidence, the global value is returned entirely.
 *
 * ## Write path
 * [updated] writes to BOTH the current day bucket AND the global bucket
 * on every observation, so the global state is always current.
 *
 * ## Fallback guarantee
 * Because [global] is always updated, a fresh install or a day that has
 * never been seen will always return a meaningful value from global.
 */
data class DayOfWeekCircadianState(
    val days:   Array<CircadianState> = Array(7) { CircadianState() },
    val global: CircadianState        = CircadianState()
) {
    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the effective value for [hour] on [dayOfWeek].
     * Blends day-specific and global based on day bucket confidence.
     *
     * @param dayOfWeek  0=Sun, 1=Mon … 6=Sat (Calendar.DAY_OF_WEEK - 1)
     * @param hour       0–23
     */
    fun get(dayOfWeek: Int, hour: Int): Double {
        val d       = dayOfWeek.coerceIn(0, 6)
        val dayConf = days[d].getConfidence(hour)
        val blend   = (dayConf / DAY_CONFIDENCE_THRESHOLD).coerceIn(0.0, 1.0)
        return global.get(hour) * (1.0 - blend) + days[d].get(hour) * blend
    }

    /**
     * Effective confidence for display — average of day and global confidence,
     * weighted by how much day data has accumulated.
     */
    fun getConfidence(dayOfWeek: Int, hour: Int): Double {
        val d       = dayOfWeek.coerceIn(0, 6)
        val dayConf = days[d].getConfidence(hour)
        val blend   = (dayConf / DAY_CONFIDENCE_THRESHOLD).coerceIn(0.0, 1.0)
        return global.getConfidence(hour) * (1.0 - blend) + dayConf * blend
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * EWMA update for [hour] on [dayOfWeek].
     * Writes to both the day bucket and global. Both the value AND
     * confidence are bumped — use for genuine observations of this
     * hour's physiology.
     *
     * @param dayOfWeek  0=Sun, 1=Mon … 6=Sat
     */
    fun updated(dayOfWeek: Int, hour: Int, newValue: Double, alpha: Double): DayOfWeekCircadianState {
        val d       = dayOfWeek.coerceIn(0, 6)
        val newDays = days.copyOf()
        newDays[d]  = days[d].updated(hour, newValue, alpha)
        val newGlobal = global.updated(hour, newValue, alpha)
        return DayOfWeekCircadianState(newDays, newGlobal)
    }

    /**
     * EWMA update for [hour] on [dayOfWeek] — day bucket ONLY.
     * Does NOT update global. Used by the aggression nudge which is a
     * day-specific signal — writing it to global would corrupt the
     * cross-day baseline and cause the blended output to move the wrong way.
     */
    fun updatedDayOnly(dayOfWeek: Int, hour: Int, newValue: Double, alpha: Double): DayOfWeekCircadianState {
        val d       = dayOfWeek.coerceIn(0, 6)
        val newDays = days.copyOf()
        newDays[d]  = days[d].updated(hour, newValue, alpha)
        return DayOfWeekCircadianState(newDays, global)  // global unchanged
    }

    /**
     * EWMA update for [hour] on [dayOfWeek] — value only, NO confidence bump.
     * Writes to both the day bucket and global.
     *
     * Used for "bookkeeping" updates that shouldn't count as observations of
     * this hour's physiology. The motivating case is the aggr recovery
     * branch: it only ever fires when the ceiling is below 1.0, so counting
     * recovery cycles as confidence observations structurally biases
     * confidence toward hours that have been penalised. An hour with one
     * rollercoaster + 10 stable recovery cycles ends up with HIGHER
     * confidence than an hour that was stable the whole time and never
     * needed to recover. That's backwards.
     *
     * Real events (penalties, episode closes, drift firings) still bump
     * confidence via [updated]. Recovery just unwinds the value.
     */
    fun updatedNoConf(dayOfWeek: Int, hour: Int, newValue: Double, alpha: Double): DayOfWeekCircadianState {
        val d       = dayOfWeek.coerceIn(0, 6)
        val newDays = days.copyOf()
        newDays[d]  = days[d].updatedNoConf(hour, newValue, alpha)
        val newGlobal = global.updatedNoConf(hour, newValue, alpha)
        return DayOfWeekCircadianState(newDays, newGlobal)
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("global", circadianStateToJson(global))
        val dArr = org.json.JSONArray()
        days.forEach { dArr.put(circadianStateToJson(it)) }
        put("days", dArr)
    }

    companion object {
        /**
         * Minimum day-bucket confidence before day-specific values are
         * fully trusted over the global average.
         *
         * At CONF_ALPHA=0.02, ~18 observations are needed to reach this
         * threshold — i.e. day-specific values start to take over from
         * global once we've seen this hour-of-week roughly 18 times.
         * For an ISF fast-path firing ~20–40×/day during fasting windows
         * that's typically achieved within the first day or two of a given
         * day-of-week recurring. For rarer learners (episode close, drift
         * firing) it's a few weeks of the same weekday before the day
         * bucket starts dominating, which correctly reflects how much
         * weekday-specific data we actually have.
         */
        const val DAY_CONFIDENCE_THRESHOLD = 0.3

        fun fromJson(json: JSONObject): DayOfWeekCircadianState {
            val global = circadianStateFromJson(json.getJSONObject("global"))
            val dArr   = json.getJSONArray("days")
            val days   = Array(7) { i -> circadianStateFromJson(dArr.getJSONObject(i)) }
            return DayOfWeekCircadianState(days, global)
        }

        fun currentDayOfWeek(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

        val DAY_LABELS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        private fun circadianStateToJson(s: CircadianState): JSONObject {
            val vArr = org.json.JSONArray()
            val cArr = org.json.JSONArray()
            for (i in 0..23) { vArr.put(s.values[i]); cArr.put(s.confidence[i]) }
            return JSONObject().put("values", vArr).put("confidence", cArr)
        }

        private fun circadianStateFromJson(obj: JSONObject): CircadianState {
            val vArr   = obj.getJSONArray("values")
            val cArr   = obj.getJSONArray("confidence")
            val values = DoubleArray(24) { vArr.getDouble(it) }
            val conf   = DoubleArray(24) { cArr.getDouble(it) }
            return CircadianState(values, conf)
        }
    }

    // Required for data class with Array fields
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DayOfWeekCircadianState) return false
        return days.contentDeepEquals(other.days) && global == other.global
    }

    override fun hashCode(): Int = 31 * days.contentDeepHashCode() + global.hashCode()
}