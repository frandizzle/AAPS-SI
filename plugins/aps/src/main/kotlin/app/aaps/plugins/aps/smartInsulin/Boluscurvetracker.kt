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
 * Tracks post-bolus CGM curves to estimate:
 * 1. Global Insulin Kinetics (during FASTING)
 * 2. Per-mode Carb Absorption (during MEAL modes)
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

    // Fasting-specific (Insulin Kinetics)
    private var bgNadir         = Double.MAX_VALUE
    private var nadirTimeMs     = 0L

    // Meal-specific (Carb Absorption)
    private var bgPeak          = 0.0
    private var bgPeakTimeMs    = 0L

    private var curveConfirmed  = false
    private var prevIob         = 0.0
    private var seededPrevIob   = false

    init {
        restoreState()
    }

    companion object {
        private const val MIN_TRACK_IOB_U        = 0.6
        private const val MIN_BOLUS_SPIKE_U      = 0.3
        private const val ABANDON_SPIKE_U        = 0.8
        private const val RECOVERY_MGDL          = 12.0
        private const val MIN_BG_DROP_MGDL       = 10.0
        private const val MAX_TRACK_DURATION_MS  = 6 * 60 * 60 * 1000L
        private const val MIN_CONFIRM_DELAY_MS   = 30 * 60 * 1000L
        private const val IOB_DECLINE_FRACTION   = 0.05
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
        private const val K_BG_PEAK          = "bgPeak"
        private const val K_PEAK_TIME_MS     = "peakTimeMs"
        private const val K_CONFIRMED        = "curveConfirmed"
        private const val K_PREV_IOB         = "prevIob"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun statusSummary(currentMode: MealMode? = null): String {
        if (!tracking) return "tracker=idle"
        val elapsedMin = (System.currentTimeMillis() - trackStartMs) / 60_000.0
        val modeStr = if (currentMode == null || currentMode == trackMode)
            trackMode.label else "${trackMode.label}(hist)"

        return if (trackMode == MealMode.FASTING) {
            val nadirStr = if (bgNadir == Double.MAX_VALUE) "?" else "%.1f".format(Locale.US, bgNadir)
            "tracker=kinetics mode=$modeStr nadir=$nadirStr elapsed=%.0fm".format(Locale.US, elapsedMin)
        } else {
            "tracker=absorption mode=$modeStr bgPeak=%.1f elapsed=%.0fm".format(Locale.US, bgPeak, elapsedMin)
        }
    }

    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode:      MealMode,
        iobArray:      Array<IobTotal>
    ) {
        val currentIob = iobArray.firstOrNull()?.iob ?: return
        val currentBg  = glucoseStatus.glucose
        val nowMs      = System.currentTimeMillis()
        var stateDirty = false

        if (!seededPrevIob) {
            prevIob = currentIob
            seededPrevIob = true
            return
        }

        if (!tracking) {
            val iobSpike = currentIob - prevIob
            prevIob = currentIob

            val shouldStart = when {
                // Meal mode active + meaningful IOB: start immediately, no spike needed.
                // Catches PB1 delivered before tracker seeded, app restarts mid-meal, etc.
                mealMode != MealMode.FASTING && currentIob >= MIN_TRACK_IOB_U -> true
                // Fasting: require explicit IOB spike (correction bolus)
                iobSpike >= MIN_BOLUS_SPIKE_U && currentIob >= MIN_TRACK_IOB_U -> true
                else -> false
            }
            if (shouldStart) {
                tracking        = true
                trackStartMs    = nowMs
                trackMode       = mealMode
                iobAtStart      = currentIob
                bgAtStart       = currentBg
                iobPeak         = currentIob
                iobDeclineSeen  = false
                bgNadir         = currentBg
                nadirTimeMs     = nowMs
                bgPeak          = currentBg
                bgPeakTimeMs    = nowMs
                curveConfirmed  = false
                // Seed prevIob to current so next cycle doesn't compute a phantom spike
                // from stale prevIob and immediately trigger the ABANDON_SPIKE_U reset.
                prevIob         = currentIob
                stateDirty      = true
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: started tracking ${mealMode.label} IOB=${"%.2f".format(Locale.US, currentIob)}")
            }
            if (stateDirty) saveState()
            return
        }

        val iobSpikeWhileTracking = currentIob - (prevIob.takeIf { it > 0.0 } ?: currentIob)
        prevIob = currentIob

        if (mealMode != trackMode || (nowMs - trackStartMs) > MAX_TRACK_DURATION_MS ||
            iobSpikeWhileTracking >= ABANDON_SPIKE_U || currentIob > iobPeak * 1.4) {
            reset(); return
        }

        // Track IOB peak (drug concentration proxy)
        if (currentIob > iobPeak) {
            iobPeak = currentIob
            stateDirty = true
        } else if (!iobDeclineSeen && currentIob < iobPeak * (1.0 - IOB_DECLINE_FRACTION)) {
            iobDeclineSeen = true
            stateDirty = true
        }

        if (trackMode == MealMode.FASTING) {
            updateFastingLogic(currentBg, nowMs)
        } else {
            updateMealLogic(currentBg, nowMs)
        }

        if (stateDirty) saveState()
    }

    private fun updateFastingLogic(currentBg: Double, nowMs: Long) {
        if (!iobDeclineSeen) return

        if (currentBg < bgNadir) {
            bgNadir     = currentBg
            nadirTimeMs = nowMs
            saveState()
        }

        // Logic: Nadir is the Peak Action. Insulin Peak is ~45m prior.
        if (!curveConfirmed && (nowMs - nadirTimeMs) > MIN_CONFIRM_DELAY_MS &&
            currentBg > bgNadir + RECOVERY_MGDL && bgNadir < bgAtStart - MIN_BG_DROP_MGDL) {

            curveConfirmed = true
            val observedPeakActionMins = (nadirTimeMs - trackStartMs).toDouble() / 60_000.0
            val learnedPeak = (observedPeakActionMins - 45.0).coerceAtLeast(35.0)
            val learnedDia  = learnedPeak * 4.0

            profileLearner.observeInsulinKinetics(learnedPeak, learnedDia, PROFILE_LEARNING_RATE)
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: Insulin Kinetics complete. Peak=${learnedPeak.toInt()}m DIA=${learnedDia.toInt()}m")
            reset()
        }
    }

    private fun updateMealLogic(currentBg: Double, nowMs: Long) {
        if (currentBg > bgPeak) {
            bgPeak     = currentBg
            bgPeakTimeMs = nowMs
            saveState()
        }

        // Recovery for meals: BG returns to within 15 mg/dL of start, or starts falling after a peak
        if (!curveConfirmed && (nowMs - bgPeakTimeMs) > MIN_CONFIRM_DELAY_MS &&
            currentBg < bgPeak - RECOVERY_MGDL && currentBg < bgAtStart + 20.0) {

            curveConfirmed = true
            val observedCarbPeakMins = (bgPeakTimeMs - trackStartMs).toDouble() / 60_000.0
            val observedDurationMins = (nowMs - trackStartMs).toDouble() / 60_000.0

            profileLearner.observeCarbAbsorption(trackMode, observedCarbPeakMins, observedDurationMins, PROFILE_LEARNING_RATE)
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: Carb Absorption complete. Peak=${observedCarbPeakMins.toInt()}m Duration=${observedDurationMins.toInt()}m")
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
                put(K_BG_NADIR,         bgNadir)
                put(K_NADIR_TIME_MS,    nadirTimeMs)
                put(K_BG_PEAK,          bgPeak)
                put(K_PEAK_TIME_MS,     bgPeakTimeMs)
                put(K_CONFIRMED,        curveConfirmed)
                put(K_PREV_IOB,         prevIob)
            }
            preferences.put(StringKey.ApsSmartInsulinTrackerState, json.toString())
        } catch (_: Exception) {}
    }

    private fun restoreState() {
        try {
            val raw = preferences.get(StringKey.ApsSmartInsulinTrackerState)
            if (raw.isBlank()) return
            val json = JSONObject(raw)
            if (!json.optBoolean(K_TRACKING, false)) return

            tracking        = true
            trackStartMs    = json.getLong(K_START_MS)
            trackMode       = MealMode.valueOf(json.getString(K_MODE))
            iobAtStart      = json.getDouble(K_IOB_AT_START)
            bgAtStart       = json.getDouble(K_BG_AT_START)
            iobPeak         = json.getDouble(K_IOB_PEAK)
            iobDeclineSeen  = json.getBoolean(K_IOB_DECLINE_SEEN)
            bgNadir         = json.optDouble(K_BG_NADIR, bgAtStart)
            nadirTimeMs     = json.optLong(K_NADIR_TIME_MS, trackStartMs)
            bgPeak          = json.optDouble(K_BG_PEAK, bgAtStart)
            bgPeakTimeMs    = json.optLong(K_PEAK_TIME_MS, trackStartMs)
            curveConfirmed  = json.optBoolean(K_CONFIRMED, false)
            prevIob         = json.optDouble(K_PREV_IOB, 0.0)
            seededPrevIob   = true
        } catch (_: Exception) { reset() }
    }

    private fun reset() {
        tracking        = false
        trackStartMs    = 0L
        curveConfirmed  = false
        try { preferences.put(StringKey.ApsSmartInsulinTrackerState, "") } catch (_: Exception) {}
    }
}