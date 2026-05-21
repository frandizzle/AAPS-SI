package app.aaps.plugins.aps.smartInsulin.ice

/**
 * Glycemic-index bucket for an announced meal. Classifies how quickly the meal
 * will deliver its glucose load into the bloodstream, which drives the shape
 * of the expected ICE curve.
 *
 * Numbers are empirical defaults that match observed behaviour for typical
 * meals in each category. They are starting points — the learning system can
 * adjust per-user once enough labelled meals accumulate (future work).
 *
 * @property peakMinutes when the absorption rate peaks (minutes from now)
 * @property totalDurationMinutes total absorption time (minutes from now)
 * @property tailFraction what fraction of the total carb effect arrives in
 *           a slow tail (after the peak has come down) — relevant for fat / protein-heavy meals
 */
enum class GiBucket(
    val label: String,
    val peakMinutes: Int,
    val totalDurationMinutes: Int,
    val tailFraction: Double
) {
    /** Fast carbs — juice, soft drinks, candy, dextrose. Peaks early, done quickly. */
    FAST(
        label = "Fast (juice, candy, soft drinks)",
        peakMinutes = 30,
        totalDurationMinutes = 120,
        tailFraction = 0.0
    ),

    /** Medium GI — most cooked meals, bread, rice, pasta with sauce. */
    MEDIUM(
        label = "Medium (bread, rice, pasta, most cooked meals)",
        peakMinutes = 75,
        totalDurationMinutes = 240,
        tailFraction = 0.15
    ),

    /** Slow GI — heavy fat/protein content, pizza, fried foods, restaurant meals. Bimodal absorption. */
    SLOW(
        label = "Slow (pizza, fried food, fatty meals, large portions)",
        peakMinutes = 120,
        totalDurationMinutes = 360,
        tailFraction = 0.35
    )
}