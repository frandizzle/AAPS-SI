package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * A stretch of entered carbs, treated as a meal episode in its own right.
 *
 * When carbs are entered the loop knows what is coming, so the stock oref treatment applies: the
 * COB curve predicts the rise and the dose follows it. That makes UAM redundant for this meal and
 * actively harmful — the carb model doses the rise in advance, then UAM would dose the same rise
 * again when it shows up in BG. So a carb episode stands UAM and P/F down for its duration, and
 * the two are alternatives per meal rather than partners.
 *
 * What the episode is for, beyond the gate:
 *   - It files the meal under a window ([MealMode]) by the clock at the moment carbs were entered,
 *     so 12:30 carbs are lunch's — the same windows the UAM detector uses.
 *   - It ends when COB returns to zero, which starts the post-meal lockout exactly as a UAM or
 *     meal mode ending would.
 *   - Only insulin-curve learning (DIA/peak) runs against it. The ISF/basal learners already stand
 *     down above 5 g of COB, and the mode ISF, entry-fraction and DURA learners are told to sit it
 *     out: their whole basis is judging a dose the LOOP chose, and here the dose came from carbs
 *     the user counted.
 */
@SingleIn(AppScope::class)
class CarbEpisodeManager @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    /** The window this episode is filed under, null when no carbs are on board. */
    var activeMode: MealMode? = null
        private set
    var startMs: Long = 0L
        private set
    /** Highest COB seen this episode, for the status line. */
    var peakCobG: Double = 0.0
        private set

    val active: Boolean get() = activeMode != null

    companion object {
        /** COB above this opens an episode. Same 5 g the circadian learner already steps aside at,
         *  so the two agree on when carbs are "on board" rather than rounding noise. */
        const val COB_OPEN_G  = 5.0
        /** ...and it closes once COB has effectively run out. */
        const val COB_CLOSE_G = 0.5
    }

    /**
     * Call once per cycle.
     *
     * @param windowAt the meal window for the current clock time, or null outside them all
     * @return the episode that just ENDED this cycle, for the caller to start the lockout on
     */
    fun onCycle(cobG: Double, nowMs: Long, windowAt: MealMode?): MealMode? {
        if (!active) {
            if (cobG >= COB_OPEN_G) {
                // Outside every configured window it is still a carb episode — it just has no
                // window to learn the insulin curve under, so it files as a generic one.
                activeMode = windowAt ?: MealMode.LOW_CARB
                startMs    = nowMs
                peakCobG   = cobG
                aapsLogger.debug(LTag.APS, "CarbEpisode: ${activeMode?.label} opened with ${"%.0f".format(cobG)}g — UAM and P/F stand down")
            }
            return null
        }
        if (cobG > peakCobG) peakCobG = cobG
        if (cobG > COB_CLOSE_G) return null
        val ended = activeMode
        aapsLogger.debug(LTag.APS, "CarbEpisode: ${ended?.label} closed after ${(nowMs - startMs) / 60_000}min, peak ${"%.0f".format(peakCobG)}g")
        activeMode = null
        startMs    = 0L
        peakCobG   = 0.0
        return ended
    }

    fun statusLine(cobG: Double, nowMs: Long): String? {
        val mode = activeMode ?: return null
        return "Carbs: ${mode.label} — ${"%.0f".format(cobG)}g on board, ${(nowMs - startMs) / 60_000}min in " +
            "(dosing from the COB curve, UAM/P/F standing down)"
    }

    fun reset() { activeMode = null; startMs = 0L; peakCobG = 0.0 }
}
