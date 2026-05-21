package app.aaps.plugins.aps.smartInsulin.ice

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the user's currently-active announced meal (if any) and provides
 * lookups against the expected ICE curve.
 *
 * ## Lifecycle
 *
 * - User announces a meal via [announceMeal]. Any existing meal is replaced
 *   (only one active meal at a time — keeping it simple for v1).
 * - Each loop cycle, the plugin calls [expectedIceMgdlPerHour] with `now`
 *   to get the expected ICE at this moment.
 * - When the meal's absorption window expires (or [clearMeal] is called),
 *   the manager returns 0.0 and the loop falls back to observed-only ICE.
 *
 * ## Persistence
 *
 * The active meal is persisted to [SP] as a delimited string on every
 * mutation, and restored on construction. Survives process restarts, app
 * updates, and device reboots. On restore, expired meals are discarded so
 * a long reboot doesn't resurrect ancient state.
 *
 * ## Why a separate manager
 *
 * Decouples the meal description (data) from the curve math (pure functions
 * in [MealCurveBuilder]) and from the per-cycle lookup (this manager). UI
 * code that announces meals doesn't need to know about MealCurveBuilder;
 * loop code that consumes the value doesn't need to know about AnnouncedMeal.
 * Each layer talks through small interfaces.
 *
 * ## Thread safety
 *
 * The active meal is held in a [MutableStateFlow] so the UI can observe
 * announcements reactively without polling. All mutations go through the
 * StateFlow; reads via [expectedIceMgdlPerHour] are lock-free.
 */
