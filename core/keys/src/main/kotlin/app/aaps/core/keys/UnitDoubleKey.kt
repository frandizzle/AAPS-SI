package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

enum class UnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val unitType: UnitType = UnitType.MGDL
) : UnitDoublePreferenceKey {

    OverviewLowMark(key = "low_mark", defaultValue = 72.0, minMgdl = 25, maxMgdl = 160, titleResId = R.string.pref_title_low_mark, showInNsClientMode = false, hideParentScreenIfHidden = true),
    OverviewHighMark(key = "high_mark", defaultValue = 180.0, minMgdl = 90, maxMgdl = 250, titleResId = R.string.pref_title_high_mark, showInNsClientMode = false),
    ApsLgsThreshold(
        key = "lgsThreshold",
        defaultValue = 65.0,
        minMgdl = 60,
        maxMgdl = 100,
        titleResId = R.string.pref_title_lgs_threshold,
        summaryResId = R.string.lgs_threshold_summary,
        defaultedBySM = true,
        dependency = BooleanKey.ApsUseDynamicSensitivity
    ),

    // ── SmartInsulin ─────────────────────────────────────────────────────────
    // All values stored in mg/dL. AdaptiveUnitPreference handles display conversion.

    // BG guards
    ApsSmartInsulinLowGuard(key = "si_low_guard", defaultValue = 72.0, minMgdl = 54, maxMgdl = 90, titleResId = R.string.pref_title_si_low_guard, defaultedBySM = true),
    ApsSmartInsulinWarnGuard(key = "si_warn_guard", defaultValue = 86.0, minMgdl = 63, maxMgdl = 108, titleResId = R.string.pref_title_si_warn_guard, defaultedBySM = true),

    // Activity target raises (mg/dL above profile target; 9=0.5mmol, 18=1.0mmol, 27=1.5mmol)
    ApsSmartInsulinActivityLightTarget(key = "si_activity_light_target", defaultValue = 9.0, minMgdl = 0, maxMgdl = 54, titleResId = R.string.pref_title_si_activity_light_target, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinActivityModerateTarget(key = "si_activity_moderate_target", defaultValue = 18.0, minMgdl = 0, maxMgdl = 54, titleResId = R.string.pref_title_si_activity_moderate_target, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinActivityHeavyTarget(key = "si_activity_heavy_target", defaultValue = 27.0, minMgdl = 0, maxMgdl = 54, titleResId = R.string.pref_title_si_activity_heavy_target, defaultedBySM = true, unitType = UnitType.DOUBLE),

    // UAM detection thresholds
    ApsSmartInsulinUamTriggerThreshold(key = "si_uam_trigger", defaultValue = 108.0, minMgdl = 72, maxMgdl = 180, titleResId = R.string.pref_title_si_uam_trigger_threshold, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamRiseMinDelta(key = "si_uam_rise_min_delta", defaultValue = 3.6, minMgdl = 0, maxMgdl = 18, titleResId = R.string.pref_title_si_uam_rise_min_delta, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled, unitType = UnitType.DOUBLE_2),
    ApsSmartInsulinUamBurstThreshold(key = "si_uam_burst_threshold", defaultValue = 18.0, minMgdl = 0, maxMgdl = 54, titleResId = R.string.pref_title_si_uam_burst_threshold, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamProteinFatThreshold(key = "si_uam_proteinfat_threshold2", defaultValue = 117.0, minMgdl = 90, maxMgdl = 180, titleResId = R.string.pref_title_si_uam_proteinfat_threshold, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),

    // Per-meal ISF overrides (mg/dL/U) — 0.0 = use profile ISF
    ApsSmartInsulinBreakfastIsf(key = "si_breakfast_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_breakfast_isf, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinLunchIsf(key = "si_lunch_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_lunch_isf, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinDinnerIsf(key = "si_dinner_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_dinner_isf, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinLowCarbIsf(key = "si_lowcarb_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_lowcarb_isf, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinExtendedIsf(key = "si_extended_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_extended_isf, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamBreakfastIsf(key = "si_uam_breakfast_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_breakfast_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamLunchIsf(key = "si_uam_lunch_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_lunch_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamDinnerIsf(key = "si_uam_dinner_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_dinner_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamSnackIsf(key = "si_uam_snack_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_snack_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamAfternoonIsf(key = "si_uam_afternoon_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_afternoon_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamProteinFatIsf(key = "si_uam_proteinfat_isf2", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_proteinfat_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled, unitType = UnitType.DOUBLE),
    // P/F time-of-day ISF overrides — 0.0 = fall back to ApsSmartInsulinUamProteinFatIsf
    ApsSmartInsulinUamProteinFatDayIsf(key = "si_uam_proteinfat_day_isf", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_proteinfat_day_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled, unitType = UnitType.DOUBLE),
    ApsSmartInsulinUamProteinFatNightIsf(key = "si_uam_proteinfat_night_isf", defaultValue = 0.0, minMgdl = 0, maxMgdl = 360, titleResId = R.string.pref_title_si_uam_proteinfat_night_isf, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled, unitType = UnitType.DOUBLE)
}