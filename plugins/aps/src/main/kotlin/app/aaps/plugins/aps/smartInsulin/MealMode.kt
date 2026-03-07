package app.aaps.plugins.aps.smartInsulin

/**
 * Meal modes for SmartInsulin — each carries default carb estimate and ISF multiplier.
 * ISF multiplier is loaded from preferences at runtime via [SmartInsulinPlugin];
 * the values here are compile-time defaults only.
 */
enum class MealMode(
    val label: String,
    /** Default carb estimate (g) used for pre-bolus calculation */
    val defaultCarbsG: Int,
    /** Default ISF multiplier — 1.0 = no change, 0.9 = 10% more aggressive */
    val defaultIsfMultiplier: Double,
    /** Whether EWMA DIA learning is active for this mode */
    val diaLearningEnabled: Boolean = true,
    /** Weight applied to EWMA learning rate */
    val learningWeight: Double = 1.0
) {
    FASTING(
        label              = "Fasting",
        defaultCarbsG      = 0,
        defaultIsfMultiplier = 1.0,
        learningWeight     = 1.0
    ),
    LOW_CARB(
        label              = "Low Carb",
        defaultCarbsG      = 20,
        defaultIsfMultiplier = 1.0,
        learningWeight     = 0.8
    ),
    BREAKFAST(
        label              = "Breakfast",
        defaultCarbsG      = 45,
        defaultIsfMultiplier = 0.95,
        learningWeight     = 0.6
    ),
    LUNCH(
        label              = "Lunch",
        defaultCarbsG      = 60,
        defaultIsfMultiplier = 1.0,
        learningWeight     = 0.6
    ),
    DINNER(
        label              = "Dinner",
        defaultCarbsG      = 70,
        defaultIsfMultiplier = 1.05,
        diaLearningEnabled = true,
        learningWeight     = 0.5
    ),
    EXTENDED(
        label              = "Extended / High Fat",
        defaultCarbsG      = 50,
        defaultIsfMultiplier = 1.1,
        diaLearningEnabled = false,
        learningWeight     = 0.2
    )
}