@Singleton
class AnnouncedMealManager @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val sp: SP,
    private val dateUtil: DateUtil
) {

    companion object {
        /** SharedPreferences key for the serialized active meal. */
        private const val SP_KEY = "smartinsulin_active_announced_meal"
        /** Delimited serialization format version — bump if the format changes. */
        private const val SERIAL_VERSION = "v1"
        /** Field delimiter — chosen because none of the fields can contain it. */
        private const val DELIM = "|"
    }

    private val _activeMeal = MutableStateFlow<AnnouncedMeal?>(restoreFromSp())

    /** Observable handle for the UI — null when no meal is announced. */
    val activeMeal: StateFlow<AnnouncedMeal?> = _activeMeal.asStateFlow()

    init {
        _activeMeal.value?.let { restored ->
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: restored meal from SP — carbs=${restored.carbsG}g " +
                                 "protein=${restored.proteinG}g fat=${restored.fatG}g GI=${restored.giBucket.label} " +
                                 "age=${((dateUtil.now() - restored.announceTimestampMs) / 60_000L).toInt()}min")
        }
    }

    /**
     * Register a new announced meal. Replaces any existing meal (only one active
     * at a time in this version — overlapping meals are merged by the user into
     * one announcement with combined macros).
     */
    fun announceMeal(meal: AnnouncedMeal) {
        aapsLogger.debug(LTag.APS,
                         "AnnouncedMealManager: meal announced — carbs=${meal.carbsG}g protein=${meal.proteinG}g " +
                             "fat=${meal.fatG}g GI=${meal.giBucket.label} commitment=${meal.commitmentPct}%")
        _activeMeal.value = meal
        writeToSp(meal)
    }

    /** Discard the active meal — used when the user cancels or the loop force-clears. */
    fun clearMeal() {
        if (_activeMeal.value != null) {
            aapsLogger.debug(LTag.APS, "AnnouncedMealManager: meal cleared")
            _activeMeal.value = null
            writeToSp(null)
        }
    }

    /**
     * Replace the macros and/or GI bucket of the currently-active meal *without*
     * resetting [AnnouncedMeal.announceTimestampMs]. Critical UX requirement: when
     * the user realises mid-meal that they entered the wrong amount, they want to
     * correct the number without losing the absorption progress already tracked.
     * A naive cancel + re-announce would reset the timer and double-count the
     * early absorption.
     *
     * No-op if there is no active meal.
     *
     * Validation: macros are coerced to ≥ 0; GI bucket name is parsed
     * case-insensitively, falling back to the existing bucket if the input is
     * unrecognised.
     */
    fun editActiveMeal(carbsG: Double, proteinG: Double, fatG: Double, giBucketName: String) {
        val existing = _activeMeal.value ?: return
        val newBucket = when (giBucketName.uppercase()) {
            "FAST"   -> GiBucket.FAST
            "MEDIUM" -> GiBucket.MEDIUM
            "SLOW"   -> GiBucket.SLOW
            else     -> existing.giBucket
        }
        val edited = existing.copy(
            carbsG   = carbsG.coerceAtLeast(0.0),
            proteinG = proteinG.coerceAtLeast(0.0),
            fatG     = fatG.coerceAtLeast(0.0),
            giBucket = newBucket
            // announceTimestampMs deliberately preserved
        )
        aapsLogger.debug(LTag.APS,
                         "AnnouncedMealManager: meal edited — carbs=${edited.carbsG}g protein=${edited.proteinG}g " +
                             "fat=${edited.fatG}g GI=${edited.giBucket.label} (timer preserved)")
        _activeMeal.value = edited
        writeToSp(edited)
    }

    /**
     * Look up the expected ICE rate at the given moment.
     *
     * Returns 0.0 when there is no active meal. Auto-clears genuinely expired
     * meals (past the end of their absorption window) so the manager doesn't
     * accumulate stale state.
     *
     * **Important**: a meal queried with [nowMs] *earlier* than its
     * [AnnouncedMeal.announceTimestampMs] is NOT expired — it's in the future
     * relative to this query. The loop routinely calls this with
     * `glucoseStatus.date`, which is the last CGM reading and can be a few
     * minutes older than the meal announcement. We return 0.0 in that case
     * (no expected ICE *at that earlier moment*) but leave the meal alive so
     * the next cycle with a fresher reading can pick it up.
     *
     * Without this distinction, announcing a meal between CGM readings would
     * cause the next loop cycle to silently wipe the announcement.
     *
     * @return Expected ICE in mg/dL/h. Always finite, always ≥ 0.0 for sensible inputs.
     */
    fun expectedIceMgdlPerHour(nowMs: Long): Double {
        val meal = _activeMeal.value ?: return 0.0
        val ageMin = (nowMs - meal.announceTimestampMs) / 60_000.0

        // Past expiry → auto-clear and return 0
        if (ageMin > meal.effectiveTotalDurationMin.toDouble()) {
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: meal auto-cleared (expired at age=${ageMin.toInt()}min, " +
                                 "window=${meal.effectiveTotalDurationMin}min)")
            _activeMeal.value = null
            writeToSp(null)
            return 0.0
        }

        // Pre-announce relative to this query (negative age) → return 0 but keep the meal
        if (ageMin < 0.0) return 0.0

        return MealCurveBuilder.expectedIceMgdlPerHourAt(meal, nowMs)
    }

    /**
     * Sample the entire remaining curve for plotting. Returns empty list when
     * no active meal or when the meal has expired.
     */
    fun sampleRemainingCurve(nowMs: Long, sampleIntervalMin: Int = 5): List<Pair<Int, Double>> {
        val meal = _activeMeal.value ?: return emptyList()
        if (!meal.isActive(nowMs)) return emptyList()
        return MealCurveBuilder.sampleCurve(meal, nowMs, sampleIntervalMin)
    }

    /**
     * Remaining macros at the given time, computed PER COMPONENT from the actual
     * absorption curves. Each macro decreases on its own timeline — not in
     * lockstep. For a fresh announcement at age=10 min:
     *
     *   - Carbs: ~2% absorbed (Gaussian rising) → ~98% remaining
     *   - Protein: 0% absorbed (plateau hasn't onsetted yet) → 100% remaining
     *   - Fat: 0% absorbed (same plateau) → 100% remaining
     *
     * This matches real physiology, where protein/fat sit at zero contribution
     * until ~45 min post-meal before the gluconeogenesis / insulin-resistance
     * plateau kicks in.
     *
     * Returns null when no meal is active or it has expired.
     */
    fun remainingMacros(nowMs: Long): MealRemaining? {
        val meal = _activeMeal.value ?: return null
        if (!meal.isActive(nowMs)) return null
        val ageMin = (nowMs - meal.announceTimestampMs) / 60_000.0
        val carbAbsorbed    = MealCurveBuilder.carbAbsorbedFraction(meal, ageMin)
        val plateauAbsorbed = MealCurveBuilder.plateauAbsorbedFraction(ageMin, meal.fatProteinDurationMin)
        return MealRemaining(
            carbsG   = meal.carbsG   * (1.0 - carbAbsorbed),
            proteinG = meal.proteinG * (1.0 - plateauAbsorbed),
            fatG     = meal.fatG     * (1.0 - plateauAbsorbed)
        )
    }

    /**
     * Pre-formatted suffix string for the main-screen meal mode line. Returns empty
     * string when no meal is active so callers can unconditionally concatenate.
     */
    fun macrosOverviewSuffix(nowMs: Long): String {
        val r = remainingMacros(nowMs) ?: return ""
        val parts = buildList {
            if (r.carbsG   > 0.5) add("COB ${"%.0f".format(r.carbsG)}g")
            if (r.proteinG > 0.5) add("P ${"%.0f".format(r.proteinG)}g")
            if (r.fatG     > 0.5) add("F ${"%.0f".format(r.fatG)}g")
        }
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
    }

    // ── Persistence ──────────────────────────────────────────────────────────
    //
    // Serializes the active meal to SharedPreferences as a delimited string. Format:
    //   v1|carbsG|proteinG|fatG|giBucketName|commitmentPct|announceTimestampMs
    //
    // Chose delimited over JSON to avoid pulling in a serialization dependency for
    // a single tiny data class. The format is versioned so future migrations are
    // possible — older versions will fail the prefix check and restore returns null.

    /** Write the current active meal (or clear) to SharedPreferences. */
    private fun writeToSp(meal: AnnouncedMeal?) {
        try {
            if (meal == null) {
                sp.remove(SP_KEY)
                aapsLogger.debug(LTag.APS, "AnnouncedMealManager: SP cleared (key=$SP_KEY)")
                return
            }
            val serialized = listOf(
                SERIAL_VERSION,
                meal.carbsG.toString(),
                meal.proteinG.toString(),
                meal.fatG.toString(),
                meal.giBucket.name,
                meal.commitmentPct.toString(),
                meal.announceTimestampMs.toString()
            ).joinToString(DELIM)
            sp.putString(SP_KEY, serialized)
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: SP persist OK (key=$SP_KEY, payload=$serialized)")
        } catch (e: Throwable) {
            aapsLogger.error(LTag.APS,
                             "AnnouncedMealManager: SP write FAILED — ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Restore the active meal from SharedPreferences on startup. Returns null when:
     *   - No SP entry exists (first run / previously cleared)
     *   - SP entry exists but format is unrecognised (corrupt / from a future version)
     *   - The restored meal is past its absorption window (stale after a long reboot)
     *
     * Any restore failure is logged but never propagates — startup continues with no
     * active meal, which is the safe default.
     */
    private fun restoreFromSp(): AnnouncedMeal? {
        val raw = try {
            sp.getString(SP_KEY, "")
        } catch (e: Throwable) {
            aapsLogger.error(LTag.APS,
                             "AnnouncedMealManager: SP read FAILED — ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (raw.isBlank()) {
            aapsLogger.debug(LTag.APS, "AnnouncedMealManager: no SP entry at startup (key=$SP_KEY)")
            return null
        }
        aapsLogger.debug(LTag.APS, "AnnouncedMealManager: SP entry found, attempting restore — '$raw'")
        return try {
            val parts = raw.split(DELIM)
            if (parts.size != 7 || parts[0] != SERIAL_VERSION) {
                aapsLogger.debug(LTag.APS,
                                 "AnnouncedMealManager: discarding unrecognised SP entry (parts=${parts.size}, version=${parts.firstOrNull()})")
                sp.remove(SP_KEY)
                return null
            }
            val bucket = when (parts[4].uppercase()) {
                "FAST"   -> GiBucket.FAST
                "MEDIUM" -> GiBucket.MEDIUM
                "SLOW"   -> GiBucket.SLOW
                else     -> {
                    aapsLogger.debug(LTag.APS, "AnnouncedMealManager: unrecognised GI bucket '${parts[4]}' on restore")
                    return null
                }
            }
            val restored = AnnouncedMeal(
                carbsG              = parts[1].toDouble(),
                proteinG            = parts[2].toDouble(),
                fatG                = parts[3].toDouble(),
                giBucket            = bucket,
                commitmentPct       = parts[5].toInt(),
                announceTimestampMs = parts[6].toLong()
            )
            // Discard if already past the absorption window — don't resurrect ancient state
            val ageMin = (dateUtil.now() - restored.announceTimestampMs) / 60_000.0
            if (ageMin > restored.effectiveTotalDurationMin.toDouble()) {
                aapsLogger.debug(LTag.APS,
                                 "AnnouncedMealManager: discarding restored meal — expired (age=${ageMin.toInt()}min, " +
                                     "window=${restored.effectiveTotalDurationMin}min)")
                sp.remove(SP_KEY)
                return null
            }
            restored
        } catch (e: Throwable) {
            aapsLogger.debug(LTag.APS, "AnnouncedMealManager: SP restore failed — ${e.message}")
            sp.remove(SP_KEY)
            null
        }
    }
}

/** Macros remaining to absorb, in grams. */
data class MealRemaining(
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double
)