package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.BooleanKey
import app.aaps.core.interfaces.sharedPreferences.SP
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-Term Fuel Trim (STFT) — temporary target reduction for stuck-high BG.
 */
@Singleton
class StftController @Inject constructor(
    private val aapsLogger:   AAPSLogger,
    private val sp:           SP,
    private val profileUtil:  ProfileUtil
) {

    // ── State ─────────────────────────────────────────────────────────────────
    private var consecutiveAbove  = 0      // readings above trigger threshold
    private var stftActive        = false  // true once trigger fired
    private var stepsApplied      = 0      // how many 5-min steps of reduction applied
    private var currentlyHighTempTarget = false  // surfaced in statusString()
    private var negDeltaStreak         = 0      // consecutive negative delta readings
    private var lastCountedTimestampMs = 0L     // CGM timestamp of last reading that incremented counters

    companion object {
        private const val MMOL_TO_MGDL          = 18.0
        private const val TRIGGER_OFFSET_MMOL   = 0.6
        private const val TRIGGER_OFFSET_MGDL   = TRIGGER_OFFSET_MMOL * MMOL_TO_MGDL
        private const val TRIGGER_READINGS      = 3
        private const val STEP_MMOL             = 0.1
        private const val STEP_MGDL             = STEP_MMOL * MMOL_TO_MGDL
        private const val FLOOR_OFFSET_MMOL     = 0.5
        private const val FLOOR_OFFSET_MGDL     = FLOOR_OFFSET_MMOL * MMOL_TO_MGDL
        private const val NEG_DELTA_RESET_COUNT = 2
    }

    private val isMmol: Boolean get() =
        profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    private fun fmtBg(mgdl: Double): String =
        if (isMmol) String.format(java.util.Locale.US, "%.1f", mgdl / MMOL_TO_MGDL) else String.format(java.util.Locale.US, "%.0f", mgdl)
    private fun fmtDelta(mgdl: Double): String =
        if (isMmol) String.format(java.util.Locale.US, "%.2f", mgdl / MMOL_TO_MGDL) else String.format(java.util.Locale.US, "%.1f", mgdl)

    fun onLoopCycle(
        profileTargetMgdl:   Double,
        currentBgMgdl:       Double,
        delta:               Double,
        shortAvgDeltaMgdl:   Double,
        mealMode:            MealMode,
        isTempTarget:        Boolean,
        bgWentLow:           Boolean,
        inReboundWindow:     Boolean,
        cgmInWarmup:         Boolean,
        bgTimestampMs:       Long = 0L
    ): Double {

        if (mealMode != MealMode.FASTING) {
            if (stftActive || consecutiveAbove > 0) reset()
            return profileTargetMgdl
        }

        currentlyHighTempTarget = isTempTarget
        if (isTempTarget) {
            if (stftActive || consecutiveAbove > 0) reset()
            return profileTargetMgdl
        }

        if (cgmInWarmup && sp.getBoolean(BooleanKey.ApsSmartInsulinStftCgmWarmupBlock.key, BooleanKey.ApsSmartInsulinStftCgmWarmupBlock.defaultValue)) {
            if (stftActive || consecutiveAbove > 0) reset()
            return profileTargetMgdl
        }

        if (bgWentLow || inReboundWindow) {
            if (stftActive || consecutiveAbove > 0) reset()
            return profileTargetMgdl
        }

        if (shortAvgDeltaMgdl < 0.0) {
            negDeltaStreak++
            if (negDeltaStreak >= NEG_DELTA_RESET_COUNT && stftActive) {
                reset()
                return profileTargetMgdl
            }
        } else {
            negDeltaStreak = 0
        }

        if (stftActive && currentBgMgdl <= profileTargetMgdl) {
            reset()
            return profileTargetMgdl
        }

        val targetFloorMgdl = profileTargetMgdl - FLOOR_OFFSET_MGDL
        if (bgTimestampMs > 0L && bgTimestampMs == lastCountedTimestampMs) {
            return if (stftActive) {
                val reduction = stepsApplied * STEP_MGDL
                (profileTargetMgdl - reduction).coerceAtLeast(targetFloorMgdl)
            } else profileTargetMgdl
        }

        val triggerThresholdMgdl = profileTargetMgdl + TRIGGER_OFFSET_MGDL
        if (currentBgMgdl > triggerThresholdMgdl) {
            lastCountedTimestampMs = bgTimestampMs
            consecutiveAbove++
        } else {
            consecutiveAbove = 0
            if (!stftActive) return profileTargetMgdl
            lastCountedTimestampMs = bgTimestampMs
        }

        if (!stftActive && consecutiveAbove >= TRIGGER_READINGS) {
            stftActive = true
        }

        if (!stftActive) return profileTargetMgdl

        stepsApplied++
        val reduction      = stepsApplied * STEP_MGDL
        return (profileTargetMgdl - reduction).coerceAtLeast(targetFloorMgdl)
    }

    fun statusString(profileTargetMgdl: Double): String? {
        val targetFloorMgdl = profileTargetMgdl - FLOOR_OFFSET_MGDL
        if (stftActive) {
            val theoreticalTarget = profileTargetMgdl - (stepsApplied * STEP_MGDL)
            val actualTarget      = theoreticalTarget.coerceAtLeast(targetFloorMgdl)
            val actualReductionMgdl = profileTargetMgdl - actualTarget
            val reductionStr = if (isMmol)
                "${"%.1f".format(java.util.Locale.US, actualReductionMgdl / MMOL_TO_MGDL)}mmol"
            else
                "${"%.0f".format(java.util.Locale.US, actualReductionMgdl)}mg/dL"
            return "STFT: -$reductionStr target (${stepsApplied * 5}min above target)"
        }
        if (currentlyHighTempTarget) return "STFT: inactive (high temp target set)"
        if (consecutiveAbove > 0) {
            val triggerThresholdMgdl = profileTargetMgdl + TRIGGER_OFFSET_MGDL
            return "STFT: watching ($consecutiveAbove/$TRIGGER_READINGS readings above ${fmtBg(triggerThresholdMgdl)}$unitLabel)"
        }
        return null
    }

    val isActive: Boolean get() = stftActive

    private fun reset() {
        consecutiveAbove       = 0
        stftActive             = false
        stepsApplied           = 0
        negDeltaStreak         = 0
        lastCountedTimestampMs = 0L
    }
}
