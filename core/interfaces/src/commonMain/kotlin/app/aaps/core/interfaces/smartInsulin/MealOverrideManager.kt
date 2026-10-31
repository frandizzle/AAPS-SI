package app.aaps.core.interfaces.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.profile.Profile

/**
 * Interface for the meal override system.
 * Lives in core/interfaces so both plugins/aps and plugins/automation can inject it.
 * Implementation: MealOverrideManagerImpl in plugins/aps
 */
interface MealOverrideManager {

    /** Active meal mode — returns override if not expired, else null */
    val activeMealMode: MealMode?

    /** The running meal mode with its pre-boluses, or null when no mode is active. */
    val activeState: MealOverrideState?

    /** Pre-bolus 1 dose delivered when this meal mode was activated — null if none or not via SmartMeal */
    val activeDoseU: Double?
    /** Non-null and > 0 when PB2 has been delivered this session — shows the delivered amount */
    val activePb2DoseU: Double?
    /** Non-null and > 0 when PB3 has been delivered this session — shows the delivered amount */
    val activePb3DoseU: Double?

    /** ISF multiplier for current loop cycle — 1.0 if no override active */
    val activeIsfMultiplier: Double

    /** True if DURA_ISF is enabled for the currently active meal mode */
    val activeDuraEnabled: Boolean

    /** DURA_ISF floor (mg/dL/U) for the currently active meal mode — 0.0 if not set/no override active */
    val activeDuraFloorMgdl: Double

    /** DURA_ISF strength (ramp weight) for the currently active meal mode — 1.0 default, 0 = no effect */
    val activeDuraStrength: Double

    /** Milliseconds remaining in the active mode window, or 0 if no override active */
    val modeTimeRemainingMs: Long

    /** Timestamp (ms) when the current override was activated, or 0 if no override active.
     *  Used by auto-cancel logic to protect the early-meal window from premature cancellation. */
    val modeStartMs: Long

    /** True while the rest of pre-bolus 1 waits for BG to rise. See [PB1_REST_FROM_MIN]. */
    val preBolus1RestPending: Boolean

    /** Status of the rest of pre-bolus 1, for the dialog and the SI tab; "" when there is none. */
    val preBolus1RestStatusText: String

    /** True if pre-bolus 2 is pending delivery (scheduled but not yet fired) */
    val preBolus2Pending: Boolean

    /** True if pre-bolus 3 is pending delivery (scheduled but not yet fired) */
    val preBolus3Pending: Boolean

    /** Human-readable status of pre-bolus 2 for display in dialog and tab UI.
     *  Examples: "PB2: 18min", "PB2: waiting — BG below target (5.1 <= 5.5mmol)",
     *            "PB2: delivered 14:32", "PB2: cancelled", "" if not scheduled */
    val preBolus2StatusText: String

    /** Human-readable status of pre-bolus 3 for display in dialog and tab UI. */
    val preBolus3StatusText: String

    /** Seconds until pre-bolus 2 fire time (negative = overdue, waiting on safety checks) */
    val preBolus2SecondsRemaining: Long?

    /** Seconds until pre-bolus 3 fire time. Returns null if PB2 not yet fired. */
    val preBolus3SecondsRemaining: Long?

    fun activateOverride(
        mode:             MealMode,
        doseU:            Double?,
        carbsG:           Int,
        modeWindowMs:     Long   = DEFAULT_MODE_WINDOW_MS,
        preBolus2U:       Double = 0.0,
        preBolus2DelayMs: Long   = 0L,
        preBolus3U:       Double = 0.0,
        preBolus3DelayMs: Long   = 0L,
        duraEnabled:      Boolean = false,
        duraFloorMgdl:    Double  = 0.0,
        duraStrength:     Double  = 1.0
    )

    fun cancelOverride()

    /**
     * Start Protein/Fat (UAM) when the running mode runs out by itself. Call it right after
     * [activateOverride]. Ending the mode by hand does not start it.
     */
    fun followWithProteinFat()

    /**
     * True once, when a mode that was set to [followWithProteinFat] has just run out. The caller then
     * starts Protein/Fat.
     */
    fun takeProteinFatFollowOn(): Boolean

    /**
     * Gives the running mode [pct] percent less insulin, because it started soon after exercise. Call it right
     * after [activateOverride]; the pre-boluses are already cut by the caller. See `MealOverrideState.afterActivityPct`.
     */
    fun setAfterActivityCut(pct: Int)

