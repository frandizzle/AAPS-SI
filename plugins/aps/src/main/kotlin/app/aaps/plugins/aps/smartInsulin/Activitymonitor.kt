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
 * Queries the last 20 minutes of HR and steps from the DB directly.
 *
 * If no data in DB (watch not worn, no wear device), defaults to SEDENTARY — safe.
 *
 * Classification uses the higher signal from HR or steps.
 * HR uses absolute thresholds. If resting HR known, uses relative (HR - resting).
 *
 * HR smoothing uses an asymmetric EWA:
 *   - Fast attack (HR_SMOOTH_ALPHA_RISE): exercise onset detected within 1–2 loop cycles
 *   - Slow decay (HR_SMOOTH_ALPHA_FALL): brief HR dips mid-exercise don't drop classification
 *   - No-data decay (HR_SMOOTH_DROPOUT_FACTOR): watch dropout doesn't instantly kill detection,
 *     but smoothed HR bleeds to zero over ~4–5 cycles if watch goes offline
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
        const val HR_LIGHT_MIN        = 100.0
        const val HR_MODERATE_MIN     = 120.0
        const val HR_HEAVY_MIN        = 140.0

        // Relative thresholds (bpm above resting HR) — used when resting HR is known
        const val HR_REL_LIGHT_MIN    = 20.0
        const val HR_REL_MODERATE_MIN = 35.0
        const val HR_REL_HEAVY_MIN    = 60.0

        // Steps per 10-min window thresholds (steps10min bucket)
        const val STEPS_LIGHT_MIN     = 400
        const val STEPS_MODERATE_MIN  = 1000
        const val STEPS_HEAVY_MIN     = 1800

        // HR: HeartRateListener sends every 60 seconds. timestamp = END of sampling window.
        // Use 20 min window to survive Bluetooth batching delays without phantom-activity risk.
        // No fallback — if nothing in 20 min the watch is off/charging → smoothed HR decays.
        const val HR_LIVE_WINDOW_MS   = 20 * 60 * 1000L

        // Steps: timestamp = END of sampling period (per StepsCount.kt).
        // Search wide (210 min) for DB records, but only classify fresh (within 20 min).
        // Use steps10min bucket — more resilient to sync delays than steps5min.
        const val STEPS_SEARCH_MS     = 210 * 60 * 1000L
        const val STEPS_FRESH_MS      =  20 * 60 * 1000L

        // Asymmetric EWA smoothing alphas for HR
        // Rise: fast attack — a single high reading moves the smoothed value 60% of the way
        //       toward the raw reading immediately. Exercise onset visible in 1–2 loop cycles.
        // Fall: slow decay — brief HR dips (rest between sets, downhill walk) don't drop
        //       classification. Takes ~4–5 cycles to fully decay from HEAVY back to SEDENTARY.
        const val HR_SMOOTH_ALPHA_RISE    = 0.6
        const val HR_SMOOTH_ALPHA_FALL    = 0.15

        // Per-cycle bleed factor when no fresh HR reading is available (watch dropout).
        // 0.85 → ~4–5 loop cycles (~20–25 min) to decay to zero from a typical exercise HR.
        // Prevents a brief Bluetooth gap from instantly killing activity detection,
        // but doesn't sustain a stale state indefinitely.
        const val HR_SMOOTH_DROPOUT_FACTOR = 0.85
    }

    // ── Smoothed HR state (persists across loop cycles) ───────────────────────
    // rawHrBpm    — most recent raw reading from DB (0 if no fresh record)
    // smoothedHrBpm — asymmetric EWA output — used for classification
    var rawHrBpm: Double = 0.0
        private set
    var smoothedHrBpm: Double = 0.0
        private set

    // ── Last read values (public read-only for reason string / UI display) ────
    // lastHrBpm exposes the smoothed value so all callers (statusString, logging)
    // see the same number that drove classification.
    var lastHrBpm: Double
        get() = smoothedHrBpm
        private set(_) {}   // backing field unused — smoothedHrBpm is the source of truth

    var lastSteps5min: Int = 0
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
        // NOTE: StepsCount.timestamp is the END time of the 5-min window (per SC.kt).
        // Search wide (210 min like AIMI) to handle watch sync delays, then filter to fresh.
        val stepsSearchStart = nowMs - STEPS_SEARCH_MS
        val allSteps = try {
            persistenceLayer.getStepsCountFromTimeToTime(stepsSearchStart, nowMs)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: steps query failed: ${e.message}")
            emptyList()
        }

        // Only use a steps record if its timestamp (= end of window) is within last 20 min.
        val freshSteps = allSteps.filter { it.timestamp >= nowMs - STEPS_FRESH_MS }.maxByOrNull { it.timestamp }
        // steps10min bucket — wider window is more resilient to Bluetooth sync delays.
        // Thresholds are scaled accordingly (doubled vs steps5min values).
        lastSteps5min = freshSteps?.steps10min ?: 0

        // ── Heart Rate — raw fetch ─────────────────────────────────────────────
        // HR.timestamp = END of the 60-second sampling window (confirmed via HeartRateListener.kt).
        // Fetch the most recent record within the live window — single reading, no DB-side averaging.
        val hrSearchStart = nowMs - HR_LIVE_WINDOW_MS
        val allHr = try {
            persistenceLayer.getHeartRatesFromTimeToTime(hrSearchStart, nowMs)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR query failed: ${e.message}")
            emptyList()
        }

        val latestHr = allHr
            .filter { it.timestamp >= hrSearchStart }
            .maxByOrNull { it.timestamp }
        rawHrBpm = latestHr?.beatsPerMinute ?: 0.0

        // ── Heart Rate — asymmetric EWA smoothing ─────────────────────────────
        //
        // Three cases:
        //   1. No fresh reading (watch dropout/charging): bleed smoothed value toward zero
        //      at HR_SMOOTH_DROPOUT_FACTOR per cycle. Survives brief BT gaps without
        //      instantly killing activity detection.
        //   2. First reading (smoothed was 0): seed directly — no smoothing on cold start,
        //      avoids a slow ramp-up from zero when the watch first connects.
        //   3. HR rising: fast attack alpha — exercise onset detected in 1–2 cycles.
        //   4. HR falling: slow decay alpha — brief dips don't collapse classification.
        smoothedHrBpm = when {
            rawHrBpm <= 0.0 -> {
                // No fresh data — bleed toward zero slowly
                if (smoothedHrBpm > 0.0) smoothedHrBpm * HR_SMOOTH_DROPOUT_FACTOR else 0.0
            }
            smoothedHrBpm <= 0.0 -> {
                // Cold start — seed with raw reading directly
                rawHrBpm
            }
            rawHrBpm > smoothedHrBpm -> {
                // Rising — fast attack
                HR_SMOOTH_ALPHA_RISE * rawHrBpm + (1.0 - HR_SMOOTH_ALPHA_RISE) * smoothedHrBpm
            }
            else -> {
                // Falling — slow decay
                HR_SMOOTH_ALPHA_FALL * rawHrBpm + (1.0 - HR_SMOOTH_ALPHA_FALL) * smoothedHrBpm
            }
        }

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: rawHr=${if (rawHrBpm > 0) "${rawHrBpm.toInt()}bpm" else "none"} " +
                             "smoothedHr=${smoothedHrBpm.toInt()}bpm " +
                             "steps10m=$lastSteps5min  hrRecords=${allHr.size}  stepsRecords=${allSteps.size}")

        // ── Classify ──────────────────────────────────────────────────────────
        // Classification always uses smoothedHrBpm — never raw — so a single noisy spike
        // can't instantly jump classification, and brief dips can't instantly drop it.
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
            lastSteps5min >= STEPS_HEAVY_MIN    -> ActivityLevel.HEAVY
            lastSteps5min >= STEPS_MODERATE_MIN -> ActivityLevel.MODERATE
            lastSteps5min >= STEPS_LIGHT_MIN    -> ActivityLevel.LIGHT
            else                                -> ActivityLevel.SEDENTARY
        }

        val newLevel = if (hrLevel.ordinal >= stepsLevel.ordinal) hrLevel else stepsLevel
        if (newLevel != level) {
            aapsLogger.debug(LTag.APS,
                             "ActivityMonitor: $level → $newLevel  " +
                                 "smoothedHr=${smoothedHrBpm.toInt()}bpm(raw=${rawHrBpm.toInt()}) " +
                                 "steps=${lastSteps5min}/10m")
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
        ActivityLevel.SEDENTARY -> "hr=${smoothedHrBpm.toInt()}(raw=${rawHrBpm.toInt()}) steps10m=$lastSteps5min"
        else                    -> "activity=${level.label}(hr=${smoothedHrBpm.toInt()}smooth/${rawHrBpm.toInt()}raw steps10m=$lastSteps5min)"
    }
}