package app.aaps.core.interfaces.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal

/**
 * Interface for the meal override system.
 * Lives in core/interfaces so both plugins/aps and plugins/automation can inject it.
 * Implementation: MealOverrideManagerImpl in plugins/aps
 */
interface MealOverrideManager {

    /** Active meal mode — returns override if not expired, else null (caller auto-detects) */
    val activeMealMode: MealMode?

    /** ISF multiplier for current loop cycle — 1.0 if no override active */
    val activeIsfMultiplier: Double

    /** Milliseconds remaining in the active mode window, or 0 if no override active */
    val modeTimeRemainingMs: Long

    fun activateOverride(
        mode:         MealMode,
        doseU:        Double?,
        carbsG:       Int,
        modeWindowMs: Long = DEFAULT_MODE_WINDOW_MS
    )

    fun cancelOverride()

    /**
     * Called every loop cycle from SmartInsulinPlugin.invoke().
     * Fires the bolus when all safety conditions are met, or drops it on expiry.
     */
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    )

    companion object {
        const val MIN_BG_MGDL            = 90.0
        const val DEFAULT_MODE_WINDOW_MS = 3 * 60 * 60 * 1000L
    }
}