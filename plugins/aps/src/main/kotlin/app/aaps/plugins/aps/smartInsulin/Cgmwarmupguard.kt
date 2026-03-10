package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * CgmWarmupGuard — cautious dosing protection for new/noisy CGM sensors.
 *
 * PROBLEM (as seen in practice):
 *   Day-1 sensors produce phantom spikes — consecutive deltas of +0.7, +0.8, +0.9 mmol/L.
 *   The loop fires SMBs into a BG that was never actually rising. Crash follows.
 *   TBRs are safe — reversible and self-correcting.
 *
 * STRATEGY (when enabled via preference):
 *
 *   Instead of hard-blocking SMBs, we skip readings using a cycle counter:
 *     0– 6h: allow SMB every 3rd reading  (2 skipped between each SMB)
 *     6–12h: allow SMB every 2nd reading  (1 skipped)
 *    12–24h: allow SMB every reading      (no skip — normal but learning still off)
 *    24h+:   fully normal
 *
 *   Rationale: a real sustained rise will still produce SMBs (just spaced out), while
 *   a phantom 1–2 reading spike gets skipped and can't cause a crash.
 *
 *   Hard overrides (always apply when guard is ON):
 *     - Implausible delta (>3 mmol/5min): block SMBs this cycle regardless of age
 *     - Erratic sensor (|shortAvg - longAvg| > 2.5 mmol): skip every 2nd reading
 *     - High noise (xDrip/Dexcom noise flag ≥ 3): skip every 2nd reading
 *
 *   Learning suppression:
 *     - ALWAYS suppressed for 0–24h regardless of whether the guard switch is on.
 *       Day-1 data is too unreliable to train from.
 *
 * SENSOR AGE DETECTION:
 *   Stateful gap detection — when BG reading timestamps show a gap > 35 min,
 *   a new sensor is assumed. sensorInsertTimeMs from TherapyEvent overrides if available.
 *
 * SMB FRACTION:
 *   WarmupState.smbFraction is now either 0.0 (skip this cycle) or 1.0 (allow this cycle).
 *   The skip pattern is determined by an internal cycle counter incremented each evaluate() call.
 */
