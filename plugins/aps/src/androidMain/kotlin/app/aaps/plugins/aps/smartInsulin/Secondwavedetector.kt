package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Spots a SECOND lot of food eaten inside a mode's own window — the late snack that would
 * otherwise teach that mode to be stronger for carbs it was never dosed for.
 *
 * Why this can't be the tail check read earlier. In the tail the premise is "absorption should be
 * finished by now, so any residual is new food". Mid-episode that premise is false: the meal the
 * mode exists for is still absorbing, and with no carb model that absorption is unexplained-by-
 * insulin by construction. Running the tail's level threshold here would flag nearly every real
 * meal and void the learning it was meant to protect.
 *
 * The insulin side needs no special handling: the residual is already measured against what the
 * current IOB activity predicts BG should be doing (see [absorptionRateGPer5Min]), so a dose still
 * ramping toward peak raises the bar on its own rather than lowering it.
 *
 * What separates a second entry from digestion lag is SHAPE. One meal's absorption rate rises,
 * peaks, and decays; a second entry re-accelerates it after that decay. So two independent signals
 * have to agree, and BOTH are required:
 *
 *   1. RATE: the absorption rate fell to [DECAY_FRACTION] of its own peak — the first meal is past
 *      its worst — and then climbed back to [REACCEL_FRACTION] of that peak for two readings.
 *   2. BG: BG descended [DESCENT_MGDL] from the episode's high and then made a NEW high past it,
 *      having climbed at a carb-like SPEED somewhere on the way ([FAST_RISE_MGDL_PER_5MIN]).
 *      The speed requirement is what keeps one meal's own fat/protein tail out: a tail that lifts
 *      BG past the earlier carb peak does it slowly, while food going in fresh does not. The
 *      number is not a new guess — it is the same rate the UAM burst already uses to call a rise
 *      "someone is eating" (1.0 mmol inside 20 min), which is calibrated to this user.
 *      It is the PEAK rate seen during the climb, not the average from the trough: a second
 *      helping that starts slow and accelerates would be averaged under the bar otherwise, which
 *      is exactly the case the check still has to catch.
 *
 * Requiring both is deliberate, and it is what makes the rate thresholds tolerable. Real mixed
 * meals are bumpy — gastric emptying isn't smooth and fat staggers carb absorption — so a single
 * meal can absolutely produce a rate curve that decays and re-accelerates. What it does not
 * produce is that curve AND a second BG peak. The BG signal is a direct measured fact; the rate
 * signal is a derived shape, and derived shapes have been the less reliable half all along, so the
 * measured one has a veto.
 *
 * The costs are asymmetric and the bias is chosen: a missed slow second helping teaches one
 * episode slightly wrong, while a false positive throws away a real meal's evidence. Missing
 * detections is the cheaper mistake, so this errs that way.
 */
