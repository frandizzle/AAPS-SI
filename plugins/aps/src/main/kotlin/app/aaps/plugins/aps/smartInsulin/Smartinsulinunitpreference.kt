package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.text.InputType
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Unit-aware preference for SmartInsulin.
 *
 * Stores mg/dL always. Uses fromMgdlToUnits() for display — no < 36 heuristic.
 * Dependencies passed directly — no Dagger registration needed.
 */
class SmartInsulinUnitPreference(
    ctx: Context,
    val unitKey: UnitDoublePreferenceKey,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    @StringRes private val dialogMessage: Int? = null,
    @StringRes title: Int?,
) : EditTextPreference(ctx) {

    init {
        key = unitKey.key
        dialogMessage?.let { setDialogMessage(it) }
        title?.let { dialogTitle = ctx.getString(it) }
        title?.let { this.title = ctx.getString(it) }
        isPersistent = false  // We handle persistence ourselves in persistString

        if (preferences.simpleMode && unitKey.defaultedBySM) isVisible = false
        if (preferences.apsMode && !unitKey.showInApsMode) { isVisible = false; isEnabled = false }
        if (preferences.nsclientMode && !unitKey.showInNsClientMode) { isVisible = false; isEnabled = false }
        if (preferences.pumpControlMode && !unitKey.showInPumpControlMode) { isVisible = false; isEnabled = false }
        unitKey.dependency?.let { if (!preferences.get(it)) isVisible = false }
        unitKey.negativeDependency?.let { if (preferences.get(it)) isVisible = false }

        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.setSelection(editText.length())
        }

        summary = displayString()
    }

    /** Reads stored mg/dL and converts to current display units — fresh every call. */
    private fun toDisplay(): Double =
        profileUtil.fromMgdlToUnits(preferences.get(unitKey), profileUtil.units)

    private fun displayString(): String {
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        return BigDecimal(toDisplay()).setScale(precision, RoundingMode.HALF_UP).toPlainString()
    }

    // EditTextPreference uses getText() to populate the dialog — return display value
    override fun getText(): String = displayString()

    // EditTextPreference calls setText() when user confirms — we intercept to convert & store
    override fun setText(text: String?) {
        if (text == null) return
        val numericValue = SafeParse.stringToDouble(text, unitKey.defaultValue)
        val storeMgdl = profileUtil.convertToMgdl(numericValue, profileUtil.units)
        try {
            preferenceDataStore?.putFloat(key, storeMgdl.toFloat())
                ?: sharedPreferences?.edit()?.putFloat(key, storeMgdl.toFloat())?.apply()
        } catch (_: Exception) {
            preferenceDataStore?.putString(key, storeMgdl.toString())
                ?: sharedPreferences?.edit()?.putString(key, storeMgdl.toString())?.apply()
        }
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        summary = BigDecimal(numericValue).setScale(precision, RoundingMode.HALF_UP).toPlainString()
        notifyChanged()
    }

    override fun onAttached() {
        super.onAttached()
        if (unitKey.hideParentScreenIfHidden) {
            parent?.isVisible = isVisible
            parent?.isEnabled = isEnabled
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // Don't call super — it would call setText() with persisted string
        // which would trigger our setText() and double-convert
        summary = displayString()
    }
}