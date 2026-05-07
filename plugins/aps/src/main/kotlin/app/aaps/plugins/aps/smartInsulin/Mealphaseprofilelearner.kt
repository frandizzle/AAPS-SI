package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MealPhaseProfileLearner
 *
 * Learns per-mode, per-phase insulin delivery multipliers from completed meal sessions.
 * Consumes [MealPhaseTracker.CompletedMealSession] outcome data and adjusts:
 *
 *   carbSmbFractionMult  — scales SMB fraction during carb phase
 *                          Low in carb phase → reduce (less SMB aggression)
 *
 *   pfIsfMult            — scales ISF during protein/fat plateau
 *                          Low in P/F → raise ISF (less aggressive corrections)
 *                          Sustained high in P/F → lower ISF (more aggressive)
 *
 *   tailIsfMult          — scales ISF during tail phase
 *                          Low in tail → raise ISF (back off earlier)
 *
 * All multipliers start at 1.0 (no change from configured values) and drift
 * based on outcomes. Asymmetric learning rate: lows penalise fast (safety),
 * recovery is slow (stability).
 *
 *   LOW_PENALTY  = 0.20  → 20% step reduction on a low
 *   HIGH_NUDGE   = 0.05  → 5% step increase on sustained P/F high (conservative)
 *   RECOVERY     = 0.02  → 2% drift back per clean session with no issue
 *
 * After one low: needs ~10 clean sessions to drift back to 1.0. This is intentional.
 *
 * Confidence gate: multipliers only influence dosing once [MIN_SESSIONS_FOR_CONFIDENCE]
 * sessions have been observed for that mode. Below threshold, configured values are used.
 * This means no dosing changes for the first few meals — pure data collection.
 *
 * State is persisted per mode to SharedPreferences and survives restarts.
 */
