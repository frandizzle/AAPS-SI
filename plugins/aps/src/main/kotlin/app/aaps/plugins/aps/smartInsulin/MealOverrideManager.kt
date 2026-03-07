package app.aaps.core.interfaces.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the active [MealOverrideState] and fires the queued bolus when safe.
 * Lives in core/interfaces so both plugins/aps and plugins/automation can inject it.
 *
 * Safety conditions (all must be true before bolus fires):
 *   1. BG rising      shortAvgDelta >= RISING_DELTA_MGDL
 *   2. IOB safe       iob[0].iob    <= max_iob * SAFE_IOB_FRACTION
 *   3. BG above floor glucose       >= MIN_BG_MGDL
 *   4. Within window  now           <= state.expiryMs
 */
@Singleton
class MealOverrideManager @Inject constructor(
    private val commandQueue: CommandQueue,
    private val aapsLogger:   AAPSLogger
) {

    @Volatile private var _state: MealOverrideState? = null

    val activeState: MealOverrideState? get() = _state

    /** Active meal mode — returns override if not expired, else null (caller auto-detects) */
    val activeMealMode: MealMode? get() {
        val s = _state ?: return null
        return if (System.currentTimeMillis() < s.modeExpiryMs) s.mode
        else { _state = null; null }
    }

    /** ISF multiplier for current loop cycle — 1.0 if no override active */
    val activeIsfMultiplier: Double get() = activeMealMode?.defaultIsfMultiplier ?: 1.0

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
                         "SmartInsulin override: mode=${mode.label} dose=${doseU}U " +
                             "carbs=${carbsG}g bolusTTL=${MealOverrideState.BOLUS_WINDOW_MS / 60_000}min " +
                             "modeTTL=${modeWindowMs / 60_000}min")
    }

    fun cancelOverride() {
        aapsLogger.debug(LTag.APS, "SmartInsulin override cancelled")
        _state = null
    }

    /**
     * Called every loop cycle from SmartInsulinPlugin.invoke().
     * Fires the bolus when all safety conditions are met, or drops it on expiry.
     */
    fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        val state = _state ?: return
        if (!state.hasPendingBolus) return

        val now        = System.currentTimeMillis()
        val currentIob = iobArray.firstOrNull()?.iob ?: 0.0
        val bg         = glucoseStatus.glucose
        val delta      = glucoseStatus.shortAvgDelta

        if (now > state.expiryMs) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: bolus window expired for ${state.mode.label} — dropping bolus, keeping mode")
            _state = state.copy(lockedDoseU = null)
            return
        }

        val safeIob    = maxIobU * SAFE_IOB_FRACTION
        val bgRising   = delta      >= RISING_DELTA_MGDL
        val iobSafe    = currentIob <= safeIob
        val bgAboveMin = bg         >= MIN_BG_MGDL

        aapsLogger.debug(LTag.APS,
                         "SmartInsulin safety: bg=$bg Δ=$delta iob=$currentIob safeIob=$safeIob " +
                             "rising=$bgRising iobOk=$iobSafe bgOk=$bgAboveMin")

        if (!bgRising || !iobSafe || !bgAboveMin) return

        val dose = state.lockedDoseU ?: return
        val detail = DetailedBolusInfo().also {
            it.insulin            = dose
            it.carbs              = state.lockedCarbsG.toDouble()
            it.bolusType          = BS.Type.NORMAL
            it.deliverAtTheLatest = now + 60_000L  // deliver within 1 minute
        }

        aapsLogger.debug(LTag.APS,
                         "SmartInsulin firing pre-bolus: ${dose}U for ${state.mode.label} " +
                             "(bg=$bg Δ=$delta iob=$currentIob)")

        commandQueue.bolus(detail, object : Callback() {
            override fun run() {
                if (result.success) {
                    aapsLogger.debug(LTag.APS, "SmartInsulin pre-bolus delivered: ${dose}U")
                    _state = state.copy(bolusFired = true)
                } else {
                    aapsLogger.error(LTag.APS, "SmartInsulin pre-bolus FAILED: ${result.comment}")
                }
            }
        })
    }

    companion object {
        const val MIN_BG_MGDL            = 90.0
        const val DEFAULT_MODE_WINDOW_MS = 3 * 60 * 60 * 1000L
        private const val RISING_DELTA_MGDL = 2.0
        private const val SAFE_IOB_FRACTION = 0.4
    }
}