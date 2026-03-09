package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.MealOverrideState
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealOverrideManagerImpl @Inject constructor(
    private val aapsLogger:   AAPSLogger,
    private val preferences:  Preferences
) : MealOverrideManager {

    @Volatile private var _state: MealOverrideState? = null

    init {
        restoreState()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persistState() {
        val s = _state
        if (s == null) {
            preferences.put(StringKey.ApsSmartInsulinOverrideState, "")
            return
        }
        val json = JSONObject().apply {
            put("mode",          s.mode.name)
            put("lockedDoseU",   s.lockedDoseU ?: JSONObject.NULL)
            put("lockedCarbsG",  s.lockedCarbsG)
            put("triggerTimeMs", s.triggerTimeMs)
            put("expiryMs",      s.expiryMs)
            put("modeExpiryMs",  s.modeExpiryMs)
            put("bolusFired",    s.bolusFired)
        }
        preferences.put(StringKey.ApsSmartInsulinOverrideState, json.toString())
        aapsLogger.debug(LTag.APS, "SmartInsulin: override state persisted mode=${s.mode.label}")
    }

    private fun restoreState() {
        val raw = preferences.get(StringKey.ApsSmartInsulinOverrideState)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            val mode = MealMode.valueOf(json.getString("mode"))
            val now  = System.currentTimeMillis()
            val modeExpiryMs = json.getLong("modeExpiryMs")
            // Discard if mode window already expired
            if (modeExpiryMs <= now) {
                preferences.put(StringKey.ApsSmartInsulinOverrideState, "")
                aapsLogger.debug(LTag.APS, "SmartInsulin: persisted override expired on restore, discarding")
                return
            }
            _state = MealOverrideState(
                mode          = mode,
                lockedDoseU   = if (json.isNull("lockedDoseU")) null else json.getDouble("lockedDoseU"),
                lockedCarbsG  = json.getInt("lockedCarbsG"),
                triggerTimeMs = json.getLong("triggerTimeMs"),
                expiryMs      = json.getLong("expiryMs"),
                modeExpiryMs  = modeExpiryMs,
                bolusFired    = json.getBoolean("bolusFired")
            )
            val remainingMins = (modeExpiryMs - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin: override restored mode=${mode.label} ${remainingMins}min remaining")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SmartInsulin: failed to restore override state: ${e.message}")
            preferences.put(StringKey.ApsSmartInsulinOverrideState, "")
        }
    }

    // ── Interface ─────────────────────────────────────────────────────────────

    override val activeMealMode: MealMode? get() {
        val s = _state ?: return null
        val now = System.currentTimeMillis()
        val remainingMs = s.modeExpiryMs - now
        return if (remainingMs > 0) {
            s.mode
        } else {
            aapsLogger.debug(LTag.APS, "SmartInsulin mode ${s.mode.label} expired")
            _state = null
            persistState()
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
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin override: mode=${mode.label} dose=${doseU}U " +
                             "carbs=${carbsG}g modeTTL=${modeWindowMs / 60_000}min")
    }

    override fun cancelOverride() {
        aapsLogger.debug(LTag.APS, "SmartInsulin override cancelled")
        _state = null
        persistState()
    }

    override fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        // Pre-bolus is now delivered directly by SmartMealDialog.
        // onLoopCycle() intentionally does nothing.
    }

    companion object {
        // companions retained in case onLoopCycle logic is re-introduced later
        private const val FALLING_DELTA_MGDL_CUTOFF = 2.0
        private const val SAFE_IOB_FRACTION          = 0.4
    }
}