package app.aaps.plugins.aps.smartInsulin

/**
 * Per-hour learned state for a single circadian parameter.
 * 24 buckets indexed by hour-of-day (0–23).
 *
 * @param values     The learned multiplier/value per hour
 * @param confidence Sample confidence per hour (0.0–1.0, EWMA of observation count)
 */
data class CircadianState(
    val values:     DoubleArray = DoubleArray(24) { 1.0 },
    val confidence: DoubleArray = DoubleArray(24) { 0.0 }
) {
    fun get(hour: Int): Double = values[hour.coerceIn(0, 23)]
    fun getConfidence(hour: Int): Double = confidence[hour.coerceIn(0, 23)]

    /**
     * Minute-aware read. Hour buckets are treated as samples centred at :30 and the value
     * between two centres is linearly interpolated, so the effective multiplier is continuous
     * in time instead of a 24-step staircase.
     *
     * Why this matters: without it, learning earned at 17:50 lives entirely in bucket 17 and is
     * discarded the instant the clock reaches 18:00, which hands the loop back a multiplier that
     * has no knowledge of the episode still in progress. With it, 17:50 already reads 1/3 of
     * bucket 18, and 18:05 still reads 42% of bucket 17 — no cliff at the boundary.
     *
     * The neighbour weight peaks at 29/60 (0.483) and is 0 at :30, so the hour's own bucket
     * always dominates its own hour.
     */
    fun get(hour: Int, minute: Int): Double {
        val h = hour.coerceIn(0, 23)
        val (nb, w) = neighbour(h, minute)
        return if (w <= 0.0) values[h] else values[h] * (1.0 - w) + values[nb] * w
    }

    /** Minute-aware confidence read — same interpolation as [get]. */
    fun getConfidence(hour: Int, minute: Int): Double {
        val h = hour.coerceIn(0, 23)
        val (nb, w) = neighbour(h, minute)
        return if (w <= 0.0) confidence[h] else confidence[h] * (1.0 - w) + confidence[nb] * w
    }

    /** EWMA update for a single hour bucket */
    fun updated(hour: Int, newValue: Double, alpha: Double): CircadianState {
        val h = hour.coerceIn(0, 23)
        val newValues = values.copyOf()
        val newConf   = confidence.copyOf()
        newValues[h] = values[h] * (1.0 - alpha) + newValue * alpha
        newConf[h]   = (confidence[h] * (1.0 - CONF_ALPHA) + CONF_ALPHA).coerceIn(0.0, 1.0)
        return CircadianState(newValues, newConf)
    }

    /**
     * EWMA update that also bleeds a fraction of the observation into the adjacent hour bucket,
     * weighted by how close the current minute is to that neighbour's centre (same weight [get]
     * uses to read).
     *
     * This is the write-side half of the boundary fix: an episode that runs through 17:40–17:59
     * has already moved bucket 18 by ~40% of what it moved bucket 17, so crossing into hour 18
     * does not drop the loop back onto an uninformed multiplier. Bucket 18 still keeps its own
     * independent history — the neighbour write is a minority contribution, never a copy.
     */
    fun updatedSmoothed(hour: Int, minute: Int, newValue: Double, alpha: Double): CircadianState {
        val h = hour.coerceIn(0, 23)
        val newValues = values.copyOf()
        val newConf   = confidence.copyOf()
        newValues[h] = values[h] * (1.0 - alpha) + newValue * alpha
        newConf[h]   = (confidence[h] * (1.0 - CONF_ALPHA) + CONF_ALPHA).coerceIn(0.0, 1.0)
        val (nb, w) = neighbour(h, minute)
        if (w > 0.0) {
            val na = (alpha * w).coerceIn(0.0, 1.0)
            val nc = (CONF_ALPHA * w).coerceIn(0.0, 1.0)
            newValues[nb] = values[nb] * (1.0 - na) + newValue * na
            newConf[nb]   = (confidence[nb] * (1.0 - nc) + nc).coerceIn(0.0, 1.0)
        }
        return CircadianState(newValues, newConf)
    }

    /**
     * The adjacent bucket the given minute leans toward, and how strongly (0.0 at :30, rising to
     * 0.483 at :00/:59). Wraps across midnight, so hour 23 leans into hour 0.
     */
    private fun neighbour(hour: Int, minute: Int): Pair<Int, Double> {
        val m = minute.coerceIn(0, 59)
        return if (m >= 30) Pair((hour + 1) % 24, (m - 30) / 60.0)
        else                Pair((hour + 23) % 24, (30 - m) / 60.0)
    }

    // --- ADD THESE OVERRIDES ---
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CircadianState) return false
        return values.contentEquals(other.values) && confidence.contentEquals(other.confidence)
    }

    override fun hashCode(): Int = 31 * values.contentHashCode() + confidence.contentHashCode()
    // ---------------------------

    companion object {
        // Confidence EWMA alpha — ~7 observations (~35 min @ 5-min cycle) to reach 0.5.
        // Separate from (and faster than) the ISF/basal value alphas: we want to know
        // "have we seen this hour enough times" independent of how fast values converge.
        const val CONF_ALPHA = 0.10
    }
}
