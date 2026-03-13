package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ActivityMonitor — tracks physical activity from HR (wear) + steps (watch/phone).
 *
 * Data source: PersistenceLayer (same approach as AIMI).
 * Called once per loop cycle via [recompute] — no RxBus subscriptions needed.
 *
 * If no data in DB (watch not worn, no wear device), defaults to SEDENTARY — safe.
 *
 * Classification uses the higher signal from HR or steps.
 * HR uses absolute thresholds. If resting HR known, uses relative (HR - resting).
 *
 * HR smoothing uses an asymmetric EWA — but ONLY fires on genuinely new HR samples
 * (tracked via lastProcessedHrTimestamp). Stale samples are never re-smoothed.
 *
 *   - Fast attack   (HR_SMOOTH_ALPHA_RISE):    exercise onset detected within 1–2 loop cycles
 *   - Slow decay    (HR_SMOOTH_ALPHA_FALL):    brief HR dips mid-exercise don't drop classification
 *   - Dropout decay (HR_SMOOTH_DROPOUT_FACTOR): watch offline → smoothed HR bleeds to zero
 *     over ~4–5 cycles rather than instantly
 *
 * Freshness split:
 *   - HR_SEARCH_MS: how far back to query the DB (handles BT sync delays)
 *   - HR_FRESH_MS:  max age of a sample to be treated as live (not re-smoothed if stale)
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val aapsLogger:       AAPSLogger,
    private val persistenceLayer: PersistenceLayer
) {
    enum class ActivityLevel {
        SEDENTARY, LIGHT, MODERATE, HEAVY;
        val label get() = name.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        // HR thresholds — absolute bpm fallback (used when restingHrBpm = 0)
        const val HR_LIGHT_MIN        = 90.0
        const val HR_MODERATE_MIN     = 110.0
        const val HR_HEAVY_MIN        = 140.0

        // Relative thresholds (bpm above resting HR) — used when resting HR is known
        const val HR_REL_LIGHT_MIN    = 20.0
        const val HR_REL_MODERATE_MIN = 35.0
        const val HR_REL_HEAVY_MIN    = 60.0

        // Steps per 10-min window thresholds (steps10min bucket — doubled vs steps5min values)
        const val STEPS_LIGHT_MIN     = 400
        const val STEPS_MODERATE_MIN  = 1000
        const val STEPS_HEAVY_MIN     = 1800

        // HR fetch window — search back this far to handle BT batching/sync delays.
        // A sample found here is only smoothed if it also passes the HR_FRESH_MS check.
        const val HR_SEARCH_MS        = 20 * 60 * 1000L

        // HR freshness gate — a sample must be this recent to be treated as live data.
        // Older than this → treated as dropout → smoothed HR decays instead of updating.
        // 2 min gives one missed 60s HeartRateListener cycle of slack before decaying.
        const val HR_FRESH_MS         =  2 * 60 * 1000L

        // Steps: search wide (210 min) for DB records, classify fresh only (within 20 min).
        // steps10min bucket — more resilient to sync delays than steps5min.
        const val STEPS_SEARCH_MS     = 210 * 60 * 1000L
        const val STEPS_FRESH_MS      =  20 * 60 * 1000L

        // Asymmetric EWA alphas for HR smoothing
        // Rise: fast attack — exercise onset visible in 1–2 loop cycles
        // Fall: slow decay — brief dips (rest between sets, downhill) don't collapse level
        const val HR_SMOOTH_ALPHA_RISE     = 0.6
        const val HR_SMOOTH_ALPHA_FALL     = 0.15

        // Per-cycle bleed when no fresh HR arrives (watch dropout / charging).
        // 0.85 → ~4–5 loop cycles (~20–25 min) to decay to zero from a typical exercise HR.
        const val HR_SMOOTH_DROPOUT_FACTOR = 0.85
    }

    // ── Stale-sample guard ────────────────────────────────────────────────────
    // Tracks the timestamp of the last HR record we actually smoothed.
    // EWA only fires when latestHr.timestamp > lastProcessedHrTimestamp.
    // Without this, the same sample would be re-smoothed every loop cycle,
    // causing smoothedHrBpm to drift upward indefinitely with no new data.
    private var lastProcessedHrTimestamp: Long = 0L

    // ── HR state (persists across loop cycles) ────────────────────────────────
    // rawHrBpm      — the raw bpm of the last fresh sample (0 if dropout this cycle)
    // smoothedHrBpm — asymmetric EWA output — the value used for classification
    var rawHrBpm: Double = 0.0
        private set
    var smoothedHrBpm: Double = 0.0
        private set

    // Convenience alias — exposes smoothedHrBpm to existing callers without breaking API
    val lastHrBpm: Double get() = smoothedHrBpm

    // ── Steps state ───────────────────────────────────────────────────────────
    // Stores the steps10min bucket value — named to match what it actually contains
    var lastSteps10min: Int = 0
        private set

    // ── Computed level ────────────────────────────────────────────────────────
    var level: ActivityLevel = ActivityLevel.SEDENTARY
        private set

    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY

    /**
     * Query DB and recompute activity level.
     * Called once per loop cycle from SmartInsulinPlugin.invoke().
     *
     * @param nowMs         Current time (ms since epoch)
     * @param restingHrBpm  User's known resting HR (0 = unknown → use absolute thresholds)
     */
    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {

        // ── Steps ─────────────────────────────────────────────────────────────
        // StepsCount.timestamp = END of the 5-min sampling window (per StepsCount.kt).
        // Search wide to handle watch sync delays, then filter to fresh records only.
        val stepsSearchStart = nowMs - STEPS_SEARCH_MS
        val allSteps = try {
            persistenceLayer.getStepsCountFromTimeToTime(stepsSearchStart, nowMs)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: steps query failed: ${e.message}")
            emptyList()
        }

        val freshSteps = allSteps
            .filter { it.timestamp >= nowMs - STEPS_FRESH_MS }
            .maxByOrNull { it.timestamp }
        // steps10min bucket — wider window, more resilient to BT sync delays.
        // STEPS_*_MIN thresholds are calibrated to 10-min counts.
        lastSteps10min = freshSteps?.steps10min ?: 0

        // ── Heart Rate — fetch ────────────────────────────────────────────────
        // HR.timestamp = END of the 60-second sampling window (HeartRateListener.kt).
        // Search HR_SEARCH_MS back to survive BT batching delays, then gate on HR_FRESH_MS
        // to determine whether the latest record is live data or dropout.
        val hrSearchStart = nowMs - HR_SEARCH_MS
        val allHr = try {
            persistenceLayer.getHeartRatesFromTimeToTime(hrSearchStart, nowMs)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR query failed: ${e.message}")
            emptyList()
        }

        val latestHr  = allHr.maxByOrNull { it.timestamp }
        // Fresh = arrived within HR_FRESH_MS. Stale records found by the wide search
        // are ignored for smoothing — they only exist to confirm the watch was worn recently.
        val freshHr   = latestHr?.takeIf { it.timestamp >= nowMs - HR_FRESH_MS }
        val isNewSample = freshHr != null && freshHr.timestamp > lastProcessedHrTimestamp

        // ── Heart Rate — asymmetric EWA smoothing ─────────────────────────────
        //
        // KEY INVARIANT: EWA only updates on a genuinely new sample (isNewSample = true).
        // If the same sample were re-smoothed every loop, smoothedHrBpm would keep drifting
        // upward toward rawHrBpm even with no new HR data arriving.
        //
        // Cases:
        //   new fresh sample  → apply EWA (rise or fall alpha), record timestamp
        //   no new fresh sample → decay smoothed HR toward zero by dropout factor
        //   cold start        → seed smoothedHrBpm directly, skip EWA ramp-up from zero
        if (isNewSample) {
            rawHrBpm = freshHr!!.beatsPerMinute
            smoothedHrBpm = when {
                smoothedHrBpm <= 0.0 ->
                    // Cold start — seed directly to avoid slow ramp-up from zero
                    rawHrBpm
                rawHrBpm > smoothedHrBpm ->
                    // Rising — fast attack
                    HR_SMOOTH_ALPHA_RISE * rawHrBpm + (1.0 - HR_SMOOTH_ALPHA_RISE) * smoothedHrBpm
                else ->
                    // Falling — slow decay
                    HR_SMOOTH_ALPHA_FALL * rawHrBpm + (1.0 - HR_SMOOTH_ALPHA_FALL) * smoothedHrBpm
            }
            lastProcessedHrTimestamp = freshHr.timestamp
        } else {
            // No new fresh sample — stale, dropout, or watch offline
            rawHrBpm      = 0.0
            smoothedHrBpm = if (smoothedHrBpm > 0.0) smoothedHrBpm * HR_SMOOTH_DROPOUT_FACTOR else 0.0
        }

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: rawHr=${if (rawHrBpm > 0) "${rawHrBpm.toInt()}bpm" else "none"} " +
                             "smoothedHr=${smoothedHrBpm.toInt()}bpm newSample=$isNewSample " +
                             "steps10m=$lastSteps10min  hrRecords=${allHr.size}  stepsRecords=${allSteps.size}")

        // ── Classify ──────────────────────────────────────────────────────────
        // Always classifies from smoothedHrBpm — never raw — so a single noisy spike
        // can't instantly jump the level, and brief dips can't instantly drop it.
        val hrLevel = when {
            smoothedHrBpm <= 0.0 -> ActivityLevel.SEDENTARY
            restingHrBpm > 0.0 -> {
                val delta = smoothedHrBpm - restingHrBpm
                when {
                    delta >= HR_REL_HEAVY_MIN    -> ActivityLevel.HEAVY
                    delta >= HR_REL_MODERATE_MIN -> ActivityLevel.MODERATE
                    delta >= HR_REL_LIGHT_MIN    -> ActivityLevel.LIGHT
                    else                         -> ActivityLevel.SEDENTARY
                }
            }
            else -> when {
                smoothedHrBpm >= HR_HEAVY_MIN    -> ActivityLevel.HEAVY
                smoothedHrBpm >= HR_MODERATE_MIN -> ActivityLevel.MODERATE
                smoothedHrBpm >= HR_LIGHT_MIN    -> ActivityLevel.LIGHT
                else                             -> ActivityLevel.SEDENTARY
            }
        }

        val stepsLevel = when {
            lastSteps10min >= STEPS_HEAVY_MIN    -> ActivityLevel.HEAVY
            lastSteps10min >= STEPS_MODERATE_MIN -> ActivityLevel.MODERATE
            lastSteps10min >= STEPS_LIGHT_MIN    -> ActivityLevel.LIGHT
            else                                 -> ActivityLevel.SEDENTARY
        }

        val newLevel = if (hrLevel.ordinal >= stepsLevel.ordinal) hrLevel else stepsLevel
        if (newLevel != level) {
            aapsLogger.debug(LTag.APS,
                             "ActivityMonitor: $level → $newLevel  " +
                                 "smoothedHr=${smoothedHrBpm.toInt()}bpm(raw=${rawHrBpm.toInt()}) " +
                                 "steps=${lastSteps10min}/10m")
        }
        level = newLevel
    }

    fun targetOffsetMmol(lightMmol: Double, moderateMmol: Double, heavyMmol: Double): Double = when (level) {
        ActivityLevel.SEDENTARY -> 0.0
        ActivityLevel.LIGHT     -> lightMmol
        ActivityLevel.MODERATE  -> moderateMmol
        ActivityLevel.HEAVY     -> heavyMmol
    }

    val statusString: String get() = when (level) {
        ActivityLevel.SEDENTARY -> "hr=${smoothedHrBpm.toInt()}(raw=${rawHrBpm.toInt()}) steps10m=$lastSteps10min"
        else                    -> "activity=${level.label}(hr=${smoothedHrBpm.toInt()}smooth/${rawHrBpm.toInt()}raw steps10m=$lastSteps10min)"
    }
}