package app.aaps.core.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class DoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
) : DoubleNonPreferenceKey {

    // ── SmartInsulin: learner state and per-mode DURA settings ──
    ApsSmartInsulinBreakfastDuraStrength(key = "si_breakfast_dura_strength", defaultValue = 1.0),
    ApsSmartInsulinLunchDuraStrength(key = "si_lunch_dura_strength", defaultValue = 1.0),
    ApsSmartInsulinDinnerDuraStrength(key = "si_dinner_dura_strength", defaultValue = 1.0),
    ApsSmartInsulinLowCarbDuraStrength(key = "si_lowcarb_dura_strength", defaultValue = 1.0),
    ApsSmartInsulinExtendedDuraStrength(key = "si_extended_dura_strength", defaultValue = 1.0),
    ApsSmartInsulinBreakfastDuraFloor(key = "si_breakfast_dura_floor", defaultValue = 0.0),
    ApsSmartInsulinLunchDuraFloor(key = "si_lunch_dura_floor", defaultValue = 0.0),
    ApsSmartInsulinDinnerDuraFloor(key = "si_dinner_dura_floor", defaultValue = 0.0),
    ApsSmartInsulinLowCarbDuraFloor(key = "si_lowcarb_dura_floor", defaultValue = 0.0),
    ApsSmartInsulinExtendedDuraFloor(key = "si_extended_dura_floor", defaultValue = 0.0),

}