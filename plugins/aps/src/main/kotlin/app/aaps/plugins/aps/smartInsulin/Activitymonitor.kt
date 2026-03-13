package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ActivityMonitor — tracks physical activity from HR (watch DB) + steps (phone pedometer).
 *
 * HR:    mirrors TriggerHeartRate — duration-weighted average over last 5.5 min from DB.
 *        Fed into ActivityManager.process() which handles its own EWA smoothing.
 *        No additional smoothing here — single stage only.
 *
 * Steps: mirrors BoostV2 StepService — phone TYPE_STEP_COUNTER pedometer via StepCounterService.
 *        Hardware sensor, always running, no BT/DB dependency. Instant response.
 *
 * Classification: delegated to ActivityManager (AIMI scoring model) which combines
 *        HR reserve + steps into a smoothed intensity score with recovery bucket.
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val aapsLogger:       AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val stepCounter:      StepCounterService,
    private val activityManager:  ActivityManager
) {
    enum class ActivityLevel {
        SEDENTARY, LIGHT, MODERATE, HEAVY;
        val label get() = name.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        // HR: search back this far to survive BT batching delays
        const val HR_SEARCH_MS    = 20 * 60 * 1000L
        // HR: average window — matches TriggerHeartRate.averageHeartRateDurationMillis (330s)
        const val HR_AVG_MS       = 330 * 1000L

        // ActivityManager score→level mapping (mirrors its internal state thresholds)
        const val SCORE_LIGHT_MIN    = 1.0
        const val SCORE_MODERATE_MIN = 3.0
        const val SCORE_HEAVY_MIN    = 6.0
    }

    // ── Public state ──────────────────────────────────────────────────────────
    var avgHrBpm:     Double = 0.0; private set
    var lastSteps5min: Int   = 0;   private set
    var level: ActivityLevel = ActivityLevel.SEDENTARY; private set

    var lastActivityDescription: String = "Rest"; private set
    var isRecovery: Boolean = false; private set

    // Suppress learning during any activity OR post-exercise recovery
    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY || isRecovery

    /**
     * Recompute activity level. Called once per loop cycle from SmartInsulinPlugin.invoke().
     *
     * @param nowMs        Current wall-clock time in ms
     * @param restingHrBpm User-configured resting HR (0 = unknown, uses AIMI 60bpm fallback)
     */
    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {

        // ── Heart Rate — duration-weighted average (TriggerHeartRate pattern) ─
        // Search wide for BT batching delays, average only the last 330s.
        val hrSearchStart = nowMs - HR_SEARCH_MS
        val allHr = try {
            persistenceLayer.getHeartRatesFromTime(hrSearchStart)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR query failed: ${e.message}")
            emptyList()
        }

        val recentHr      = allHr.filter { it.timestamp >= nowMs - HR_AVG_MS }
        val totalDuration = recentHr.sumOf { it.duration }
        avgHrBpm = if (totalDuration > 0L)
            recentHr.sumOf { it.beatsPerMinute * it.duration } / totalDuration.toDouble()
        else
            0.0

        // ── Steps — phone pedometer (BoostV2 StepService pattern) ────────────
        // TYPE_STEP_COUNTER hardware sensor — instant, no BT/DB dependency.
        stepCounter.ensureRegistered()
        lastSteps5min = stepCounter.steps5min

        // ── Classify via ActivityManager (AIMI scoring model) ─────────────────
        // ActivityManager handles EWA smoothing + recovery bucket internally.
        // Pass steps5min for both 5min and 10min params — we only have the 5-min window.
        val ctx = activityManager.process(
            steps5min    = lastSteps5min,
            steps10min   = lastSteps5min,
            avgHr        = avgHrBpm,
            avgHrResting = if (restingHrBpm > 0.0) restingHrBpm else 0.0
        )

        lastActivityDescription = ctx.description
        isRecovery              = ctx.isRecovery

        level = when {
            ctx.isRecovery                           -> ActivityLevel.LIGHT  // conservative during recovery
            ctx.intensityScore >= SCORE_HEAVY_MIN    -> ActivityLevel.HEAVY
            ctx.intensityScore >= SCORE_MODERATE_MIN -> ActivityLevel.MODERATE
            ctx.intensityScore >= SCORE_LIGHT_MIN    -> ActivityLevel.LIGHT
            else                                     -> ActivityLevel.SEDENTARY
        }

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: level=$level avgHr=${avgHrBpm.toInt()}bpm " +
                             "steps5m=$lastSteps5min score=${"%.1f".format(ctx.intensityScore)} " +
                             "recovery=$isRecovery hrRecords=${recentHr.size}/${allHr.size}")
    }

    fun targetOffsetMmol(lightMmol: Double, moderateMmol: Double, heavyMmol: Double): Double = when (level) {
        ActivityLevel.SEDENTARY -> 0.0
        ActivityLevel.LIGHT     -> lightMmol
        ActivityLevel.MODERATE  -> moderateMmol
        ActivityLevel.HEAVY     -> heavyMmol
    }
}