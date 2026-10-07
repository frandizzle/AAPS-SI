package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Learns how hard DURA should push, per mode — the tail of the meal, after mode ISF has handled
 * the spike. Two knobs, and each episode moves at most one of them:
 *
 *   - STRENGTH sets how fast DURA climbs once BG is stuck (`1 + stuckHours × strength × excess`).
 *   - CEILING caps how far it can climb. Your configured ISF floor is the hard limit above that;
 *     the learner never goes past it.
 *
 * Outcomes, judged after a [TAIL_MS] watch once the mode ends:
 *
 *   - Low or near-low (during the mode or in the tail) with DURA engaged → DURA pushed too far.
 *     Ceiling cut from the peak DURA actually reached, strength trimmed. A low always wins: an
 *     episode that went low can never strengthen DURA, however long BG was stuck before it.
 *   - BG stuck elevated for [STUCK_LONG_MS] and nothing went low → DURA wasn't enough. Which knob
 *     depends on where DURA spent that stuck time:
 *       · held at your configured floor → nothing left to give; the SI tab says so, you decide.
 *       · held at the learned ceiling  → loosen the ceiling, so DURA can go further.
 *       · still climbing               → raise strength, so it gets there sooner (up to the
 *         strength you configured; at full strength the SI tab says so).
 *   - Anything else → no change. DURA did about the right amount.
 *
 * "Stuck elevated" is one unbroken run of DURA's own stuck test (BG within ±5% of where it has
 * been sitting, no single fall ≥0.2 mmol/5min) with BG at least [STUCK_MARGIN_MGDL] over target.
 * A run only counts as "DURA wasn't enough" if all three hold:
 *   - it lasted [STUCK_LONG_MS]. A normal DURA-assisted tail comes down well inside an hour; the
 *     lunch that prompted the ceiling sat 45 minutes and then went low.
 *   - DURA was engaged (≥[ENGAGED_THRESHOLD]) or held at a limit for [ENGAGED_MIN_MS] of it. A
 *     stretch where DURA barely nudged ISF says DURA should have engaged harder — a weaker claim
 *     than "it engaged and still wasn't enough", and not one to strengthen on.
 *   - BG didn't net-decline more than [MAX_NET_DROP_MGDL] across it. The tracker's anchor follows
 *     BG, so a steady bleed just under the single-cycle reset reads as stuck the whole way down;
 *     strengthening on a plateau that was already ending is how a stall turns into the next low.
 * Rising across the run still counts — that is a stall getting worse, not resolving.
 *
 * There used to be a blind give-back on every clean landing. It is gone: with real evidence in the
 * up direction, a clean landing means "about right", and creeping stronger on it just walked DURA
 * back into the next low with lows as the only brake. Mode ISF no longer strengthens on "needed
 * DURA" or on a stuck tail either — that was the spike knob being charged for the tail's problem.
 */