@SingleIn(AppScope::class)
class SecondWaveDetector @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    private var peakRate       = 0.0
    private var smoothedRate   = 0.0
    private var decayed        = false
    private var reaccelRuns    = 0
    private var rateSignal     = false

    private var peakBgMgdl     = 0.0
    private var descended      = false
    private var bgSignal       = false
    /** Fastest climb seen since the descent — peak, not average, over [RISE_READINGS]. */
    private var maxRiseSinceDescent = 0.0
    private var prevDelta      = 0.0

    /** True once both signals have agreed within this episode. Latches until [reset]. */
    var detected: Boolean = false
        private set

    /** What fired, for the outcome line the learners print. */
    var description: String = ""
        private set

    companion object {
        /** Rate must fall this far below its own peak before a re-acceleration means anything. */
        private const val DECAY_FRACTION    = 0.4
        /** ...and then climb back to this share of it. */
        private const val REACCEL_FRACTION  = 0.6
        /** Two readings, so one noisy cycle can't do it. */
        private const val REACCEL_READINGS  = 2
        /** Below this peak rate there is no absorption curve worth reading the shape of. */
        private const val MIN_PEAK_RATE     = 0.8   // g per 5 min
        /** BG has to come down this far off the episode high before a new high counts. */
        private const val DESCENT_MGDL      = 18.0  // ~1 mmol
        /** ...and the new high has to clear the old one by this much. */
        private const val NEW_HIGH_MGDL     = 9.0   // ~0.5 mmol
        /** Fastest climb seen on the way to that new high, per 5 min. 0.2 mmol — the burst
         *  detector's own "this is fresh carbs" rate. A fat/protein tail rarely sustains it. */
        private const val FAST_RISE_MGDL_PER_5MIN = 3.6
        /** The peak rate is read over two readings, so one noisy delta can't supply it alone. */
        private const val RISE_READINGS     = 2
        /** EWMA on the rate — CGM noise moves a per-cycle derivative far more than it moves BG. */
        private const val RATE_ALPHA        = 0.4
    }

    /** Carb-equivalent absorption this cycle, with insulin's own expected effect subtracted. */
    private fun absorptionRateGPer5Min(deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double): Double {
        if (carbRatio <= 0.0 || isfMgdl <= 0.0) return 0.0
        val ci = deltaMgdl - (-activityPerMin * isfMgdl * 5.0)
        if (ci <= 0.0) return 0.0
        return ci / (isfMgdl / carbRatio)
    }

    fun reset() {
        peakRate = 0.0; smoothedRate = 0.0; decayed = false; reaccelRuns = 0; rateSignal = false
        peakBgMgdl = 0.0; descended = false; bgSignal = false
        maxRiseSinceDescent = 0.0; prevDelta = 0.0
        detected = false; description = ""
    }

    /** Call once per cycle while a meal/UAM mode is running. */
    fun onCycle(bgMgdl: Double, deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double) {
        if (detected) return

        // -- Rate signal --------------------------------------------------------
        val rate = absorptionRateGPer5Min(deltaMgdl, activityPerMin, isfMgdl, carbRatio)
        smoothedRate = if (smoothedRate == 0.0) rate else smoothedRate + RATE_ALPHA * (rate - smoothedRate)
        if (smoothedRate > peakRate) peakRate = smoothedRate
        if (peakRate >= MIN_PEAK_RATE) {
            if (!decayed && smoothedRate <= peakRate * DECAY_FRACTION) decayed = true
            else if (decayed && smoothedRate >= peakRate * REACCEL_FRACTION) {
                reaccelRuns++
                if (reaccelRuns >= REACCEL_READINGS) rateSignal = true
            } else if (decayed && smoothedRate < peakRate * REACCEL_FRACTION) reaccelRuns = 0
        }

        // -- BG signal ----------------------------------------------------------
        if (bgMgdl > peakBgMgdl && !descended) peakBgMgdl = bgMgdl
        if (!descended && peakBgMgdl - bgMgdl >= DESCENT_MGDL) descended = true
        else if (descended) {
            // Peak climb rate over the whole ascent, so a slow start can't dilute a fast finish.
            val twoReadingRise = (deltaMgdl + prevDelta) / RISE_READINGS
            if (twoReadingRise > maxRiseSinceDescent) maxRiseSinceDescent = twoReadingRise
            if (bgMgdl >= peakBgMgdl + NEW_HIGH_MGDL && maxRiseSinceDescent >= FAST_RISE_MGDL_PER_5MIN)
                bgSignal = true
        }
        prevDelta = deltaMgdl

        if (rateSignal && bgSignal) {
            detected = true
            description = "second wave: absorption re-accelerated to ${"%.1f".format(smoothedRate)}g/5min after decaying, " +
                "and BG climbed at ${BgText.bg(maxRiseSinceDescent)}/5min to a new high at ${BgText.bg(bgMgdl)}"
            aapsLogger.debug(LTag.APS, "SecondWaveDetector: $description")
        }
    }
}