@Singleton
class MealPhaseProfileLearner @Inject constructor(
    private val aapsLogger:           AAPSLogger,
    private val sp:                   SP,
    private val mealPhaseTracker:     MealPhaseTracker
) {

    // ── Learning rates ────────────────────────────────────────────────────────

    private val LOW_PENALTY   = 0.20   // 20% reduction on a low — your specified value
    private val PF_HIGH_NUDGE = 0.05   // 5% increase on sustained P/F high (conservative)
    private val RECOVERY_STEP = 0.02   // 2% drift back per clean session
    private val MANUAL_NUDGE  = 0.10   // 10% SMB fraction increase if manual bolus needed

    // ── Multiplier bounds ─────────────────────────────────────────────────────

    private val MULT_MIN = 0.40  // never cut more than 60% from configured value
    private val MULT_MAX = 1.50  // never push more than 50% above configured value

    // ── Confidence gate ───────────────────────────────────────────────────────

    // Multipliers don't influence dosing until this many sessions observed per mode.
    // Below threshold: always returns 1.0 (no change from configured).
    private val MIN_SESSIONS_FOR_CONFIDENCE = 5

    // ── Per-mode learned state ────────────────────────────────────────────────

    data class ModeProfile(
        val mode:                MealMode,
        var carbSmbFractionMult: Double = 1.0,  // < 1.0 = less SMB in carb phase
        var pfIsfMult:           Double = 1.0,  // > 1.0 = higher ISF = less aggressive in P/F
        var tailIsfMult:         Double = 1.0,  // > 1.0 = higher ISF = less aggressive in tail
        var sessionCount:        Int    = 0,    // total sessions observed
        var lastUpdatedMs:       Long   = 0L
    ) {
        val hasConfidence: Boolean get() = sessionCount >= 5

        override fun toString() =
            "mode=${mode.label} n=$sessionCount " +
                "carbSmb=${"%.3f".format(carbSmbFractionMult)} " +
                "pfIsf=${"%.3f".format(pfIsfMult)} " +
                "tailIsf=${"%.3f".format(tailIsfMult)}"
    }

    private val profiles: MutableMap<MealMode, ModeProfile> = mutableMapOf()

    init {
        // Load persisted state for all meal modes
        MealMode.entries.filter { it != MealMode.FASTING }.forEach { mode ->
            profiles[mode] = loadProfile(mode)
        }
        // Wire ourselves into MealPhaseTracker to receive completed sessions
        mealPhaseTracker.onSessionComplete = { session -> onSessionComplete(session) }
        aapsLogger.debug(LTag.APS, "MealPhaseProfileLearner: initialised, listening for sessions")
    }

    // ── Public accessors — used by dosing path ────────────────────────────────

    /**
     * Multiplier for SMB fraction during the carb phase for [mode].
     * Returns 1.0 (no change) until MIN_SESSIONS_FOR_CONFIDENCE sessions observed.
     * @return multiplier in [MULT_MIN, MULT_MAX]
     */
    fun carbSmbFractionMult(mode: MealMode): Double {
        val p = profiles[mode] ?: return 1.0
        return if (p.hasConfidence) p.carbSmbFractionMult else 1.0
    }

    /**
     * ISF multiplier during the P/F phase for [mode].
     * > 1.0 = higher ISF = less aggressive corrections during plateau.
     * Returns 1.0 until confidence threshold reached.
     */
    fun pfIsfMult(mode: MealMode): Double {
        val p = profiles[mode] ?: return 1.0
        return if (p.hasConfidence) p.pfIsfMult else 1.0
    }

    /**
     * ISF multiplier during the tail phase for [mode].
     * > 1.0 = higher ISF = less aggressive = crash protection.
     * Returns 1.0 until confidence threshold reached.
     */
    fun tailIsfMult(mode: MealMode): Double {
        val p = profiles[mode] ?: return 1.0
        return if (p.hasConfidence) p.tailIsfMult else 1.0
    }

    /** Human-readable status for display in SmartInsulinScreen */
    fun statusForMode(mode: MealMode): String {
        val p = profiles[mode] ?: return "No data"
        return if (!p.hasConfidence) {
            "Building confidence (${p.sessionCount}/$MIN_SESSIONS_FOR_CONFIDENCE sessions)"
        } else {
            "n=${p.sessionCount} | carbSmb×${"%.2f".format(p.carbSmbFractionMult)} | pfISF×${"%.2f".format(p.pfIsfMult)} | tailISF×${"%.2f".format(p.tailIsfMult)}"
        }
    }

    /** Full debug string for all modes with data */
    val debugSummary: String get() = profiles.values
        .filter { it.sessionCount > 0 }
        .joinToString("\n") { p ->
            val conf = if (p.hasConfidence) "✓" else "(${p.sessionCount}/$MIN_SESSIONS_FOR_CONFIDENCE)"
            "${p.mode.label} $conf carbSmb×${"%.3f".format(p.carbSmbFractionMult)} pfISF×${"%.3f".format(p.pfIsfMult)} tailISF×${"%.3f".format(p.tailIsfMult)}"
        }.ifEmpty { "No sessions yet" }

    // ── Session consumer ──────────────────────────────────────────────────────

    private fun onSessionComplete(session: MealPhaseTracker.CompletedMealSession) {
        val p = profiles.getOrPut(session.mode) { ModeProfile(session.mode) }
        p.sessionCount++
        p.lastUpdatedMs = session.sessionEndMs

        val prev = p.copy()

        // ── Carb phase — SMB fraction multiplier ─────────────────────────────
        when {
            session.carbPhaseWentLow -> {
                // Low during carbs = SMBs too aggressive → cut fraction
                p.carbSmbFractionMult = (p.carbSmbFractionMult * (1.0 - LOW_PENALTY)).coerceIn(MULT_MIN, MULT_MAX)
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseProfileLearner [${session.mode.label}] CARB LOW penalty: " +
                                     "carbSmbMult ${"%.3f".format(prev.carbSmbFractionMult)}→${"%.3f".format(p.carbSmbFractionMult)}")
            }
            session.manualBolusDetected -> {
                // Manual top-up needed = SMBs delivered too little → nudge fraction up
                p.carbSmbFractionMult = (p.carbSmbFractionMult * (1.0 + MANUAL_NUDGE)).coerceIn(MULT_MIN, MULT_MAX)
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseProfileLearner [${session.mode.label}] CARB manual bolus nudge: " +
                                     "carbSmbMult ${"%.3f".format(prev.carbSmbFractionMult)}→${"%.3f".format(p.carbSmbFractionMult)}")
            }
            else -> {
                // Clean session — slow drift back toward 1.0
                p.carbSmbFractionMult = drift(p.carbSmbFractionMult)
            }
        }

        // ── P/F phase — ISF multiplier ────────────────────────────────────────
        when {
            session.pfPhaseWentLow -> {
                // Low during plateau = ISF too aggressive → raise ISF mult (less aggressive)
                p.pfIsfMult = (p.pfIsfMult * (1.0 + LOW_PENALTY)).coerceIn(MULT_MIN, MULT_MAX)
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseProfileLearner [${session.mode.label}] P/F LOW penalty: " +
                                     "pfIsfMult ${"%.3f".format(prev.pfIsfMult)}→${"%.3f".format(p.pfIsfMult)}")
            }
            session.pfPhaseWentHigh -> {
                // Sustained high during plateau = ISF too conservative → lower ISF mult (more aggressive)
                // Smaller step than low penalty — high is less urgent than low
                p.pfIsfMult = (p.pfIsfMult * (1.0 - PF_HIGH_NUDGE)).coerceIn(MULT_MIN, MULT_MAX)
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseProfileLearner [${session.mode.label}] P/F HIGH nudge: " +
                                     "pfIsfMult ${"%.3f".format(prev.pfIsfMult)}→${"%.3f".format(p.pfIsfMult)}")
            }
            else -> {
                p.pfIsfMult = drift(p.pfIsfMult)
            }
        }

        // ── Tail phase — ISF multiplier ───────────────────────────────────────
        when {
            session.tailPhaseWentLow -> {
                // Low during tail = taper too slow / ISF too aggressive → raise ISF mult
                p.tailIsfMult = (p.tailIsfMult * (1.0 + LOW_PENALTY)).coerceIn(MULT_MIN, MULT_MAX)
                aapsLogger.debug(LTag.APS,
                                 "MealPhaseProfileLearner [${session.mode.label}] TAIL LOW penalty: " +
                                     "tailIsfMult ${"%.3f".format(prev.tailIsfMult)}→${"%.3f".format(p.tailIsfMult)}")
            }
            else -> {
                p.tailIsfMult = drift(p.tailIsfMult)
            }
        }

        aapsLogger.debug(LTag.APS,
                         "MealPhaseProfileLearner [${session.mode.label}] session #${p.sessionCount} complete " +
                             "clean=${session.isClean} → $p")

        saveProfile(p)
    }

    // ── Drift back toward 1.0 on clean sessions ───────────────────────────────
    // Asymmetric: if below 1.0 (cut due to lows) drift up slowly.
    //             if above 1.0 (raised) also drift back slowly.
    // Both directions use RECOVERY_STEP — the asymmetry is in the penalty size,
    // not in the recovery speed.
    private fun drift(current: Double): Double =
        (current + (1.0 - current) * RECOVERY_STEP).coerceIn(MULT_MIN, MULT_MAX)

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun resetMode(mode: MealMode) {
        profiles[mode] = ModeProfile(mode)
        saveProfile(profiles[mode]!!)
        aapsLogger.debug(LTag.APS, "MealPhaseProfileLearner: reset mode=${mode.label}")
    }

    fun resetAll() {
        MealMode.entries.filter { it != MealMode.FASTING }.forEach { resetMode(it) }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun prefKey(mode: MealMode) = "si_mealphase_${mode.name.lowercase()}"

    private fun saveProfile(p: ModeProfile) {
        try {
            val json = JSONObject().apply {
                put("carbSmbFractionMult", p.carbSmbFractionMult)
                put("pfIsfMult",           p.pfIsfMult)
                put("tailIsfMult",         p.tailIsfMult)
                put("sessionCount",        p.sessionCount)
                put("lastUpdatedMs",       p.lastUpdatedMs)
            }
            sp.edit { putString(prefKey(p.mode), json.toString()) }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "MealPhaseProfileLearner: save failed ${p.mode}: ${e.message}")
        }
    }

    private fun loadProfile(mode: MealMode): ModeProfile {
        return try {
            val raw = sp.getString(prefKey(mode), "")
            if (raw.isNullOrBlank()) return ModeProfile(mode)
            val json = JSONObject(raw)
            ModeProfile(
                mode                = mode,
                carbSmbFractionMult = json.optDouble("carbSmbFractionMult", 1.0),
                pfIsfMult           = json.optDouble("pfIsfMult",           1.0),
                tailIsfMult         = json.optDouble("tailIsfMult",         1.0),
                sessionCount        = json.optInt("sessionCount",            0),
                lastUpdatedMs       = json.optLong("lastUpdatedMs",          0L)
            )
        } catch (_: Exception) {
            ModeProfile(mode)
        }
    }
}