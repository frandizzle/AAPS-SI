package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.Locale
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Tracks post-bolus CGM curves to estimate observed peak and DIA per MealMode.
 *
 * State is persisted to SharedPreferences on every cycle so AAPS restarts
 * mid-track do not lose the baseline BG/IOB reference or accumulated curve.
 *
 * Strategy:
 *   1. Detect a fresh bolus from an IOB spike, capture baseline BG/IOB.
 *   2. Accumulate (timestamp, smoothedBg) into [curveHistory] every loop cycle.
 *   3. Wait for IOB to start declining from its peak.
 *   4. Track BG nadir from that point.
 *   5. Once BG recovers [RECOVERY_MGDL] above nadir AND the nadir was a real drop
 *      below baseline (per [MIN_BG_DROP_MGDL_FASTING] / [MIN_BG_DROP_MGDL_MEAL]),
 *      the bolus is "complete" (rebound exit).
 *   5b. Alternatively, if BG has been stable within [FLATLINE_MAX_VARIANCE_MGDL] for
 *      [FLATLINE_WINDOW_MS] after a real nadir drop (FASTING only, ≥2h elapsed,
 *      BG in safe range), the bolus is "complete" via flatline exit. DIA is measured
 *      as bolus-start → nadir time (not current time — plateau adds no DIA signal).
 *      Learning rate is attenuated by [FLATLINE_LEARNING_RATE_MULT] since the DIA
 *      endpoint is blurrier than a rebound exit.
 *   6. Run [BolusCurveAnalysis.calculateInterpolatedPeakMinutes] over the recorded
 *      curve to extract the time of maximum negative BG velocity (peak insulin
 *      action) — this is physiologically distinct from BG nadir time and yields
 *      a much better signal for AAPS's `iCfg.peak` parameter.
 *   7. Feed (peak-or-null, DIA) to ProfileLearner. Peak may be null when the curve
 *      shape doesn't support clean velocity-peak extraction (typical for meal modes
 *      where carbs win); ProfileLearner holds peakMinutes constant in that case
 *      while still learning DIA.
 */
