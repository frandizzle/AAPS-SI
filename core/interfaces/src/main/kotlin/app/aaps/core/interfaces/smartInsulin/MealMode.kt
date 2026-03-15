package app.aaps.core.interfaces.smartInsulin

enum class MealMode(
    val label:              String,
    val diaLearningEnabled: Boolean,
    val learningWeight:     Double,
    val isUam:              Boolean = false
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
    ),

    // ── UAM modes — auto-detected, separate profile learning from manual modes ──
    UAM_BREAKFAST(
        label              = "Breakfast (UAM)",
        diaLearningEnabled = true,
        learningWeight     = 0.4,
        isUam              = true
    ),
    UAM_LUNCH(
        label              = "Lunch (UAM)",
        diaLearningEnabled = true,
        learningWeight     = 0.4,
        isUam              = true
    ),
    UAM_DINNER(
        label              = "Dinner (UAM)",
        diaLearningEnabled = true,
        learningWeight     = 0.4,
        isUam              = true
    ),
    UAM_LOW_CARB(
        label              = "Low Carb (UAM)",
        diaLearningEnabled = true,
        learningWeight     = 0.4,
        isUam              = true
    ),
    UAM_SNACK(
        label              = "Snack (UAM)",
        diaLearningEnabled = true,
        learningWeight     = 0.4,
        isUam              = true
    )
}