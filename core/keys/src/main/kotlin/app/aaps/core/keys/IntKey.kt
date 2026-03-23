package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey

enum class IntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true
) : IntPreferenceKey {

    OverviewCarbsButtonIncrement1("carbs_button_increment_1", 5, -50, 50, defaultedBySM = true, dependency = BooleanKey.OverviewShowCarbsButton),
    OverviewCarbsButtonIncrement2("carbs_button_increment_2", 10, -50, 50, defaultedBySM = true, dependency = BooleanKey.OverviewShowCarbsButton),
    OverviewCarbsButtonIncrement3("carbs_button_increment_3", 20, -50, 50, defaultedBySM = true, dependency = BooleanKey.OverviewShowCarbsButton),
    OverviewEatingSoonDuration("eatingsoon_duration", 45, 15, 120, defaultedBySM = true, hideParentScreenIfHidden = true),
    OverviewActivityDuration("activity_duration", 90, 15, 600, defaultedBySM = true),
    OverviewHypoDuration("hypo_duration", 60, 15, 180, defaultedBySM = true),
    OverviewCageWarning("statuslights_cage_warning", 48, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewCageCritical("statuslights_cage_critical", 72, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewIageWarning("statuslights_iage_warning", 72, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewIageCritical("statuslights_iage_critical", 144, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    // SmartInsulin plugin
    ApsSmartInsulinPredictionHorizonMins("si_pred_horizon_mins", 240, 60, 240, defaultedBySM = true),    ApsSmartInsulinLowCarbThresholdG("si_low_carb_threshold_g", 20, 5, 50, defaultedBySM = true),
    ApsSmartInsulinBreakfastCarbsG("si_breakfast_carbs_g",   45, 10, 150, defaultedBySM = true),
    ApsSmartInsulinLunchCarbsG    ("si_lunch_carbs_g",       60, 10, 200, defaultedBySM = true),
    ApsSmartInsulinDinnerCarbsG   ("si_dinner_carbs_g",      70, 10, 200, defaultedBySM = true),
    ApsSmartInsulinLowCarbCarbsG  ("si_lowcarb_carbs_g",     20,  5,  60, defaultedBySM = true),
    ApsSmartInsulinExtendedCarbsG ("si_extended_carbs_g",    50, 10, 150, defaultedBySM = true),
    ApsSmartInsulinModeWindowMins ("si_mode_window_mins",   180, 30, 480, defaultedBySM = true),
    ApsSmartInsulinPreBolus2DefaultDelayMins("si_prebolus2_default_delay_mins", 25, 5, 120, defaultedBySM = true),
    ApsSmartInsulinPostModeLockoutMins("si_post_mode_lockout_mins", 90, 0, 180, defaultedBySM = true),
    OverviewSageWarning("statuslights_sage_warning", 216, 24, 720, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSageCritical("statuslights_sage_critical", 240, 24, 720, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSbatWarning("statuslights_sbat_warning", 25, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSbatCritical("statuslights_sbat_critical", 5, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBageWarning("statuslights_bage_warning", 216, 24, 1000, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBageCritical("statuslights_bage_critical", 240, 24, 1000, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewResWarning("statuslights_res_warning", 80, 0, 300, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewResCritical("statuslights_res_critical", 10, 0, 300, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBattWarning("statuslights_bat_warning", 51, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBattCritical("statuslights_bat_critical", 26, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBolusPercentage("boluswizard_percentage", 100, 10, 100),
    OverviewResetBolusPercentageTime("key_reset_boluswizard_percentage_time", 16, 6, 120, defaultedBySM = true, engineeringModeOnly = true),
    ProtectionTimeout("protection_timeout", 1, 1, 180, defaultedBySM = true),
    ProtectionTypeSettings("settings_protection", 0, 0, 5),
    ProtectionTypeApplication("application_protection", 0, 0, 5),
    ProtectionTypeBolus("bolus_protection", 0, 0, 5),
    SafetyMaxCarbs("treatmentssafety_maxcarbs", 48, 1, 200),
    LoopOpenModeMinChange("loop_openmode_min_change", 30, 0, 50, defaultedBySM = true),
    ApsMaxSmbFrequency("smbinterval", 3, 1, 10, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsMaxMinutesOfBasalToLimitSmb("smbmaxminutes", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsUamMaxMinutesOfBasalToLimitSmb("uamsmbmaxminutes", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsCarbsRequestThreshold("carbsReqThreshold", 1, 1, 100, defaultedBySM = true),
    ApsAutoIsfHalfBasalExerciseTarget("half_basal_exercise_target", 160, 120, 200, defaultedBySM = true),
    ApsAutoIsfIobThPercent("iob_threshold_percent", 100, 10, 100, defaultedBySM = true),
    ApsDynIsfAdjustmentFactor("DynISFAdjust", 100, 1, 300, dependency = BooleanKey.ApsUseDynamicSensitivity),
    AutosensPeriod("openapsama_autosens_period", 24, 4, 24, calculatedDefaultValue = true),
    MaintenanceLogsAmount("maintenance_logs_amount", 2, 1, 10, defaultedBySM = true),
    AlertsStaleDataThreshold("missed_bg_readings_threshold", 30, 15, 10000, defaultedBySM = true, dependency = BooleanKey.AlertMissedBgReading),
    AlertsPumpUnreachableThreshold("pump_unreachable_threshold", 30, 30, 300, defaultedBySM = true, dependency = BooleanKey.AlertPumpUnreachable),
    InsulinOrefPeak("insulin_oref_peak", 75, 35, 120, hideParentScreenIfHidden = true),
    ApsSmartInsulinDawnWindowStartHour("si_dawn_start_hour",  3, 0, 23, defaultedBySM = true),
    ApsSmartInsulinDawnWindowEndHour  ("si_dawn_end_hour",    9, 0, 23, defaultedBySM = true),

    AutotuneDefaultTuneDays("autotune_default_tune_days", 5, 1, 30),

    // AutoExportPasswordExpiryDays("auto_export_password_expiry_days", 28, 7, 28),

    SmsRemoteBolusDistance("smscommunicator_remotebolusmindistance", 15, 3, 60),

    BgSourceRandomInterval("randombg_interval_min", 5, 1, 15, defaultedBySM = true),
    NsClientAlarmStaleData("ns_alarm_stale_data_value", 16, 15, 120),
    NsClientUrgentAlarmStaleData("ns_alarm_urgent_stale_data_value", 31, 30, 180),

    SiteRotationUserProfile("site_rotation_user_profile", 0, 0, 2),

    // UAM auto-detection
    ApsSmartInsulinUamNightCutoffHour("si_uam_night_cutoff_hour", 23, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamDayStartHour("si_uam_day_start_hour", 9, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamRiseConsecutiveReadings("si_uam_rise_readings", 3, 2, 6, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamEntrySmbCount("si_uam_entry_smb_count", 3, 1, 10, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    // Breakfast UAM window
    ApsSmartInsulinUamBreakfastStartHour("si_uam_breakfast_start", 6, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled),
    ApsSmartInsulinUamBreakfastEndHour("si_uam_breakfast_end", 10, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled),
    ApsSmartInsulinUamBreakfastDurationMins("si_uam_breakfast_duration", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled),
    // Lunch UAM window
    ApsSmartInsulinUamLunchStartHour("si_uam_lunch_start", 10, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled),
    ApsSmartInsulinUamLunchEndHour("si_uam_lunch_end", 14, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled),
    ApsSmartInsulinUamLunchDurationMins("si_uam_lunch_duration", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled),
    // Dinner UAM window
    ApsSmartInsulinUamDinnerStartHour("si_uam_dinner_start", 17, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled),
    ApsSmartInsulinUamDinnerEndHour("si_uam_dinner_end", 21, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled),
    ApsSmartInsulinUamDinnerDurationMins("si_uam_dinner_duration", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled),
    // Snack UAM window
    ApsSmartInsulinUamSnackStartHour("si_uam_snack_start", 21, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled),
    ApsSmartInsulinUamSnackEndHour("si_uam_snack_end", 23, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled),
    ApsSmartInsulinUamSnackDurationMins("si_uam_snack_duration", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled),
    ApsSmartInsulinUamAfternoonStartHour("si_uam_afternoon_start", 14, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled),
    ApsSmartInsulinUamAfternoonEndHour("si_uam_afternoon_end", 17, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled),
    ApsSmartInsulinUamAfternoonDurationMins("si_uam_afternoon_duration", 60, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled),
    // Protein/Fat UAM — no time window, stuck-high detection runs until night cutoff
    ApsSmartInsulinUamProteinFatDurationMins("si_uam_proteinfat_duration", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
    ApsSmartInsulinUamProteinFatStuckReadings("si_uam_proteinfat_stuck_readings", 6, 2, 12, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
    // P/F time-of-day ISF window hours
    ApsSmartInsulinUamProteinFatDayStartHour(  "si_uam_proteinfat_day_start",   6, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
    ApsSmartInsulinUamProteinFatDayEndHour(    "si_uam_proteinfat_day_end",     22, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
    ApsSmartInsulinUamProteinFatNightStartHour("si_uam_proteinfat_night_start", 22, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
    ApsSmartInsulinUamProteinFatNightEndHour(  "si_uam_proteinfat_night_end",    6, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),
}