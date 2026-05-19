package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val unitType: UnitType = UnitType.NONE
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1(
        key = "insulin_button_increment_1",
        defaultValue = 0.5,
        min = -5.0,
        max = 5.0,
        titleResId = R.string.pref_title_insulin_button_increment_1,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN
    ),
    OverviewInsulinButtonIncrement2(
        key = "insulin_button_increment_2",
        defaultValue = 1.0,
        min = -5.0,
        max = 5.0,
        titleResId = R.string.pref_title_insulin_button_increment_2,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN
    ),
    OverviewInsulinButtonIncrement3(
        key = "insulin_button_increment_3",
        defaultValue = 2.0,
        min = -5.0,
        max = 5.0,
        titleResId = R.string.pref_title_insulin_button_increment_3,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN
    ),
    ActionsFillButton1(key = "fill_button1", defaultValue = 0.3, min = 0.05, max = 20.0, titleResId = R.string.pref_title_fill_button_1, defaultedBySM = true, hideParentScreenIfHidden = true, unitType = UnitType.INSULIN),
    ActionsFillButton2(key = "fill_button2", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_2, defaultedBySM = true, unitType = UnitType.INSULIN),
    ActionsFillButton3(key = "fill_button3", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_3, defaultedBySM = true, unitType = UnitType.INSULIN),
    SafetyMaxBolus(key = "treatmentssafety_maxbolus", defaultValue = 3.0, min = 0.1, max = 60.0, titleResId = R.string.pref_title_max_bolus, unitType = UnitType.INSULIN),
    ApsMaxBasal(
        key = "openapsma_max_basal",
        defaultValue = 1.0,
        min = 0.1,
        max = 25.0,
        titleResId = R.string.pref_title_max_basal,
        summaryResId = R.string.openapsma_max_basal_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN_RATE
    ),
    ApsSmbMaxIob(
        key = "openapsmb_max_iob",
        defaultValue = 3.0,
        min = 0.0,
        max = 70.0,
        titleResId = R.string.pref_title_smb_max_iob,
        summaryResId = R.string.openapssmb_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN
    ),
    ApsAmaMaxIob(
        key = "openapsma_max_iob",
        defaultValue = 1.5,
        min = 0.0,
        max = 25.0,
        titleResId = R.string.pref_title_ama_max_iob,
        summaryResId = R.string.openapsma_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN
    ),
    ApsMaxDailyMultiplier(
        key = "openapsama_max_daily_safety_multiplier",
        defaultValue = 3.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_max_daily_multiplier,
        summaryResId = R.string.openapsama_max_daily_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsMaxCurrentBasalMultiplier(
        key = "openapsama_current_basal_safety_multiplier",
        defaultValue = 4.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_current_basal_multiplier,
        summaryResId = R.string.openapsama_current_basal_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAmaBolusSnoozeDivisor(
        key = "bolussnooze_dia_divisor",
        defaultValue = 2.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_bolus_snooze_divisor,
        summaryResId = R.string.openapsama_bolus_snooze_dia_divisor_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAmaMin5MinCarbsImpact(
        key = "openapsama_min_5m_carbimpact",
        defaultValue = 3.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_ama_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsSmbMin5MinCarbsImpact(
        key = "openaps_smb_min_5m_carbimpact",
        defaultValue = 8.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_smb_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    AbsorptionCutOff(key = "absorption_cutoff", defaultValue = 6.0, min = 4.0, max = 10.0, titleResId = R.string.pref_title_absorption_cutoff, summaryResId = R.string.absorption_cutoff_summary, unitType = UnitType.HOURS_DOUBLE),
    AbsorptionMaxTime(key = "absorption_maxtime", defaultValue = 6.0, min = 4.0, max = 10.0, titleResId = R.string.pref_title_absorption_maxtime, summaryResId = R.string.absorption_max_time_summary, unitType = UnitType.HOURS_DOUBLE),
    AutosensMin(
        key = "autosens_min",
        defaultValue = 0.7,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_autosens_min,
        summaryResId = R.string.openapsama_autosens_min_summary,
        defaultedBySM = true,
        hideParentScreenIfHidden = true,
        unitType = UnitType.DOUBLE
    ),
    AutosensMax(key = "autosens_max", defaultValue = 1.2, min = 0.5, max = 3.0, titleResId = R.string.pref_title_autosens_max, summaryResId = R.string.openapsama_autosens_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfMin(key = "autoISF_min", defaultValue = 1.0, min = 0.3, max = 1.0, titleResId = R.string.pref_title_autoisf_min, summaryResId = R.string.openapsama_autoISF_min_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfMax(key = "autoISF_max", defaultValue = 1.0, min = 1.0, max = 3.0, titleResId = R.string.pref_title_autoisf_max, summaryResId = R.string.openapsama_autoISF_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfBgAccelWeight(
        key = "bgAccel_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_accel_weight,
        summaryResId = R.string.openapsama_bgAccel_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfBgBrakeWeight(
        key = "bgBrake_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_brake_weight,
        summaryResId = R.string.openapsama_bgBrake_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfLowBgWeight(
        key = "lower_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_low_bg_weight,
        summaryResId = R.string.openapsama_lower_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfHighBgWeight(
        key = "higher_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_high_bg_weight,
        summaryResId = R.string.openapsama_higher_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbDeliveryRatioBgRange(
        key = "openapsama_smb_delivery_ratio_bg_range",
        defaultValue = 0.0,
        min = 0.0,
        max = 100.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_bg_range,
        summaryResId = R.string.openapsama_smb_delivery_ratio_bg_range_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfPpWeight(key = "pp_ISF_weight", defaultValue = 0.0, min = 0.0, max = 1.0, titleResId = R.string.pref_title_pp_weight, summaryResId = R.string.openapsama_pp_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE_2),
    ApsAutoIsfDuraWeight(key = "dura_ISF_weight", defaultValue = 0.0, min = 0.0, max = 3.0, titleResId = R.string.pref_title_dura_weight, summaryResId = R.string.openapsama_dura_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE_2),
    ApsAutoIsfSmbDeliveryRatio(
        key = "openapsama_smb_delivery_ratio",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio,
        summaryResId = R.string.openapsama_smb_delivery_ratio_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbDeliveryRatioMin(
        key = "openapsama_smb_delivery_ratio_min",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_min,
        summaryResId = R.string.openapsama_smb_delivery_ratio_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbDeliveryRatioMax(
        key = "openapsama_smb_delivery_ratio_max",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_max,
        summaryResId = R.string.openapsama_smb_delivery_ratio_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2
    ),
    ApsAutoIsfSmbMaxRangeExtension(
        key = "openapsama_smb_max_range_extension",
        defaultValue = 1.0,
        min = 1.0,
        max = 5.0,
        titleResId = R.string.pref_title_smb_max_range_extension,
        summaryResId = R.string.openapsama_smb_max_range_extension_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),

    // SmartInsulin plugin
    ApsSmartInsulinMaxSmb(key = "si_max_smb_u", defaultValue = 3.0, min = 0.1, max = 20.0, titleResId = R.string.pref_title_si_max_smb, defaultedBySM = true, unitType = UnitType.INSULIN),
    ApsSmartInsulinMaxTbr(key = "si_max_tbr_u", defaultValue = 3.0, min = 0.5, max = 10.0, titleResId = R.string.pref_title_si_max_tbr, defaultedBySM = true, unitType = UnitType.INSULIN_RATE),
    ApsSmartInsulinAggressionMax(key = "si_aggression_max", defaultValue = 1.5, min = 1.0, max = 2.5, titleResId = R.string.pref_title_si_aggression_max, defaultedBySM = true, unitType = UnitType.DOUBLE),
    // ISF overrides moved to UnitDoubleKey (ApsSmartInsulinBreakfastIsf etc.)
    ApsSmartInsulinMaxPreBolus(key = "si_max_prebolus_u", defaultValue = 8.0, min = 0.5, max = 15.0, titleResId = R.string.pref_title_si_max_prebolus, defaultedBySM = true, unitType = UnitType.INSULIN),
    // Pre-bolus 2: default amount in units (user can override per-activation in SmartMealDialog)
    ApsSmartInsulinPreBolus2DefaultU(key = "si_prebolus2_default_u", defaultValue = 2.0, min = 0.5, max = 10.0, titleResId = R.string.pref_title_si_prebolus2_default_u, defaultedBySM = true, unitType = UnitType.INSULIN),
    // ISF learner speed — higher = learns faster but more reactive to single bad readings
    ApsSmartInsulinIsfAlpha(key = "si_isf_alpha", defaultValue = 0.08, min = 0.02, max = 0.20, titleResId = R.string.pref_title_si_isf_alpha, defaultedBySM = true, unitType = UnitType.DOUBLE_2),
    // Basal learner speed — higher = learns faster
    ApsSmartInsulinBasalAlpha(key = "si_basal_alpha", defaultValue = 0.06, min = 0.02, max = 0.15, titleResId = R.string.pref_title_si_basal_alpha, defaultedBySM = true, unitType = UnitType.DOUBLE_2),
    // LowGuard, WarnGuard moved to UnitDoubleKey (ApsSmartInsulinLowGuard, ApsSmartInsulinWarnGuard)
    ApsSmartInsulinDawnSmbReduction(key = "si_dawn_smb_reduction", defaultValue = 0.5, min = 0.1, max = 1.0, titleResId = R.string.pref_title_si_dawn_smb_reduction, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinRestingHrBpm(key = "si_resting_hr_bpm", defaultValue = 70.0, min = 50.0, max = 110.0, titleResId = R.string.pref_title_si_resting_hr_bpm, defaultedBySM = true),
    ApsSmartInsulinUamEntrySmbFraction(key = "si_uam_entry_smb_fraction", defaultValue = 0.8, min = 0.1, max = 1.0, titleResId = R.string.pref_title_si_uam_entry_smb_fraction, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled, unitType = UnitType.DOUBLE),
    // UAM thresholds, ISF overrides, activity targets all in UnitDoubleKey

    // PDP — Persistent Deviation Prediction
    ApsSmartInsulinPdpCiStrength(
        key = "si_pdp_ci_strength",
        defaultValue = 3.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_si_pdp_ci_strength,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinPdpEnabled,
        unitType = UnitType.DOUBLE
    ),
    // Rising pathway ci strength — separate from stuck-high to prevent over-aggressiveness on rises
    ApsSmartInsulinPdpRisingStrength(
        key = "si_pdp_rising_strength",
        defaultValue = 1.0,
        min = 0.5,
        max = 1.5,
        titleResId = R.string.pref_title_si_pdp_rising_strength,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinPdpEnabled,
        unitType = UnitType.DOUBLE
    ),
    ApsSmartInsulinPdpMaxBlendWeight(
        key = "si_pdp_max_blend_weight",
        defaultValue = 0.7,
        min = 0.1,
        max = 0.9,
        titleResId = R.string.pref_title_si_pdp_max_blend_weight,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinPdpEnabled,
        unitType = UnitType.DOUBLE
    ),
    ApsSmartInsulinFastingMaxIob(
        key = "si_fasting_max_iob",
        defaultValue = 0.0,
        min = 0.0,
        max = 20.0,
        titleResId = R.string.pref_title_si_fasting_max_iob,
        defaultedBySM = true,
        unitType = UnitType.INSULIN
    ),
    ApsSmartInsulinPdpMealMaxStrength(
        key = "si_pdp_meal_max_strength",
        defaultValue = 2.0,
        min = 1.1,
        max = 4.0,
        titleResId = R.string.pref_title_si_pdp_meal_max_strength,
        summaryResId = R.string.pref_summary_si_pdp_meal_max_strength,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinPdpMealStuckEnabled,
        unitType = UnitType.DOUBLE
    ),

}