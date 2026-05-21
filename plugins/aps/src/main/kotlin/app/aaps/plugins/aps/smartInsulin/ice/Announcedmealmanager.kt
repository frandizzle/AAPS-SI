package app.aaps.plugins.aps.smartInsulin.ice

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
    private val aapsLogger: AAPSLogger
) {

    private val _activeMeal = MutableStateFlow<AnnouncedMeal?>(null)

    /** Observable handle for the UI — null when no meal is announced. */
    val activeMeal: StateFlow<AnnouncedMeal?> = _activeMeal.asStateFlow()

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
    }

    /** Discard the active meal — used when the user cancels or the loop force-clears. */
    fun clearMeal() {
        if (_activeMeal.value != null) {
            aapsLogger.debug(LTag.APS, "AnnouncedMealManager: meal cleared")
            _activeMeal.value = null
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
    }

    /**
     * Look up the expected ICE rate at the given moment.
     *
     * Returns 0.0 when there is no active meal, or when the active meal is
     * outside its absorption window. Also auto-clears expired meals as a
     * convenience (so the manager doesn't accumulate stale state if no one
     * calls [clearMeal] explicitly).
     *
     * @return Expected ICE in mg/dL/h. Always finite, always ≥ 0.0 for sensible inputs.
     */
    fun expectedIceMgdlPerHour(nowMs: Long): Double {
        val meal = _activeMeal.value ?: return 0.0
        if (!meal.isActive(nowMs)) {
            // Expired — auto-clear so future polls don't keep checking
            clearMeal()
            return 0.0
        }
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
     * Approximate remaining macros at the given time. Linear time-decay across the
     * meal's absorption window — accurate enough for a UI display ("how much carb
     * is left to absorb"). Dosing decisions use the full curve via [expectedIceMgdlPerHour],
     * which is non-linear and more precise.
     *
     * Returns null when no meal is active or it has expired.
     */
    fun remainingMacros(nowMs: Long): MealRemaining? {
        val meal = _activeMeal.value ?: return null
        if (!meal.isActive(nowMs)) return null
        val elapsedMin = (nowMs - meal.announceTimestampMs) / 60_000.0
        val fraction = (elapsedMin / meal.effectiveTotalDurationMin.toDouble()).coerceIn(0.0, 1.0)
        val absorbed = fraction
        return MealRemaining(
            carbsG   = meal.carbsG   * (1.0 - absorbed),
            proteinG = meal.proteinG * (1.0 - absorbed),
            fatG     = meal.fatG     * (1.0 - absorbed)
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
}

/** Macros remaining to absorb, in grams. */
data class MealRemaining(
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double
)