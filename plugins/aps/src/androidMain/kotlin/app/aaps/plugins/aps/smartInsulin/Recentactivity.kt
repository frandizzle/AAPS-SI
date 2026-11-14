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
        val minutes = exerciseMin(nowMs)
        val cut = cutFor(minutes).coerceAtMost(maxPct)
        return if (cut > 0) AfterActivity(minutes.roundToInt(), cut) else null
    }

    /** Minutes of real exercise in the last [RecentActivity.LOOKBACK_HOURS] hours; a heavy minute counts as 1.5. */
    private fun exerciseMin(nowMs: Long): Double =
        synchronized(this) { samples.filter { it.first >= nowMs - LOOKBACK_MS }.sumOf { credit(it.second) } } * CYCLE_MIN

    /**
     * True when the last [RecentActivity.LOOKBACK_HOURS] hours had at least [MIN_EXERCISE_MIN] minutes of real
     * exercise. A meal low then may be the exercise, not the dose, so the meal learners do not take it at full weight.
     */
    fun exercised(nowMs: Long): Boolean = exerciseMin(nowMs) >= MIN_EXERCISE_MIN

    companion object {

        /**
         * Steps in 5 min that make a cycle of a Golf or Gym session count as exercise, see [levelWithSession]. Walking
         * between shots gives 300 to 500; waiting and putting close to 0.
         */
        const val SESSION_MIN_STEPS_5MIN = 100

        /**
         * The level to count for a cycle: [level], but at least moderate while a Golf or Gym session the user started
         * runs ([sessionActive]) and the steps show movement. A watch that reads heart rate badly makes the user set the
         * resting heart rate high, and stop-and-go walking never reaches the moderate step count by itself: on 14 Nov a
         * 4 h round of golf counted as no exercise at all, and the lunch after it got its full dose and went low.
         */
        fun levelWithSession(level: ActivityLevel, sessionActive: Boolean, steps5min: Int): ActivityLevel =
            if (sessionActive && steps5min >= SESSION_MIN_STEPS_5MIN && level.ordinal < ActivityLevel.MODERATE.ordinal) ActivityLevel.MODERATE
            else level

        /** Less exercise than this (a run up the stairs) does not change how a meal is learned. */
        const val MIN_EXERCISE_MIN = 10.0
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
