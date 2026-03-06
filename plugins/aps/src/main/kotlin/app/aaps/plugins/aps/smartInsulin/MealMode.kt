package app.aaps.plugins.aps.smartInsulin

/**
 * Represents the current metabolic context for insulin activity learning and prediction.
 *
 * Mode selection affects:
 * - Which [LearnedInsulinProfile] is used for IOB/prediction calculations
 * - How much weight a bolus response carries in the EWMA learner
 * - Whether DIA learning is active or suppressed (EXTENDED suppresses it entirely)
 *
 * Detection priority (highest to lowest):
 *   EXTENDED → MEAL → LOW_CARB → FASTING
 */
enum class MealMode(
    /** Human-readable label for logging and UI */
    val label: String,
    /**
     * Whether DIA learning is permitted in this mode.
     * EXTENDED is excluded because a slow carb tail distorts the insulin decay curve.
     */
    val diaLearningEnabled: Boolean,
    /**
     * EWMA weight applied to a completed bolus observation in this mode.
     * Lower = slower learning, higher = faster adaptation.
     * FASTING/LOW_CARB have the cleanest signal so they carry more weight.
     */
    val learningWeight: Double
) {
    /**
     * No active carbs, correction bolus only.
     * Cleanest signal for DIA and peak learning — highest learning weight.
     */
    FASTING(
        label              = "Fasting",
        diaLearningEnabled = true,
        learningWeight     = 1.0
    ),

    /**
     * Active carbs below the user-configured low-carb threshold (default 20g).
     * Minimal carb competition — good signal, slightly lower weight than FASTING.
     */
    LOW_CARB(
        label              = "LowCarb",
        diaLearningEnabled = true,
        learningWeight     = 0.8
    ),

    /**
     * Normal meal bolus in progress with significant COB.
     * Peak timing is learnable but DIA tail is noisier — reduced weight.
     */
    MEAL(
        label              = "Meal",
        diaLearningEnabled = true,
        learningWeight     = 0.5
    ),

    /**
     * Dual-wave or high-fat/protein meal — slow, prolonged carb absorption.
     * Carb tail dominates the BG curve; DIA learning suppressed entirely.
     */
    EXTENDED(
        label              = "Extended",
        diaLearningEnabled = false,
        learningWeight     = 0.2
    )
}
