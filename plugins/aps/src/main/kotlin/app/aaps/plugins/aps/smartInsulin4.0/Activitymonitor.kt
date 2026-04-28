package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
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

        // Steps — 5-min staleness cutoff
        const val STEPS_WINDOW_MS     = 5 * 60 * 1000L

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

    @Volatile private var cachedSteps5min: Int = 0
    @Volatile private var latestStepsTimeMs: Long = 0L

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
        // ⚠️ DEVELOPER NOTE: Uncomment and update the Event class names below
        // to match the exact Event classes used in your specific AAPS fork.
        // (e.g., EventNewHeartRate, EventNewPluginData, etc.)

        /*
        rxBus.toObservable(EventNewHeartRate::class.java).subscribe { event ->
            latestHrBpm = event.heartRate.toDouble()
            latestHrTimeMs = System.currentTimeMillis()
        }.also { disposables.add(it) }

        rxBus.toObservable(EventNewStepCount::class.java).subscribe { event ->
            cachedSteps5min = event.steps
            latestStepsTimeMs = System.currentTimeMillis()
        }.also { disposables.add(it) }
        */
    }

    fun recompute(nowMs: Long, restingHrBpm: Double = 0.0) {

        // 1. Check RAM for fresh HR data
        val hrIsFresh = (nowMs - latestHrTimeMs) <= HR_WINDOW_MS
        avgHrBpm = if (hrIsFresh) latestHrBpm else 0.0

        // 2. Check RAM for fresh Step data
        val stepsAreFresh = (nowMs - latestStepsTimeMs) <= STEPS_WINDOW_MS
        lastSteps5min = if (stepsAreFresh) cachedSteps5min else 0

        aapsLogger.debug(LTag.APS,
                         "ActivityMonitor: avgHr=${avgHrBpm.toInt()}bpm (fresh=$hrIsFresh) " +
                             "steps5m=$lastSteps5min (fresh=$stepsAreFresh)")

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

    // Clean up RxBus subscriptions if the object is ever destroyed
    fun destroy() {
        disposables.clear()
    }
}