@SingleIn(AppScope::class)
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
    /** Longest QUALIFYING stuck-elevated run this episode (see class doc), and how much of that
     *  run DURA spent held at the learned ceiling / the configured floor. */
    private var episodeStuckMs = 0L
    private var episodeAtCeilingMs = 0L
    private var episodeAtFloorMs   = 0L
    /** Why the longest run that didn't qualify was rejected — surfaced in the outcome line. */
    private var episodeStallNote: String? = null
    private var episodeRejectedMs  = 0L

    // The stuck-elevated run currently open.
    private var runStartMs     = 0L
    private var runLastMs      = 0L
    private var runEngagedMs   = 0L
    private var runAtCeilingMs = 0L
    private var runAtFloorMs   = 0L
    private val runBgs         = ArrayList<Double>()
    /** A low or near-low already happened this episode — it can no longer strengthen DURA. */
    private var episodeWentLow     = false
    private var episodeBoosted     = false  // the new pod boost ran at some point in this episode
    // BG was already low (or close to it) when the mode opened - not DURA's doing. Not scored until
    // BG comes out of it, or goes clearly lower than [episodeStartBgMgdl]. Same rule as ModeIsfLearner.
    private var episodeCarriedInLow = false
    private var episodeStartBgMgdl  = 0.0

    // Post-episode watch window — a DURA-driven crash often lands after the mode itself ends,
    // and more so now that at-target auto-cancel ends modes while their insulin is still working.
    private var pendingScope:      Scope? = null
    private var pendingUntilMs     = 0L
    private var pendingMaxDura     = 1.0
    private var pendingStuckMs     = 0L
    private var pendingAtCeilingMs = 0L
    private var pendingAtFloorMs   = 0L
    private var pendingStallNote: String? = null

    /** Called with each new verdict; the plugin points it at [LearningJournal]. */
    var onOutcome: ((String) -> Unit)? = null

    var lastOutcome = ""
        private set(value) {
            field = value
            if (value.isNotEmpty()) onOutcome?.invoke(value)
        }

    init { restore() }

    companion object {
        private const val TAIL_MS                   = 105 * 60_000L
        private const val ENGAGED_THRESHOLD         = 1.10  // DURA must have actually done something
        /**
         * -10% strength per low (-5% for a near-low). Was -5%: on a low DURA had pushed into, the
         * strength barely moved (×1.00 → ×0.98 for a near-low), so the same low came back.
         */
        private const val REDUCE_STEP               = 0.90
        /** +3% strength per episode stuck long while DURA was still climbing. Smaller than the
         *  5% cut, so a mode oscillating between stuck and low still drifts toward safety. */
        private const val STRENGTHEN_STEP           = 1.03
        /**
         * Ceiling cut per low, applied to the EXCESS over 1.0: a low halves the boost DURA reached
         * (×1.17 → ×1.09; a near-low takes a quarter off, ×1.17 → ×1.13). Was 15% of the excess,
         * which on a small boost was nothing: ×1.17 → ×1.16 after a near-low.
         */
        private const val CAP_REDUCE_STEP           = 0.50
        /** Ceiling loosen per episode stuck long at it, on the excess — 10%, but never less than
         *  [CAP_LOOSEN_MIN], or a ceiling cut down near ×1.09 would take dozens of meals to move. */
        private const val CAP_LOOSEN_STEP           = 1.10
        private const val CAP_LOOSEN_MIN            = 0.02
        /** BG this far over target (1.0 mmol) counts as elevated. */
        const val STUCK_MARGIN_MGDL                 = 18.0
        /** Unbroken stuck-elevated run that counts as "DURA wasn't enough". */
        const val STUCK_LONG_MS                     = 60 * 60_000L
        /** Of the stuck time, this much held at a limit decides which limit was the problem. */
        private const val HELD_AT_LIMIT_MS          = 30 * 60_000L
        /** Of the stuck run, this much with DURA engaged or held at a limit — half the hour. */
        const val ENGAGED_MIN_MS                    = 30 * 60_000L
        /** Largest net fall across a stuck run (0.5 mmol) that still counts as stuck, measured
         *  between the means of its first and last [NET_DELTA_READINGS] readings. */
        const val MAX_NET_DROP_MGDL                 = 9.0
        private const val NET_DELTA_READINGS        = 3
        /** DURA can always add at least this much — below it the mode may as well have DURA off,
         *  which is the user's toggle to make, not a learner's. */
        private const val CAP_MIN                   = 1.05
        /** A ceiling recovered past this is no longer constraining anything real; drop it. */
        private const val CAP_CLEAR_ABOVE           = 3.0
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
        /** How much lower than the starting BG a carried-in low must go before it counts (~0.5 mmol). */
        private const val CARRIED_IN_LOW_DEEPER_MGDL = 9.0

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
        pfWindow:          PfWindow = PfWindow.NONE,  // which P/F ISF window this episode doses
        // from; NONE for every other mode. Fixed when the episode opens.
        bgMgdl:            Double = 0.0,
        targetMgdl:        Double = 0.0,
        duraStuckMinutes:  Double = 0.0,     // DURA's own unbroken stuck timer this cycle
        duraAtCeiling:     Boolean = false,  // DURA wanted more than the learned ceiling allowed
        duraAtFloor:       Boolean = false,  // DURA's ISF was held at the configured floor
        modeInsulinShare:  Double = 1.0,     // share of the insulin behind a post-mode low that was
        // this mode's rather than the loop's own corrections since (see ModeInsulinShare)
        watchMs:           Long = TAIL_MS,   // how long after the mode ends its insulin is watched
        learningEnabled:   Boolean = true,   // user switch. Off FREEZES this learner: episodes stop
        // being judged and anything in flight is dropped, but the factor and ceiling already
        // learned keep being applied. Clearing them is the reset button's job.
        newPodBoost:       Boolean = false   // the new pod boost really added insulin (see NewPodBoost.addedInsulin).
        // An episode it touches is not judged at all, lows included: the boost changed the dose,
        // and a low already ends the boost for that pod. A tail watch still open is dropped.
    ) {
        if (!learningEnabled) {
            // Drop anything in flight rather than judging it whenever the switch comes back on —
            // that verdict would be about an episode from another era.
            if (activeScope != null || pendingScope != null) {
                activeScope = null; activeStartMs = 0L; episodeMaxDura = 1.0
                episodeStuckMs = 0L; episodeAtCeilingMs = 0L; episodeAtFloorMs = 0L
                episodeStallNote = null; episodeRejectedMs = 0L; clearRun()
                pendingScope = null
                lastOutcome = "DURA learning is switched off — episode dropped"
                aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            }
            return
        }
        if (newPodBoost && pendingScope != null) {
            lastOutcome = "${pendingScope!!.label} tail not judged — new pod boost started"
            aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            pendingScope = null
        }
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
                episodeStuckMs = 0L
                episodeAtCeilingMs = 0L
                episodeAtFloorMs   = 0L
                episodeStallNote   = null
                episodeRejectedMs  = 0L
                clearRun()
                episodeWentLow     = false
                episodeCarriedInLow = lowActive || undershootActive
                episodeStartBgMgdl  = bgMgdl
                episodeBoosted      = false
            }
            if (newPodBoost) episodeBoosted = true
            if (duraMult > episodeMaxDura) episodeMaxDura = duraMult
            val elevatedAndStuck = targetMgdl > 0.0 && bgMgdl >= targetMgdl + stuckMarginMgdl() && duraStuckMinutes > 0.0
            if (!elevatedAndStuck) closeRun()
            else {
                if (runStartMs == 0L) { runStartMs = nowMs; runLastMs = nowMs }
                val stepMs = (nowMs - runLastMs).coerceIn(0L, 10 * 60_000L)  // cap a gap in readings
                // Floor first: when both bind, the floor is the one actually stopping DURA —
                // loosening the ceiling would change nothing. Held at either limit counts as
                // engaged: DURA is doing everything it is allowed to, whatever the multiplier.
                when {
                    duraAtFloor                     -> { runAtFloorMs += stepMs; runEngagedMs += stepMs }
                    duraAtCeiling                   -> { runAtCeilingMs += stepMs; runEngagedMs += stepMs }
                    duraMult >= ENGAGED_THRESHOLD   -> runEngagedMs += stepMs
                }
                runBgs.add(bgMgdl)
                runLastMs = nowMs
            }
            // A low while the mode is still running, with DURA meaningfully engaged, is
            // immediate evidence — act now rather than waiting for the mode to expire.
            if (episodeCarriedInLow &&
                ((!lowActive && !undershootActive) || bgMgdl < episodeStartBgMgdl - CARRIED_IN_LOW_DEEPER_MGDL))
                episodeCarriedInLow = false
            val undershootCounts = undershootActive && (nowMs - activeStartMs) >= UNDERSHOOT_MIN_ELAPSED_MS
            val lowCounts = !episodeCarriedInLow && (lowActive || undershootCounts)
            if (lowCounts) episodeWentLow = true
            if (lowCounts && episodeMaxDura >= ENGAGED_THRESHOLD && !episodeBoosted) {
                reduce(activeScope!!, exerciseSuspected, episodeMaxDura, "during", undershoot = !lowActive)
                episodeMaxDura = 1.0  // don't fire repeatedly on one low
            }
            return
        }

        if (activeScope != null) {
            closeRun()
            val ended = activeScope!!
            val maxDura = episodeMaxDura
            activeScope   = null
            activeStartMs = 0L
            // Stuck long opens the watch too, even with DURA under the engaged bar: a ceiling cut
            // down near ×1.09 holds DURA under it by construction, and that is exactly the
            // episode that needs to be able to loosen it.
            // A low during the mode was already charged (or wasn't DURA's); either way this episode
            // is finished as evidence — its stall must not come back later as "DURA wasn't enough".
            // A long run that failed a check opens it as well, only so the outcome line can say
            // why an hour stuck high changed nothing.
            if (episodeBoosted || newPodBoost) {
                episodeBoosted = false
                lastOutcome = "${ended.label} not scored — new pod boost"
                aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            } else if (!episodeWentLow && (maxDura >= ENGAGED_THRESHOLD || episodeStuckMs >= STUCK_LONG_MS || episodeStallNote != null)) {
                pendingScope   = ended
                pendingUntilMs = nowMs + watchMs
                pendingMaxDura = maxDura
                pendingStuckMs     = episodeStuckMs
                pendingAtCeilingMs = episodeAtCeilingMs
                pendingAtFloorMs   = episodeAtFloorMs
                pendingStallNote   = episodeStallNote
            }
        }

        val p = pendingScope ?: return
        if (lowActive || undershootActive) {
            // Opened only for being stuck, with DURA never engaged: the low isn't DURA's. Close the
            // watch without strengthening — a low always wins — and without cutting either.
            if (!ModeInsulinShare.chargeable(modeInsulinShare)) {
                lastOutcome = "${p.label} went low afterwards, but only ${ModeInsulinShare.describe(modeInsulinShare, p.label)} — the loop's own insulin since, not DURA's"
                aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            } else if (pendingMaxDura >= ENGAGED_THRESHOLD)
                reduce(p, exerciseSuspected, pendingMaxDura, "in the tail after", undershoot = !lowActive,
                       share = modeInsulinShare)
            else {
                lastOutcome = "${p.label} was stuck but went ${if (lowActive) "low" else "near the low guard"} afterwards with DURA barely engaged — no change"
                aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            }
            pendingScope = null
            return
        }
        if (nowMs >= pendingUntilMs) {
            val s = states.getOrPut(p.key) { ModeState() }
            s.episodes++
            val stuckMins = pendingStuckMs / 60_000
            lastOutcome = when {
                pendingStuckMs < stuckLongMs() ->
                    "${p.label} DURA ×${"%.2f".format(pendingMaxDura)} landed without a low or a qualifying stall" +
                        (pendingStallNote?.let { " ($it)" } ?: "") + " — no change"
                pendingAtFloorMs >= HELD_AT_LIMIT_MS ->
                    "${p.label} stuck ${stuckMins}min with DURA held at your configured floor — nothing left for the learner to give; lower the floor in settings if this keeps happening"
                s.ceiling != NO_CEILING && pendingAtCeilingMs >= HELD_AT_LIMIT_MS -> {
                    val before = s.ceiling
                    val excess = s.ceiling - 1.0
                    val raised = 1.0 + maxOf(excess * capLoosenStep(), excess + CAP_LOOSEN_MIN)
                    s.ceiling = if (raised > CAP_CLEAR_ABOVE) NO_CEILING else raised
                    "${p.label} stuck ${stuckMins}min with DURA held at its ceiling — ceiling ${fmtCeiling(before)} → ${fmtCeiling(s.ceiling)}"
                }
                s.factor >= FACTOR_MAX - 1e-9 ->
                    "${p.label} stuck ${stuckMins}min with DURA at full configured strength — raise DURA strength in settings if this keeps happening"
                else -> {
                    val before = s.factor
                    s.factor = (s.factor * strengthenStep()).coerceIn(FACTOR_MIN, FACTOR_MAX)
                    "${p.label} stuck ${stuckMins}min while DURA was still climbing — strength ×${"%.2f".format(before)} → ×${"%.2f".format(s.factor)}"
                }
            } + " (n=${s.episodes})"
            persist()
            aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
            pendingScope = null
        }
    }

    private fun reduce(mode: Scope, exerciseSuspected: Boolean, maxDura: Double, whenTxt: String, undershoot: Boolean = false,
                       share: Double = 1.0) {
        val s = states.getOrPut(mode.key) { ModeState() }
        var fraction = bias().safetyScale
        if (exerciseSuspected) fraction *= UNEXPLAINED_STEP_FRACTION
        if (undershoot)        fraction *= UNDERSHOOT_STEP_FRACTION
        fraction *= share.coerceIn(0.0, 1.0)
        val step = 1.0 - (1.0 - REDUCE_STEP) * fraction
        val factorBefore = s.factor
        s.factor = (s.factor * step).coerceIn(FACTOR_MIN, FACTOR_MAX)
        // Cut from the peak this episode actually reached, not from the old ceiling: DURA stopping
        // short of the ceiling and still causing a low means the ceiling was never the limit that
        // mattered. min() keeps a ceiling that is already tighter than this episode's cut.
        val capStep = 1.0 - (1.0 - CAP_REDUCE_STEP) * fraction
        val ceilingBefore = s.ceiling
        s.ceiling = minOf(s.ceiling, 1.0 + (maxDura - 1.0) * capStep).coerceAtLeast(CAP_MIN)
        s.episodes++
        persist()
        // Plain words: what happened, why DURA is the one charged, what changed, and why the step
        // is smaller when it is.
        val event = if (undershoot) "BG dropped near the low guard" else "BG went low"
        val where = if (whenTxt == "during") "during ${mode.label}" else "after ${mode.label} ended"
        val why = buildList {
            if (undershoot) add("near the low guard, not under it")
            if (exerciseSuspected) add("BG fell faster than insulin explains (exercise?)")
            if (share < 1.0) add("${(share * 100).toInt()}% of the insulin was this meal's")
        }
        lastOutcome = "$event $where, with DURA pushing (×${"%.2f".format(maxDura)}) — DURA takes the blame. " +
            "Max DURA boost ${fmtCeiling(ceilingBefore)} → ${fmtCeiling(s.ceiling)}, strength ×${"%.2f".format(factorBefore)} → ×${"%.2f".format(s.factor)}" +
            (if (why.isNotEmpty()) " (smaller cut: ${why.joinToString(", ")})" else "") +
            " (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "DuraStrengthLearner: $lastOutcome")
    }

    /**
     * Ends the open stuck run and judges it. Called when BG stops being stuck-elevated and when
     * the mode ends. A run that qualifies replaces the episode's best if it is longer; one that
     * lasted long enough but failed a check leaves a note saying which, so "no change" explains
     * itself on the SI tab.
     */
    private fun closeRun() {
        if (runStartMs == 0L) return
        val lenMs = runLastMs - runStartMs
        if (lenMs >= stuckLongMs()) {
            val n = NET_DELTA_READINGS.coerceAtMost(runBgs.size)
            val netDrop = runBgs.take(n).average() - runBgs.takeLast(n).average()
            val reject = when {
                runEngagedMs < engagedMinMs() ->
                    "stuck ${lenMs / 60_000}min but DURA engaged only ${runEngagedMs / 60_000}min of it"
                netDrop > MAX_NET_DROP_MGDL ->
                    "stuck ${lenMs / 60_000}min but already drifting down ${BgText.bg(netDrop)}"
                else -> null
            }
            if (reject == null) {
                if (lenMs > episodeStuckMs) {
                    episodeStuckMs     = lenMs
                    episodeAtCeilingMs = runAtCeilingMs
                    episodeAtFloorMs   = runAtFloorMs
                }
            } else if (lenMs > episodeRejectedMs) {
                episodeRejectedMs = lenMs
                episodeStallNote  = reject
            }
        }
        clearRun()
    }

    private fun clearRun() {
        runStartMs = 0L; runLastMs = 0L
        runEngagedMs = 0L; runAtCeilingMs = 0L; runAtFloorMs = 0L
        runBgs.clear()
    }

    // -- User bias ------------------------------------------------------------
    private fun bias() = LearningBias.from(sp)
    private fun strengthenStep()  = scaleStrengthenAmount(STRENGTHEN_STEP - 1.0, bias()) + 1.0
    private fun capLoosenStep()   = scaleStrengthenAmount(CAP_LOOSEN_STEP - 1.0, bias()) + 1.0
    private fun stuckLongMs()     = (STUCK_LONG_MS * bias().barScale).toLong()
    private fun stuckMarginMgdl() = STUCK_MARGIN_MGDL * bias().barScale
    private fun engagedMinMs()    = (ENGAGED_MIN_MS * bias().barScale).toLong()

    private fun fmtCeiling(c: Double) = if (c == NO_CEILING) "none" else "×${"%.2f".format(c)}"

    fun reset() {
        states.clear()
        activeScope = null; activeStartMs = 0L; episodeMaxDura = 1.0
        episodeStuckMs = 0L; episodeAtCeilingMs = 0L; episodeAtFloorMs = 0L
        episodeStallNote = null; episodeRejectedMs = 0L; clearRun()
        pendingScope = null
        lastOutcome = ""
        sp.edit { putString(StringNonKey.ApsSmartInsulinDuraStrengthLearnerState.key, "") }
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
            sp.edit { putString(StringNonKey.ApsSmartInsulinDuraStrengthLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "DuraStrengthLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinDuraStrengthLearnerState.key, StringNonKey.ApsSmartInsulinDuraStrengthLearnerState.defaultValue)
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

/** What DURA actually did this cycle once both limits were applied. */
internal data class DuraLimited(
    /** ISF to dose from. */
    val isfMgdl: Double,
    /** Strengthening actually delivered: pre-DURA ISF ÷ final ISF. 1.0 = none. This, not the raw
     *  tracker value, is what the learners must see — a low is judged against what was dosed. */
    val effectiveMult: Double,
    /** The learned ceiling clamped DURA's own push this cycle. */
    val atCeiling: Boolean,
    /** The configured floor clamped DURA's own push this cycle. */
    val atFloor: Boolean
)

/**
 * Applies the learned ceiling and the configured ISF floor to DURA's raw multiplier.
 *
 * "Held at a limit" means the clamp fired against DURA's own push — the uncapped multiplier asked
 * for more than the limit allowed — never that the result merely equals the limit:
 *   - ceiling: raw multiplier strictly above the ceiling.
 *   - floor: the pre-DURA ISF was above the floor (DURA had room) and DURA's push would have taken
 *     it below. An ISF already at or under the floor before DURA ran is not DURA being held — DURA
 *     had nothing to give, and counting any ×1.01 there as "held" would read as engaged when it
 *     wasn't.
 * The floor only ever limits DURA's strengthening. It never makes the ISF weaker than it was
 * before DURA: with the base already under the floor, DURA simply adds nothing.
 * When both limits would bind, the floor is the one actually stopping DURA, so only it is reported.
 */
internal fun applyDuraLimits(preDuraIsfMgdl: Double, rawMult: Double, ceiling: Double, floorMgdl: Double): DuraLimited {
    if (preDuraIsfMgdl <= 0.0 || rawMult <= 1.0) return DuraLimited(preDuraIsfMgdl, 1.0, atCeiling = false, atFloor = false)
    val ceilingClamped = rawMult > ceiling
    val mult           = minOf(rawMult, ceiling)
    val duraIsf        = preDuraIsfMgdl / mult
    val floorApplies   = floorMgdl > 0.0
    val floorClamped   = floorApplies && preDuraIsfMgdl > floorMgdl && duraIsf < floorMgdl
    val isf = when {
        !floorApplies                   -> duraIsf
        preDuraIsfMgdl <= floorMgdl     -> preDuraIsfMgdl        // no room: DURA adds nothing, and never weakens
        else                            -> maxOf(duraIsf, floorMgdl)
    }
    return DuraLimited(isf, preDuraIsfMgdl / isf, atCeiling = ceilingClamped && !floorClamped, atFloor = floorClamped)
}