    /**
     * Gives the running mode a rest of pre-bolus 1 of [restU] units: the part above the max pre-bolus. Call it
     * right after [activateOverride]. It fires from [PB1_REST_FROM_MIN] to [PB1_REST_UNTIL_MIN] minutes after
     * the start, only once BG rises, and only within the same IOB and loop limits as pre-bolus 2.
     */
    fun setPreBolus1Rest(restU: Double)

    /** Cancels the rest of pre-bolus 1 if it still waits. */
    fun cancelPreBolus1Rest()

    /**
     * Adds food eaten later to the running meal mode, for example a dessert. Pre-bolus 2 and 3 only
     * grow while they still wait; one that has fired or was cancelled stays as it is, and the loop
     * covers the rest. The mode never gets shorter, and DURA and Protein/Fat are only turned on, never off.
     *
     * @param addedU         insulin given now for the added food (the caller delivers it)
     * @param preBolus2AddU  added to pre-bolus 2 if it still waits
     * @param preBolus3AddU  added to pre-bolus 3 if it still waits
     * @param modeEndMs      the mode runs at least until this time
     * @param duraFloorMgdl  used only when DURA was off and is turned on now
     * @param duraStrength   used only when DURA was off and is turned on now
     * @return false when no meal mode is running, so nothing was changed
     */
    fun addToMeal(
        addedU:        Double,
        preBolus2AddU: Double,
        preBolus3AddU: Double,
        modeEndMs:     Long,
        duraEnabled:   Boolean,
        duraFloorMgdl: Double,
        duraStrength:  Double,
        pfAfter:       Boolean
    ): Boolean

    /**
     * Cancel a pending pre-bolus 2 without cancelling the meal mode itself.
     * Also cancels PB3 if pending, as PB3 timing is relative to PB2 firing.
     */
    fun cancelPreBolus2()

    /**
     * Cancel a pending pre-bolus 3 without cancelling the meal mode itself.
     */
    fun cancelPreBolus3()

    /**
     * Called every loop cycle from SmartInsulinPlugin.invoke().
     * Checks if pre-bolus 2/3 is due, runs safety checks, fires if safe.
     */
    /**
     * @param loopRestraining true when the main loop is actively holding insulin back — inside a
     *   post-low rebound window, or its last decision was a zero temp. A pre-bolus scheduled
     *   20–40 minutes earlier carries no knowledge of what has happened since, so without this
     *   it can land as a fixed dose at the moment the loop has decided to deliver nothing.
     */
    fun onLoopCycle(
        glucoseStatus:   GlucoseStatus,
        iobArray:        Array<IobTotal>,
        maxIobU:         Double,
        profile:         Profile? = null,
        loopRestraining: Boolean  = false
    )

    data class Pb2GateData(
        val isMmol:            Boolean,
        val profileTargetMgdl: Double,
        val bgMgdl:            Double,
        val maxIobU:           Double,
        val iobU:              Double,
        val deltaMgdl:         Double,
        val shortAvgDeltaMgdl: Double
    )

    /** Last known safety-gate values for PB2; null if no active session or no gate check run yet */
    val pb2GateData: Pb2GateData?

    /** Last known safety-gate values for PB3; null if no active session or PB3 never pending */
    val pb3GateData: Pb2GateData?

    companion object {
        const val MIN_BG_FOR_PB2_MGDL        = 90.0   // ~5.0 mmol — don't fire PB2 if below this
        const val MAX_IOB_HEADROOM_RATIO      = 0.75   // IOB must be < 75% of maxIob to allow PB2
        // Delta safety: block PB2 if BG is falling on either last reading or sustained trend
        const val DELTA_INSTANT_BLOCK_MGDL    = -2.0   // single reading: -2 mg/dL = ~-0.11 mmol/5min
        const val SHORT_AVG_DELTA_BLOCK_MGDL  = -3.0   // avg of last 3: -3 mg/dL = ~-0.17 mmol/5min
        const val DEFAULT_MODE_WINDOW_MS  = 3 * 60 * 60 * 1000L

        // Pre-bolus 1 rest: the part of pre-bolus 1 above the max pre-bolus. It is given once BG shows the
        // food coming in, not before [PB1_REST_FROM_MIN] minutes after the start, and dropped after
        // [PB1_REST_UNTIL_MIN]: by then pre-bolus 2 and the loop take over.
        const val PB1_REST_FROM_MIN  = 15
        const val PB1_REST_UNTIL_MIN = 45
        /** BG counts as rising from this change per 5 min, in mg/dL (about 0.1 mmol), with the 15 min average also up. */
        const val PB1_REST_RISE_MGDL = 2.0
        /** A smaller rest is left to the loop. */
        const val PB1_REST_MIN_U     = 0.5
    }
}
