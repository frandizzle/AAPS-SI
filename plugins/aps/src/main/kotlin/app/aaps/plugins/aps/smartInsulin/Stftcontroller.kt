package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
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
    private val aapsLogger:   AAPSLogger,
    private val preferences:  Preferences,
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

    // ── Unit-aware display helpers ────────────────────────────────────────────
    private val isMmol: Boolean get() =
        profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    private fun fmtBg(mgdl: Double): String =
        if (isMmol) String.format(java.util.Locale.US, "%.1f", mgdl / MMOL_TO_MGDL) else String.format(java.util.Locale.US, "%.0f", mgdl)
    private fun fmtDelta(mgdl: Double): String =
        if (isMmol) String.format(java.util.Locale.US, "%.2f", mgdl / MMOL_TO_MGDL) else String.format(java.util.Locale.US, "%.1f", mgdl)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once per loop cycle. Returns the adjusted target in mg/dL.
     *
     * @param profileTargetMgdl   The profile/temp target — STFT adjusts relative to this
     * @param currentBgMgdl       Current BG reading
     * @param delta               Current 5-min delta (mg/dL) — retained for future use
     * @param shortAvgDeltaMgdl   15-min smoothed delta (mg/dL) — used for reset detection.
     *                            Immune to single-tick CGM noise (e.g. 145→144→145→144 won't
     *                            falsely kill STFT — shortAvg stays ~0 while raw delta wobbles).
     * @param mealMode            Current meal mode — STFT only active in FASTING
     */
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
        currentlyHighTempTarget = isTempTarget
        if (isTempTarget) {
            if (stftActive || consecutiveAbove > 0) {
                aapsLogger.debug(LTag.APS, "STFT: reset — temp target active")
                reset()
            }
            return profileTargetMgdl
        }

        // CGM warmup — deltas are unreliable, don't run STFT
        if (cgmInWarmup && preferences.get(BooleanKey.ApsSmartInsulinStftCgmWarmupBlock)) {
            if (stftActive || consecutiveAbove > 0) {
                aapsLogger.debug(LTag.APS, "STFT: reset — CGM in warmup")
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

        // Track negative-shortAvgDelta streak for reset detection.
        // Using shortAvgDelta (15-min smoothed) instead of raw 5-min delta prevents
        // single-tick CGM noise from killing an active STFT: if BG hovers at 145 and
        // wobbles 145→144→145, raw delta would give two negative ticks and reset STFT
        // even though BG hasn't meaningfully recovered. shortAvgDelta stays near zero.
        if (shortAvgDeltaMgdl < 0.0) {
            negDeltaStreak++
            if (negDeltaStreak >= NEG_DELTA_RESET_COUNT && stftActive) {
                aapsLogger.debug(LTag.APS,
                                 "STFT: reset — $NEG_DELTA_RESET_COUNT consecutive negative short-avg deltas (shortAvgΔ=${fmtDelta(shortAvgDeltaMgdl)}$unitLabel)")
                reset()
                return profileTargetMgdl
            }
        } else {
            negDeltaStreak = 0
        }

        // Reset if BG has returned to profile target
        if (stftActive && currentBgMgdl <= profileTargetMgdl) {
            aapsLogger.debug(LTag.APS,
                             "STFT: reset — BG ${fmtBg(currentBgMgdl)}$unitLabel back at/below target ${fmtBg(profileTargetMgdl)}$unitLabel")
            reset()
            return profileTargetMgdl
        }

        // Single duplicate-timestamp guard — skip all counter/step mutations on a
        // repeated CGM reading. Return the appropriate target (reduced if active,
        // profile if not) without advancing any state.
        if (bgTimestampMs > 0L && bgTimestampMs == lastCountedTimestampMs) {
            return if (stftActive) {
                val reduction = stepsApplied * STEP_MGDL
                (profileTargetMgdl - reduction).coerceAtLeast(TARGET_FLOOR_MGDL)
            } else profileTargetMgdl
        }

        // Count consecutive readings above trigger threshold.
        // Activation is driven purely by absolute BG — not delta. Rationale: BG
        // drifting DOWN but still stuck above target is the exact scenario STFT
        // should handle (e.g. 6.8 → 6.7 → 6.6 at fasting — all above trigger, but
        // a delta-gated check would miss this because shortAvgDelta is negative).
        // The reset logic (shortAvgDelta < 0 × 2 while STFT active) handles
        // turning STFT off when a genuine recovery establishes.
        if (currentBgMgdl > TRIGGER_THRESHOLD_MGDL) {
            lastCountedTimestampMs = bgTimestampMs
            consecutiveAbove++
        } else {
            consecutiveAbove = 0
            if (!stftActive) return profileTargetMgdl
            lastCountedTimestampMs = bgTimestampMs
        }

        // Activate once trigger threshold is met
        if (!stftActive && consecutiveAbove >= TRIGGER_READINGS) {
            stftActive = true
            aapsLogger.debug(LTag.APS,
                             "STFT: activated — BG above ${fmtBg(TRIGGER_THRESHOLD_MGDL)}$unitLabel for $TRIGGER_READINGS readings")
        }

        if (!stftActive) return profileTargetMgdl

        // Apply one step per loop cycle (dup-timestamp already guarded above)
        stepsApplied++
        val reduction      = stepsApplied * STEP_MGDL
        val adjustedTarget = (profileTargetMgdl - reduction).coerceAtLeast(TARGET_FLOOR_MGDL)

        aapsLogger.debug(LTag.APS,
                         "STFT: active steps=$stepsApplied target ${fmtBg(profileTargetMgdl)}→${fmtBg(adjustedTarget)}$unitLabel (floor=${fmtBg(TARGET_FLOOR_MGDL)}$unitLabel)")

        return adjustedTarget
    }

    /** Current STFT status for display — null if inactive and not watching */
    fun statusString(profileTargetMgdl: Double = TARGET_FLOOR_MGDL + STEP_MGDL): String? {
        if (stftActive) {
            val theoreticalTarget = profileTargetMgdl - (stepsApplied * STEP_MGDL)
            val actualTarget      = theoreticalTarget.coerceAtLeast(TARGET_FLOOR_MGDL)
            val actualReductionMgdl = profileTargetMgdl - actualTarget
            val reductionStr = if (isMmol)
                "${"%.1f".format(java.util.Locale.US, actualReductionMgdl / MMOL_TO_MGDL)}mmol"
            else
                "${"%.0f".format(java.util.Locale.US, actualReductionMgdl)}mg/dL"
            return "STFT: -$reductionStr target (${stepsApplied * 5}min above target)"
        }
        if (currentlyHighTempTarget) return "STFT: inactive (high temp target set)"
        if (consecutiveAbove > 0) {
            return "STFT: watching ($consecutiveAbove/$TRIGGER_READINGS readings above ${fmtBg(TRIGGER_THRESHOLD_MGDL)}$unitLabel)"
        }
        return null
    }

    /** True if STFT is currently adjusting the target */
    val isActive: Boolean get() = stftActive

    // ── Private ───────────────────────────────────────────────────────────────

    private fun reset() {
        consecutiveAbove       = 0
        stftActive             = false
        stepsApplied           = 0
        negDeltaStreak         = 0
        lastCountedTimestampMs = 0L
    }
}