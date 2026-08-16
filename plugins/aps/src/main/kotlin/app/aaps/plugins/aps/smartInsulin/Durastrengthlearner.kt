package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learns how hard DURA should push, per mode — **downward only**.
 *
 * Asymmetric by design, and the asymmetry is the whole point:
 *
 *   - DURA engaged meaningfully and the episode ended in a low → DURA over-corrected →
 *     reduce its strength for that mode. Nothing else owns this signal, and it's the
 *     safety-relevant direction.
 *   - DURA engaged but BG stayed stuck → do NOT raise strength here. [ModeIsfLearner]
 *     already treats "needed DURA" as evidence the mode's base ISF is too weak and
 *     strengthens that instead. Raising DURA strength on the same evidence would correct
 *     one problem twice — and fixing the baseline is the better lever anyway, since a
 *     correctly-converged mode ISF means DURA rarely has to fire at all.
 *
 * The learned value multiplies the configured DURA strength, railed to [FACTOR_MIN]..1.0 —
 * it can only ever soften what the user configured, never exceed it.
 */
@Singleton
class DuraStrengthLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private class ModeState(var factor: Double = 1.0, var episodes: Int = 0, var baseSig: Double = Double.NaN)

    private val states = mutableMapOf<MealMode, ModeState>()

    // Active episode
    private var activeMode:    MealMode? = null
    private var activeStartMs  = 0L
    private var episodeMaxDura = 1.0

    // Post-episode watch window — a DURA-driven crash often lands after the mode itself ends
    private var pendingMode:       MealMode? = null
    private var pendingUntilMs     = 0L
    private var pendingMaxDura     = 1.0

    var lastOutcome = ""
        private set

    init { restore() }

    companion object {
        private const val TAIL_MS                   = 75 * 60_000L
        private const val ENGAGED_THRESHOLD         = 1.10  // DURA must have actually done something
        private const val REDUCE_STEP               = 0.85  // -15% per crash
        private const val FACTOR_MIN                = 0.3
        private const val FACTOR_MAX                = 1.0
        private const val UNEXPLAINED_STEP_FRACTION = 0.4   // exercise low — soften, don't fully credit

        private const val K_FACTOR = "factor"
        private const val K_N      = "n"
        private const val K_BASE   = "baseSig"
    }

    /** Multiplier on the configured DURA strength for this mode (≤ 1.0). */
    fun factor(mode: MealMode?): Double = if (mode == null) 1.0 else states[mode]?.factor ?: 1.0

    fun episodeCount(mode: MealMode): Int = states[mode]?.episodes ?: 0

    /**
     * Call once per loop cycle. [baseSignature] is the configured DURA strength for the active
     * mode — a change resets the learned factor, since it was a correction relative to the old
     * setting.
     */
    fun onCycle(
        activeModeNow:     MealMode?,
        modeStartMs:       Long,
        lowActive:         Boolean,
        duraMult:          Double,
        exerciseSuspected: Boolean,
        nowMs:             Long,
        baseSignature:     Double = 0.0
    ) {
        if (activeModeNow != null) {
            if (activeMode == null || modeStartMs != activeStartMs) {
                pendingMode = null
                val s = states.getOrPut(activeModeNow) { ModeState() }
                if (!s.baseSig.isNaN() && kotlin.math.abs(s.baseSig - baseSignature) > 0.001 &&
                    (s.factor != 1.0 || s.episodes > 0)) {
                    s.factor = 1.0
                    s.episodes = 0
                    lastOutcome = "${activeModeNow.label} DURA strength changed — learned factor reset"
                    aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
                }
                s.baseSig = baseSignature
                persist()

                activeMode     = activeModeNow
                activeStartMs  = modeStartMs
                episodeMaxDura = 1.0
            }
            if (duraMult > episodeMaxDura) episodeMaxDura = duraMult
            // A low while the mode is still running, with DURA meaningfully engaged, is
            // immediate evidence — act now rather than waiting for the mode to expire.
            if (lowActive && episodeMaxDura >= ENGAGED_THRESHOLD) {
                reduce(activeModeNow, exerciseSuspected, episodeMaxDura, "during")
                episodeMaxDura = 1.0  // don't fire repeatedly on one low
            }
            return
        }

        if (activeMode != null) {
            val ended = activeMode!!
            val maxDura = episodeMaxDura
            activeMode    = null
            activeStartMs = 0L
            if (maxDura >= ENGAGED_THRESHOLD) {
                pendingMode    = ended
                pendingUntilMs = nowMs + TAIL_MS
                pendingMaxDura = maxDura
            }
        }

        val p = pendingMode ?: return
        if (lowActive) {
            reduce(p, exerciseSuspected, pendingMaxDura, "in the tail after")
            pendingMode = null
            return
        }
        if (nowMs >= pendingUntilMs) {
            val s = states.getOrPut(p) { ModeState() }
            s.episodes++
            persist()
            lastOutcome = "${p.label} DURA ×${"%.2f".format(pendingMaxDura)} landed cleanly — no change (n=${s.episodes})"
            aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            pendingMode = null
        }
    }

    private fun reduce(mode: MealMode, exerciseSuspected: Boolean, maxDura: Double, whenTxt: String) {
        val s = states.getOrPut(mode) { ModeState() }
        val step = if (exerciseSuspected) 1.0 - (1.0 - REDUCE_STEP) * UNEXPLAINED_STEP_FRACTION else REDUCE_STEP
        s.factor = (s.factor * step).coerceIn(FACTOR_MIN, FACTOR_MAX)
        s.episodes++
        persist()
        lastOutcome = "low $whenTxt ${mode.label} with DURA ×${"%.2f".format(maxDura)}" +
            (if (exerciseSuspected) ", but BG fell faster than insulin explains — reduced at a smaller step" else " — DURA strength reduced") +
            " → ×${"%.2f".format(s.factor)} (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
    }

    fun reset() {
        states.clear()
        activeMode = null; activeStartMs = 0L; episodeMaxDura = 1.0
        pendingMode = null
        lastOutcome = ""
        sp.edit { putString(StringKey.ApsSmartInsulinDuraStrengthLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "DuraStrengthLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (mode, s) ->
                val obj = JSONObject().put(K_FACTOR, s.factor).put(K_N, s.episodes)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                json.put(mode.name, obj)
            }
            sp.edit { putString(StringKey.ApsSmartInsulinDuraStrengthLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "DuraStrengthLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringKey.ApsSmartInsulinDuraStrengthLearnerState.key, StringKey.ApsSmartInsulinDuraStrengthLearnerState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val mode = try { MealMode.valueOf(key) } catch (_: Exception) { return@forEach }
                val obj  = json.optJSONObject(key) ?: return@forEach
                states[mode] = ModeState(
                    factor   = obj.optDouble(K_FACTOR, 1.0).coerceIn(FACTOR_MIN, FACTOR_MAX),
                    episodes = obj.optInt(K_N, 0),
                    baseSig  = obj.optDouble(K_BASE, Double.NaN)
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "DuraStrengthLearner: restore failed: ${e.message}")
        }
    }
}
