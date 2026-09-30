package app.aaps.plugins.aps.smartInsulin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Steps from the phone's own hardware pedometer.
 *
 * A second source alongside the watch, not a replacement, because the two fail in opposite
 * directions: the phone misses everything you do while it is on a desk, and the watch is slow.
 * [ActivityMonitor] takes whichever reports more.
 *
 * Why it is worth having at all — the watch path stacks four delays, and this has none of them:
 *
 *  - the watch buckets into fixed 5-min blocks and reports the previous COMPLETED one, so its
 *    numbers are up to 5 min old before they are even sent. This keeps a rolling window instead,
 *    so "steps in the last 5 minutes" means exactly that at the moment it is asked.
 *  - watch-to-phone transport, which fires a foreground service per record and is throttled hard
 *    in the background. There is no transport here.
 *  - clock skew between the two devices, which shifted records out of the query window entirely.
 *    One clock now.
 *  - a database round-trip per read. This is in memory.
 *
 * TYPE_STEP_COUNTER is a low-power hardware sensor reporting steps since boot, so the cost of
 * listening is close to nothing. Its value is cumulative and resets on reboot, which is what the
 * regression check in [onSensorChanged] is for.
 */
@SingleIn(AppScope::class)
class PhoneStepCounter @Inject constructor(
    private val context:    Context,
    private val aapsLogger: AAPSLogger
) : SensorEventListener {

    /** Why the phone pedometer is or is not contributing — surfaced on the SI tab, because a
     *  source that silently contributes nothing is indistinguishable from one that says zero. */
    enum class State { STOPPED, NO_SENSOR, NEEDS_PERMISSION, RUNNING }

    companion object {
        /** Kept a little longer than any window asked for, so a caller can never see a partially
         *  pruned history. */
        private const val RETAIN_MS = 30 * 60 * 1000L
    }

    @Volatile var state: State = State.STOPPED
        private set

    /** (epoch ms, steps in that event). Guarded by its own monitor — written from the sensor
     *  thread, read from the loop and the UI. */
    private val events = ArrayDeque<Pair<Long, Int>>()
    private var previousTotal = -1L

    private val sensorManager: SensorManager?
        get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun permissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Register for step events. Safe to call repeatedly — re-registering after the permission is
     * granted is exactly how the SI tab's request button takes effect without a restart.
     */
    fun start() {
        if (state == State.RUNNING) return
        if (!permissionGranted()) {
            state = State.NEEDS_PERMISSION
            aapsLogger.debug(LTag.APS, "PhoneStepCounter: ACTIVITY_RECOGNITION not granted")
            return
        }
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            state = State.NO_SENSOR
            aapsLogger.debug(LTag.APS, "PhoneStepCounter: no TYPE_STEP_COUNTER on this device")
            return
        }
        // maxReportLatencyUs = 0: deliver as they happen. The default lets the hardware batch for
        // power, which would put back the very lag this exists to remove.
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
        previousTotal = -1L
        state = State.RUNNING
        aapsLogger.debug(LTag.APS, "PhoneStepCounter: listening")
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        synchronized(events) { events.clear() }
        previousTotal = -1L
        state = State.STOPPED
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val total = event.values?.firstOrNull()?.toLong() ?: return
        recordTotal(total, eventEpochMs(event.timestamp))
    }

    /**
     * Fold one cumulative-since-boot reading into the rolling history.
     *
     * Split out from [onSensorChanged] so the counting can be tested without a SensorEvent, which
     * cannot be constructed off-device.
     */
    internal fun recordTotal(total: Long, atMs: Long) {
        // First sample establishes the baseline; a value that went DOWN means the counter reset,
        // which happens on reboot. Either way there is no usable delta, only a new baseline.
        if (previousTotal < 0L || total < previousTotal) { previousTotal = total; return }
        val delta = (total - previousTotal).toInt()
        previousTotal = total
        if (delta <= 0) return
        synchronized(events) {
            events.addLast(atMs to delta)
            prune(atMs)
        }
    }

    /**
     * Steps in the last [windowMs]. A true rolling sum, which is the whole point — the watch can
     * only answer for a block that has already closed.
     */
    fun stepsInLast(windowMs: Long, nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(events) {
            prune(nowMs)
            val from = nowMs - windowMs
            events.filter { it.first >= from }.sumOf { it.second }
        }

    /**
     * Sensor timestamps are nanoseconds on the elapsed-realtime clock, not epoch. Converting
     * rather than stamping arrival time keeps a batch that was buffered while the CPU slept
     * attributed to when the steps were actually taken.
     */
    private fun eventEpochMs(eventTimestampNanos: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - eventTimestampNanos) / 1_000_000L

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - RETAIN_MS
        while (events.isNotEmpty() && events.first().first < cutoff) events.removeFirst()
    }
}
