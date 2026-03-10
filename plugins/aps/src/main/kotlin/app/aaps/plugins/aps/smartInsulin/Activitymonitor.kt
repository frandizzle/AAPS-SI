package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ActivityMonitor — tracks physical activity from HR (wear) + steps (watch/phone).
 *
 * HR data path:
 *   Wear → EventData.ActionHeartRate → DataLayerListenerServiceMobile
 *   → stored in DB HeartRate table → and/or broadcast on RxBus
 *   SmartInsulinPlugin subscribes to the RxBus event and calls [feedHeartRate].
 *
 * Steps data path:
 *   StepsCount DB entity (steps5min field) received from wear.
 *   SmartInsulinPlugin reads iobCobCalculator.ads or subscribes to steps event
 *   and calls [feedSteps].
 *
 * If no data arrives (watch not worn, no wear device), defaults to SEDENTARY — safe.
 *
 * Activity levels:
 *   SEDENTARY   — no effect
 *   LIGHT       — target +lightTargetMmol, learning suppressed
 *   MODERATE    — target +moderateTargetMmol, learning suppressed
 *   HEAVY       — target +heavyTargetMmol, learning suppressed
 *
 * Classification uses the higher signal from HR or steps.
 * HR uses absolute thresholds. If resting HR known, uses relative (HR - resting).
 * Stale data (>10 min old) is treated as SEDENTARY.
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    enum class ActivityLevel {
        SEDENTARY, LIGHT, MODERATE, HEAVY;
        val label get() = name.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        // HR thresholds — absolute bpm
        const val HR_LIGHT_MIN    = 90.0
        const val HR_MODERATE_MIN = 110.0
        const val HR_HEAVY_MIN    = 140.0

        // Relative thresholds (bpm above resting HR) — used when resting HR is known
        const val HR_REL_LIGHT_MIN    = 20.0
        const val HR_REL_MODERATE_MIN = 35.0
        const val HR_REL_HEAVY_MIN    = 60.0

        // Steps per 5-min window thresholds
        const val STEPS_LIGHT_MIN     = 200
        const val STEPS_MODERATE_MIN  = 500
        const val STEPS_HEAVY_MIN     = 900

        // Data older than this is ignored
        const val STALE_THRESHOLD_MS  = 10 * 60 * 1000L  // 10 minutes
    }

    // ── Received data ─────────────────────────────────────────────────────────
    private var lastHrBpm:       Double = 0.0
    private var lastHrTimestampMs: Long = 0L
    private var lastSteps5min:   Int    = 0
    private var lastStepsTimestampMs: Long = 0L

    // ── Computed level ────────────────────────────────────────────────────────
    var level: ActivityLevel = ActivityLevel.SEDENTARY
        private set

    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY

    // ── Data feed methods (called from SmartInsulinPlugin RxBus subscribers) ──

    /** Call when a new HR reading arrives from wear via RxBus. */
    fun feedHeartRate(bpm: Double, timestampMs: Long) {
        if (bpm > 0.0) {
            lastHrBpm = bpm
            lastHrTimestampMs = timestampMs
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR=${bpm.toInt()}bpm @$timestampMs")
        }
    }

    /** Call when a new StepsCount arrives. Pass steps5min from the StepsCount entity. */
    fun feedSteps(steps5min: Int, timestampMs: Long) {
        if (steps5min >= 0) {
            lastSteps5min = steps5min
            lastStepsTimestampMs = timestampMs
            aapsLogger.debug(LTag.APS, "ActivityMonitor: steps5min=$steps5min @$timestampMs")
        }
    }

    /**
     * Recompute activity level. Call once per loop cycle from SmartInsulinPlugin
     * after feeding any new HR/steps data.
     * @param restingHrBpm  User's known resting HR (0 = unknown, use absolute thresholds)
     */
    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {
        val hrFresh    = (nowMs - lastHrTimestampMs)    <= STALE_THRESHOLD_MS && lastHrBpm > 0.0
        val stepsFresh = (nowMs - lastStepsTimestampMs) <= STALE_THRESHOLD_MS && lastSteps5min > 0

        val hrLevel = when {
            !hrFresh -> ActivityLevel.SEDENTARY
            restingHrBpm > 0.0 -> {
                val delta = lastHrBpm - restingHrBpm
                when {
                    delta >= HR_REL_HEAVY_MIN    -> ActivityLevel.HEAVY
                    delta >= HR_REL_MODERATE_MIN -> ActivityLevel.MODERATE
                    delta >= HR_REL_LIGHT_MIN    -> ActivityLevel.LIGHT
                    else                          -> ActivityLevel.SEDENTARY
                }
            }
            else -> when {
                lastHrBpm >= HR_HEAVY_MIN    -> ActivityLevel.HEAVY
                lastHrBpm >= HR_MODERATE_MIN -> ActivityLevel.MODERATE
                lastHrBpm >= HR_LIGHT_MIN    -> ActivityLevel.LIGHT
                else                          -> ActivityLevel.SEDENTARY
            }
        }

        val stepLevel = when {
            !stepsFresh                          -> ActivityLevel.SEDENTARY
            lastSteps5min >= STEPS_HEAVY_MIN     -> ActivityLevel.HEAVY
            lastSteps5min >= STEPS_MODERATE_MIN  -> ActivityLevel.MODERATE
            lastSteps5min >= STEPS_LIGHT_MIN     -> ActivityLevel.LIGHT
            else                                 -> ActivityLevel.SEDENTARY
        }

        val newLevel = if (hrLevel.ordinal >= stepLevel.ordinal) hrLevel else stepLevel
        if (newLevel != level) {
            aapsLogger.debug(LTag.APS,
                             "ActivityMonitor: $level → $newLevel  hr=${if (hrFresh) "${lastHrBpm.toInt()}bpm" else "stale"}  steps=${if (stepsFresh) "$lastSteps5min/5min" else "stale"}")
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
        ActivityLevel.SEDENTARY -> ""
        else -> "activity=${level.label}(hr=${lastHrBpm.toInt()} steps5m=$lastSteps5min)"
    }
}