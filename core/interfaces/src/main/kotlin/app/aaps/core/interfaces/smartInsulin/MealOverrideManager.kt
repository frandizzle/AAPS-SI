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

    /** ISF multiplier for current loop cycle — 1.0 if no override active */
    val activeIsfMultiplier: Double

    /** Milliseconds remaining in the active mode window, or 0 if no override active */
    val modeTimeRemainingMs: Long

    /** True if pre-bolus 2 is pending delivery (scheduled but not yet fired) */
    val preBolus2Pending: Boolean

    /** Human-readable status of pre-bolus 2 for display in dialog and tab UI.
     *  Examples: "PB2: 18min", "PB2: waiting — BG below target (5.1 <= 5.5mmol)",
     *            "PB2: delivered 14:32", "PB2: cancelled", "" if not scheduled */
    val preBolus2StatusText: String

    /** Seconds until pre-bolus 2 fire time (negative = overdue, waiting on safety checks) */
    val preBolus2SecondsRemaining: Long?

    fun activateOverride(
        mode:             MealMode,
        doseU:            Double?,
        carbsG:           Int,
        modeWindowMs:     Long   = DEFAULT_MODE_WINDOW_MS,
        preBolus2U:       Double = 0.0,
        preBolus2DelayMs: Long   = 0L
    )

    fun cancelOverride()

    /**
     * Cancel a pending pre-bolus 2 without cancelling the meal mode itself.
     * No-op if PB2 has already fired or was never scheduled.
     */
    fun cancelPreBolus2()

    /**
     * Called every loop cycle from SmartInsulinPlugin.invoke().
     * Checks if pre-bolus 2 is due, runs safety checks, fires if safe.
     */
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
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