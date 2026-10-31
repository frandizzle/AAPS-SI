package app.aaps.core.interfaces.smartInsulin

/**
 * Exercise in the last few hours, as a suggested cut of meal insulin.
 *
 * @param activeMin minutes of moderate or harder activity found; a heavy minute counts as 1.5
 * @param cutPct    how much less insulin to give, in percent
 */
data class AfterActivity(val activeMin: Int, val cutPct: Int)

/**
 * Remembers how active the last few hours were, so a meal soon after exercise can take less insulin: the
 * body uses insulin better for hours after it. Implementation: `RecentActivityImpl` in plugins/aps.
 */
interface RecentActivity {

    /** What the last [LOOKBACK_HOURS] hours suggest, or null when there was too little activity or the cut is turned off. */
    fun afterActivity(nowMs: Long): AfterActivity?

    companion object {

        const val LOOKBACK_HOURS = 4
    }
}
