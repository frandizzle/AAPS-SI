package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
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
    private val persistenceLayer: PersistenceLayer,
    private val aapsSchedulers:   AapsSchedulers,
    private val phoneStepCounter: PhoneStepCounter
) {
    enum class ActivityLevel {
        SEDENTARY, LIGHT, MODERATE, HEAVY;
        val label get() = name.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        // HR window — matches TriggerHeartRate.averageHeartRateDurationMillis = 330s
        const val HR_WINDOW_MS        = 330 * 1000L

        const val STEPS_DURATION_MS   = 5 * 60 * 1000L  // duration filter for 5-min records

        /**
         * How far back to look for the newest steps record.
         *
         * Was 5 min, copied from TriggerStepsCount — and that is a silent cliff here. The stored
         * timestamp comes from the WATCH (DataHandlerMobile stores it verbatim) while this query
         * uses the PHONE's clock, so watch-to-phone transport delay and any clock skew between the
         * two both eat into the window. A record 6 min old was dropped outright, lastSteps5min
         * went to 0, and the tab looked like you had not moved — then a record would happen to
         * land inside the window and steps would appear several cycles late. Looking back further
         * finds the record; [STEPS_MAX_AGE_MS] is what decides whether it still counts.
         */
        const val STEPS_LOOKBACK_MS   = 15 * 60 * 1000L

        /**
         * Age past which a steps record stops counting toward the activity level.
         *
         * The watch reports the previous COMPLETED 5-min bucket, so its content is already up to
         * 5 min old before it is even sent. 10 min of record age on top of that is the outer edge
         * of "recent enough to still be exercising"; beyond it the record is reported as stale
         * rather than silently becoming zero, so a transport problem is visible instead of looking
         * like a sedentary user.
         */
        const val STEPS_MAX_AGE_MS    = 10 * 60 * 1000L

        // HR thresholds — absolute (used when no resting HR configured)
        const val HR_LIGHT_MIN        = 90.0
        const val HR_MODERATE_MIN     = 110.0
        const val HR_HEAVY_MIN        = 140.0

        // HR thresholds — relative above resting (used when resting HR configured)
        const val HR_REL_LIGHT_MIN    = 20.0
        const val HR_REL_MODERATE_MIN = 35.0
        const val HR_REL_HEAVY_MIN    = 60.0

        // Steps thresholds — 5-min window
        const val STEPS_LIGHT_MIN     = 150
        const val STEPS_MODERATE_MIN  = 300
        const val STEPS_HEAVY_MIN     = 600

        /**
         * How stale the cached values may be before a display read asks for a refresh.
         *
         * The loop recomputes once per cycle, so anything reading these between cycles was seeing
         * numbers up to five minutes old — which is most of why the tab looked like it had not
         * noticed you were walking. The wear sends a fresh record every 40s, so refreshing faster
         * than that buys nothing but database reads.
         */
        const val DISPLAY_MAX_AGE_MS  = 30 * 1000L
    }

    // Written from the loop thread and from the background refresh below, read from the UI thread.
    @Volatile var avgHrBpm:      Double = 0.0; private set
    @Volatile var lastSteps5min: Int    = 0;   private set
    /** Age of the newest steps record, or null when none was found at all. Surfaced on the SI tab
     *  so a watch that is delivering late is distinguishable from a watch that sees no steps. */
    @Volatile var lastStepsAgeMs: Long? = null; private set
    /** Steps the phone's own pedometer saw over the same window, and whether it, rather than the
     *  watch, is the number being used. */
    @Volatile var phoneSteps5min: Int = 0;      private set
    @Volatile var stepsFromPhone: Boolean = false; private set
    val phoneStepState: PhoneStepCounter.State get() = phoneStepCounter.state
    @Volatile var level: ActivityLevel  = ActivityLevel.SEDENTARY; private set

    @Volatile private var lastComputedMs = 0L
    @Volatile private var refreshQueued  = false

    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY

    /**
     * Refresh the cached values if they have gone stale, WITHOUT blocking the caller.
     *
     * For display paths, which run on the UI thread: [recompute] issues two blocking database
     * reads, so calling it from there directly would put a database round-trip on every frame that
     * asks. This hops to IO instead and lets the next read pick the result up — the SI tab ticks
     * every 10s, so in practice the number on screen is at most one tick behind the watch.
     *
     * The loop calls [recompute] directly: it is already off the UI thread, and it needs this
     * cycle's value before it doses, not whenever a background job happens to land.
     */
    fun requestRefresh(nowMs: Long, restingHrBpm: Double = 0.0) {
        if (refreshQueued || nowMs - lastComputedMs < DISPLAY_MAX_AGE_MS) return
        refreshQueued = true
        aapsSchedulers.io.scheduleDirect {
            try { recompute(System.currentTimeMillis(), restingHrBpm) } finally { refreshQueued = false }
        }
    }

    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {
        lastComputedMs = nowMs

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

        // ── Steps — TriggerStepsCount pattern, over a wider window ───────────
        val stepsStart = nowMs - STEPS_LOOKBACK_MS
        val measurements = try {
            persistenceLayer.getStepsCountFromTime(stepsStart)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "ActivityMonitor: steps query failed: ${e.message}")
            emptyList()
        }
        // Use tolerance rather than strict equality — OS/thread scheduling jitter
        // can cause the recorded duration to be a few ms off the exact 300000ms bucket.
        val lastSC = measurements.lastOrNull { Math.abs(it.duration - STEPS_DURATION_MS) < 5000L }
        // maxOf(0) because the timestamp is the watch's: a watch running slightly ahead of the
        // phone would otherwise produce a negative age.
        val stepsAgeMs = lastSC?.let { maxOf(0L, nowMs - it.timestamp) }
        lastStepsAgeMs = stepsAgeMs
        val watchSteps = if (stepsAgeMs != null && stepsAgeMs <= STEPS_MAX_AGE_MS) lastSC.steps5min else 0

        // Higher of the two sources, because they fail in opposite directions: the phone sees
        // nothing while it sits on a desk, the watch reports a block that closed minutes ago.
        // Taking the max means neither can hide the other's steps, and the same rule already
        // decides between HR and steps below.
        phoneSteps5min = phoneStepCounter.stepsInLast(STEPS_DURATION_MS, nowMs)
        stepsFromPhone = phoneSteps5min > watchSteps
        lastSteps5min  = maxOf(watchSteps, phoneSteps5min)

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: avgHr=${avgHrBpm.toInt()}bpm hrRecords=${hrs.size} " +
                             "steps5m=$lastSteps5min (watch=$watchSteps phone=$phoneSteps5min " +
                             "phoneState=${phoneStepCounter.state}) stepsRecords=${measurements.size} " +
                             "newestStepsAge=${stepsAgeMs?.let { "${it / 1000}s" } ?: "none"}")

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