package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlin.math.abs

/**
 * CgmWarmupGuard — conservative dosing protection for new/noisy CGM sensors.
 *
 * PROBLEM (as seen in practice):
 *   A new sensor on day 1 (e.g. 20h in) produces phantom spikes — consecutive
 *   deltas of +0.7, +0.8, +0.9 mmol/L — that look like a rapid BG rise.
 *   The loop fires SMBs. BG was never actually rising. Crash follows.
 *   TBRs would have been harmless here — they're reversible and self-correcting.
 *
 * SENSOR AGE DETECTION (no TherapyEvent DB query needed):
 *   The sensor insert time is stored as a SENSOR_CHANGE therapy event in AAPS, but
 *   PersistenceLayer doesn't expose a clean interface for it in all builds.
 *   Instead, we use a stateful approach:
 *     1. Track consecutive BG reading timestamps
 *     2. When we see a gap > [NEW_SENSOR_GAP_MS] in readings, record "new sensor" time
 *     3. Age is measured from that detected gap
 *   This works because Dexcom/Libre always produce a multi-minute gap when a new sensor
 *   starts (warmup period). If the sensorInsertTimeMs is provided (from TherapyEvent
 *   query), it overrides the gap detection — use whichever is available.
 *
 * DELTA PLAUSIBILITY:
 *   Applies at all sensor ages. A delta > [MAX_PLAUSIBLE_DELTA_MMOL] per 5 min is
 *   physiologically impossible for non-glucose-infusion scenarios.
 *   Blocks SMBs that cycle only. TBRs still go through.
 *
 * DELTA DIVERGENCE DETECTION:
 *   During sensor warmup, shortAvgDelta and longAvgDelta diverge wildly.
 *   If |shortAvgDelta - longAvgDelta| > [MAX_DELTA_DIVERGENCE_MMOL], the sensor
 *   is behaving erratically — treat as warmup regardless of age.
 *
 * STRATEGY:
 *   0–12h:   SMBs blocked entirely. TBRs pass through.
 *   12–24h:  SMBs at 50%. TBRs pass through.
 *   24h+:    Normal unless delta is implausible or divergence is high.
 *   Noise ≥3: Full block regardless of age (xDrip/Dexcom noise flag).
 *   Learning suppressed during entire 24h warmup window.
 */
