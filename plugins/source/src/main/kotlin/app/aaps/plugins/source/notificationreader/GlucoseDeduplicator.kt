/*
 * Ported from AndroidAPS dev (4.0) — nightscout/AndroidAPS, plugins/source/src/androidMain/
 * kotlin/app/aaps/plugins/source/notificationreader/GlucoseDeduplicator.kt
 *
 * Copied unchanged apart from this header: the file has no dependency on anything 4.0 introduced
 * (no Metro DI, no Compose, no multiplatform source sets), so keeping it byte-identical is what
 * makes re-syncing against upstream a diff rather than a merge.
 *
 * AndroidAPS is licensed AGPL-3.0; this fork inherits that licence.
 */
package app.aaps.plugins.source.notificationreader

import app.aaps.plugins.source.notificationreader.GlucoseDeduplicator.Companion.SNAP_UP_CONSECUTIVE
import app.aaps.plugins.source.notificationreader.GlucoseDeduplicator.Companion.snapGapToKnownIntervalMs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-package deduplication for glucose readings extracted from CGM notifications.
 *
 * The same CGM notification is often re-posted multiple times during a single sensor cycle,
 * producing duplicate readings. This class enforces a per-package interval window: any reading
 * arriving within that window is rejected as a duplicate, regardless of value. Short-gap
 * value changes (e.g. Dexcom posting the old value and the new 5-min reading within seconds
 * during a transition) are notification noise, not evidence of a shorter sensor cadence.
 *
 * Adaptation:
 *  - **Snap up** (longer interval) — requires [SNAP_UP_CONSECUTIVE] consecutive gaps that snap to the
 *    same larger known interval, with no shorter gaps interrupting. This covers seed-too-low
 *    cases (e.g. default 5 min for an actual 15-min sensor).
 *  - **No snap down.** The configured seed (or default) is a hard floor. All production packages
 *    are seeded with their true cadence in `notification_reader_packages.json`; unknown packages
 *    default to 5 min, which is the shortest real CGM cadence we expect. Allowing the interval to
 *    decay below the seed was the source of the Dexcom field bug where a single transition glitch
 *    permanently dropped dedup to 1-min mode.
 *
 * Known intervals: 1, 3, 5, 15 minutes (mapped via [snapGapToKnownIntervalMs]).
 *
 * ## Divergence from upstream: the repeat-value guard
 *
 * Upstream accepts any reading once the gap clears [REPOST_THRESHOLD_FRACTION] of the interval,
 * explicitly "regardless of value". That holds only if the CGM app posts once per reading. A G7
 * app keeps an ONGOING notification and re-posts it for reasons of its own — connectivity, alert
 * state, a foreground-service tick — and every re-post looks like a fresh reading to a purely
 * time-based rule.
 *
 * The failure that produces is specific and self-sustaining. With a 5-min interval the bar is
 * 4:00. A re-post landing at 4:05 after the last accepted reading is taken, carrying the SAME
 * value, and the window restarts from there. The genuine reading arriving at 5:00 is then only
 * 55s later and gets rejected. Acceptance locks onto a ~4-minute beat set by the threshold rather
 * than by the sensor, permanently out of phase with it, holding each value one cycle late.
 *
 * So an unchanged value now has to wait nearly the whole interval ([REPEAT_VALUE_FRACTION], 4:45
 * of a 5-min cycle) rather than 4:00. A re-post of the same reading cannot clear that; a real
 * reading can, and a reading whose value has MOVED is still admitted at the original 4:00 bar, so
 * genuine early arrivals are unaffected. Flat glucose is the only case this can delay, and by at
 * most the 45s between the two bars.
 */
