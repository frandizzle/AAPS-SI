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

    /** EWMA update for a single hour bucket — bumps both value AND confidence.
     *  Use for genuine observations of this hour's physiology: ISF fast-path
     *  firings, ISF episode closes, basal drift firings, neg-IOB firings,
     *  predictive trim firings, and aggr penalty events. */
    fun updated(hour: Int, newValue: Double, alpha: Double): CircadianState {
        val h = hour.coerceIn(0, 23)
        val newValues = values.copyOf()
        val newConf   = confidence.copyOf()
        newValues[h] = values[h] * (1.0 - alpha) + newValue * alpha
        newConf[h]   = (confidence[h] * (1.0 - CONF_ALPHA) + CONF_ALPHA).coerceIn(0.0, 1.0)
        return CircadianState(newValues, newConf)
    }

    /** EWMA update for VALUE ONLY — does NOT bump confidence.
     *
     *  Use for "bookkeeping" updates that don't reflect a new observation about
     *  this hour's physiology. The motivating case is the aggr recovery branch:
     *  it only ever fires when the ceiling is below 1.0, so counting recovery
     *  cycles as confidence observations structurally biases confidence toward
     *  hours that have been penalised — an hour with one rollercoaster + 10
     *  stable recovery cycles ends up with HIGHER aggr confidence than an hour
     *  that was perfectly stable the whole time and never needed recovery.
     *  That's backwards.
     *
     *  Real events (penalties, episode closes, drift firings) still bump
     *  confidence via updated(). Recovery just unwinds the value. */
    fun updatedNoConf(hour: Int, newValue: Double, alpha: Double): CircadianState {
        val h = hour.coerceIn(0, 23)
        val newValues = values.copyOf()
        newValues[h] = values[h] * (1.0 - alpha) + newValue * alpha
        return CircadianState(newValues, confidence)
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
        // Confidence EWMA alpha — controls how fast confidence approaches 1.0
        // as observations accumulate.
        //
        // Reaches:
        //   30% at ~18 observations  (amber threshold)
        //   50% at ~35 observations
        //   60% at ~46 observations  (green threshold)
        //   90% at ~115 observations
        //
        // At the ISF fast-path's typical cadence (~20–40 fires/day during
        // fasting windows) that's roughly:
        //   1–2 days to amber
        //   3–5 days to green
        //   2–3 weeks to 90%
        //
        // For episode-driven learners (ISF episode close, basal drift firing —
        // a few per day at most) confidence builds over weeks, which correctly
        // reflects that those observations are rare and structural.
        //
        // Previous value (0.10) reached 90% in ~22 observations, which at the
        // aggr learner's per-cycle cadence meant ~2 hours to "fully trained".
        // That was wrong: confidence should reflect multi-day pattern stability,
        // not single-hour activity bursts.
        const val CONF_ALPHA = 0.02
    }
}