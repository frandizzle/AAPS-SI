package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

enum class UnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : UnitDoublePreferenceKey {

    OverviewEatingSoonTarget("eatingsoon_target", 90.0, 72, 160, defaultedBySM = true),
    OverviewActivityTarget("activity_target", 140.0, 108, 180, defaultedBySM = true),
    OverviewHypoTarget("hypo_target", 160.0, 108, 180, defaultedBySM = true),
    OverviewLowMark("low_mark", 72.0, 25, 160, showInNsClientMode = false, hideParentScreenIfHidden = true),
    OverviewHighMark("high_mark", 180.0, 90, 250, showInNsClientMode = false),
    ApsLgsThreshold("lgsThreshold", 65.0, 60, 100, defaultedBySM = true, dependency = BooleanKey.ApsUseDynamicSensitivity),

    // ── SmartInsulin ─────────────────────────────────────────────────────────

    // BG guards
    ApsSmartInsulinLowGuard("si_low_guard",   72.0,  54,  90, defaultedBySM = true),   // 4.0 mmol
    ApsSmartInsulinWarnGuard("si_warn_guard",  86.0,  63, 108, defaultedBySM = true),   // 4.8 mmol


    // Activity target raises — stored in mg/dL, displayed in user units
    // Default: 9=0.5mmol, 18=1.0mmol, 27=1.5mmol; max 54=3.0mmol
    ApsSmartInsulinActivityLightTarget(   "si_activity_light_target",     9.0,  0, 54, defaultedBySM = true),
    ApsSmartInsulinActivityModerateTarget("si_activity_moderate_target", 18.0,  0, 54, defaultedBySM = true),
    ApsSmartInsulinActivityHeavyTarget(   "si_activity_heavy_target",    27.0,  0, 54, defaultedBySM = true),

    // UAM detection thresholds
    ApsSmartInsulinUamTriggerThreshold(   "si_uam_trigger",          108.0, 72, 180, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),  // 6.0 mmol
    ApsSmartInsulinUamRiseMinDelta(       "si_uam_rise_min_delta",     3.6,  2,  18, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),  // 0.2 mmol
    ApsSmartInsulinUamBurstThreshold(     "si_uam_burst_threshold",   18.0,  0,  54, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),  // 1.0 mmol
    ApsSmartInsulinUamProteinFatThreshold("si_uam_proteinfat_threshold2",  117.0, 90, 180, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled),  // 6.5 mmol

    // Per-meal ISF overrides — 0.0 means "use profile ISF" (sentinel preserved in any unit)
    // Range max 360 mg/dL/U = ~20 mmol/U
    ApsSmartInsulinBreakfastIsf(   "si_breakfast_isf2",       0.0, 0, 360, defaultedBySM = true),
    ApsSmartInsulinLunchIsf(       "si_lunch_isf2",            0.0, 0, 360, defaultedBySM = true),
    ApsSmartInsulinDinnerIsf(      "si_dinner_isf2",           0.0, 0, 360, defaultedBySM = true),
    ApsSmartInsulinLowCarbIsf(     "si_lowcarb_isf2",          0.0, 0, 360, defaultedBySM = true),
    ApsSmartInsulinExtendedIsf(    "si_extended_isf2",         0.0, 0, 360, defaultedBySM = true),
    ApsSmartInsulinUamBreakfastIsf("si_uam_breakfast_isf2",    0.0, 0, 360, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamBreakfastEnabled),
    ApsSmartInsulinUamLunchIsf(    "si_uam_lunch_isf2",        0.0, 0, 360, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamLunchEnabled),
    ApsSmartInsulinUamDinnerIsf(   "si_uam_dinner_isf2",       0.0, 0, 360, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamDinnerEnabled),
    ApsSmartInsulinUamSnackIsf(    "si_uam_snack_isf2",        0.0, 0, 360, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamSnackEnabled),
    ApsSmartInsulinUamAfternoonIsf("si_uam_afternoon_isf2",    0.0, 0, 360, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamAfternoonEnabled),
    ApsSmartInsulinUamProteinFatIsf("si_uam_proteinfat_isf2",  0.0, 0, 360, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled)
}