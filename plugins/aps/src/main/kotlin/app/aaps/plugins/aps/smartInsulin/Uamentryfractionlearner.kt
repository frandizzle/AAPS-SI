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
 * Episode-outcome learning for the UAM *entry* SMB fraction — the front-loading of the first
 * few SMBs after a UAM mode auto-fires.
 *
 * This is a SHAPE knob (how early the insulin arrives), deliberately separated from
 * [ModeIsfLearner]'s MAGNITUDE knob (how much insulin overall). Two learners on one error
 * signal would double-correct every mistake, so the two are arbitrated by the TIMING of the
 * evidence — shape and magnitude have distinguishable signatures:
 *
 *   - Low EARLY (within [ENTRY_ATTRIBUTION_MS] of entry, while the entry burst is peaking)
 *       → too front-loaded → fraction DOWN. [ModeIsfLearner] skips that episode entirely.
 *   - Big BG excursion after entry BUT the episode still ended on target
 *       → total insulin was right, it just arrived too late → fraction UP.
 *   - Ended HIGH → that's a magnitude problem, not shape → fraction unchanged
 *       ([ModeIsfLearner] owns it).
 *   - Low LATE (in the settling tail) → magnitude problem → fraction unchanged.
 *
 * Only the UAM entry modes are learned — P/F is a tail correction, not a meal-entry event, and
 * the manually-activated modes don't use the entry-burst mechanism at all.
 *
 * The learned value is an ADDITIVE offset to the user's configured per-mode fraction, railed to
 * [OFFSET_MIN]..[OFFSET_MAX] so it can refine the setting but never wander far from it. The
 * entry SMB *count* stays manual by design: it's a coarse integer whose effects overlap heavily
 * with the fraction, and learning both would re-create the double-actuation problem this
 * arbitration exists to avoid.
 */
