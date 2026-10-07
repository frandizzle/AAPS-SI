package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.StringNonKey

/**
 * New pod boost: a new infusion site often runs high for a few hours (slower absorption at the new
 * site, inflammation, stress hormones from the insertion). When switched on, a new pod makes ISF
 * and basal stronger for a while.
 *
 *  - Starts at the pod change (the site-change record the pump driver writes) at [percent] and
 *    fades evenly to nothing over [hours]: the effect is strongest just after insertion.
 *  - Pauses while BG is under target - no extra insulin then - and picks up again, still fading on
 *    schedule, if BG rises back above it. Pods are often changed at or under target, so ending for
 *    good there would lose the boost before the new site has had any chance to run high.
 *  - During a meal mode or UAM only part of it is used ([forMeal]): meal modes already push hard and
 *    bypass the low guard, and a slow new site makes the meal's insulin tail longer, not smaller.
 *  - Ends for the rest of that pod once BG goes low (or the low recovery window runs), and when the
 *    user stops it.
 *  - While it runs the caller pauses learning, or the learners would make that hour permanently
 *    stronger for a site effect that is only there on pod days.
 *
 * "Ended for this pod" is stored, so a restart cannot bring a boost back that already stopped.
 */
class NewPodBoost(private val sp: SP) {

    /** [extra] is the boost now, 0.20 for +20%; 0 when inactive. [paused]: in the window, but BG is
     *  under target so nothing extra is given (learning still pauses: the window is not over). */
    data class State(val active: Boolean, val extra: Double, val minutesLeft: Int, val paused: Boolean = false) {
        /** Basal is multiplied by this, ISF divided by it. */
        val multiplier: Double get() = 1.0 + extra
    }

    /** Called with a short description when a boost starts or ends - the plugin journals it. */
    var onEvent: ((String) -> Unit)? = null

    var state = State(false, 0.0, 0)
        private set

    /** The boost to dose with this cycle: [state], cut down by [forMeal] during a meal. */
    var dosing = State(false, 0.0, 0)
        private set

    /** Share of the boost used in this meal, 50 for half; null when not in a meal. */
    var mealPercent: Int? = null
        private set

    private var lastSiteChangeMs = 0L

    private var endedForSiteChangeMs: Long
        get() = sp.getString(StringNonKey.ApsSmartInsulinNewPodBoostEndedFor.key, "0").toLongOrNull() ?: 0L
        set(value) = sp.edit { putString(StringNonKey.ApsSmartInsulinNewPodBoostEndedFor.key, value.toString()) }

    /**
     * Call once per loop cycle.
     * @param siteChangeMs time of the last pod/site change, or null if none is known
     * @param belowTarget BG is under target now - pauses the boost
     * @param lowActive BG is under the low guard, or the low recovery window is running
     */
    fun evaluate(
        nowMs: Long, siteChangeMs: Long?, enabled: Boolean, percent: Int, hours: Int,
        belowTarget: Boolean, lowActive: Boolean
    ): State {
        val wasActive = state.active || state.paused
        state = compute(nowMs, siteChangeMs, enabled, percent, hours, belowTarget, lowActive)
        if (state.active && (!wasActive || siteChangeMs != lastSiteChangeMs))
            onEvent?.invoke("New pod: ISF and basal +${(state.extra * 100).toInt()}%, fading to 0% over ${state.minutesLeft / 60}h${state.minutesLeft % 60}m. Learning paused while it runs.")
        if (siteChangeMs != null) lastSiteChangeMs = siteChangeMs
        dosing = state
        mealPercent = null
        return state
    }

    /**
     * Call after [evaluate], once the meal mode for this cycle is known. During a meal mode or UAM
     * only [percent] of the boost is used (0 = none, 100 = all of it). It still fades on the same
     * schedule. Learning and the time left are not changed.
     */
    fun forMeal(inMeal: Boolean, percent: Int): State {
        if (!inMeal || !state.active) {
            dosing = state; mealPercent = null
            return dosing
        }
        val pct = percent.coerceIn(0, 100)
        mealPercent = pct
        dosing = state.copy(extra = state.extra * pct / 100.0)
        return dosing
    }

    private fun compute(
        nowMs: Long, siteChangeMs: Long?, enabled: Boolean, percent: Int, hours: Int,
        belowTarget: Boolean, lowActive: Boolean
    ): State {
        val off = State(false, 0.0, 0)
        if (!enabled || siteChangeMs == null || percent <= 0 || hours <= 0) return off
        if (siteChangeMs == endedForSiteChangeMs) return off
        val durationMs = hours * 3_600_000L
        val elapsedMs = nowMs - siteChangeMs
        val running = state.active || state.paused
        if (elapsedMs < 0 || elapsedMs >= durationMs) {
            if (running) onEvent?.invoke("New pod boost finished.")
            return off
        }
        if (lowActive) {
            if (running) onEvent?.invoke("New pod boost ended early: BG went low.")
            endedForSiteChangeMs = siteChangeMs
            return off
        }
        val minutesLeft = ((durationMs - elapsedMs) / 60_000L).toInt()
        if (belowTarget) return State(false, 0.0, minutesLeft, paused = true)
        val extra = percent / 100.0 * (1.0 - elapsedMs.toDouble() / durationMs)
        return State(true, extra, minutesLeft)
    }

    /** The user stopped it from the SI tab: off for the rest of this pod. */
    fun endNow() {
        if (!state.active && !state.paused) return
        endedForSiteChangeMs = lastSiteChangeMs
        state = State(false, 0.0, 0)
        dosing = state
        mealPercent = null
        onEvent?.invoke("New pod boost stopped by you.")
    }
}
