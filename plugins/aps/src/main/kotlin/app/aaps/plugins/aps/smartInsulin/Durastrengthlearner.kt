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
 * Learns how hard DURA should push, per mode. Strongly asymmetric, and the asymmetry is the point:
 *
 *   - DURA engaged meaningfully and the episode ended in a low → DURA over-corrected →
 *     lower its CEILING for that mode, and trim its strength a little. Nothing else owns this
 *     signal, and it's the safety-relevant direction. A dip close to the low guard that never
 *     reached it counts too, at a reduced step — otherwise DURA can repeatedly hand BG back a
 *     whisker above the guard and never once be told it pushed too hard.
 *
 *     Why the ceiling carries the correction rather than the strength: DURA's multiplier is
 *     `1 + stuckHours × strength × excess`, and nothing but the user's ISF floor stops it
 *     climbing. Strength only sets the SLOPE. Cutting it made DURA take longer to reach the
 *     same multiplier — about 8 minutes longer per 15% cut on a typical lunch — and BG sitting
 *     stuck for those extra minutes then let it carry on past where it had been. Strength
 *     factors walked toward their floor while the multiplier that actually caused each low kept
 *     being reached. The ceiling is a cap on the multiplier itself, cut from the peak the
 *     failing episode actually reached, so "×1.44 was too much" is learned as exactly that.
 *   - DURA engaged but BG stayed stuck → do NOT raise strength on that. [ModeIsfLearner]
 *     already treats "needed DURA" as evidence the mode's base ISF is too weak and
 *     strengthens that instead. Raising DURA strength on the same evidence would correct
 *     one problem twice — and fixing the baseline is the better lever anyway, since a
 *     correctly-converged mode ISF means DURA rarely has to fire at all.
 *   - DURA engaged and the episode landed cleanly → creep back toward the configured strength by
 *     [RECOVER_STEP]. This is NOT the "needed DURA" signal above wearing a different hat; it is
 *     the only evidence that a PAST cut is no longer earning its keep. Without it the factor is a
 *     one-way ratchet with no way home short of the user changing the configured strength.
 *     The ceiling gives back the same way ([CAP_RECOVER_STEP]), but only when the clean episode
 *     actually pressed against it — an episode where DURA stayed well under the ceiling says
 *     nothing about whether the ceiling is too low, and raising it on that would walk it back up
 *     blind.
 *
 * The learned strength multiplies the configured DURA strength, railed to [FACTOR_MIN]..[FACTOR_MAX]
 * — it can only ever soften what the user configured, never exceed it. The learned ceiling caps
 * the DURA multiplier alongside the user's ISF floor; whichever is tighter wins.
 */
