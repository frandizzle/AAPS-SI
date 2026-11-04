package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    TddCycleOffset("tdd_cycle_offset", 0),
    /** Smart Meal: the last "Eat in" choice, in minutes after pre-bolus 1; 0 = now. */
    ApsSmartInsulinEatInMinutes("si_eat_in_minutes", 0)
}