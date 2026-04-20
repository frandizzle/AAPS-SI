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
import kotlinx.coroutines.runBlocking
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
            put("preBolus3U",       s.preBolus3U)
            put("preBolus3DelayMs", s.preBolus3DelayMs)
            if (s.preBolus3FiredMs != null) put("preBolus3FiredMs", s.preBolus3FiredMs)
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
                preBolus2FiredMs = if (json.has("preBolus2FiredMs")) json.getLong("preBolus2FiredMs") else null,
                preBolus3U       = json.optDouble("preBolus3U", 0.0),
                preBolus3DelayMs = json.optLong("preBolus3DelayMs", 0L),
                preBolus3FiredMs = if (json.has("preBolus3FiredMs")) json.getLong("preBolus3FiredMs") else null
            )
            val remainingMins = (modeExpiryMs - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin: override restored mode=${mode.label} ${remainingMins}min remaining" +
                (if (_state!!.preBolus2Pending) " pb2=${_state!!.preBolus2U}U pending" else "") +
                (if (_state!!.preBolus3Pending) " pb3=${_state!!.preBolus3U}U pending" else ""))
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

        // Mode expired. If PB2 or PB3 is still pending, leave _state intact so
        // onLoopCycle can formally discard and log it rather than silently vanishing.
        if (s.preBolus2Pending || s.preBolus3Pending) return null

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
    override val activePb3DoseU: Double? get() {
        val s = _state ?: return null
        if (activeMealMode == null) return null
        // preBolus3FiredMs > 0 means PB3 was delivered; -1L means cancelled
        return if (s.preBolus3FiredMs != null && s.preBolus3FiredMs!! > 0L && s.preBolus3U > 0.0)
            s.preBolus3U else null
    }

    override val modeTimeRemainingMs: Long get() {
        val s = _state ?: return 0L
        return (s.modeExpiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override val modeStartMs: Long get() = _state?.triggerTimeMs ?: 0L

    override val preBolus2Pending: Boolean
        get() = _state?.preBolus2Pending == true

    override val preBolus3Pending: Boolean
        get() = _state?.preBolus3Pending == true

    override val preBolus2SecondsRemaining: Long? get() {
        val s = _state?.takeIf { it.preBolus2Pending } ?: return null
        return s.preBolus2FireAtMs - System.currentTimeMillis()  // negative = overdue/waiting on safety
    }

    /** PB3 seconds remaining — returns null if PB2 hasn't fired yet (PB3 timer hasn't started).
     *  Once PB2 has fired successfully, returns (fireAt - now), which may be negative if
     *  PB3 is overdue and waiting on safety gates. */
    override val preBolus3SecondsRemaining: Long? get() {
        val s = _state?.takeIf { it.preBolus3Pending } ?: return null
        val fireAt = s.preBolus3FireAtMs ?: return null  // null when PB2 not fired yet
        return fireAt - System.currentTimeMillis()
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
    // Separate cache specifically for PB3 — updated when PB3's onLoopCycle runs, keeps live
    // gate state visible in UI during gate waits. Identical fields to PB2, updated after PB2's
    // own cache is populated (so they reflect the most recent cycle's values).
    @Volatile private var lastPb3BgMgdl        = 0.0
    @Volatile private var lastPb3Iob           = 0.0
    @Volatile private var lastPb3MaxIob        = 0.0
    @Volatile private var lastPb3TargetMgdl    = 108.0
    @Volatile private var lastPb3Delta         = 0.0
    @Volatile private var lastPb3ShortAvgDelta = 0.0

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

    override val preBolus3StatusText: String get() {
        val s   = _state ?: return ""
        if (s.preBolus3U <= 0.0) return ""  // PB3 never requested
        val now = System.currentTimeMillis()
        return when {
            // Discarded (-1L)
            s.preBolus3FiredMs != null && s.preBolus3FiredMs == -1L ->
                "PB3: cancelled"
            // Fired (positive timestamp)
            s.preBolus3FiredMs != null && s.preBolus3FiredMs!! > 0L -> {
                val firedMins = (now - s.preBolus3FiredMs!!) / 60_000
                "PB3: delivered ${firedMins}min ago"
            }
            // PB2 hasn't fired yet — PB3 timer hasn't started counting
            !s.preBolus2FiredSuccessfully -> {
                // Differentiate: PB2 never requested (orphan), PB2 cancelled, or PB2 still pending
                when {
                    s.preBolus2U <= 0.0 ->
                        "PB3: cannot fire (no PB2 scheduled)"
                    s.preBolus2FiredMs != null && s.preBolus2FiredMs == -1L ->
                        "PB3: will cancel (PB2 cancelled)"
                    else ->
                        "PB3: waiting for PB2"
                }
            }
            // PB2 fired — PB3 now has a valid fireAt time
            else -> {
                val fireAt = s.preBolus3FireAtMs!!
                if (now < fireAt) {
                    val secsLeft = (fireAt - now) / 1000
                    if (secsLeft >= 60)
                        "PB3: ${secsLeft / 60}min ${secsLeft % 60}s"
                    else
                        "PB3: ${secsLeft}s"
                } else {
                    val reasons = safetyBlockReasons(
                        lastPb3BgMgdl, lastPb3Iob, lastPb3MaxIob, lastPb3TargetMgdl,
                        lastPb3Delta, lastPb3ShortAvgDelta
                    )
                    if (reasons.isEmpty()) "PB3: waiting for next loop cycle"
                    else "PB3 waiting: ${reasons.joinToString(", ")}"
                }
            }
        }
    }

    override fun activateOverride(
        mode:             MealMode,
        doseU:            Double?,
        carbsG:           Int,
        modeWindowMs:     Long,
        preBolus2U:       Double,
        preBolus2DelayMs: Long,
        preBolus3U:       Double,
        preBolus3DelayMs: Long
    ) {
        val now = System.currentTimeMillis()
        _state = MealOverrideState(
            mode             = mode,
            triggerTimeMs    = now,
            modeExpiryMs     = now + modeWindowMs,
            doseU            = doseU,
            preBolus2U       = preBolus2U,
            preBolus2DelayMs = preBolus2DelayMs,
            preBolus2FiredMs = null,
            preBolus3U       = preBolus3U,
            preBolus3DelayMs = preBolus3DelayMs,
            preBolus3FiredMs = null
        )
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin override: mode=${mode.label} modeTTL=${modeWindowMs / 60_000}min" +
                             (if (preBolus2U > 0.0) " pb2=${preBolus2U}U in ${preBolus2DelayMs / 60_000}min" else "") +
                             (if (preBolus3U > 0.0) " pb3=${preBolus3U}U ${preBolus3DelayMs / 60_000}min after pb2" else ""))
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
        // Cascade: if PB3 is also pending, cancel it too. PB3's timing reference is PB2's
        // fire time, so a cancelled PB2 means PB3 can never have a valid fire time.
        val pb3Cascade = s.preBolus3Pending
        val pb3Fired = if (pb3Cascade) -1L else s.preBolus3FiredMs
        _state = s.copy(
            preBolus2FiredMs = -1L,
            preBolus3FiredMs = pb3Fired
        )
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB2 cancelled by user (mode still active: ${s.mode.label})" +
                             (if (pb3Cascade) " — PB3 cascade cancelled" else ""))
    }

    override fun cancelPreBolus3() {
        val s = _state ?: return
        if (!s.preBolus3Pending) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB3 cancel: nothing pending")
            return
        }
        _state = s.copy(preBolus3FiredMs = -1L)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin PB3 cancelled by user (mode still active: ${s.mode.label})")
    }

    // ── Pre-bolus 2 delivery ──────────────────────────────────────────────────

    override fun onLoopCycle(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        val s = _state ?: return
        // Skip entirely if neither PB2 nor PB3 is pending
        if (!s.preBolus2Pending && !s.preBolus3Pending) return

        // Process PB2 first. If it fires this cycle, PB3 becomes eligible next cycle (its
        // fireAt = pb2FiredMs + pb3Delay). We don't try to fire PB3 in the same cycle as PB2
        // — gives at least one 5-min gap so SMB logic can react to the PB2 bolus first.
        if (s.preBolus2Pending) {
            processPreBolus2(glucoseStatus, iobArray, maxIobU)
            // Re-read state after PB2 handling — it may have fired/failed/cancelled
            return
        }

        // PB2 not pending. Either it fired successfully (PB3 eligible), cancelled (PB3 should
        // have cascade-cancelled already), or was never requested (PB3 can't fire — has no timing
        // reference). Check PB3 now.
        if (s.preBolus3Pending) {
            processPreBolus3(glucoseStatus, iobArray, maxIobU)
        }
    }

    // ── Pre-bolus 2 delivery ──────────────────────────────────────────────────

    private fun processPreBolus2(
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
        val profile       = runBlocking { profileFunction.getProfile() }
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
                aapsLogger.debug(LTag.APS, "SmartInsulin PB2: mode expired, discarding pending PB2" +
                    (if (s.preBolus3Pending) " and cascading cancel to PB3" else ""))
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
                    // Cascade: PB3 can never fire now (no valid timing reference) — cancel it too.
                    // Only update state if it hasn't been changed by a cancel or new activation.
                    if (_state == firedState) {
                        val pb3Cascade = firedState.preBolus3Pending
                        _state = firedState.copy(
                            preBolus2FiredMs = -1L,
                            preBolus3FiredMs = if (pb3Cascade) -1L else firedState.preBolus3FiredMs
                        )
                        persistState()
                        if (pb3Cascade) {
                            aapsLogger.debug(LTag.APS, "SmartInsulin PB3 cascade-cancelled due to PB2 delivery failure")
                        }
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

    // ── Pre-bolus 3 delivery ──────────────────────────────────────────────────

    private fun processPreBolus3(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double
    ) {
        val s = _state ?: return
        if (!s.preBolus3Pending) return

        // PB3 requires PB2 to have been requested in the first place. If PB2 was never
        // scheduled (preBolus2U == 0.0), PB3 has no timing reference and will never fire.
        // Auto-cancel defensively — the UI should prevent this state, but handle it here too.
        if (s.preBolus2U <= 0.0) {
            aapsLogger.error(LTag.APS, "SmartInsulin PB3: no PB2 scheduled — PB3 has no timing reference, auto-cancelling")
            _state = s.copy(preBolus3FiredMs = -1L)
            persistState()
            return
        }

        // PB3 cannot begin counting until PB2 has fired successfully.
        // If PB2 was cancelled (-1L), PB3 should have been cascade-cancelled already —
        // but double-check here defensively and cancel if somehow missed.
        if (!s.preBolus2FiredSuccessfully) {
            if (s.preBolus2FiredMs != null && s.preBolus2FiredMs == -1L) {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB3: PB2 was cancelled, cascading PB3 cancel (defensive)")
                _state = s.copy(preBolus3FiredMs = -1L)
                persistState()
            } else {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB3: waiting for PB2 to fire")
            }
            return
        }

        val fireAt = s.preBolus3FireAtMs ?: return  // should be non-null once PB2 fired successfully
        val now    = System.currentTimeMillis()

        // Not time yet
        if (now < fireAt) {
            val minsLeft = (fireAt - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin PB3: ${minsLeft}min until scheduled delivery")
            return
        }

        // ── Safety checks (identical gates to PB2) ───────────────────────────
        val currentBgMgdl = glucoseStatus.glucose
        val currentIob    = iobArray.firstOrNull()?.iob ?: 0.0
        val profile       = runBlocking { profileFunction.getProfile() }
        val profileTarget = profile?.getTargetMgdl() ?: 108.0

        // Cache for preBolus3StatusText — separate from PB2 cache so both stay accurate
        lastPb3BgMgdl        = currentBgMgdl
        lastPb3Iob           = currentIob
        lastPb3MaxIob        = maxIobU
        lastPb3TargetMgdl    = profileTarget
        lastPb3Delta         = glucoseStatus.delta
        lastPb3ShortAvgDelta = glucoseStatus.shortAvgDelta

        val reasons = safetyBlockReasons(
            bgMgdl        = currentBgMgdl,
            iob           = currentIob,
            maxIob        = maxIobU,
            targetMgdl    = profileTarget,
            delta         = glucoseStatus.delta,
            shortAvgDelta = glucoseStatus.shortAvgDelta
        )

        if (reasons.isNotEmpty()) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB3 BLOCKED: ${reasons.joinToString(", ")}")
            // If mode has already expired, discard pending PB3 and clean up state entirely
            if (now > s.modeExpiryMs) {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB3: mode expired, discarding pending PB3")
                _state = null
                persistState()
            }
            return
        }

        // ── Fire ─────────────────────────────────────────────────────────────
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB3 FIRING: ${s.preBolus3U}U bg=${String.format("%.1f", currentBgMgdl/18.0)} " +
                             "iob=${String.format("%.2f", currentIob)}U target=${String.format("%.1f", profileTarget/18.0)}")

        val bolusInfo = DetailedBolusInfo().apply {
            insulin   = s.preBolus3U
            notes     = "SmartMeal ${s.mode.label} pre-bolus 3 (late protein/fat cover)"
            timestamp = dateUtil.now()
        }

        // Mark as fired *before* delivery to prevent double-fire if loop cycles quickly.
        val firedState = s.copy(preBolus3FiredMs = now)
        _state = firedState
        persistState()

        commandQueue.bolus(bolusInfo, object : Callback() {
            override fun run() {
                if (!result.success) {
                    aapsLogger.error(LTag.APS, "SmartInsulin PB3 delivery FAILED: ${result.comment}")
                    // Mark as discarded — same policy as PB2 failure: no automatic retry,
                    // user must manually deliver if needed.
                    if (_state == firedState) {
                        _state = firedState.copy(preBolus3FiredMs = -1L)
                        persistState()
                    }
                    uiInteraction.runAlarm(
                        "Pre-bolus 3 delivery failed — please check pump and deliver manually if needed. Reason: ${result.comment}",
                        "SmartMeal pre-bolus 3 failed",
                        app.aaps.core.ui.R.raw.boluserror
                    )
                } else {
                    aapsLogger.debug(LTag.APS, "SmartInsulin PB3 delivered OK: ${s.preBolus3U}U")
                }
            }
        })
    }
}