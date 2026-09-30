package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

enum class UnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val sync: SyncSpec? = null
) : UnitDoublePreferenceKey {

    OverviewLowMark(key = "low_mark", defaultValue = 72.0, minMgdl = 25, maxMgdl = 160, title = KeysStrings.pref_title_low_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    OverviewHighMark(key = "high_mark", defaultValue = 180.0, minMgdl = 90, maxMgdl = 250, title = KeysStrings.pref_title_high_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsLgsThreshold(
        key = "lgsThreshold",
        defaultValue = 65.0,
        minMgdl = 60,
        maxMgdl = 100,
        title = KeysStrings.pref_title_lgs_threshold,
        summary = KeysStrings.lgs_threshold_summary,
        defaultedBySM = true,
        dependency = BooleanKey.ApsUseDynamicSensitivity,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    )
    // ── SmartInsulin ──
    ApsSmartInsulinLowGuard(
        key = "si_low_guard",
        defaultValue = 72.0,
        minMgdl = 54,
        maxMgdl = 90,
        title = KeysStrings.smart_insulin_low_guard,
        summary = KeysStrings.smart_insulin_low_guard_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinWarnGuard(
        key = "si_warn_guard",
        defaultValue = 86.0,
        minMgdl = 63,
        maxMgdl = 108,
        title = KeysStrings.smart_insulin_warn_guard,
        summary = KeysStrings.smart_insulin_warn_guard_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinActivityLightTarget(
        key = "si_activity_light_target",
        defaultValue = 9.0,
        minMgdl = 0,
        maxMgdl = 54,
        title = KeysStrings.si_activity_light_target_title,
        defaultedBySM = true
    ),
    ApsSmartInsulinActivityModerateTarget(
        key = "si_activity_moderate_target",
        defaultValue = 18.0,
        minMgdl = 0,
        maxMgdl = 54,
        title = KeysStrings.si_activity_moderate_target_title,
        defaultedBySM = true
    ),
    ApsSmartInsulinActivityHeavyTarget(
        key = "si_activity_heavy_target",
        defaultValue = 27.0,
        minMgdl = 0,
        maxMgdl = 54,
        title = KeysStrings.si_activity_heavy_target_title,
        defaultedBySM = true
    ),
    ApsSmartInsulinUamTriggerThreshold(
        key = "si_uam_trigger",
        defaultValue = 108.0,
        minMgdl = 72,
        maxMgdl = 180,
        title = KeysStrings.si_uam_trigger_threshold_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamEnabled
    ),
    ApsSmartInsulinUamRiseMinDelta(
        key = "si_uam_rise_min_delta",
        defaultValue = 3.6,
        minMgdl = 0,
        maxMgdl = 18,
        title = KeysStrings.si_uam_rise_min_delta_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamEnabled
    ),
    ApsSmartInsulinUamBurstThreshold(
        key = "si_uam_burst_threshold",
        defaultValue = 18.0,
        minMgdl = 0,
        maxMgdl = 54,
        title = KeysStrings.si_uam_burst_threshold_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamEnabled
    ),
    ApsSmartInsulinUamProteinFatThreshold(
        key = "si_uam_proteinfat_threshold2",
        defaultValue = 117.0,
        minMgdl = 90,
        maxMgdl = 180,
        title = KeysStrings.si_uam_proteinfat_threshold_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled
    ),
    ApsSmartInsulinBreakfastIsf(
        key = "si_breakfast_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_breakfast_isf_title,
        summary = KeysStrings.si_isf_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinLunchIsf(
        key = "si_lunch_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_lunch_isf_title,
        summary = KeysStrings.si_isf_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinDinnerIsf(
        key = "si_dinner_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_dinner_isf_title,
        summary = KeysStrings.si_isf_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinLowCarbIsf(
        key = "si_lowcarb_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_lowcarb_isf_title,
        summary = KeysStrings.si_isf_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinExtendedIsf(
        key = "si_extended_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_extended_isf_title,
        summary = KeysStrings.si_isf_summary,
        defaultedBySM = true
    ),
    ApsSmartInsulinUamDuraFloor(
        key = "si_uam_dura_floor",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_dura_floor_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamDuraEnabled
    ),
    ApsSmartInsulinPfDuraFloor(
        key = "si_pf_dura_floor",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_pf_dura_floor_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinPfDuraEnabled
    ),
    ApsSmartInsulinUamBreakfastIsf(
        key = "si_uam_breakfast_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_breakfast_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled
    ),
    ApsSmartInsulinUamLunchIsf(
        key = "si_uam_lunch_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_lunch_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled
    ),
    ApsSmartInsulinUamDinnerIsf(
        key = "si_uam_dinner_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_dinner_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled
    ),
    ApsSmartInsulinUamSnackIsf(
        key = "si_uam_snack_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_snack_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled
    ),
    ApsSmartInsulinUamAfternoonIsf(
        key = "si_uam_afternoon_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_afternoon_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled
    ),
    ApsSmartInsulinUamProteinFatIsf(
        key = "si_uam_proteinfat_isf2",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_proteinfat_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled
    ),
    ApsSmartInsulinUamProteinFatDayIsf(
        key = "si_uam_proteinfat_day_isf",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_proteinfat_day_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled
    ),
    ApsSmartInsulinUamProteinFatNightIsf(
        key = "si_uam_proteinfat_night_isf",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_proteinfat_night_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled
    ),
    ApsSmartInsulinUamProteinFatOvernightIsf(
        key = "si_uam_proteinfat_overnight_isf",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 360,
        title = KeysStrings.si_uam_proteinfat_overnight_isf_title,
        defaultedBySM = true,
        dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled
    ),
    ;

}