@Singleton
class UamEntryFractionLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private class ModeState(var offset: Double = 0.0, var episodes: Int = 0, var baseSig: Double = Double.NaN)

    private val states = mutableMapOf<MealMode, ModeState>()

    // Currently-active entry episode
    private var activeMode:         MealMode? = null
    private var activeStartMs       = 0L
    private var bgAtEntry           = 0.0
    private var maxBgInEntryWindow  = 0.0
    private var maxBgOffsetMs       = 0L    // when that peak occurred, relative to entry
    private var earlyLow            = false

    // Pending post-episode evaluation (settling tail)
    private var pendingMode:          MealMode? = null
    private var pendingEvalAtMs       = 0L
    private var pendingExcursion      = 0.0
    private var pendingPeakOffsetMs   = 0L
    private var pendingTailGrams      = 0.0

    /** Human-readable summary of the most recent learning decision — for the SI tab. */
    var lastOutcome = ""
        private set

    init { restore() }

    companion object {
        /** Lows within this long after a UAM entry are attributed to the entry burst's shape,
         *  not to the mode's overall ISF. [ModeIsfLearner] reads this to arbitrate. */
        const val ENTRY_ATTRIBUTION_MS = 75 * 60_000L

        private const val TAIL_MS                    = 75 * 60_000L
        private const val OFFSET_MIN                 = -0.25
        private const val OFFSET_MAX                 = 0.25
        private const val STRENGTHEN_STEP            = 0.03   // +3% fraction — entry arrived too late
        private const val WEAKEN_STEP                = 0.06   // -6% — asymmetric, safety-biased
        private const val EXCURSION_STRENGTHEN_MGDL  = 45.0   // ~2.5 mmol rise after entry = too slow off the mark

        /**
         * A post-entry spike only counts as "the entry burst was too small" if the peak arrived
         * late enough that a bigger entry SMB could plausibly have blunted it.
         *
         * High-GI food wins the race outright: UAM can't fire until BG has been rising for
         * ~15 min, so entry is already well into the meal, and a subcutaneous SMB given then
         * needs ~45-75 min to peak. A BG top-out 20 min after entry happened before the entry
         * insulin meaningfully acted — front-loading harder wouldn't have changed it, it would
         * just put more insulin in the system that lands AFTER the glucose has cleared. That
         * shows up as a LATE low, which the arbitration hands to ModeIsfLearner — so without
         * this gate the two learners actively fight: entry fraction railing up while mode ISF
         * gets weakened, converging on over-front-loaded and under-dosed.
         *
         * Set below typical insulin peak: by ~40 min an entry SMB is contributing enough that a
         * larger one would have measurably altered the curve.
         */
        private const val MIN_PEAK_OFFSET_FOR_STRENGTHEN_MS = 40 * 60_000L
        private const val ON_TARGET_MARGIN_MGDL      = 18.0   // ~1 mmol — above this at eval is a magnitude problem
        private const val TAIL_CONTAMINATION_G       = 8.0    // est. grams in the tail that void the evaluation
        private const val FRACTION_MIN               = 0.1
        private const val FRACTION_MAX               = 1.0

        private const val K_OFFSET = "offset"
        private const val K_N      = "n"
        private const val K_BASE   = "baseSig"

        /** True for modes that actually use the UAM entry-burst mechanism. */
        fun isEntryMode(mode: MealMode?): Boolean =
            mode != null && mode.isUam && mode != MealMode.UAM_PROTEIN_FAT
    }

    /** Learned additive offset for a mode (0.0 = no adjustment yet). */
    fun offset(mode: MealMode): Double = states[mode]?.offset ?: 0.0

    /** The configured fraction with this mode's learned offset applied, clamped to sane bounds. */
    fun adjustedFraction(mode: MealMode, configuredFraction: Double): Double =
        if (!isEntryMode(mode)) configuredFraction
        else (configuredFraction + offset(mode)).coerceIn(FRACTION_MIN, FRACTION_MAX)

    fun statusString(): String = buildString {
        MealMode.entries.forEach { mode ->
            val s = states[mode] ?: return@forEach
            if (s.episodes > 0) {
                val sign = if (s.offset >= 0) "+" else ""
                appendLine("${mode.label.padEnd(20)} $sign${"%.2f".format(s.offset)} (n=${s.episodes})")
            }
        }
        if (lastOutcome.isNotEmpty()) appendLine("Last: $lastOutcome")
    }.trimEnd()

    /**
     * Call once per loop cycle. [baseSignature] is the user's configured fraction for the active
     * mode — a change resets that mode's learned offset (the offset was a correction relative to
     * the old setting; keeping it would double-apply the user's own adjustment).
     */
    fun onCycle(
        activeModeNow:  MealMode?,
        modeStartMs:    Long,
        bgMgdl:         Double,
        targetMgdl:     Double,
        lowActive:      Boolean,
        deltaMgdl:      Double,
        activityPerMin: Double,
        fastingIsfMgdl: Double,
        carbRatio:      Double,
        nowMs:          Long,
        baseSignature:  Double = 0.0
    ) {
        if (isEntryMode(activeModeNow)) {
            val mode = activeModeNow!!
            if (activeMode == null || modeStartMs != activeStartMs) {
                pendingMode?.let { skipPending("superseded by new ${mode.label} activation") }

                val s = states.getOrPut(mode) { ModeState() }
                if (!s.baseSig.isNaN() && kotlin.math.abs(s.baseSig - baseSignature) > 0.001 &&
                    (s.offset != 0.0 || s.episodes > 0)) {
                    s.offset = 0.0
                    s.episodes = 0
                    lastOutcome = "${mode.label} entry fraction changed — learned offset reset"
                    aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
                }
                s.baseSig = baseSignature
                persist()

                activeMode         = mode
                activeStartMs      = modeStartMs
                bgAtEntry          = bgMgdl
                maxBgInEntryWindow = bgMgdl
                maxBgOffsetMs      = 0L
                earlyLow           = false
            }
            // Only the entry window shapes this learner's evidence — later movement is the
            // ISF learner's territory.
            val elapsed = nowMs - activeStartMs
            if (elapsed <= ENTRY_ATTRIBUTION_MS) {
                if (bgMgdl > maxBgInEntryWindow) {
                    maxBgInEntryWindow = bgMgdl
                    maxBgOffsetMs      = elapsed
                }
                if (lowActive) earlyLow = true
            }
            return
        }

        // Entry mode just ended (or switched to a non-entry mode)?
        if (activeMode != null) {
            val ended        = activeMode!!
            val hadEarlyLow  = earlyLow
            val excursion    = maxBgInEntryWindow - bgAtEntry
            activeMode    = null
            activeStartMs = 0L
            if (hadEarlyLow) {
                // Definitive shape evidence — lands immediately, not skippable by later noise.
                applyOutcome(ended, -WEAKEN_STEP, "low soon after ${ended.label} entry — entry fraction reduced")
            } else {
                pendingMode        = ended
                pendingEvalAtMs    = nowMs + TAIL_MS
                pendingExcursion   = excursion
                pendingPeakOffsetMs = maxBgOffsetMs
                pendingTailGrams   = 0.0
            }
        }

        val p = pendingMode ?: return
        if (lowActive) {
            // A low this late is a magnitude problem — ModeIsfLearner weakens for it. Entry
            // shape gets no evidence from it, so abandon the evaluation unchanged.
            skipPending("late low in ${p.label} tail — magnitude signal, left to mode ISF")
            return
        }
        pendingTailGrams += tailGramsThisCycle(deltaMgdl, activityPerMin, fastingIsfMgdl, carbRatio)
        if (pendingTailGrams > TAIL_CONTAMINATION_G) {
            skipPending("fresh absorption (~${"%.0f".format(pendingTailGrams)}g) in ${p.label} tail")
            return
        }
        if (nowMs >= pendingEvalAtMs) {
            val endedOnTarget = bgMgdl <= targetMgdl + ON_TARGET_MARGIN_MGDL
            val bigExcursion  = pendingExcursion >= EXCURSION_STRENGTHEN_MGDL
            val peakWasLate   = pendingPeakOffsetMs >= MIN_PEAK_OFFSET_FOR_STRENGTHEN_MS
            val peakMins      = pendingPeakOffsetMs / 60_000
            when {
                bigExcursion && endedOnTarget && peakWasLate ->
                    applyOutcome(p, STRENGTHEN_STEP,
                                 "${p.label} spiked ${"%.1f".format(pendingExcursion / 18.0)}mmol peaking ${peakMins}min after entry, ended on target — entry fraction raised")
                bigExcursion && endedOnTarget -> {
                    // Fast-carb signature: the peak beat the entry insulin, so a bigger entry
                    // SMB could not have prevented it — only landed later and caused a low.
                    val s = states.getOrPut(p) { ModeState() }
                    s.episodes++
                    persist()
                    lastOutcome = "${p.label} spiked ${"%.1f".format(pendingExcursion / 18.0)}mmol but peaked only ${peakMins}min after entry — fast carbs, not a shape problem (n=${s.episodes})"
                    aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
                }
                else -> {
                    val s = states.getOrPut(p) { ModeState() }
                    s.episodes++
                    persist()
                    lastOutcome = "${p.label} entry shape OK — no change (n=${s.episodes})"
                    aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
                }
            }
            pendingMode = null
        }
    }

    /** Insulin-pull-only residual — conservative fasting-rules "is something still absorbing". */
    private fun tailGramsThisCycle(deltaMgdl: Double, activityPerMin: Double, isfMgdl: Double, carbRatio: Double): Double {
        if (carbRatio <= 0.0 || isfMgdl <= 0.0) return 0.0
        val ci = deltaMgdl - (-activityPerMin * isfMgdl * 5.0)
        if (ci <= 0.0) return 0.0
        return ci / (isfMgdl / carbRatio)
    }

    private fun applyOutcome(mode: MealMode, delta: Double, reason: String) {
        val s = states.getOrPut(mode) { ModeState() }
        s.offset = (s.offset + delta).coerceIn(OFFSET_MIN, OFFSET_MAX)
        s.episodes++
        persist()
        val sign = if (s.offset >= 0) "+" else ""
        lastOutcome = "$reason → $sign${"%.2f".format(s.offset)} (n=${s.episodes})"
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: $lastOutcome")
    }

    private fun skipPending(reason: String) {
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: evaluation skipped — $reason")
        lastOutcome = "skipped: $reason"
        pendingMode = null
    }

    fun reset() {
        states.clear()
        activeMode = null; activeStartMs = 0L; earlyLow = false
        bgAtEntry = 0.0; maxBgInEntryWindow = 0.0; maxBgOffsetMs = 0L
        pendingMode = null
        lastOutcome = ""
        sp.edit { putString(StringKey.ApsSmartInsulinUamEntryFractionLearnerState.key, "") }
        aapsLogger.debug(LTag.APS, "UamEntryFractionLearner: reset")
    }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (mode, s) ->
                val obj = JSONObject().put(K_OFFSET, s.offset).put(K_N, s.episodes)
                if (!s.baseSig.isNaN()) obj.put(K_BASE, s.baseSig)  // JSON rejects NaN
                json.put(mode.name, obj)
            }
            sp.edit { putString(StringKey.ApsSmartInsulinUamEntryFractionLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "UamEntryFractionLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringKey.ApsSmartInsulinUamEntryFractionLearnerState.key, StringKey.ApsSmartInsulinUamEntryFractionLearnerState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val mode = try { MealMode.valueOf(key) } catch (_: Exception) { return@forEach }
                val obj  = json.optJSONObject(key) ?: return@forEach
                states[mode] = ModeState(
                    offset   = obj.optDouble(K_OFFSET, 0.0).coerceIn(OFFSET_MIN, OFFSET_MAX),
                    episodes = obj.optInt(K_N, 0),
                    baseSig  = obj.optDouble(K_BASE, Double.NaN)
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "UamEntryFractionLearner: restore failed: ${e.message}")
        }
    }
}
