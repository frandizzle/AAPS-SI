package app.aaps.plugins.aps.smartInsulin.ice

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuously tracks Insulin Counteraction Effect (ICE) — the residual BG signal
 * after subtracting modeled insulin action.
 *
 * ## What this does
 *
 * Each loop cycle, [recordCycle] is called with the current BG, insulin activity,
 * and any disable conditions detected upstream (warmup, exercise, etc). The tracker:
 *
 * 1. Computes ICE from the delta between this cycle and the previous one.
 * 2. Stores the sample in a rolling 6h buffer.
 * 3. Computes [IceConfidence] over recent history.
 * 4. Publishes an [IceDebugSnapshot] via [snapshot] StateFlow for UI consumption.
 *
 * ## What this does NOT do (yet)
 *
 * This is Step 1 of the ICE rollout. **No dosing decisions are influenced by this
 * tracker yet.** It runs in shadow mode, computing and exposing data so we can
 * validate that ICE values look sensible before plugging them into determine_basal.
 *
 * After two weeks of shadow operation, Step 2 will introduce IcePrediction (forward
 * extrapolation) and the blending into determine_basal's prediction curve.
 *
 * ## ICE formula
 *
 *   ICE_mg/dL/h = (observed_ΔBG − modeled_ΔBG) × (60 / intervalMin)
 *
 * Where:
 *   observed_ΔBG = bgMgdl(t) − bgMgdl(t-1)
 *   modeled_ΔBG  = −avgActivity × ISF × intervalMin
 *   avgActivity  = (activity(t-1) + activity(t)) / 2  (trapezoidal average)
 *
 * Positive ICE = something raising BG beyond what insulin explains (food, dawn, stress).
 * Negative ICE = something lowering BG beyond what insulin explains (exercise sensitivity).
 *
 * ## Buffer policy
 *
 * Keeps up to [BUFFER_SIZE] samples (~6h at 5-min cycles). Older samples are evicted
 * FIFO. If a gap > [MAX_GAP_MIN] minutes occurs between cycles, the next sample's
 * delta/ICE fields are null — we don't trust integrating activity across a big gap.
 *
 * @see IceConfidence for confidence scoring
 * @see IceDebugSnapshot for what UI reads
 */
@Singleton
class IceTracker @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    companion object {
        /** Max samples retained. At 5-min cycles, 72 samples ≈ 6h. */
        private const val BUFFER_SIZE = 72

        /** Max gap between cycles before we discard the delta (minutes). */
        private const val MAX_GAP_MIN = 10.0

        /** Min gap to compute a delta (minutes). Prevents same-cycle double-record from making 0 interval. */
        private const val MIN_GAP_MIN = 1.0

