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

    // Tail tracking — when meal mode ends before BG recovers, the tracker enters
    // "tail mode": it keeps watching for recovery without requiring mealMode to match.
    // mealModeEndedMs records when meal mode expired so we can enforce MAX_TAIL_DURATION_MS.
    private var inTailMode         = false
    private var mealModeEndedMs    = 0L

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
        /** Max time after meal mode ends to keep watching for BG recovery in tail mode.
         *  3 hours covers even the slowest protein/fat tail. If BG hasn't recovered
         *  by then, the curve is abandoned — something else (correction, food, etc) is
         *  confounding the signal. */
        private const val MAX_TAIL_DURATION_MS   = 3 * 60 * 60 * 1000L
        /** Stable-flat scoring: minimum time BG must stay near nadir before scoring
         *  a "perfect correction" that never rebounded. 45 min ensures we're not
         *  scoring mid-correction flats caused by temporary basal action. */
        private const val STABLE_CONFIRM_MS      = 45 * 60 * 1000L
        /** Stable-flat scoring: BG must stay within this many mg/dL above nadir.
         *  6 mg/dL ≈ 0.33 mmol — tight enough to exclude active descent, wide enough
         *  to tolerate normal CGM jitter. */
        private const val STABLE_BAND_MGDL       = 6.0
        /** Stable-flat scoring: nadir must be above this floor to qualify.
         *  72 mg/dL = 4.0 mmol — prevents learning from near-low corrections where
         *  the basal is just holding you at a dangerous level, not a clean end-of-action. */
        private const val STABLE_MIN_BG_MGDL     = 72.0

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
        private const val K_IN_TAIL_MODE     = "inTailMode"
        private const val K_MEAL_MODE_ENDED  = "mealModeEndedMs"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Rich snapshot of current tracker state for UI display. */
    data class TrackerSnapshot(
        val isTracking:          Boolean,
        val trackMode:           String,
        val elapsedMins:         Int,
        val phase:               String,          // "kinetics", "absorption", "tail", "idle"
        val inTailMode:          Boolean,
        val tailElapsedMins:     Int,
        val bgPeakMmol:          Double,
        val bgNadirMmol:         Double?,
        val iobDeclineSeen:      Boolean,
        val lastEventDesc:       String,
        // Learned carb curve parameters for the tracked mode — used for phase display
        val learnedPeakMins:     Double,          // when plateau starts (safePeak)
        val learnedDurationMins: Double,          // when tail ends (absorptionMinutes)
        val transientWindowMins: Double = 60.0    // hardcoded spike window
    )

    fun snapshot(isMmol: Boolean = true): TrackerSnapshot {
        val now = System.currentTimeMillis()
        if (!tracking) return TrackerSnapshot(
            isTracking = false, trackMode = "", elapsedMins = 0,
            phase = "idle", inTailMode = false, tailElapsedMins = 0,
            bgPeakMmol = 0.0, bgNadirMmol = null,
            iobDeclineSeen = false, lastEventDesc = "Not tracking",
            learnedPeakMins = 0.0, learnedDurationMins = 0.0
        )
        val elapsedMins = ((now - trackStartMs) / 60_000).toInt()
        val phase = when {
            trackMode == MealMode.FASTING -> "kinetics"
            inTailMode                    -> "tail"
            else                          -> "absorption"
        }
        val tailElapsed = if (inTailMode && mealModeEndedMs > 0L)
            ((now - mealModeEndedMs) / 60_000).toInt() else 0
        val bgPeakMmol = if (isMmol) bgPeak / 18.0 else bgPeak
        val bgNadirMmol = if (bgNadir == Double.MAX_VALUE) null
        else if (isMmol) bgNadir / 18.0 else bgNadir

        // Compute learned carb phase boundaries for the tracked mode
        val carbAbs = profileLearner.getCarbAbsorption(trackMode)
        val carbDuration = carbAbs.absorptionMinutes
        val carbPeakRaw  = carbAbs.peakMinutes
        val safePeak = if (carbAbs.sampleCount >= 3)  // same threshold as DetermineBasalSmartInsulin
            carbPeakRaw.coerceIn(carbDuration * 0.25, carbDuration * 0.80)
        else
            carbDuration * 0.65  // default fraction
        val lastEvent = when {
            trackMode == MealMode.FASTING && bgNadir < Double.MAX_VALUE -> {
                val timeSinceNadir = System.currentTimeMillis() - nadirTimeMs
                val stableProgress = (timeSinceNadir / (STABLE_CONFIRM_MS.toDouble()) * 100).toInt().coerceAtMost(100)
                val stableStr = if (timeSinceNadir < STABLE_CONFIRM_MS)
                    " — stable ${stableProgress}%" else " — stable window met ✓"
                "Nadir ${String.format(Locale.US, "%.1f", bgNadirMmol)} at +${((nadirTimeMs - trackStartMs)/60_000).toInt()}min$stableStr"
            }
            trackMode == MealMode.FASTING ->
                "Watching for BG nadir (IOB decline ${if (iobDeclineSeen) "seen ✓" else "not yet"})"
            inTailMode ->
                "Meal mode ended ${tailElapsed}min ago — watching for recovery"
            else ->
                "BG peak ${String.format(Locale.US, "%.1f", bgPeakMmol)} at +${((bgPeakTimeMs - trackStartMs)/60_000).toInt()}min"
        }
        return TrackerSnapshot(
            isTracking = true, trackMode = trackMode.label,
            elapsedMins = elapsedMins, phase = phase,
            inTailMode = inTailMode, tailElapsedMins = tailElapsed,
            bgPeakMmol = bgPeakMmol, bgNadirMmol = bgNadirMmol,
            iobDeclineSeen = iobDeclineSeen, lastEventDesc = lastEvent,
            learnedPeakMins = safePeak,
            learnedDurationMins = carbDuration
        )
    }

    fun statusSummary(currentMode: MealMode? = null): String {
        if (!tracking) return "tracker=idle"
        val elapsedMin = (System.currentTimeMillis() - trackStartMs) / 60_000.0
        val modeStr = if (currentMode == null || currentMode == trackMode)
            trackMode.label else "${trackMode.label}(hist)"
        val tailStr = if (inTailMode) " [tail ${(System.currentTimeMillis() - mealModeEndedMs)/60_000}m]" else ""

        return if (trackMode == MealMode.FASTING) {
            val nadirStr = if (bgNadir == Double.MAX_VALUE) "?" else "%.1f".format(Locale.US, bgNadir)
            "tracker=kinetics mode=$modeStr nadir=$nadirStr elapsed=%.0fm".format(Locale.US, elapsedMin)
        } else {
            "tracker=absorption mode=$modeStr$tailStr bgPeak=%.1f elapsed=%.0fm".format(Locale.US, bgPeak, elapsedMin)
        }
    }

    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        mealMode:      MealMode,
        iobArray:      Array<IobTotal>,
        pb2OrPb3FiredThisCycle: Boolean = false
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
            if (iobSpike >= MIN_BOLUS_SPIKE_U && currentIob >= MIN_TRACK_IOB_U) {
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
                stateDirty      = true
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: started tracking $trackMode")
            }
            if (stateDirty) saveState()
            return
        }

        val iobSpikeWhileTracking = currentIob - prevIob
        prevIob = currentIob

        // PB2/PB3 whitelist: scheduled pre-boluses are part of the same meal intervention we're
        // already tracking, not an unrelated correction. Instead of abandoning the track, treat
        // the spike as a staged re-dose: refresh iobPeak baseline to the new IOB level and reset
        // the "decline seen" flag so we'll watch for the new combined IOB to fall before scoring
        // the curve. BG tracking (nadir/peak) is untouched — the physiology being measured is
        // the same meal absorption or fasting correction.
        if (pb2OrPb3FiredThisCycle && iobSpikeWhileTracking > 0.0) {
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: PB2/PB3 fired (spike=${"%.2f".format(Locale.US, iobSpikeWhileTracking)}U) — refreshing iobPeak baseline, keeping BG track")
            iobPeak        = currentIob
            iobDeclineSeen = false
            stateDirty     = true
            // Fall through to the normal BG-update logic below — do NOT run the abandon check.
        } else if (mealMode != trackMode) {
            // Meal mode ended while a meal track is in progress. Rather than abandoning,
            // enter "tail mode" — keep watching for BG recovery without requiring mode match.
            // This lets the tracker observe the full absorption including the protein/fat tail
            // that extends beyond the meal mode window.
            // Exception: if trackMode is FASTING, a mode change is a genuine disruption → abandon.
            if (trackMode == MealMode.FASTING) {
                reset(); return
            }
            if (!inTailMode) {
                inTailMode      = true
                mealModeEndedMs = nowMs
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: meal mode ended, entering tail observation for ${trackMode.label}")
                stateDirty = true
            }
            // Abandon if tail observation window exceeded
            if (nowMs - mealModeEndedMs > MAX_TAIL_DURATION_MS) {
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: tail observation window exceeded for ${trackMode.label}, abandoning")
                reset(); return
            }
            // Abandon if unexpected IOB spike during tail (manual correction etc)
            if (iobSpikeWhileTracking >= ABANDON_SPIKE_U || currentIob > iobPeak * 1.4) {
                aapsLogger.debug(LTag.APS, "BolusCurveTracker: IOB spike during tail, abandoning ${trackMode.label}")
                reset(); return
            }
        } else if ((nowMs - trackStartMs) > MAX_TRACK_DURATION_MS ||
            iobSpikeWhileTracking >= ABANDON_SPIKE_U || currentIob > iobPeak * 1.4) {
            reset(); return
        }

        // Track IOB peak (drug concentration proxy) — only when not in a PB2/PB3 refresh cycle
        // (the refresh block above already handled peak/decline state).
        if (!pb2OrPb3FiredThisCycle) {
            if (currentIob > iobPeak) {
                iobPeak = currentIob
                stateDirty = true
            } else if (!iobDeclineSeen && currentIob < iobPeak * (1.0 - IOB_DECLINE_FRACTION)) {
                iobDeclineSeen = true
                stateDirty = true
            }
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

        if (curveConfirmed) return

        val timeSinceNadir = nowMs - nadirTimeMs

        // ── Scoring condition 1: Classic recovery ────────────────────────────
        // BG has risen ≥ RECOVERY_MGDL above nadir → insulin effect clearly over.
        val recoveryMet = currentBg > bgNadir + RECOVERY_MGDL

        // ── Scoring condition 2: Stable flat ────────────────────────────────
        // BG has been hovering just above (or at) the nadir for 45+ min, which
        // means basal is now holding the line and the correction bolus is done.
        // This captures "perfect" corrections where BG drops to target and stays
        // there — previously these were ignored because there was no rebound rise.
        //
        // Guards:
        //   currentBg >= bgNadir: still falling → not stable yet (Gemini's tweak)
        //   (currentBg - bgNadir) <= STABLE_BAND_MGDL: genuinely flat, not rising
        //   bgNadir > STABLE_MIN_BG_MGDL: don't learn from corrections that bottomed
        //     near a low — basal holding at 3.8 mmol isn't a clean signal
        val isStable = timeSinceNadir > STABLE_CONFIRM_MS &&
            currentBg >= bgNadir &&
            (currentBg - bgNadir) <= STABLE_BAND_MGDL &&
            bgNadir > STABLE_MIN_BG_MGDL

        if (timeSinceNadir > MIN_CONFIRM_DELAY_MS &&
            (recoveryMet || isStable) &&
            bgNadir < bgAtStart - MIN_BG_DROP_MGDL) {

            curveConfirmed = true
            val observedPeakActionMins = (nadirTimeMs - trackStartMs).toDouble() / 60_000.0
            val observedDiaMins        = (nowMs - trackStartMs).toDouble() / 60_000.0

            profileLearner.observeInsulinKinetics(
                observedPeakMins = observedPeakActionMins.coerceIn(
                    LearnedInsulinKinetics.PEAK_MIN, LearnedInsulinKinetics.PEAK_MAX),
                observedDiaMins  = observedDiaMins.coerceIn(
                    LearnedInsulinKinetics.DIA_MIN, LearnedInsulinKinetics.DIA_MAX),
                learningRate     = PROFILE_LEARNING_RATE
            )
            aapsLogger.debug(LTag.APS,
                             "BolusCurveTracker: Insulin Kinetics complete. " +
                                 "Peak=${observedPeakActionMins.toInt()}m DIA=${observedDiaMins.toInt()}m " +
                                 "(scored via ${if (isStable) "stable-flat" else "recovery"})")
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
                put(K_IN_TAIL_MODE,     inTailMode)
                put(K_MEAL_MODE_ENDED,  mealModeEndedMs)
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
            inTailMode      = json.optBoolean(K_IN_TAIL_MODE, false)
            mealModeEndedMs = json.optLong(K_MEAL_MODE_ENDED, 0L)
            seededPrevIob   = true
        } catch (_: Exception) { reset() }
    }

    private fun reset() {
        tracking        = false
        trackStartMs    = 0L
        curveConfirmed  = false
        inTailMode      = false
        mealModeEndedMs = 0L
        try { preferences.put(StringKey.ApsSmartInsulinTrackerState, "") } catch (_: Exception) {}
    }
}