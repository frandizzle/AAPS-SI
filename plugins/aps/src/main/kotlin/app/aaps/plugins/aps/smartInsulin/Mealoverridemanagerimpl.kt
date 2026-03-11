package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.MealOverrideState
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealOverrideManagerImpl @Inject constructor(
    private val aapsLogger:      AAPSLogger,
    private val preferences:     Preferences,
    private val commandQueue:    CommandQueue,
    private val profileFunction: ProfileFunction,
    private val dateUtil:        DateUtil,
    private val uiInteraction:   UiInteraction
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
            put("mode",             s.mode.name)
            put("triggerTimeMs",    s.triggerTimeMs)
            put("modeExpiryMs",     s.modeExpiryMs)
            put("preBolus2U",       s.preBolus2U)
            put("preBolus2DelayMs", s.preBolus2DelayMs)
            if (s.preBolus2FiredMs != null) put("preBolus2FiredMs", s.preBolus2FiredMs)
        }
        preferences.put(StringKey.ApsSmartInsulinOverrideState, json.toString())
        aapsLogger.debug(LTag.APS, "SmartInsulin: override state persisted mode=${s.mode.label}")
    }

    private fun restoreState() {
        val raw = preferences.get(StringKey.ApsSmartInsulinOverrideState)
        if (raw.isBlank()) return
        try {
            val json         = JSONObject(raw)
            val mode         = MealMode.valueOf(json.getString("mode"))
            val now          = System.currentTimeMillis()
            val modeExpiryMs = json.getLong("modeExpiryMs")
            if (modeExpiryMs <= now) {
                preferences.put(StringKey.ApsSmartInsulinOverrideState, "")
                aapsLogger.debug(LTag.APS, "SmartInsulin: persisted override expired on restore")
                return
            }
            _state = MealOverrideState(
                mode             = mode,
                triggerTimeMs    = json.getLong("triggerTimeMs"),
                modeExpiryMs     = modeExpiryMs,
                preBolus2U       = json.optDouble("preBolus2U", 0.0),
                preBolus2DelayMs = json.optLong("preBolus2DelayMs", 0L),
                preBolus2FiredMs = if (json.has("preBolus2FiredMs")) json.getLong("preBolus2FiredMs") else null
            )
            val remainingMins = (modeExpiryMs - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin: override restored mode=${mode.label} ${remainingMins}min remaining" +
                if (_state!!.preBolus2Pending) " pb2=${_state!!.preBolus2U}U pending" else "")
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

    override val preBolus2Pending: Boolean
        get() = _state?.preBolus2Pending == true

    override val preBolus2MinutesRemaining: Int? get() {
        val s = _state?.takeIf { it.preBolus2Pending } ?: return null
        val minsLeft = ((s.preBolus2FireAtMs - System.currentTimeMillis()) / 60_000).toInt()
        return minsLeft.coerceAtLeast(0)
    }

    override fun activateOverride(
        mode:             MealMode,
        doseU:            Double?,
        carbsG:           Int,
        modeWindowMs:     Long,
        preBolus2U:       Double,
        preBolus2DelayMs: Long
    ) {
        val now = System.currentTimeMillis()
        _state = MealOverrideState(
            mode             = mode,
            triggerTimeMs    = now,
            modeExpiryMs     = now + modeWindowMs,
            preBolus2U       = preBolus2U,
            preBolus2DelayMs = preBolus2DelayMs,
            preBolus2FiredMs = null
        )
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin override: mode=${mode.label} modeTTL=${modeWindowMs / 60_000}min" +
                             if (preBolus2U > 0.0) " pb2=${preBolus2U}U in ${preBolus2DelayMs / 60_000}min" else "")
    }

    override fun cancelOverride() {
        aapsLogger.debug(LTag.APS, "SmartInsulin override cancelled")
        _state = null
        persistState()
    }

    // ── Pre-bolus 2 delivery ──────────────────────────────────────────────────

    override fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        val s = _state ?: return
        if (!s.preBolus2Pending) return

        val now = System.currentTimeMillis()

        // Not time yet
        if (now < s.preBolus2FireAtMs) {
            val minsLeft = (s.preBolus2FireAtMs - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin PB2: ${minsLeft}min until scheduled delivery")
            return
        }

        // ── Safety checks ─────────────────────────────────────────────────────
        val currentBgMgdl = glucoseStatus.glucose
        val currentIob    = iobArray.firstOrNull()?.iob ?: 0.0
        val profile       = profileFunction.getProfile()
        val profileTarget = profile?.getTargetMgdl() ?: 108.0  // fallback 6.0 mmol

        val reasons = mutableListOf<String>()

        // 1. BG must be above threshold (don't stack if already dropping to target)
        if (currentBgMgdl < MealOverrideManager.MIN_BG_FOR_PB2_MGDL) {
            reasons += "BG ${String.format("%.1f", currentBgMgdl / 18.0)}mmol < threshold"
        }

        // 2. BG must actually be above profile target — the whole point is catching a protein/fat rise
        if (currentBgMgdl <= profileTarget) {
            reasons += "BG not above target (${String.format("%.1f", currentBgMgdl/18.0)} <= ${String.format("%.1f", profileTarget/18.0)}mmol)"
        }

        // 3. IOB headroom — must have room to absorb additional bolus
        val iobHeadroomOk = currentIob < (maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO)
        if (!iobHeadroomOk) {
            reasons += "IOB ${String.format("%.2f", currentIob)}U >= ${String.format("%.0f", MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100)}% of maxIob ${String.format("%.1f", maxIobU)}U"
        }

        // 4. BG trend — don't fire if falling fast (delta < -1 mg/dL/5min)
        if (glucoseStatus.delta < -1.0) {
            reasons += "BG falling (delta=${String.format("%.1f", glucoseStatus.delta)}mg/dL)"
        }

        if (reasons.isNotEmpty()) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB2 BLOCKED: ${reasons.joinToString(", ")}")
            // If mode has already expired, drop the pending PB2 entirely to avoid stale delivery
            if (now > s.modeExpiryMs) {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB2: mode expired, discarding pending PB2")
                _state = s.copy(preBolus2FiredMs = -1L)  // -1 = discarded (never null if not pending)
                persistState()
            }
            return
        }

        // ── Fire ─────────────────────────────────────────────────────────────
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB2 FIRING: ${s.preBolus2U}U bg=${String.format("%.1f", currentBgMgdl/18.0)}mmol " +
                             "iob=${String.format("%.2f", currentIob)}U target=${String.format("%.1f", profileTarget/18.0)}mmol")

        val bolusInfo = DetailedBolusInfo().apply {
            insulin   = s.preBolus2U
            notes     = "SmartMeal ${s.mode.label} pre-bolus 2 (protein/fat cover)"
            timestamp = dateUtil.now()
        }

        // Mark as fired *before* delivery to prevent double-fire if loop cycles quickly
        _state = s.copy(preBolus2FiredMs = now)
        persistState()

        commandQueue.bolus(bolusInfo, object : Callback() {
            override fun run() {
                if (!result.success) {
                    aapsLogger.error(LTag.APS, "SmartInsulin PB2 delivery FAILED: ${result.comment}")
                    uiInteraction.runAlarm(
                        result.comment,
                        "SmartMeal pre-bolus 2 failed",
                        app.aaps.core.ui.R.raw.boluserror
                    )
                    // Revert fired state so it can retry next cycle if failure was transient
                    _state = _state?.copy(preBolus2FiredMs = null)
                    persistState()
                } else {
                    aapsLogger.debug(LTag.APS, "SmartInsulin PB2 delivered OK: ${s.preBolus2U}U")
                }
            }
        })
    }
}