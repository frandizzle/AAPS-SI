package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * CgmWarmupGuard — cautious dosing protection for new/noisy CGM sensors.
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
        const val SKIP3_END_HOURS       = 12.0    // 0–12h: allow every 3rd reading
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

        val effectiveSensorStartMs = when {
            sensorInsertTimeMs > 0L    -> sensorInsertTimeMs
            detectedSensorStartMs > 0L -> detectedSensorStartMs
            else                       -> 0L
        }

        val sensorAgeHours = if (effectiveSensorStartMs > 0L)
            (nowMs - effectiveSensorStartMs) / 3_600_000.0
        else
            999.0

        val suppressLearning = sensorAgeHours < WARMUP_HOURS

        // Implausible-delta block runs BEFORE the enabled check and at every sensor age. This is
        // an artifact guard, not a warmup feature: a >MAX_PLAUSIBLE_DELTA_MMOL jump between
        // readings is not physiology at any sensor age, and turning the warmup guard off should
        // not also remove the only protection against dosing on a sensor glitch.
        val deltaPlausible = abs(deltaMmol) <= MAX_PLAUSIBLE_DELTA_MMOL
        if (!deltaPlausible) {
            aapsLogger.debug(LTag.APS,
                             "CgmWarmupGuard: implausible delta ${"%+.1f".format(deltaMmol)} mmol/L — SMBs blocked")
            return SAFE_STATE.copy(
                deltaPlausible   = false,
                inWarmup         = suppressLearning,
                sensorAgeHours   = sensorAgeHours,
                suppressLearning = suppressLearning,
                reason           = "cgmJump(delta=${"%.1f".format(deltaMmol)}mmol SMBsBlocked TBRok)"
            )
        }

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

        if (sensorAgeHours >= WARMUP_HOURS) return SAFE_STATE

        cycleCounter++

        val deltaDivergence = abs(shortAvgDeltaMmol - longAvgDeltaMmol)
        val sensorErratic   = deltaDivergence > MAX_DELTA_DIVERGENCE_MMOL
        val highNoise       = noiseLevelRaw >= 3.0

        val skipN: Int = when {
            sensorErratic || highNoise -> 2          // safety override → every 3rd
            sensorAgeHours < 12.0 -> 2               // 0–12h → every 3rd
            sensorAgeHours < 24.0 -> 1               // 12–24h → every 2nd
            else -> 0                                // 24h+ → every reading
        }

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
