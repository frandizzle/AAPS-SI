package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import kotlin.math.min

/**
 * Which carbs go to a low. Food logged as a snack in a meal ([FoodLogEntry.mealSnack]) in the [WINDOW_MS] before a low
 * was most likely eaten for it, or did not come in as planned. Its carbs treat the low, so the loop must not give insulin
 * for them when BG comes back up. Food that came with its own insulin is the meal itself and is not moved.
 */
object LowFoodAllocation {

    /** Food logged this long before the low counts. */
    const val WINDOW_MS = 45 * 60_000L

    /** At most this many grams of carbs go to one low, about one treatment. More is a meal. */
    const val MAX_G = 20.0

    /**
     * The carbs of each of [entries] that go to the low that starts at [lowStartMs], the newest food first, up to
     * [MAX_G] in all. Food already used for a low is left alone.
     */
    fun carbsForLow(entries: List<FoodLogEntry>, lowStartMs: Long): List<Pair<FoodLogEntry, Double>> {
        var leftG = MAX_G
        return entries
            .filter { it.mealSnack && it.forLowG <= 0.0 && it.carbsG > 0.0 && it.timeMs >= lowStartMs - WINDOW_MS && it.timeMs < lowStartMs }
            .sortedByDescending { it.timeMs }
            .mapNotNull { entry ->
                val grams = min(entry.carbsG, leftG)
                if (grams <= 0.0) null else entry to grams.also { leftG -= it }
            }
    }
}
