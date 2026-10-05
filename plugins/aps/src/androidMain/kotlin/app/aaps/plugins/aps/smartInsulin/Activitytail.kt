package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.ActivityMonitor.ActivityLevel

/**
 * Keeps learning paused for a short while after activity stops, while BG is still falling faster
 * than insulin explains.
 *
 * Learning already pauses while the watch or phone sees activity. But that ends the moment you sit
 * down, and the effect does not: a 400 m sprint at 8.0 with 2 U on board keeps driving that insulin
 * hard for a while afterwards. Learning would then come back on in the middle of the drop and charge
 * the low to the hour's ISF and basal, which did nothing wrong.
 *
 * A fixed window after activity would be wrong the other way: one short burst of activity, with BG
 * not affected at all, would pause learning for hours. So the pause follows BG itself:
 *  - always at least [MIN_TAIL_MS] after the last active reading, since the CGM lags;
 *  - after that, only while BG is still falling faster than insulin explains;
 *  - never longer than the cap for the hardest level seen in this bout of activity.
 * Once it ends it stays ended until there is new activity.
 */
class ActivityTail {

    private var lastActiveMs = 0L
    private var boutLevel = ActivityLevel.SEDENTARY

    /** True while learning should stay paused after activity has stopped. */
    var paused = false
        private set

    /**
     * Call once per loop cycle.
     * @param level the activity level seen this cycle
     * @param stillDropping BG is falling faster than insulin explains right now
     */
    fun onCycle(nowMs: Long, level: ActivityLevel, stillDropping: Boolean) {
        if (level != ActivityLevel.SEDENTARY) {
            // Activity is happening: that pause is the activity monitor's own. Remember the bout.
            lastActiveMs = nowMs
            if (level.ordinal > boutLevel.ordinal) boutLevel = level
            paused = false
            return
        }
        if (lastActiveMs == 0L) { paused = false; return }
        val sinceMs = nowMs - lastActiveMs
        paused = when {
            sinceMs > capMs(boutLevel) -> false
            sinceMs < MIN_TAIL_MS      -> true
            else                       -> stillDropping
        }
        if (!paused) { lastActiveMs = 0L; boutLevel = ActivityLevel.SEDENTARY }
    }

    private fun capMs(level: ActivityLevel): Long = when (level) {
        ActivityLevel.HEAVY    -> HEAVY_CAP_MS
        ActivityLevel.MODERATE -> MODERATE_CAP_MS
        else                   -> LIGHT_CAP_MS
    }

    companion object {
        const val MIN_TAIL_MS     = 15 * 60_000L
        const val LIGHT_CAP_MS    = 30 * 60_000L
        const val MODERATE_CAP_MS = 60 * 60_000L
        const val HEAVY_CAP_MS    = 90 * 60_000L
    }
}
