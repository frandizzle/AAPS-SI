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

    /** EWMA update for a single hour bucket */
    fun updated(hour: Int, newValue: Double, alpha: Double): CircadianState {
        val h = hour.coerceIn(0, 23)
        val newValues = values.copyOf()
        val newConf   = confidence.copyOf()
        newValues[h] = values[h] * (1.0 - alpha) + newValue * alpha
        newConf[h]   = (confidence[h] * (1.0 - CONF_ALPHA) + CONF_ALPHA).coerceIn(0.0, 1.0)
        return CircadianState(newValues, newConf)
    }

    companion object {
        const val CONF_ALPHA = 0.05   // confidence grows slowly — need ~20 observations to reach 0.64
    }
}