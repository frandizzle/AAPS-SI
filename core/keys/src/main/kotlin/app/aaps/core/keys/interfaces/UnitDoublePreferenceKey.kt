package app.aaps.core.keys.interfaces

import app.aaps.core.keys.UnitType

interface UnitDoublePreferenceKey : PreferenceKey {

    /**
     * Default value if not changed from preferences
     */
    val defaultValue: Double

    /**
     *  Minimal allowed value
     */
    val minMgdl: Int

    /**
     *  Maximal allowed value
     */
    val maxMgdl: Int

    /**
     *  The unit type, used for precision control.
     *  Defaults to MGDL for standard glucose values (0/1 decimals).
     *  Use DOUBLE or DOUBLE_2 for higher precision deltas/overrides.
     */
    override val unitType: UnitType

}