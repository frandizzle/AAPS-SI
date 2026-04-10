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

    init {
        restoreState()
    }

    companion object {
        private const val MIN_TRACK_IOB_U        = 1.0
        private const val MIN_BOLUS_SPIKE_U      = 0.3   // IOB must rise ≥0.3U in one cycle to count as a new bolus
        private const val RECOVERY_MGDL          = 18.0   // ~1 mmol recovery above nadir
        private const val MIN_BG_DROP_MGDL       = 18.0   // ~1 mmol minimum drop to count
        private const val MAX_TRACK_DURATION_MS  = 6 * 60 * 60 * 1000L
        private const val MIN_NADIR_DELAY_MS     = 30 * 60 * 1000L
        private const val IOB_DECLINE_FRACTION   = 0.05

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
        return "tracker=$phase mode=$modeStr " +
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
                bgAtStart       = currentBg
                iobPeak         = currentIob
                iobDeclineSeen  = false
                bgNadir         = Double.MAX_VALUE   // will update to first BG reading below bgAtStart
                nadirTimeMs     = nowMs
                nadirConfirmed  = false
                saveState()
                aapsLogger.debug(LTag.APS,
                                 "BolusCurveTracker: started tracking mode=${mealMode.label} iob=$currentIob spike=${"%.2f".format(Locale.US, iobSpike)} bg=$currentBg")
            } else {
                prevIob = currentIob
            }
            return
        }
        // New bolus detected while tracking — abandon current curve and restart
        // Compute spike FIRST using the previous cycle's IOB, THEN update prevIob
        val iobSpikeWhileTracking = currentIob - (prevIob.takeIf { it > 0.0 } ?: currentIob)
        prevIob = currentIob  // update AFTER spike check so next cycle sees this cycle's value

        val elapsedMs = nowMs - trackStartMs

        // Abandon if tracking too long
        if (elapsedMs > MAX_TRACK_DURATION_MS) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: abandoned (timeout)")
            reset(); return
        }

        if (iobSpikeWhileTracking >= MIN_BOLUS_SPIKE_U || currentIob > iobPeak * 1.3) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: abandoned (new bolus spike=%.2f iob=$currentIob)".format(Locale.US, iobSpikeWhileTracking))
            reset(); return
        }

        // Track IOB peak and confirm decline
        if (currentIob > iobPeak) {
            iobPeak = currentIob
            saveState()
        } else if (!iobDeclineSeen && currentIob < iobPeak * (1.0 - IOB_DECLINE_FRACTION)) {
            iobDeclineSeen = true
            saveState()
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: IOB peak confirmed at $iobPeak")
        }

        if (!iobDeclineSeen) return

        // Update BG nadir
        if (currentBg < bgNadir) {
            bgNadir     = currentBg
            nadirTimeMs = nowMs
            saveState()
        }

        // Check recovery
        if (!nadirConfirmed &&
            (nowMs - nadirTimeMs) > MIN_NADIR_DELAY_MS &&
            currentBg > bgNadir + RECOVERY_MGDL &&
            bgNadir < bgAtStart - MIN_BG_DROP_MGDL
        ) {
            nadirConfirmed = true
            val observedPeakMins = (nadirTimeMs - trackStartMs).toDouble() / 60_000.0
            val observedDiaMins  = elapsedMs.toDouble() / 60_000.0
            val learningRate     = 0.15  // profile peak/DIA learning rate — independent of ISF/basal alpha

            aapsLogger.debug(
                LTag.APS,
                "BolusCurveTracker: complete mode=${trackMode.label} " +
                    "peak=%.1fmin dia=%.1fmin bgDrop=%.1f".format(
                        Locale.US, observedPeakMins, observedDiaMins, bgAtStart - bgNadir
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

            aapsLogger.debug(LTag.APS,
                             "BolusCurveTracker: restored state mode=${trackMode.label} " +
                                 "iobAtStart=$iobAtStart bgAtStart=$bgAtStart declineSeen=$iobDeclineSeen")
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
        // prevIob intentionally NOT reset — we still need continuity to detect next bolus spike
        try { preferences.put(StringKey.ApsSmartInsulinTrackerState, "") } catch (_: Exception) {}
    }
}