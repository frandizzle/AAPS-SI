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
 * Slack on every BG-vs-low-guard comparison, in mg/dL.
 *
 * The guard is entered in mmol and stored as its mg/dL conversion (4.8 -> 86.4) while CGM values
 * arrive as whole mg/dL, so "BG 4.8, guard 4.8" on screen is really 86 vs 86.4. A strict
 * comparison calls that a low, over a gap smaller than the 0.1 mmol display step (1.8 mg/dL) and
 * smaller than the sensor resolves — with nothing in any log line able to show why.
 *
 * mmol guards in 0.1 steps convert to mg/dL values whose fractional part is a multiple of 0.2
 * capped at 0.8, so a whole-mg/dL reading DISPLAYING as level with the guard is at most 0.8 below
 * it. 1.0 covers exactly those and stops short of the next reading down, which displays as a
 * lower number and stays below.
 */
internal const val GUARD_MATCH_TOLERANCE_MGDL = 1.0

/**
 * The single definition of "BG has gone below the low guard".
 *
 * Every consumer of the low guard shares it — the SMB gate, the rebound/low-event tracking, the
 * learners' lowActive flag, and the circadian hard-low penalty — so that what the loop calls a
 * low is the same thing everywhere, and is the same thing the user is being shown. They used to
 * be six independent `<` comparisons, which meant a BG could simultaneously clear the guard for
 * dosing and register as a low event for everything else.
 *
 * Only "below" is defined here. How far above the guard anything unlocks is each caller's own
 * business.
 */
internal fun bgBelowGuard(bgMgdl: Double, lowGuardMgdl: Double): Boolean =
    bgMgdl < lowGuardMgdl - GUARD_MATCH_TOLERANCE_MGDL