@Singleton
class CgmWarmupGuard @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    data class WarmupState(
        val inWarmup:         Boolean,
        val sensorAgeHours:   Double,
        val smbFraction:      Double,    // 0.0 = skip this cycle, 1.0 = allow
        val suppressLearning: Boolean,
        val deltaPlausible:   Boolean,
        val reason:           String
    ) {
        val allowSmb: Boolean get() = smbFraction > 0.0 && deltaPlausible
    }

    companion object {
        const val WARMUP_HOURS          = 24.0   // learning suppressed for full 24h

        // Skip-N pattern boundaries
        const val SKIP3_END_HOURS       = 6.0    // 0–6h: allow every 3rd reading
        const val SKIP2_END_HOURS       = 12.0   // 6–12h: allow every 2nd reading
        // 12–24h: allow every reading (no skip, but learning still off)

        const val MAX_PLAUSIBLE_DELTA_MMOL   = 3.0   // mmol/L per 5 min
        const val MAX_DELTA_DIVERGENCE_MMOL  = 2.5   // mmol/L short vs long avg
        const val NEW_SENSOR_GAP_MS          = 35 * 60 * 1000L

        val SAFE_STATE = WarmupState(
            inWarmup         = false,
            sensorAgeHours   = 999.0,
            smbFraction      = 1.0,
            suppressLearning = false,
            deltaPlausible   = true,
            reason           = ""
        )
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var detectedSensorStartMs: Long = 0L
    private var lastBgTimestampMs:     Long = 0L
    private var cycleCounter:          Int  = 0   // counts evaluate() calls since sensor start

    /**
     * Evaluate CGM state for this loop cycle.
     *
     * @param enabled             From preference ApsSmartInsulinCgmWarmupEnabled
     * @param sensorInsertTimeMs  From TherapyEvent SENSOR_CHANGE if available; 0 = gap detection
     * @param nowMs               dateUtil.now()
     * @param latestBgTimestampMs glucoseStatus.date
     * @param deltaMmol           glucoseStatus.delta / 18.0
     * @param shortAvgDeltaMmol   glucoseStatus.shortAvgDelta / 18.0
     * @param longAvgDeltaMmol    glucoseStatus.longAvgDelta / 18.0
     * @param noiseLevelRaw       glucoseStatus.noise (0 = clean, ≥3 = high noise)
     */
    fun evaluate(
        enabled:              Boolean,
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
                cycleCounter = 0
                aapsLogger.debug(LTag.APS,
                                 "CgmWarmupGuard: new sensor detected via gap (${gap / 60_000}min) start=$detectedSensorStartMs")
            }
        }
        if (latestBgTimestampMs > 0L) lastBgTimestampMs = latestBgTimestampMs

        // ── Resolve sensor start ───────────────────────────────────────────────
        val effectiveSensorStartMs = when {
            sensorInsertTimeMs > 0L    -> sensorInsertTimeMs
            detectedSensorStartMs > 0L -> detectedSensorStartMs
            else                       -> 0L
        }

        val sensorAgeHours = if (effectiveSensorStartMs > 0L)
            (nowMs - effectiveSensorStartMs) / 3_600_000.0
        else
            999.0

        // ── Learning: always suppress for first 24h, guard on or off ──────────
        val suppressLearning = sensorAgeHours < WARMUP_HOURS

        // ── If guard is disabled, just return learning flag, no SMB gating ────
        if (!enabled) {
            return if (suppressLearning)
                SAFE_STATE.copy(
                    inWarmup         = true,
                    sensorAgeHours   = sensorAgeHours,
                    suppressLearning = true,
                    reason           = "cgmWarmup(age=${"%.1f".format(sensorAgeHours)}h learningOff guardDisabled)"
                )
            else
                SAFE_STATE
        }

        // ── Guard is ON from here ──────────────────────────────────────────────

        // Delta plausibility — hard block (all ages)
        val deltaPlausible = abs(deltaMmol) <= MAX_PLAUSIBLE_DELTA_MMOL
        if (!deltaPlausible) {
            aapsLogger.debug(LTag.APS,
                             "CgmWarmupGuard: implausible delta ${"%+.1f".format(deltaMmol)} mmol/L — SMBs blocked")
            return SAFE_STATE.copy(
                deltaPlausible = false,
                suppressLearning = suppressLearning,
                reason = "cgmJump(delta=${"%.1f".format(deltaMmol)}mmol SMBsBlocked TBRok)"
            )
        }

        // Fully outside warmup window — normal
        if (sensorAgeHours >= WARMUP_HOURS) return SAFE_STATE

        // ── Within 24h warmup window — skip-N pattern ─────────────────────────
        cycleCounter++

        val deltaDivergence = abs(shortAvgDeltaMmol - longAvgDeltaMmol)
        val sensorErratic   = deltaDivergence > MAX_DELTA_DIVERGENCE_MMOL
        val highNoise       = noiseLevelRaw >= 3.0

        // Determine how many readings to skip between each allowed SMB
        // Erratic/noisy overrides to skip-2 regardless of age
        val skipN: Int = when {
            sensorErratic || highNoise                -> 2  // allow every 3rd (same as 0–6h)
            sensorAgeHours < SKIP3_END_HOURS          -> 2  // 0–6h: allow every 3rd
            sensorAgeHours < SKIP2_END_HOURS          -> 1  // 6–12h: allow every 2nd
            else                                      -> 0  // 12–24h: allow every reading
        }

        // Allow SMB on cycle 0, skip the next skipN cycles, then allow again
        val allowThisCycle = (cycleCounter % (skipN + 1)) == 0
        val smbFraction    = if (allowThisCycle) 1.0 else 0.0

        val skipDesc = when (skipN) {
            2    -> "every3rd"
            1    -> "every2nd"
            else -> "allowed"
        }
        val noiseDesc = buildString {
            if (sensorErratic) append(" erratic(div=${"%.1f".format(deltaDivergence)}mmol)")
            if (highNoise)     append(" noise=${"%.0f".format(noiseLevelRaw)}")
        }
        val cycleDesc = if (skipN > 0) "(cyc=$cycleCounter→${if (allowThisCycle) "SMB" else "skip"})" else ""

        return WarmupState(
            inWarmup         = true,
            sensorAgeHours   = sensorAgeHours,
            smbFraction      = smbFraction,
            suppressLearning = true,
            deltaPlausible   = true,
            reason           = "cgmWarmup(age=${"%.1f".format(sensorAgeHours)}h $skipDesc$cycleDesc$noiseDesc learningOff)"
        ).also {
            aapsLogger.debug(LTag.APS, "CgmWarmupGuard: ${it.reason}")
        }
    }
}