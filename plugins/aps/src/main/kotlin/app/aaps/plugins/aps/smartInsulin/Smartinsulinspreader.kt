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