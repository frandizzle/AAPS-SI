package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.MealOverrideState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealOverrideManagerImpl @Inject constructor(
    private val commandQueue: CommandQueue,
    private val aapsLogger:   AAPSLogger
) : MealOverrideManager {

    @Volatile private var _state: MealOverrideState? = null

    override val activeMealMode: MealMode? get() {
        val s = _state ?: return null
        val now = System.currentTimeMillis()
        val remainingMs = s.modeExpiryMs - now
        return if (remainingMs > 0) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin activeMealMode=${s.mode.label} remaining=${remainingMs / 60_000}min")
            s.mode
        } else {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin mode ${s.mode.label} expired (modeExpiryMs=${s.modeExpiryMs} now=$now)")
            _state = null
            null
        }
    }

    override val activeIsfMultiplier: Double get() = 1.0  // actual multiplier read from prefs in SmartInsulinPlugin

    override val modeTimeRemainingMs: Long get() {
        val s = _state ?: return 0L
        return (s.modeExpiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override fun activateOverride(
        mode:         MealMode,
        doseU:        Double?,
        carbsG:       Int,
        modeWindowMs: Long
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
                             "modeTTL=${modeWindowMs / 60_000}min modeExpiryMs=${now + modeWindowMs}")
    }

    override fun cancelOverride() {
        aapsLogger.debug(LTag.APS, "SmartInsulin override cancelled")
        _state = null
    }

    override fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        val state = _state ?: return
        if (!state.hasPendingBolus) return

        val now        = System.currentTimeMillis()
        val currentIob = iobArray.firstOrNull()?.iob ?: 0.0
        val bg         = glucoseStatus.glucose   // always mg/dL internally
        val delta      = glucoseStatus.shortAvgDelta

        if (now > state.expiryMs) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: bolus window expired for ${state.mode.label} — dropping bolus, keeping mode")
            _state = state.copy(lockedDoseU = null)
            return
        }

        // Safety gates for intentional pre-bolus:
        //   1. BG above hard low guard (90 mg/dL / 5 mmol) — never bolus into a low
        //   2. Not falling fast — don't add insulin if already dropping
        //   3. IOB headroom — don't stack on top of a lot of active insulin
        val bgSafe     = bg    >= MealOverrideManager.MIN_BG_MGDL
        val notFalling = delta >= -FALLING_DELTA_MGDL_CUTOFF
        val iobSafe    = currentIob <= maxIobU * SAFE_IOB_FRACTION

        aapsLogger.debug(LTag.APS,
                         "SmartInsulin pre-bolus check: bg=$bg Δ=$delta iob=$currentIob safeIob=${maxIobU * SAFE_IOB_FRACTION} " +
                             "bgSafe=$bgSafe notFalling=$notFalling iobSafe=$iobSafe")

        if (!bgSafe || !notFalling || !iobSafe) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin pre-bolus safety blocked: bgSafe=$bgSafe notFalling=$notFalling iobSafe=$iobSafe")
            return
        }

        val dose = state.lockedDoseU ?: return
        val detail = DetailedBolusInfo().also {
            it.insulin            = dose
            it.carbs              = state.lockedCarbsG.toDouble()
            it.bolusType          = BS.Type.NORMAL
            it.deliverAtTheLatest = now + 60_000L
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
        private const val FALLING_DELTA_MGDL_CUTOFF = 2.0   // mg/dL per 5min — block if falling faster
        private const val SAFE_IOB_FRACTION          = 0.4   // fraction of maxIob allowed before blocking
    }
}