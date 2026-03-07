package app.aaps.core.interfaces.smartInsulin

enum class MealMode(
    val label:              String,
    val diaLearningEnabled: Boolean,
    val learningWeight:     Double
) {
    FASTING(
        label              = "Fasting",
        diaLearningEnabled = true,
        learningWeight     = 1.0
    ),
    LOW_CARB(
        label              = "Low Carb",
        diaLearningEnabled = true,
        learningWeight     = 0.8
    ),
    BREAKFAST(
        label              = "Breakfast",
        diaLearningEnabled = true,
        learningWeight     = 0.5
    ),
    LUNCH(
        label              = "Lunch",
        diaLearningEnabled = true,
        learningWeight     = 0.5
    ),
    DINNER(
        label              = "Dinner",
        diaLearningEnabled = true,
        learningWeight     = 0.5
    ),
    EXTENDED(
        label              = "Extended",
        diaLearningEnabled = false,
        learningWeight     = 0.2
    )
}