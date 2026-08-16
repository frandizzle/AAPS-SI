package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1("insulin_button_increment_1", 0.5, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement2("insulin_button_increment_2", 1.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement3("insulin_button_increment_3", 2.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    ActionsFillButton1("fill_button1", 0.3, 0.05, 20.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    ActionsFillButton2("fill_button2", 0.0, 0.05, 20.0, defaultedBySM = true),
    ActionsFillButton3("fill_button3", 0.0, 0.05, 20.0, defaultedBySM = true),
    SafetyMaxBolus("treatmentssafety_maxbolus", 3.0, 0.1, 60.0),
    ApsMaxBasal("openapsma_max_basal", 1.0, 0.1, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsSmbMaxIob("openapsmb_max_iob", 3.0, 0.0, 70.0, defaultedBySM = true, calculatedBySM = true),
    ApsAmaMaxIob("openapsma_max_iob", 1.5, 0.0, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsMaxDailyMultiplier("openapsama_max_daily_safety_multiplier", 3.0, 1.0, 10.0, defaultedBySM = true),
    ApsMaxCurrentBasalMultiplier("openapsama_current_basal_safety_multiplier", 4.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaBolusSnoozeDivisor("bolussnooze_dia_divisor", 2.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaMin5MinCarbsImpact("openapsama_min_5m_carbimpact", 3.0, 1.0, 12.0, defaultedBySM = true),
    ApsSmbMin5MinCarbsImpact("openaps_smb_min_5m_carbimpact", 8.0, 1.0, 12.0, defaultedBySM = true),
    AbsorptionCutOff("absorption_cutoff", 6.0, 4.0, 10.0),
    AbsorptionMaxTime("absorption_maxtime", 6.0, 4.0, 10.0),
    AutosensMin("autosens_min", 0.7, 0.1, 1.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    AutosensMax("autosens_max", 1.2, 0.5, 3.0, defaultedBySM = true),
    ApsAutoIsfMin("autoISF_min", 1.0, 0.3, 1.0, defaultedBySM = true),
    ApsAutoIsfMax("autoISF_max", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfBgBrakeWeight("bgBrake_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfLowBgWeight("lower_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfHighBgWeight("higher_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioBgRange("openapsama_smb_delivery_ratio_bg_range", 0.0, 0.0, 100.0, defaultedBySM = true),
    ApsAutoIsfPpWeight("pp_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfDuraWeight("dura_ISF_weight", 0.0, 0.0, 3.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatio("openapsama_smb_delivery_ratio", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMin("openapsama_smb_delivery_ratio_min", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMax("openapsama_smb_delivery_ratio_max", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),
    ApsSmartInsulinMaxSmb("si_max_smb_u", 3.0, 0.1, 20.0, defaultedBySM = true),
    ApsSmartInsulinMaxTbr("si_max_tbr_u", 3.0, 0.5, 10.0, defaultedBySM = true),
    ApsSmartInsulinAggressionMax("si_aggression_max", 1.5, 1.0, 2.5, defaultedBySM = true),
    // ISF overrides moved to UnitDoubleKey (ApsSmartInsulinBreakfastIsf etc.)
    ApsSmartInsulinMaxPreBolus("si_max_prebolus_u", 8.0, 0.5, 15.0, defaultedBySM = true),
    // Pre-bolus 2: default amount in units (user can override per-activation in SmartMealDialog)
    ApsSmartInsulinPreBolus2DefaultU("si_prebolus2_default_u", 2.0, 0.5, 10.0, defaultedBySM = true),
    ApsSmartInsulinLearningRate("si_learning_rate", 0.15, 0.05, 0.5, defaultedBySM = true),
    // LowGuard, WarnGuard moved to UnitDoubleKey (ApsSmartInsulinLowGuard, ApsSmartInsulinWarnGuard)
    ApsSmartInsulinDawnSmbReduction("si_dawn_smb_reduction", 0.5, 0.1, 1.0, defaultedBySM = true),
    ApsSmartInsulinRestingHrBpm("si_resting_hr_bpm", 70.0, 50.0, 110.0, defaultedBySM = true),
    // DURA_ISF strength (per meal mode) — how fast ISF ramps down while BG sits stuck above
    // target. Mirrors AutoISF's dura_ISF_weight, but per-mode and settable up to 5.0 (AutoISF
    // caps at 3.0). 1.0 = original fixed behaviour; higher = strengthens ISF faster when stuck.
    ApsSmartInsulinUamDuraStrength(      "si_uam_dura_strength",       1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDuraEnabled),
    ApsSmartInsulinPfDuraStrength(       "si_pf_dura_strength",        1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinPfDuraEnabled),
    ApsSmartInsulinBreakfastDuraStrength("si_breakfast_dura_strength", 1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinBreakfastDuraEnabled),
    ApsSmartInsulinLunchDuraStrength(    "si_lunch_dura_strength",     1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinLunchDuraEnabled),
    ApsSmartInsulinDinnerDuraStrength(   "si_dinner_dura_strength",    1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinDinnerDuraEnabled),
    ApsSmartInsulinLowCarbDuraStrength(  "si_lowcarb_dura_strength",   1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinLowCarbDuraEnabled),
    ApsSmartInsulinExtendedDuraStrength( "si_extended_dura_strength",  1.0, 0.0, 5.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinExtendedDuraEnabled),
    ApsSmartInsulinUamEntrySmbFraction           ("si_uam_entry_smb_fraction",            0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamEntrySmbFractionBreakfast  ("si_uam_entry_smb_fraction_breakfast",   0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled),
    ApsSmartInsulinUamEntrySmbFractionLunch      ("si_uam_entry_smb_fraction_lunch",       0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled),
    ApsSmartInsulinUamEntrySmbFractionDinner     ("si_uam_entry_smb_fraction_dinner",      0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled),
    ApsSmartInsulinUamEntrySmbFractionSnack      ("si_uam_entry_smb_fraction_snack",       0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled),
    ApsSmartInsulinUamEntrySmbFractionAfternoon  ("si_uam_entry_smb_fraction_afternoon",   0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled),
    // UAM thresholds, ISF overrides, activity targets all in UnitDoubleKey
}