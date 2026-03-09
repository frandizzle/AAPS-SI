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
    private val aapsLogger:  AAPSLogger,
    private val preferences: Preferences
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
            put("triggerTimeMs", s.triggerTimeMs)
            put("modeExpiryMs",  s.modeExpiryMs)
        }
        preferences.put(StringKey.ApsSmartInsulinOverrideState, json.toString())
        aapsLogger.debug(LTag.APS, "SmartInsulin: override state persisted mode=${s.mode.label}")
    }

    private fun restoreState() {
        val raw = preferences.get(StringKey.ApsSmartInsulinOverrideState)
        if (raw.isBlank()) return
        try {
            val json        = JSONObject(raw)
            val mode        = MealMode.valueOf(json.getString("mode"))
            val now         = System.currentTimeMillis()
            val modeExpiryMs = json.getLong("modeExpiryMs")
            if (modeExpiryMs <= now) {
                preferences.put(StringKey.ApsSmartInsulinOverrideState, "")
                aapsLogger.debug(LTag.APS, "SmartInsulin: persisted override expired on restore, discarding")
                return
            }
            _state = MealOverrideState(
                mode          = mode,
                triggerTimeMs = json.getLong("triggerTimeMs"),
                modeExpiryMs  = modeExpiryMs
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
        val s   = _state ?: return null
        val now = System.currentTimeMillis()
        return if (s.modeExpiryMs > now) {
            s.mode
        } else {
            aapsLogger.debug(LTag.APS, "SmartInsulin mode ${s.mode.label} expired")
            _state = null
            persistState()
            null
        }
    }

    override val activeIsfMultiplier: Double get() = 1.0

    override val modeTimeRemainingMs: Long get() {
        val s = _state ?: return 0L
        return (s.modeExpiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override fun activateOverride(
        mode:         MealMode,
        doseU:        Double?,   // retained in signature for API compat, ignored here
        carbsG:       Int,       // retained in signature for API compat, ignored here
        modeWindowMs: Long
    ) {
        val now = System.currentTimeMillis()
        _state = MealOverrideState(
            mode          = mode,
            triggerTimeMs = now,
            modeExpiryMs  = now + modeWindowMs
        )
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin override: mode=${mode.label} modeTTL=${modeWindowMs / 60_000}min")
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
        // Pre-bolus is delivered directly by SmartMealDialog.
        // onLoopCycle() intentionally does nothing.
    }
}