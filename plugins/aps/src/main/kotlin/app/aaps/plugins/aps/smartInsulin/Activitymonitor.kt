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
 * Queries the last 10 minutes of HR and steps from the DB directly.
 *
 * If no data in DB (watch not worn, no wear device), defaults to SEDENTARY — safe.
 *
 * Classification uses the higher signal from HR or steps.
 * HR uses absolute thresholds. If resting HR known, uses relative (HR - resting).
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
        // HR thresholds — absolute bpm
        const val HR_LIGHT_MIN        = 100.0
        const val HR_MODERATE_MIN     = 120.0
        const val HR_HEAVY_MIN        = 135.0

        // Relative thresholds (bpm above resting HR) — used when resting HR is known
        const val HR_REL_LIGHT_MIN    = 20.0
        const val HR_REL_MODERATE_MIN = 35.0
        const val HR_REL_HEAVY_MIN    = 60.0

        // Steps per 5-min window thresholds
        const val STEPS_LIGHT_MIN     = 200
        const val STEPS_MODERATE_MIN  = 500
        const val STEPS_HEAVY_MIN     = 900

        // HR: HeartRateListener sends every 60 seconds. timestamp = END of sampling window.
        // Use 20 min window to survive Bluetooth batching delays without phantom-activity risk.
        // No fallback — if nothing in 20 min the watch is off/charging → SEDENTARY.
        const val HR_LIVE_WINDOW_MS   = 20 * 60 * 1000L

        // Steps: timestamp = END of sampling period (per StepsCount.kt).
        // Search wide (210 min) for DB records, but only classify fresh (within 20 min).
        // Use steps10min bucket — more resilient to sync delays than steps5min.
        const val STEPS_SEARCH_MS     = 210 * 60 * 1000L
        const val STEPS_FRESH_MS      =  20 * 60 * 1000L
    }

    // ── Last read values (public read-only for reason string display) ─────────
    var lastHrBpm:    Double = 0.0
        private set
    var lastSteps5min: Int   = 0
        private set

    // ── Computed level ────────────────────────────────────────────────────────
    var level: ActivityLevel = ActivityLevel.SEDENTARY
        private set

    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY

    /**
     * Query DB and recompute activity level.
     * Called once per loop cycle from SmartInsulinPlugin.invoke().
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

        // Only use a steps record if its timestamp (= end of 5-min window) is within last 10 min.
        // No fallback — same reason as HR: stale steps shouldn't sustain activity targeting.
        val freshSteps = allSteps.filter { it.timestamp >= nowMs - STEPS_FRESH_MS }.maxByOrNull { it.timestamp }
        // Use steps10min bucket — wider window is more resilient to Bluetooth sync delays
        lastSteps5min  = freshSteps?.steps10min ?: 0

        // ── Heart Rate ────────────────────────────────────────────────────────
        // Use the single most recent HR record whose END (timestamp + duration) is within
        // the last 10 minutes. Most recent = lowest latency. No averaging — averaging
        // old + new readings introduces lag (e.g. 75bpm 5min ago + 95bpm now = 85bpm reported).
        // No fallback: if nothing fresh → watch off/charging → SEDENTARY.
        val hrSearchStart = nowMs - HR_LIVE_WINDOW_MS
        val allHr = try {
            persistenceLayer.getHeartRatesFromTimeToTime(hrSearchStart, nowMs)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: HR query failed: ${e.message}")
            emptyList()
        }

        // HR.timestamp = END of the 60-second sampling window (confirmed via HeartRateListener.kt).
        // Filter: record ended within last 20 min. Pick most recent by timestamp.
        val latestHr = allHr
            .filter { it.timestamp >= hrSearchStart }
            .maxByOrNull { it.timestamp }
        lastHrBpm = latestHr?.beatsPerMinute ?: 0.0

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: hr=${if (lastHrBpm > 0) "${lastHrBpm.toInt()}bpm" else "none"} " +
                             "steps5m=$lastSteps5min  hrRecords=${allHr.size}  stepsRecords=${allSteps.size}")

        // ── Classify ──────────────────────────────────────────────────────────
        val hrLevel = when {
            lastHrBpm <= 0.0 -> ActivityLevel.SEDENTARY
            restingHrBpm > 0.0 -> {
                val delta = lastHrBpm - restingHrBpm
                when {
                    delta >= HR_REL_HEAVY_MIN    -> ActivityLevel.HEAVY
                    delta >= HR_REL_MODERATE_MIN -> ActivityLevel.MODERATE
                    delta >= HR_REL_LIGHT_MIN    -> ActivityLevel.LIGHT
                    else                         -> ActivityLevel.SEDENTARY
                }
            }
            else -> when {
                lastHrBpm >= HR_HEAVY_MIN    -> ActivityLevel.HEAVY
                lastHrBpm >= HR_MODERATE_MIN -> ActivityLevel.MODERATE
                lastHrBpm >= HR_LIGHT_MIN    -> ActivityLevel.LIGHT
                else                         -> ActivityLevel.SEDENTARY
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
                             "ActivityMonitor: $level → $newLevel  hr=${lastHrBpm.toInt()}bpm steps=$lastSteps5min/5m")
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
        ActivityLevel.SEDENTARY -> "hr=${lastHrBpm.toInt()} steps5m=$lastSteps5min"
        else -> "activity=${level.label}(hr=${lastHrBpm.toInt()} steps5m=$lastSteps5min)"
    }
}