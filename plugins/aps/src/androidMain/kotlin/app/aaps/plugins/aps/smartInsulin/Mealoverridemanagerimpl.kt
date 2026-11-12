package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.keys.StringNonKey

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.MealOverrideState
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs
import kotlin.math.floor

@SingleIn(AppScope::class)
class MealOverrideManagerImpl @Inject constructor(
    private val aapsLogger:      AAPSLogger,
    private val sp:              KeyValueStore,
    private val commandQueue:    CommandQueue,
    private val profileFunction: ProfileFunction,
    private val dateUtil:        DateUtil,
    private val uiInteraction:   UiInteraction,
    @ApplicationScope private val appScope: CoroutineScope
) : MealOverrideManager {

    @Volatile private var _state: MealOverrideState? = null

    /** A mode set to [followWithProteinFat] has run out, and Protein/Fat has not been started yet. */
    @Volatile private var pfFollowOnDue = false

    init {
        restoreState()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persistState() {
        val s = _state
        if (s == null) {
            sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, "") }
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
            put("duraEnabled",      s.duraEnabled)
            put("duraFloorMgdl",    s.duraFloorMgdl)
            put("duraStrength",     s.duraStrength)
            put("pfAfter",          s.pfAfter)
            put("addedU",           s.addedU)
            put("afterActivityPct", s.afterActivityPct)
            put("preBolus1RestU",   s.preBolus1RestU)
            if (s.preBolus1RestFiredMs != null) put("preBolus1RestFiredMs", s.preBolus1RestFiredMs)
            if (s.preBolus1PlannedU != null) put("preBolus1PlannedU", s.preBolus1PlannedU)
            put("preBolus2Pieces",  s.preBolus2Pieces)
            put("preBolus2PiecesGiven", s.preBolus2PiecesGiven)
            put("preBolus2GivenU",  s.preBolus2GivenU)
            if (s.preBolus2LastPieceMs != null) put("preBolus2LastPieceMs", s.preBolus2LastPieceMs)
            put("preBolus2LoopU",   s.preBolus2LoopU)
            put("extendedMs",       s.extendedMs)
            put("eatDelayMs",       s.eatDelayMs)
            put("preBolus2DroppedU", s.preBolus2DroppedU)
            put("preBolus3PiecesGiven", s.preBolus3PiecesGiven)
            put("preBolus3GivenU",  s.preBolus3GivenU)
            if (s.preBolus3LastPieceMs != null) put("preBolus3LastPieceMs", s.preBolus3LastPieceMs)
            put("preBolus3DroppedU", s.preBolus3DroppedU)
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, json.toString()) }
        aapsLogger.debug(LTag.APS, "SmartInsulin: override state persisted mode=${s.mode.label}")
    }

    private fun restoreState() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinOverrideState.key, StringNonKey.ApsSmartInsulinOverrideState.defaultValue)
        if (raw.isNullOrBlank()) return
        try {
            val json         = JSONObject(raw)
            val mode         = MealMode.valueOf(json.getString("mode"))
            val now          = System.currentTimeMillis()
            val modeExpiryMs = json.getLong("modeExpiryMs")
            if (modeExpiryMs <= now) {
                sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, "") }
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
                preBolus3FiredMs = if (json.has("preBolus3FiredMs")) json.getLong("preBolus3FiredMs") else null,
                duraEnabled      = json.optBoolean("duraEnabled", false),
                duraFloorMgdl    = json.optDouble("duraFloorMgdl", 0.0),
                duraStrength     = json.optDouble("duraStrength", 1.0),
                pfAfter          = json.optBoolean("pfAfter", false),
                addedU           = json.optDouble("addedU", 0.0),
                afterActivityPct = json.optInt("afterActivityPct", 0),
                preBolus1RestU   = json.optDouble("preBolus1RestU", 0.0),
                preBolus1RestFiredMs = if (json.has("preBolus1RestFiredMs")) json.getLong("preBolus1RestFiredMs") else null,
                preBolus1PlannedU = if (json.has("preBolus1PlannedU")) json.getDouble("preBolus1PlannedU") else null,
                preBolus2Pieces  = json.optInt("preBolus2Pieces", 0),
                preBolus2PiecesGiven = json.optInt("preBolus2PiecesGiven", 0),
                preBolus2GivenU  = json.optDouble("preBolus2GivenU", 0.0),
                preBolus2LastPieceMs = if (json.has("preBolus2LastPieceMs")) json.getLong("preBolus2LastPieceMs") else null,
                preBolus2LoopU   = json.optDouble("preBolus2LoopU", 0.0),
                extendedMs       = json.optLong("extendedMs", 0L),
                eatDelayMs       = json.optLong("eatDelayMs", 0L),
                preBolus2DroppedU = json.optDouble("preBolus2DroppedU", 0.0),
                preBolus3PiecesGiven = json.optInt("preBolus3PiecesGiven", 0),
                preBolus3GivenU  = json.optDouble("preBolus3GivenU", 0.0),
                preBolus3LastPieceMs = if (json.has("preBolus3LastPieceMs")) json.getLong("preBolus3LastPieceMs") else null,
                preBolus3DroppedU = json.optDouble("preBolus3DroppedU", 0.0)
            )
            val remainingMins = (modeExpiryMs - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin: override restored mode=${mode.label} ${remainingMins}min remaining" +
                (if (_state!!.preBolus2Pending) " pb2=${_state!!.preBolus2U}U pending" else "") +
                (if (_state!!.preBolus3Pending) " pb3=${_state!!.preBolus3U}U pending" else ""))
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SmartInsulin: failed to restore override state: ${e.message}")
            sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, "") }
        }
    }

    // ── Interface ─────────────────────────────────────────────────────────────

    override val activeMealMode: MealMode? get() {
        val s   = _state ?: return null
        val now = System.currentTimeMillis()
        if (s.modeExpiryMs > now) return s.mode

        // A meal with a slow fat and protein tail hands over to Protein/Fat now, see takeProteinFatFollowOn.
        if (s.pfAfter) {
            pfFollowOnDue = true
            _state = s.copy(pfAfter = false)
            persistState()
            aapsLogger.debug(LTag.APS, "SmartInsulin mode ${s.mode.label} expired - Protein/Fat follows")
        }

        // Mode expired. If PB2 or PB3 is still pending, leave _state intact so
        // onLoopCycle can formally discard and log it rather than silently vanishing.
        if (s.preBolus2Pending || s.preBolus3Pending) return null

        aapsLogger.debug(LTag.APS, "SmartInsulin mode ${s.mode.label} expired")
        _state = null
        persistState()
        return null
    }

    override val activeState: MealOverrideState? get() = if (activeMealMode != null) _state else null

    override val activeIsfMultiplier: Double get() = 1.0

    override val activeDuraEnabled: Boolean get() = activeMealMode != null && _state?.duraEnabled == true
    override val activeDuraFloorMgdl: Double get() = if (activeMealMode != null) _state?.duraFloorMgdl ?: 0.0 else 0.0
    override val activeDuraStrength: Double get() = if (activeMealMode != null) _state?.duraStrength ?: 1.0 else 1.0

    override val activeDoseU: Double? get() = if (activeMealMode != null) _state?.doseU else null
    override val activePb2DoseU: Double? get() {
        val s = _state ?: return null
        if (activeMealMode == null) return null
        // preBolus2FiredMs > 0 means PB2 was delivered; -1L means cancelled. Less what pieces led by BG left out.
        return if (s.preBolus2FiredMs != null && s.preBolus2FiredMs!! > 0L && s.preBolus2U > 0.0)
            s.preBolus2U - s.preBolus2DroppedU else null
    }
    override val activePb3DoseU: Double? get() {
        val s = _state ?: return null
        if (activeMealMode == null) return null
        // preBolus3FiredMs > 0 means its pieces are done; what they gave, which BG may have kept below the plan.
        return if (s.preBolus3FiredMs != null && s.preBolus3FiredMs!! > 0L && s.preBolus3U > 0.0)
            s.preBolus3GivenU else null
    }

    override val modeTimeRemainingMs: Long get() {
        val s = _state ?: return 0L
        return (s.modeExpiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override val modeStartMs: Long get() = _state?.triggerTimeMs ?: 0L

    override val preBolus1RestPending: Boolean
        get() = activeMealMode != null && _state?.preBolus1RestPending == true

    /**
     * The meal state while its mode runs; null once it has ended, also in the minutes before the next loop cycle drops it
     * (see onLoopCycle). What the overview and the SI tab show reads this, so an ended meal does not stay on screen.
     */
    private val liveState: MealOverrideState?
        get() = _state?.takeIf { System.currentTimeMillis() <= it.modeExpiryMs }

    override val preBolus2Pending: Boolean
        get() = liveState?.preBolus2Pending == true

    override val preBolus3Pending: Boolean
        get() = liveState?.preBolus3Pending == true

    override val preBolus2SecondsRemaining: Long? get() {
        val s = liveState?.takeIf { it.preBolus2Pending } ?: return null
        return (s.preBolus2NextAtMs - System.currentTimeMillis()) / 1000
    }

    /** PB3 seconds remaining — returns null if PB2 hasn't fired yet (PB3 timer hasn't started).
     *  Once PB2 has fired successfully, returns (fireAt - now), which may be negative if
     *  PB3 is overdue and waiting on safety gates. */
    override val preBolus3SecondsRemaining: Long? get() {
        val s = liveState?.takeIf { it.preBolus3Pending } ?: return null
        val fireAt = s.preBolus3NextAtMs ?: return null  // null when PB2 not fired yet; then the next piece's time
        return (fireAt - System.currentTimeMillis()) / 1000
    }

    /** Evaluate current safety blocks without needing glucoseStatus (uses last known values). */
    private fun safetyBlockReasons(
        bgMgdl:        Double,
        iob:           Double,
        maxIob:        Double,
        targetMgdl:    Double,
        delta:         Double,
        shortAvgDelta: Double,
        doseU:         Double
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (bgMgdl < MealOverrideManager.MIN_BG_FOR_PB2_MGDL)
            reasons += "BG too low"
        if (bgMgdl <= targetMgdl)
            reasons += "BG not above target"
        if (iob >= maxIob * MealOverrideManager.MAX_IOB_HEADROOM_RATIO)
            reasons += "IOB too high"
        // IOB is checked before the dose above; this keeps a big pre-bolus from taking IOB past max.
        if (iob + doseU > maxIob)
            reasons += "IOB would go past max"
        if (delta < MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)
            reasons += "BG falling"
        if (shortAvgDelta < MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)
            reasons += "Trend falling"
        // The gates above all read glucose state, which is what a pre-bolus decision was based on
        // when it was scheduled. They say nothing about what the loop has since decided to do.
        // If the loop is suspending or working through a post-low rebound, a scheduled fixed dose
        // is directly opposed to the decision the loop just made — hold it and re-check next cycle
        // (the mode-expiry path above eventually discards it if the window closes).
        if (loopRestraining)
            reasons += "Loop restraining insulin"
        return reasons
    }

    /** Why a piece led by BG does not fire: the usual gates, and BG must ask for it, see [MealOverrideManager.bgLedShare]. */
    private fun bgLedBlockReasons(
        bgMgdl: Double, iob: Double, maxIob: Double, targetMgdl: Double, delta: Double, shortAvgDelta: Double, doseU: Double
    ): List<String> {
        val reasons = safetyBlockReasons(bgMgdl, iob, maxIob, targetMgdl, delta, shortAvgDelta, doseU).toMutableList()
        if (MealOverrideManager.bgLedShare(bgMgdl, targetMgdl, delta) == null) reasons += "BG not 1 mmol above target or rising"
        return reasons
    }

    /**
     * A piece led by BG at [share] of [pieceU], rounded down to a bolus step, but at least one step so a small last piece is
     * not a bolus of nothing. The rest of the piece is left out.
     */
    private fun bgLedDoseU(pieceU: Double, share: Double): Double {
        val step = MealOverrideManager.SPREAD_STEP_U
        return (floor(pieceU * share / step + ROUNDING_U) * step).coerceAtLeast(minOf(step, pieceU)).coerceAtMost(pieceU)
    }

    /**
     * Why the rest of pre-bolus 1 does not fire yet. Like pre-bolus 2, but BG need not be above target: it
     * must be rising, which is the sign that the food is coming in. When not all of [restU] fits under max IOB it gives
     * [doseU], the part that does, see [restDoseU]; only a part too small to be worth a bolus waits.
     */
    private fun restBlockReasons(bgMgdl: Double, iob: Double, maxIob: Double, delta: Double, shortAvgDelta: Double, restU: Double, doseU: Double): List<String> {
        val reasons = mutableListOf<String>()
        if (bgMgdl < MealOverrideManager.MIN_BG_FOR_PB2_MGDL) reasons += "BG too low"
        if (delta < MealOverrideManager.PB1_REST_RISE_MGDL || shortAvgDelta <= 0.0) reasons += "BG not rising yet"
        if (iob >= maxIob * MealOverrideManager.MAX_IOB_HEADROOM_RATIO) reasons += "IOB too high"
        if (doseU < minOf(restU, MealOverrideManager.PB1_REST_MIN_U) - ROUNDING_U) reasons += "IOB would go past max"
        if (loopRestraining) reasons += "Loop restraining insulin"
        return reasons
    }

    // Set from onLoopCycle each cycle — see the loopRestraining parameter on MealOverrideManager.
    @Volatile private var loopRestraining = false

    // Set from onLoopCycle each cycle: the pre-bolus it started to give, see MealOverrideManager.preBolusThisCycleU.
    @Volatile override var preBolusThisCycleU = 0.0
        private set

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

    override val pb2GateData: MealOverrideManager.Pb2GateData?
        get() {
            val s = liveState ?: return null
            // Only show PB2 gate data if PB2 was actually requested (dose > 0 and pending)
            if (s.preBolus2U <= 0.0 || !s.preBolus2Pending) return null
            if (lastMaxIob <= 0.0) return null
            return MealOverrideManager.Pb2GateData(
                isMmol            = false,
                profileTargetMgdl = lastTargetMgdl,
                bgMgdl            = lastBgMgdl,
                maxIobU           = lastMaxIob,
                iobU              = lastIob,
                deltaMgdl         = lastDelta,
                shortAvgDeltaMgdl = lastShortAvgDelta,
                doseU             = if (s.preBolus2Spread) s.preBolus2NextPieceU else s.preBolus2U,
                loopRestraining   = loopRestraining,
                bgLed             = s.preBolus2BgLed
            )
        }

    override val pb3GateData: MealOverrideManager.Pb2GateData?
        get() {
            val s = liveState ?: return null
            // Only show PB3 gate data if PB3 was actually requested (dose > 0 and pending)
            if (s.preBolus3U <= 0.0 || !s.preBolus3Pending) return null
            if (lastPb3MaxIob <= 0.0) return null
            return MealOverrideManager.Pb2GateData(
                isMmol            = false,
                profileTargetMgdl = lastPb3TargetMgdl,
                bgMgdl            = lastPb3BgMgdl,
                maxIobU           = lastPb3MaxIob,
                iobU              = lastPb3Iob,
                deltaMgdl         = lastPb3Delta,
                shortAvgDeltaMgdl = lastPb3ShortAvgDelta,
                doseU             = s.preBolus3NextPieceU,
                loopRestraining   = loopRestraining,
                bgLed             = true
            )
        }

    override val preBolus1RestStatusText: String get() {
        val s = _state ?: return ""
        if (s.preBolus1RestU <= 0.0 || activeMealMode == null) return ""
        val now = System.currentTimeMillis()
        val rest = "PB1 rest (${"%.2f".format(s.preBolus1RestU)} U)"
        val fired = s.preBolus1RestFiredMs
        val fromMs = s.eatAtMs + MealOverrideManager.PB1_REST_FROM_MIN * 60_000L
        return when {
            fired == -1L -> "PB1 rest: cancelled"
            fired == -2L -> "PB1 rest: not given, BG did not rise in time"
            fired != null && fired > 0L -> "PB1 rest: delivered ${(now - fired) / 60_000}min ago"
            now < fromMs -> "$rest: checks BG in ${(fromMs - now + 59_999) / 60_000}min"
            else -> {
                val reasons = restBlockReasons(lastBgMgdl, lastIob, lastMaxIob, lastDelta, lastShortAvgDelta, s.preBolus1RestU, restDoseU(s.preBolus1RestU, lastIob, lastMaxIob))
                val leftMin = (s.eatAtMs + MealOverrideManager.PB1_REST_UNTIL_MIN * 60_000L - now).coerceAtLeast(0L) / 60_000
                if (reasons.isEmpty()) "$rest: waiting for next loop cycle"
                else "$rest waiting: ${reasons.joinToString(", ")} (${leftMin}min left)"
            }
        }
    }

    override val preBolus2StatusText: String get() {
        val s   = liveState ?: return ""
        if (s.preBolus2U <= 0.0) return ""  // PB2 never requested
        val now = System.currentTimeMillis()
        // What is still to come shows its dose, for example "PB2 (2.40 U): 32min 57s", or in pieces
        // "PB2 piece 2 of 6 (0.55 U): 12min 3s".
        val doseU = if (s.preBolus2Spread) s.preBolus2NextPieceU else s.preBolus2U
        val loop = if (s.preBolus2LoopU > 0.0) ", loop gave ${"%.2f".format(s.preBolus2LoopU)} U" else ""
        val led = if (s.preBolus2BgLed) ", led by BG" else ""
        val pb2 = if (s.preBolus2Spread) "PB2 piece ${s.preBolus2PiecesGiven + 1} of ${s.preBolus2Pieces} (${"%.2f".format(doseU)} U$loop$led)"
                  else "PB2 (${"%.2f".format(s.preBolus2U)} U)"
        val nextAt = s.preBolus2NextAtMs
        return when {
            // Discarded (-1L) or already fired (positive timestamp)
            s.preBolus2FiredMs != null && s.preBolus2FiredMs == -1L ->
                if (s.preBolus2GivenU > 0.0) "PB2: stopped after ${"%.2f".format(s.preBolus2GivenU)} U" else "PB2: cancelled"
            s.preBolus2FiredMs != null && s.preBolus2FiredMs!! > 0L -> {
                val firedMins = (now - s.preBolus2FiredMs!!) / 60_000
                "PB2: delivered ${firedMins}min ago"
            }
            // Counting down
            now < nextAt -> {
                val secsLeft = (nextAt - now) / 1000
                if (secsLeft >= 60)
                    "$pb2: ${secsLeft / 60}min ${secsLeft % 60}s"
                else
                    "$pb2: ${secsLeft}s"
            }
            // Time elapsed — show safety block reasons if any
            else -> {
                val reasons =
                    if (s.preBolus2BgLed) bgLedBlockReasons(lastBgMgdl, lastIob, lastMaxIob, lastTargetMgdl, lastDelta, lastShortAvgDelta, doseU)
                    else safetyBlockReasons(lastBgMgdl, lastIob, lastMaxIob, lastTargetMgdl, lastDelta, lastShortAvgDelta, doseU)
                if (reasons.isEmpty()) "$pb2: waiting for next loop cycle"
                else "$pb2 waiting: ${reasons.joinToString(", ")}"
            }
        }
    }

    override val preBolus3StatusText: String get() {
        val s   = liveState ?: return ""
        if (s.preBolus3U <= 0.0) return ""  // PB3 never requested
        val now = System.currentTimeMillis()
        // Waiting for pre-bolus 2 it shows the whole dose; once its time has come, the next piece, for example
        // "PB3 piece 2 of 6 (0.70 U, led by BG)".
        val pb3 = if (s.preBolus2FiredSuccessfully)
            "PB3 piece ${s.preBolus3PiecesGiven + 1} of ${s.preBolus3Pieces} (${"%.2f".format(s.preBolus3NextPieceU)} U, led by BG)"
        else "PB3 (${"%.2f".format(s.preBolus3U)} U)"
        return when {
            // Discarded (-1L)
            s.preBolus3FiredMs != null && s.preBolus3FiredMs == -1L ->
                if (s.preBolus3GivenU > 0.0) "PB3: stopped after ${"%.2f".format(s.preBolus3GivenU)} U" else "PB3: cancelled"
            // Done (positive timestamp): what the pieces gave, which BG may have kept below the plan
            s.preBolus3FiredMs != null && s.preBolus3FiredMs!! > 0L -> {
                val firedMins = (now - s.preBolus3FiredMs!!) / 60_000
                "PB3: done ${firedMins}min ago, gave ${"%.2f".format(s.preBolus3GivenU)} U of ${"%.2f".format(s.preBolus3U)} U"
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
                        "$pb3: waiting for PB2"
                }
            }
            // PB2 fired — PB3 now has a valid time for its next piece
            else -> {
                val fireAt = s.preBolus3NextAtMs!!
                if (now < fireAt) {
                    val secsLeft = (fireAt - now) / 1000
                    if (secsLeft >= 60)
                        "$pb3: ${secsLeft / 60}min ${secsLeft % 60}s"
                    else
                        "$pb3: ${secsLeft}s"
                } else {
                    val reasons = bgLedBlockReasons(
                        lastPb3BgMgdl, lastPb3Iob, lastPb3MaxIob, lastPb3TargetMgdl,
                        lastPb3Delta, lastPb3ShortAvgDelta, s.preBolus3NextPieceU
                    )
                    if (reasons.isEmpty()) "$pb3: waiting for next loop cycle"
                    else "$pb3 waiting: ${reasons.joinToString(", ")}"
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
        preBolus3DelayMs: Long,
        duraEnabled:      Boolean,
        duraFloorMgdl:    Double,
        duraStrength:     Double,
        eatDelayMs:       Long
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
            preBolus3FiredMs = null,
            duraEnabled      = duraEnabled,
            duraFloorMgdl    = duraFloorMgdl,
            duraStrength     = duraStrength,
            eatDelayMs       = eatDelayMs.coerceAtLeast(0L)
        )
        pfFollowOnDue = false  // a new mode owns the time now
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin override: mode=${mode.label} modeTTL=${modeWindowMs / 60_000}min" +
                             (if (preBolus2U > 0.0) " pb2=${preBolus2U}U in ${preBolus2DelayMs / 60_000}min" else "") +
                             (if (preBolus3U > 0.0) " pb3=${preBolus3U}U ${preBolus3DelayMs / 60_000}min after pb2" else "") +
                             (if (duraEnabled) " dura=on floor=${duraFloorMgdl}mg/dL strength=${duraStrength}" else "") +
                             (if (eatDelayMs > 0L) " eat in ${eatDelayMs / 60_000}min" else ""))
    }

    override fun addToMeal(
        addedU:        Double,
        preBolus2AddU: Double,
        preBolus3AddU: Double,
        modeEndMs:     Long,
        duraEnabled:   Boolean,
        duraFloorMgdl: Double,
        duraStrength:  Double,
        pfAfter:       Boolean
    ): Boolean {
        if (activeMealMode == null) return false
        val s = _state ?: return false
        // A pre-bolus that has fired or was cancelled is not brought back: the loop covers that part.
        val pb2Add = if (s.preBolus2Pending) preBolus2AddU.coerceAtLeast(0.0) else 0.0
        val pb3Add = if (s.preBolus3Pending) preBolus3AddU.coerceAtLeast(0.0) else 0.0
        val duraNew = duraEnabled && !s.duraEnabled
        _state = s.copy(
            addedU        = s.addedU + addedU.coerceAtLeast(0.0),
            preBolus2U    = s.preBolus2U + pb2Add,
            preBolus3U    = s.preBolus3U + pb3Add,
            modeExpiryMs  = maxOf(s.modeExpiryMs, modeEndMs),
            duraEnabled   = s.duraEnabled || duraEnabled,
            duraFloorMgdl = if (duraNew) duraFloorMgdl else s.duraFloorMgdl,
            duraStrength  = if (duraNew) duraStrength else s.duraStrength,
            pfAfter       = s.pfAfter || pfAfter
        )
        persistState()
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin add to meal ${s.mode.label}: now=${addedU}U pb2+=${pb2Add}U pb3+=${pb3Add}U" +
                             (if (preBolus2AddU > pb2Add) " (pb2 no longer waits, ${preBolus2AddU}U left to the loop)" else "") +
                             (if (preBolus3AddU > pb3Add) " (pb3 no longer waits, ${preBolus3AddU}U left to the loop)" else "") +
                             " ends in ${(_state!!.modeExpiryMs - System.currentTimeMillis()) / 60_000}min" +
                             (if (duraNew) " dura=on" else "") + (if (pfAfter) " then P/F" else ""))
        return true
    }

    override fun cancelOverride() {
        aapsLogger.debug(LTag.APS, "SmartInsulin override cancelled")
        _state = null
        pfFollowOnDue = false
        persistState()
    }

    override fun followWithProteinFat() {
        val s = _state ?: return
        _state = s.copy(pfAfter = true)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin override: Protein/Fat follows ${s.mode.label}")
    }

    override fun takeProteinFatFollowOn(): Boolean {
        activeMealMode  // notices a mode that has just run out
        if (!pfFollowOnDue) return false
        pfFollowOnDue = false
        return true
    }

    override fun setAfterActivityCut(pct: Int) {
        val s = _state ?: return
        _state = s.copy(afterActivityPct = pct.coerceIn(0, IntKey.ApsSmartInsulinAfterActivityMaxPct.max))
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin override: ${s.mode.label} after activity -$pct%")
    }

    override fun setPreBolus1Rest(restU: Double) {
        val s = _state ?: return
        if (restU <= 0.0) return
        _state = s.copy(preBolus1RestU = restU, preBolus1RestFiredMs = null)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin override: ${s.mode.label} pre-bolus 1 rest ${restU}U once BG rises")
    }

    override fun spreadPreBoluses(pieces: Int) {
        val s = _state ?: return
        if (pieces < 2 || !s.preBolus2Pending) return
        // Only pre-bolus 2 is given in pieces. Pre-bolus 3 stays its own dose at its own time, for the fat and protein that
        // come in 3 to 6 hours after the meal: folded into the pieces, it was all given within 2 hours of a turkey dinner
        // with 4 g of carbs, and nothing was left for the wave that came later (10 Nov).
        _state = s.copy(preBolus2Pieces = pieces)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin override: ${s.mode.label} pre-bolus 2 ${s.preBolus2U}U in $pieces pieces")
    }

    override fun countLoopSmb(smbU: Double) {
        val s = _state ?: return
        if (!s.preBolus2Spread || !s.preBolus2Pending) return
        // Never more than the pieces had left, so a piece already given is never taken back.
        val loopU = smbU.coerceIn(0.0, (s.preBolus2U - s.preBolus2GivenU).coerceAtLeast(0.0))
        if (abs(loopU - s.preBolus2LoopU) < ROUNDING_U) return
        val done = s.preBolus2U - s.preBolus2GivenU - loopU < MealOverrideManager.SPREAD_STEP_U
        _state = s.copy(preBolus2LoopU = loopU, preBolus2FiredMs = if (done) System.currentTimeMillis() else null)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin PB2: loop gave ${"%.2f".format(loopU)}U of it" + if (done) ", pre-bolus 2 done" else "")
    }

    override fun extendWhileFoodComes(nowMs: Long, shortAvgDeltaMgdl: Double, foodLeftG: Double?): MealOverrideManager.Extension? {
        val s = _state ?: return null
        if (s.mode.isUam) return null
        val leftMs = s.modeExpiryMs - nowMs
        if (leftMs <= 0L || leftMs > MealOverrideManager.EXTEND_CHECK_MIN * 60_000L) return null
        val why = when {
            shortAvgDeltaMgdl >= MealOverrideManager.EXTEND_RISE_MGDL     -> MealOverrideManager.Extension.BG_RISING
            (foodLeftG ?: 0.0) > MealOverrideManager.EXTEND_FOOD_LEFT_G -> MealOverrideManager.Extension.FOOD_LEFT
            else                                                         -> return null
        }
        val latestMs = minOf(
            s.modeExpiryMs - s.extendedMs + MealOverrideManager.EXTEND_MAX_MIN * 60_000L,
            s.triggerTimeMs + MealOverrideManager.MAX_MODE_MIN * 60_000L
        )
        val expiryMs = minOf(nowMs + MealOverrideManager.EXTEND_STEP_MIN * 60_000L, latestMs)
        if (expiryMs <= s.modeExpiryMs) return null
        _state = s.copy(modeExpiryMs = expiryMs, extendedMs = s.extendedMs + expiryMs - s.modeExpiryMs)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin mode ${s.mode.label} made longer ($why): ends in ${(expiryMs - nowMs) / 60_000L} min")
        return why
    }

    override fun scaleWaitingPreBoluses(carbsFactor: Double, fatProteinFactor: Double, maxPreBolusU: Double) {
        val s = _state ?: return
        // Rounded down to a bolus step most pumps can give, so a smaller meal never rounds up.
        fun scaled(u: Double, factor: Double): Double {
            val capU = if (factor > 1.0) maxOf(u, maxPreBolusU) else u
            val step = MealOverrideManager.SPREAD_STEP_U
            return floor((u * factor).coerceIn(0.0, capU) / step + ROUNDING_U) * step
        }
        val restU = if (s.preBolus1RestPending) scaled(s.preBolus1RestU, carbsFactor) else s.preBolus1RestU
        // In pieces only what the pieces still have to give changes: what they and the loop gave, and what BG left out, stays.
        val doneU = s.preBolus2GivenU + s.preBolus2LoopU + s.preBolus2DroppedU
        val pb2U = if (s.preBolus2Pending) doneU + scaled(s.preBolus2LeftU, fatProteinFactor) else s.preBolus2U
        val pb2Done = s.preBolus2Spread && s.preBolus2Pending && pb2U - doneU < MealOverrideManager.SPREAD_STEP_U
        val done3U = s.preBolus3GivenU + s.preBolus3DroppedU
        val pb3U = if (s.preBolus3Pending) done3U + scaled(s.preBolus3LeftU, fatProteinFactor) else s.preBolus3U
        val pb3Done = s.preBolus3Pending && s.preBolus3PiecesGiven > 0 && pb3U - done3U < MealOverrideManager.SPREAD_STEP_U
        if (abs(restU - s.preBolus1RestU) < ROUNDING_U && abs(pb2U - s.preBolus2U) < ROUNDING_U && abs(pb3U - s.preBolus3U) < ROUNDING_U) return
        _state = s.copy(
            preBolus1RestU = restU,
            preBolus2U = pb2U,
            preBolus2FiredMs = if (pb2Done) System.currentTimeMillis() else s.preBolus2FiredMs,
            preBolus3U = pb3U,
            preBolus3FiredMs = if (pb3Done) System.currentTimeMillis() else s.preBolus3FiredMs
        )
        persistState()
        aapsLogger.debug(
            LTag.APS, "SmartInsulin ${s.mode.label}: food changed, waiting pre-boluses now rest ${"%.2f".format(restU)}U, " +
                "pb2 ${"%.2f".format(pb2U)}U, pb3 ${"%.2f".format(pb3U)}U"
        )
    }

    override fun preBolus1Delivered(deliveredU: Double) {
        val s = _state ?: return
        val doseU = s.doseU ?: return
        if (deliveredU >= doseU - STOPPED_MIN_DIFF_U) return
        val given = deliveredU.coerceAtLeast(0.0)
        _state = s.copy(doseU = given, preBolus1PlannedU = s.preBolus1PlannedU ?: doseU)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin override: ${s.mode.label} pre-bolus 1 gave ${given}U of ${s.preBolus1PlannedU ?: doseU}U")
    }

    override fun cancelPreBolus1Rest() {
        val s = _state ?: return
        if (!s.preBolus1RestPending) return
        _state = s.copy(preBolus1RestFiredMs = -1L)
        persistState()
        aapsLogger.debug(LTag.APS, "SmartInsulin PB1 rest cancelled by user (mode still active: ${s.mode.label})")
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
        glucoseStatus:   GlucoseStatus,
        iobArray:        Array<IobTotal>,
        maxIobU:         Double,
        profile:         app.aaps.core.interfaces.profile.Profile?,
        loopRestraining: Boolean
    ) {
        this.loopRestraining = loopRestraining
        preBolusThisCycleU = 0.0
        val s = _state ?: return
        // A meal mode that has ended takes its waiting pre-boluses with it: what is left is dropped, never given after the
        // meal. It was only dropped when a piece was due and blocked: a piece still minutes away kept the meal on screen,
        // and one due with the gates open would have fired after the meal (12 Nov, pre-bolus 3 "in 40s" in Fasting).
        if (System.currentTimeMillis() > s.modeExpiryMs) {
            if (s.preBolus2Pending || s.preBolus3Pending)
                aapsLogger.debug(LTag.APS, "SmartInsulin ${s.mode.label} ended: waiting pre-boluses dropped" +
                    (if (s.preBolus2Pending) ", PB2 ${"%.2f".format(s.preBolus2WaitingU)}U left" else "") +
                    (if (s.preBolus3Pending) ", PB3 ${"%.2f".format(s.preBolus3WaitingU)}U left" else ""))
            _state = null
            persistState()
            return
        }
        // The rest of pre-bolus 1 comes first. One bolus per cycle, so the loop sees it before anything else fires.
        if (s.preBolus1RestPending && processPreBolus1Rest(glucoseStatus, iobArray, maxIobU)) return
        // Skip entirely if neither PB2 nor PB3 is pending
        if (!s.preBolus2Pending && !s.preBolus3Pending) return

        // Process PB2 first. If it fires this cycle, PB3 becomes eligible next cycle (its
        // fireAt = pb2FiredMs + pb3Delay). We don't try to fire PB3 in the same cycle as PB2
        // — gives at least one 5-min gap so SMB logic can react to the PB2 bolus first.
        if (s.preBolus2Pending) {
            processPreBolus2(glucoseStatus, iobArray, maxIobU, profile)
            // Re-read state after PB2 handling — it may have fired/failed/cancelled
            return
        }

        // PB2 not pending. Either it fired successfully (PB3 eligible), cancelled (PB3 should
        // have cascade-cancelled already), or was never requested (PB3 can't fire — has no timing
        // reference). Check PB3 now.
        if (s.preBolus3Pending) {
            processPreBolus3(glucoseStatus, iobArray, maxIobU, profile)
        }
    }

    // ── Pre-bolus 1 rest delivery ─────────────────────────────────────────────

    /** Fires the rest of pre-bolus 1 once BG rises, inside its window. True when it fired this cycle. */
    private fun processPreBolus1Rest(glucoseStatus: GlucoseStatus, iobArray: Array<IobTotal>, maxIobU: Double): Boolean {
        val s = _state ?: return false
        if (!s.preBolus1RestPending || activeMealMode == null) return false
        val now = System.currentTimeMillis()
        val currentIob = iobArray.firstOrNull()?.iob ?: 0.0
        // Shared with pre-bolus 2's cache, so the status text shows this cycle's values.
        lastBgMgdl        = glucoseStatus.glucose
        lastIob           = currentIob
        lastMaxIob        = maxIobU
        lastDelta         = glucoseStatus.delta
        lastShortAvgDelta = glucoseStatus.shortAvgDelta

        // Counted from when the meal is eaten: with "Eat in" the food starts later than the mode.
        val elapsedMs = now - s.eatAtMs
        if (elapsedMs < MealOverrideManager.PB1_REST_FROM_MIN * 60_000L) return false
        if (elapsedMs > MealOverrideManager.PB1_REST_UNTIL_MIN * 60_000L) {
            // BG never showed the food: the meal was smaller or slower than planned. Pre-bolus 2 and the loop cover the rest.
            _state = s.copy(preBolus1RestFiredMs = -2L)
            persistState()
            aapsLogger.debug(LTag.APS, "SmartInsulin PB1 rest: BG did not rise in time, ${s.preBolus1RestU}U not given")
            return false
        }
        val doseU = restDoseU(s.preBolus1RestU, currentIob, maxIobU)
        val reasons = restBlockReasons(glucoseStatus.glucose, currentIob, maxIobU, glucoseStatus.delta, glucoseStatus.shortAvgDelta, s.preBolus1RestU, doseU)
        if (reasons.isNotEmpty()) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB1 rest BLOCKED: ${reasons.joinToString(", ")}")
            return false
        }

        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB1 rest FIRING: ${doseU}U of ${s.preBolus1RestU}U bg=${String.format("%.1f", glucoseStatus.glucose / 18.0)} " +
                             "delta=${String.format("%.1f", glucoseStatus.delta)} iob=${String.format("%.2f", currentIob)}U")
        val bolusInfo = DetailedBolusInfo().apply {
            insulin   = doseU
            notes     = "SmartMeal ${s.mode.label} pre-bolus 1 rest (BG rising)"
            timestamp = dateUtil.now()
        }
        // Marked as fired before delivery, as pre-bolus 2 is, so a quick next cycle cannot fire it twice. Only what was
        // given stays as the rest: the part that did not fit under max IOB is left to the loop, which now sees its food.
        val firedState = s.copy(preBolus1RestU = doseU, preBolus1RestFiredMs = now)
        _state = firedState
        persistState()
        preBolusThisCycleU = doseU
        appScope.launch {
            val result = commandQueue.bolus(bolusInfo)
            if (!result.success) {
                aapsLogger.error(LTag.APS, "SmartInsulin PB1 rest delivery FAILED: ${result.comment}")
                // No automatic retry, as for pre-bolus 2: a failed delivery may have given part of it.
                if (_state == firedState) {
                    _state = firedState.copy(preBolus1RestFiredMs = -1L)
                    persistState()
                }
                uiInteraction.runAlarm(
                    "Rest of pre-bolus 1 delivery failed — please check pump and deliver manually if needed. Reason: ${result.comment}",
                    "SmartMeal pre-bolus 1 rest failed",
                    AlarmSound.BOLUS_ERROR
                )
            } else {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB1 rest delivered OK: ${doseU}U")
            }
        }
        return true
    }

    // ── Pre-bolus 2 delivery ──────────────────────────────────────────────────

    private fun processPreBolus2(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double,
        profile:       app.aaps.core.interfaces.profile.Profile?
    ) {
        val s = _state ?: return
        if (!s.preBolus2Pending) return

        val now = System.currentTimeMillis()

        // ── Cache glucose/IOB values every cycle so gate data is visible during countdown ──
        val currentBgMgdl = glucoseStatus.glucose
        val currentIob    = iobArray.firstOrNull()?.iob ?: 0.0
        val profileTarget = profile?.getTargetMgdl() ?: 108.0
        lastBgMgdl        = currentBgMgdl
        lastIob           = currentIob
        lastMaxIob        = maxIobU
        lastTargetMgdl    = profileTarget
        lastDelta         = glucoseStatus.delta
        lastShortAvgDelta = glucoseStatus.shortAvgDelta

        // Not time yet. In pieces: the next piece's time. The first piece comes sooner, from PB1_REST_FROM_MIN, once BG
        // shows the fat and protein coming in: a meal of almost only protein can raise BG well before pre-bolus 2's time.
        val risingEarly = s.preBolus2Spread && s.preBolus2LastPieceMs == null &&
            now - s.eatAtMs >= MealOverrideManager.PB1_REST_FROM_MIN * 60_000L &&
            glucoseStatus.delta >= MealOverrideManager.PB1_REST_RISE_MGDL && glucoseStatus.shortAvgDelta > 0.0
        if (now < s.preBolus2NextAtMs && !risingEarly) {
            val minsLeft = (s.preBolus2NextAtMs - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin PB2: ${minsLeft}min until scheduled delivery")
            return
        }

        // ── Safety checks ─────────────────────────────────────────────────────

        // In pieces only the next piece is given now; the gates are checked again before each one. Once the first half is
        // in, BG leads: the piece needs BG to ask for it, and is cut by how far above target BG is.
        val pieceU = if (s.preBolus2Spread) s.preBolus2NextPieceU else s.preBolus2U
        val bgLed = s.preBolus2BgLed
        val share = if (bgLed) MealOverrideManager.bgLedShare(currentBgMgdl, profileTarget, glucoseStatus.delta) ?: 0.0 else 1.0
        val doseU = if (bgLed) bgLedDoseU(pieceU, share) else pieceU
        val reasons =
            if (bgLed) bgLedBlockReasons(currentBgMgdl, currentIob, maxIobU, profileTarget, glucoseStatus.delta, glucoseStatus.shortAvgDelta, doseU)
            else safetyBlockReasons(currentBgMgdl, currentIob, maxIobU, profileTarget, glucoseStatus.delta, glucoseStatus.shortAvgDelta, doseU)

        if (reasons.isNotEmpty()) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB2 BLOCKED: ${reasons.joinToString(", ")}")
            return
        }

        // ── Fire ─────────────────────────────────────────────────────────────
        val piece = s.preBolus2PiecesGiven + 1
        val droppedU = pieceU - doseU
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB2 FIRING: ${doseU}U" + (if (s.preBolus2Spread) " piece $piece of ${s.preBolus2Pieces}" else "") +
                             (if (bgLed) " led by BG (${"%.0f".format(share * 100)} % of ${"%.2f".format(pieceU)}U)" else "") +
                             " bg=${String.format("%.1f", currentBgMgdl/18.0)} " +
                             "iob=${String.format("%.2f", currentIob)}U target=${String.format("%.1f", profileTarget/18.0)}")

        val bolusInfo = DetailedBolusInfo().apply {
            insulin   = doseU
            notes     = "SmartMeal ${s.mode.label} pre-bolus 2 (protein/fat cover)" + (if (s.preBolus2Spread) " piece $piece of ${s.preBolus2Pieces}" else "")
            timestamp = dateUtil.now()
        }

        // Mark as fired *before* delivery to prevent double-fire if loop cycles quickly.
        // Capture the exact state we're acting on — if _state changes before callback
        // (e.g. user cancels), we don't overwrite the new state. In pieces, pre-bolus 2 is fired once the last one is.
        val firedState =
            if (s.preBolus2Spread)
                s.copy(
                    preBolus2PiecesGiven = piece, preBolus2GivenU = s.preBolus2GivenU + doseU, preBolus2LastPieceMs = now,
                    // What BG left out of the piece is dropped, not carried on to the next one.
                    preBolus2DroppedU = s.preBolus2DroppedU + droppedU,
                    // Done after the last piece, or sooner when the loop gave part of it and nothing is left.
                    preBolus2FiredMs = if (piece >= s.preBolus2Pieces || s.preBolus2LeftU - pieceU < MealOverrideManager.SPREAD_STEP_U) now else null
                )
            else s.copy(preBolus2FiredMs = now)
        _state = firedState
        persistState()
        preBolusThisCycleU = doseU

        // Launched rather than awaited: 4.0's bolus is suspend, and awaiting it here would hold the
        // loop cycle for the whole delivery. The old callback didn't, and neither does this.
        appScope.launch {
            val result = commandQueue.bolus(bolusInfo)
            if (!result.success) {
                aapsLogger.error(LTag.APS, "SmartInsulin PB2 delivery FAILED: ${result.comment}")
                // Mark as discarded — do NOT retry automatically.
                // A failed delivery may have partially or fully delivered on some pumps.
                // Automatic retry risks double-dosing. User must manually deliver if still needed.
                // Cascade: PB3 can never fire now (no valid timing reference) — cancel it too.
                // Only update state if it hasn't been changed by a cancel or new activation.
                // In pieces the pieces left stop too, and the failed piece does not count as given.
                if (_state == firedState) {
                    val pb3Cascade = firedState.preBolus3Pending
                    _state = firedState.copy(
                        preBolus2FiredMs = -1L,
                        preBolus3FiredMs = if (pb3Cascade) -1L else firedState.preBolus3FiredMs,
                        preBolus2PiecesGiven = s.preBolus2PiecesGiven,
                        preBolus2GivenU = s.preBolus2GivenU,
                        preBolus2DroppedU = s.preBolus2DroppedU
                    )
                    persistState()
                    if (pb3Cascade) {
                        aapsLogger.debug(LTag.APS, "SmartInsulin PB3 cascade-cancelled due to PB2 delivery failure")
                    }
                }
                uiInteraction.runAlarm(
                    "Pre-bolus 2 delivery failed — please check pump and deliver manually if needed. Reason: ${result.comment}",
                    "SmartMeal pre-bolus 2 failed",
                    AlarmSound.BOLUS_ERROR
                )
            } else {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB2 delivered OK: ${doseU}U")
            }
        }
    }

    // ── Pre-bolus 3 delivery ──────────────────────────────────────────────────

    private fun processPreBolus3(
        glucoseStatus: GlucoseStatus,
        iobArray:      Array<IobTotal>,
        maxIobU:       Double,
        profile:       app.aaps.core.interfaces.profile.Profile?
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

        // The next piece's time: pre-bolus 3's own time, then the gap after the last piece.
        val fireAt = s.preBolus3NextAtMs ?: return  // should be non-null once PB2 fired successfully
        val now    = System.currentTimeMillis()

        // ── Cache glucose/IOB values every cycle so gate data is visible during countdown ──
        val currentBgMgdl = glucoseStatus.glucose
        val currentIob    = iobArray.firstOrNull()?.iob ?: 0.0
        val profileTarget = profile?.getTargetMgdl() ?: 108.0
        lastPb3BgMgdl        = currentBgMgdl
        lastPb3Iob           = currentIob
        lastPb3MaxIob        = maxIobU
        lastPb3TargetMgdl    = profileTarget
        lastPb3Delta         = glucoseStatus.delta
        lastPb3ShortAvgDelta = glucoseStatus.shortAvgDelta

        // Not time yet
        if (now < fireAt) {
            val minsLeft = (fireAt - now) / 60_000
            aapsLogger.debug(LTag.APS, "SmartInsulin PB3: ${minsLeft}min until scheduled delivery")
            return
        }

        // ── Safety checks: the gates of pre-bolus 2, and BG must ask for it ──
        // Pre-bolus 3 is the tail of the meal, the least sure part of it: it always comes in pieces led by BG. A 4.4 U
        // pre-bolus 3 in one dose waited 4.5 hours into a dinner while BG drifted down at 5.8 (11 Nov).
        val pieceU = s.preBolus3NextPieceU
        val share  = MealOverrideManager.bgLedShare(currentBgMgdl, profileTarget, glucoseStatus.delta) ?: 0.0
        val doseU  = bgLedDoseU(pieceU, share)
        val reasons = bgLedBlockReasons(currentBgMgdl, currentIob, maxIobU, profileTarget, glucoseStatus.delta, glucoseStatus.shortAvgDelta, doseU)

        if (reasons.isNotEmpty()) {
            aapsLogger.debug(LTag.APS, "SmartInsulin PB3 BLOCKED: ${reasons.joinToString(", ")}")
            return
        }

        // ── Fire ─────────────────────────────────────────────────────────────
        val piece = s.preBolus3PiecesGiven + 1
        aapsLogger.debug(LTag.APS,
                         "SmartInsulin PB3 FIRING: ${doseU}U piece $piece of ${s.preBolus3Pieces} led by BG " +
                             "(${"%.0f".format(share * 100)} % of ${"%.2f".format(pieceU)}U) bg=${String.format("%.1f", currentBgMgdl/18.0)} " +
                             "iob=${String.format("%.2f", currentIob)}U target=${String.format("%.1f", profileTarget/18.0)}")

        val bolusInfo = DetailedBolusInfo().apply {
            insulin   = doseU
            notes     = "SmartMeal ${s.mode.label} pre-bolus 3 (late protein/fat cover) piece $piece of ${s.preBolus3Pieces}"
            timestamp = dateUtil.now()
        }

        // Mark as given *before* delivery to prevent double-fire if loop cycles quickly. What BG left out of the piece is
        // dropped, not carried on. Pre-bolus 3 is done after the last piece, or once nothing is left.
        val firedState = s.copy(
            preBolus3PiecesGiven = piece, preBolus3GivenU = s.preBolus3GivenU + doseU, preBolus3LastPieceMs = now,
            preBolus3DroppedU = s.preBolus3DroppedU + (pieceU - doseU),
            preBolus3FiredMs = if (piece >= s.preBolus3Pieces || s.preBolus3LeftU - pieceU < MealOverrideManager.SPREAD_STEP_U) now else null
        )
        _state = firedState
        persistState()
        preBolusThisCycleU = doseU

        // Launched rather than awaited: 4.0's bolus is suspend, and awaiting it here would hold the
        // loop cycle for the whole delivery. The old callback didn't, and neither does this.
        appScope.launch {
            val result = commandQueue.bolus(bolusInfo)
            if (!result.success) {
                aapsLogger.error(LTag.APS, "SmartInsulin PB3 delivery FAILED: ${result.comment}")
                // Mark as discarded — same policy as PB2 failure: no automatic retry,
                // user must manually deliver if needed. The pieces left stop, and the failed one does not count as given.
                if (_state == firedState) {
                    _state = firedState.copy(
                        preBolus3FiredMs = -1L, preBolus3PiecesGiven = s.preBolus3PiecesGiven,
                        preBolus3GivenU = s.preBolus3GivenU, preBolus3DroppedU = s.preBolus3DroppedU
                    )
                    persistState()
                }
                uiInteraction.runAlarm(
                    "Pre-bolus 3 delivery failed — please check pump and deliver manually if needed. Reason: ${result.comment}",
                    "SmartMeal pre-bolus 3 failed",
                    AlarmSound.BOLUS_ERROR
                )
            } else {
                aapsLogger.debug(LTag.APS, "SmartInsulin PB3 delivered OK: ${doseU}U, piece $piece")
            }
        }
    }

    companion object {

        /** Less than this short of the planned pre-bolus 1 is rounding, not a stopped bolus. */
        const val STOPPED_MIN_DIFF_U = 0.05
        /** Doubles of insulin closer than this are the same amount. */
        private const val ROUNDING_U = 1e-6

        /**
         * The rest of pre-bolus 1 to give with [iobU] on board: all of [restU] when it fits under [maxIobU], else the part
         * that does, rounded down to a bolus step. The rest of it is left to the loop.
         */
        fun restDoseU(restU: Double, iobU: Double, maxIobU: Double): Double {
            val step = MealOverrideManager.SPREAD_STEP_U
            val roomU = floor((maxIobU - iobU).coerceAtLeast(0.0) / step + ROUNDING_U) * step
            return minOf(restU, roomU)
        }
        /** Pre-bolus 1 is given right after the mode starts: its record is looked for this long after the start. */
        const val PB1_RECORD_WINDOW_MS = 10 * 60_000L
        /** And this long before it, for a clock a little out between the mode start and the bolus record. */
        private const val PB1_RECORD_EARLY_MS = 60_000L

        /**
         * The bolus record of pre-bolus 1 among [boluses]: the first valid normal bolus from just before the mode
         * started at [triggerTimeMs] until [PB1_RECORD_WINDOW_MS] after. SMBs are not it. Null when there is none.
         */
        fun preBolus1Record(boluses: List<BS>, triggerTimeMs: Long): BS? =
            boluses.filter {
                it.isValid && it.type == BS.Type.NORMAL &&
                    it.timestamp >= triggerTimeMs - PB1_RECORD_EARLY_MS && it.timestamp <= triggerTimeMs + PB1_RECORD_WINDOW_MS
            }.minByOrNull { it.timestamp }
    }
}
