package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.pump.PumpEnactResult
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the active [MealOverrideState] and evaluates safety conditions each loop cycle.
 *
 * Safety conditions that must ALL be true before the queued bolus is fired:
 *   1. BG is rising  (shortAvgDelta >= RISING_DELTA_MGDL)
 *   2. Current IOB is below a safe fraction of max_iob  (iob <= maxSafeIobU)
 *   3. BG is above the low floor  (glucose >= MIN_BG_MGDL)
 *   4. The 30-minute delivery window has not expired
 *
 * ISF multiplier for the active mode is exposed via [activeIsfMultiplier] and applied
 * by [SmartInsulinPlugin] when building the OapsProfile for each loop cycle.
 */
@Singleton
class MealOverrideManager @Inject constructor(
    private val commandQueue:          CommandQueue,
    private val aapsLogger:            AAPSLogger,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>
) {

    // ── Active state ─────────────────────────────────────────────────────────

    @Volatile private var _state: MealOverrideState? = null
    val activeState: MealOverrideState? get() = _state

    /** Current meal mode — override if active, else null (caller falls back to auto-detect) */
    val activeMealMode: MealMode? get() {
        val s = _state ?: return null
        return if (System.currentTimeMillis() < s.modeExpiryMs) s.mode else {
            // mode window expired — clear silently
            _state = null
            null
        }
    }

    /** ISF multiplier to apply this loop cycle. 1.0 if no override active. */
    val activeIsfMultiplier: Double get() = activeMealMode?.defaultIsfMultiplier ?: 1.0

    // ── Safety thresholds ────────────────────────────────────────────────────

    /** BG must be rising at least this fast (mg/dL per 5 min) before bolus fires */
    private val RISING_DELTA_MGDL = 2.0

    /** BG must be above this floor (mg/dL) before bolus fires */
    private val MIN_BG_MGDL = 90.0   // ~5.0 mmol/L

    /** Bolus fires only when IOB ≤ this fraction of oapsProfile.max_iob */
    private val SAFE_IOB_FRACTION = 0.4

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by [ActionSmartMeal] when the user selects a meal type.
     *
     * @param mode          Meal type chosen
     * @param doseU         Pre-calculated bolus dose (U), or null for mode-only
     * @param carbsG        Carb amount used to calculate doseU
     * @param modeWindowMs  How long the mode override stays active (ms)
     */
    fun activateOverride(
        mode:         MealMode,
        doseU:        Double?,
        carbsG:       Int,
        modeWindowMs: Long = DEFAULT_MODE_WINDOW_MS
    ) {
        val now = System.currentTimeMillis()
        _state = MealOverrideState(
            mode          = mode,
            lockedDoseU   = doseU,
            lockedCarbsG  = carbsG,
            triggerTimeMs = now,
            expiryMs      = now + MealOverrideState.BOLUS_WINDOW_MS,
            modeExpiryMs  = now + modeWindowMs
        )
        aapsLogger.debug(LTag.APS,
            "SmartInsulin override activated: mode=${mode.label} dose=${doseU}U carbs=${carbsG}g " +
            "expiresIn=${MealOverrideState.BOLUS_WINDOW_MS / 60_000}min " +
            "modeWindowMin=${modeWindowMs / 60_000}")
    }

    /** Manually cancel any pending override (e.g. user taps Cancel) */
    fun cancelOverride() {
        aapsLogger.debug(LTag.APS, "SmartInsulin override cancelled by user")
        _state = null
    }

    /**
     * Called every loop cycle from [SmartInsulinPlugin.invoke].
     *
     * Evaluates safety conditions and fires the bolus if they are all met.
     * Cancels the pending bolus (but keeps mode active) on expiry.
     *
     * @param glucoseStatus  Current BG snapshot
     * @param iobArray       Current IOB array from IobCobCalculator
     * @param maxIobU        max_iob from OapsProfile (used to derive safe IOB threshold)
     */
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        val state = _state ?: return
        if (!state.hasPendingBolus) return

        val now      = System.currentTimeMillis()
        val currentIob = iobArray.firstOrNull()?.iob ?: 0.0
        val bg         = glucoseStatus.glucose
        val delta      = glucoseStatus.shortAvgDelta

        // ── Check expiry ──────────────────────────────────────────────────────
        if (now > state.expiryMs) {
            aapsLogger.debug(LTag.APS,
                "SmartInsulin pre-bolus window expired for ${state.mode.label} — cancelling bolus")
            // Keep mode active but drop the pending bolus
            _state = state.copy(lockedDoseU = null)
            return
        }

        // ── Safety gate ───────────────────────────────────────────────────────
        val safeIob    = maxIobU * SAFE_IOB_FRACTION
        val bgRising   = delta   >= RISING_DELTA_MGDL
        val iobSafe    = currentIob <= safeIob
        val bgAboveMin = bg      >= MIN_BG_MGDL

        aapsLogger.debug(LTag.APS,
            "SmartInsulin safety check — bg=$bg delta=$delta iob=$currentIob " +
            "safeIob=$safeIob rising=$bgRising iobSafe=$iobSafe bgAboveMin=$bgAboveMin")

        if (!bgRising || !iobSafe || !bgAboveMin) return

        // ── All conditions met → fire bolus ───────────────────────────────────
        val dose   = state.lockedDoseU ?: return
        val detail = DetailedBolusInfo().apply {
            insulin    = dose
            carbs      = state.lockedCarbsG.toDouble()
            bolusType  = DetailedBolusInfo.BolusType.NORMAL
            deliverAt  = now
        }

        aapsLogger.debug(LTag.APS,
            "SmartInsulin firing pre-bolus: ${dose}U for ${state.mode.label} " +
            "(bg=$bg delta=$delta iob=$currentIob)")

        commandQueue.bolus(detail, object : Callback() {
            override fun run() {
                if (result.success) {
                    aapsLogger.debug(LTag.APS, "SmartInsulin pre-bolus delivered: ${dose}U")
                    _state = state.copy(bolusFired = true)
                } else {
                    aapsLogger.error(LTag.APS,
                        "SmartInsulin pre-bolus FAILED: ${result.comment}")
                }
            }
        })
    }

    companion object {
        /** Default mode window: 3 hours */
        const val DEFAULT_MODE_WINDOW_MS = 3 * 60 * 60 * 1000L
    }
}