@Singleton
class CgmWarmupGuard @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    data class WarmupState(
        val inWarmup:         Boolean,
        val sensorAgeHours:   Double,
        val smbFraction:      Double,    // 0.0 = block, 0.5 = half, 1.0 = full
        val suppressLearning: Boolean,
        val deltaPlausible:   Boolean,   // false = implausible delta, SMBs blocked this cycle
        val reason:           String
    ) {
        /** True if SMBs should be delivered (warmup AND plausibility both OK). */
        val allowSmb: Boolean get() = smbFraction > 0.0 && deltaPlausible
    }

    companion object {
        const val WARMUP_HOURS          = 12.0
        const val PARTIAL_WARMUP_HOURS  = 24.0
        const val SMB_FRACTION_FULL_WARMUP    = 0.0
        const val SMB_FRACTION_PARTIAL_WARMUP = 0.5

        // A delta > this is physically impossible (not a glucose infusion scenario)
        // 3.0 mmol/L / 5 min = 54 mg/dL / 5 min
        const val MAX_PLAUSIBLE_DELTA_MMOL = 3.0

        // shortAvgDelta vs longAvgDelta divergence threshold — sensor erratic if above this
        const val MAX_DELTA_DIVERGENCE_MMOL = 2.5  // mmol/L

        // Gap in BG readings that indicates a new sensor was inserted
        const val NEW_SENSOR_GAP_MS = 35 * 60 * 1000L  // 35 minutes

        val SAFE_STATE = WarmupState(
            inWarmup         = false,
            sensorAgeHours   = 999.0,
            smbFraction      = 1.0,
            suppressLearning = false,
            deltaPlausible   = true,
            reason           = ""
        )
    }

    // ── Internal state for gap-based sensor age detection ────────────────────
    private var detectedSensorStartMs: Long = 0L
    private var lastBgTimestampMs:     Long = 0L

    /**
     * Evaluate CGM state for this loop cycle.
     *
     * @param sensorInsertTimeMs  From TherapyEvent SENSOR_CHANGE if available; 0 = use gap detection
     * @param nowMs               dateUtil.now()
     * @param latestBgTimestampMs glucoseStatus.date — timestamp of the most recent BG reading
     * @param deltaMmol           glucoseStatus.delta / 18.0
     * @param shortAvgDeltaMmol   glucoseStatus.shortAvgDelta / 18.0
     * @param longAvgDeltaMmol    glucoseStatus.longAvgDelta / 18.0
     * @param noiseLevelRaw       glucoseStatus.noise (0=clean, ≥3=high noise)
     */
    fun evaluate(
        sensorInsertTimeMs:   Long,
        nowMs:                Long,
        latestBgTimestampMs:  Long,
        deltaMmol:            Double,
        shortAvgDeltaMmol:    Double,
        longAvgDeltaMmol:     Double,
        noiseLevelRaw:        Double
    ): WarmupState {

        // ── Gap-based sensor start detection ──────────────────────────────────
        if (latestBgTimestampMs > 0L && lastBgTimestampMs > 0L) {
            val gap = latestBgTimestampMs - lastBgTimestampMs
            if (gap > NEW_SENSOR_GAP_MS && detectedSensorStartMs < lastBgTimestampMs) {
                detectedSensorStartMs = latestBgTimestampMs
                aapsLogger.debug(LTag.APS,
                                 "CgmWarmupGuard: new sensor detected via gap (gap=${gap / 60_000}min) start=$detectedSensorStartMs")
            }
        }
        if (latestBgTimestampMs > 0L) lastBgTimestampMs = latestBgTimestampMs

        // ── Resolve sensor start time: explicit TherapyEvent wins, gap detection fallback ──
        val effectiveSensorStartMs = when {
            sensorInsertTimeMs > 0L -> sensorInsertTimeMs
            detectedSensorStartMs > 0L -> detectedSensorStartMs
            else -> 0L  // unknown — no protection applied
        }

        val sensorAgeHours = if (effectiveSensorStartMs > 0L)
            (nowMs - effectiveSensorStartMs) / 3_600_000.0
        else
            999.0  // unknown age, assume safe

        // ── Delta plausibility (all ages) ─────────────────────────────────────
        val deltaPlausible = abs(deltaMmol) <= MAX_PLAUSIBLE_DELTA_MMOL
        if (!deltaPlausible) {
            aapsLogger.debug(LTag.APS,
                             "CgmWarmupGuard: implausible delta ${"%+.1f".format(deltaMmol)} mmol/L — SMBs blocked")
        }

        // ── Delta divergence (short vs long avg) — sensor erratic flag ────────
        val deltaDivergence = abs(shortAvgDeltaMmol - longAvgDeltaMmol)
        val sensorErratic   = deltaDivergence > MAX_DELTA_DIVERGENCE_MMOL

        // ── High noise (xDrip/Dexcom noise level) ─────────────────────────────
        val highNoise = noiseLevelRaw >= 3.0

        // ── Age-based warmup ──────────────────────────────────────────────────
        val inFullWarmup    = sensorAgeHours < WARMUP_HOURS || highNoise || sensorErratic
        val inPartialWarmup = !inFullWarmup && sensorAgeHours < PARTIAL_WARMUP_HOURS

        return when {
            inFullWarmup -> {
                val why = buildString {
                    append("age=${"%.1f".format(sensorAgeHours)}h")
                    if (highNoise)     append(" noise=${"%.0f".format(noiseLevelRaw)}")
                    if (sensorErratic) append(" erratic(div=${"%.1f".format(deltaDivergence)}mmol)")
                }
                WarmupState(
                    inWarmup         = true,
                    sensorAgeHours   = sensorAgeHours,
                    smbFraction      = SMB_FRACTION_FULL_WARMUP,
                    suppressLearning = true,
                    deltaPlausible   = deltaPlausible,
                    reason           = "cgmWarmup($why SMBsBlocked TBRok)"
                ).also {
                    aapsLogger.debug(LTag.APS, "CgmWarmupGuard: full warmup — ${it.reason}")
                }
            }
            inPartialWarmup -> WarmupState(
                inWarmup         = true,
                sensorAgeHours   = sensorAgeHours,
                smbFraction      = SMB_FRACTION_PARTIAL_WARMUP,
                suppressLearning = true,
                deltaPlausible   = deltaPlausible,
                reason           = "cgmWarmup(age=${"%.1f".format(sensorAgeHours)}h SMBs×50% TBRok)"
            )
            !deltaPlausible -> SAFE_STATE.copy(
                deltaPlausible = false,
                reason = "cgmJump(delta=${"%.1f".format(deltaMmol)}mmol SMBsBlocked TBRok)"
            )
            else -> SAFE_STATE
        }
    }
}