@Singleton
class DuraStrengthLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private data class ModeState(
        var factor: Double = 1.0, var episodes: Int = 0, var baseSig: Double = Double.NaN,
        /** Highest DURA multiplier allowed; [NO_CEILING] until a low first sets one. */
        var ceiling: Double = NO_CEILING
    )

    /**
     * Keyed by [PfWindow.stateKey] for the same reason [ModeIsfLearner] is: P/F doses from a
     * different ISF in each window, so a DURA cut earned overnight has no business softening
     * daytime P/F as well.
     */
    private val states = mutableMapOf<String, ModeState>()

    /** A mode together with the state slot and label it is being learned under this episode. */
    private data class Scope(val mode: MealMode, val key: String, val label: String)

    private fun scopeOf(mode: MealMode, window: PfWindow) =
        Scope(mode, PfWindow.stateKey(mode, window), PfWindow.label(mode, window))

    // Active episode
    private var activeScope:   Scope? = null
    private var activeStartMs  = 0L
    private var episodeMaxDura = 1.0

    // Post-episode watch window — a DURA-driven crash often lands after the mode itself ends,
    // and more so now that at-target auto-cancel ends modes while their insulin is still working.
    private var pendingScope:      Scope? = null
    private var pendingUntilMs     = 0L
    private var pendingMaxDura     = 1.0

    var lastOutcome = ""
        private set

    init { restore() }

    companion object {
        private const val TAIL_MS                   = 105 * 60_000L
        private const val ENGAGED_THRESHOLD         = 1.10  // DURA must have actually done something
        /**
         * -5% strength per crash. Was 15% when strength was the only lever; the ceiling now takes
         * the main correction, and charging both at full size would bill one low twice.
         */
        private const val REDUCE_STEP               = 0.95
        /** +3% strength per clean landing. */
        private const val RECOVER_STEP              = 1.03
        /** Ceiling cut per crash, applied to the EXCESS over 1.0: a low after ×1.44 → ×1.374. */
        private const val CAP_REDUCE_STEP           = 0.85
        /** Ceiling give-back per clean landing that pressed against it, also on the excess. */
        private const val CAP_RECOVER_STEP          = 1.03
        /** DURA can always add at least this much — below it the mode may as well have DURA off,
         *  which is the user's toggle to make, not a learner's. */
        private const val CAP_MIN                   = 1.05
        /** A ceiling recovered past this is no longer constraining anything real; drop it. */
        private const val CAP_CLEAR_ABOVE           = 3.0
        /** An episode counts as having pressed the ceiling once DURA got within 3% of it. */
        private const val CAP_BINDING_FRACTION      = 0.97
        const val NO_CEILING                        = Double.POSITIVE_INFINITY
        private const val FACTOR_MIN                = 0.3
        private const val FACTOR_MAX                = 1.0
        private const val UNEXPLAINED_STEP_FRACTION = 0.4   // exercise low — soften, don't fully credit
        /** Undershoot (close to the low guard, never below it) — DURA still pushed too hard, but
         *  the outcome was milder than a crash, so the correction is milder too. */
        private const val UNDERSHOOT_STEP_FRACTION  = 0.5
        /** DURA needs time to have caused anything; a mode that starts inside the undershoot band
         *  says nothing about DURA's strength in its first cycles. */
        private const val UNDERSHOOT_MIN_ELAPSED_MS = 25 * 60_000L

        private const val K_FACTOR = "factor"
        private const val K_N      = "n"
        private const val K_BASE   = "baseSig"
        private const val K_CAP    = "ceiling"
    }

    /** Multiplier on the configured DURA strength for this mode (≤ 1.0). */
    fun factor(mode: MealMode?, window: PfWindow = PfWindow.NONE): Double =
        if (mode == null) 1.0 else states[PfWindow.stateKey(mode, window)]?.factor ?: 1.0

    /** Highest DURA multiplier this mode may use, or [NO_CEILING]. */
    fun ceiling(mode: MealMode?, window: PfWindow = PfWindow.NONE): Double =
        if (mode == null) NO_CEILING else states[PfWindow.stateKey(mode, window)]?.ceiling ?: NO_CEILING

    fun episodeCount(mode: MealMode, window: PfWindow = PfWindow.NONE): Int =
        states[PfWindow.stateKey(mode, window)]?.episodes ?: 0

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
        baseSignature:     Double = 0.0,
        undershootActive:  Boolean = false,  // close to the low guard but above it — a DURA
        // over-correction that never became a hypo is still a DURA over-correction
        pfWindow:          PfWindow = PfWindow.NONE  // which P/F ISF window this episode doses
        // from; NONE for every other mode. Fixed when the episode opens.
    ) {
        if (activeModeNow != null) {
            if (activeScope == null || modeStartMs != activeStartMs) {
                pendingScope = null
                val opening = scopeOf(activeModeNow, pfWindow)
                val s = states.getOrPut(opening.key) { ModeState() }
                if (!s.baseSig.isNaN() && kotlin.math.abs(s.baseSig - baseSignature) > 0.001 &&
                    (s.factor != 1.0 || s.episodes > 0)) {
                    s.factor = 1.0
                    s.episodes = 0
                    s.ceiling = NO_CEILING
                    lastOutcome = "${opening.label} DURA strength changed — learned factor and ceiling reset"
                    aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
                }
                s.baseSig = baseSignature
                persist()

                activeScope    = opening
                activeStartMs  = modeStartMs
                episodeMaxDura = 1.0
            }
            if (duraMult > episodeMaxDura) episodeMaxDura = duraMult
            // A low while the mode is still running, with DURA meaningfully engaged, is
            // immediate evidence — act now rather than waiting for the mode to expire.
            val undershootCounts = undershootActive && (nowMs - activeStartMs) >= UNDERSHOOT_MIN_ELAPSED_MS
            if ((lowActive || undershootCounts) && episodeMaxDura >= ENGAGED_THRESHOLD) {
                reduce(activeScope!!, exerciseSuspected, episodeMaxDura, "during", undershoot = !lowActive)
                episodeMaxDura = 1.0  // don't fire repeatedly on one low
            }
            return
        }

        if (activeScope != null) {
            val ended = activeScope!!
            val maxDura = episodeMaxDura
            activeScope   = null
            activeStartMs = 0L
            if (maxDura >= ENGAGED_THRESHOLD) {
                pendingScope   = ended
                pendingUntilMs = nowMs + TAIL_MS
                pendingMaxDura = maxDura
            }
        }

        val p = pendingScope ?: return
        if (lowActive || undershootActive) {
            reduce(p, exerciseSuspected, pendingMaxDura, "in the tail after", undershoot = !lowActive)
            pendingScope = null
            return
        }
        if (nowMs >= pendingUntilMs) {
            val s = states.getOrPut(p.key) { ModeState() }
            s.episodes++
            // DURA pushed, BG came back, nothing went low: whatever earlier crash cut this mode's
            // strength, that cut is no longer being paid for. Give a little back.
            val before = s.factor
            s.factor = (s.factor * RECOVER_STEP).coerceIn(FACTOR_MIN, FACTOR_MAX)
            val ceilingBefore = s.ceiling
            val pressedCeiling = s.ceiling != NO_CEILING && pendingMaxDura >= s.ceiling * CAP_BINDING_FRACTION
            if (pressedCeiling) {
                val raised = 1.0 + (s.ceiling - 1.0) * CAP_RECOVER_STEP
                s.ceiling = if (raised > CAP_CLEAR_ABOVE) NO_CEILING else raised
            }
            persist()
            lastOutcome = "${p.label} DURA ×${"%.2f".format(pendingMaxDura)} landed cleanly — " +
                (if (s.factor > before + 1e-9) "strength returned toward configured → ×${"%.2f".format(s.factor)}"
                 else "already at configured strength") +
                (if (pressedCeiling) ", ceiling ${fmtCeiling(ceilingBefore)} → ${fmtCeiling(s.ceiling)}" else "") +
                " (n=${s.episodes})"
            aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            pendingScope = null
        }
    }

    private fun reduce(mode: Scope, exerciseSuspected: Boolean, maxDura: Double, whenTxt: String, undershoot: Boolean = false) {
        val s = states.getOrPut(mode.key) { ModeState() }
        var fraction = 1.0
        if (exerciseSuspected) fraction *= UNEXPLAINED_STEP_FRACTION
        if (undershoot)        fraction *= UNDERSHOOT_STEP_FRACTION
        val step = 1.0 - (1.0 - REDUCE_STEP) * fraction
        s.factor = (s.factor * step).coerceIn(FACTOR_MIN, FACTOR_MAX)
        // Cut from the peak this episode actually reached, not from the old ceiling: DURA stopping
        // short of the ceiling and still causing a low means the ceiling was never the limit that
        // mattered. min() keeps a ceiling that is already tighter than this episode's cut.
        val capStep = 1.0 - (1.0 - CAP_REDUCE_STEP) * fraction
        val ceilingBefore = s.ceiling
        s.ceiling = minOf(s.ceiling, 1.0 + (maxDura - 1.0) * capStep).coerceAtLeast(CAP_MIN)
        s.episodes++
        persist()
        lastOutcome = (if (undershoot) "dip toward the low guard $whenTxt " else "low $whenTxt ") + "${mode.label} with DURA ×${"%.2f".format(maxDura)}" +
            (if (exerciseSuspected) ", but BG fell faster than insulin explains" else "") +
            " — DURA ceiling ${fmtCeiling(ceilingBefore)} → ${fmtCeiling(s.ceiling)}, strength → ×${"%.2f".format(s.factor)}" +
            (if (fraction < 1.0) " (smaller step)" else "") +
            " (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
    }

    private fun fmtCeiling(c: Double) = if (c == NO_CEILING) "none" else "×${"%.2f".format(c)}"

    fun reset() {
        states.clear()
        activeScope = null; activeStartMs = 0L; episodeMaxDura = 1.0
        pendingScope = null
        lastOutcome = ""
        sp.edit { putString(StringKey.ApsSmartInsulinDuraStrengthLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "DuraStrengthLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (key, s) ->
                val obj = JSONObject().put(K_FACTOR, s.factor).put(K_N, s.episodes)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                if (s.ceiling != NO_CEILING) obj.put(K_CAP, s.ceiling)  // ...and Infinity
                json.put(key, obj)
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
                val obj = json.optJSONObject(key) ?: return@forEach
                val st  = ModeState(
                    factor   = obj.optDouble(K_FACTOR, 1.0).coerceIn(FACTOR_MIN, FACTOR_MAX),
                    episodes = obj.optInt(K_N, 0),
                    baseSig  = obj.optDouble(K_BASE, Double.NaN),
                    ceiling  = if (obj.has(K_CAP)) obj.optDouble(K_CAP, NO_CEILING).coerceAtLeast(CAP_MIN) else NO_CEILING
                )
                if (key == MealMode.UAM_PROTEIN_FAT.name) {
                    // Migration: pre-split state. Seed every window from it — see the matching
                    // note in ModeIsfLearner.restore().
                    PfWindow.PF_WINDOWS.forEach { w ->
                        states.getOrPut(PfWindow.stateKey(MealMode.UAM_PROTEIN_FAT, w)) { st.copy() }
                    }
                    return@forEach
                }
                states[key] = st
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "DuraStrengthLearner: restore failed: ${e.message}")
        }
    }
}
