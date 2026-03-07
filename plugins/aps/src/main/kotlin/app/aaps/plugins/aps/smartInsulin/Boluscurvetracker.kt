package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Watches post-bolus CGM response and feeds peak/DIA observations to [ProfileLearner].
 *
 * ## Detection strategy
 *
 * A bolus "event" is opened when IOB rises above [MIN_IOB_TO_TRACK] U.
 * Each loop cycle, we record (timestamp, BG, IOB) into a rolling window.
 * The event closes when IOB falls back below [MIN_IOB_TO_TRACK].
 *
 * From the window we derive:
 *   - observedPeakMins: time from bolus detection to maximum BG *drop rate*
 *     (the inflection point, where insulin effect was strongest)
 *   - observedDiaMins: time from bolus detection until IOB drops below
 *     [DIA_IOB_TAIL_THRESHOLD] — approximates end of insulin action
 *
 * ## Noise filtering
 * At least [MIN_WINDOW_SAMPLES] samples are required before attempting a fit.
 * Events with a total BG drop < [MIN_BG_DROP_MGDL] are discarded as
 * insufficient signal (e.g. correction bolus into rising meal — too much noise).
 *
 * ## Learning hand-off
 * Valid observations are passed to [ProfileLearner.observeBolusCurve].
 * The learner applies its own EWMA blending and hard-limit clamping.
 */
@Singleton
class BolusCurveTracker @Inject constructor(
    private val profileLearner: ProfileLearner,
    private val preferences:    Preferences,
    private val aapsLogger:     AAPSLogger
) {
    // ── Tracking state ───────────────────────────────────────────────────────

    /** A single CGM+IOB sample captured during bolus tracking */
    private data class Sample(
        val timestampMs: Long,
        val bgMgdl:      Double,
        val iob:         Double,
        val activity:    Double
    )

    private val window        = mutableListOf<Sample>()
    private var trackingActive = false
    private var bolusStartMs   = 0L
    private var peakIob        = 0.0

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Called once per loop cycle. Feeds the current state into the tracker.
     * When a complete bolus event is detected, derives peak/DIA estimates
     * and hands them off to [ProfileLearner].
     */
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode:      MealMode,
        iobArray:      Array<IobTotal>
    ) {
        val currentIob      = iobArray.firstOrNull()?.iob      ?: 0.0
        val currentActivity = iobArray.firstOrNull()?.activity ?: 0.0
        val now             = glucoseStatus.date

        when {
            // ── Open a new tracking window ───────────────────────────
            !trackingActive && currentIob >= MIN_IOB_TO_TRACK -> {
                trackingActive = true
                bolusStartMs   = now
                peakIob        = currentIob
                window.clear()
                window.add(Sample(now, glucoseStatus.glucose, currentIob, currentActivity))
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: opened window IOB=%.2f".format(currentIob))
            }

            // ── Accumulate samples ───────────────────────────────────
            trackingActive && currentIob >= MIN_IOB_TO_TRACK -> {
                if (currentIob > peakIob) peakIob = currentIob
                window.add(Sample(now, glucoseStatus.glucose, currentIob, currentActivity))

                // Safety: cap window to avoid unbounded memory growth
                if (window.size > MAX_WINDOW_SAMPLES) {
                    window.removeAt(0)
                    bolusStartMs = window.first().timestampMs
                }
            }

            // ── Close window and attempt fit ─────────────────────────
            trackingActive && currentIob < MIN_IOB_TO_TRACK -> {
                window.add(Sample(now, glucoseStatus.glucose, currentIob, currentActivity))
                trackingActive = false
                aapsLogger.debug(
                    LTag.APS,
                    "BolusCurveTracker: closed window n=${window.size} peakIOB=%.2f".format(peakIob)
                )
                attemptFitAndLearn(mealMode)
                window.clear()
                peakIob = 0.0
            }

            // ── No bolus active, nothing to do ───────────────────────
            else -> { /* idle */ }
        }
    }

    // ── Curve fitting ────────────────────────────────────────────────────────

    private fun attemptFitAndLearn(mealMode: MealMode) {
        if (window.size < MIN_WINDOW_SAMPLES) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: discarding — insufficient samples (${window.size})")
            return
        }

        val startBg   = window.first().bgMgdl
        val minBg     = window.minOf { it.bgMgdl }
        val totalDrop = startBg - minBg

        if (totalDrop < MIN_BG_DROP_MGDL) {
            aapsLogger.debug(
                LTag.APS,
                "BolusCurveTracker: discarding — BG drop %.1f < %.1f minimum".format(totalDrop, MIN_BG_DROP_MGDL)
            )
            return
        }

        // ── Peak activity time ───────────────────────────────────────
        // Find the sample with maximum insulin activity — this is the
        // inflection point of the BG drop curve, representing peak insulin effect.
        val peakSample       = window.maxByOrNull { it.activity } ?: return
        val peakOffsetMs     = peakSample.timestampMs - bolusStartMs
        val observedPeakMins = (peakOffsetMs / MS_PER_MIN).coerceAtLeast(1.0)

        // ── DIA estimate ─────────────────────────────────────────────
        // Find first sample where IOB has dropped to the tail threshold.
        // If the window ends before that, use the full window duration as a lower bound.
        val diaSample = window.firstOrNull { it.iob <= DIA_IOB_TAIL_THRESHOLD }
        val diaOffsetMs = if (diaSample != null) {
            diaSample.timestampMs - bolusStartMs
        } else {
            window.last().timestampMs - bolusStartMs
        }
        val observedDiaMins = (diaOffsetMs / MS_PER_MIN).coerceAtLeast(observedPeakMins + 30.0)

        aapsLogger.debug(
            LTag.APS,
            "BolusCurveTracker: fit mode=${mealMode.label} " +
                "peak=%.1fmin dia=%.1fmin drop=%.1fmgdl n=${window.size}".format(
                    observedPeakMins, observedDiaMins, totalDrop
                )
        )

        val learningRate = preferences.get(DoubleKey.ApsSmartInsulinLearningRate)

        profileLearner.observeBolusCurve(
            mode             = mealMode,
            observedPeakMins = observedPeakMins,
            observedDiaMins  = observedDiaMins,
            learningRate     = learningRate
        )
    }

    companion object {
        /** IOB threshold to start/stop tracking a bolus event (units) */
        private const val MIN_IOB_TO_TRACK          = 0.1

        /** IOB threshold below which we consider insulin "tail" complete */
        private const val DIA_IOB_TAIL_THRESHOLD    = 0.05

        /** Minimum BG drop (mg/dL) required to treat the window as a valid signal */
        private const val MIN_BG_DROP_MGDL          = 10.0

        /** Minimum number of samples before attempting a fit */
        private const val MIN_WINDOW_SAMPLES        = 6

        /** Maximum samples to retain (guards against very long events) */
        private const val MAX_WINDOW_SAMPLES        = 600  // ~10 hours at 1-min loops

        private const val MS_PER_MIN                = 60_000.0
    }
}