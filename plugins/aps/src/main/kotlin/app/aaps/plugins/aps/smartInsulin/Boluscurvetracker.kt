package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks post-bolus CGM curves to estimate observed peak and DIA per MealMode.
 *
 * Strategy:
 *   1. Detect when IOB starts declining from its peak
 *   2. Track BG nadir from that point
 *   3. Once BG recovers [RECOVERY_MGDL] above nadir, the bolus is "complete"
 *   4. Derive observedPeakMins (start→nadir) and observedDiaMins (start→recovery)
 *   5. Feed to ProfileLearner
 */
@Singleton
class BolusCurveTracker @Inject constructor(
    private val profileLearner: ProfileLearner,
    private val preferences:    Preferences,
    private val aapsLogger:     AAPSLogger
) {
    private var tracking        = false
    private var trackStartMs    = 0L
    private var trackMode       = MealMode.FASTING
    private var iobAtStart      = 0.0
    private var bgAtStart       = 0.0
    private var iobPeak         = 0.0
    private var iobDeclineSeen  = false
    private var bgNadir         = Double.MAX_VALUE
    private var nadirTimeMs     = 0L
    private var nadirConfirmed  = false

    companion object {
        private const val MIN_TRACK_IOB_U        = 1.0
        private const val RECOVERY_MGDL          = 18.0  // ~1 mmol recovery above nadir
        private const val MIN_BG_DROP_MGDL       = 18.0  // ~1 mmol minimum drop to count
        private const val MAX_TRACK_DURATION_MS  = 6 * 60 * 60 * 1000L
        private const val MIN_NADIR_DELAY_MS     = 30 * 60 * 1000L
        private const val IOB_DECLINE_FRACTION   = 0.05
    }

    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode:      MealMode,
        iobArray:      Array<IobTotal>
    ) {
        val currentIob = iobArray.firstOrNull()?.iob ?: return
        val currentBg  = glucoseStatus.glucose
        val nowMs      = System.currentTimeMillis()

        if (!tracking) {
            if (currentIob >= MIN_TRACK_IOB_U) {
                tracking       = true
                trackStartMs   = nowMs
                trackMode      = mealMode
                iobAtStart     = currentIob
                bgAtStart      = currentBg
                iobPeak        = currentIob
                iobDeclineSeen = false
                bgNadir        = currentBg
                nadirTimeMs    = nowMs
                nadirConfirmed = false
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: started tracking mode=${mealMode.label} iob=$currentIob bg=$currentBg")
            }
            return
        }

        val elapsedMs = nowMs - trackStartMs

        if (elapsedMs > MAX_TRACK_DURATION_MS) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: abandoned (timeout)")
            reset(); return
        }

        // New bolus detected — restart
        if (currentIob > iobAtStart * 1.2) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: abandoned (new bolus iob=$currentIob)")
            reset(); return
        }

        // Track IOB peak and confirm decline
        if (currentIob > iobPeak) {
            iobPeak = currentIob
        } else if (!iobDeclineSeen && currentIob < iobPeak * (1.0 - IOB_DECLINE_FRACTION)) {
            iobDeclineSeen = true
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: IOB peak confirmed at $iobPeak")
        }

        if (!iobDeclineSeen) return

        // Update BG nadir
        if (currentBg < bgNadir) {
            bgNadir   = currentBg
            nadirTimeMs = nowMs
        }

        // Check recovery
        val nadirElapsedMs = nadirTimeMs - trackStartMs
        if (!nadirConfirmed &&
            (nowMs - nadirTimeMs) > MIN_NADIR_DELAY_MS &&
            currentBg > bgNadir + RECOVERY_MGDL &&
            bgNadir < bgAtStart - MIN_BG_DROP_MGDL
        ) {
            nadirConfirmed = true
            val observedPeakMins = nadirElapsedMs.toDouble() / 60_000.0
            val observedDiaMins  = elapsedMs.toDouble() / 60_000.0
            val learningRate     = preferences.get(DoubleKey.ApsSmartInsulinLearningRate)

            aapsLogger.debug(
                LTag.APS,
                "BolusCurveTracker: complete mode=${trackMode.label} " +
                    "peak=%.1fmin dia=%.1fmin bgDrop=%.1f".format(
                        observedPeakMins, observedDiaMins, bgAtStart - bgNadir
                    )
            )

            profileLearner.observeBolusCurve(
                mode             = trackMode,
                observedPeakMins = observedPeakMins,
                observedDiaMins  = observedDiaMins,
                learningRate     = learningRate
            )
            reset()
        }
    }

    private fun reset() {
        tracking = false; trackStartMs = 0L; iobAtStart = 0.0; bgAtStart = 0.0
        iobPeak = 0.0; iobDeclineSeen = false; bgNadir = Double.MAX_VALUE
        nadirTimeMs = 0L; nadirConfirmed = false
    }
}