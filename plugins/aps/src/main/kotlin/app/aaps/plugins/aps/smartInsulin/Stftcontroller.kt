package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-Term Fuel Trim (STFT) — temporary target reduction for stuck-high BG.
 *
 * Analogous to an ECU's STFT: reacts to immediate BG feedback without waiting
 * for the long-term aggressiveness learner to adapt. Does NOT affect any learners —
 * it only adjusts the target passed to determine_basal(), letting the existing
 * insulinReq/SMB/TBR machinery do the work naturally.
 *
 * ## Behaviour
 * - Only active in FASTING mode — meal highs are expected and handled by ISF tightening
 * - Trigger: BG > [TRIGGER_THRESHOLD_MMOL] for [TRIGGER_READINGS] consecutive readings
 * - After trigger: target drops by [STEP_MMOL] every loop cycle (5 min), floor at [TARGET_FLOOR_MMOL]
 * - Reset: two consecutive negative deltas → immediate reset to profile target
 * - Reset: BG drops back to profile target → immediate reset
 * - Reset: meal mode becomes active → immediate reset
 *
 * ## Why target, not aggressiveness?
 * Lowering the target widens the predMinGap the loop sees, naturally increasing
 * insulinReq and SMB/TBR output — without touching ISF, basal, or aggressiveness.
 * All existing safety guards (maxIOB, maxSMB, guards) still apply unchanged.
 */
@Singleton
class StftController @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    // ── State ─────────────────────────────────────────────────────────────────
    private var consecutiveAbove  = 0      // readings above trigger threshold
    private var stftActive        = false  // true once trigger fired
    private var stepsApplied      = 0      // how many 5-min steps of reduction applied
    private var negDeltaStreak    = 0      // consecutive negative delta readings

    companion object {
        private const val MMOL_TO_MGDL          = 18.0

        // Trigger: BG must be above this threshold to start counting
        const val TRIGGER_THRESHOLD_MMOL        = 6.0
        private const val TRIGGER_THRESHOLD_MGDL = TRIGGER_THRESHOLD_MMOL * MMOL_TO_MGDL

        // Number of consecutive readings above threshold before STFT activates (3 = 15 min)
        private const val TRIGGER_READINGS      = 3

        // Target reduction per loop cycle once active (0.1 mmol = 1.8 mg/dL per 5 min)
        private const val STEP_MMOL             = 0.1
        private const val STEP_MGDL             = STEP_MMOL * MMOL_TO_MGDL

        // Floor — never push target below this
        const val TARGET_FLOOR_MMOL             = 5.0
        private const val TARGET_FLOOR_MGDL     = TARGET_FLOOR_MMOL * MMOL_TO_MGDL

        // Consecutive negative deltas required to reset
        private const val NEG_DELTA_RESET_COUNT = 2
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once per loop cycle. Returns the adjusted target in mg/dL.
     *
     * @param profileTargetMgdl  The profile/temp target — STFT adjusts relative to this
     * @param currentBgMgdl      Current BG reading
     * @param delta              Current 5-min delta (mg/dL)
     * @param mealMode           Current meal mode — STFT only active in FASTING
     */
    fun onLoopCycle(
        profileTargetMgdl: Double,
        currentBgMgdl:     Double,
        delta:             Double,
        mealMode:          MealMode,
        isTempTarget:      Boolean,
        bgWentLow:         Boolean,
        inReboundWindow:   Boolean
    ): Double {

        // STFT only runs in fasting — reset immediately if meal mode activates
        if (mealMode != MealMode.FASTING) {
            if (stftActive || consecutiveAbove > 0) {
                aapsLogger.debug(LTag.APS, "STFT: reset — meal mode active (${mealMode.label})")
                reset()
            }
            return profileTargetMgdl
        }

        // Temp target active — reset and suspend. The user has deliberately set a target;
        // don't manipulate it. Reset fully so streak doesn't survive TT expiry.
        if (isTempTarget) {
            if (stftActive || consecutiveAbove > 0) {
                aapsLogger.debug(LTag.APS, "STFT: reset — temp target active")
                reset()
            }
            return profileTargetMgdl
        }

        // Low/rebound protection — reset and block during and after a low.
        // Prevents STFT from re-activating immediately during rebound recovery.
        if (bgWentLow || inReboundWindow) {
            if (stftActive || consecutiveAbove > 0) {
                val reason = if (inReboundWindow) "rebound window active" else "recent low"
                aapsLogger.debug(LTag.APS, "STFT: reset — $reason")
                reset()
            }
            return profileTargetMgdl
        }

        // Track negative delta streak for reset detection
        if (delta < 0.0) {
            negDeltaStreak++
            if (negDeltaStreak >= NEG_DELTA_RESET_COUNT && stftActive) {
                aapsLogger.debug(LTag.APS,
                                 "STFT: reset — $NEG_DELTA_RESET_COUNT consecutive negative deltas (delta=%.1f)".format(delta))
                reset()
                return profileTargetMgdl
            }
        } else {
            negDeltaStreak = 0
        }

        // Reset if BG has returned to profile target
        if (stftActive && currentBgMgdl <= profileTargetMgdl) {
            aapsLogger.debug(LTag.APS,
                             "STFT: reset — BG ${currentBgMgdl / MMOL_TO_MGDL} back at/below target ${profileTargetMgdl / MMOL_TO_MGDL}")
            reset()
            return profileTargetMgdl
        }

        // Count consecutive readings above trigger threshold
        if (currentBgMgdl > TRIGGER_THRESHOLD_MGDL) {
            consecutiveAbove++
        } else {
            consecutiveAbove = 0
            if (!stftActive) return profileTargetMgdl
        }

        // Activate once trigger threshold is met
        if (!stftActive && consecutiveAbove >= TRIGGER_READINGS) {
            stftActive = true
            aapsLogger.debug(LTag.APS,
                             "STFT: activated — BG above ${TRIGGER_THRESHOLD_MMOL}mmol for $TRIGGER_READINGS readings")
        }

        if (!stftActive) return profileTargetMgdl

        // Apply one step per loop cycle
        stepsApplied++
        val reduction    = (stepsApplied * STEP_MGDL)
        val adjustedTarget = (profileTargetMgdl - reduction).coerceAtLeast(TARGET_FLOOR_MGDL)

        aapsLogger.debug(LTag.APS,
                         "STFT: active steps=$stepsApplied target %.1f→%.1f mmol (floor=$TARGET_FLOOR_MMOL)".format(
                             profileTargetMgdl / MMOL_TO_MGDL, adjustedTarget / MMOL_TO_MGDL))

        return adjustedTarget
    }

    /** Current STFT status for display — null if inactive and not watching */
    fun statusString(): String? {
        if (stftActive) {
            val reductionMmol = stepsApplied * STEP_MMOL
            return "STFT: -%.1fmmol target (${stepsApplied * 5}min above target)".format(reductionMmol)
        }
        if (consecutiveAbove > 0) {
            return "STFT: watching ($consecutiveAbove/$TRIGGER_READINGS readings above ${TRIGGER_THRESHOLD_MMOL}mmol)"
        }
        return null
    }

    /** True if STFT is currently adjusting the target */
    val isActive: Boolean get() = stftActive

    // ── Private ───────────────────────────────────────────────────────────────

    private fun reset() {
        consecutiveAbove = 0
        stftActive       = false
        stepsApplied     = 0
        negDeltaStreak   = 0
    }
}