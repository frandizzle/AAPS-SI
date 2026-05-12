package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.weardata.EventData
import javax.inject.Inject
import javax.inject.Singleton
import io.reactivex.rxjava3.disposables.CompositeDisposable

/**
 * ActivityMonitor — HR and steps using RxBus RAM Caching.
 *
 * Eliminates high-frequency DB polling. Listens for incoming Wear OS
 * broadcast events and caches them in memory. Math is computed instantly
 * against RAM, saving battery and CPU wakelock time.
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus
) {
    enum class ActivityLevel {
        SEDENTARY, LIGHT, MODERATE, HEAVY;
        val label get() = name.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        // HR window — 330s (5.5 minutes) staleness cutoff
        const val HR_WINDOW_MS        = 330 * 1000L

        // Steps — 5-min staleness cutoff for the computed window value
        const val STEPS_WINDOW_MS     = 5 * 60 * 1000L

        // Step log retention — keep 10 minutes of history so we always have
        // a "then" baseline to subtract from the latest cumulative count.
        const val STEPS_LOG_RETAIN_MS = 10 * 60 * 1000L

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

    // ── RAM Cache (Replaces Database) ────────────────────────────────────────
    @Volatile private var latestHrBpm: Double = 0.0
    @Volatile private var latestHrTimeMs: Long = 0L

    // Rolling log of (timestampMs, cumulativeSteps) pairs received from Wear OS.
    // We compute the 5-min delta ourselves in recompute() instead of trusting
    // a pre-bucketed field on the event (which silently returned 0).
    private val stepLog = ArrayDeque<Pair<Long, Int>>()

    private val disposables = CompositeDisposable()

    var avgHrBpm:      Double = 0.0; private set
    var lastSteps5min: Int    = 0;   private set
    var level: ActivityLevel  = ActivityLevel.SEDENTARY; private set

    val suppressLearning: Boolean get() = level != ActivityLevel.SEDENTARY

    init {
        subscribeToEvents()
        aapsLogger.debug(LTag.APS, "ActivityMonitor: Subscribed to RxBus for RAM caching")
    }

    private fun subscribeToEvents() {
        rxBus.toObservable(EventData.ActionHeartRate::class.java).subscribe { event ->
            latestHrBpm    = event.beatsPerMinute
            latestHrTimeMs = System.currentTimeMillis()
        }.also { disposables.add(it) }

        rxBus.toObservable(EventData.ActionStepsRate::class.java).subscribe { event ->
            val now = System.currentTimeMillis()

            // Append the steps5min value with its timestamp. Because the Wear side
            // sends a fresh 5-min count each time rather than a cumulative total,
            // we accumulate these samples in the log and sum any entries that fall
            // within the current 5-min window in recompute() for a rolling total.
            synchronized(stepLog) {
                stepLog.addLast(now to event.steps5min)

                // Trim entries older than our retention window to bound memory usage
                while (stepLog.isNotEmpty() && now - stepLog.first().first > STEPS_LOG_RETAIN_MS) {
                    stepLog.removeFirst()
                }
            }

            aapsLogger.debug(LTag.APS,
                             "ActivityMonitor: step event received — steps5min=${event.steps5min} logSize=${stepLog.size}")
        }.also { disposables.add(it) }
    }

    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {

        // 1. Check RAM for fresh HR data
        val hrIsFresh = (nowMs - latestHrTimeMs) <= HR_WINDOW_MS
        avgHrBpm = if (hrIsFresh) latestHrBpm else 0.0

        // 2. Compute 5-min step delta from the rolling log
        lastSteps5min = computeSteps5min(nowMs)

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: avgHr=${avgHrBpm.toInt()}bpm (fresh=$hrIsFresh) " +
                             "steps5m=$lastSteps5min (logSize=${stepLog.size})")

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

    /**
     * Returns the most recent steps5min sample, provided it arrived within
     * [STEPS_WINDOW_MS]. Because event.steps5min is already a pre-bucketed
     * 5-minute count from Wear OS (not a cumulative total), we just need the
     * latest fresh value — no delta math required.
     *
     * The log is still useful here: it lets us average a few recent samples
     * to smooth out any single-event jitter from the Wear side.
     */
    private fun computeSteps5min(nowMs: Long): Int {
        synchronized(stepLog) {
            if (stepLog.isEmpty()) return 0

            val latest = stepLog.last()

            // Newest entry is stale — treat as no data
            if (nowMs - latest.first > STEPS_WINDOW_MS) return 0

            // Average all samples within the window for a smoother reading
            val windowStart = nowMs - STEPS_WINDOW_MS
            val recentSamples = stepLog.filter { (ts, _) -> ts >= windowStart }
            if (recentSamples.isEmpty()) return 0

            return recentSamples.map { it.second }.average().toInt()
        }
    }

    fun targetOffsetMmol(lightMmol: Double, moderateMmol: Double, heavyMmol: Double): Double = when (level) {
        ActivityLevel.SEDENTARY -> 0.0
        ActivityLevel.LIGHT     -> lightMmol
        ActivityLevel.MODERATE  -> moderateMmol
        ActivityLevel.HEAVY     -> heavyMmol
    }

    // Clean up RxBus subscriptions if the object is ever destroyed
    fun destroy() {
        disposables.clear()
    }
}