@Singleton
class BolusCurveTracker @Inject constructor(
    private val profileLearner: ProfileLearner,
    private val preferences:    Preferences,
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
    private var prevIob         = 0.0   // tracks IOB from last cycle to detect new bolus spikes
    // ── BG Smoothing buffer ──────────────────────────────────────────────────
    private val bgBuffer        = mutableListOf<Double>()
    // Full curve history from track start through nadir confirmation.
    // Pairs of (timestampMs, smoothedBg) accumulated every cycle while tracking.
    // Used by BolusCurveAnalysis at close-out to compute interpolated peak via
    // velocity-curve parabolic vertex fitting. Persisted across restarts so a mid-track
    // restart doesn't lose the data needed for shadow peak computation.
    private val curveHistory    = mutableListOf<Pair<Long, Double>>()
    // Not persisted — set true after first onLoopCycle call or successful restoreState.
    // Prevents a false-positive bolus spike on the first cycle after app start when the user
    // has pre-existing IOB (prevIob=0.0 vs currentIob=2.0 would look like a fresh 2U bolus).
    private var seededPrevIob   = false

    init {
        restoreState()
    }

    companion object {
        private const val MIN_TRACK_IOB_U        = 0.6
        private const val MIN_BOLUS_SPIKE_U      = 0.3   // IOB must rise ≥0.3U in one cycle to count as a new bolus
        private const val RECOVERY_MGDL          = 12.0  // ~0.7 mmol recovery above nadir
        // Minimum drop below bgAtStart for the nadir to count as a real insulin trough.
        // Fasting requires a meaningful drop (clean signal). Meal/UAM modes allow a smaller
        // drop because carb absorption can keep BG near or above start even with healthy
        // insulin action — but we still require *some* drop, otherwise we end up feeding
        // ProfileLearner samples where insulin never visibly pulled BG down at all (carbs
        // won the curve), which corrupts learned peak/DIA.
        private const val MIN_BG_DROP_MGDL_FASTING = 10.0  // ~0.5 mmol — clean signal, demand real drop
        private const val MIN_BG_DROP_MGDL_MEAL    = 5.0   // ~0.3 mmol — token drop to confirm insulin acted
        private const val MAX_TRACK_DURATION_MS  = 6 * 60 * 60 * 1000L
        // Minimum time between nadir reading and confirmation. Prevents a single noisy
        // reading upward from prematurely "recovering" the curve — the BG has to prove
        // the recovery is real by sustaining it for at least this long after nadir.
        private const val MIN_NADIR_DELAY_MS     = 30 * 60 * 1000L
        private const val IOB_DECLINE_FRACTION   = 0.05
        // Learning rate passed to ProfileLearner.observeBolusCurve. Kept separate from
        // ISF/basal learning alphas because peak/DIA learning operates on a different signal
        // (bolus curve shape) with different noise characteristics.
        private const val PROFILE_LEARNING_RATE  = 0.15

        // ── Flatline exit constants ───────────────────────────────────────────
        // A flatline exit fires when BG has been stable near its nadir for a sustained
        // period — indicating insulin finished acting without a visible rebound. This
        // captures clean fasting corrections (the highest-quality learning signal)
        // that would otherwise be abandoned at MAX_TRACK_DURATION_MS because BG
        // simply landed at target and stayed there.
        //
        // DIA is measured as time from bolus start to nadir (when BG stopped falling),
        // NOT elapsed-to-now. Using nadirTimeMs avoids inflating DIA by the entire
        // flatline plateau duration, which has no insulin-action information.
        //
        // Only fires for FASTING — meal/UAM curves have carb-driven BG dynamics that
        // make "flat near nadir" ambiguous and potentially food-driven rather than
        // insulin-complete.
        private const val FLATLINE_MIN_ELAPSED_MS     = 120 * 60_000L   // must be ≥2h into track
        private const val FLATLINE_WINDOW_MS          = 45 * 60_000L    // variance measured over last 45 min
        private const val FLATLINE_MIN_WINDOW_SAMPLES = 6               // ≥6 samples in window (~30 min minimum)
        private const val FLATLINE_MAX_VARIANCE_MGDL  = 9.0             // ~0.5 mmol — tighter than this = flat
        private const val FLATLINE_BG_MIN_MGDL        = 72.0            // 4.0 mmol — must be above this (not hypo-flat)
        private const val FLATLINE_BG_MAX_MGDL        = 162.0           // 9.0 mmol — must be below this (not failed-bolus flat)
        // Lower learning rate multiplier for flatline vs rebound — the DIA endpoint
        // is blurrier (nadir time, not actual return-to-baseline time).
        private const val FLATLINE_LEARNING_RATE_MULT = 0.7

        // JSON keys
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
        private const val K_PREV_IOB         = "prevIob"   // persisted to prevent false-positive spike on app restart
        private const val K_BG_BUFFER        = "bgBuffer"
        private const val K_CURVE_HISTORY    = "curveHistory"
        // Bound on persisted curve history. 6h tracking ÷ 5min cycle = ~72 samples typical.
        // Cap at 200 to defend against runaway accumulation (broken cycle timing, etc.) —
        // 200 × ~30 bytes ≈ 6KB JSON, well within SharedPreferences sanity.
        private const val MAX_CURVE_SAMPLES  = 200
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Human-readable one-liner for logcat — shows tracking state each loop cycle.
     * Example: "tracking=true mode=Fasting iobAtStart=3.2 iobPeak=3.8 declineSeen=true nadir=6.1 nadirConfirmed=false elapsed=42min"
     */
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
        // Only show tracked mode if it matches current mode — otherwise label as historical
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
        // Time injection — defaults to wall clock. Override in tests to simulate
        // time-based state transitions (nadir delay, flatline window, track timeout).
        nowMs:         Long = System.currentTimeMillis()
    ) {
        val currentIob = iobArray.firstOrNull()?.iob ?: return
        val currentBg  = glucoseStatus.glucose

        // Dirty flag — replaces the 4 scattered saveState() calls that used to run per
        // cycle. We accumulate changes and persist once at the end of the cycle (if needed).
        var stateDirty = false

        // ── BG Smoothing ──────────────────────────────────────────────────────
        bgBuffer.add(currentBg)
        if (bgBuffer.size > 3) bgBuffer.removeAt(0)
        stateDirty = true

        // Use median of last 3 readings to avoid noise-driven nadir/recovery
        val smoothedBg = if (bgBuffer.size >= 3) {
            bgBuffer.sorted()[1]
        } else {
            currentBg
        }

        // First cycle after app start — seed prevIob from currentIob so subsequent
        // spike detection compares against a real baseline, not 0.0. Only seeds when
        // not already restored from persisted state (restoreState sets the flag).
        if (!seededPrevIob) {
            prevIob = currentIob
            seededPrevIob = true
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: seeded prevIob=$currentIob on first cycle")
            return
        }

        if (!tracking) {
            // Only start on a meaningful IOB spike — new bolus delivered
            // Require IOB to have risen by at least MIN_BOLUS_SPIKE_U since last cycle
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
                bgNadir         = Double.MAX_VALUE   // will update to first BG reading below bgAtStart
                nadirTimeMs     = nowMs
                nadirConfirmed  = false
                curveHistory.clear()
                curveHistory.add(nowMs to smoothedBg)
                stateDirty      = true
                aapsLogger.debug(LTag.APS,
                                 "BolusCurveTracker: started tracking mode=${mealMode.label} iob=$currentIob spike=${"%.2f".format(Locale.US, iobSpike)} bg=$currentBg (smoothed=$smoothedBg)")
            }
            // prevIob already updated above — no else branch needed
            if (stateDirty) saveState()
            return
        }
        // New bolus detected while tracking — abandon current curve and restart
        // Compute spike FIRST using the previous cycle's IOB, THEN update prevIob
        val iobSpikeWhileTracking = currentIob - (prevIob.takeIf { it > 0.0 } ?: currentIob)
        prevIob = currentIob  // update AFTER spike check so next cycle sees this cycle's value

        val elapsedMs = nowMs - trackStartMs

        // Abandon if meal mode changed mid-tracking — the observed curve would be attributed
        // to the wrong profile (e.g. FASTING curve feeding into DINNER's learner after user
        // activated dinner mode post-bolus). Corrupts per-mode peak/DIA learning.
        if (mealMode != trackMode) {
            aapsLogger.debug(LTag.APS,
                             "BolusCurveTracker: abandoned (mode changed ${trackMode.label}→${mealMode.label})")
            reset(); return
        }

        // Abandon if tracking too long
        if (elapsedMs > MAX_TRACK_DURATION_MS) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: abandoned (timeout)")
            reset(); return
        }

        // ── Abandon and Pivot Logic ──────────────────────────────────────────
        // Refined for High-Frequency Dosing & Large UAM SMBs.
        // We only Pivot if the new bolus is large enough to fundamentally change
        // the curve shape.

        // Use a relative ratio: only pivot if the new spike is > 30% of existing IOB.
        // This allows large SMBs (e.g. 1.0U into 5.0U active) to "ride along" without
        // resetting the learner, ensuring we actually finish a sample.
        val existingIob = currentIob - iobSpikeWhileTracking
        val pivotRatio  = if (existingIob > 0) iobSpikeWhileTracking / existingIob else 1.0
        val isSignificantSpike = iobSpikeWhileTracking >= 0.4
        val shouldPivot = isSignificantSpike && (pivotRatio > 0.30 || iobSpikeWhileTracking >= 2.0)

        if (shouldPivot) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: pivot to new bolus (spike=%.2f ratio=%.1f%% iob=$currentIob)".format(Locale.US, iobSpikeWhileTracking, pivotRatio * 100))

            // PIVOT: Restart tracking immediately using the new spike as the baseline.
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

        // Append to curve history. By this point we've cleared the abandon/pivot checks and
        // are committed to continuing this track. The cap defends against runaway accumulation
        // from broken cycle timing — at 5min cycles a 6h track produces ~72 samples; 200 is
        // ample slack. Once capped, oldest samples drop off (FIFO) so we keep the most recent
        // window for parabolic peak fitting around the nadir.
        curveHistory.add(nowMs to smoothedBg)
        if (curveHistory.size > MAX_CURVE_SAMPLES) curveHistory.removeAt(0)
        stateDirty = true

        // Track IOB peak and confirm decline
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

        // Update BG nadir
        if (smoothedBg < bgNadir) {
            bgNadir     = smoothedBg
            nadirTimeMs = nowMs
            stateDirty  = true
        }

        // Check recovery
        val dynamicRecovery = max(RECOVERY_MGDL, smoothedBg * 0.06)
        // Required drop below start before nadir is acceptable. Both modes now require a real
        // drop — meal modes just demand a smaller one. Previously meal modes used `bgAtStart +
        // headroom` which was a near-trivial ceiling and let through samples where BG never
        // actually dropped (carbs winning the curve), feeding garbage into ProfileLearner.
        val minDrop = if (trackMode == MealMode.FASTING) MIN_BG_DROP_MGDL_FASTING else MIN_BG_DROP_MGDL_MEAL
        val dropTarget = bgAtStart - minDrop

        // ── Flatline exit — FASTING only ─────────────────────────────────────
        // Fires when BG has been stable near the nadir for ≥45 min, indicating insulin
        // finished acting without a visible rebound. This captures clean fasting corrections
        // that would otherwise be abandoned at MAX_TRACK_DURATION_MS because BG landed at
        // target and stayed there — the best possible outcome, and historically the one we
        // threw away most often.
        //
        // Guards:
        //   1. FASTING only — meal/UAM curves have carb dynamics that make "flat" ambiguous
        //   2. ≥120 min elapsed — gives insulin time to fully act before declaring done
        //   3. BG must have actually dropped below baseline (real nadir, not pre-peak plateau)
        //   4. BG in safe range — not flatlining at 60 (hypo-flat) or 250 (failed bolus)
        //   5. Low variance over last 45 min — genuinely stable, not just a quiet moment
        //   6. ≥6 samples in variance window — enough data to confirm stability
        if (!nadirConfirmed &&
            trackMode == MealMode.FASTING &&
            elapsedMs >= FLATLINE_MIN_ELAPSED_MS &&
            bgNadir < dropTarget                           // real drop must have occurred
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
                    // DIA = time from bolus start to nadir (when BG stopped falling).
                    // NOT elapsed-to-now — the flatline plateau adds no DIA information.
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
                    val peakDiagStr = when (peakResult) {
                        is BolusCurveAnalysis.PeakResult.Ok          -> "%.1fmin".format(Locale.US, peakResult.minutes)
                        is BolusCurveAnalysis.PeakResult.NoNegSlope  -> "no_neg_slope"
                        is BolusCurveAnalysis.PeakResult.TooFewPoints -> "too_few(${peakResult.count})"
                        is BolusCurveAnalysis.PeakResult.NonUniform  ->
                            "non_uniform_skipped(fallback=%.1fmin)".format(Locale.US, peakResult.fallbackMinutes)
                    }

                    aapsLogger.debug(
                        LTag.APS,
                        "BolusCurveTracker: FLATLINE_EXIT mode=${trackMode.label} " +
                            "peak=$peakDiagStr dia=%.1fmin(nadir) " +
                            "avgBg=%.1f range=%.1f samples=${windowSamples.size} " +
                            "elapsed=%.0fmin α=%.3f"
                                .format(Locale.US, observedDiaMins, avgBg, bgRange,
                                        elapsedMs / 60_000.0, flatlineLearningRate)
                    )

                    profileLearner.observeBolusCurve(
                        mode             = trackMode,
                        observedPeakMins = peakForLearner,
                        observedDiaMins  = observedDiaMins,
                        learningRate     = flatlineLearningRate
                    )
                    reset()
                    return
                } else {
                    aapsLogger.debug(
                        LTag.APS,
                        "BolusCurveTracker: flatline check failed — " +
                            "range=%.1f(max=%.0f) avgBg=%.1f(%.0f–%.0f) samples=${windowSamples.size}"
                                .format(Locale.US, bgRange, FLATLINE_MAX_VARIANCE_MGDL,
                                        avgBg, FLATLINE_BG_MIN_MGDL, FLATLINE_BG_MAX_MGDL)
                    )
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

            // Run the parabolic-vertex peak estimator over the recorded curve. We use the
            // refined peak time rather than nadir time because nadir lags peak action by
            // 20–40 min — feeding nadir into AAPS's `iCfg.peak` parameter systematically
            // biases the modeled peak late.
            val peakResult = BolusCurveAnalysis.calculateInterpolatedPeakMinutes(
                curve        = curveHistory.toList(),  // defensive copy — analysis runs sync but list is mutable
                trackStartMs = trackStartMs
            )

            // Map sealed result to the nullable peak that ProfileLearner expects.
            // - Ok          : feed the refined peak.
            // - NoNegSlope  : meal-mode curve dominated by carbs — no clean peak signal,
            //                 pass null so ProfileLearner holds peak constant but still
            //                 learns DIA.
            // - TooFewPoints: curve too short for parabolic fit (rare — 4+ samples means
            //                 the track ran for at least 15 min). Hold peak.
            // - NonUniform  : timestamp jitter exceeded the threshold for sub-cycle math.
            //                 We choose to pass null (rather than the segment-midpoint
            //                 fallback) — better to skip an uncertain peak than to feed
            //                 the learner a coarse approximation that pretends to be a
            //                 measurement. The fallback value is logged for diagnostics.
            val peakForLearner: Double? = when (peakResult) {
                is BolusCurveAnalysis.PeakResult.Ok           -> peakResult.minutes
                is BolusCurveAnalysis.PeakResult.NoNegSlope,
                is BolusCurveAnalysis.PeakResult.TooFewPoints,
                is BolusCurveAnalysis.PeakResult.NonUniform   -> null
            }
            val peakDiagStr = when (peakResult) {
                is BolusCurveAnalysis.PeakResult.Ok           -> "%.1fmin".format(Locale.US, peakResult.minutes)
                is BolusCurveAnalysis.PeakResult.NoNegSlope   -> "no_neg_slope"
                is BolusCurveAnalysis.PeakResult.TooFewPoints -> "too_few(${peakResult.count})"
                is BolusCurveAnalysis.PeakResult.NonUniform   ->
                    "non_uniform_skipped(fallback_was=%.1fmin)".format(Locale.US, peakResult.fallbackMinutes)
            }

            aapsLogger.debug(
                LTag.APS,
                "BolusCurveTracker: complete mode=${trackMode.label} " +
                    "peak=$peakDiagStr dia=%.1fmin bgDrop=%.1f recoveryThresh=%.1f curveSamples=${curveHistory.size}"
                        .format(Locale.US, observedDiaMins, bgAtStart - bgNadir, dynamicRecovery)
            )

            profileLearner.observeBolusCurve(
                mode             = trackMode,
                observedPeakMins = peakForLearner,  // null when no clean peak signal — ProfileLearner gates further by mode
                observedDiaMins  = observedDiaMins,
                learningRate     = PROFILE_LEARNING_RATE
            )
            reset()
            return  // reset() already cleared persisted state; no need to saveState below
        }

        // Flush accumulated changes once per cycle, at end — replaces the previous
        // pattern of 2-3 saveState() calls scattered through the decision tree.
        if (stateDirty) saveState()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

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
                // Curve history serialized as an array of two-element arrays: [[ts1, bg1], [ts2, bg2], ...].
                // Two-element arrays are more compact than objects and easier to deserialize defensively.
                val curveJson = org.json.JSONArray()
                for ((ts, bg) in curveHistory) {
                    curveJson.put(org.json.JSONArray().apply {
                        put(ts)
                        put(bg)
                    })
                }
                put(K_CURVE_HISTORY, curveJson)
            }
            preferences.put(StringKey.ApsSmartInsulinTrackerState, json.toString())
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to save state: ${e.message}")
        }
    }

    private fun restoreState() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinTrackerState)
            if (raw.isBlank()) return
            val json = JSONObject(raw)
            if (!json.optBoolean(K_TRACKING, false)) return

            // Validate restored state — abandon if start time is impossibly old
            val restoredStartMs = json.getLong(K_START_MS)
            if (System.currentTimeMillis() - restoredStartMs > MAX_TRACK_DURATION_MS) {
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: restored state expired, discarding")
                preferences.put(StringKey.ApsSmartInsulinTrackerState, "")
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
            // prevIob may be missing from older saved state — optDouble falls back to 0.0,
            // which is fine: the tracking branch's `takeIf { it > 0.0 } ?: currentIob` pattern
            // handles the 0 case defensively by returning zero spike.
            prevIob         = json.optDouble(K_PREV_IOB, 0.0)

            bgBuffer.clear()
            json.optJSONArray(K_BG_BUFFER)?.let { arr ->
                for (i in 0 until arr.length()) {
                    bgBuffer.add(arr.getDouble(i))
                }
            }

            // Curve history — defensive against missing key (older save formats predate this field)
            // and against corrupt entries. We skip rather than abort to keep partial recovery viable.
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

            // Successful restore with tracking active — prevIob is set, skip the seed-on-first-cycle path
            seededPrevIob   = true

            aapsLogger.debug(LTag.APS,
                             "BolusCurveTracker: restored state mode=${trackMode.label} " +
                                 "iobAtStart=$iobAtStart bgAtStart=$bgAtStart declineSeen=$iobDeclineSeen prevIob=$prevIob")
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
        // prevIob intentionally NOT reset — we still need continuity to detect next bolus spike
        try {
            preferences.put(StringKey.ApsSmartInsulinTrackerState, "")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to clear persisted state: ${e.message}")
        }
    }
}