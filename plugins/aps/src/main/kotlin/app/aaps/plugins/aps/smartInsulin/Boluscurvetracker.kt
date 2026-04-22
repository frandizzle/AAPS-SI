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
 * mid-track do not lose the baseline BG/IOB reference.
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
        private const val MIN_BG_DROP_MGDL       = 10.0  // ~0.5 mmol minimum drop below start (for Fasting)
        private const val MEAL_NADIR_HEADROOM    = 10.0  // Nadir can be up to 10mg/dL above start for meal modes
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
        iobArray:      Array<IobTotal>
    ) {
        val currentIob = iobArray.firstOrNull()?.iob ?: return
        val currentBg  = glucoseStatus.glucose
        val nowMs      = System.currentTimeMillis()

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
            saveState()
            return
        } else if (isSignificantSpike) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: spike ignored (ride-along) - spike=%.2f ratio=%.1f%%".format(Locale.US, iobSpikeWhileTracking, pivotRatio * 100))
        }

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
        val dropTarget = if (trackMode == MealMode.FASTING)
            bgAtStart - MIN_BG_DROP_MGDL
        else
            bgAtStart + MEAL_NADIR_HEADROOM

        if (!nadirConfirmed &&
            (nowMs - nadirTimeMs) > MIN_NADIR_DELAY_MS &&
            smoothedBg > bgNadir + dynamicRecovery &&
            bgNadir < dropTarget
        ) {
            nadirConfirmed = true
            val observedPeakMins = (nadirTimeMs - trackStartMs).toDouble() / 60_000.0
            val observedDiaMins  = elapsedMs.toDouble() / 60_000.0

            aapsLogger.debug(
                LTag.APS,
                "BolusCurveTracker: complete mode=${trackMode.label} " +
                    "peak=%.1fmin dia=%.1fmin bgDrop=%.1f recoveryThresh=%.1f".format(
                        Locale.US, observedPeakMins, observedDiaMins, bgAtStart - bgNadir, dynamicRecovery
                    )
            )

            profileLearner.observeBolusCurve(
                mode             = trackMode,
                observedPeakMins = observedPeakMins,
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
        // prevIob intentionally NOT reset — we still need continuity to detect next bolus spike
        try {
            preferences.put(StringKey.ApsSmartInsulinTrackerState, "")
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: failed to clear persisted state: ${e.message}")
        }
    }
}