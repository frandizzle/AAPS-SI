package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.UnitDoubleKey

/**
 * Pure SP-reading utilities extracted from SmartInsulinPlugin so they can be
 * unit-tested without constructing the full plugin.
 *
 * All UnitDoubleKey values are stored in mg/dL by SmartInsulinUnitPreference.
 * defaultValues are already in mg/dL (LowGuard=72, ActivityLightTarget=9 etc)
 * so no heuristic conversion is needed or correct — just a direct read.
 */
internal fun spMgdl(sp: SP, key: UnitDoubleKey): Double =
    sp.getDouble(key.key, key.defaultValue)

/**
 * Reads the three activity target offsets from SP (stored as mg/dL),
 * converts to mmol, and delegates to ActivityMonitor.targetOffsetMmol()
 * to pick the right one for the current activity level.
 *
 * This is the exact chain that had the unit-conversion bug:
 *   spMgdl() → /18.0 → targetOffsetMmol()
 * Keeping it here makes it directly testable.
 */
internal fun activityOffsetMmol(sp: SP, monitor: ActivityMonitor): Double =
    monitor.targetOffsetMmol(
        spMgdl(sp, UnitDoubleKey.ApsSmartInsulinActivityLightTarget)    / 18.0,
        spMgdl(sp, UnitDoubleKey.ApsSmartInsulinActivityModerateTarget) / 18.0,
        spMgdl(sp, UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget)    / 18.0
    )

/**
 * The single definition of "BG has gone below the low guard": the number on screen is lower than
 * the guard. "BG 4.8, guard 4.8" is not a low; "BG 4.7, guard 4.8" always is.
 *
 * Every consumer of the low guard shares it — the SMB gate, the rebound/low-event tracking, the
 * learners' lowActive flag, and the circadian hard-low penalty — so that what the loop calls a
 * low is the same thing everywhere, and is the same thing the user is being shown.
 *
 * It used to allow a fixed 1.0 mg/dL, on the idea that CGM values are whole mg/dL. Smoothed values
 * (UKF and the others) are not: 85.4 mg/dL shows as 4.7 but was not below a 4.8 guard (86.4 - 1.0),
 * so a BG shown under the guard could fail to count as a low. Now it is the display rounding
 * itself: a value shows lower than the guard when it is under the guard's own displayed value by
 * more than half a display step (0.05 mmol, or 0.5 mg/dL).
 *
 * Only "below" is defined here. How far above the guard anything unlocks is each caller's own
 * business.
 */
internal fun bgBelowGuard(bgMgdl: Double, lowGuardMgdl: Double, mmol: Boolean = BgText.mmol): Boolean =
    if (mmol) bgMgdl < (Math.round(lowGuardMgdl / 18.0 * 10.0) / 10.0 - 0.05) * 18.0
    else bgMgdl < Math.round(lowGuardMgdl) - 0.5