class GlucoseDeduplicator(
    private val packageConfig: PackageConfig,
    private val store: StateStore,
    private val defaultIntervalMs: Long = DEFAULT_INTERVAL_MS
) {

    interface StateStore {

        fun load(): String?
        fun save(json: String)
    }

    private data class State(
        var lastAcceptedTimestamp: Long,
        var intervalMs: Long,
        var pendingLongerIntervalMs: Long,
        var consecutiveLongGapCount: Int,
        /** Last value accepted for this package — see the repeat-value guard in the class note. */
        var lastAcceptedValue: Int = 0
    )

    private val states: MutableMap<String, State> = loadStates()

    /**
     * Returns true if the reading should be accepted (and persists state). Returns false for
     * a detected duplicate. Caller must only invoke this after parsing a valid glucose value.
     */
    @Synchronized
    fun process(packageName: String, now: Long, glucoseMgdl: Int = 0): Boolean {
        val state = states[packageName]
        if (state == null) {
            val seed = packageConfig.intervalForPackage(packageName, defaultIntervalMs)
            states[packageName] = State(now, seed, 0L, 0, glucoseMgdl)
            persist()
            return true
        }

        val gap = now - state.lastAcceptedTimestamp
        val threshold = state.intervalMs - state.intervalMs / REPOST_THRESHOLD_FRACTION

        if (gap < threshold) return false

        // Repeat-value guard — see the class note. An unchanged value has to clear nearly the
        // whole interval, which a re-post of the same reading cannot do.
        //
        // Zero means "value not supplied" and disables the guard rather than matching every other
        // zero: the parser's own range is 40..405, so a real reading can never be 0, and callers
        // that only care about timing (upstream's tests among them) get upstream's rule unchanged.
        if (glucoseMgdl != VALUE_UNKNOWN &&
            glucoseMgdl == state.lastAcceptedValue &&
            gap < state.intervalMs - state.intervalMs / REPEAT_VALUE_FRACTION
        ) return false

        val snapped = snapGapToKnownIntervalMs(gap)
        when {
            snapped > state.intervalMs -> {
                if (snapped == state.pendingLongerIntervalMs) {
                    state.consecutiveLongGapCount++
                    if (state.consecutiveLongGapCount >= SNAP_UP_CONSECUTIVE) {
                        state.intervalMs = snapped
                        state.pendingLongerIntervalMs = 0L
                        state.consecutiveLongGapCount = 0
                    }
                } else {
                    state.pendingLongerIntervalMs = snapped
                    state.consecutiveLongGapCount = 1
                }
            }

            else                       -> {
                state.pendingLongerIntervalMs = 0L
                state.consecutiveLongGapCount = 0
            }
        }

        state.lastAcceptedTimestamp = now
        state.lastAcceptedValue = glucoseMgdl
        persist()
        return true
    }

    /** Currently-effective interval for a package (for diagnostics/tests). */
    fun currentIntervalMs(packageName: String): Long =
        states[packageName]?.intervalMs
            ?: packageConfig.intervalForPackage(packageName, defaultIntervalMs)

    private fun persist() {
        val root = JSONArray()
        for ((pkg, s) in states) {
            root.put(
                JSONObject()
                    .put("p", pkg)
                    .put("t", s.lastAcceptedTimestamp)
                    .put("i", s.intervalMs)
                    .put("pi", s.pendingLongerIntervalMs)
                    .put("c", s.consecutiveLongGapCount)
                    .put("v", s.lastAcceptedValue)
            )
        }
        store.save(root.toString())
    }

    private fun loadStates(): MutableMap<String, State> {
        val raw = store.load() ?: return mutableMapOf()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, State>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("p")] = State(
                    lastAcceptedTimestamp = o.getLong("t"),
                    intervalMs = o.getLong("i"),
                    pendingLongerIntervalMs = o.optLong("pi", 0L),
                    consecutiveLongGapCount = o.optInt("c", 0),
                    lastAcceptedValue = o.optInt("v", 0)
                )
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    companion object {

        const val DEFAULT_INTERVAL_MS = 5 * 60_000L
        const val SNAP_UP_CONSECUTIVE = 3
        /** Upstream's bar: a reading may arrive up to interval/5 early. 4:00 of a 5-min cycle. */
        private const val REPOST_THRESHOLD_FRACTION = 5
        /** The repeat-value bar: interval/20 early at most. 4:45 of a 5-min cycle. */
        private const val REPEAT_VALUE_FRACTION = 20
        /** Sentinel for "caller supplied no value"; outside NotificationParser.GLUCOSE_RANGE. */
        private const val VALUE_UNKNOWN = 0

        /**
         * Snap a measured gap to the nearest known sensor interval using fixed thresholds.
         * Used for snap-up detection only (seed is a hard floor, so shorter bands are only
         * reached when a package is seeded with a low interval).
         */
        fun snapGapToKnownIntervalMs(gapMs: Long): Long = when {
            gapMs <= 2 * 60_000L  -> 1 * 60_000L
            gapMs <= 4 * 60_000L  -> 3 * 60_000L
            gapMs <= 10 * 60_000L -> 5 * 60_000L
            else                  -> 15 * 60_000L
        }
    }
}
