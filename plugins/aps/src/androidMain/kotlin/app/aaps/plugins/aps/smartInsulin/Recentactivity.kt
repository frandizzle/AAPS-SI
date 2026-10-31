package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.smartInsulin.AfterActivity
import app.aaps.core.interfaces.smartInsulin.RecentActivity
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.smartInsulin.ActivityMonitor.ActivityLevel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToInt

/**
 * The activity level of each loop cycle over the last [RecentActivity.LOOKBACK_HOURS] hours, as minutes
 * of real exercise, and the cut of meal insulin they suggest.
 *
 * Light activity does not count: walking around the house or a shop is part of every day and is already
 * in the profile. Moderate counts in full and heavy as 1.5, because harder exercise leaves the body more
 * sensitive. Only kept in memory: after a restart nothing is suggested, which gives the normal dose.
 */
@SingleIn(AppScope::class)
class RecentActivityImpl @Inject constructor(private val sp: KeyValueStore) : RecentActivity {

    private val samples = ArrayDeque<Pair<Long, ActivityLevel>>()

    /** Call once per loop cycle. A second call within [MIN_GAP_MS] is ignored, so an extra loop run does not count twice. */
    fun onCycle(nowMs: Long, level: ActivityLevel) = synchronized(this) {
        val last = samples.lastOrNull()
        if (last != null && nowMs - last.first < MIN_GAP_MS) return@synchronized
        samples.addLast(nowMs to level)
        while (samples.first().first < nowMs - LOOKBACK_MS) samples.removeFirst()
    }

    override fun afterActivity(nowMs: Long): AfterActivity? {
        val maxPct = sp.getInt(IntKey.ApsSmartInsulinAfterActivityMaxPct.key, IntKey.ApsSmartInsulinAfterActivityMaxPct.defaultValue)
        if (maxPct <= 0) return null
        val minutes = synchronized(this) { samples.filter { it.first >= nowMs - LOOKBACK_MS }.sumOf { credit(it.second) } } * CYCLE_MIN
        val cut = cutFor(minutes).coerceAtMost(maxPct)
        return if (cut > 0) AfterActivity(minutes.roundToInt(), cut) else null
    }

    companion object {

        const val LOOKBACK_MS = RecentActivity.LOOKBACK_HOURS * 60 * 60_000L
        const val MIN_GAP_MS = 4 * 60_000L
        const val CYCLE_MIN = 5.0

        private fun credit(level: ActivityLevel): Double = when (level) {
            ActivityLevel.HEAVY    -> 1.5
            ActivityLevel.MODERATE -> 1.0
            else                   -> 0.0
        }

        /** The cut for [activeMin] minutes of exercise: 30 min or more 10 %, an hour 15 %, an hour and a half 20 %. */
        fun cutFor(activeMin: Double): Int = when {
            activeMin >= 90.0 -> 20
            activeMin >= 60.0 -> 15
            activeMin >= 30.0 -> 10
            else              -> 0
        }
    }
}
