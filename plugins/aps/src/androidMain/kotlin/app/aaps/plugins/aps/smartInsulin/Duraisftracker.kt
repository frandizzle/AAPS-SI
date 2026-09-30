package app.aaps.plugins.aps.smartInsulin

import javax.inject.Inject
import javax.inject.Singleton

/**
 * DURA_ISF, adapted from openAPS AutoISF: the longer BG sits "stuck" (roughly flat) above
 * target, the more the effective ISF is strengthened (lowered). AutoISF computes this by
 * walking backward over historical BG readings every cycle; here it's tracked incrementally
 * cycle-by-cycle instead, since that's the shape SmartInsulin's other learners already use.
 *
 * Scoped to meal-mode activations: callers reset the tracker (via `active = false`) whenever
 * DURA isn't enabled for the currently active mode, so a stuck-BG plateau from one activation
 * never leaks into the next.
 */
@Singleton
class DuraIsfTracker @Inject constructor() {

    private var anchorMgdl   = 0.0
    private var stuckMinutes = 0.0

    companion object {
        private const val BAND_FRACTION     = 0.05  // within ±5% of the running average counts as "stuck"
        private const val MIN_STUCK_MINUTES = 10.0  // ignore short-lived plateaus
        // Default ramp weight when the caller doesn't supply one — mirrors AutoISF's
        // dura_ISF_weight default of "no adjustment beyond the original fixed behaviour".
        const val DEFAULT_WEIGHT            = 1.0
        // A single cycle falling at least this fast (-0.2 mmol/5min) resets immediately, regardless
        // of the band check. The band alone is too forgiving for a real, gradual recovery: since the
        // anchor incrementally follows in-band values, a slow steady decline can keep getting absorbed
        // into the anchor cycle after cycle without ever breaking out — so stuckMinutes would keep
        // climbing even while BG is genuinely (if slowly) coming down.
        private const val RESET_ON_DROP_MGDL = -3.6
    }

    /** Call once per loop cycle. [active] should be false whenever DURA isn't currently enabled
     *  for the active meal mode — resets the tracker so a later activation starts clean.
     *  [deltaMgdl] is this cycle's BG delta (mg/dL/5min) — a fast enough drop resets immediately. */
    fun onCycle(bgMgdl: Double, active: Boolean, deltaMgdl: Double = 0.0, cycleMinutes: Double = 5.0) {
        if (!active || deltaMgdl <= RESET_ON_DROP_MGDL) { reset(); return }
        if (anchorMgdl <= 0.0) {
            anchorMgdl   = bgMgdl
            stuckMinutes = 0.0
            return
        }
        val band = anchorMgdl * BAND_FRACTION
        if (bgMgdl > anchorMgdl - band && bgMgdl < anchorMgdl + band) {
            val priorSamples = (stuckMinutes / cycleMinutes) + 1.0
            anchorMgdl += (bgMgdl - anchorMgdl) / priorSamples
            stuckMinutes += cycleMinutes
        } else {
            anchorMgdl   = bgMgdl
            stuckMinutes = 0.0
        }
    }

    fun reset() {
        anchorMgdl   = 0.0
        stuckMinutes = 0.0
    }

    /** Divisor to apply to ISF (>1.0 strengthens/lowers ISF). 1.0 = no effect.
     *  [weight] scales how fast the multiplier ramps — user-configurable per mode
     *  (0 = DURA has no effect, higher = strengthens ISF faster while stuck). */
    fun multiplier(targetMgdl: Double, weight: Double = DEFAULT_WEIGHT): Double {
        if (stuckMinutes < MIN_STUCK_MINUTES || anchorMgdl <= targetMgdl || targetMgdl <= 0.0 || weight <= 0.0) return 1.0
        val stuckHours = stuckMinutes / 60.0
        val avgWeight  = weight / targetMgdl
        return 1.0 + stuckHours * avgWeight * (anchorMgdl - targetMgdl)
    }

    val stuckMinutesForDisplay: Double get() = stuckMinutes
    val stuckAverageMgdlForDisplay: Double get() = anchorMgdl
}
