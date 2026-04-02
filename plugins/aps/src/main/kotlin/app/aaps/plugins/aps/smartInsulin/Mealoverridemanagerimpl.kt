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
            if (s.doseU != null) put("doseU", s.doseU)
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
                doseU            = if (json.has("doseU")) json.getDouble("doseU") else null,
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
        if (s.modeExpiryMs > now) return s.mode

        // Mode expired. If PB2 is still pending, leave _state intact so
        // onLoopCycle can formally discard and log it rather than silently vanishing.
        if (s.preBolus2Pending) return null

        aapsLogger.debug(LTag.APS, "SmartInsulin mode ${s.mode.label} expired")
        _state = null
        persistState()
        return null
    }

    override val activeIsfMultiplier: Double get() = 1.0

    override val activeDoseU: Double? get() = if (activeMealMode != null) _state?.doseU else null
    override val activePb2DoseU: Double? get() {
        val s = _state ?: return null
        if (activeMealMode == null) return null
        // preBolus2FiredMs > 0 means PB2 was delivered; -1L means cancelled
        return if (s.preBolus2FiredMs != null && s.preBolus2FiredMs!! > 0L && s.preBolus2U > 0.0)
            s.preBolus2U else null
    }

    override val modeTimeRemainingMs: Long get() {
        val s = _state ?: return 0L
        return (s.modeExpiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override val preBolus2Pending: Boolean
        get() = _state?.preBolus2Pending == true

    override val preBolus2SecondsRemaining: Long? get() {
        val s = _state?.takeIf { it.preBolus2Pending } ?: return null
        return s.preBolus2FireAtMs - System.currentTimeMillis()  // negative = overdue/waiting on safety
    }

    /** Evaluate current safety blocks without needing glucoseStatus (uses last known values). */
    private fun safetyBlockReasons(
        bgMgdl:        Double,
        iob:           Double,
        maxIob:        Double,
        targetMgdl:    Double,
        delta:         Double,
        shortAvgDelta: Double
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (bgMgdl < MealOverrideManager.MIN_BG_FOR_PB2_MGDL)
            reasons += "BG too low"
        if (bgMgdl <= targetMgdl)
            reasons += "BG not above target"
        if (iob >= maxIob * MealOverrideManager.MAX_IOB_HEADROOM_RATIO)
            reasons += "IOB too high"
        if (delta < MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)
            reasons += "BG falling"
        if (shortAvgDelta < MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)
            reasons += "Trend falling"
        return reasons
    }

    // Cache last known glucose values so statusText can reflect safety state between loop cycles
    @Volatile private var lastBgMgdl        = 0.0
    @Volatile private var lastIob           = 0.0
    @Volatile private var lastMaxIob        = 0.0
    @Volatile private var lastTargetMgdl    = 108.0
    @Volatile private var lastDelta         = 0.0
    @Volatile private var lastShortAvgDelta = 0.0

    override val preBolus2StatusText: String get() {
        val s   = _state ?: return ""
        val now = System.currentTimeMillis()
        return when {
            // Discarded (-1L) or already fired (positive timestamp)
            s.preBolus2FiredMs != null && s.preBolus2FiredMs == -1L ->
                "PB2: cancelled"
            s.preBolus2FiredMs != null && s.preBolus2FiredMs!! > 0L -> {
                val firedMins = (now - s.preBolus2FiredMs!!) / 60_000
                "PB2: delivered ${firedMins}min ago"
            }
            // Counting down
            now < s.preBolus2FireAtMs -> {
                val secsLeft = (s.preBolus2FireAtMs - now) / 1000
                if (secsLeft >= 60)
                    "PB2: ${secsLeft / 60}min ${secsLeft % 60}s"
                else
                    "PB2: ${secsLeft}s"
            }
            // Time elapsed — show safety block reasons if any
            else -> {
                val reasons = safetyBlockReasons(
                    lastBgMgdl, lastIob, lastMaxIob, lastTargetMgdl, lastDelta, lastShortAvgDelta
                )
                if (reasons.isEmpty()) "PB2: waiting for next loop cycle"
                else "PB2 waiting: ${reasons.joinToString(", ")}"
            }
        }
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
            doseU            = doseU,
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

    override fun cancelPreBolus2() {
        val s = _state ?: return
        if (!s.preBolus2Pending) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB2 cancel: nothing pending")
            return
        }
        // Mark as discarded (-1L) so it won't retry, without touching mode expiry
        _state = s.copy(preBolus2FiredMs = -1L)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin PB2 cancelled by user (mode still active: ${s.mode.label})")
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
        val profileTarget = profile?.getTargetMgdl() ?: 108.0

        // Cache for preBolus2StatusText so dialog can show live block reasons between cycles
        lastBgMgdl        = currentBgMgdl
        lastIob           = currentIob
        lastMaxIob        = maxIobU
        lastTargetMgdl    = profileTarget
        lastDelta         = glucoseStatus.delta
        lastShortAvgDelta = glucoseStatus.shortAvgDelta

        val reasons = safetyBlockReasons(
            bgMgdl        = currentBgMgdl,
            iob           = currentIob,
            maxIob        = maxIobU,
            targetMgdl    = profileTarget,
            delta         = glucoseStatus.delta,
            shortAvgDelta = glucoseStatus.shortAvgDelta
        )

        if (reasons.isNotEmpty()) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB2 BLOCKED: ${reasons.joinToString(", ")}")
            // If mode has already expired, formally discard pending PB2 and clean up state entirely
            if (now > s.modeExpiryMs) {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB2: mode expired, discarding pending PB2")
                _state = null  // fully clean up — getter kept state alive for this formal discard
                persistState()
            }
            return
        }

        // ── Fire ─────────────────────────────────────────────────────────────
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB2 FIRING: ${s.preBolus2U}U bg=${String.format("%.1f", currentBgMgdl/18.0)} " +
                             "iob=${String.format("%.2f", currentIob)}U target=${String.format("%.1f", profileTarget/18.0)}")

        val bolusInfo = DetailedBolusInfo().apply {
            insulin   = s.preBolus2U
            notes     = "SmartMeal ${s.mode.label} pre-bolus 2 (protein/fat cover)"
            timestamp = dateUtil.now()
        }

        // Mark as fired *before* delivery to prevent double-fire if loop cycles quickly.
        // Capture the exact state we're acting on — if _state changes before callback
        // (e.g. user cancels), we don't overwrite the new state.
        val firedState = s.copy(preBolus2FiredMs = now)
        _state = firedState
        persistState()

        commandQueue.bolus(bolusInfo, object : Callback() {
            override fun run() {
                if (!result.success) {
                    aapsLogger.error(LTag.APS, "SmartInsulin PB2 delivery FAILED: ${result.comment}")
                    // Mark as discarded — do NOT retry automatically.
                    // A failed delivery may have partially or fully delivered on some pumps.
                    // Automatic retry risks double-dosing. User must manually deliver if still needed.
                    // Only update state if it hasn't been changed by a cancel or new activation.
                    if (_state == firedState) {
                        _state = firedState.copy(preBolus2FiredMs = -1L)
                        persistState()
                    }
                    uiInteraction.runAlarm(
                        "Pre-bolus 2 delivery failed — please check pump and deliver manually if needed. Reason: ${result.comment}",
                        "SmartMeal pre-bolus 2 failed",
                        app.aaps.core.ui.R.raw.boluserror
                    )
                } else {
                    aapsLogger.debug(LTag.APS, "SmartInsulin PB2 delivered OK: ${s.preBolus2U}U")
                }
            }
        })
    }
}