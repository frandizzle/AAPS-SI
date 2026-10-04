package app.aaps.plugins.aps.smartInsulin

/**
 * BG values as text, in the units the user has chosen. Everything inside SmartInsulin is mg/dL;
 * this is only for what people read - the learning journal, the SI tab, status lines.
 *
 * Learners write their text from plain classes with no access to the profile, so the units are
 * held here. The plugin sets [mmol] at the start of every loop cycle, before any learner runs.
 */
object BgText {

    /** BG is shown in mmol/L. Also read by [bgBelowGuard], so a low is judged in the same units. */
    @Volatile var mmol: Boolean = true

    val unit: String get() = if (mmol) "mmol" else "mg/dL"

    /** The number alone: "9.1" in mmol (with [mmolDecimals]), "164" in mg/dL. */
    fun num(mgdl: Double, mmolDecimals: Int = 1): String =
        if (mmol) "%.${mmolDecimals}f".format(mgdl / 18.0) else "%.0f".format(mgdl)

    /** A BG or a BG difference with its unit: "9.1mmol", "164mg/dL". */
    fun bg(mgdl: Double, mmolDecimals: Int = 1): String = num(mgdl, mmolDecimals) + unit

    /** Like [bg], with a "+" in front of a rise: "+1.8mmol", "-32mg/dL". */
    fun signed(mgdl: Double, mmolDecimals: Int = 1): String = (if (mgdl > 0.0) "+" else "") + bg(mgdl, mmolDecimals)
}
