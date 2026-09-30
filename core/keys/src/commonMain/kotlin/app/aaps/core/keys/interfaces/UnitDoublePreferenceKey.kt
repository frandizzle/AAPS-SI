package app.aaps.core.keys.interfaces

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
     * True when the value is known to always be stored in mg/dl, so the UI converts by the user's units
     * instead of guessing them from the magnitude. The guess treats anything under 36 as mmol, which is
     * right for a legacy target but wrong for a small mg/dl quantity such as an ISF or a BG delta.
     */
    val storedAsMgdl: Boolean get() = false

}