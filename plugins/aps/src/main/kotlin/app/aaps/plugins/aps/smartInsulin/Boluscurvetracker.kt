package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import app.aaps.core.interfaces.sharedPreferences.SP
import java.util.Locale
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Tracks post-bolus CGM curves to estimate observed peak and DIA per MealMode.
 */
@Singleton
class BolusCurveTracker @Inject constructor(
    private val profileLearner: ProfileLearner,
    private val sp:             SP,
    private val aapsLogger:     AAPSLogger
) {
    // ── In-memory state ───────────────────────────────────────────────────────
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
    private var prevIob         = 0.0
    private val bgBuffer        = mutableListOf<Double>()
    private val curveHistory    = mutableListOf<Pair<Long, Double>>()
    private var seededPrevIob   = false

    init {
        restoreState()
    }

    companion object {
        private const val MIN_TRACK_IOB_U        = 0.6
        private const val MIN_BOLUS_SPIKE_U      = 0.3
        private const val RECOVERY_MGDL          = 12.0
        private const val MIN_BG_DROP_MGDL_FASTING = 10.0
        private const val MIN_BG_DROP_MGDL_MEAL    = 5.0
        private const val MAX_TRACK_DURATION_MS  = 6 * 60 * 60 * 1000L
        private const val MIN_NADIR_DELAY_MS     = 30 * 60 * 1000L
        private const val IOB_DECLINE_FRACTION   = 0.05
        private const val PROFILE_LEARNING_RATE  = 0.15

        private const val FLATLINE_MIN_ELAPSED_MS     = 120 * 60_000L
        private const val FLATLINE_WINDOW_MS          = 45 * 60_000L
        private const val FLATLINE_MIN_WINDOW_SAMPLES = 6
        private const val FLATLINE_MAX_VARIANCE_MGDL  = 9.0
        private const val FLATLINE_BG_MIN_MGDL        = 72.0
        private const val FLATLINE_BG_MAX_MGDL        = 162.0
        private const val FLATLINE_LEARNING_RATE_MULT = 0.7

        private const val K_TRACKING         = "tracking"
        private const val K_START_MS         = "trackStartMs"
        private const val K_MODE             = "trackMode"
        private const val K_IOB_AT_START     = "iobAtStart"
        private const val K_BG_AT_START      = "bgAtStart"
        private const val K_IOB_PEAK         = "iobPeak"
        private const val K_IOB_DECLINE_SEEN = "iobDeclineSeen"
        private const val K_BG_NADIR         = "bgNadir"
        private const val K_NADIR_TIME_MS    = "nadirTimeMs"
        private const val K_NADIR_CONFIRMED  = "nadirConfirmed"
        private const val K_PREV_IOB         = "prevIob"
        private const val K_BG_BUFFER        = "bgBuffer"
        private const val K_CURVE_HISTORY    = "curveHistory"
        private const val MAX_CURVE_SAMPLES  = 200
    }

    fun statusSummary(currentMode: MealMode? = null): String {
        if (!tracking) return "tracker=idle"
        val elapsedMin = (System.currentTimeMillis() - trackStartMs) / 60_000.0
        val nadirStr   = if (bgNadir == Double.MAX_VALUE) "?" else "%.1f".format(Locale.US, bgNadir)
        val smoothed   = if (bgBuffer.size >= 3) bgBuffer.sorted()[1] else bgBuffer.lastOrNull() ?: 0.0
        val phase = when {
            !iobDeclineSeen -> "waiting_peak"
            !nadirConfirmed -> "tracking_nadir"
            else            -> "confirming"
        }
        val modeStr = if (currentMode == null || currentMode == trackMode)
            trackMode.label
        else
            "${trackMode.label}(historical)"
        return "tracker=$phase mode=$modeStr smoothedBG=%.1f ".format(Locale.US, smoothed) +
            "peak=%.1fm nadir=$nadirStr elapsed=%.0fm".format(Locale.US, iobPeak, elapsedMin)
    }

    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode:      MealMode,
        iobArray:      Array<IobTotal>,
        nowMs:         Long = System.currentTimeMillis()
    ) {
        val currentIob = iobArray.firstOrNull()?.iob ?: return
        val currentBg  = glucoseStatus.glucose

        var stateDirty = false

        bgBuffer.add(currentBg)
        if (bgBuffer.size > 3) bgBuffer.removeAt(0)
        stateDirty = true

        val smoothedBg = if (bgBuffer.size >= 3) {
            bgBuffer.sorted()[1]
        } else {
            currentBg
        }

        if (!seededPrevIob) {
            prevIob = currentIob
            seededPrevIob = true
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: seeded prevIob=$currentIob on first cycle")
            return
        }

        if (!tracking) {
            val iobSpike = currentIob - prevIob
            prevIob = currentIob
            if (iobSpike >= MIN_BOLUS_SPIKE_U && currentIob >= MIN_TRACK_IOB_U) {
                tracking        = true
                trackStartMs    = nowMs
                trackMode       = mealMode
                iobAtStart      = currentIob
                bgAtStart       = smoothedBg
                iobPeak         = currentIob
                iobDeclineSeen  = false
                bgNadir         = Double.MAX_VALUE
                nadirTimeMs     = nowMs
                nadirConfirmed  = false
                curveHistory.clear()
                curveHistory.add(nowMs to smoothedBg)
                stateDirty      = true
                aapsLogger.debug(LTag.APS,
                                 "BolusCurveTracker: started tracking mode=${mealMode.label} iob=$currentIob spike=${"%.2f".format(Locale.US, iobSpike)} bg=$currentBg (smoothed=$smoothedBg)")
            }
            if (stateDirty) saveState()
            return
        }
        val iobSpikeWhileTracking = currentIob - (prevIob.takeIf { it > 0.0 } ?: currentIob)
        prevIob = currentIob

        val elapsedMs = nowMs - trackStartMs

        if (mealMode != trackMode) {
            aapsLogger.debug(LTag.APS,
                             "BolusCurveTracker: abandoned (mode changed ${trackMode.label}→${mealMode.label})")
            reset(); return
        }

        if (elapsedMs > MAX_TRACK_DURATION_MS) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: abandoned (timeout)")
            reset(); return
        }

        val existingIob = currentIob - iobSpikeWhileTracking
        val pivotRatio  = if (existingIob > 0) iobSpikeWhileTracking / existingIob else 1.0
        val isSignificantSpike = iobSpikeWhileTracking >= 0.4
        val shouldPivot = isSignificantSpike && (pivotRatio > 0.30 || iobSpikeWhileTracking >= 2.0)

        if (shouldPivot) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: pivot to new bolus (spike=%.2f ratio=%.1f%% iob=$currentIob)".format(Locale.US, iobSpikeWhileTracking, pivotRatio * 100))

            trackStartMs    = nowMs
            trackMode       = mealMode
            iobAtStart      = currentIob
            bgAtStart       = smoothedBg
            iobPeak         = currentIob
            iobDeclineSeen  = false
            bgNadir         = Double.MAX_VALUE
            nadirTimeMs     = nowMs
            nadirConfirmed  = false
            bgBuffer.clear()
            bgBuffer.add(currentBg)
            curveHistory.clear()
            curveHistory.add(nowMs to smoothedBg)
            saveState()
            return
        } else if (isSignificantSpike) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: spike ignored (ride-along) - spike=%.2f ratio=%.1f%%".format(Locale.US, iobSpikeWhileTracking, pivotRatio * 100))
        }

        curveHistory.add(nowMs to smoothedBg)
        if (curveHistory.size > MAX_CURVE_SAMPLES) curveHistory.removeAt(0)
        stateDirty = true

        if (currentIob > iobPeak) {
            iobPeak = currentIob
            stateDirty = true
        } else if (!iobDeclineSeen && currentIob < iobPeak * (1.0 - IOB_DECLINE_FRACTION)) {
            iobDeclineSeen = true
            stateDirty = true
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: IOB peak confirmed at $iobPeak")
        }

        if (!iobDeclineSeen) {
            if (stateDirty) saveState()
            return
        }

        if (smoothedBg < bgNadir) {
            bgNadir     = smoothedBg
            nadirTimeMs = nowMs
            stateDirty  = true
        }

        val dynamicRecovery = max(RECOVERY_MGDL, smoothedBg * 0.06)
        val minDrop = if (trackMode == MealMode.FASTING) MIN_BG_DROP_MGDL_FASTING else MIN_BG_DROP_MGDL_MEAL
        val dropTarget = bgAtStart - minDrop

        if (!nadirConfirmed &&
            trackMode == MealMode.FASTING &&
            elapsedMs >= FLATLINE_MIN_ELAPSED_MS &&
            bgNadir < dropTarget
        ) {
            val windowStart = nowMs - FLATLINE_WINDOW_MS
            val windowSamples = curveHistory.filter { it.first >= windowStart }

            if (windowSamples.size >= FLATLINE_MIN_WINDOW_SAMPLES) {
                val windowBgs = windowSamples.map { it.second }
                val bgRange   = (windowBgs.maxOrNull() ?: 0.0) - (windowBgs.minOrNull() ?: 0.0)
                val avgBg     = windowBgs.average()

                if (bgRange <= FLATLINE_MAX_VARIANCE_MGDL &&
                    avgBg >= FLATLINE_BG_MIN_MGDL &&
                    avgBg <= FLATLINE_BG_MAX_MGDL
                ) {
                    val observedDiaMins = (nadirTimeMs - trackStartMs).toDouble() / 60_000.0
                    val flatlineLearningRate = PROFILE_LEARNING_RATE * FLATLINE_LEARNING_RATE_MULT

                    val peakResult = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(
                        curve        = curveHistory.toList(),
                        trackStartMs = trackStartMs
                    )
                    val peakForLearner: Double? = when (peakResult) {
                        is BolusCurveAnalysis.PeakResult.Ok         -> peakResult.minutes
                        else                                         -> null
                    }

                    profileLearner.observeBolusCurve(
                        mode             = trackMode,
                        observedPeakMins = peakForLearner,
                        observedDiaMins  = observedDiaMins,
                        learningRate     = flatlineLearningRate
                    )
                    reset()
                    return
                }
            }
        }

        if (!nadirConfirmed &&
            (nowMs - nadirTimeMs) > MIN_NADIR_DELAY_MS &&
            smoothedBg > bgNadir + dynamicRecovery &&
            bgNadir < dropTarget
        ) {
            nadirConfirmed = true
            val observedDiaMins = elapsedMs.toDouble() / 60_000.0

            val peakResult = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(
                curve        = curveHistory.toList(),
                trackStartMs = trackStartMs
            )

            val peakForLearner: Double? = when (peakResult) {
                is BolusCurveAnalysis.PeakResult.Ok           -> peakResult.minutes
                else   -> null
            }

            profileLearner.observeBolusCurve(
                mode             = trackMode,
                observedPeakMins = peakForLearner,
                observedDiaMins  = observedDiaMins,
                learningRate     = PROFILE_LEARNING_RATE
            )
            reset()
            return
        }

        if (stateDirty) saveState()
    }

    private fun saveState() {
        try {
            val json = JSONObject().apply {
                put(K_TRACKING,         tracking)
                put(K_START_MS,         trackStartMs)
                put(K_MODE,             trackMode.name)
                put(K_IOB_AT_START,     iobAtStart)
                put(K_BG_AT_START,      bgAtStart)
                put(K_IOB_PEAK,         iobPeak)
                put(K_IOB_DECLINE_SEEN, iobDeclineSeen)
                put(K_BG_NADIR,         if (bgNadir == Double.MAX_VALUE) -1.0 else bgNadir)
                put(K_NADIR_TIME_MS,    nadirTimeMs)
                put(K_NADIR_CONFIRMED,  nadirConfirmed)
                put(K_PREV_IOB,         prevIob)
                put(K_BG_BUFFER,        org.json.JSONArray(bgBuffer))
                val curveJson = org.json.JSONArray()
                for ((ts, bg) in curveHistory) {
                    curveJson.put(org.json.JSONArray().apply {
                        put(ts)
                        put(bg)
                    })
                }
                put(K_CURVE_HISTORY, curveJson)
            }
            sp.edit { putString(StringKey.ApsSmartInsulinTrackerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to save state: ${e.message}")
        }
    }

    private fun restoreState() {
        try {
            val raw = sp.getString(StringKey.ApsSmartInsulinTrackerState.key, StringKey.ApsSmartInsulinTrackerState.defaultValue)
            if (raw.isNullOrBlank()) return
            val json = JSONObject(raw)
            if (!json.optBoolean(K_TRACKING, false)) return

            val restoredStartMs = json.getLong(K_START_MS)
            if (System.currentTimeMillis() - restoredStartMs > MAX_TRACK_DURATION_MS) {
                sp.edit { putString(StringKey.ApsSmartInsulinTrackerState.key, "") }
                return
            }

            tracking        = true
            trackStartMs    = restoredStartMs
            trackMode       = MealMode.valueOf(json.getString(K_MODE))
            iobAtStart      = json.getDouble(K_IOB_AT_START)
            bgAtStart       = json.getDouble(K_BG_AT_START)
            iobPeak         = json.getDouble(K_IOB_PEAK)
            iobDeclineSeen  = json.getBoolean(K_IOB_DECLINE_SEEN)
            val nadirRaw    = json.getDouble(K_BG_NADIR)
            bgNadir         = if (nadirRaw < 0) Double.MAX_VALUE else nadirRaw
            nadirTimeMs     = json.getLong(K_NADIR_TIME_MS)
            nadirConfirmed  = json.getBoolean(K_NADIR_CONFIRMED)
            prevIob         = json.optDouble(K_PREV_IOB, 0.0)

            bgBuffer.clear()
            json.optJSONArray(K_BG_BUFFER)?.let { arr ->
                for (i in 0 until arr.length()) {
                    bgBuffer.add(arr.getDouble(i))
                }
            }

            curveHistory.clear()
            json.optJSONArray(K_CURVE_HISTORY)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i) ?: continue
                    if (entry.length() < 2) continue
                    val ts = entry.optLong(0, -1L)
                    val bg = entry.optDouble(1, Double.NaN)
                    if (ts > 0L && !bg.isNaN()) curveHistory.add(ts to bg)
                }
            }

            seededPrevIob   = true
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to restore state: ${e.message}")
            reset()
        }
    }

    private fun reset() {
        tracking        = false
        trackStartMs    = 0L
        iobAtStart      = 0.0
        bgAtStart       = 0.0
        iobPeak         = 0.0
        iobDeclineSeen  = false
        bgNadir         = Double.MAX_VALUE
        nadirTimeMs     = 0L
        nadirConfirmed  = false
        bgBuffer.clear()
        curveHistory.clear()
        try {
            sp.edit { putString(StringKey.ApsSmartInsulinTrackerState.key, "") }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to clear persisted state: ${e.message}")
        }
    }
}
