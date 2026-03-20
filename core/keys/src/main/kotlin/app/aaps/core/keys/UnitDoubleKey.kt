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

    // BG guards — safe: all >= 54 mg/dL, heuristic correctly identifies as mg/dL
    ApsSmartInsulinLowGuard( "si_low_guard",  72.0, 54,  90, defaultedBySM = true),
    ApsSmartInsulinWarnGuard("si_warn_guard", 86.0, 63, 108, defaultedBySM = true),

    // UAM BG thresholds — safe: all >= 72 mg/dL
    ApsSmartInsulinUamTriggerThreshold(   "si_uam_trigger",              108.0, 72, 180, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamEnabled),
    ApsSmartInsulinUamProteinFatThreshold("si_uam_proteinfat_threshold2", 117.0, 90, 180, defaultedBySM = true, dependency = BooleanKey.ApsSmartInsulinUamProteinFatEnabled)

    // NOTE: Activity targets (9/18/27), UAM delta (3.6)/burst (18), and all ISF overrides
    // are in DoubleKey — they store mg/dL but can be < 36 which breaks valueInCurrentUnitsDetect.
}