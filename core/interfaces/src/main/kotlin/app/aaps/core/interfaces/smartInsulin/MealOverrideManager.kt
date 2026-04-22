package app.aaps.core.interfaces.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal

/**
 * Interface for the meal override system.
 * Lives in core/interfaces so both plugins/aps and plugins/automation can inject it.
 * Implementation: MealOverrideManagerImpl in plugins/aps
 */
interface MealOverrideManager {

    /** Active meal mode — returns override if not expired, else null */
    val activeMealMode: MealMode?

    /** Pre-bolus 1 dose delivered when this meal mode was activated — null if none or not via SmartMeal */
    val activeDoseU: Double?
    /** Non-null and > 0 when PB2 has been delivered this session — shows the delivered amount */
    val activePb2DoseU: Double?
    /** Non-null and > 0 when PB3 has been delivered this session — shows the delivered amount */
    val activePb3DoseU: Double?

    /** ISF multiplier for current loop cycle — 1.0 if no override active */
    val activeIsfMultiplier: Double

    /** Milliseconds remaining in the active mode window, or 0 if no override active */
    val modeTimeRemainingMs: Long

    /** Timestamp (ms) when the current override was activated, or 0 if no override active.
     *  Used by auto-cancel logic to protect the early-meal window from premature cancellation. */
    val modeStartMs: Long

    /** True if pre-bolus 2 is pending delivery (scheduled but not yet fired) */
    val preBolus2Pending: Boolean

    /** True if pre-bolus 3 is pending delivery (scheduled but not yet fired).
     *  PB3 remains pending while PB2 is still counting down or waiting on gates — it can only
     *  begin its own countdown once PB2 fires successfully. */
    val preBolus3Pending: Boolean

    /** Human-readable status of pre-bolus 2 for display in dialog and tab UI.
     *  Examples: "PB2: 18min", "PB2: waiting — BG below target (5.1 <= 5.5mmol)",
     *            "PB2: delivered 14:32", "PB2: cancelled", "" if not scheduled */
    val preBolus2StatusText: String

    /** Human-readable status of pre-bolus 3 for display in dialog and tab UI.
     *  Examples: "PB3: waiting for PB2", "PB3: 18min", "PB3: waiting — BG falling",
     *            "PB3: delivered 15:02", "PB3: cancelled", "" if not scheduled */
    val preBolus3StatusText: String

    /** Seconds until pre-bolus 2 fire time (negative = overdue, waiting on safety checks) */
    val preBolus2SecondsRemaining: Long?

    /** Seconds until pre-bolus 3 fire time (negative = overdue, waiting on safety checks).
     *  null if PB3 not scheduled, or if PB2 hasn't fired yet (PB3 timer hasn't started). */
    val preBolus3SecondsRemaining: Long?

    fun activateOverride(
        mode:             MealMode,
        doseU:            Double?,
        carbsG:           Int,
        modeWindowMs:     Long   = DEFAULT_MODE_WINDOW_MS,
        preBolus2U:       Double = 0.0,
        preBolus2DelayMs: Long   = 0L,
        preBolus3U:       Double = 0.0,
        preBolus3DelayMs: Long   = 0L
    )

    fun cancelOverride()

    /**
     * Cancel a pending pre-bolus 2 without cancelling the meal mode itself.
     * Also cancels PB3 if scheduled — PB3's timing reference (PB2 fire time) will never exist
     * if PB2 is cancelled before firing, so PB3 must be cancelled as a consequence.
     * No-op if PB2 has already fired or was never scheduled.
     */
    fun cancelPreBolus2()

    /**
     * Cancel a pending pre-bolus 3 without cancelling the meal mode or PB2.
     * No-op if PB3 has already fired or was never scheduled.
     */
    fun cancelPreBolus3()

    /**
     * Called every loop cycle from SmartInsulinPlugin.invoke().
     * Checks if pre-bolus 2 is due, runs safety checks, fires if safe.
     */
    fun onLoopCycle(
        glucoseStatus: app.aaps.core.interfaces.aps.GlucoseStatus,
        iobArray:      Array<app.aaps.core.interfaces.aps.IobTotal>,
        maxIobU:       Double,
        profile:       app.aaps.core.interfaces.profile.Profile? = null
    )

    companion object {
        const val MIN_BG_FOR_PB2_MGDL        = 90.0   // ~5.0 mmol — don't fire PB2 if below this
        const val MAX_IOB_HEADROOM_RATIO      = 0.75   // IOB must be < 75% of maxIob to allow PB2
        // Delta safety: block PB2 if BG is falling on either last reading or sustained trend
        const val DELTA_INSTANT_BLOCK_MGDL    = -2.0   // single reading: -2 mg/dL = ~-0.11 mmol/5min
        const val SHORT_AVG_DELTA_BLOCK_MGDL  = -3.0   // avg of last 3: -3 mg/dL = ~-0.17 mmol/5min
        const val DEFAULT_MODE_WINDOW_MS  = 3 * 60 * 60 * 1000L
    }
}