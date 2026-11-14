package app.aaps.core.interfaces.smartInsulin

/**
 * Exercise in the last few hours, as a suggested cut of meal insulin.
 *
 * @param activeMin    minutes of moderate or harder activity found; a heavy minute counts as 1.5
 * @param cutPct       how much less insulin to give, in percent
 * @param afterSession the cut comes from a Golf or Gym session earlier today, not the last [RecentActivity.LOOKBACK_HOURS]
 *                     hours: it lasts longer and gets smaller with time, see [RecentActivity.SESSION_TAIL_HOURS]
 */
data class AfterActivity(val activeMin: Int, val cutPct: Int, val afterSession: Boolean = false)

/**
 * Remembers how active the last few hours were, so a meal soon after exercise can take less insulin: the
 * body uses insulin better for hours after it. Implementation: `RecentActivityImpl` in plugins/aps.
 */
interface RecentActivity {

    /** What the last [LOOKBACK_HOURS] hours suggest, or null when there was too little activity or the cut is turned off. */
    fun afterActivity(nowMs: Long): AfterActivity?

    companion object {

        const val LOOKBACK_HOURS = 4
        /** After a Golf or Gym session the cut lasts this long from its end: in full for [LOOKBACK_HOURS], then less and less. */
        const val SESSION_TAIL_HOURS = 12
    }
}
