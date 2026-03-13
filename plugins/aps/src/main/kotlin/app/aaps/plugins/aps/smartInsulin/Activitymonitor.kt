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
 *   - HR_DROPOUT_MS: how long with no HR record before treating watch as offline
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val aapsLogger:       AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val stepCounter:      StepCounterService
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

        // Steps — thresholds for 5-min native pedometer window
        const val STEPS_LIGHT_MIN     = 200
        const val STEPS_MODERATE_MIN  = 500
        const val STEPS_HEAVY_MIN     = 900

        // HR fetch window — search back this far to handle BT batching/sync delays.
        const val HR_SEARCH_MS        = 20 * 60 * 1000L

        // HR dropout threshold — if the most recent HR record is older than this,
        // the watch is considered offline/charging and smoothedHrBpm decays.
        // Must be >= loop interval (~5 min) + HeartRateListener interval (1 min) + BT slack.
        // 7 min gives one full loop cycle of slack before treating absence as dropout.
        const val HR_DROPOUT_MS       =  7 * 60 * 1000L


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

        // ── Steps — native Android pedometer ────────────────────────────────
        // TYPE_STEP_COUNTER hardware sensor — no BT/DB dependency.
        // StepCounterService registers once and maintains a rolling 5-min delta.
        stepCounter.ensureRegistered()
        lastSteps5min = stepCounter.steps5min

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: steps5m=$lastSteps5min totalSteps=${stepCounter.totalStepsSinceRegistration}")


        // ── Heart Rate — fetch ────────────────────────────────────────────────
        // HR.timestamp = END of the 60-second sampling window (HeartRateListener.kt).
        // Search HR_SEARCH_MS back to survive BT batching delays.
        // Single smoothing stage (EWA below) — no pre-average to avoid double-lagging.
        val hrSearchStart = nowMs - HR_SEARCH_MS
        val allHr = try {
            persistenceLayer.getHeartRatesFromTime(hrSearchStart)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR query failed: ${e.message}")
            emptyList()
        }

        val latestHr    = allHr.maxByOrNull { it.timestamp }
        val isNewSample = latestHr != null && latestHr.timestamp > lastProcessedHrTimestamp
        val isDropout   = latestHr == null || latestHr.timestamp < nowMs - HR_DROPOUT_MS

        // Duration-weighted average over the last 90s — short enough to react quickly,
        // long enough to smooth per-beat noise. EWA adds final hysteresis on top.
        // ── Heart Rate — asymmetric EWA smoothing ─────────────────────────────
        //
        // Three distinct cases — must not conflate "already processed" with "dropout":
        //
        //   isNewSample = true  → new record arrived since last loop: apply EWA, record timestamp
        //   isDropout   = true  → no record within HR_DROPOUT_MS: watch offline → decay
        //   neither             → same record, already processed, watch still live → HOLD
        //                         Do not decay. Do not re-smooth. Just keep smoothedHrBpm as-is.
        //
        // The HOLD case is the critical fix: without it, every loop cycle between new HR
        // records (which arrive every 60s but are read every ~5 min) would incorrectly
        // decay smoothedHrBpm, causing it to drop ~10 bpm per loop to zero.
        when {
            isNewSample -> {
                rawHrBpm = latestHr!!.beatsPerMinute
                smoothedHrBpm = when {
                    smoothedHrBpm <= 0.0 ->
                        // Cold start — seed directly, no ramp-up from zero
                        rawHrBpm
                    rawHrBpm > smoothedHrBpm ->
                        // Rising — fast attack
                        HR_SMOOTH_ALPHA_RISE * rawHrBpm + (1.0 - HR_SMOOTH_ALPHA_RISE) * smoothedHrBpm
                    else ->
                        // Falling — slow decay
                        HR_SMOOTH_ALPHA_FALL * rawHrBpm + (1.0 - HR_SMOOTH_ALPHA_FALL) * smoothedHrBpm
                }
                lastProcessedHrTimestamp = latestHr.timestamp
            }
            isDropout -> {
                // Watch offline or charging — bleed smoothedHrBpm toward zero
                rawHrBpm      = 0.0
                smoothedHrBpm = if (smoothedHrBpm > 1.0) smoothedHrBpm * HR_SMOOTH_DROPOUT_FACTOR else 0.0
            }
            else -> {
                // HOLD — same record already processed, watch still live between loop cycles
                // rawHrBpm stays 0 (no new reading this cycle), smoothedHrBpm unchanged
                rawHrBpm = 0.0
            }
        }

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: rawHr=${rawHrBpm.toInt()}bpm smoothedHr=${smoothedHrBpm.toInt()}bpm " +
                             "newSample=$isNewSample hrRecords=${allHr.size} steps5m=$lastSteps5min")

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
            lastSteps5min >= STEPS_HEAVY_MIN    -> ActivityLevel.HEAVY
            lastSteps5min >= STEPS_MODERATE_MIN -> ActivityLevel.MODERATE
            lastSteps5min >= STEPS_LIGHT_MIN    -> ActivityLevel.LIGHT
            else                                 -> ActivityLevel.SEDENTARY
        }

        val newLevel = if (hrLevel.ordinal >= stepsLevel.ordinal) hrLevel else stepsLevel
        if (newLevel != level) {
            aapsLogger.debug(LTag.APS,
                             "ActivityMonitor: $level → $newLevel  " +
                                 "smoothedHr=${smoothedHrBpm.toInt()}bpm(raw=${rawHrBpm.toInt()}) " +
                                 "steps=${lastSteps5min}/5m")
        }
        level = newLevel
    }

    fun targetOffsetMmol(lightMmol: Double, moderateMmol: Double, heavyMmol: Double): Double = when (level) {
        ActivityLevel.SEDENTARY -> 0.0
        ActivityLevel.LIGHT     -> lightMmol
        ActivityLevel.MODERATE  -> moderateMmol
        ActivityLevel.HEAVY     -> heavyMmol
    }


}