package app.aaps.core.interfaces.smartInsulin

/**
 * Meal modes for SmartInsulin — lives in core/interfaces so both
 * plugins/aps and plugins/automation can reference it.
 */
enum class MealMode(
    val label:               String,
    val defaultCarbsG:       Int,
    val defaultIsfMultiplier: Double,
    val diaLearningEnabled:  Boolean = true,
    val learningWeight:      Double  = 1.0
) {
    FASTING  ("Fasting",          0,  1.00, learningWeight = 1.0),
    LOW_CARB ("Low Carb",        20,  1.00, learningWeight = 0.8),
    BREAKFAST("Breakfast",       45,  0.95, learningWeight = 0.6),
    LUNCH    ("Lunch",           60,  1.00, learningWeight = 0.6),
    DINNER   ("Dinner",          70,  1.05, learningWeight = 0.5),
    EXTENDED ("Extended / High Fat", 50, 1.10, diaLearningEnabled = false, learningWeight = 0.2)
}