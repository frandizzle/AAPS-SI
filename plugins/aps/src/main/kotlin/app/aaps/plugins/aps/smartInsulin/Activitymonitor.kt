package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ActivityMonitor — HR and steps using stock AAPS trigger patterns verbatim.
 *
 * HR:    TriggerHeartRate pattern — getHeartRatesFromTime(start), duration-weighted average.
 * Steps: TriggerStepsCount pattern — getStepsCountFromTime(start), filter by duration, steps5min.
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
        // HR window — matches TriggerHeartRate.averageHeartRateDurationMillis = 330s
        const val HR_WINDOW_MS        = 330 * 1000L

        // Steps — 5-min bucket, search back 5 min (same as TriggerStepsCount)
        const val STEPS_WINDOW_MS     = 5 * 60 * 1000L
        const val STEPS_DURATION_MS   = 5 * 60 * 1000L  // duration filter for 5-min records

        // HR thresholds — absolute (used when no resting HR configured)
        const val HR_LIGHT_MIN        = 90.0
        const val HR_MODERATE_MIN     = 110.0
        const val HR_HEAVY_MIN        = 140.0

        // HR thresholds — relative above resting (used when resting HR configured)
        const val HR_REL_LIGHT_MIN    = 20.0
        const val HR_REL_MODERATE_MIN = 35.0
        const val HR_REL_HEAVY_MIN    = 60.0

        // Steps thresholds — 5-min window
        const val STEPS_LIGHT_MIN     = 200
        const val STEPS_MODERATE_MIN  = 500
        const val STEPS_HEAVY_MIN     = 900
    }

    var avgHrBpm:      Double = 0.0; private set
    var lastSteps5min: Int    = 0;   private set
    var level: ActivityLevel  = ActivityLevel.SEDENTARY; private set

    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY

    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {

        // ── Heart Rate — TriggerHeartRate pattern ─────────────────────────────
        val hrStart = nowMs - HR_WINDOW_MS
        val hrs = try {
            persistenceLayer.getHeartRatesFromTime(hrStart)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR query failed: ${e.message}")
            emptyList()
        }
        val totalDuration = hrs.sumOf { it.duration }
        avgHrBpm = if (totalDuration > 0L)
            hrs.sumOf { it.beatsPerMinute * it.duration } / totalDuration.toDouble()
        else
            0.0

        // ── Steps — TriggerStepsCount pattern ────────────────────────────────
        val stepsStart = nowMs - STEPS_WINDOW_MS
        val measurements = try {
            persistenceLayer.getStepsCountFromTime(stepsStart)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: steps query failed: ${e.message}")
            emptyList()
        }
        val lastSC = measurements.lastOrNull { it.duration == STEPS_DURATION_MS }
        lastSteps5min = lastSC?.steps5min ?: 0

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: avgHr=${avgHrBpm.toInt()}bpm hrRecords=${hrs.size} " +
                             "steps5m=$lastSteps5min stepsRecords=${measurements.size}")

        // ── Classify — take higher of HR or steps ────────────────────────────
        val hrLevel = when {
            avgHrBpm <= 0.0    -> ActivityLevel.SEDENTARY
            restingHrBpm > 0.0 -> {
                val delta = avgHrBpm - restingHrBpm
                when {
                    delta >= HR_REL_HEAVY_MIN    -> ActivityLevel.HEAVY
                    delta >= HR_REL_MODERATE_MIN -> ActivityLevel.MODERATE
                    delta >= HR_REL_LIGHT_MIN    -> ActivityLevel.LIGHT
                    else                         -> ActivityLevel.SEDENTARY
                }
            }
            else -> when {
                avgHrBpm >= HR_HEAVY_MIN    -> ActivityLevel.HEAVY
                avgHrBpm >= HR_MODERATE_MIN -> ActivityLevel.MODERATE
                avgHrBpm >= HR_LIGHT_MIN    -> ActivityLevel.LIGHT
                else                        -> ActivityLevel.SEDENTARY
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
                             "ActivityMonitor: $level → $newLevel  hr=${avgHrBpm.toInt()}bpm steps=$lastSteps5min/5m")
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