        /** Samples retained for the debug snapshot's recentHistory. */
        private const val SNAPSHOT_HISTORY_SIZE = 36  // ~3h
    }

    // ── Mutable state — synchronised access only ─────────────────────────────
    private val buffer = ArrayDeque<IceSample>(BUFFER_SIZE)
    private val _snapshot = MutableStateFlow<IceDebugSnapshot?>(null)

    /** UI subscribes here for live updates. */
    val snapshot: StateFlow<IceDebugSnapshot?> = _snapshot.asStateFlow()

    /** Confidence params — replaced by recordCycle when caller provides them, otherwise defaults. */
    private var currentParams: IceConfidenceParams = IceConfidenceParams()

    /**
     * Record one loop cycle's observation.
     *
     * Thread safety: call this from the loop thread only — not safe for concurrent calls.
     * (In AAPS the loop is single-threaded, so this is fine in practice.)
     *
     * @param timestampMs   When this CGM reading came in (epoch ms).
     * @param bgMgdl        Current BG, mg/dL.
     * @param activityUperMin  Current insulin activity rate, U/min (from IobTotal.activity).
     * @param isfMgdlPerU   Current ISF, mg/dL per U. Use the same value the loop's dosing logic sees.
     * @param disableReason If ICE should be suppressed this cycle, the reason. Null = active.
     * @param params        Confidence parameters from preferences. Use defaults if not configured.
     * @return The newly-recorded sample (with ICE computed if possible).
     */
    @Synchronized
    fun recordCycle(
        timestampMs: Long,
        bgMgdl: Double,
        activityUperMin: Double,
        isfMgdlPerU: Double,
        disableReason: IceDisableReason? = null,
        params: IceConfidenceParams = IceConfidenceParams()
    ): IceSample {
        currentParams = params

        val previous = buffer.lastOrNull()

        val sample = if (previous == null) {
            // First sample — no delta yet.
            IceSample(
                timestampMs    = timestampMs,
                bgMgdl         = bgMgdl,
                activityUperMin = activityUperMin,
                isfMgdlPerU    = isfMgdlPerU,
                disabled       = disableReason
            )
        } else {
            val intervalMin = (timestampMs - previous.timestampMs) / 60_000.0
            if (intervalMin < MIN_GAP_MIN || intervalMin > MAX_GAP_MIN) {
                // Too short (duplicate?) or too long (gap) — record without delta/ICE.
                aapsLogger.debug(LTag.APS,
                                 "ICE: gap $intervalMin min outside [$MIN_GAP_MIN, $MAX_GAP_MIN] — recording without ICE")
                IceSample(
                    timestampMs    = timestampMs,
                    bgMgdl         = bgMgdl,
                    activityUperMin = activityUperMin,
                    isfMgdlPerU    = isfMgdlPerU,
                    intervalMin    = intervalMin,
                    disabled       = disableReason
                )
            } else {
                val observedDelta = bgMgdl - previous.bgMgdl
                val avgActivity   = (previous.activityUperMin + activityUperMin) / 2.0
                val modeledDelta  = -avgActivity * isfMgdlPerU * intervalMin
                val iceMgdlPerH   = (observedDelta - modeledDelta) * (60.0 / intervalMin)

                IceSample(
                    timestampMs       = timestampMs,
                    bgMgdl            = bgMgdl,
                    activityUperMin   = activityUperMin,
                    isfMgdlPerU       = isfMgdlPerU,
                    observedDeltaMgdl = observedDelta,
                    modeledDeltaMgdl  = modeledDelta,
                    iceMgdlPerHour    = iceMgdlPerH,
                    intervalMin       = intervalMin,
                    disabled          = disableReason
                )
            }
        }

        // Add to buffer, evict if over capacity.
        buffer.addLast(sample)
        while (buffer.size > BUFFER_SIZE) buffer.removeFirst()

        // Compute confidence and publish snapshot.
        val confidence = computeIceConfidence(buffer.toList(), currentParams, disableReason)
        val snapshot = buildSnapshot(sample, confidence)
        _snapshot.value = snapshot

        return sample
    }

    /** Current ICE in mg/dL/h, or null if no sample with computed ICE exists. */
    @Synchronized
    fun currentIceMgdlPerH(): Double? = buffer.lastOrNull { it.iceMgdlPerHour != null }?.iceMgdlPerHour

    /** Current ICE in mmol/h. */
    fun currentIceMmolPerH(): Double? = currentIceMgdlPerH()?.div(18.0)

    /** All samples within the requested window from now. Most recent last. */
    @Synchronized
    fun history(windowMs: Long, nowMs: Long): List<IceSample> {
        val cutoff = nowMs - windowMs
        return buffer.filter { it.timestampMs >= cutoff }
    }

    /** Snapshot of the entire current buffer — for debugging / persistence. */
    @Synchronized
    fun fullBuffer(): List<IceSample> = buffer.toList()

    /**
     * Reset the tracker — used when a new sensor session starts or the user manually
     * clears state. The buffer is cleared; the next recordCycle will start fresh.
     */
    @Synchronized
    fun reset() {
        aapsLogger.debug(LTag.APS, "ICE: tracker reset (buffer cleared)")
        buffer.clear()
        _snapshot.value = null
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun buildSnapshot(current: IceSample, confidence: IceConfidence): IceDebugSnapshot {
        val recentHistory = buffer.toList().takeLast(SNAPSHOT_HISTORY_SIZE)

        val summary = buildString {
            val ice = current.iceMgdlPerHour
            if (ice != null) {
                val mmol = ice / 18.0
                val sign = if (mmol >= 0) "+" else ""
                append("ICE $sign${"%.2f".format(mmol)} mmol/h")
            } else {
                append("ICE pending")
            }
            append(", confidence ${"%.2f".format(confidence.score)}")
            confidence.disabled?.let { append(" [${it.displayLabel}]") }
        }

        return IceDebugSnapshot(
            cycleTimeMs     = current.timestampMs,
            // ice-step31: if the current sample has no computed ICE (gap-skip
            // path at line 122), fall back to the last sample that does. Without
            // this, a manual loop landing within MIN_GAP_MIN of a CGM-driven loop
            // produces a sample with iceMgdlPerHour=null, which echoes through
            // the snapshot and makes the plugin's iceMode evaluate to NONE — so
            // the chart's UAM/COB line vanishes for one cycle even though the
            // underlying observation is unchanged. Falling back to the last
            // valid value keeps the snapshot stable across these "no new data"
            // sample additions. Same semantics as currentIceMgdlPerH() above.
            currentIceMgdlH = current.iceMgdlPerHour
                ?: buffer.lastOrNull { it.iceMgdlPerHour != null }?.iceMgdlPerHour,
            confidence      = confidence,
            recentHistory   = recentHistory,
            summaryText     = summary
        )
    }
}