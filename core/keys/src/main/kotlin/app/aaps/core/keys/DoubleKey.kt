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

    // UAM auto-detection — delta and burst can be < 36 mg/dL so use DoubleKey not UnitDoubleKey
    ApsSmartInsulinUamRiseMinDelta(  "si_uam_rise_min_delta",  3.6, 0.0, 18.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamBurstThreshold("si_uam_burst_threshold", 18.0, 0.0, 54.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),

    // Activity target raises (mg/dL above profile target) — < 36 so cannot use UnitDoubleKey
    ApsSmartInsulinActivityLightTarget(   "si_activity_light_target",     9.0, 0.0, 54.0, defaultedBySM = true),
    ApsSmartInsulinActivityModerateTarget("si_activity_moderate_target", 18.0, 0.0, 54.0, defaultedBySM = true),
    ApsSmartInsulinActivityHeavyTarget(   "si_activity_heavy_target",    27.0, 0.0, 54.0, defaultedBySM = true),

    // Per-meal ISF overrides (mg/dL/U) — 0.0 = use profile ISF sentinel
    // Can be < 36 so cannot use UnitDoubleKey
    ApsSmartInsulinBreakfastIsf(    "si_breakfast_isf2",       0.0, 0.0, 360.0, defaultedBySM = true),
    ApsSmartInsulinLunchIsf(        "si_lunch_isf2",           0.0, 0.0, 360.0, defaultedBySM = true),
    ApsSmartInsulinDinnerIsf(       "si_dinner_isf2",          0.0, 0.0, 360.0, defaultedBySM = true),
    ApsSmartInsulinLowCarbIsf(      "si_lowcarb_isf2",         0.0, 0.0, 360.0, defaultedBySM = true),
    ApsSmartInsulinExtendedIsf(     "si_extended_isf2",        0.0, 0.0, 360.0, defaultedBySM = true),
    ApsSmartInsulinUamBreakfastIsf( "si_uam_breakfast_isf2",   0.0, 0.0, 360.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled),
    ApsSmartInsulinUamLunchIsf(     "si_uam_lunch_isf2",       0.0, 0.0, 360.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled),
    ApsSmartInsulinUamDinnerIsf(    "si_uam_dinner_isf2",      0.0, 0.0, 360.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled),
    ApsSmartInsulinUamSnackIsf(     "si_uam_snack_isf2",       0.0, 0.0, 360.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled),
    ApsSmartInsulinUamAfternoonIsf( "si_uam_afternoon_isf2",   0.0, 0.0, 360.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled),
    ApsSmartInsulinUamProteinFatIsf("si_uam_proteinfat_isf2",  0.0, 0.0, 360.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
    ApsSmartInsulinUamEntrySmbFraction("si_uam_entry_smb_fraction", 0.8, 0.1, 1.0, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    // UAM per-window ISF overrides moved to UnitDoubleKey (ApsSmartInsulinUamBreakfastIsf etc.)

}