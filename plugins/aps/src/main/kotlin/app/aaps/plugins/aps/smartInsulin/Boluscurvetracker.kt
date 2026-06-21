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
    // Tracks basaliob (the basal-only component of IOB) alongside the BG curve. Used to
    // detect when a flatline/nadir-recovery is actually the loop suspending basal rather
    // than the tracked bolus finishing its pharmacological action — see the SUSPENSION
    // GUARD checks in onLoopCycle. Same (timestamp, value) shape as curveHistory.
    private val basalIobHistory  = mutableListOf<Pair<Long, Double>>()
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

        // -- Basal suspension guard ------------------------------------------
        // If basaliob has been at/below this threshold for most of the flatline/recovery
        // window, the loop — not the bolus finishing — is the more likely explanation for
        // the BG stabilising. basaliob this negative means the loop has been zero-temping
        // or actively withholding basal; a curve flattening under that condition tells you
        // about the LOOP's response, not the bolus's pharmacological DIA.
        private const val SUSPENSION_BASAL_IOB_THRESHOLD = -0.10
        // Fraction of samples in the relevant window that must show suspension before the
        // observation is downgraded. Not "any single suspended reading" — a brief zero-temp
        // blip is normal loop behaviour and shouldn't discard an otherwise clean observation.
        private const val SUSPENSION_FRACTION_GATE        = 0.5
        // When suspension is detected, the observation is still passed to the learner (the
        // flatline/recovery genuinely happened, after all) but at a further-reduced rate —
        // same philosophy as FLATLINE_LEARNING_RATE_MULT, stacked on top of it.
        private const val SUSPENSION_LEARNING_RATE_MULT   = 0.4

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
        private const val K_BASAL_IOB_HISTORY = "basalIobHistory"
        private const val MAX_CURVE_SAMPLES  = 200
    }

    // -- Suspension guard helper -----------------------------------------------
    /**
     * Fraction of basalIobHistory samples at/below [SUSPENSION_BASAL_IOB_THRESHOLD] within
     * the given time window. Used to decide whether a flatline/recovery is more likely the
     * loop suspending basal than the tracked bolus finishing — see callers in [onLoopCycle].
     * Returns 0.0 (assume no suspension) if there's no history in the window, rather than
     * blocking the observation on missing data.
     */
    private fun suspendedFraction(windowStartMs: Long): Double {
        val windowSamples = basalIobHistory.filter { it.first >= windowStartMs }
        if (windowSamples.isEmpty()) return 0.0
        val suspendedCount = windowSamples.count { it.second <= SUSPENSION_BASAL_IOB_THRESHOLD }
        return suspendedCount.toDouble() / windowSamples.size
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
        // basaliob: the basal-only component of total IOB. Used to detect when the loop is
        // actively suspending/withholding basal — see SUSPENSION_BASAL_IOB_THRESHOLD usage
        // below. Same field/pattern CircadianLearner already uses for the equivalent check.
        val currentBasalIob = iobArray.firstOrNull()?.basaliob ?: 0.0

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
                basalIobHistory.clear()
                basalIobHistory.add(nowMs to currentBasalIob)
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
            basalIobHistory.clear()
            basalIobHistory.add(nowMs to currentBasalIob)
            saveState()
            return
        } else if (isSignificantSpike) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: spike ignored (ride-along) - spike=%.2f ratio=%.1f%%".format(Locale.US, iobSpikeWhileTracking, pivotRatio * 100))
        }

        curveHistory.add(nowMs to smoothedBg)
        if (curveHistory.size > MAX_CURVE_SAMPLES) curveHistory.removeAt(0)
        basalIobHistory.add(nowMs to currentBasalIob)
        if (basalIobHistory.size > MAX_CURVE_SAMPLES) basalIobHistory.removeAt(0)
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

                    // SUSPENSION GUARD: a flatline can mean "the bolus finished" OR "the loop
                    // suspended basal and that arrested the BG fall" — the raw curve can't tell
                    // these apart. Check what basaliob was doing over the SAME window we just
                    // confirmed the flatline in. If the loop was suspended for most of it, this
                    // observation is measuring the system's response, not the bolus's true DIA —
                    // still worth learning from (it's real, actionable behaviour), but at a much
                    // lower rate so it can't dominate the profile on its own.
                    val suspFrac = suspendedFraction(windowStart)
                    val suspended = suspFrac >= SUSPENSION_FRACTION_GATE
                    val flatlineLearningRate = PROFILE_LEARNING_RATE * FLATLINE_LEARNING_RATE_MULT *
                        (if (suspended) SUSPENSION_LEARNING_RATE_MULT else 1.0)
                    if (suspended) {
                        aapsLogger.debug(LTag.APS,
                                         "BolusCurveTracker: flatline coincides with basal suspension (${"%.0f".format(Locale.US, suspFrac * 100)}% of window) — " +
                                             "observedDia=${"%.0f".format(Locale.US, observedDiaMins)}m may reflect loop response, not true DIA. Learning at reduced rate.")
                    }

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

            // SUSPENSION GUARD (recovery path): same concern as the flatline branch above —
            // a "recovery" off the nadir can be the bolus finishing, OR it can be the loop
            // resuming basal (or firing a correction) after having suspended through the fall.
            // Check basaliob over the window from the nadir to now, since that's the period
            // whose end we're using as observedDiaMins.
            val suspFrac = suspendedFraction(nadirTimeMs)
            val suspended = suspFrac >= SUSPENSION_FRACTION_GATE
            val recoveryLearningRate = PROFILE_LEARNING_RATE * (if (suspended) SUSPENSION_LEARNING_RATE_MULT else 1.0)
            if (suspended) {
                aapsLogger.debug(LTag.APS,
                                 "BolusCurveTracker: nadir recovery coincides with basal suspension (${"%.0f".format(Locale.US, suspFrac * 100)}% of post-nadir window) — " +
                                     "observedDia=${"%.0f".format(Locale.US, observedDiaMins)}m may reflect loop response, not true DIA. Learning at reduced rate.")
            }

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
                learningRate     = recoveryLearningRate
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

                val basalIobJson = org.json.JSONArray()
                for ((ts, bIob) in basalIobHistory) {
                    basalIobJson.put(org.json.JSONArray().apply {
                        put(ts)
                        put(bIob)
                    })
                }
                put(K_BASAL_IOB_HISTORY, basalIobJson)
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

            // basalIobHistory is best-effort: absent on upgrade from a pre-suspension-guard
            // version, or if restore fails partway. An empty history just means
            // suspendedFraction() returns 0.0 (assume not suspended) until fresh samples
            // accumulate post-restore — safe default, not a crash risk.
            basalIobHistory.clear()
            json.optJSONArray(K_BASAL_IOB_HISTORY)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i) ?: continue
                    if (entry.length() < 2) continue
                    val ts   = entry.optLong(0, -1L)
                    val bIob = entry.optDouble(1, Double.NaN)
                    if (ts > 0L && !bIob.isNaN()) basalIobHistory.add(ts to bIob)
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
        basalIobHistory.clear()
        try {
            sp.edit { putString(StringKey.ApsSmartInsulinTrackerState.key, "") }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to clear persisted state: ${e.message}")
        }
    }
}