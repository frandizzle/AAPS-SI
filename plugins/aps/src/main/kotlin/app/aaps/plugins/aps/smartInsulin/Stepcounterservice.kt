package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.di.ApplicationContext
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Android pedometer using TYPE_STEP_COUNTER hardware sensor.
 *
 * TYPE_STEP_COUNTER — cumulative steps since last device reboot. Hardware-fused,
 * low power, always running as long as the sensor is registered.
 * Much more reliable than DB-backed steps from a BT watch.
 *
 * How it works:
 *  - Registers the sensor once on first [ensureRegistered] call (called from ActivityMonitor).
 *  - Each sensor event gives cumulative total steps since boot.
 *  - We store timestamped snapshots in a rolling 10-min deque.
 *  - [steps5min] returns the delta between now and the snapshot closest to 5 min ago.
 *
 * Thread safety: SensorEventListener callbacks arrive on the handler thread we provide.
 * The deque and baseline are accessed from both that thread and the loop thread —
 * all access is @Synchronized.
 */
@Singleton
class StepCounterService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aapsLogger: AAPSLogger
) : SensorEventListener {

    private data class Snapshot(val steps: Long, val timestampMs: Long)

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // Rolling window of step snapshots — kept for 10 min, enough for 5-min delta
    private val snapshots = ArrayDeque<Snapshot>()

    // Cumulative step total at registration time — subtracted so we count from 0
    private var baselineSteps: Long = -1L

    @Volatile private var registered = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Steps counted in approximately the last 5 minutes.
     * Returns 0 if sensor not yet registered or not enough history.
     */
    val steps5min: Int
        @Synchronized get() {
            if (snapshots.isEmpty()) return 0
            val nowMs = System.currentTimeMillis()
            val targetMs = nowMs - WINDOW_MS
            // Find the snapshot closest to 5 min ago
            val reference = snapshots.minByOrNull { Math.abs(it.timestampMs - targetMs) }
                ?: return 0
            val latest = snapshots.last()
            val delta = (latest.steps - reference.steps).coerceAtLeast(0L)
            return delta.toInt()
        }

    /**
     * Most recent cumulative step count (relative to registration baseline).
     * Useful for debugging.
     */
    val totalStepsSinceRegistration: Long
        @Synchronized get() = snapshots.lastOrNull()?.steps ?: 0L

    /**
     * Call once from ActivityMonitor.recompute() — idempotent after first call.
     * Registers the sensor if available. No-op if already registered or sensor absent.
     */
    fun ensureRegistered() {
        if (registered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            aapsLogger.debug(LTag.APS, "StepCounterService: TYPE_STEP_COUNTER not available on this device")
            return
        }
        // SENSOR_DELAY_NORMAL is sufficient — step counter events fire on each step anyway
        val ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        registered = ok
        aapsLogger.debug(LTag.APS, "StepCounterService: sensor registered=$ok")
    }

    fun unregister() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        aapsLogger.debug(LTag.APS, "StepCounterService: sensor unregistered")
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val raw = event.values[0].toLong()

        // Capture baseline on first event
        if (baselineSteps < 0L) {
            baselineSteps = raw
            aapsLogger.debug(LTag.APS, "StepCounterService: baseline set at $baselineSteps steps")
        }

        val relativeSteps = raw - baselineSteps
        val nowMs = System.currentTimeMillis()
        snapshots.addLast(Snapshot(relativeSteps, nowMs))

        // Prune snapshots older than PRUNE_MS (keep 10 min of history)
        while (snapshots.isNotEmpty() && snapshots.peekFirst().timestampMs < nowMs - PRUNE_MS) {
            snapshots.pollFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val WINDOW_MS = 5 * 60 * 1000L   // 5-min step delta window
        private const val PRUNE_MS  = 10 * 60 * 1000L  // keep 10 min of snapshots
    }
}